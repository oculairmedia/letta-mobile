package com.letta.mobile.avatar.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.random.Random

/**
 * How the host drives the [GazeDirector]. JUSTIFIED is the product behaviour;
 * CURSOR is the bench's range check; OFF parks gaze at centre (bench sliders).
 * Source: `GazeMode` in `RiveDesktopSpike.kt`.
 */
enum class GazeDriveMode {
    JUSTIFIED,
    CURSOR,
    OFF,
}

/**
 * World the director can look at this frame. Pointer / input / timeline are
 * already in gaze units (-1..1). A null optional point makes that target
 * unavailable in the plan (the spike always had fake rects; product hosts
 * supply them later).
 */
data class GazeWorld(
    val pointer: GazePoint? = null,
    val input: GazePoint? = null,
    val timeline: GazePoint? = null,
    val mode: GazeDriveMode = GazeDriveMode.JUSTIFIED,
    /**
     * Bench: `now - lastCursorMove < 500ms`. When null, inferred from pointer
     * position changes with the same 500 ms window.
     */
    val pointerMovedRecently: Boolean? = null,
)

/** Eyes, head, and a one-tick blink pulse. Look is also packaged as a screen target. */
data class GazePose(
    val lookX: Float,
    val lookY: Float,
    val headX: Float,
    val headY: Float,
    val blink: Boolean,
    val target: GazeTarget,
) {
    val lookTarget: AvatarLookTarget.Screen
        get() = GazeMath.toScreen(GazePoint(lookX, lookY))

    companion object {
        val CENTER: GazePose = GazePose(
            lookX = 0f,
            lookY = 0f,
            headX = 0f,
            headY = 0f,
            blink = false,
            target = GazeTarget.OWN,
        )
    }
}

/**
 * Attention policy lifted from the desktop bench (`RiveDesktopSpike.kt`) —
 * the justified-plan `LaunchedEffect`, the scan `LaunchedEffect(target)`, and
 * the per-frame eye-ease / head-spring loop. Numbers are that source. The
 * host writes look + `turnX`/`turnY`; the file keeps AutoLook / plate saccades.
 *
 * Drive it with [tick] (`deltaSeconds` from the same clock as [AvatarDirector]).
 * Apply [GazePose.lookTarget] via [AvatarDirector.setLookTarget] and
 * [GazePose.headX]/[GazePose.headY] via [AvatarHeadTurn]. A large committed
 * head turn sets [GazePose.blink] for one tick (Eyes Alive).
 */
class GazeDirector(
    private val random: Random = Random.Default,
    var config: Config = Config(),
) {
    /**
     * Bench tunables. Defaults match `RiveDesktopSpike.kt` (omega 8.5 / zeta
     * 0.72 in the spike loop — not the README's older 11 / 0.5).
     */
    data class Config(
        val headLeadSeconds: Float = 0.350f,
        val eyeTauSeconds: Float = 0.25f,
        val scanTauSeconds: Float = 0.08f,
        val springOmega: Float = 8.5f,
        val springZeta: Float = 0.72f,
        val habituationDecaySeconds: Float = 4f,
        val habituationRestoreSeconds: Float = 12f,
        val cursorInterestFloor: Float = 0.3f,
        val cursorNearRadius: Float = 0.4f,
        val cursorDemandSeconds: Float = 0.5f,
        val headCommitDelta: Float = 0.15f,
        val blinkOnHeadTurn: Float = 0.4f,
        val headXScale: Float = 0.85f,
        val headYScale: Float = 0.7f,
        val eyeHeadCompensation: Float = 0.6f,
        val maxDeltaSeconds: Float = 0.1f,
    )

    var target: GazeTarget = GazeTarget.OWN
        private set

    var lastPose: GazePose = GazePose.CENTER
        private set

    private val interest = mutableMapOf<GazeTarget, Float>().also { map ->
        GazeTarget.entries.forEach { map[it] = 1f }
    }

    private var lastState: AvatarState? = null
    private var phase: PlanPhase = PlanPhase.PICK
    private var phaseRemaining: Float = 0f
    private var dwellLook: GazeLook? = null

    private var lastPointer: GazePoint? = null
    private var pointerFresh: Float = 0f

    private var scanKind: GazeTarget = GazeTarget.OWN
    private var scanX: Float = 0f
    private var scanY: Float = 0f
    private var headScan: Float = 0f
    private var scanWait: Float = 0f
    private var scanLine: Int = 0
    private var scanCursor: Float = 0f
    private var scanSpeed: Float = 0.04f
    private var scanUserIndex: Int = 0
    private var scanPhase: ScanPhase = ScanPhase.STEP

    private var eyeX: Float = 0f
    private var eyeY: Float = 0f
    private var headX: Float = 0f
    private var headY: Float = 0f
    private var headVx: Float = 0f
    private var headVy: Float = 0f
    private var headTargetX: Float = 0f
    private var headTargetY: Float = 0f
    private var lastWantX: Float = 0f
    private var lastWantY: Float = 0f
    // Spike: `wantSince = 0L`. A first-frame base of 0 commits at once (`now - 0`);
    // a >0.15 jump (cursor grab) resets the clock and waits [Config.headLeadSeconds].
    private var wantAge: Float = Float.POSITIVE_INFINITY

    /** Current habituation 0..1 (Pan et al. / Disney Research eq. 2). [GazeTarget.OWN] never decays. */
    fun interestOf(target: GazeTarget): Float = interest[target] ?: 1f

    /** Plan weight scaled by current interest — `look.weight * (0.15 + 0.85 * interest)`. */
    fun score(look: GazeLook): Float =
        look.weight * (0.15f + 0.85f * interestOf(look.target))

    /**
     * Advance attention by [deltaSeconds]. Non-positive / NaN deltas return
     * the last pose without mutating state (tick idempotence).
     */
    fun tick(deltaSeconds: Float, state: AvatarState, world: GazeWorld = GazeWorld()): GazePose {
        if (deltaSeconds.isNaN() || deltaSeconds <= 0f) return lastPose
        val dt = deltaSeconds.coerceAtMost(config.maxDeltaSeconds)
        if (world.mode == GazeDriveMode.OFF) {
            lastPose = GazePose.CENTER.copy(target = target)
            return lastPose
        }
        notePointer(world.pointer, dt)
        if (world.mode == GazeDriveMode.JUSTIFIED) {
            tickPlan(dt, state, world)
        } else {
            target = GazeTarget.CURSOR
            lastState = state
        }
        if (target != scanKind) resetScan(target)
        val near = cursorDemandsLook(state, world)
        tickHabituation(dt, near)
        if (world.mode == GazeDriveMode.JUSTIFIED) tickScan(dt)
        val pose = tickMotion(dt, world, near)
        lastPose = pose
        return pose
    }

    // --- plan (spike `LaunchedEffect(gazeMode, current)`) ----------------------

    private fun tickPlan(dt: Float, state: AvatarState, world: GazeWorld) {
        if (state != lastState) {
            lastState = state
            target = GazeTarget.OWN
            phase = PlanPhase.PICK
            phaseRemaining = 0f
            dwellLook = null
        }
        phaseRemaining -= dt
        var steps = 0
        while (phaseRemaining <= 0f && steps < 4) {
            steps++
            when (phase) {
                PlanPhase.PICK -> beginDwell(state, world)
                PlanPhase.DWELL -> beginGap()
                PlanPhase.GAP -> phase = PlanPhase.PICK
            }
        }
    }

    private fun beginDwell(state: AvatarState, world: GazeWorld) {
        val look = pickLook(GazePlan.forState(state), world)
        target = look.target
        dwellLook = look
        phase = PlanPhase.DWELL
        phaseRemaining = pickRange(look.dwellSeconds)
    }

    private fun beginGap() {
        target = GazeTarget.OWN
        phase = PlanPhase.GAP
        phaseRemaining = dwellLook?.let { pickRange(it.gapSeconds) } ?: 0.5f
    }

    private fun pickLook(plan: List<GazeLook>, world: GazeWorld): GazeLook {
        val available = plan.filter { available(it.target, world) }
        val use = if (available.isEmpty()) GazePlan.fallback else available
        var total = 0f
        val scored = FloatArray(use.size) { i ->
            val s = score(use[i])
            total += s
            s
        }
        if (total <= 0f) return use.first()
        var pick = random.nextFloat() * total
        for (i in use.indices) {
            pick -= scored[i]
            if (pick < 0f) return use[i]
        }
        return use.last()
    }

    private fun available(target: GazeTarget, world: GazeWorld): Boolean = when (target) {
        GazeTarget.OWN, GazeTarget.USER -> true
        GazeTarget.CURSOR -> world.pointer != null
        GazeTarget.INPUT -> world.input != null
        GazeTarget.TIMELINE -> world.timeline != null
    }

    // --- pointer demand (spike `near`) ----------------------------------------

    private fun notePointer(pointer: GazePoint?, dt: Float) {
        if (pointer == null) {
            lastPointer = null
            pointerFresh = 0f
            return
        }
        val previous = lastPointer
        lastPointer = pointer
        val moved = previous == null || previous.x != pointer.x || previous.y != pointer.y
        pointerFresh = if (moved) config.cursorDemandSeconds else (pointerFresh - dt).coerceAtLeast(0f)
    }

    private fun cursorDemandsLook(state: AvatarState, world: GazeWorld): Boolean {
        if (world.mode == GazeDriveMode.CURSOR) return world.pointer != null
        val pointer = world.pointer ?: return false
        if (state == AvatarState.SLEEPING) return false
        if (interestOf(GazeTarget.CURSOR) <= config.cursorInterestFloor) return false
        val movedRecently = world.pointerMovedRecently ?: (pointerFresh > 0f)
        if (!movedRecently) return false
        return hypot(pointer.x, pointer.y) < config.cursorNearRadius
    }

    // --- habituation (spike: -dt/4 attended non-OWN, +dt/12 otherwise) --------

    private fun tickHabituation(dt: Float, near: Boolean) {
        val attended = if (near) GazeTarget.CURSOR else target
        for (t in GazeTarget.entries) {
            val current = interest[t] ?: 1f
            val next = if (t == attended && t != GazeTarget.OWN) {
                current - dt / config.habituationDecaySeconds
            } else {
                current + dt / config.habituationRestoreSeconds
            }
            interest[t] = next.coerceIn(0f, 1f)
        }
    }

    // --- scans (spike `LaunchedEffect(target)`) --------------------------------

    private fun resetScan(kind: GazeTarget) {
        scanKind = kind
        scanX = 0f
        scanY = 0f
        headScan = 0f
        scanWait = 0f
        scanLine = 0
        scanUserIndex = 0
        scanPhase = ScanPhase.STEP
        when (kind) {
            GazeTarget.TIMELINE -> {
                scanCursor = -0.22f
                applyTimeline()
                scanWait = pickRange(0.180f..0.340f)
            }
            GazeTarget.INPUT -> {
                scanCursor = -0.15f
                scanSpeed = pickRange(0.03f..0.06f)
                applyInput()
                scanWait = pickRange(0.080f..0.160f)
            }
            GazeTarget.USER -> {
                applyUser()
                scanWait = pickRange(0.100f..0.500f)
            }
            else -> Unit
        }
    }

    private fun tickScan(dt: Float) {
        if (scanKind != GazeTarget.TIMELINE && scanKind != GazeTarget.INPUT && scanKind != GazeTarget.USER) {
            return
        }
        scanWait -= dt
        var steps = 0
        while (scanWait <= 0f && steps < 8) {
            steps++
            stepScan()
        }
    }

    private fun stepScan() {
        when (scanKind) {
            GazeTarget.USER -> {
                scanUserIndex = (scanUserIndex + 1 + random.nextInt(2)) % USER_TRIANGLE.size
                applyUser()
                scanWait = pickRange(0.100f..0.500f)
            }
            GazeTarget.TIMELINE -> stepTimeline()
            GazeTarget.INPUT -> stepInput()
            else -> scanWait = 1f
        }
    }

    private fun stepTimeline() {
        if (scanPhase == ScanPhase.HOLD) {
            scanLine = (scanLine + 1) % 3
            scanCursor = -0.22f
            scanPhase = ScanPhase.STEP
            applyTimeline()
            scanWait = pickRange(0.180f..0.340f)
            return
        }
        scanCursor += 0.05f + random.nextFloat() * 0.05f
        if (scanCursor >= 0.22f) {
            scanPhase = ScanPhase.HOLD
            scanWait = pickRange(0.120f..0.260f)
            return
        }
        applyTimeline()
        scanWait = pickRange(0.180f..0.340f)
    }

    private fun stepInput() {
        if (scanPhase == ScanPhase.HOLD) {
            scanCursor = -0.15f
            scanSpeed = pickRange(0.03f..0.06f)
            scanPhase = ScanPhase.STEP
            applyInput()
            scanWait = pickRange(0.080f..0.160f)
            return
        }
        if (scanPhase == ScanPhase.PAUSE) {
            scanPhase = ScanPhase.STEP
            scanCursor += scanSpeed * 0.4f
            finishInputAdvance()
            return
        }
        scanCursor += scanSpeed * 0.4f
        finishInputAdvance()
    }

    private fun finishInputAdvance() {
        if (scanCursor >= 0.15f) {
            scanPhase = ScanPhase.HOLD
            scanWait = pickRange(0.100f..0.300f)
            return
        }
        applyInput()
        scanWait = pickRange(0.080f..0.160f)
        if (random.nextFloat() < 0.06f) {
            scanPhase = ScanPhase.PAUSE
            scanWait = pickRange(0.300f..0.900f)
        }
    }

    private fun applyTimeline() {
        scanX = scanCursor
        scanY = scanLine * 0.05f
        headScan = scanCursor * 0.5f
    }

    private fun applyInput() {
        scanX = scanCursor
        scanY = 0f
        headScan = 0f
    }

    private fun applyUser() {
        val point = USER_TRIANGLE[scanUserIndex]
        scanX = point.x
        scanY = point.y
        headScan = 0f
    }

    // --- eyes ease, head spring (spike per-frame loop) ------------------------

    private fun tickMotion(dt: Float, world: GazeWorld, near: Boolean): GazePose {
        val base = aimBase(world, near)
        val useScan = !near && world.mode != GazeDriveMode.CURSOR
        val sx = if (useScan) scanX else 0f
        val sy = if (useScan) scanY else 0f
        val wantX = (base.x + sx).coerceIn(-1f, 1f)
        val wantY = (base.y + sy).coerceIn(-1f, 1f)
        var blink = false
        if (abs(base.x - lastWantX) > config.headCommitDelta || abs(base.y - lastWantY) > config.headCommitDelta) {
            lastWantX = base.x
            lastWantY = base.y
            wantAge = 0f
        } else {
            wantAge += dt
        }
        if (wantAge > config.headLeadSeconds) {
            // Spike always adds headScan (0 unless TIMELINE); eyes drop scan when near/cursor.
            val nextX = base.x * config.headXScale + headScan
            val nextY = base.y * config.headYScale
            if (abs(nextX - headTargetX) > config.blinkOnHeadTurn) blink = true
            headTargetX = nextX
            headTargetY = nextY
        }
        val tau = if (target == GazeTarget.TIMELINE || target == GazeTarget.INPUT) {
            config.scanTauSeconds
        } else {
            config.eyeTauSeconds
        }
        val k = 1f - exp(-dt / tau)
        val ex = wantX - headX * config.eyeHeadCompensation
        val ey = wantY - headY * config.eyeHeadCompensation
        eyeX += (ex - eyeX) * k
        eyeY += (ey - eyeY) * k
        val omega = config.springOmega
        val zeta = config.springZeta
        headVx += ((headTargetX - headX) * omega * omega - 2f * zeta * omega * headVx) * dt
        headX += headVx * dt
        headVy += ((headTargetY - headY) * omega * omega - 2f * zeta * omega * headVy) * dt
        headY += headVy * dt
        return GazePose(
            lookX = eyeX.coerceIn(-1f, 1f),
            lookY = eyeY.coerceIn(-1f, 1f),
            headX = headX.coerceIn(-1f, 1f),
            headY = headY.coerceIn(-1f, 1f),
            blink = blink,
            target = if (near) GazeTarget.CURSOR else target,
        )
    }

    private fun aimBase(world: GazeWorld, near: Boolean): GazePoint {
        val pointer = world.pointer
        if (world.mode == GazeDriveMode.CURSOR || near) {
            return (pointer ?: GazePoint(0f, 0f)).coerce()
        }
        return when (target) {
            GazeTarget.CURSOR -> (pointer ?: GazePoint(0f, 0f)).coerce()
            GazeTarget.INPUT -> (world.input ?: GazePoint(0f, 0f)).coerce()
            GazeTarget.TIMELINE -> (world.timeline ?: GazePoint(0f, 0f)).coerce()
            GazeTarget.USER, GazeTarget.OWN -> GazePoint(0f, 0f)
        }
    }

    private fun pickRange(range: ClosedFloatingPointRange<Float>): Float {
        val span = range.endInclusive - range.start
        return (range.start + random.nextFloat() * span).coerceAtLeast(0f)
    }

    private enum class PlanPhase { PICK, DWELL, GAP }

    private enum class ScanPhase { STEP, HOLD, PAUSE }

    private companion object {
        /** Spike `triangle`: other person's eyes and nose (Disney Research). */
        val USER_TRIANGLE: List<GazePoint> = listOf(
            GazePoint(-0.05f, -0.04f),
            GazePoint(0.05f, -0.04f),
            GazePoint(0f, 0.05f),
        )
    }
}
