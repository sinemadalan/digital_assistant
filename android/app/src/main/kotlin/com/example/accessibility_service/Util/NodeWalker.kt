package com.example.accessibility_service.Util

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

class NodeWalker {

    companion object {
        private const val MAX_NODE_COUNT = 100
        private const val MAX_TEXT_COUNT = 20
        private const val MAX_TEXT_LENGTH = 120
    }

    // Local tests use this access seam to exercise the same traversal without Android mocks.
    internal interface NodeReader<T> {
        fun read(node: T, parentIndex: Int?): CaptureNode
        fun childCount(node: T): Int
        fun child(node: T, index: Int): T?
    }

    private data class StackEntry<T>(val node: T, val parentIndex: Int?)

    fun walk(rootNode: AccessibilityNodeInfo): ScreenSummary {
        val bounds = Rect() // Reused per traversal; each node retains an immutable snapshot.
        return walk(rootNode, object : NodeReader<AccessibilityNodeInfo> {
            override fun read(node: AccessibilityNodeInfo, parentIndex: Int?): CaptureNode {
                node.getBoundsInScreen(bounds)
                return CaptureNode(
                    text = node.text?.toString(),
                    contentDescription = node.contentDescription?.toString(),
                    className = node.className?.toString(),
                    viewIdResourceName = node.viewIdResourceName,
                    isClickable = node.isClickable,
                    isEditable = node.isEditable,
                    visibleToUser = node.isVisibleToUser,
                    boundsInScreen = BoundsInScreen(bounds.left, bounds.top, bounds.right, bounds.bottom),
                    parentIndex = parentIndex,
                )
            }

            override fun childCount(node: AccessibilityNodeInfo) = node.childCount
            override fun child(node: AccessibilityNodeInfo, index: Int) = node.getChild(index)
        })
    }

    internal fun <T> walk(rootNode: T, reader: NodeReader<T>): ScreenSummary {
        val texts = mutableListOf<String>()
        val nodes = mutableListOf<CaptureNode>()
        val stack = ArrayDeque<StackEntry<T>>()

        stack.add(StackEntry(rootNode, null))

        while (stack.isNotEmpty() && nodes.size < MAX_NODE_COUNT) {
            val (node, parentIndex) = stack.removeLast()
            val captured = reader.read(node, parentIndex)
            val nodeIndex = nodes.size

            val text = captured.text?.trim().orEmpty()
            val contentDescription = captured.contentDescription?.trim().orEmpty()

            // Preserve every visited node, including invisible and textless containers.
            nodes.add(
                captured.copy(
                    text = text.takeIf { it.isNotBlank() }?.take(MAX_TEXT_LENGTH),
                    contentDescription = contentDescription.takeIf { it.isNotBlank() }?.take(MAX_TEXT_LENGTH),
                )
            )

            // Preserve the original pre-distinct budget and paired insertion behavior.
            if (texts.size < MAX_TEXT_COUNT) {
                if (text.isNotBlank()) texts.add(text.take(MAX_TEXT_LENGTH))
                if (contentDescription.isNotBlank()) texts.add(contentDescription.take(MAX_TEXT_LENGTH))
            }

            for (index in 0 until reader.childCount(node)) {
                reader.child(node, index)?.let { childNode ->
                    stack.add(StackEntry(childNode, nodeIndex))
                }
            }
        }
        return ScreenSummary(
            nodeCount = nodes.size,
            texts = texts.distinct().take(MAX_TEXT_COUNT),
            nodes = nodes,
        )
    }
}
