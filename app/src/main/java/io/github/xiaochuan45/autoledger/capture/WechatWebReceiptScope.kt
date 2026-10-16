package io.github.xiaochuan45.autoledger.capture

import android.view.accessibility.AccessibilityNodeInfo
import io.github.xiaochuan45.autoledger.core.WechatWebReceipt

/** Queries fixed labels only. An ordinary browser's body is never passed to the text reader. */
internal object WechatWebReceiptScope {
    private val webClasses = setOf("android.webkit.WebView", "com.tencent.mm.ui.widget.MMWebView",
        "com.tencent.mm.ui.widget.MMWebViewWithJsApi")

    /** Caller owns root; a returned WebView is a separate owned node and must be released. */
    fun findReceiptRoot(root: AccessibilityNodeInfo, expectedPackage: String, windowId: Int): AccessibilityNodeInfo? {
        val starts = root.findAccessibilityNodeInfosByText(WechatWebReceipt.labels.first()).orEmpty()
        try {
            if (starts.size > MAX_MATCHES) return null
            for (start in starts) {
                if (!matchesLabel(start, WechatWebReceipt.labels.first(), expectedPackage, windowId)) continue
                val web = webAncestor(start, expectedPackage, windowId) ?: continue
                var keep = false
                try {
                    // All markers must be inside the SAME WebView, not native chrome or a second page.
                    if (WechatWebReceipt.labels.all { hasSingleLabel(web, it, expectedPackage, windowId) }) {
                        keep = true
                        return web
                    }
                } finally { if (!keep) release(web) }
            }
            return null
        } finally { starts.forEach(::release) }
    }

    private fun hasSingleLabel(root: AccessibilityNodeInfo, label: String, expectedPackage: String, windowId: Int): Boolean {
        val matches = root.findAccessibilityNodeInfosByText(label).orEmpty()
        return try {
            matches.size <= MAX_MATCHES && matches.count { matchesLabel(it, label, expectedPackage, windowId) } == 1
        } finally { matches.forEach(::release) }
    }

    private fun matchesLabel(node: AccessibilityNodeInfo, label: String, expectedPackage: String, windowId: Int): Boolean =
        safeNode(node, expectedPackage, windowId) &&
            (isExactLabel(node.text, label) || isExactLabel(node.contentDescription, label))

    internal fun isExactLabel(value: CharSequence?, label: String): Boolean =
        value != null && value.length in label.length..(label.length + 4) && value.toString().trim() == label

    private fun safeNode(node: AccessibilityNodeInfo, expectedPackage: String, windowId: Int): Boolean =
        node.packageName?.toString() == expectedPackage && node.windowId == windowId && node.isVisibleToUser &&
            !node.isEditable && !node.isPassword && node.className?.toString()?.contains("EditText", true) != true

    private fun webAncestor(start: AccessibilityNodeInfo, expectedPackage: String, windowId: Int): AccessibilityNodeInfo? {
        var current = start.parent
        try {
            repeat(MAX_PARENTS) {
                val node = current ?: return null
                if (!safeNode(node, expectedPackage, windowId)) return null
                if (node.className?.toString() in webClasses) { current = null; return node }
                val parent = node.parent
                current = null
                release(node)
                current = parent
            }
            return null
        } finally { current?.let(::release) }
    }

    private const val MAX_MATCHES = 8
    private const val MAX_PARENTS = 12
    @Suppress("DEPRECATION")
    private fun release(node: AccessibilityNodeInfo) { node.recycle() }
}
