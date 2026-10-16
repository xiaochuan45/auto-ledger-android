package io.github.xiaochuan45.autoledger

import android.content.ComponentName

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import io.github.xiaochuan45.autoledger.capture.CapturePreferences
import io.github.xiaochuan45.autoledger.capture.CaptureDiagnostics
import io.github.xiaochuan45.autoledger.capture.LedgerNotificationService
import io.github.xiaochuan45.autoledger.capture.PermissionHelper
import io.github.xiaochuan45.autoledger.core.CaptureSource
import io.github.xiaochuan45.autoledger.core.EntryKind
import io.github.xiaochuan45.autoledger.core.ParsedPayment
import io.github.xiaochuan45.autoledger.core.PaymentChannel
import io.github.xiaochuan45.autoledger.core.RecordStatus
import io.github.xiaochuan45.autoledger.core.TimeSource
import io.github.xiaochuan45.autoledger.data.LedgerRepository
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** All ledger data in these UI tests is fictional; never run against a user's installed ledger. */
@RunWith(AndroidJUnit4::class)
class LaunchSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()
    private val repository get() = LedgerRepository.get(compose.activity)

    @Before
    fun clearVisibleFictionalRecords() {
        CaptureDiagnostics.clearCompatibilityCheck()
        CapturePreferences(compose.activity).setEnabled(false)
        runBlocking { repository.records.first().forEach { repository.delete(it.id) } }
    }

    @After
    fun removeFictionalRecords() {
        CaptureDiagnostics.clearCompatibilityCheck()
        runBlocking { repository.records.first().forEach { repository.delete(it.id) } }
    }

    @Test
    fun compatibilityCheckCanBeStartedStoppedAndCopiedWithoutLedgerText() {
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithTag("start_compatibility").performScrollTo().performClick()
        compose.onNodeWithTag("compatibility_status").assertTextContains("检查中 · 暂停自动记录")
        CaptureDiagnostics.windowEvent(PaymentChannel.WECHAT, "com.tencent.mm.ui.FictionalProbeUI", false)
        compose.onNodeWithText("刷新检查结果").performScrollTo().performClick()
        compose.onNodeWithText("微信 · com.tencent.mm.ui.FictionalProbeUI").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("copy_compatibility").performScrollTo().performClick()
        compose.onNodeWithText("已复制诊断摘要").assertIsDisplayed()
        val clipboard = compose.activity.getSystemService(android.content.ClipboardManager::class.java)
        val report = clipboard.primaryClip!!.getItemAt(0).text.toString()
        org.junit.Assert.assertTrue(report.contains("com.tencent.mm.ui.FictionalProbeUI"))
        org.junit.Assert.assertFalse(report.contains("商户"))
        compose.onNodeWithTag("stop_compatibility").performScrollTo().performClick()
        compose.onNodeWithTag("compatibility_status").assertTextContains("检查未运行")
        runBlocking { org.junit.Assert.assertTrue(repository.records.first().isEmpty()) }
    }

    @Test
    fun emptyLedgerAndDestinationsExplainPrototypeScope() {
        compose.onNodeWithText("我的账单").assertIsDisplayed()
        compose.onNodeWithText("还没有账单").assertIsDisplayed()
        compose.onNodeWithText("统计").performClick()
        compose.onNodeWithText("开销统计").assertIsDisplayed()
        compose.onNodeWithText("已确认支出总额").assertIsDisplayed()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("设置与状态").assertIsDisplayed()
        compose.onNodeWithText("自动记账总开关").assertIsDisplayed()
        compose.onNodeWithText("账单").performClick()
        compose.onNodeWithText("我的账单").assertIsDisplayed()
    }

    @Test
    fun recoveryWithoutAuthorizationExplainsThatNoRequestWasSent() {
        org.junit.Assert.assertFalse("This test requires the clean emulator's ungranted notification access", PermissionHelper.snapshot(compose.activity).notificationAccess)
        val beforeRequests = CaptureDiagnostics.snapshot().notificationReconnectRequests
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithTag("recover_notification_listener").performScrollTo().performClick()
        compose.onNodeWithTag("notification_recovery_result").performScrollTo()
            .assertTextContains("尚未授予通知使用权，未发起恢复。请先打开通知权限设置。")
        compose.onNodeWithTag("notification_connection_status")
            .assertTextContains("未授权 · 服务未连接")
        org.junit.Assert.assertEquals(beforeRequests, CaptureDiagnostics.snapshot().notificationReconnectRequests)
    }

    @Test
    @SdkSuppress(minSdkVersion = 29)
    fun notificationConnectionChangesUpdateTheOpenSettingsPage() {
        // A real service bind/unbind proves the page observes callbacks after its initial snapshot.
        // cmd notification is used only by the clean CI emulator; capture stays disabled throughout.
        org.junit.Assert.assertFalse("Do not change an existing user's notification grant", PermissionHelper.snapshot(compose.activity).notificationAccess)
        val component = ComponentName(compose.activity, LedgerNotificationService::class.java).flattenToString()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithTag("notification_connection_status").performScrollTo()
            .assertTextContains("未授权 · 服务未连接")
        try {
            shellCommand("cmd notification allow_listener $component")
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(hasTestTag("notification_connection_status") and hasText("已授权 · 服务已连接")).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("notification_connection_status").assertTextContains("已授权 · 服务已连接")
            org.junit.Assert.assertFalse(CapturePreferences(compose.activity).read().enabled)
            shellCommand("cmd notification disallow_listener $component")
            compose.waitUntil(timeoutMillis = 10_000) {
                compose.onAllNodes(hasTestTag("notification_connection_status") and hasText("服务未连接", substring = true)).fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithTag("notification_connection_status").assertTextContains("服务未连接", substring = true)
        } finally {
            shellCommand("cmd notification disallow_listener $component")
        }
    }

    @Test
    fun manualEntryCanBeEditedDeletedAndUndone() {
        val merchant = "虚构测试餐馆 ${UUID.randomUUID().toString().take(5)}"
        compose.onNodeWithContentDescription("手动补记").performClick()
        compose.onNodeWithTag("edit_amount").performTextReplacement("23.50")
        compose.onNodeWithTag("edit_time").performScrollTo().performTextReplacement("2026-10-16 12:30:00")
        compose.onNodeWithTag("edit_merchant").performScrollTo().performTextReplacement(merchant)
        compose.onNodeWithTag("edit_platform").performScrollTo().performTextReplacement("美团")
        compose.onNodeWithTag("select_channel_WECHAT").performScrollTo().performClick()
        compose.onNodeWithTag("edit_category").performScrollTo().performTextReplacement("餐饮")
        compose.onNodeWithTag("save_bill").performClick()
        waitForText(merchant)
        compose.onNodeWithText("23.50 元").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(merchant).performScrollTo().performClick()
        compose.onNodeWithTag("edit_bill").performClick()
        compose.onNodeWithTag("edit_amount").performTextReplacement("19.80")
        compose.onNodeWithTag("save_bill").performClick()
        waitForText("19.80 元")
        compose.onNodeWithText(merchant).performScrollTo().performClick()
        compose.onNodeWithTag("delete_bill").performScrollTo().performClick()
        waitForText("撤销")
        compose.onNodeWithText("撤销").performClick()
        waitForText(merchant)
        compose.onNodeWithText("19.80 元").performScrollTo().assertIsDisplayed()
        runBlocking {
            val bill = repository.records.first { rows -> rows.any { it.merchant == merchant } }.single { it.merchant == merchant }
            org.junit.Assert.assertEquals(1980L, bill.amountMinor)
            org.junit.Assert.assertEquals("美团", bill.platform)
            org.junit.Assert.assertEquals("餐饮", bill.category)
        }
    }

    @Test
    fun reviewRecordDoesNotCountUntilUserConfirmsSuccessfulExpense() {
        val key = UUID.randomUUID().toString()
        val merchant = "虚构待核对商户 ${key.take(5)}"
        runBlocking {
            repository.ingest(ParsedPayment(
                amountMinor = 1250L,
                channel = PaymentChannel.ALIPAY,
                merchant = merchant,
                platform = null,
                occurredAt = System.currentTimeMillis(),
                timeSource = TimeSource.OBSERVED,
                kind = EntryKind.EXPENSE,
                status = RecordStatus.REVIEW,
                transactionId = null,
                captureKey = "ui-review-$key",
                contentFingerprint = "ui-review-fingerprint-$key",
                source = CaptureSource.NOTIFICATION,
                reviewReason = "虚构样本：交易状态待核对",
            ))
        }
        compose.onNodeWithTag("filter_2").performClick()
        waitForText(merchant)
        compose.onNodeWithText(merchant).performScrollTo().performClick()
        compose.onNodeWithTag("review_record").performScrollTo().performClick()
        compose.onNodeWithTag("save_bill").performClick()
        waitForText("没有待核对账单")
        compose.onNodeWithTag("filter_1").performClick()
        waitForText(merchant)
        compose.onNodeWithText("支出 · 已确认 · 未分类").assertIsDisplayed()
        runBlocking {
            val confirmed = repository.records.first().single { it.merchant == merchant }
            org.junit.Assert.assertEquals(TimeSource.OBSERVED.name, confirmed.timeSource)
        }
        compose.onNodeWithText("统计").performClick()
        compose.onNodeWithText("12.50 元 · 1 笔\n全部本地记录，不限日期。").assertIsDisplayed()
    }

    @Test
    fun suspectedDuplicateCanBeMergedByExplicitUserChoice() {
        val key = UUID.randomUUID().toString()
        val merchant = "虚构重复商户 ${key.take(5)}"
        val observed = System.currentTimeMillis()
        runBlocking {
            val first = ParsedPayment(
                amountMinor = 850L, channel = PaymentChannel.WECHAT,
                merchant = merchant, platform = null, occurredAt = observed,
                timeSource = TimeSource.OBSERVED, kind = EntryKind.EXPENSE,
                status = RecordStatus.CONFIRMED, transactionId = null,
                captureKey = "ui-first-$key", contentFingerprint = "ui-first-$key",
                source = CaptureSource.NOTIFICATION,
            )
            repository.ingest(first)
            repository.ingest(first.copy(
                captureKey = "ui-second-$key", contentFingerprint = "ui-second-$key",
                source = CaptureSource.ACCESSIBILITY, occurredAt = observed + 1000,
            ))
        }
        compose.onNodeWithTag("filter_2").performClick()
        waitForText(merchant)
        compose.onNodeWithText(merchant).performScrollTo().performClick()
        compose.onNodeWithText("合并到原账单").performScrollTo().performClick()
        waitForText("没有待核对账单")
        compose.onNodeWithTag("filter_0").performClick()
        waitForText(merchant)
        runBlocking {
            val bills = repository.records.first().filter { it.merchant == merchant }
            org.junit.Assert.assertEquals(1, bills.size)
            org.junit.Assert.assertEquals(RecordStatus.CONFIRMED.name, bills.single().status)
        }
    }

    @Test
    fun invalidMoneyAndImpossibleDateCannotCreateBill() {
        val merchant = "虚构校验商户 ${UUID.randomUUID().toString().take(5)}"
        val validTime = java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        compose.onNodeWithTag("add_bill").performClick()
        compose.onNodeWithTag("edit_amount").performTextReplacement("1.999")
        compose.onNodeWithTag("edit_time").performScrollTo().performTextReplacement(validTime)
        compose.onNodeWithTag("edit_merchant").performScrollTo().performTextReplacement(merchant)
        compose.onNodeWithTag("select_channel_WECHAT").performScrollTo().performClick()

        // Every other required field is valid, so only invalid precision can block this save.
        compose.onNodeWithTag("save_bill").performClick()
        compose.onNodeWithText("请输入非负金额，最多两位小数，上限 99,999,999.99 元")
            .performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("edit_amount").assertTextContains("1.999")
        runBlocking { org.junit.Assert.assertTrue("Invalid precision must not create a ledger record", repository.records.first().isEmpty()) }

        // With a valid amount and selected channel, the impossible calendar date must block saving.
        compose.onNodeWithTag("edit_amount").performScrollTo().performTextReplacement("1.99")
        compose.onNodeWithTag("edit_time").performScrollTo().performTextReplacement("2026-02-30 12:00:00")
        compose.onNodeWithTag("save_bill").performClick()
        compose.onNodeWithText("时间格式：yyyy-MM-dd HH:mm:ss；2000 年起，最多允许未来 24 小时")
            .performScrollTo().assertIsDisplayed()
        // assertTextContains uses exact matching unless substring=true; retain the entire entered value.
        compose.onNodeWithTag("edit_time").assertTextContains("2026-02-30 12:00:00")
        runBlocking { org.junit.Assert.assertTrue("Impossible dates must not create a ledger record", repository.records.first().isEmpty()) }

        // Positive control: correcting just the date now saves the same draft successfully.
        compose.onNodeWithTag("edit_time").performScrollTo().performTextReplacement(validTime)
        compose.waitForIdle()
        compose.onNodeWithTag("edit_time").assertTextContains(validTime)
        compose.onNodeWithTag("edit_amount").assertTextContains("1.99")
        compose.onNodeWithTag("select_channel_WECHAT").assertIsSelected()
        // This test verifies validation, not touch targeting during an Android IME animation.
        // The separate CRUD test covers ordinary button taps; invoke the same button action here.
        compose.onNodeWithTag("save_bill").assertIsDisplayed().assertIsEnabled()
            .performSemanticsAction(SemanticsActions.OnClick) { click -> click() }
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithTag("save_bill").fetchSemanticsNodes().isEmpty() }
        waitForText(merchant)
        runBlocking {
            val saved = repository.records.first().single { it.merchant == merchant }
            org.junit.Assert.assertEquals(199L, saved.amountMinor)
            org.junit.Assert.assertEquals(RecordStatus.CONFIRMED.name, saved.status)
        }
    }

    private fun shellCommand(command: String) {
        InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command).use { descriptor ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }
    }

    private fun waitForText(value: String) {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    }
}
