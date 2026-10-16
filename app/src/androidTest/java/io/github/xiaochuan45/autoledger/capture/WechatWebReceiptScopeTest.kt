package io.github.xiaochuan45.autoledger.capture

import android.os.SystemClock
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
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
    }

    private fun withPage(labels: List<String>, nativeLabels: Boolean, check: (AccessibilityNodeInfo) -> Unit) {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                if (nativeLabels) WechatWebReceipt.labels.forEach { label ->
                    layout.addView(TextView(activity).apply { text = label })
                }
                val web = WebView(activity)
                layout.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
                activity.setContentView(layout)
                // Only inert, fictional markup; no JS, network URLs or user's transaction data.
                web.loadData("<html><meta name='viewport' content='width=device-width, initial-scale=1'><body>" +
                    "<p>FICTIONAL_SCOPE_READY</p>" + labels.joinToString("") { "<p>$it</p>" } +
                    "</body></html>", "text/html; charset=utf-8", "UTF-8")
            }
            val deadline = SystemClock.uptimeMillis() + 15_000
            while (SystemClock.uptimeMillis() < deadline) {
                val root = instrumentation.uiAutomation.rootInActiveWindow
                if (root != null) {
                    var ready = false
                    try {
                        val sentinels = root.findAccessibilityNodeInfosByText("FICTIONAL_SCOPE_READY").orEmpty()
                        ready = sentinels.isNotEmpty()
                        sentinels.forEach(::release)
                        if (ready) { check(root); return }
                    } finally { release(root) }
                }
                SystemClock.sleep(100)
            }
            fail("Local WebView accessibility tree did not become available")
        }
    }

    @Suppress("DEPRECATION")
    private fun release(node: AccessibilityNodeInfo) { node.recycle() }
}
