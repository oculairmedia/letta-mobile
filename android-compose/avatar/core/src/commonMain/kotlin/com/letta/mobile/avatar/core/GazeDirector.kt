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
    /** Reduced motion this tick: one held look, no plan, no scans, no asides. */
    private var still: Boolean = false
    /** The last planned (non-pointer) aim, where a gap after a look at a place rests. */
    private var lastPlanAim: GazePoint = GazePoint(0f, 0f)

    /** How long each attention point has been on screen (te3n1.2), for its interest decay. */
    private val attentionClock = AttentionClock()
    /** A reaction's bias, honoured at the next tick ([cue]). */
    private var pendingCue: GazeCue? = null
    /** The dwell running now was cued: it holds through a state flip instead of restarting the plan. */
    private var cuedDwell: Boolean = false
    /** The look a cued glance hands over to once it ends (SENT: back to you). */
    private var followUp: GazeTarget? = null
    /** Commit the head this tick, without waiting out the lead, and blink (APPROVAL_ASK's head turn). */
    private var snapHead: Boolean = false
    /** A seat hand-off's eye lead ([lead]): where, and for how much longer. */
    private var leadPoint: GazePoint = GazePoint(0f, 0f)
    private var leadRemaining: Float = 0f

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
        attentionClock.advance(world.attention, stepSeconds)
        still = world.mode == GazeDriveMode.JUSTIFIED && world.reducedMotion
        chooseTarget(state, world)
        if (target != scanKind) resetScan(target)
        cursorNear = cursorDemandsLook(state, world)
        tickHabituation()
        advanceScan(world.mode)
        val pose = tickMotion(world)
        leadRemaining = (leadRemaining - stepSeconds).coerceAtLeast(0f)
        lastPose = pose
        return pose
    }

    /**
     * Bias the next look by a reaction (te3n1.2): [GazeCue.APPROVAL_ASK] snaps to the approval card
     * with a head turn and a blink; [GazeCue.SENT] glances at the newest message, then back at you.
     * Honoured at the next justified tick; reduced motion keeps its one held look instead.
     */
    fun cue(cue: GazeCue) {
        pendingCue = cue
    }

    /**
     * A seat hand-off (te3n1.2): the eyes go to [toward] (gaze units, from where the mascot stands
     * now) for [seconds] before the body hops, and the head follows after its usual lead. The plan
     * keeps running underneath, so when the lead ends the gaze carries on where it was - nothing
     * resets on arrival. Ignored under reduced motion (the hop snaps; the look holds).
     */
    fun lead(toward: GazePoint, seconds: Float) {
        leadPoint = toward.coerce()
        leadRemaining = seconds.coerceAtLeast(0f)
    }

    /** True while a seat hand-off's eye lead owns the look. */
    private fun leading(mode: GazeDriveMode): Boolean = leadRemaining > 0f && planDrives(mode)

    /** The justified plan owns the look this tick (not the bench cursor, not reduced motion's held look). */
    private fun planDrives(mode: GazeDriveMode): Boolean = !still && mode == GazeDriveMode.JUSTIFIED

    /** Reduced motion holds one look; the product plan picks justified looks; the bench follows the cursor. */
    private fun chooseTarget(state: AvatarState, world: GazeWorld) {
        when {
            still -> holdStill(state, world)
            world.mode == GazeDriveMode.JUSTIFIED -> tickPlan(state, world)
            else -> {
                target = GazeTarget.CURSOR
                lastState = state
            }
        }
    }

    // --- plan (spike `LaunchedEffect(gazeMode, current)`) ----------------------

    private fun tickPlan(state: AvatarState, world: GazeWorld) {
        noteStateFlip(state)
        beginCue(world)
        phaseRemaining -= stepSeconds
        var steps = 0
        while (phaseRemaining <= 0f && steps < 4) {
            steps++
            stepPhase(state, world)
        }
    }

    /**
     * A new state restarts the plan, unless a cued look holds through the flip its own event
     * caused (an approval turns the state to WAITING_INPUT).
     */
    private fun noteStateFlip(state: AvatarState) {
        if (state == lastState) return
        lastState = state
        if (!cuedDwell) restartPlan()
    }

    /** One PICK -> DWELL -> GAP step of the plan. */
    private fun stepPhase(state: AvatarState, world: GazeWorld) {
        when (phase) {
            PlanPhase.PICK -> beginDwell(state, world)
            PlanPhase.DWELL -> beginGap()
            PlanPhase.GAP -> phase = PlanPhase.PICK
        }
    }

    private fun restartPlan() {
        target = GazeTarget.OWN
        phase = PlanPhase.PICK
        phaseRemaining = 0f
        dwellLook = null
    }

    /** Turns a pending [GazeCue] into a cued dwell, now. */
    private fun beginCue(world: GazeWorld) {
        val cue = pendingCue ?: return
        pendingCue = null
        when (cue) {
            GazeCue.APPROVAL_ASK -> cueApproval(world)
            GazeCue.SENT -> cueSent(world)
        }
    }

    /** APPROVAL_ASK: the card (or you, with no card), with the head turned at once. */
    private fun cueApproval(world: GazeWorld) {
        val look = if (available(GazeTarget.APPROVAL, world)) GazeTarget.APPROVAL else GazeTarget.USER
        holdCued(look, config.cueApprovalSeconds)
        snapHead = true
    }

    /** SENT: a glance at the newest message, then back at you; straight to you when there is nothing to glance at. */
    private fun cueSent(world: GazeWorld) {
        val glance = sentGlance(world)
        if (glance == null) {
            holdCued(GazeTarget.USER, config.cueReturnSeconds)
            followUp = null
            return
        }
        holdCued(glance, config.cueGlanceSeconds)
        followUp = GazeTarget.USER
    }

    /** What a sent prompt glances at: the newest message, else the timeline, else nothing. */
    private fun sentGlance(world: GazeWorld): GazeTarget? = when {
        available(GazeTarget.MESSAGE, world) -> GazeTarget.MESSAGE
        available(GazeTarget.TIMELINE, world) -> GazeTarget.TIMELINE
        else -> null
    }

    /** A dwell on [look] for [seconds] that the plan did not pick and a state flip does not cut short. */
    private fun holdCued(look: GazeTarget, seconds: ClosedFloatingPointRange<Float>) {
        target = look
        dwellLook = GazeLook(look, 1, seconds, CUED_GAP_SECONDS)
        phase = PlanPhase.DWELL
        phaseRemaining = pickRange(seconds)
        cuedDwell = true
    }

    private fun beginDwell(state: AvatarState, world: GazeWorld) {
        if (beginFollowUp()) return
        val look = pickLook(GazePlan.forState(state), world)
        target = look.target
        dwellLook = look
        phase = PlanPhase.DWELL
        phaseRemaining = pickRange(look.dwellSeconds)
        placeDwell(look, state, world)
    }

    /** A cued glance's hand-over (SENT's look back at you) takes the next dwell; false when there is none. */
    private fun beginFollowUp(): Boolean {
        val next = followUp ?: return false
        followUp = null
        holdCued(next, config.cueReturnSeconds)
        return true
    }

    /** Where an aside or a peer look parks the eyes for this dwell. */
    private fun placeDwell(look: GazeLook, state: AvatarState, world: GazeWorld) {
        val working = ContentGaze.isAtWork(state)
        when (look.target) {
            GazeTarget.AWAY -> asidePoint = pickAside(config.awayReach, working)
            // Own thoughts mostly wander off-axis too; sometimes they rest at centre.
            GazeTarget.OWN -> asidePoint =
                if (random.nextFloat() < config.ownAsideChance) pickAside(config.ownReach, working) else CENTER
            GazeTarget.PEER -> peerIndex = if (world.peers.isEmpty()) 0 else random.nextInt(world.peers.size)
            else -> Unit
        }
    }

    /**
     * A point off to one side, biased toward the content (right / up; see [ContentGaze.aside]):
     * at work always right and up, at rest mostly so and never far left.
     */
    private fun pickAside(reach: ClosedFloatingPointRange<Float>, atWork: Boolean): GazePoint =
        ContentGaze.aside(
            reach = reach,
            atWork = atWork,
            draws = ContentGaze.AsideDraws(
                side = random.nextFloat(),
                reach = random.nextFloat(),
                up = random.nextFloat(),
                height = random.nextFloat(),
            ),
        )

    /**
     * Reduced motion: one held look per state, no dwell timers. At work the eyes rest on the
     * content's live edge; at rest on the input when there is one; otherwise straight ahead.
     */
    private fun holdStill(state: AvatarState, world: GazeWorld) {
        // Forget the plan (and any cue) so it restarts cleanly if motion comes back.
        lastState = null
        asidePoint = CENTER
        pendingCue = null
        followUp = null
        cuedDwell = false
        target = stillTarget(state, world)
    }

    /**
     * The one held look: what the state is about when a surface published it (the approval card
     * while it asks, the running tool while it works, the caret while you type), else the content
     * at work, the input at rest, or straight ahead.
     */
    private fun stillTarget(state: AvatarState, world: GazeWorld): GazeTarget {
        val atWork = ContentGaze.isAtWork(state)
        val preferred = when {
            state == AvatarState.WAITING_INPUT -> GazeTarget.APPROVAL
            state == AvatarState.WORKING -> GazeTarget.TOOL
            atWork -> GazeTarget.TIMELINE
            else -> GazeTarget.CARET
        }
        return when {
            available(preferred, world) -> preferred
            atWork && world.timeline != null -> GazeTarget.TIMELINE
            !atWork && world.input != null -> GazeTarget.INPUT
            else -> GazeTarget.OWN
        }
    }

    private fun beginGap() {
        cuedDwell = false
        // The gap keeps the last parked point when the dwell was already aside: eyes rest where they
        // were, they do not snap to centre between every look. After a look at a place (the reply,
        // the input, a peer, you) the eyes rest on that place rather than on a stale aside.
        if (dwellLook?.target?.isPlace() == true) asidePoint = lastPlanAim
        target = GazeTarget.OWN
        phase = PlanPhase.GAP
        phaseRemaining = dwellLook?.let { pickRange(it.gapSeconds) } ?: 0.5f
    }

    private fun pickLook(plan: List<GazeLook>, world: GazeWorld): GazeLook {
        val available = plan.filter { available(it.target, world) }
        val use = if (available.isEmpty()) GazePlan.fallback else available
        var total = 0f
        val scored = FloatArray(use.size) { i ->
            val s = scoreIn(use[i], world)
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

    /** [score] times the pull of the attention point the look aims at, as it has aged ([AttentionInterest]). */
    private fun scoreIn(look: GazeLook, world: GazeWorld): Float {
        val point = attentionPoint(look.target, world) ?: return score(look)
        return score(look) * attentionClock.interestOf(point)
    }

    private fun available(target: GazeTarget, world: GazeWorld): Boolean = when (target) {
        GazeTarget.OWN, GazeTarget.USER, GazeTarget.AWAY -> true
        GazeTarget.CURSOR -> world.pointer != null
        GazeTarget.INPUT -> world.input != null
        GazeTarget.TIMELINE -> world.timeline != null
        GazeTarget.PEER -> world.peers.isNotEmpty()
        GazeTarget.CARET, GazeTarget.MESSAGE, GazeTarget.APPROVAL, GazeTarget.TOOL -> attentionPoint(target, world) != null
    }

    /** The published point [target] aims at, or null (not an attention target, or nothing published). */
    private fun attentionPoint(target: GazeTarget, world: GazeWorld): AttentionPoint? {
        val kind = target.attentionKind() ?: return null
        return world.attentionOf(kind)
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
        if (state.eyesClosed) return false
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
            // The newest message reads like the timeline's live edge; the caret scans like the field.
            GazeTarget.TIMELINE, GazeTarget.MESSAGE -> {
                scanCursor = TIMELINE_SCAN_START
                applyTimeline()
                scanWait = pickRange(0.180f..0.340f)
            }
            GazeTarget.INPUT, GazeTarget.CARET -> {
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

    private fun advanceScan(mode: GazeDriveMode) {
        if (still) clearScan() else if (mode == GazeDriveMode.JUSTIFIED) tickScan()
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

    private fun hasScanOverlay(kind: GazeTarget): Boolean = kind == GazeTarget.USER || kind.readsAsScan()

    /** Targets the eyes read across in quick fixations (text being written or typed), rather than hold. */
    private fun GazeTarget.readsAsScan(): Boolean = when (this) {
        GazeTarget.TIMELINE, GazeTarget.MESSAGE, GazeTarget.INPUT, GazeTarget.CARET -> true
        else -> false
    }

    private fun stepScan() {
        when (scanKind) {
            GazeTarget.USER -> {
                scanUserIndex = (scanUserIndex + 1 + random.nextInt(2)) % USER_TRIANGLE.size
                applyUser()
                scanWait = pickRange(0.100f..0.500f)
            }
            GazeTarget.TIMELINE, GazeTarget.MESSAGE -> stepTimeline()
            GazeTarget.INPUT, GazeTarget.CARET -> stepInput()
            else -> scanWait = 1f
        }
    }

    private fun stepTimeline() {
        if (scanPhase == ScanPhase.HOLD) {
            scanLine = (scanLine + 1) % 3
            scanCursor = TIMELINE_SCAN_START
            scanPhase = ScanPhase.STEP
            applyTimeline()
            scanWait = pickRange(0.180f..0.340f)
            return
        }
        scanCursor += 0.05f + random.nextFloat() * 0.05f
        if (scanCursor >= TIMELINE_SCAN_END) {
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

    /**
     * Reading the reply as it is written: each sweep runs left to right from just before the live
     * edge, and successive lines climb (a reversed list grows upward), so the read stays up / right.
     */
    private fun applyTimeline() {
        scanX = scanCursor
        scanY = -scanLine * TIMELINE_LINE_STEP
        headScan = scanCursor * 0.5f
    }

    /** Reduced motion: the target holds still, no saccades on top of it. */
    private fun clearScan() {
        scanX = 0f
        scanY = 0f
        headScan = 0f
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
        val want = wantLook(base, world)
        noteWantJump(base)
        pulseBlink = commitHeadIfLed(base)
        easeEyes(want, world.mode)
        stepHeadSpring()
        return finishedPose()
    }

    /**
     * The base aim plus this tick's scan saccade, held inside the plate (SPEC §10.4, [GazeContainment]):
     * the intent first, the saccade attenuated before the intent is.
     */
    private fun wantLook(base: GazePoint, world: GazeWorld): GazePoint {
        val mode = world.mode
        // A look the pointer or a hand-off lead owns is followed as is, with no scan on top.
        val useScan = !pointerDemands(mode)
        val host = contained(base)
        val lambda = if (useScan) GazeContainment.noiseScale(host, scanX, scanY) else 0f
        val want = GazePoint((host.x + lambda * scanX).coerceIn(-1f, 1f), (host.y + lambda * scanY).coerceIn(-1f, 1f))
        return if (pointerDemands(mode)) want else constrained(want)
    }

    /** [point] as is when it fits the plate, else scaled in by the greatest κ that does (rare: extremes only). */
    private fun contained(point: GazePoint): GazePoint {
        val kappa = GazeContainment.hostScale(point)
        return if (kappa >= 1f) point else GazePoint(point.x * kappa, point.y * kappa)
    }

    /**
     * A look the user's pointer demands, or a seat hand-off's lead, is followed as is; every planned
     * look stays toward the content.
     */
    private fun pointerDemands(mode: GazeDriveMode): Boolean = pointerLook(mode) || leading(mode)

    /** The bench's cursor mode, or a pointer moving near enough to demand a look. */
    private fun pointerLook(mode: GazeDriveMode): Boolean = mode == GazeDriveMode.CURSOR || cursorNear

    private fun constrained(point: GazePoint): GazePoint =
        ContentGaze.constrain(point)

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
        // A cued snap (APPROVAL_ASK) turns the head at once, with a blink; otherwise it waits out the lead.
        val snap = snapHead
        snapHead = false
        if (wantAge <= config.headLeadSeconds && !snap) return false
        // Spike always adds headScan (0 unless TIMELINE); eyes drop scan when near/cursor.
        val nextX = base.x * config.headXScale + headScan
        val nextY = base.y * config.headYScale
        val blink = abs(nextX - headTargetX) > config.blinkOnHeadTurn
        headTargetX = nextX
        headTargetY = nextY
        return blink || snap
    }

    private fun easeEyes(want: GazePoint, mode: GazeDriveMode) {
        // Scans snap between fixations; a held (reduced-motion) look always eases gently.
        val scanning = !still && target.readsAsScan()
        val tau = if (scanning) config.scanTauSeconds else config.eyeTauSeconds
        val k = 1f - exp(-stepSeconds / tau)
        val ex = want.x - headX * config.eyeHeadCompensation
        val ey = want.y - headY * config.eyeHeadCompensation
        eyeX += (ex - eyeX) * k
        eyeY += (ey - eyeY) * k
        // Head compensation can swing the eyes past the target while the head settles; a planned
        // look still never reads as glancing left (the pointer, when it demands, is followed as is).
        if (!pointerDemands(mode)) eyeX = eyeX.coerceAtLeast(ContentGaze.MAX_LEFT_X)
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
        // SPEC §10.4 (letta-mobile-kkjyd): the eased eyes, head compensation included, stay inside the plate too.
        return GazePose(
            look = contained(GazePoint(eyeX, eyeY).coerce()),
            head = GazePoint(headX, headY).coerce(),
            blink = pulseBlink,
            target = if (cursorNear) GazeTarget.CURSOR else target,
        )
    }

    private fun aimBase(world: GazeWorld): GazePoint {
        if (leading(world.mode)) return leadPoint
        if (pointerDemands(world.mode)) return (world.pointer ?: CENTER).coerce()
        val aim = constrained(planAim(world) ?: CENTER)
        lastPlanAim = aim
        return aim
    }

    /** Where the current plan target is this frame; null falls back to centre. */
    private fun planAim(world: GazeWorld): GazePoint? = when (target) {
        GazeTarget.CURSOR -> world.pointer
        // While a caret is published the field's look rides it, so its scan follows the caret's x.
        GazeTarget.INPUT -> world.attentionOf(AttentionKind.CARET)?.point ?: world.input
        GazeTarget.TIMELINE -> world.timeline
        GazeTarget.CARET, GazeTarget.MESSAGE, GazeTarget.APPROVAL, GazeTarget.TOOL -> attentionPoint(target, world)?.point
        GazeTarget.PEER -> world.peers.getOrNull(peerIndex) ?: asidePoint
        GazeTarget.OWN, GazeTarget.AWAY -> asidePoint
        GazeTarget.USER -> CENTER
    }

    /** Durations: never negative. */
    private fun pickRange(range: ClosedFloatingPointRange<Float>): Float = pickSigned(range).coerceAtLeast(0f)

    /** Positions: the range's sign is meaningful (negative y is up). */
    private fun pickSigned(range: ClosedFloatingPointRange<Float>): Float {
        val span = range.endInclusive - range.start
        return range.start + random.nextFloat() * span
    }

    /** A look at something on screen (or you), as opposed to an aside into its own thoughts. */
    private fun GazeTarget.isPlace(): Boolean = this != GazeTarget.OWN && this != GazeTarget.AWAY

    private enum class PlanPhase { PICK, DWELL, GAP }

    private enum class ScanPhase { STEP, HOLD, PAUSE }

    private companion object {
        val CENTER = GazePoint(0f, 0f)

        /** The pause after a cued look: short, so the hand-over (SENT's look back at you) reads as one beat. */
        val CUED_GAP_SECONDS = 0.1f..0.25f

        /** A timeline sweep starts just before the live edge and reads rightward past it. */
        const val TIMELINE_SCAN_START = -0.05f
        const val TIMELINE_SCAN_END = 0.3f

        /** How far each successive line of the reply climbs (gaze units; negative y is up). */
        const val TIMELINE_LINE_STEP = 0.05f

        /** Spike `triangle`: other person's eyes and nose (Disney Research). */
        val USER_TRIANGLE: List<GazePoint> = listOf(
            GazePoint(-0.05f, -0.04f),
            GazePoint(0.05f, -0.04f),
            GazePoint(0f, 0.05f),
        )
    }
}
