package io.github.xiaochuan45.autoledger.capture

import android.view.accessibility.AccessibilityNodeInfo
import io.github.xiaochuan45.autoledger.core.CaptureSource
import io.github.xiaochuan45.autoledger.core.WechatWebReceipt
import java.util.ArrayDeque

/** Owns and releases root. Native behavior is unchanged; web text follows document order. */
internal object PaymentTextReader {
    fun read(root: AccessibilityNodeInfo, expectedPackage: String, webReceipt: Boolean = false,
        selectedMini: Boolean = false): List<String>? {
        val expectedWindowId = root.windowId
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        val texts = TransientTextBuffer(if (webReceipt) 48 else 24)
        val limit = if (webReceipt) WechatWebReceiptScope.MAX_WEB_NODES else 150
        var visited = 0
        queue.add(root)
        try {
            while (queue.isNotEmpty() && visited < limit && !texts.incomplete) {
                val node = queue.removeFirst()
                visited++
                try {
                    val pkg = node.packageName?.toString()
                    if (pkg != null && pkg != expectedPackage) continue
                    if (selectedMini && node.windowId != expectedWindowId) continue
                    if (node.isPassword || node.isEditable || node.className?.toString()?.contains("EditText", true) == true) continue
                    val visible = node.isVisibleToUser
                    if (!visible && !(webReceipt && selectedMini && SelectedReceiptNodePolicy.access(pkg, expectedPackage,
                            node.windowId, expectedWindowId, false, false, false, node.className?.toString(),
                            node.childCount) == ReceiptNodeAccess.CHILDREN_ONLY)) continue
                    // Hidden structural wrappers may route to visible children; never query their own text/description.
                    val text = if (visible) node.text else null
                    val desc = if (visible) node.contentDescription else null
                    if (hasSensitiveText(text) || hasSensitiveText(desc)) {
                        CaptureDiagnostics.skipped(CaptureSkip.SENSITIVE_GUARD, CaptureSource.ACCESSIBILITY)
                        return null
                    }
                    val count = node.childCount
                    // Container summaries duplicate/reorder their children. Keep leaves and explicit field rows.
                    if (!webReceipt || count == 0 || WechatWebReceipt.labels.any { WechatWebReceipt.hasLabelPrefix(text, it) }) texts.add(text)
                    if (!webReceipt || count == 0 || WechatWebReceipt.labels.any { WechatWebReceipt.hasLabelPrefix(desc, it) }) texts.add(desc)
                    if (count > limit - visited - queue.size) { texts.markIncomplete(); continue }
                    val indices: IntProgression = if (webReceipt) count - 1 downTo 0 else 0 until count
                    for (index in indices) {
                        val child = node.getChild(index)
                        if (child == null) { texts.markIncomplete(); break }
                        if (webReceipt) queue.addFirst(child) else queue.addLast(child)
                    }
                } finally { release(node) }
            }
            if (queue.isNotEmpty()) texts.markIncomplete()
            if (texts.incomplete) { CaptureDiagnostics.failed(CaptureFailure.SKIPPED_INCOMPLETE); return null }
            return texts.toList()
        } finally { while (queue.isNotEmpty()) release(queue.removeFirst()) }
    }

    @Suppress("DEPRECATION") private fun release(node: AccessibilityNodeInfo) { node.recycle() }
}
