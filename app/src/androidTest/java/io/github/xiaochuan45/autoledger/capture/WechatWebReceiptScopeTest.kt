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
import org.junit.BeforeClass
import org.junit.AfterClass
import org.junit.Test
import org.junit.runner.RunWith
import java.util.ArrayDeque
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.os.ParcelFileDescriptor
import androidx.room.Room
import io.github.xiaochuan45.autoledger.data.LedgerDatabase
import io.github.xiaochuan45.autoledger.data.LedgerRepository
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first

/** Local HTML in our own test Activity, never a payment app or external web site. */
@RunWith(AndroidJUnit4::class)
class WechatWebReceiptScopeTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    companion object {
        private lateinit var fixtureAutomation: UiAutomation
        private lateinit var originalInfo: AccessibilityServiceInfo
        private var previousServices = "null"
        private var previousEnabled = "null"

        @BeforeClass @JvmStatic fun connectProviderSessionOnceForTheClass() {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val automation = instrumentation.getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
            fixtureAutomation = automation
            originalInfo = automation.serviceInfo
            previousServices = shell(automation, "settings get secure enabled_accessibility_services")
            previousEnabled = shell(automation, "settings get secure accessibility_enabled")
            // Retain the exact production flag and one stable non-suppressing connection across fixtures.
            automation.serviceInfo = automation.serviceInfo.apply { flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS }
            val component = instrumentation.targetContext.packageName + "/" + LedgerAccessibilityService::class.java.name
            val services = previousServices.takeUnless { it == "null" || it.isBlank() }?.split(':').orEmpty()
            shell(automation, "settings put secure enabled_accessibility_services " + settingToken((services + component).distinct().joinToString(":")))
            shell(automation, "settings put secure accessibility_enabled 1")
            val manager = instrumentation.targetContext.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
            fun ready() = CaptureServiceConnections.accessibilityConnected &&
                manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK).any {
                    it.resolveInfo.serviceInfo.name == LedgerAccessibilityService::class.java.name
                }
            val deadline = SystemClock.uptimeMillis() + 15_000
            while (!ready() && SystemClock.uptimeMillis() < deadline) SystemClock.sleep(100)
            assertTrue("The actual non-tool service must be connected before local provider fixtures", ready())
        }

        @AfterClass @JvmStatic fun restoreProviderSessionAfterAllFixtures() {
            if (!::fixtureAutomation.isInitialized) return
            val automation = fixtureAutomation
            restore(automation, "enabled_accessibility_services", previousServices)
            restore(automation, "accessibility_enabled", previousEnabled)
            if (::originalInfo.isInitialized) automation.serviceInfo = originalInfo
            InstrumentationRegistry.getInstrumentation().uiAutomation
        }

    // UiAutomation uses Runtime.exec argv tokenization, not shell parsing: quotes become literal data.
    private fun settingToken(value: String): String {
        require(Regex("[A-Za-z0-9_.$/:+-]+").matches(value))
        return value
    }
    private fun shell(automation: UiAutomation, command: String) =
        ParcelFileDescriptor.AutoCloseInputStream(automation.executeShellCommand(command)).bufferedReader().use { it.readText().trim() }
    private fun restore(automation: UiAutomation, name: String, previous: String) {
        shell(automation, if (previous == "null" || previous.isBlank()) "settings delete secure $name"
            else "settings put secure $name " + settingToken(previous))
    }

    }

    @Test fun selectedNativeMerchantDetailReadsInlineBeforeLauncherAndFlowsThroughScopeParserAndDedup() {
        val profile = listOf("虚构商户", "喜欢", "虚构店铺介绍", "服务", "会员", "交易详情",
            "虚构栏目一", "虚构栏目二", "虚构栏目三", "-42.70", "虚构商户").joinToString("\n")
        val rows = listOf(profile, "当前状态支付成功", "支付时间2025年3月12日 10:20:30",
            "支付方式零钱", "交易单号FICTIONAL-NATIVE-MINI-A")
        withPage(emptyList(), false, nativeRows = rows, nativeOnly = true) { root ->
            val request = OneShotWechatReceipt { 100L }.apply { arm() }
            var pieces: List<String>? = null
            var capturedSession: PaymentWindowSession? = null
            val capture = PaymentWindowCapture({ session ->
                assertTrue(request.beginWindowCheck(session.channel, session.screenClass))
                val selected = WechatWebReceiptScope.findReceiptRoot(root, instrumentation.targetContext.packageName,
                    root.windowId, selectedMini = session.explicitOnly)
                assertNotNull("Four native columns must be required before the body reader runs", selected)
                assertTrue(request.consume(session.channel, session.screenClass))
                assertFalse(request.consume(session.channel, session.screenClass))
                pieces = PaymentTextReader.read(requireNotNull(selected), instrumentation.targetContext.packageName, true)
                capturedSession = session
            }, { fail("Selected mini must not be deferred") }, {})
            assertNull(capture.event(PaymentChannel.WECHAT, WechatWebReceipt.MINI_WINDOW, root.windowId, true, 100, true))
            assertNotNull("Production callback must read before the following Launcher event", pieces)
            assertEquals(CaptureSkip.WINDOW_UNSUPPORTED, capture.event(PaymentChannel.WECHAT, "com.tencent.mm.ui.LauncherUI",
                root.windowId, true, 101, false))
            val session = requireNotNull(capturedSession)
            val parsed = requireNotNull(PaymentParser.parse(CaptureInput(CaptureSource.USER_WEB_RECEIPT,
                PaymentChannel.WECHAT.packageName, requireNotNull(pieces), 1_800_000_000_000L, session.token, screenClass = session.screenClass)))
            assertEquals("Fictional native fragments: $pieces", 4270L, parsed.amountMinor)
            assertEquals(TimeSource.PAYMENT, parsed.timeSource)
            assertEquals(RecordStatus.REVIEW, parsed.status)
            val database = Room.inMemoryDatabaseBuilder(instrumentation.targetContext, LedgerDatabase::class.java).build()
            try {
                runBlocking {
                    val repository = LedgerRepository(database)
                    val first = repository.ingest(parsed)
                    val replay = repository.ingest(parsed.copy(captureKey = "fictional-mini-reopen"))
                    assertEquals(first.recordId, replay.recordId)
                    assertEquals(1, repository.records.first().size)
                    assertEquals(RecordStatus.REVIEW.name, repository.getRecord(first.recordId)!!.status)
                }
            } finally { database.close() }
        }
    }

    @Test fun selectedMiniWithOrdinaryOrPartialFieldsIsRejectedBeforeAnyBodyRead() {
        withPage(emptyList(), false, nativeRows = listOf("虚构普通小程序", "当前状态支付成功", "支付时间2025年3月12日 10:20:30"),
            nativeOnly = true) { root ->
            assertNull(WechatWebReceiptScope.findReceiptRoot(root, instrumentation.targetContext.packageName,
                root.windowId, selectedMini = true))
            val result = requireNotNull(CaptureDiagnostics.snapshot().webInspection)
            assertTrue(result.nativeReceipt)
            assertEquals(WebReceiptRejection.MISSING_LABELS, result.reason)
            assertEquals(3, result.labelMask)
        }
    }

    @Test fun invisibleStructuralRootStillRoutesToVisibleReceiptFieldsWithoutReadingItsOwnText() {
        val rows = listOf("虚构商户\n-42.70", "当前状态支付成功", "支付时间2025年3月12日 10:20:30",
            "支付方式零钱", "交易单号FICTIONAL-INVISIBLE-WRAPPER-A")
        withPage(emptyList(), false, nativeRows = rows, nativeOnly = true, invisibleWrapper = true) { outer ->
            val root = requireNotNull(findHiddenWrapper(outer))
            try {
                assertFalse(root.isVisibleToUser)
                val scoped = WechatWebReceiptScope.findReceiptRoot(root, instrumentation.targetContext.packageName,
                    root.windowId, selectedMini = true, requestId = 7)
                assertNotNull(scoped)
                val stats = requireNotNull(CaptureDiagnostics.snapshot().webInspection)
                assertEquals(false, stats.rootVisible)
                assertTrue(stats.rootChildren >= 5)
                assertTrue(stats.hiddenContainers > 0)
                assertEquals(7L, stats.requestId)
                val pieces = requireNotNull(PaymentTextReader.read(requireNotNull(scoped),
                    instrumentation.targetContext.packageName, true, selectedMini = true))
                assertFalse(pieces.any { it.contains("FICTIONAL_HIDDEN_WRAPPER") })
                val parsed = requireNotNull(PaymentParser.parse(CaptureInput(CaptureSource.USER_WEB_RECEIPT,
                    PaymentChannel.WECHAT.packageName, pieces, 1_800_000_000_000L, "fictional-invisible-wrapper",
                    screenClass = WechatWebReceipt.MINI_WINDOW)))
                assertEquals(4270L, parsed.amountMinor)
                assertEquals(RecordStatus.REVIEW, parsed.status)
                // The exception is scoped to explicit mini receipts, not ordinary automatic readers.
                assertTrue(requireNotNull(PaymentTextReader.read(AccessibilityNodeInfo.obtain(root),
                    instrumentation.targetContext.packageName, true)).isEmpty())
            } finally { release(root) }
        }
    }

    @Test fun invisibleTextCannotProvideAMissingRequiredColumnThroughAHiddenWrapper() {
        val rows = listOf("当前状态支付成功", "支付时间2025年3月12日 10:20:30",
            "支付方式零钱", "交易单号FICTIONAL-HIDDEN-STATUS-A")
        withPage(emptyList(), false, nativeRows = rows, nativeOnly = true, invisibleWrapper = true,
            hiddenRowIndices = setOf(0)) { root ->
            assertNull(WechatWebReceiptScope.findReceiptRoot(root, instrumentation.targetContext.packageName,
                root.windowId, selectedMini = true))
            val stats = requireNotNull(CaptureDiagnostics.snapshot().webInspection)
            assertEquals(WebReceiptRejection.MISSING_LABELS, stats.reason)
            assertEquals(14, stats.labelMask)
            assertTrue(stats.hiddenContainers > 0)
        }
    }

    @Test fun entirelyInvisibleTreeCannotProduceAnyReceiptTextOrLabels() {
        val rows = WechatWebReceipt.requiredLabels
        withPage(emptyList(), false, nativeRows = rows, nativeOnly = true, invisibleWrapper = true,
            hiddenRowIndices = (0..rows.size).toSet()) { outer ->
            val root = requireNotNull(findHiddenWrapper(outer))
            try {
                assertNull(WechatWebReceiptScope.findReceiptRoot(root, instrumentation.targetContext.packageName,
                    root.windowId, selectedMini = true))
                val stats = requireNotNull(CaptureDiagnostics.snapshot().webInspection)
                assertEquals(WebReceiptRejection.FILTERED_NODES, stats.reason)
                assertEquals(0, stats.textNodes); assertEquals(0, stats.labelMask)
                assertTrue(stats.rootChildren > 0)
                assertTrue(requireNotNull(PaymentTextReader.read(AccessibilityNodeInfo.obtain(root),
                    instrumentation.targetContext.packageName, true, selectedMini = true)).isEmpty())
            } finally { release(root) }
        }
    }

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

    private fun withPage(labels: List<String>, nativeLabels: Boolean, html: String? = null,
        nativeRows: List<String> = emptyList(), nativeOnly: Boolean = false,
        invisibleWrapper: Boolean = false, hiddenRowIndices: Set<Int> = emptySet(), check: (AccessibilityNodeInfo) -> Unit) {
        val automation = fixtureAutomation
        val loaded = CountDownLatch(1)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var page: WebView? = null
            try {
            scenario.onActivity { activity ->
                val layout = object : LinearLayout(activity) {
                    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(info)
                        if (invisibleWrapper) {
                            info.className = "android.widget.LinearLayout"
                            info.isVisibleToUser = false
                            info.text = "FICTIONAL_HIDDEN_WRAPPER must never become a field"
                        }
                    }
                }.apply { orientation = LinearLayout.VERTICAL; importantForAccessibility = android.view.View.IMPORTANT_FOR_ACCESSIBILITY_YES }
                fun row(value: String, index: Int) = object : TextView(activity) {
                    override fun onInitializeAccessibilityNodeInfo(info: AccessibilityNodeInfo) {
                        super.onInitializeAccessibilityNodeInfo(info)
                        if (index in hiddenRowIndices) info.isVisibleToUser = false
                    }
                }.apply { text = value }
                if (nativeLabels) WechatWebReceipt.labels.forEach { label ->
                    layout.addView(TextView(activity).apply { text = label })
                }
                nativeRows.forEachIndexed { index, value -> layout.addView(row(value, index)) }
                if (nativeOnly) {
                    layout.addView(row("FICTIONAL_SCOPE_READY", nativeRows.size))
                    activity.setContentView(layout)
                    loaded.countDown()
                    return@onActivity
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
    }

    @Suppress("DEPRECATION")
    private fun findHiddenWrapper(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(AccessibilityNodeInfo.obtain(root))
        try {
            var count = 0
            while (queue.isNotEmpty() && count++ < 200) {
                val node = queue.removeFirst()
                var kept = false
                try {
                    if (!node.isVisibleToUser && node.className?.toString() == "android.widget.LinearLayout" && node.childCount > 0) {
                        kept = true; return node
                    }
                    repeat(node.childCount) { node.getChild(it)?.let(queue::addLast) }
                } finally { if (!kept) release(node) }
            }
            return null
        } finally { while (queue.isNotEmpty()) release(queue.removeFirst()) }
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
