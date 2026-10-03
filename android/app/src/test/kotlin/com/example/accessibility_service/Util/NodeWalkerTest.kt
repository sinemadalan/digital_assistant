package com.example.accessibility_service.Util

import org.junit.Assert.*
import org.junit.Test

class NodeWalkerTest {
    private class Node(
        val name: String,
        val text: String? = null,
        val description: String? = null,
        val visible: Boolean = true,
        val bounds: BoundsInScreen = BoundsInScreen(0, 0, 100, 100),
        val children: List<Node?> = emptyList(),
    )

    private class Reader : NodeWalker.NodeReader<Node> {
        val visited = mutableListOf<Node>()
        override fun read(node: Node, parentIndex: Int?): CaptureNode {
            visited.add(node)
            return CaptureNode(
                text = node.text,
                contentDescription = node.description,
                className = node.name,
                viewIdResourceName = "test:id/${node.name}",
                isClickable = true,
                isEditable = true,
                visibleToUser = node.visible,
                boundsInScreen = node.bounds,
                parentIndex = parentIndex,
            )
        }
        override fun childCount(node: Node) = node.children.size
        override fun child(node: Node, index: Int) = node.children[index]
    }

    private fun walk(root: Node) = NodeWalker().walk(root, Reader())

    @Test
    fun preservesContainersAndReverseSiblingDfsWithParentIndices() {
        val root = Node("root", children = listOf(
            Node("container", children = listOf(Node("caption", text = " caption "))),
            null,
            Node("button", description = " Like "),
        ))
        val summary = walk(root)
        assertEquals(listOf("root", "button", "container", "caption"), summary.nodes.map { it.className })
        assertEquals(listOf(null, 0, 0, 2), summary.nodes.map { it.parentIndex })
        assertEquals(4, summary.nodeCount)
        assertNull(summary.nodes[0].text)
        assertNull(summary.nodes[2].contentDescription)
        assertEquals(listOf("Like", "caption"), summary.texts)
        assertEquals("test:id/caption", summary.nodes[3].viewIdResourceName)
        assertTrue(summary.nodes[3].isClickable)
        assertTrue(summary.nodes[3].isEditable)
    }

    @Test
    fun keepsBothVisibilityValuesAndRawBounds() {
        val outside = BoundsInScreen(-500, -200, 3000, 4000)
        val summary = walk(Node("root", text = "offscreen", visible = false, bounds = outside,
            children = listOf(Node("child", text = "visible"))))
        assertEquals(2, summary.nodes.size)
        assertFalse(summary.nodes[0].visibleToUser)
        assertTrue(summary.nodes[1].visibleToUser)
        assertEquals(listOf("offscreen", "visible"), summary.texts)
        assertEquals(outside, summary.nodes[0].boundsInScreen)
        assertEquals(BoundsInScreen(0, 0, 100, 100), summary.nodes[1].boundsInScreen)
        assertEquals(outside, summary.nodes[0].boundsInScreen)
    }

    @Test
    fun limitsDeepTreeToFirst100VisitedNodesWithoutDanglingParents() {
        var root = Node("149", text = "149")
        for (i in 148 downTo 0) root = Node("$i", children = listOf(root))
        val reader = Reader()
        val summary = NodeWalker().walk(root, reader)
        assertEquals(100, summary.nodeCount)
        assertEquals(100, summary.nodes.size)
        assertEquals(100, reader.visited.size)
        assertEquals((0..99).map { "$it" }, summary.nodes.map { it.className })
        assertNull(summary.nodes.first().parentIndex)
        summary.nodes.drop(1).forEachIndexed { offset, node ->
            assertEquals(offset, node.parentIndex)
        }
        assertTrue(summary.texts.isEmpty())
    }

    @Test
    fun limitsWideTreeWithoutChangingSiblingOrder() {
        val summary = walk(Node("root", children = (0..149).map { Node("$it") }))
        assertEquals(listOf("root") + (149 downTo 51).map { "$it" }, summary.nodes.map { it.className })
        assertEquals(List(99) { 0 }, summary.nodes.drop(1).map { it.parentIndex })
    }

    @Test
    fun preservesTrimmingBlankFilteringTruncationAndDistinct() {
        val longText = "x".repeat(130)
        val summary = walk(Node("root", text = " \n ", children = listOf(
            Node("blank", description = "\t "),
            Node("duplicate", text = " same "),
            Node("content", text = " $longText ", description = " same "),
        )))
        assertEquals(listOf("x".repeat(120), "same"), summary.texts)
        assertEquals("x".repeat(120), summary.nodes[1].text)
        assertNull(summary.nodes[0].text)
        assertNull(summary.nodes[3].contentDescription)
        assertEquals(4, summary.nodes.size)
    }

    @Test
    fun duplicateTextsStillConsumeTheOriginalTextBudget() {
        val summary = walk(Node("root", children = listOf(Node("late", text = "excluded")) +
            (0..19).map { Node("$it", text = " repeated ") }))
        assertEquals(listOf("repeated"), summary.texts)
        assertEquals("excluded", summary.nodes.last().text)
    }

    @Test
    fun pairedInsertionAt19ThenDistinctRetainsOriginalBehavior() {
        val first = (0..18).map { Node("$it", text = "t$it") }
        val traversalOrder = first + Node("pair", text = "t0", description = " twentieth ") +
            Node("late", text = "excluded")
        val summary = walk(Node("root", children = traversalOrder.reversed()))
        assertEquals((0..18).map { "t$it" } + "twentieth", summary.texts)
        assertEquals(20, summary.texts.size)
        val unique = walk(Node("root", children = (first +
            Node("pair", text = "twentieth", description = "twenty-first")).reversed()))
        assertEquals((0..18).map { "t$it" } + "twentieth", unique.texts)
        assertEquals("twenty-first", unique.nodes.last().contentDescription)
    }

    @Test
    fun summaryRetainsRootContextAndSupportsUnavailableMetadata() {
        val empty = ScreenSummary()
        assertNull(empty.rootPackageName)
        assertNull(empty.windowBoundsInScreen)
        assertTrue(empty.nodes.isEmpty())
        val original = walk(Node("root"))
        val bounds = BoundsInScreen(-10, 20, 1080, 2400)
        val withMetadata = original.copy(rootPackageName = "root.package", windowBoundsInScreen = bounds)
        assertEquals("root.package", withMetadata.rootPackageName)
        assertEquals(bounds, withMetadata.windowBoundsInScreen)
        assertSame(original.nodes, withMetadata.nodes)
        assertSame(original.texts, withMetadata.texts)
        assertNull(withMetadata.copy(windowBoundsInScreen = null).windowBoundsInScreen)
    }
}
