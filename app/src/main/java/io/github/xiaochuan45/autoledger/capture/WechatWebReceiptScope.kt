package io.github.xiaochuan45.autoledger.capture

import android.view.accessibility.AccessibilityNodeInfo
import io.github.xiaochuan45.autoledger.core.WechatWebReceipt
import java.util.ArrayDeque

/** Bounded classification of a single browser subtree. No field values enter diagnostics. */
internal object WechatWebReceiptScope {
    private val webClasses = setOf("android.webkit.WebView", "com.tencent.mm.ui.widget.MMWebView",
        "com.tencent.mm.ui.widget.MMWebViewWithJsApi", "com.tencent.xweb.WebView", "com.tencent.smtt.sdk.WebView")

    private class Inspection {
        var native = 0; var web = 0; var texts = 0; var longTexts = 0; var nullPackages = 0; var mask = 0
        var foreign = 0; var otherWindows = 0; var invisible = 0; var protected = 0
        fun snapshot(reason: WebReceiptRejection) = WebReceiptInspection(reason, native, web, texts, longTexts, nullPackages, mask,
            foreign, otherWindows, invisible, protected)
    }

    /** Caller keeps root; returned browser is separately owned. Rejecting never calls the body reader. */
    @Suppress("DEPRECATION")
    fun findReceiptRoot(root: AccessibilityNodeInfo, expectedPackage: String, windowId: Int): AccessibilityNodeInfo? {
        val stats = Inspection()
        fun reject(reason: WebReceiptRejection): Nothing? {
            CaptureDiagnostics.webInspection(stats.snapshot(reason)); return null
        }
        // Missing provider package names are accepted only below this verified app/window boundary.
        if (root.packageName?.toString() != expectedPackage || root.windowId != windowId) return reject(WebReceiptRejection.FOREIGN_ROOT)
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(root))
        var candidate: AccessibilityNodeInfo? = null
        var keep = false
        try {
            while (queue.isNotEmpty()) {
                if (++stats.native > MAX_NATIVE_NODES) return reject(WebReceiptRejection.NATIVE_LIMIT)
                val node = queue.removeFirst()
                var retained = false
                try {
                    if (!safeNode(node, expectedPackage, windowId, stats)) continue
                    if (node.packageName == null) stats.nullPackages++
                    if (node.className?.toString() in webClasses) {
                        if (candidate != null) return reject(WebReceiptRejection.MULTIPLE_WEBVIEWS)
                        candidate = node; retained = true
                    } else {
                        val failed = enqueue(node, queue, MAX_NATIVE_NODES - stats.native)
                        if (failed != null) return reject(failed)
                    }
                } finally { if (!retained) release(node) }
            }
            val web = candidate ?: return reject(WebReceiptRejection.NO_WEBVIEW)
            queue.add(AccessibilityNodeInfo.obtain(web))
            while (queue.isNotEmpty()) {
                if (++stats.web > MAX_WEB_NODES) return reject(WebReceiptRejection.WEB_LIMIT)
                val node = queue.removeFirst()
                try {
                    if (!safeNode(node, expectedPackage, windowId, stats)) continue
                    if (node.packageName == null) stats.nullPackages++
                    val text = node.text
                    val desc = node.contentDescription
                    if (!text.isNullOrEmpty() || !desc.isNullOrEmpty()) stats.texts++
                    if ((text?.length ?: 0) > 160 || (desc?.length ?: 0) > 160) stats.longTexts++
                    WechatWebReceipt.requiredLabels.forEachIndexed { index, label ->
                        if (WechatWebReceipt.hasLabelPrefix(text, label) || WechatWebReceipt.hasLabelPrefix(desc, label)) {
                            // Accessible parent/child aliases are normal; they are not separate bills.
                            stats.mask = stats.mask or (1 shl index)
                        }
                    }
                    val failed = enqueue(node, queue, MAX_WEB_NODES - stats.web, web = true)
                    if (failed != null) return reject(failed)
                } finally { release(node) }
            }
            if (stats.texts == 0) return reject(if (stats.foreign + stats.otherWindows + stats.invisible + stats.protected > 0)
                WebReceiptRejection.FILTERED_NODES else WebReceiptRejection.NO_TEXT)
            if (stats.mask != (1 shl WechatWebReceipt.requiredLabels.size) - 1) return reject(WebReceiptRejection.MISSING_LABELS)
            CaptureDiagnostics.webInspection(stats.snapshot(WebReceiptRejection.MATCHED))
            keep = true
            return web
        } finally {
            while (queue.isNotEmpty()) release(queue.removeFirst())
            if (!keep) candidate?.let(::release)
        }
    }

    private fun enqueue(node: AccessibilityNodeInfo, queue: ArrayDeque<AccessibilityNodeInfo>, remaining: Int,
        web: Boolean = false): WebReceiptRejection? {
        if (node.childCount > remaining - queue.size) return if (web) WebReceiptRejection.WEB_LIMIT else WebReceiptRejection.NATIVE_LIMIT
        for (index in 0 until node.childCount) queue.addLast(node.getChild(index) ?: return WebReceiptRejection.NODE_UNAVAILABLE)
        return null
    }

    private fun safeNode(node: AccessibilityNodeInfo, expectedPackage: String, windowId: Int, stats: Inspection): Boolean {
        if (!ownedDescendantPackage(node.packageName?.toString(), expectedPackage)) { stats.foreign++; return false }
        if (node.windowId != windowId) { stats.otherWindows++; return false }
        if (!node.isVisibleToUser) { stats.invisible++; return false }
        if (node.isEditable || node.isPassword || node.className?.toString()?.contains("EditText", true) == true) {
            stats.protected++; return false
        }
        return true
    }

    internal fun ownedDescendantPackage(value: String?, expected: String) = value == null || value == expected
    private const val MAX_NATIVE_NODES = 256
    internal const val MAX_WEB_NODES = 512
    @Suppress("DEPRECATION") private fun release(node: AccessibilityNodeInfo) { node.recycle() }
}
