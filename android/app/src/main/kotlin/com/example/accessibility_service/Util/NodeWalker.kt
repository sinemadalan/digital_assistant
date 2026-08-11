package com.example.accessibility_service.Util

import android.view.accessibility.AccessibilityNodeInfo

class NodeWalker {

    companion object {
        private const val MAX_NODE_COUNT = 100
        private const val MAX_TEXT_COUNT = 20
        private const val MAX_TEXT_LENGTH = 120
    }
    public fun walk(rootNode: AccessibilityNodeInfo) : ScreenSummary {
        val texts = mutableListOf<String>()
        val nodes = mutableListOf<CaptureNode>()
        val stack = ArrayDeque<AccessibilityNodeInfo>()
        var visitedNodeCount = 0

        stack.add(rootNode)

        while (stack.isNotEmpty() && visitedNodeCount < MAX_NODE_COUNT) {
            val node = stack.removeLast()
            visitedNodeCount += 1

            val text = node.text?.toString()?.trim().orEmpty()
            val contentDescription = node.contentDescription?.toString()?.trim().orEmpty()

            if (text.isNotBlank() || contentDescription.isNotBlank()) {
                nodes.add(
                    CaptureNode(
                        text = text.takeIf { it.isNotBlank() }?.take(MAX_TEXT_LENGTH),
                        contentDescription = contentDescription.takeIf { it.isNotBlank() }?.take(MAX_TEXT_LENGTH),
                        className = node.className?.toString(),
                        viewIdResourceName = node.viewIdResourceName,
                        isClickable = node.isClickable,
                        isEditable = node.isEditable,
                    )
                )
            }

            if (texts.size < MAX_TEXT_COUNT) {
                if (text.isNotBlank()) texts.add(text.take(MAX_TEXT_LENGTH))
                if (contentDescription.isNotBlank()) texts.add(contentDescription.take(MAX_TEXT_LENGTH))
            }

            for (index in 0 until node.childCount) {
                node.getChild(index)?.let { childNode ->
                    stack.add(childNode)
                }
            }
        }
        return ScreenSummary(
            nodeCount = visitedNodeCount,
            texts = texts.distinct().take(MAX_TEXT_COUNT),
            nodes = nodes.take(MAX_NODE_COUNT),
        )

    }
}