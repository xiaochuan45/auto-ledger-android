package io.github.xiaochuan45.autoledger.capture

internal enum class ReceiptNodeAccess { REJECT, CHILDREN_ONLY, VISIBLE_FIELDS }

/** Selected mini receipts only. A hidden structural wrapper is not a hidden text permission. */
internal object SelectedReceiptNodePolicy {
    fun access(packageName: String?, expectedPackage: String, windowId: Int, expectedWindowId: Int,
        visible: Boolean, editable: Boolean, password: Boolean, className: String?, childCount: Int): ReceiptNodeAccess {
        if ((packageName != null && packageName != expectedPackage) || windowId != expectedWindowId ||
            editable || password || className?.contains("EditText", true) == true) return ReceiptNodeAccess.REJECT
        if (visible) return ReceiptNodeAccess.VISIBLE_FIELDS
        return if (childCount > 0 && className in STRUCTURAL_CONTAINERS) ReceiptNodeAccess.CHILDREN_ONLY
            else ReceiptNodeAccess.REJECT
    }
    private val STRUCTURAL_CONTAINERS = setOf("android.view.View", "android.view.ViewGroup",
        "android.widget.FrameLayout", "android.widget.LinearLayout", "android.widget.RelativeLayout",
        "android.widget.ScrollView", "android.widget.HorizontalScrollView", "android.webkit.WebView",
        "com.tencent.mm.ui.widget.MMWebView", "com.tencent.mm.ui.widget.MMWebViewWithJsApi",
        "com.tencent.xweb.WebView", "com.tencent.smtt.sdk.WebView")
}
