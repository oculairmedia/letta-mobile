package com.letta.mobile.data.memory.graph

import com.letta.mobile.data.memory.MemoryGraphEdge
import com.letta.mobile.data.memory.MemoryGraphEdgeKind
import com.letta.mobile.data.memory.MemoryGraphNode
import com.letta.mobile.data.memory.MemoryGraphNodeKind
import com.letta.mobile.data.memory.MemoryParityGraph
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MemoryGraphTuningTest {
    private val treePositionBits = listOf(
        1129532844, -1038087816, 1103747257, 1122176474, -1021589117, 1110066960, -1034933820, -1032378336,
        -1034658592, 1132876877, -1012785294, 1112425421, -1047098852, 1127609054, 1108971299, -1022723775,
        -1043045048, 1110480023, -1029391281, 1123726072, -1031333668, 1126597473, -1015898527, 1105912377,
        -1013819916, -1039251104, 1110943913, 1116863431, 1116885339, 1124206935, 1083515835, -1031016114,
        1120047956, -1016476945, 1129783086, 1120246095, -1025136095, 1127402811, -1030250378, -1025773280,
        1097740984, -1050811996, -1030914947, 1078133948, -1042177461, -1045485658, -1013209858, -1027923100,
        -1064353549, 1104238892, -1014821816, -1022375003, -1021760039, -1020368593, 1126279901, 1129890196,
        1113722825, -1020336978, 1131661536, 1113045375, 1127562448, 1121695638, -1022048818, 1124370079,
        1126211896, -1029348246, 1124319314, -1019706689, 1133166810, -1017194291, 1125383058, 1127257980,
        1134729191, -1065602100, 1104756730, 1132399861, -1024182124, 1108253434, -1029155897, -1014936842,
        1110565520, -1015256605, 1117610008, 1126734646, -1030202641, -1020892446, 1117026039, -1031257341,
        -1044789660, 1124988203, -1047689568, 1120063138, 1119520050, -1022655348, -1018221454, -1021223088,
        -1018379303, -1032518643, 1120369809, 1119059158, -1025770004, 1118365048, -1015883972, -1041651469,
        -1017237868, -1014842925, -1014727048, -1017840073, -1036608068, -1015652550, 1119050968, 1133981656,
        1132564614, 1133133664, 1112329129, -1013317500, 1126548181, -1022260269, 1125108188, 1117750815,
        1133907648, -1034437476, 1126324061, -1048914015, 1133851190, 1125953067, -1022999536, 1132636657,
        -1019333250, 1132152171, 1120949676, 1099489190, 1132732404, -1064678814, 1131488646, -1020878150,
        1092973033, -1018643140, 1136715222, -1020327759, 1135797549, -1023972191, 1124634175, 1134065495,
        1132663910, 1123757486, 1131679411, -1027796438, 1134207336, -1023649886, 1126068107, 1133105130,
        -1029305666, 1130869932, -1016993604, 1117151546, -1018080382, 1123438253, -1019694965, -1016986969,
        -1040798044, -1021560756, 1127004811, -1017265841, 1126023124, -1013225172, 1129097610, 1127864093,
        1109634346, 1133673139, -1020704785, -1025572443, -1023265784, -1031293802, 1125427141, 1103735117,
        1128470116, -1081324493, -1030385413, 1112124731, 1116832191, 1130029887, 1114192260, 1085270177,
        -1051996962, 1130397164, 1125586459, -1015128673, 1122916838, -1037299044, -1022468937, -1014627361,
    )

    private val pairPositionBits = listOf(
        1129532844, -1038087816, -1041123820, 1107345703, -1021453181, 1117518274, -1046697507, -1024618241,
        -1030704999, 1130143804, -1012746185, 1120793524, -1046570639, 1121599864, 1117798412, -1022818246,
        -1047008346, -1048764945, -1027143560, 1117929894, -1032598853, 1123683849, -1014979470, 1119061129,
        -1012570297, -1047407482, 1116023757, 1112170646, 1116498497, 1122587462, 1115801997, -1046329363,
        1122416103, -1016848606, 1128180423, 1116184045, -1025255524, 1123639047, -1029719863, -1020754512,
        1101328847, -1039392725, 1107348042, 1101556412, -1045214473, -1033867651, -1014668826, -1034766349,
        -1023119643, -1067725337, -1012409996, -1024079872, -1020166066, -1020377987, 1128208911, 1127665103,
        1118553080, -1020375017, 1125680546, -1044347743, 1130824755, 1124769715, -1021693895, 1126481732,
        1132249012, 1112942532, 1105728664, -1017251134, 1133152320, -1020755794, 1127325760, 1130750015,
        1134011462, -1029373470, 1113650381, 1129779294, -1020191494, -1042212771, -1030957966, -1014063357,
        1111539558, -1014431691, 1107676299, 1125203413, -1039439992, -1020839654, 1126942793, -1025649419,
        -1079906985, 1129173588, -1026179549, 1107163713, 1120767741, -1027476136, -1017820318, -1021418576,
        -1018843633, 1103947926, -1061778651, 1113628233, -1033433930, 1116732340, -1013501108, -1081697741,
        -1013915460, -1016443456, -1014103624, -1019935666, -1031500407, -1016682920, 1121289050, 1133306342,
        1132330570, 1133252538, 1120468266, -1013509748, 1125424947, -1029794942, 1121500142, 1118075483,
        1133594727, -1040902604, 1122774592, 1125008013, 1135289698, 1124755246, -1025320175, 1133425368,
        -1020090516, 1133473934, 1125390719, 1116524844, 1132066185, -1029992038, 1125526370, -1020467759,
        1108157504, -1025203669, 1137016774, -1021659274, 1136205210, -1028520506, 1122986096, 1135182484,
        1133493393, 1129477094, 1131269147, 1097239219, 1133921438, -1018137283, 1126243854, 1133553193,
        -1042332653, 1126623091, -1015420402, -1025686732, -1014888990, 1109336481, -1019602227, -1014383511,
        -1024776030, -1021104778, 1127557977, -1015889857, 1126711564, -1013620285, 1125581416, 1126339849,
        1088578615, 1132922199, -1022863024, -1027243068, -1029131445, -1034687317, 1122001251, -1054335515,
        1132409347, -1043939224, -1024054534, 1129287563, 1121033879, 1131338934, -1030077066, -1028520578,
        -1029431483, 1126285331, 1125552126, -1018023243, 1127066825, 1086244264, -1022662835, -1014937390,
    )

    private val config = MemoryGraphLayoutConfig()

    @Test
    fun fixedSeedPositionsStayBitIdentical() {
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
        val seed = List(count) { MemoryGraphPoint(random.nextFloat() * 800f - 400f, random.nextFloat() * 800f - 400f) }
        listOf(0f, 2000f).forEach { threshold ->
            val tuning = MemoryGraphTuning(barnesHutAbove = threshold)
            val field = MemoryGraphForceField(count, springs, radii = FloatArray(count) { 9f })
            seed.forEachIndexed(field::place)
            field.applyTuning(tuning, kinds, IntArray(springs.size / 2) { MemoryGraphEdgeKind.Remembers.ordinal })
            val motion = MemoryGraphMotion.forGraph(count, springs.size / 2, tuning)
            var alpha = tuning.alphaStart
            repeat(80) {
                field.step(MemoryGraphForceField.FREE, motion, alpha)
                alpha = motion.cool(alpha)
            }
            val bits = (0 until count).flatMap { listOf(field.point(it).x.toBits(), field.point(it).y.toBits()) }
            val expected = if (threshold == 0f) treePositionBits else pairPositionBits
            assertEquals(expected, bits, "all coordinate bits after 80 steps, threshold=$threshold")
        }
    }

    @Test
    fun theDefaultsAreTheShippedConstants() {
        val tuning = MemoryGraphTuning()
        assertEquals(config.repulsion, tuning.repulsion)
        assertEquals(config.gravity, tuning.gravity)
        assertEquals(config.damping, tuning.sparseDamping)
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
        assertEquals(listOf(10f, 0.6f, 0.6f, 12f, 40f, 240f), listOf(
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
        val off = MemoryGraphTuning(degreeScaling = 0f, clusterEnabled = false)
        val tuned = field(view, seed).also {
            it.applyTuning(off, nodeKinds(view), MemoryGraphForceField.springKindsOf(view))
        }
        val motion = MemoryGraphMotion.forGraph(view.nodes.size, view.edges.size)
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
        field.applyTuning(tuning, nodeKinds(view), MemoryGraphForceField.springKindsOf(view))
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
        field.applyTuning(tuning, nodeKinds(view), MemoryGraphForceField.springKindsOf(view))
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
        field.applyTuning(tuning, nodeKinds(view), MemoryGraphForceField.springKindsOf(view))
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
        field.applyTuning(tuning, nodeKinds(view), MemoryGraphForceField.springKindsOf(view))
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
            field.applyTuning(tuning, kinds, IntArray(0))
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
        field.applyTuning(tuning, nodeKinds(view), IntArray(0))
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
        const val SPOKE_PULL = 50f
        const val SPREAD = 3_000f
        const val SEED = 11
    }
}
