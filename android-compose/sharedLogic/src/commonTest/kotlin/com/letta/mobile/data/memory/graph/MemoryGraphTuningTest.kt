package com.letta.mobile.data.memory.graph

import com.letta.mobile.data.memory.MemoryGraphEdge
import com.letta.mobile.data.memory.MemoryGraphEdgeKind
import com.letta.mobile.data.memory.MemoryGraphNode
import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.memory.MemoryParityGraph
import com.letta.mobile.data.memory.graph.forces.GraphElements
import com.letta.mobile.data.memory.graph.forces.Variation
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryGraphTuningTest {
    private val treePositionBits = listOf(
        1129532844, -1038087816, 1122180155, 1091015343, -1023167644, 1107709877, -1050374373, -1028322057,
        -1066945473, 1128449694, -1015205374, 1124769700, 1116216390, 1113553078, 1122022060, -1023532894,
        1108467841, 1105599554, -1045010986, 1117951369, -1030435490, 1120971338, -1019478149, 1119000534,
        -1016946855, 1113774194, 1101141798, 1119947914, 1116864036, 1122573229, -1071702973, -1050433792,
        1124155232, -1018962244, 1132995342, 1116115958, -1026699359, 1124512707, 1105292675, -1020075708,
        1078834641, 1104850556, -1041106524, -1039714216, -1031481292, -1027791867, -1013467102, -1030038759,
        -1033881233, 1112696835, -1022088237, -1030381573, -1022859080, -1022417932, 1124686437, 1129551235,
        1106377249, -1027604224, 1127065482, -1029383636, 1125943542, 1126117072, -1023581241, 1126082408,
        1121755592, 1112202421, 1128136962, -1022769290, 1133560903, -1021387983, 1132568670, 1129410016,
        1133918312, -1030998987, 1104528538, 1131664954, -1021249684, -1061987855, -1028084566, -1014753522,
        1126040550, -1016537162, 1112712831, 1128836503, -1026906777, -1032567541, 1121702829, -1031193976,
        1102777781, 1125733328, -1030388024, -1060532803, 1114873348, -1017941144, -1017877140, -1015244357,
        -1019409538, -1022872291, 1120748254, 1120689552, -1030098058, 1128602277, -1014915060, -1067597216,
        -1023022496, -1017833308, -1015888934, -1017863614, -1034339944, -1020380454, 1108962890, 1133925677,
        1130843102, 1133063753, 1117580423, -1015107221, 1124751650, -1038287347, 1118315787, -1048516435,
        1134203066, -1044139297, 1130094769, 1114074531, 1134044008, 1126501845, -1026369923, 1131927748,
        -1021328814, 1133675931, 1109013444, -1042041714, 1127726477, -1042145134, 1131589588, -1045787274,
        1117748979, -1023190636, 1136611842, -1023912635, 1136289476, -1035498534, 1127585351, 1135150567,
        1135238962, 1119553023, 1133567701, 1099323130, 1130686908, -1019050184, 1125830258, 1132635238,
        1120050626, 1126916706, -1018611161, -1029935309, -1013907522, 1110705089, -1020071487, -1019139864,
        -1049588475, -1015594943, 1132054593, -1023183967, 1133445870, -1015668594, 1128401703, 1126064040,
        1116319665, 1135598960, -1015737309, -1032399701, -1027004242, 1107429800, 1127174116, 1116037525,
        1126049382, 1106699445, -1024900657, 1119042835, 1118995620, 1131913601, -1086572905, -1022573721,
        -1037102798, 1123242609, 1127202414, -1013546848, 1116108528, -1030606498, -1022698160, -1013074652,
    )

    private val pairPositionBits = listOf(
        1129532844, -1038087816, 1125859503, 1115978267, -1027954159, 1118364959, 1113461002, -1027428008,
        1058367920, 1130076035, -1016458422, 1125044600, 1112035970, 1130763916, -1041893899, -1019117642,
        1114709297, 1103373641, -1037769209, 1120099293, -1028944212, 1122822953, -1020243054, 1115412613,
        -1016859341, 1111890687, 1099830232, 1124323232, 1116331251, 1124687776, -1031455655, -1035508098,
        1118881477, -1018019926, 1134803395, 1119668369, -1027651345, 1125559534, 1101442816, -1020658450,
        1096714279, 1116599391, -1047748148, 1073538157, -1031452712, -1028855284, -1013250872, -1028742682,
        -1056783540, 1109038783, -1021941897, -1028672133, -1020938288, -1019959564, 1124666806, 1131137317,
        -1070714262, -1028906008, 1125996888, -1030103671, 1125397791, 1125748251, -1021957401, 1124339264,
        1117379974, -1051913066, 1128024999, -1021877749, 1133290697, -1021167698, 1132936763, 1128914142,
        1134163119, -1034123332, 1119457050, 1128597714, -1020943229, 1074835150, -1026478280, -1016515774,
        1126048526, -1017131790, 1112384497, 1127794410, -1024679594, -1034166868, 1119616154, -1030669919,
        -1032957477, 1129566869, -1036073022, 1109548896, 1114800072, -1019848789, -1017441861, -1014914841,
        -1019848158, -1023882184, 1124628539, 1120912956, -1031576966, 1126836899, -1014965682, -1067873703,
        -1016045030, -1019331876, -1014371735, -1015683687, -1029161866, -1020663645, 1104077007, 1134555548,
        1131763217, 1133107832, 1115849287, -1015608212, 1123097767, -1035786934, 1122838056, 1092981497,
        1133647733, -1048481995, 1126135884, 1104355466, 1133524699, 1124770558, -1023021229, 1130511473,
        -1021084350, 1133226189, -1056114532, -1036305569, 1128491302, -1029411049, 1131543969, -1042085914,
        1120853676, -1022443456, 1136491224, -1023122307, 1136288682, -1033299004, 1128008561, 1134909227,
        1135319249, 1115759429, 1133591786, 1108006227, 1131827528, -1019150682, 1128362324, 1131627570,
        1117999402, 1120439527, -1017925816, -1030978423, -1013965615, 1116246890, -1018661024, -1020906262,
        -1039111083, -1013953181, 1132462453, -1023473290, 1133535756, -1015753581, 1128207082, 1126724442,
        1118316075, 1134901807, -1014825732, -1034258970, -1029304498, 1097118480, 1121121026, 1115466815,
        1128157257, 1089984926, -1024737833, 1114320708, 1104913919, 1132981983, 1085169568, -1024594662,
        -1045876783, 1126000149, 1125956475, -1013758060, 1111941662, -1036809965, -1022997087, -1013450947,
    )

    private val config = MemoryGraphLayoutConfig()

    /**
     * The tuning the stored bits above were captured under, spelled out in full so retuning the
     * shipped defaults never moves them: the golden locks the force step's determinism, not the
     * defaults. Naturalness is 1 and the run gives every element a seed, so the seeded per-element
     * variation is covered too.
     */
    private val pinnedTuning = MemoryGraphTuning(
        repulsion = 3200f,
        nodeCharge = MemoryGraphNodeKind.entries.associateWith { 1f },
        repulsionCutoff = 0f,
        crowdedRepulsionScale = 3f,
        barnesHutAbove = 300f,
        barnesHutTheta = 0.9f,
        barnesHutRebuildFrames = 10f,
        springLength = MemoryGraphEdgeKind.entries.associateWith { 110f },
        naturalness = 1f,
        linkLengthSpread = mapOf(
            MemoryGraphEdgeKind.Runs to 0.2f,
            MemoryGraphEdgeKind.Remembers to 0.3f,
            MemoryGraphEdgeKind.Uses to 0.25f,
            MemoryGraphEdgeKind.Delivers to 0.15f,
        ),
        linkStiffnessSpread = 0.3f,
        chargeSpread = 0.2f,
        collisionRadiusSpread = 0.15f,
        gravitySpread = 0.2f,
        clusterSpread = 0.2f,
        linkLengthDegreeBias = 0.1f,
        springStiffness = MemoryGraphEdgeKind.entries.associateWith { 0.14f },
        hubSpokeStrength = 0.02f,
        pinBackend = true,
        degreeScaling = 1f,
        clusterEnabled = true,
        clusterStrength = 0.05f,
        seededLayout = true,
        seedSpacing = 60f,
        seedJitter = 30f,
        gravity = 0.01f,
        collisionPadding = 10f,
        collisionStrength = 0.6f,
        crowdedCollisionStrength = 3f,
        collisionMaxShare = 0.35f,
        collisionFadeAlpha = 0.1f,
        collisionRadiusScale = MemoryGraphNodeKind.entries.associateWith { 1f },
        sparseDamping = 0.75f,
        crowdedDamping = 0.45f,
        sparseMaxSpeed = 30f,
        crowdedMaxSpeed = 12f,
        sparseCrowding = 40f,
        crowdedCrowding = 240f,
        alphaStart = 1f,
        coolingFrames = 150f,
        alphaMin = 0.001f,
        settleSpeed = 0.04f,
        maxFramesPerHeat = 360f,
        labelMinDegree = 3f,
        labelZoomThreshold = 1.6f,
        labelAllUnderNodes = 60f,
        labelCulling = true,
        labelNeighboursOnFocus = true,
        labelFontScale = 1f,
        labelHysteresisCount = 3f,
        labelHysteresisMargin = 6f,
        labelCullInterval = 8f,
    )

    @Test
    fun fixedSeedPositionsStayBitIdentical() {
        assertEquals(
            mapOf(0f to treePositionBits, 2000f to pairPositionBits),
            goldenRuns(pinnedTuning),
            "all coordinate bits after 80 steps under the pinned tuning, by Barnes-Hut threshold",
        )
    }

    @Test
    fun theSameInputStepsBitIdenticallyEveryTime() {
        assertEquals(goldenRuns(pinnedTuning), goldenRuns(pinnedTuning))
        assertEquals(goldenRuns(MemoryGraphTuning()), goldenRuns(MemoryGraphTuning()), "the shipped defaults too")
    }

    /** A seeded 96-node tree stepped 80 times under [tuning], once with Barnes-Hut and once every pair: each run's coordinate bits. */
    private fun goldenRuns(tuning: MemoryGraphTuning): Map<Float, List<Int>> {
        val random = kotlin.random.Random(2512)
        val count = 96
        val springs = (1 until count).flatMap { listOf(it, (it - 1) / 2) }.toIntArray()
        val kinds = IntArray(count) {
            when {
                it == 0 -> MemoryGraphNodeKind.Backend.ordinal
                it < 8 -> MemoryGraphNodeKind.Agent.ordinal
                else -> MemoryGraphNodeKind.Memory.ordinal
            }
        }
        val elements = GraphElements(
            nodeKinds = kinds,
            springKinds = IntArray(springs.size / 2) { MemoryGraphEdgeKind.Remembers.ordinal },
            nodeSeeds = IntArray(count) { Variation.hash("node-$it") },
            springSeeds = IntArray(springs.size / 2) { Variation.hash("spring-$it") },
        )
        val seed = List(count) { MemoryGraphPoint(random.nextFloat() * 800f - 400f, random.nextFloat() * 800f - 400f) }
        return listOf(0f, 2000f).associateWith { threshold ->
            val tuned = tuning.copy(barnesHutAbove = threshold)
            val field = MemoryGraphForceField(count, springs, radii = FloatArray(count) { 9f })
            seed.forEachIndexed(field::place)
            field.applyTuning(tuned, elements)
            val motion = MemoryGraphMotion.forGraph(count, springs.size / 2, tuned)
            var alpha = tuned.alphaStart
            repeat(80) {
                field.step(MemoryGraphForceField.FREE, motion, alpha)
                alpha = motion.cool(alpha)
            }
            (0 until count).flatMap { listOf(field.point(it).x.toBits(), field.point(it).y.toBits()) }
        }
    }

    @Test
    fun theDefaultsAreTheShippedConstants() {
        val tuning = MemoryGraphTuning()
        assertEquals(config.repulsion, tuning.repulsion)
        assertEquals(config.gravity, tuning.gravity)
        // Damping is raised from the layout's 0.82 so nodes stop vibrating; every force is unchanged.
        assertEquals(0.75f, tuning.sparseDamping)
        assertEquals(config.maxStep, tuning.sparseMaxSpeed)
        assertEquals(0f, tuning.repulsionCutoff)
        MemoryGraphNodeKind.entries.forEach { kind ->
            assertEquals(1f, tuning.chargeOf(kind))
            assertEquals(1f, tuning.radiusScaleOf(kind))
        }
        MemoryGraphEdgeKind.entries.forEach { kind ->
            assertEquals(config.idealEdgeLength, tuning.lengthOf(kind))
            assertEquals(config.attraction, tuning.stiffnessOf(kind))
        }
        assertEquals(listOf(10f, 0.6f, 0.45f, 12f, 40f, 240f), listOf(
            tuning.collisionPadding, tuning.collisionStrength, tuning.crowdedDamping,
            tuning.crowdedMaxSpeed, tuning.sparseCrowding, tuning.crowdedCrowding,
        ))
        assertEquals(listOf(1f, 150f, 0.001f, 0.04f, 360f), listOf(
            tuning.alphaStart, tuning.coolingFrames, tuning.alphaMin, tuning.settleSpeed, tuning.maxFramesPerHeat,
        ))
        // The force-model improvements ship switched on.
        val improvements = listOf(tuning.degreeScaling, tuning.clusterStrength, tuning.seedSpacing, tuning.seedJitter)
        assertEquals(listOf(1f, 0.05f, 60f, 30f), improvements)
        val fleet = listOf(tuning.hubSpokeStrength, tuning.crowdedRepulsionScale, tuning.crowdedCollisionStrength, tuning.barnesHutAbove)
        assertEquals(listOf(0.02f, 3f, 3f, 300f), fleet)
        assertEquals(0.9f, tuning.barnesHutTheta)
        assertEquals(0.1f, tuning.linkLengthDegreeBias)
        assertEquals(1f, tuning.naturalness)
        assertEquals(
            mapOf(
                MemoryGraphEdgeKind.Runs to 0.2f,
                MemoryGraphEdgeKind.Remembers to 0.3f,
                MemoryGraphEdgeKind.Uses to 0.25f,
                MemoryGraphEdgeKind.Delivers to 0.15f,
            ),
            tuning.linkLengthSpread,
        )
        val spreads = listOf(
            tuning.linkStiffnessSpread, tuning.chargeSpread, tuning.collisionRadiusSpread, tuning.gravitySpread, tuning.clusterSpread,
        )
        assertEquals(listOf(0.3f, 0.2f, 0.15f, 0.2f, 0.2f), spreads)
        assertEquals(listOf(10f, 0.35f, 0.1f), listOf(tuning.barnesHutRebuildFrames, tuning.collisionMaxShare, tuning.collisionFadeAlpha))
        assertTrue(tuning.pinBackend)
        assertTrue(tuning.clusterEnabled && tuning.seededLayout)
        val labels = listOf(tuning.labelMinDegree, tuning.labelZoomThreshold, tuning.labelAllUnderNodes, tuning.labelFontScale)
        assertEquals(listOf(3f, 1.6f, 60f, 1f), labels)
        assertTrue(tuning.labelCulling && tuning.labelNeighboursOnFocus)
        assertEquals(listOf(3f, 6f, 8f), listOf(tuning.labelHysteresisCount, tuning.labelHysteresisMargin, tuning.labelCullInterval))
    }

    @Test
    fun withTheImprovementsOffATunedFieldStepsExactlyLikeAnUntunedOne() {
        val view = MemoryGraphViews.build(star(), emptySet())
        val seed = MemoryGraphLayoutEngine.layout(view, MemoryGraphLayoutMode.Radial)
        val plain = field(view, seed)
        val off = MemoryGraphTuning(
            degreeScaling = 0f,
            clusterEnabled = false,
            sparseDamping = config.damping,
            naturalness = 0f,
            linkLengthDegreeBias = 0f,
        )
        val tuned = field(view, seed).also {
            it.applyTuning(off, MemoryGraphForceField.elementsOf(view))
        }
        val motion = MemoryGraphMotion.forGraph(view.nodes.size, view.edges.size, off)
        repeat(STEPS) {
            plain.step()
            tuned.step(MemoryGraphForceField.FREE, motion, 1f)
        }
        view.nodes.indices.forEach { assertEquals(plain.point(it), tuned.point(it)) }
    }

    @Test
    fun aKindsChargeScalesHowHardItRepels() {
        val baseline = pairPush(MemoryGraphTuning(repulsion = WEAK))
        val charged = pairPush(MemoryGraphTuning(repulsion = WEAK, nodeCharge = mapOf(MemoryGraphNodeKind.Agent to 2f)))
        assertTrue(abs(charged / baseline - 2f) < 0.05f, "charge 2 doubled the push: $baseline -> $charged")
    }

    @Test
    fun nodesBeyondTheCutOffDoNotRepel() {
        val unlimited = pairPush(MemoryGraphTuning(repulsion = WEAK, gravity = 0f))
        val cut = pairPush(MemoryGraphTuning(repulsion = WEAK, gravity = 0f, repulsionCutoff = PAIR_DISTANCE / 2))
        assertTrue(unlimited > 0f)
        assertEquals(0f, cut)
    }

    @Test
    fun aSpringKindsLengthIsWhereItRests() {
        val view = MemoryGraphViews.build(pair(), emptySet())
        val tuning = MemoryGraphTuning(repulsion = 0f, gravity = 0f, springLength = mapOf(MemoryGraphEdgeKind.Runs to PAIR_DISTANCE))
        val field = MemoryGraphForceField(2, MemoryGraphForceField.springsOf(view))
        field.applyTuning(tuning, GraphElements(nodeKinds(view), MemoryGraphForceField.springKindsOf(view)))
        field.place(0, MemoryGraphPoint(0f, 0f))
        field.place(1, MemoryGraphPoint(PAIR_DISTANCE, 0f))
        field.step(MemoryGraphForceField.FREE, MemoryGraphMotion.forGraph(2, 1, tuning), 1f)
        assertEquals(MemoryGraphPoint(PAIR_DISTANCE, 0f), field.point(1), "a spring at its rest length pulls nothing")
    }

    @Test
    fun degreeScalingSoftensASpringBetweenHubsAgainstOneBetweenLeaves() {
        val view = MemoryGraphViews.build(hubsAndPair(), emptySet())
        val tuning = MemoryGraphTuning(repulsion = 0f, gravity = 0f, clusterEnabled = false)
        val field = MemoryGraphForceField(view.nodes.size, MemoryGraphForceField.springsOf(view))
        field.applyTuning(tuning, GraphElements(nodeKinds(view), MemoryGraphForceField.springKindsOf(view)))
        val start = hubsAndPairPositions()
        view.nodes.forEachIndexed { index, node -> field.place(index, start.getValue(node.id)) }
        field.step(MemoryGraphForceField.FREE, MemoryGraphMotion.forGraph(view.nodes.size, view.edges.size, tuning), 1f)
        val index = view.nodes.withIndex().associate { (i, node) -> node.id to i }
        val hubMove = field.point(index.getValue("h1")).x - start.getValue("h1").x
        val leafMove = field.point(index.getValue("p")).x - start.getValue("p").x
        assertTrue(hubMove > 0f && hubMove < leafMove * 0.3f, "hub edge $hubMove against leaf edge $leafMove")
    }

    @Test
    fun theClusterForcePullsASharedNodeTowardItsAgents() {
        val view = MemoryGraphViews.build(sharedBlock(), emptySet())
        val tuning = MemoryGraphTuning(
            repulsion = 0f,
            gravity = 0f,
            springStiffness = MemoryGraphEdgeKind.entries.associateWith { 0f },
            clusterStrength = 0.1f,
        )
        val field = MemoryGraphForceField(view.nodes.size, MemoryGraphForceField.springsOf(view))
        field.applyTuning(tuning, GraphElements(nodeKinds(view), MemoryGraphForceField.springKindsOf(view)))
        val start = mapOf(
            "backend" to MemoryGraphPoint(0f, 0f),
            "a1" to MemoryGraphPoint(-100f, 0f),
            "a2" to MemoryGraphPoint(100f, 0f),
            "shared" to MemoryGraphPoint(0f, 100f),
        )
        view.nodes.forEachIndexed { index, node -> field.place(index, start.getValue(node.id)) }
        field.step(MemoryGraphForceField.FREE, MemoryGraphMotion.forGraph(view.nodes.size, view.edges.size, tuning), 1f)
        val shared = field.point(view.nodes.indexOfFirst { it.id == "shared" })
        assertEquals(0f, shared.x)
        assertTrue(shared.y < 100f, "moved toward the agents' centroid: $shared")
    }

    @Test
    fun aFleetPinsItsBackendAndKeepsItsSpokesWeak() {
        val view = MemoryGraphViews.build(sharedBlock(), emptySet())
        // Only the spokes pull: every other spring is slack, so the agent moves by its spoke alone.
        val tuning = MemoryGraphTuning(
            repulsion = 0f,
            gravity = 0f,
            clusterEnabled = false,
            springStiffness = MemoryGraphEdgeKind.entries.associateWith { 0f },
        )
        val field = MemoryGraphForceField(view.nodes.size, MemoryGraphForceField.springsOf(view))
        field.applyTuning(tuning, GraphElements(nodeKinds(view), MemoryGraphForceField.springKindsOf(view)))
        val start = mapOf(
            "backend" to MemoryGraphPoint(SPOKE_PULL, 0f),
            "a1" to MemoryGraphPoint(SPOKE_PULL - STRETCHED, 0f),
            "a2" to MemoryGraphPoint(SPOKE_PULL + STRETCHED, 0f),
            "shared" to MemoryGraphPoint(SPOKE_PULL, FAR),
        )
        view.nodes.forEachIndexed { index, node -> field.place(index, start.getValue(node.id)) }
        field.step(MemoryGraphForceField.FREE, MemoryGraphMotion.forGraph(view.nodes.size, view.edges.size, tuning), 1f)
        val index = view.nodes.withIndex().associate { (i, node) -> node.id to i }
        assertEquals(start.getValue("backend"), field.point(index.getValue("backend")), "the backend is pinned")
        val agentMove = field.point(index.getValue("a1")).x - start.getValue("a1").x
        val spokeOnly = tuning.hubSpokeStrength * (STRETCHED - REST)
        assertTrue(agentMove > 0f && agentMove <= spokeOnly * 1.01f, "a weak spoke moved the agent $agentMove (at most $spokeOnly)")
    }

    @Test
    fun barnesHutRepulsionMatchesEveryPairClosely() {
        val count = 500
        val random = kotlin.random.Random(SEED)
        val points = List(count) { MemoryGraphPoint(random.nextFloat() * SPREAD, random.nextFloat() * SPREAD) }
        val kinds = IntArray(count) { MemoryGraphNodeKind.Memory.ordinal }
        fun moves(tuning: MemoryGraphTuning): List<MemoryGraphPoint> {
            val field = MemoryGraphForceField(count, IntArray(0))
            field.applyTuning(tuning, GraphElements(kinds, IntArray(0)))
            points.forEachIndexed { i, point -> field.place(i, point) }
            field.step(MemoryGraphForceField.FREE, MemoryGraphMotion.forGraph(count, 0, tuning), 1f)
            return List(count) { i -> MemoryGraphPoint(field.point(i).x - points[i].x, field.point(i).y - points[i].y) }
        }
        val base = MemoryGraphTuning(repulsion = WEAK, gravity = 0f)
        val exact = moves(base.copy(barnesHutAbove = 100_000f))
        val approximate = moves(base.copy(barnesHutAbove = 0f, barnesHutTheta = 0.5f))
        val error = exact.indices.sumOf { i -> hypot(exact[i].x - approximate[i].x, exact[i].y - approximate[i].y).toDouble() }
        val magnitude = exact.sumOf { hypot(it.x, it.y).toDouble() }
        assertTrue(error / magnitude < 0.05, "Barnes-Hut off by ${error / magnitude}")
    }

    @Test
    fun tuningInstancesKeepChangesAndReheatsIndependent() {
        val first = MemoryGraphTunings()
        val second = MemoryGraphTunings()
        first.set(MemoryGraphTuning(repulsion = 4200f))
        first.reheat()
        assertEquals(4200f, first.current.value.repulsion)
        assertEquals(1, first.reheatRequests.value)
        assertEquals(MemoryGraphTuning(), second.current.value)
        assertEquals(0, second.reheatRequests.value)
    }

    @Test
    fun eachLinkGetsItsOwnStableLengthWithinTheSpread() {
        val view = MemoryGraphViews.build(star(), emptySet())
        val tuning = MemoryGraphTuning(linkLengthDegreeBias = 0f)
        val lengths = restLengths(view, tuning)
        assertEquals(lengths, restLengths(view, tuning), "the same edge always gets the same length")
        val base = tuning.lengthOf(MemoryGraphEdgeKind.Remembers)
        lengths.values.forEach { length ->
            val spread = tuning.lengthSpreadOfKind(MemoryGraphEdgeKind.Remembers.ordinal)
            val low = base * (1f - spread) - BISECTION_TOLERANCE
            val high = base * (1f + spread) + BISECTION_TOLERANCE
            val bounds = low..high
            assertTrue(length in bounds, "$length strays too far")
        }
        assertTrue(lengths.values.toSet().size > 1, "lengths vary")
        val flat = restLengths(view, tuning.copy(linkLengthSpread = emptyMap())).values
        assertTrue(flat.all { abs(it - base) < BISECTION_TOLERANCE }, "spread 0 is one length: $flat")
    }

    @Test
    fun addingANodeLeavesEveryOtherLinksLength() {
        val before = MemoryGraphViews.build(star(), emptySet())
        val grown = star().let { graph ->
            val newcomer = node("newcomer", MemoryGraphNodeKind.Memory)
            val anchor = node("other-agent", MemoryGraphNodeKind.Agent)
            MemoryParityGraph(
                nodes = graph.nodes + anchor + newcomer,
                edges = graph.edges + MemoryGraphEdge("other->new", anchor.id, newcomer.id, "remembers", MemoryGraphEdgeKind.Remembers),
            )
        }
        val after = MemoryGraphViews.build(grown, emptySet())
        val lengthsBefore = restLengths(before, MemoryGraphTuning())
        val lengthsAfter = restLengths(after, MemoryGraphTuning())
        lengthsBefore.forEach { (edge, length) -> assertEquals(length, lengthsAfter[edge], edge) }
    }

    @Test
    fun theDegreeBiasLengthensLinksToHubs() {
        val view = MemoryGraphViews.build(star(), emptySet())
        val plain = restLengths(view, MemoryGraphTuning(linkLengthSpread = emptyMap(), linkLengthDegreeBias = 0f))
        val biased = restLengths(view, MemoryGraphTuning(linkLengthSpread = emptyMap(), linkLengthDegreeBias = 0.2f))
        plain.forEach { (edge, length) -> assertTrue(biased.getValue(edge) > length, "$edge to the hub is longer") }
    }

    /**
     * Each edge's rest length under [tuning], read back as where a spring stops pulling: a lone
     * pair placed at a candidate length moves neither way only at its rest length. Measured by
     * bisection on a two-node field per edge, so no field internals are exposed.
     */
    private fun restLengths(view: MemoryGraphView, tuning: MemoryGraphTuning): Map<String, Float> {
        val springs = MemoryGraphForceField.springsOf(view)
        val elements = MemoryGraphForceField.elementsOf(view)
        val quiet = tuning.copy(repulsion = 0f, gravity = 0f, clusterEnabled = false, degreeScaling = 0f)
        return view.edges.indices.associate { s ->
            val edge = view.edges[s]
            val field = MemoryGraphForceField(view.nodes.size, springs)
            field.applyTuning(quiet, elements)
            edge.id to bisectRest(field, springs[2 * s], springs[2 * s + 1], view)
        }
    }

    /** The distance at which the spring between [a] and [b] is slack, every other node far away. */
    private fun bisectRest(field: MemoryGraphForceField, a: Int, b: Int, view: MemoryGraphView): Float {
        var low = 1f
        var high = 1_000f
        repeat(BISECTIONS) {
            val mid = (low + high) / 2f
            view.nodes.indices.forEach { i -> field.place(i, MemoryGraphPoint(i * FAR * 10f, FAR * 10f)) }
            field.rest()
            field.place(a, MemoryGraphPoint(0f, 0f))
            field.place(b, MemoryGraphPoint(mid, 0f))
            field.step(a, MemoryGraphMotion.forGraph(view.nodes.size, view.edges.size), 1f)
            if (field.point(b).x < mid) high = mid else low = mid
        }
        return (low + high) / 2f
    }

    @Test
    fun aStoredTuningRoundTrips() {
        val tuned = MemoryGraphTuning(
            repulsion = 4200f,
            nodeCharge = MemoryGraphTuning().nodeCharge + (MemoryGraphNodeKind.Memory to 0.5f),
            springLength = MemoryGraphTuning().springLength + (MemoryGraphEdgeKind.Remembers to 80f),
            coolingFrames = 90f,
        )
        assertEquals(tuned, MemoryGraphTunings.decode(MemoryGraphTunings.encode(tuned)))
        assertEquals(MemoryGraphTuning(repulsion = 5000f), MemoryGraphTunings.decode("""{"repulsion": 5000.0}"""))
        assertNull(MemoryGraphTunings.decode("not json"))
    }

    @Test
    fun theKotlinSourceNamesEveryKnob() {
        val source = MemoryGraphTunings.kotlinSource(MemoryGraphTuning(repulsion = 4200f))
        assertTrue(source.startsWith("MemoryGraphTuning(\n"))
        assertTrue("    repulsion = 4200.0f," in source)
        assertTrue("MemoryGraphNodeKind.Agent to 1.0f" in source)
        assertTrue("MemoryGraphEdgeKind.Runs to 110.0f" in source)
        assertTrue("    maxFramesPerHeat = 360.0f,\n" in source)
        assertTrue("    labelCulling = true,\n" in source)
        assertTrue("    labelFontScale = 1.0f,\n" in source)
        assertTrue("    labelCullInterval = 8.0f,\n)" in source)
    }

    /** How far the agent of an agent–memory pair [PAIR_DISTANCE] apart moves in one step under [tuning]. */
    private fun pairPush(tuning: MemoryGraphTuning): Float {
        val view = MemoryGraphViews.build(MemoryParityGraph(nodes = pair().nodes), emptySet())
        val field = MemoryGraphForceField(2, IntArray(0))
        field.applyTuning(tuning, GraphElements(nodeKinds(view), IntArray(0)))
        field.place(0, MemoryGraphPoint(0f, 0f))
        field.place(1, MemoryGraphPoint(PAIR_DISTANCE, 0f))
        field.step(1, MemoryGraphMotion.forGraph(2, 0, tuning), 1f)
        return abs(field.point(0).x)
    }

    private fun field(view: MemoryGraphView, seed: MemoryGraphLayout): MemoryGraphForceField =
        MemoryGraphForceField(view.nodes.size, MemoryGraphForceField.springsOf(view)).also { field ->
            view.nodes.forEachIndexed { index, node -> field.place(index, seed.getValue(node.id)) }
        }

    private fun MemoryGraphLayout.getValue(id: String): MemoryGraphPoint = checkNotNull(this[id])

    private fun nodeKinds(view: MemoryGraphView) = IntArray(view.nodes.size) { view.nodes[it].kind.ordinal }

    private fun pair() = MemoryParityGraph(
        nodes = listOf(node("agent", MemoryGraphNodeKind.Agent), node("block", MemoryGraphNodeKind.Memory)),
        edges = listOf(MemoryGraphEdge("agent->block", "agent", "block", "runs", MemoryGraphEdgeKind.Runs)),
    )

    private fun star(): MemoryParityGraph {
        val leaves = (1..LEAVES).map { node("block-$it", MemoryGraphNodeKind.Memory) }
        return MemoryParityGraph(
            nodes = listOf(node("agent", MemoryGraphNodeKind.Agent)) + leaves,
            edges = leaves.map { MemoryGraphEdge("agent->${it.id}", "agent", it.id, "remembers", MemoryGraphEdgeKind.Remembers) },
        )
    }

    private fun node(id: String, kind: MemoryGraphNodeKind) = MemoryGraphNode(id, id, id, kind)

    private fun edge(from: String, to: String, kind: MemoryGraphEdgeKind) = MemoryGraphEdge("$from->$to", from, to, kind.name, kind)

    /** Two hubs with three leaves each, linked to each other, beside a lone pair. */
    private fun hubsAndPair(): MemoryParityGraph {
        val leaves = listOf("h1" to "l1", "h1" to "l2", "h1" to "l3", "h2" to "m1", "h2" to "m2", "h2" to "m3")
        return MemoryParityGraph(
            nodes = listOf(node("h1", MemoryGraphNodeKind.Agent), node("h2", MemoryGraphNodeKind.Agent)) +
                leaves.map { node(it.second, MemoryGraphNodeKind.Memory) } +
                listOf(node("p", MemoryGraphNodeKind.Memory), node("q", MemoryGraphNodeKind.Memory)),
            edges = listOf(edge("h1", "h2", MemoryGraphEdgeKind.Runs), edge("p", "q", MemoryGraphEdgeKind.Runs)) +
                leaves.map { edge(it.first, it.second, MemoryGraphEdgeKind.Remembers) },
        )
    }

    /** The hub edge and the pair edge equally stretched; every leaf at its rest length from its hub. */
    private fun hubsAndPairPositions(): Map<String, MemoryGraphPoint> = mapOf(
        "h1" to MemoryGraphPoint(0f, 0f), "h2" to MemoryGraphPoint(STRETCHED, 0f),
        "l1" to MemoryGraphPoint(0f, REST), "l2" to MemoryGraphPoint(0f, -REST), "l3" to MemoryGraphPoint(-REST, 0f),
        "m1" to MemoryGraphPoint(STRETCHED, REST), "m2" to MemoryGraphPoint(STRETCHED, -REST),
        "m3" to MemoryGraphPoint(STRETCHED + REST, 0f),
        "p" to MemoryGraphPoint(0f, FAR), "q" to MemoryGraphPoint(STRETCHED, FAR),
    )

    /** A backend, two agents, and one block both agents hold. */
    private fun sharedBlock() = MemoryParityGraph(
        nodes = listOf(
            node("backend", MemoryGraphNodeKind.Backend),
            node("a1", MemoryGraphNodeKind.Agent),
            node("a2", MemoryGraphNodeKind.Agent),
            node("shared", MemoryGraphNodeKind.Memory),
        ),
        edges = listOf(
            edge("backend", "a1", MemoryGraphEdgeKind.Runs),
            edge("backend", "a2", MemoryGraphEdgeKind.Runs),
            edge("a1", "shared", MemoryGraphEdgeKind.Remembers),
            edge("a2", "shared", MemoryGraphEdgeKind.Remembers),
        ),
    )

    private companion object {
        const val STEPS = 40
        const val LEAVES = 6
        const val WEAK = 100f
        const val PAIR_DISTANCE = 10f
        const val STRETCHED = 200f
        const val REST = 110f
        const val FAR = 1_000f
        const val BISECTIONS = 30

        /** How close bisecting a spring's slack point gets to its true rest length. */
        const val BISECTION_TOLERANCE = 0.01f
        const val SPOKE_PULL = 50f
        const val SPREAD = 3_000f
        const val SEED = 11
    }
}
