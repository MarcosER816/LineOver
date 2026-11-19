package dev.mark.lineover.core.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Stage 13: the layer tree, its JSON (shared with the engine) and the layer operations. */
class LayersTest {
    private val layered =
        EditGraph(
            layers =
                listOf(
                    Layer(id = "background", ops = listOf(OpParams.Exposure(ev = 0.5f))),
                    Layer(
                        id = "l1",
                        type = LayerType.Raster,
                        name = "Photo 2",
                        opacity = 80f,
                        fill = 90f,
                        blend = BlendMode.Multiply,
                        source = "abc.jpg",
                        sourceSize = listOf(4000f, 3000f),
                        transform = listOf(0.5f, 0f, 0f, 0.5f, 10f, 20f),
                    ),
                    Layer(
                        id = "g1",
                        type = LayerType.Group,
                        name = "Group",
                        visible = false,
                        locked = true,
                        blend = BlendMode.Difference,
                        isolated = true,
                        children =
                            listOf(
                                Layer(
                                    id = "a1",
                                    ops = listOf(OpParams.Saturation(amount = -40f)),
                                    type = LayerType.Adjustment,
                                    name = "Saturation",
                                ),
                            ),
                    ),
                ),
            blendSpace = BlendSpace.Linear,
        )

    /** The fixture the engine's blend_test also round-trips (byte for byte). */
    private val fixture: String by lazy {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        while (dir != null && !File(dir, "settings.gradle.kts").isFile) dir = dir.parentFile
        File(requireNotNull(dir), "testdata/graph/edit_graph_layers.json").readText().trimEnd()
    }

    @Test
    fun layeredGraphsEncodeAsTheSharedFixture() {
        assertEquals(fixture, EditGraphJson.encode(layered))
        assertEquals(layered, EditGraphJson.decode(fixture))
        // A graph without layers writes none of the new fields.
        val plain = EditGraph().withBackgroundOps(listOf(OpParams.Exposure(ev = 1f)))
        assertEquals(
            """{"schemaVersion":1,"layers":[{"id":"background","ops":[{"op":"exposure","ev":1.0}]}]}""",
            EditGraphJson.encode(plain),
        )
    }

    @Test
    fun allTwentySevenModesHaveStableNames() {
        assertEquals(27, BlendMode.entries.size)
        assertEquals(BlendMode.entries.toSet(), BlendMode.GROUPS.flatten().toSet())
        val names =
            BlendMode.entries.map {
                EditGraphJson.encode(
                    EditGraph(layers = listOf(Layer("x", blend = it, type = LayerType.Raster))),
                )
            }
        assertTrue(names[4], names[4].contains("\"blend\":\"color_burn\""))
        assertTrue(names[10], names[10].contains("\"blend\":\"linear_dodge\""))
    }

    private fun raster(
        id: String,
        name: String = id,
    ) = Layer(id = id, type = LayerType.Raster, name = name, source = "$id.jpg", sourceSize = listOf(10f, 10f))

    private val three =
        EditGraph()
            .withBackgroundOps(emptyList())
            .let { LayerMath.add(it, raster("a")) }
            .let { LayerMath.add(it, raster("b")) }
            .let { LayerMath.add(it, raster("c")) }

    private fun order(g: EditGraph) = g.layers.map { it.id }

    @Test
    fun addMoveRemoveKeepThePhotoAtTheBottom() {
        assertEquals(listOf("background", "a", "b", "c"), order(three))
        assertEquals(listOf("background", "c", "a", "b"), order(LayerMath.move(three, "c", 0)))
        assertEquals(listOf("background", "b", "c", "a"), order(LayerMath.move(three, "a", 9)))
        assertEquals(listOf("background", "a", "c"), order(LayerMath.remove(three, "b")))
        // The background cannot be removed or changed through layer operations.
        assertEquals(three, LayerMath.remove(three, "background"))
        assertEquals(three, LayerMath.update(three, "background") { it.copy(opacity = 1f) })
        assertEquals(listOf("background", "a", "x", "b", "c"), order(LayerMath.add(three, raster("x"), aboveId = "a")))
        assertTrue(LayerMath.hasLayers(three))
        assertFalse(
            LayerMath.hasLayers(
                LayerMath.update(three, "a") { it.copy(visible = false) }.let { g ->
                    LayerMath.remove(LayerMath.remove(g, "b"), "c")
                },
            ),
        )
        assertEquals(setOf("a.jpg", "b.jpg", "c.jpg"), LayerMath.sources(three))
    }

    @Test
    fun duplicateMakesNewIdsRightAbove() {
        val grouped = LayerMath.group(three, "b")
        val g = LayerMath.duplicate(grouped, order(grouped)[2])
        val groups = g.layers.filter { it.type == LayerType.Group }
        assertEquals(2, groups.size)
        assertNotEquals(groups[0].id, groups[1].id)
        assertNotEquals(groups[0].children[0].id, groups[1].children[0].id)
        assertEquals("Group copy", groups[1].name)
    }

    @Test
    fun mergeDownAndFlattenAreNonDestructivePassThroughGroups() {
        assertFalse(LayerMath.canMergeDown(three, "a")) // never into the photo
        val merged = LayerMath.mergeDown(three, "c")
        assertEquals(3, merged.layers.size)
        val group = merged.layers[2]
        assertEquals(LayerType.Group, group.type)
        assertFalse(group.isolated) // pass-through: looks exactly as before
        assertEquals(listOf("b", "c"), group.children.map { it.id })
        assertEquals("b + c", group.name)
        val flat = LayerMath.flattenAll(three)
        assertEquals(2, flat.layers.size)
        assertEquals(LayerMath.FLATTENED, flat.layers[1].name)
        assertEquals(listOf("a", "b", "c"), flat.layers[1].children.map { it.id })
        // Ungroup puts the layers back.
        assertEquals(three, LayerMath.ungroup(flat, flat.layers[1].id))
    }

    @Test
    fun updatesReachLayersInsideGroups() {
        val g = LayerMath.group(three, "b")
        val changed = LayerMath.update(g, "b") { it.copy(blend = BlendMode.Screen, opacity = 50f) }
        val b = LayerMath.find(changed, "b")!!
        assertEquals(BlendMode.Screen, b.blend)
        assertEquals(50f, b.opacity)
        assertEquals(listOf(0, 0, 1, 0), LayerMath.flatten(changed).map { it.second })
        assertNull(LayerMath.find(changed, "nope"))
    }

    @Test
    fun placementRoundTripsThroughTheTransform() {
        val p = LayerPlacement(1200f, 800f, 0.4f, 30f)
        val t = p.transform(4000f, 3000f)
        val back = LayerPlacement.of(t, 4000f, 3000f)
        assertEquals(p.centerX, back.centerX, 1e-2f)
        assertEquals(p.centerY, back.centerY, 1e-2f)
        assertEquals(p.scale, back.scale, 1e-5f)
        assertEquals(p.degrees, back.degrees, 1e-3f)
        val fit = LayerPlacement.fit(4000f, 3000f, 2000f, 2000f)
        assertEquals(0.5f, fit.scale)
        assertEquals(1000f, fit.centerX)
    }

    @Test
    fun deletedLayersGoBackWhereTheyWere() {
        val g = LayerMath.group(three, "b")
        val groupId = g.layers[2].id
        val (parent, index) = LayerMath.parentOf(g, "b")!!
        assertEquals(groupId, parent)
        assertEquals(0, index)
        val removed = LayerMath.remove(g, "b")
        assertEquals(g, LayerMath.insertAt(removed, LayerMath.find(g, "b")!!, parent, index))
        val (top, at) = LayerMath.parentOf(three, "a")!!
        assertNull(top)
        assertEquals(three, LayerMath.insertAt(LayerMath.remove(three, "a"), LayerMath.find(three, "a")!!, top, at))
    }

    @Test
    fun historyNamesLayerChanges() {
        val faded = LayerMath.update(three, "b") { it.copy(opacity = 50f) }
        val change = HistoryLabels.describe(three, faded)
        assertEquals("b: Opacity 50 %", change.label)
        assertEquals("layer.b.opacity", change.key)
        assertEquals(
            "b: Multiply",
            HistoryLabels.describe(three, LayerMath.update(three, "b") { it.copy(blend = BlendMode.Multiply) }).label,
        )
        assertEquals("Hide c", HistoryLabels.describe(three, LayerMath.update(three, "c") { it.copy(visible = false) }).label)
        assertEquals("Layers", HistoryLabels.describe(three, LayerMath.remove(three, "c")).label)
        val adjusted = LayerMath.update(three, "a") { it.copy(ops = listOf(OpParams.Exposure(1f))) }
        assertEquals("a: Exposure +1", HistoryLabels.describe(three, adjusted).label)
        // The photo's own edits keep their plain labels.
        assertEquals("Exposure +1", HistoryLabels.describe(three, three.withBackgroundOps(listOf(OpParams.Exposure(1f)))).label)
    }

    @Test
    fun adjustmentKindsReadBackTheirValue() {
        for (kind in AdjustmentKind.entries) {
            val ops = kind.withValue(emptyList(), kind.default)
            assertEquals(kind, AdjustmentKind.of(ops))
            assertEquals(if (kind.hasValue) kind.default else 0f, kind.valueIn(ops)!!, 1e-6f)
        }
        val ops = AdjustmentKind.Exposure.withValue(listOf(OpParams.Exposure(1f), OpParams.Saturation(10f)), 9f)
        assertEquals(listOf(OpParams.Exposure(5f), OpParams.Saturation(10f)), ops)
        assertNull(AdjustmentKind.of(ops))
        assertFalse(AdjustmentKind.isPointOp(OpParams.Clarity(10f)))
        assertTrue(AdjustmentKind.isPointOp(OpParams.Invert))
    }
}
