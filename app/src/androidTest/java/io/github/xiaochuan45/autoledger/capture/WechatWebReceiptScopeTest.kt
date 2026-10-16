package io.github.xiaochuan45.autoledger.capture

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import android.webkit.WebViewClient
import android.accessibilityservice.AccessibilityServiceInfo
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xiaochuan45.autoledger.MainActivity
import io.github.xiaochuan45.autoledger.core.WechatWebReceipt
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Local HTML in our own test Activity, never a payment app or external web site. */
@RunWith(AndroidJUnit4::class)
class WechatWebReceiptScopeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Test fun completeLabelsInOneWebViewAreScopedWithoutReadingNativeChrome() {
        withPage(WechatWebReceipt.labels, false) { root ->
            val web = WechatWebReceiptScope.findReceiptRoot(root, instrumentation.targetContext.packageName, root.windowId)
            assertNotNull("The local receipt's fixed labels should identify its WebView", web)
            if (web != null) {
                assertEquals("android.webkit.WebView", web.className.toString())
                release(web)
            }
        }
    }

    @Test fun ordinaryWebPageCannotBorrowMissingReceiptColumnsFromNativeChrome() {
        withPage(listOf("虚构普通网页", "当前状态", "支付成功"), true) { root ->
            assertNull(WechatWebReceiptScope.findReceiptRoot(root, instrumentation.targetContext.packageName, root.windowId))
        }
    }

    @Test fun labelProbeNeverCopiesLongMatchedBodyText() {
        val body = object : CharSequence {
            override val length = 100_000
            override fun get(index: Int): Char = error("Body must not be inspected")
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = error("Body must not be inspected")
            override fun toString(): String = error("Body must not be copied")
        }
        assertFalse(WechatWebReceiptScope.isExactLabel(body, "当前状态"))
        assertFalse(WechatWebReceiptScope.isExactLabel("文章提到当前状态", "当前状态"))
        assertTrue(WechatWebReceiptScope.isExactLabel(" 当前状态 ", "当前状态"))
        val labelOnly = object : CharSequence {
            override val length = 4
            override fun get(index: Int): Char = "当前状态"[index]
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = error("No substring copies")
            override fun toString(): String = error("No label or field-value copies")
        }
        assertTrue(WechatWebReceiptScope.isExactLabel(labelOnly, "当前状态"))
    }

    private fun withPage(labels: List<String>, nativeLabels: Boolean, check: (AccessibilityNodeInfo) -> Unit) {
        val automation = instrumentation.uiAutomation
        // Connect before constructing WebView so its provider observes accessibility enabled.
        val originalInfo = automation.serviceInfo
        automation.serviceInfo = automation.serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        }
        val loaded = CountDownLatch(1)
        try {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                if (nativeLabels) WechatWebReceipt.labels.forEach { label ->
                    layout.addView(TextView(activity).apply { text = label })
                }
                val web = WebView(activity)
                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) { loaded.countDown() }
                }
                layout.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
                activity.setContentView(layout)
                // Only inert, fictional markup; no JS, network URLs or user's transaction data.
                web.loadDataWithBaseURL(null, "<html><meta name='viewport' content='width=device-width, initial-scale=1'><body>" +
                    "<p>FICTIONAL_SCOPE_READY</p>" + labels.joinToString("") { "<p>$it</p>" } +
                    "</body></html>", "text/html", "UTF-8", null)
            }
            assertTrue("Local HTML must finish loading", loaded.await(15, TimeUnit.SECONDS))
            val deadline = SystemClock.uptimeMillis() + 15_000
            while (SystemClock.uptimeMillis() < deadline) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                if (root != null) {
                    try {
                        if (hasFictionalSentinel(root)) { check(root); return }
                    } finally { release(root) }
                }
                SystemClock.sleep(100)
            }
            fail("Local WebView accessibility tree did not become available")
        }
        } finally { automation.serviceInfo = originalInfo }
    }

    /** Test-only traversal of our own fictional HTML; find-by-text is not implemented by every provider. */
    @Suppress("DEPRECATION")
    private fun hasFictionalSentinel(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(root))
        var visited = 0
        try {
            while (queue.isNotEmpty() && visited++ < 200) {
                val node = queue.removeFirst()
                try {
                    if (node.text?.toString()?.contains("FICTIONAL_SCOPE_READY") == true) return true
                    for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
                } finally { release(node) }
            }
            return false
        } finally { while (queue.isNotEmpty()) release(queue.removeFirst()) }
    }

    @Suppress("DEPRECATION")
    private fun release(node: AccessibilityNodeInfo) { node.recycle() }
}
