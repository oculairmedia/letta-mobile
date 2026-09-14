package com.letta.mobile.avatar.core

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.random.Random

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
    var config: GazeDirectorConfig = GazeDirectorConfig(),
) {

    var target: GazeTarget = GazeTarget.OWN
        private set

    var lastPose: GazePose = GazePose.CENTER
        private set

    private val interest = mutableMapOf<GazeTarget, Float>().also { map ->
        GazeTarget.entries.forEach { map[it] = 1f }
    }

    private var lastState: AvatarState? = null
    /** Where an OWN / AWAY dwell parks the eyes (picked per dwell); a PEER dwell's chosen peer. */
    private var asidePoint: GazePoint = GazePoint(0f, 0f)
    private var peerIndex: Int = 0
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
    // a >0.15 jump (cursor grab) resets the clock and waits [GazeDirectorConfig.headLeadSeconds].
    private var wantAge: Float = Float.POSITIVE_INFINITY
    private var stepSeconds: Float = 0f
    private var cursorNear: Boolean = false
    private var pulseBlink: Boolean = false

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
        stepSeconds = deltaSeconds.coerceAtMost(config.maxDeltaSeconds)
        if (world.mode == GazeDriveMode.OFF) {
            lastPose = GazePose.CENTER.copy(target = target)
            return lastPose
        }
        notePointer(world.pointer)
        if (world.mode == GazeDriveMode.JUSTIFIED) {
            tickPlan(state, world)
        } else {
            target = GazeTarget.CURSOR
            lastState = state
        }
        if (target != scanKind) resetScan(target)
        cursorNear = cursorDemandsLook(state, world)
        tickHabituation()
        if (world.mode == GazeDriveMode.JUSTIFIED) tickScan()
        val pose = tickMotion(world)
        lastPose = pose
        return pose
    }

    // --- plan (spike `LaunchedEffect(gazeMode, current)`) ----------------------

    private fun tickPlan(state: AvatarState, world: GazeWorld) {
        if (state != lastState) {
            lastState = state
            target = GazeTarget.OWN
            phase = PlanPhase.PICK
            phaseRemaining = 0f
            dwellLook = null
        }
        phaseRemaining -= stepSeconds
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
        when (look.target) {
            GazeTarget.AWAY -> asidePoint = pickAside(config.awayReach)
            // Own thoughts mostly wander off-axis too; sometimes they rest at centre.
            GazeTarget.OWN -> asidePoint =
                if (random.nextFloat() < config.ownAsideChance) pickAside(config.ownReach) else GazePoint(0f, 0f)
            GazeTarget.PEER -> peerIndex = if (world.peers.isEmpty()) 0 else random.nextInt(world.peers.size)
            else -> Unit
        }
    }

    /** A point off to one side: |x| in [reach], a little above or below the line of sight. */
    private fun pickAside(reach: ClosedFloatingPointRange<Float>): GazePoint {
        val side = if (random.nextFloat() < 0.5f) -1f else 1f
        return GazePoint(side * pickRange(reach), pickRange(config.asideVertical))
    }

    private fun beginGap() {
        // The gap keeps the last parked point when the dwell was already aside: eyes rest where they
        // were, they do not snap to centre between every look.
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
        GazeTarget.OWN, GazeTarget.USER, GazeTarget.AWAY -> true
        GazeTarget.CURSOR -> world.pointer != null
        GazeTarget.INPUT -> world.input != null
        GazeTarget.TIMELINE -> world.timeline != null
        GazeTarget.PEER -> world.peers.isNotEmpty()
    }

    // --- pointer demand (spike `near`) ----------------------------------------

    private fun notePointer(pointer: GazePoint?) {
        if (pointer == null) {
            lastPointer = null
            pointerFresh = 0f
            return
        }
        val previous = lastPointer
        lastPointer = pointer
        val moved = previous == null || previous.x != pointer.x || previous.y != pointer.y
        pointerFresh = if (moved) config.cursorDemandSeconds else (pointerFresh - stepSeconds).coerceAtLeast(0f)
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

    private fun tickHabituation() {
        val attended = if (cursorNear) GazeTarget.CURSOR else target
        for (t in GazeTarget.entries) {
            val current = interest[t] ?: 1f
            val next = if (t == attended && t != GazeTarget.OWN) {
                current - stepSeconds / config.habituationDecaySeconds
            } else {
                current + stepSeconds / config.habituationRestoreSeconds
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

    private fun tickScan() {
        if (!hasScanOverlay(scanKind)) return
        scanWait -= stepSeconds
        var steps = 0
        while (scanWait <= 0f && steps < 8) {
            steps++
            stepScan()
        }
    }

    private fun hasScanOverlay(kind: GazeTarget): Boolean = when (kind) {
        GazeTarget.TIMELINE, GazeTarget.INPUT, GazeTarget.USER -> true
        else -> false
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

    private fun tickMotion(world: GazeWorld): GazePose {
        val base = aimBase(world)
        val want = wantLook(base, world.mode)
        noteWantJump(base)
        pulseBlink = commitHeadIfLed(base)
        easeEyes(want)
        stepHeadSpring()
        return finishedPose()
    }

    private fun wantLook(base: GazePoint, mode: GazeDriveMode): GazePoint {
        val useScan = !cursorNear && mode != GazeDriveMode.CURSOR
        val sx = if (useScan) scanX else 0f
        val sy = if (useScan) scanY else 0f
        return GazePoint((base.x + sx).coerceIn(-1f, 1f), (base.y + sy).coerceIn(-1f, 1f))
    }

    private fun noteWantJump(base: GazePoint) {
        if (wantJumped(base)) {
            lastWantX = base.x
            lastWantY = base.y
            wantAge = 0f
        } else {
            wantAge += stepSeconds
        }
    }

    private fun wantJumped(base: GazePoint): Boolean {
        if (abs(base.x - lastWantX) > config.headCommitDelta) return true
        return abs(base.y - lastWantY) > config.headCommitDelta
    }

    private fun commitHeadIfLed(base: GazePoint): Boolean {
        if (wantAge <= config.headLeadSeconds) return false
        // Spike always adds headScan (0 unless TIMELINE); eyes drop scan when near/cursor.
        val nextX = base.x * config.headXScale + headScan
        val nextY = base.y * config.headYScale
        val blink = abs(nextX - headTargetX) > config.blinkOnHeadTurn
        headTargetX = nextX
        headTargetY = nextY
        return blink
    }

    private fun easeEyes(want: GazePoint) {
        val tau = when (target) {
            GazeTarget.TIMELINE, GazeTarget.INPUT -> config.scanTauSeconds
            else -> config.eyeTauSeconds
        }
        val k = 1f - exp(-stepSeconds / tau)
        val ex = want.x - headX * config.eyeHeadCompensation
        val ey = want.y - headY * config.eyeHeadCompensation
        eyeX += (ex - eyeX) * k
        eyeY += (ey - eyeY) * k
    }

    private fun stepHeadSpring() {
        val omega = config.springOmega
        val zeta = config.springZeta
        val dt = stepSeconds
        headVx += ((headTargetX - headX) * omega * omega - 2f * zeta * omega * headVx) * dt
        headX += headVx * dt
        headVy += ((headTargetY - headY) * omega * omega - 2f * zeta * omega * headVy) * dt
        headY += headVy * dt
    }

    private fun finishedPose(): GazePose {
        // TODO(letta-mobile-kkjyd): SPEC §10.4 combined gaze containment — do not
        // clamp look+saccade to the card here. Unattenuated H+N can put the
        // failed X ~2.54 artboard px outside; λ attenuation is that bead (after
        // 24gbf). coerceIn(-1, 1) is gaze-unit range only, not card clearance.
        return GazePose(
            look = GazePoint(eyeX, eyeY).coerce(),
            head = GazePoint(headX, headY).coerce(),
            blink = pulseBlink,
            target = if (cursorNear) GazeTarget.CURSOR else target,
        )
    }

    private fun aimBase(world: GazeWorld): GazePoint {
        val pointer = world.pointer
        if (world.mode == GazeDriveMode.CURSOR || cursorNear) {
            return (pointer ?: GazePoint(0f, 0f)).coerce()
        }
        return when (target) {
            GazeTarget.CURSOR -> (pointer ?: GazePoint(0f, 0f)).coerce()
            GazeTarget.INPUT -> (world.input ?: GazePoint(0f, 0f)).coerce()
            GazeTarget.TIMELINE -> (world.timeline ?: GazePoint(0f, 0f)).coerce()
            GazeTarget.PEER -> (world.peers.getOrNull(peerIndex) ?: asidePoint).coerce()
            GazeTarget.OWN, GazeTarget.AWAY -> asidePoint.coerce()
            GazeTarget.USER -> GazePoint(0f, 0f)
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
