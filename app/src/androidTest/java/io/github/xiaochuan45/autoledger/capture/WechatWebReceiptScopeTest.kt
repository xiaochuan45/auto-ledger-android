package io.github.xiaochuan45.autoledger.capture

import android.os.SystemClock
import android.os.Build
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.UiAutomation
import android.content.Context
import android.view.accessibility.AccessibilityManager
import android.view.accessibility.AccessibilityNodeInfo
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.LinearLayout
import android.widget.TextView
import android.view.ViewGroup
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xiaochuan45.autoledger.MainActivity
import io.github.xiaochuan45.autoledger.core.WechatWebReceipt
import io.github.xiaochuan45.autoledger.core.CaptureInput
import io.github.xiaochuan45.autoledger.core.CaptureSource
import io.github.xiaochuan45.autoledger.core.PaymentChannel
import io.github.xiaochuan45.autoledger.core.PaymentParser
import io.github.xiaochuan45.autoledger.core.RecordStatus
import io.github.xiaochuan45.autoledger.core.TimeSource
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.os.ParcelFileDescriptor

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

    @Test fun mergedRowsAndNestedHeaderPassScopeReaderAndParserInDocumentOrder() {
        val html = "<section><div><div><strong>虚构商店</strong><p>-42.70</p></div></div></section>" +
            "<p>当前状态支付成功</p><p>支付时间2025年3月12日 10:20:30</p>" +
            "<p>支付方式零钱</p><p>交易单号FICTIONAL-DOM-MERGED-A</p>"
        withPage(emptyList(), false, html) { root ->
            val web = WechatWebReceiptScope.findReceiptRoot(root, instrumentation.targetContext.packageName, root.windowId)
            assertNotNull("Merged rows without optional footer must be a candidate", web)
            val pieces = PaymentTextReader.read(requireNotNull(web), instrumentation.targetContext.packageName, true)
            val value = PaymentParser.parse(CaptureInput(CaptureSource.ACCESSIBILITY, PaymentChannel.WECHAT.packageName,
                requireNotNull(pieces), 1_800_000_000_000L, "fictional-local-dom", screenClass = WechatWebReceipt.WINDOW))
            assertNotNull(value)
            // Only this inert fixture's literal fictional fields can appear in an assertion report.
            assertEquals("Fictional HTML fragments: $pieces", 4270L, value!!.amountMinor)
            assertEquals("FICTIONAL-DOM-MERGED-A", value.transactionId)
            assertEquals(TimeSource.PAYMENT, value.timeSource)
            assertEquals(RecordStatus.REVIEW, value.status)
        }
    }

    @Test fun repeatedParentLabelDoesNotLookLikeMultipleReceipts() {
        val html = "<div role='group' aria-label='当前状态'><p>当前状态</p><p>支付成功</p></div>" +
            "<p>支付时间2025年3月12日 10:20:30</p><p>支付方式零钱</p><p>交易单号FICTIONAL-DOM-DUPLICATE-A</p>"
        withPage(emptyList(), false, html) { root ->
            val web = WechatWebReceiptScope.findReceiptRoot(root, instrumentation.targetContext.packageName, root.windowId)
            assertNotNull(web)
            if (web != null) release(web)
            assertEquals(WebReceiptRejection.MATCHED, CaptureDiagnostics.snapshot().webInspection?.reason)
        }
    }

    @Test fun scopeFailureSummaryOnlyContainsFixedLabelsAndCounts() {
        withPage(listOf("虚构不应保留的页面内容", "当前状态 支付成功"), false) { root ->
            assertNull(WechatWebReceiptScope.findReceiptRoot(root, instrumentation.targetContext.packageName, root.windowId))
            val status = requireNotNull(CaptureDiagnostics.snapshot().webInspection)
            assertEquals(WebReceiptRejection.MISSING_LABELS, status.reason)
            assertTrue(status.textNodes > 0)
            assertEquals(1, status.labelMask)
            assertFalse(status.summary().contains("虚构不应保留的页面内容"))
            assertTrue(status.summary().contains("交易单号=未见"))
        }
        assertTrue(WechatWebReceiptScope.ownedDescendantPackage(null, "fictional.package"))
        assertFalse(WechatWebReceiptScope.ownedDescendantPackage("other.package", "fictional.package"))
    }

    @Test fun labelProbeNeverCopiesLongMatchedBodyText() {
        val body = object : CharSequence {
            override val length = 100_000
            override fun get(index: Int): Char = error("Body must not be inspected")
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = error("Body must not be inspected")
            override fun toString(): String = error("Body must not be copied")
        }
        assertFalse(WechatWebReceipt.hasLabelPrefix(body, "当前状态"))
        assertFalse(WechatWebReceipt.hasLabelPrefix("文章提到当前状态", "当前状态"))
        assertTrue(WechatWebReceipt.hasLabelPrefix(" 当前状态 ", "当前状态"))
        val labelOnly = object : CharSequence {
            override val length = 4
            override fun get(index: Int): Char = "当前状态"[index]
            override fun subSequence(startIndex: Int, endIndex: Int): CharSequence = error("No substring copies")
            override fun toString(): String = error("No label or field-value copies")
        }
        assertTrue(WechatWebReceipt.hasLabelPrefix(labelOnly, "当前状态"))
    }

    private fun withPage(labels: List<String>, nativeLabels: Boolean, html: String? = null, check: (AccessibilityNodeInfo) -> Unit) {
        val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        // Connect before constructing WebView so its provider observes accessibility enabled.
        val originalInfo = automation.serviceInfo
        // Match exactly the production service's single node-visibility flag; no all-window access.
        automation.serviceInfo = automation.serviceInfo.apply { flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS }
        val loaded = CountDownLatch(1)
        val previousServices = shell(automation, "settings get secure enabled_accessibility_services")
        val previousEnabled = shell(automation, "settings get secure accessibility_enabled")
        try {
        // Chrome can report no accessibility services when only the hidden UiAutomation service exists.
        // Enable the real, non-tool app service in this emulator; its package filter excludes our HTML.
        val component = instrumentation.targetContext.packageName + "/" + LedgerAccessibilityService::class.java.name
        val services = previousServices.takeUnless { it == "null" || it.isBlank() }?.split(':').orEmpty()
        shell(automation, "settings put secure enabled_accessibility_services " + quote((services + component).distinct().joinToString(":")))
        shell(automation, "settings put secure accessibility_enabled 1")
        val manager = instrumentation.targetContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        val enabledDeadline = SystemClock.uptimeMillis() + 10_000
        while (SystemClock.uptimeMillis() < enabledDeadline &&
            !manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
                it.resolveInfo.serviceInfo.name == LedgerAccessibilityService::class.java.name
            }) SystemClock.sleep(100)
        assertTrue("The emulator must expose our actual service before WebView initialization",
            manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
                it.resolveInfo.serviceInfo.name == LedgerAccessibilityService::class.java.name
            })
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var page: WebView? = null
            try {
            scenario.onActivity { activity ->
                val layout = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                if (nativeLabels) WechatWebReceipt.labels.forEach { label ->
                    layout.addView(TextView(activity).apply { text = label })
                }
                val web = WebView(activity)
                page = web
                web.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) { loaded.countDown() }
                }
                layout.addView(web, LinearLayout.LayoutParams(-1, 0, 1f))
                activity.setContentView(layout)
                // Only inert, fictional markup; no JS, network URLs or user's transaction data.
                web.loadDataWithBaseURL(null, "<html><meta name='viewport' content='width=device-width, initial-scale=1'><body>" +
                    "<p>FICTIONAL_SCOPE_READY</p>" + (html ?: labels.joinToString("") { "<p>$it</p>" }) +
                    "</body></html>", "text/html", "UTF-8", null)
            }
            assertTrue("Local HTML must finish loading", loaded.await(15, TimeUnit.SECONDS))
            val deadline = SystemClock.uptimeMillis() + 15_000
            var observedRoot = "no active root"
            while (SystemClock.uptimeMillis() < deadline) {
                // A new Activity/WebView must not reuse the preceding fixture's cached virtual tree.
                if (Build.VERSION.SDK_INT >= 33) automation.clearCache()
                val root = automation.rootInActiveWindow
                if (root != null) {
                    try {
                        observedRoot = "ownPackage=${root.packageName?.toString() == instrumentation.targetContext.packageName}; children=${root.childCount}"
                        if (hasFictionalSentinel(root)) { check(root); return }
                    } finally { release(root) }
                }
                SystemClock.sleep(100)
            }
            fail("Local WebView accessibility tree did not become available: $observedRoot")
            } finally {
                // Activity destruction alone does not destroy a WebView or release its provider.
                scenario.onActivity {
                    page?.let { web ->
                        (web.parent as? ViewGroup)?.removeView(web)
                        web.destroy()
                    }
                }
            }
        }
        } finally {
            restore(automation, "enabled_accessibility_services", previousServices)
            restore(automation, "accessibility_enabled", previousEnabled)
            automation.serviceInfo = originalInfo
            // Restore the ordinary test connection, including its normal service suppression.
            instrumentation.uiAutomation
        }
    }

    private fun quote(value: String) = "'" + value.replace("'", "'\\''") + "'"
    private fun shell(automation: UiAutomation, command: String) =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
    private fun restore(automation: UiAutomation, name: String, previous: String) {
        shell(automation, if (previous == "null") "settings delete secure $name"
            else "settings put secure $name " + quote(previous))
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
                    if (node.text?.toString()?.contains("FICTIONAL_SCOPE_READY") == true ||
                        node.contentDescription?.toString()?.contains("FICTIONAL_SCOPE_READY") == true) return true
                    for (index in 0 until node.childCount) node.getChild(index)?.let(queue::addLast)
                } finally { release(node) }
            }
            return false
        } finally { while (queue.isNotEmpty()) release(queue.removeFirst()) }
    }

    @Suppress("DEPRECATION")
    private fun release(node: AccessibilityNodeInfo) { node.recycle() }
}
