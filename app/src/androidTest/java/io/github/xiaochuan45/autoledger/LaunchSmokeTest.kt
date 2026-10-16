package io.github.xiaochuan45.autoledger

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.xiaochuan45.autoledger.capture.CapturePreferences
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
        CapturePreferences(compose.activity).setEnabled(false)
        runBlocking { repository.records.first().forEach { repository.delete(it.id) } }
    }

    @After
    fun removeFictionalRecords() {
        runBlocking { repository.records.first().forEach { repository.delete(it.id) } }
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
        compose.onNodeWithText("23.50 元").assertIsDisplayed()
        compose.onNodeWithText(merchant).performClick()
        compose.onNodeWithTag("edit_bill").performClick()
        compose.onNodeWithTag("edit_amount").performTextReplacement("19.80")
        compose.onNodeWithTag("save_bill").performClick()
        waitForText("19.80 元")
        compose.onNodeWithText(merchant).performClick()
        compose.onNodeWithTag("delete_bill").performScrollTo().performClick()
        waitForText("撤销")
        compose.onNodeWithText("撤销").performClick()
        waitForText(merchant)
        compose.onNodeWithText("19.80 元").assertIsDisplayed()
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
        compose.onNodeWithText(merchant).performClick()
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
        compose.onNodeWithText(merchant).performClick()
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
        compose.onNodeWithTag("add_bill").performClick()
        compose.onNodeWithTag("edit_amount").performTextReplacement("1.999")
        compose.onNodeWithTag("edit_time").performScrollTo().performTextReplacement("2026-02-30 12:00:00")
        compose.onNodeWithTag("save_bill").performClick()
        compose.onNodeWithTag("edit_time").performScrollTo().assertTextContains("2026-02-30")
        runBlocking { org.junit.Assert.assertTrue(repository.records.first().isEmpty()) }
    }

    private fun waitForText(value: String) {
        compose.waitUntil(timeoutMillis = 10_000) { compose.onAllNodesWithText(value).fetchSemanticsNodes().isNotEmpty() }
    }
}
