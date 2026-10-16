package io.github.xiaochuan45.autoledger.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import io.github.xiaochuan45.autoledger.core.Amounts
import io.github.xiaochuan45.autoledger.core.EntryKind
import io.github.xiaochuan45.autoledger.core.LedgerCategories
import io.github.xiaochuan45.autoledger.core.LedgerDraft
import io.github.xiaochuan45.autoledger.core.PaymentChannel
import io.github.xiaochuan45.autoledger.data.LedgerRecord
import io.github.xiaochuan45.autoledger.data.LedgerRepository
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

private val inputTimeFormat = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT)

/** Reject impossible calendar dates and local times inside a daylight-saving gap. */
private fun parseTime(value: String): Long? = try {
    val local = LocalDateTime.parse(value.trim(), inputTimeFormat)
    val zone = ZoneId.systemDefault()
    if (local.year !in 2000..2100 || zone.rules.getValidOffsets(local).size != 1) null
    else local.atZone(zone).toInstant().toEpochMilli().takeIf { it in 946684800000L..(System.currentTimeMillis() + 86_400_000L) }
} catch (_: DateTimeParseException) { null }

@Composable
internal fun LedgerEditor(record: LedgerRecord?, confirming: Boolean, busy: Boolean, onDismiss: () -> Unit, onSave: (LedgerDraft) -> Unit) {
    var amount by rememberSaveable(record?.id) { mutableStateOf(record?.amountMinor?.let(Amounts::formatMinor) ?: "") }
    var time by rememberSaveable(record?.id) { mutableStateOf(formatTime(record?.occurredAt ?: System.currentTimeMillis())) }
    var merchant by rememberSaveable(record?.id) { mutableStateOf(record?.merchant.orEmpty()) }
    var platform by rememberSaveable(record?.id) { mutableStateOf(record?.platform.orEmpty()) }
    var channelName by rememberSaveable(record?.id) { mutableStateOf(record?.channel) }
    var kindName by rememberSaveable(record?.id) { mutableStateOf(record?.kind ?: EntryKind.EXPENSE.name) }
    var category by rememberSaveable(record?.id) { mutableStateOf(record?.category ?: "未分类") }
    var note by rememberSaveable(record?.id) { mutableStateOf(record?.note.orEmpty()) }
    var submitted by rememberSaveable(record?.id) { mutableStateOf(false) }
    val amountMinor = Amounts.parseMinor(amount)
    val amountValid = amountMinor != null && amountMinor >= 0L && amountMinor <= LedgerRepository.MAX_AMOUNT_MINOR
    // Preserve subsecond precision and the original time source when only other fields change.
    val parsedTime = if (record != null && time.trim() == formatTime(record.occurredAt)) record.occurredAt else parseTime(time)
    val channel = PaymentChannel.entries.firstOrNull { it.name == channelName }
    val kind = EntryKind.entries.firstOrNull { it.name == kindName }
    val willConfirmExpense = kind == EntryKind.EXPENSE && (confirming || record == null)

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnBackPress = !busy, dismissOnClickOutside = !busy)) {
        Surface(Modifier.fillMaxSize().widthIn(max = 600.dp), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().imePadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    TextButton(onClick = onDismiss, enabled = !busy) { Text("取消") }
                    Text(if (record == null) "手动补记" else if (confirming) "核对账单" else "编辑账单", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Button(
                        enabled = !busy,
                        modifier = Modifier.testTag("save_bill"),
                        onClick = {
                            submitted = true
                            if (amountValid && parsedTime != null && channel != null && kind != null) {
                                onSave(LedgerDraft(amountMinor!!, parsedTime, merchant.trim().ifBlank { null }, platform.trim().ifBlank { null }, channel, kind, category.trim().ifBlank { "未分类" }, note.trim()))
                            }
                        },
                    ) { Text(if (busy) "保存中" else if (confirming && kind == EntryKind.EXPENSE) "确认入账" else "保存") }
                }
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(if (willConfirmExpense) "请核实付款已成功及实际金额，再${if (confirming) "确认入账" else "保存"}。" else if (kind == EntryKind.EXPENSE) "仅按已成功付款的实际金额记账。" else "本阶段退款、转账、充值、提现保存为待核对，不计入支出。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                    OutlinedTextField(
                        value = amount, onValueChange = { amount = it }, label = { Text("实付金额（元）") },
                        singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        isError = submitted && !amountValid,
                        supportingText = { Text(if (submitted && !amountValid) "请输入非负金额，最多两位小数，上限 99,999,999.99 元" else "不包含原价、优惠金额；退款填写实际到账金额") },
                        modifier = Modifier.fillMaxWidth().testTag("edit_amount"),
                    )
                    OutlinedTextField(
                        value = time, onValueChange = { time = it }, label = { Text("日期与时间") },
                        singleLine = true, isError = submitted && parsedTime == null,
                        supportingText = { Text(if (submitted && parsedTime == null) "时间格式：yyyy-MM-dd HH:mm:ss；2000 年起，最多允许未来 24 小时" else "手机本地时区；修改后标为用户填写时间") },
                        modifier = Modifier.fillMaxWidth().testTag("edit_time"),
                    )
                    if (record != null) Text("原记录：${formatTime(record.occurredAt)}\n时间来源：${when (record.timeSource) { "PAYMENT" -> "实际支付时间"; "OBSERVED" -> "识别时间"; "USER" -> "用户填写时间"; else -> "未知" }}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    OutlinedTextField(value = merchant, onValueChange = { if (it.length <= 200) merchant = it }, label = { Text("商户 / 交易对象（可留空）") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("edit_merchant"))
                    OutlinedTextField(value = platform, onValueChange = { if (it.length <= 200) platform = it }, label = { Text("消费平台（可留空）") }, supportingText = { Text("例如美团、淘宝，与支付渠道和消费分类分别记录") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("edit_platform"))
                    Text("支付渠道", style = MaterialTheme.typography.titleSmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PaymentChannel.entries.forEach { option ->
                            FilterChip(selected = option.name == channelName, onClick = { channelName = option.name }, label = { Text(option.label) }, modifier = Modifier.testTag("select_channel_${option.name}"))
                        }
                    }
                    if (submitted && channel == null) Text("请选择实际支付渠道", color = MaterialTheme.colorScheme.error)
                    Text("资金类型", style = MaterialTheme.typography.titleSmall)
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        EntryKind.entries.forEach { option ->
                            FilterChip(selected = option.name == kindName, onClick = { kindName = option.name }, label = { Text(kindLabel(option.name)) }, modifier = Modifier.testTag("select_kind_${option.name}"))
                        }
                    }
                    OutlinedTextField(value = category, onValueChange = { if (it.length <= 40) category = it }, label = { Text("消费分类") }, supportingText = { Text("未知时保留未分类，不按平台名称猜测用途") }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("edit_category"))
                    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LedgerCategories.defaults.forEach { option -> FilterChip(selected = category == option, onClick = { category = option }, label = { Text(option) }) }
                    }
                    OutlinedTextField(value = note, onValueChange = { if (it.length <= 1000) note = it }, label = { Text("备注（可留空）") }, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth().testTag("edit_note"))
                    Text(if (record == null) "保存表示已核实此笔交易及资金类型，账单仅存于本机。退款关联、导入导出及加密备份将在后续开放。" else if (confirming && kind == EntryKind.EXPENSE) "点击“确认入账”表示已核实付款成功、实际金额和用途。确认后计入支出；另一笔真实付款会作为独立交易保留。" else "修改待核对账单不会自动确认。请从明细进入“核对并确认”。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
}

