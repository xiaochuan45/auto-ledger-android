package io.github.xiaochuan45.autoledger.capture

import android.view.accessibility.AccessibilityNodeInfo
import io.github.xiaochuan45.autoledger.core.WechatWebReceipt
import java.util.ArrayDeque

/** Bounded label comparison, not text search: WebView providers may not implement find-by-text. */
internal object WechatWebReceiptScope {
    private val webClasses = setOf("android.webkit.WebView", "com.tencent.mm.ui.widget.MMWebView",
        "com.tencent.mm.ui.widget.MMWebViewWithJsApi", "com.tencent.xweb.WebView", "com.tencent.smtt.sdk.WebView")

    /** Caller owns root; a returned WebView is a separate owned node and must be released. */
    @Suppress("DEPRECATION")
    fun findReceiptRoot(root: AccessibilityNodeInfo, expectedPackage: String, windowId: Int): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(root))
        var candidate: AccessibilityNodeInfo? = null
        var keep = false
        var visited = 0
        try {
            // Inspect native structure only, never native chrome text. Multiple WebViews are ambiguous.
            while (queue.isNotEmpty()) {
                if (++visited > MAX_NODES) return null
                val node = queue.removeFirst()
                var retained = false
                try {
                    if (!safeNode(node, expectedPackage, windowId)) continue
                    if (node.className?.toString() in webClasses) {
                        if (candidate != null) return null
                        candidate = node
                        retained = true
                    } else if (!enqueueChildren(node, queue, MAX_NODES - visited)) return null
                } finally { if (!retained) release(node) }
            }
            val web = candidate ?: return null
            if (!hasCompleteLabels(web, expectedPackage, windowId)) return null
            keep = true
            return web
        } finally {
            while (queue.isNotEmpty()) release(queue.removeFirst())
            if (!keep) candidate?.let(::release)
        }
    }

    @Suppress("DEPRECATION")
    private fun hasCompleteLabels(root: AccessibilityNodeInfo, expectedPackage: String, windowId: Int): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(root))
        val counts = IntArray(WechatWebReceipt.labels.size)
        var visited = 0
        try {
            while (queue.isNotEmpty()) {
                if (++visited > MAX_NODES) return false
                val node = queue.removeFirst()
                try {
                    if (!safeNode(node, expectedPackage, windowId)) continue
                    // Only compare short exact field labels. Never copy a non-matching value or long body.
                    val text = node.text
                    val description = node.contentDescription
                    WechatWebReceipt.labels.forEachIndexed { index, label ->
                        if (isExactLabel(text, label) || isExactLabel(description, label)) {
                            counts[index]++
                            if (counts[index] > 1) return false
                        }
                    }
                    if (!enqueueChildren(node, queue, MAX_NODES - visited)) return false
                } finally { release(node) }
            }
            return counts.all { it == 1 }
        } finally { while (queue.isNotEmpty()) release(queue.removeFirst()) }
    }

    internal fun isExactLabel(value: CharSequence?, label: String): Boolean {
        if (value == null || value.length !in label.length..(label.length + 4)) return false
        var first = 0
        var last = value.length
        while (first < last && value[first].isWhitespace()) first++
        while (last > first && value[last - 1].isWhitespace()) last--
        return last - first == label.length && label.indices.all { value[first + it] == label[it] }
    }

    private fun enqueueChildren(node: AccessibilityNodeInfo, queue: ArrayDeque<AccessibilityNodeInfo>, remaining: Int): Boolean {
        if (node.childCount > remaining - queue.size) return false
        for (index in 0 until node.childCount) queue.addLast(node.getChild(index) ?: return false)
        return true
    }

    private fun safeNode(node: AccessibilityNodeInfo, expectedPackage: String, windowId: Int): Boolean =
        node.packageName?.toString() == expectedPackage && node.windowId == windowId && node.isVisibleToUser &&
            !node.isEditable && !node.isPassword && node.className?.toString()?.contains("EditText", true) != true

    private const val MAX_NODES = 150
    @Suppress("DEPRECATION")
    private fun release(node: AccessibilityNodeInfo) { node.recycle() }
}
