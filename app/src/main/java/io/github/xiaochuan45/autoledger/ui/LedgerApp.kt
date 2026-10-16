package io.github.xiaochuan45.autoledger.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Build

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.xiaochuan45.autoledger.BuildConfig
import io.github.xiaochuan45.autoledger.capture.CapturePreferences
import io.github.xiaochuan45.autoledger.capture.CaptureDiagnostics
import io.github.xiaochuan45.autoledger.capture.PermissionHelper
import io.github.xiaochuan45.autoledger.capture.NotificationRecoveryResult
import io.github.xiaochuan45.autoledger.core.Amounts
import io.github.xiaochuan45.autoledger.core.EntryKind
import io.github.xiaochuan45.autoledger.core.LedgerDraft
import io.github.xiaochuan45.autoledger.core.PaymentChannel
import io.github.xiaochuan45.autoledger.core.RecordStatus
import io.github.xiaochuan45.autoledger.data.LedgerRecord
import io.github.xiaochuan45.autoledger.data.LedgerRepository
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay

private val displayTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
internal fun formatTime(time: Long): String = Instant.ofEpochMilli(time).atZone(ZoneId.systemDefault()).format(displayTime)
internal fun kindLabel(kind: String): String = when (kind) {
    "EXPENSE" -> "支出"
    "REFUND" -> "退款"
    "TRANSFER" -> "转账"
    "TOP_UP" -> "充值"
    "WITHDRAWAL" -> "提现"
    else -> "未知资金类型"
}
private fun timeSourceLabel(source: String): String = when (source) {
    "PAYMENT" -> "实际支付时间"
    "OBSERVED" -> "识别时间，未读取到实际支付时间"
    "USER" -> "用户填写时间"
    else -> "时间来源未知"
}
internal fun channelLabel(channel: String): String = PaymentChannel.entries.firstOrNull { it.name == channel }?.label ?: "未知"
internal fun amountLabel(amountMinor: Long?): String = amountMinor?.let { "${Amounts.formatMinor(it)} 元" } ?: "金额待核对"

@Composable
fun LedgerApp(resumeCount: Int, foregroundRecovery: NotificationRecoveryResult? = null) {
    val context = LocalContext.current
    val repository = remember(context) { LedgerRepository.get(context) }
    val records by repository.records.collectAsState(initial = emptyList())
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var selected by rememberSaveable { mutableIntStateOf(0) }
    var filter by rememberSaveable { mutableIntStateOf(0) }
    var detailId by rememberSaveable { mutableStateOf<String?>(null) }
    var editor by remember { mutableStateOf<EditorRequest?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val detail = records.firstOrNull { it.id == detailId }

    fun save(draft: LedgerDraft) {
        val request = editor ?: return
        busy = true
        scope.launch {
            try {
                when {
                    request.record == null -> repository.addManual(draft)
                    request.confirm -> repository.confirm(request.record.id, draft)
                    else -> repository.update(request.record.id, draft)
                }
                editor = null
                detailId = null
            } catch (failure: IllegalArgumentException) {
                error = failure.message ?: "填写内容不符合要求，请检查金额、时间和文字长度。"
            } catch (_: Exception) {
                error = "保存失败，请重试。现有账单没有被清空。"
            } finally {
                busy = false
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            if (selected == 0) {
                FloatingActionButton(onClick = { editor = EditorRequest(null) }, modifier = Modifier.testTag("add_bill")) {
                    Icon(Icons.Default.Add, contentDescription = "手动补记")
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                listOf("账单", "统计", "设置").forEachIndexed { index, title ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = {
                            Icon(
                                when (index) {
                                    0 -> Icons.AutoMirrored.Filled.List
                                    1 -> Icons.Default.DateRange
                                    else -> Icons.Default.Settings
                                },
                                contentDescription = null,
                            )
                        },
                        label = { Text(title) },
                    )
                }
            }
        },
    ) { padding ->
        when (selected) {
            0 -> BillsPage(records, filter, { filter = it }, padding) { detailId = it.id }
            1 -> StatisticsPage(records, padding)
            else -> SettingsPage(resumeCount, foregroundRecovery, padding)
        }
    }

    if (detail != null && editor == null) {
        RecordDetail(
            detail,
            duplicate = records.firstOrNull { it.id == detail.possibleDuplicateOf },
            onDismiss = { detailId = null },
            onEdit = { editor = EditorRequest(detail) },
            onConfirm = { editor = EditorRequest(detail, confirm = true) },
            onMerge = {
                scope.launch {
                    try {
                        repository.mergeDuplicate(detail.id)
                        detailId = null
                        snackbar.showSnackbar("已合并为同一笔交易")
                    } catch (_: Exception) { error = "合并失败，请检查原账单后重试。" }
                }
            },
            onDelete = {
                scope.launch {
                    try {
                        repository.delete(detail.id)
                        detailId = null
                        if (snackbar.showSnackbar("账单已删除", actionLabel = "撤销", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                            repository.restore(detail.id)
                        }
                    } catch (_: Exception) { error = "操作失败，请检查账单列表后重试。" }
                }
            },
        )
    }
    editor?.let { request ->
        LedgerEditor(request.record, request.confirm, busy, { if (!busy) editor = null }, ::save)
    }
    error?.let {
        AlertDialog(onDismissRequest = { error = null }, title = { Text("操作未完成") }, text = { Text(it) }, confirmButton = { TextButton(onClick = { error = null }) { Text("知道了") } })
    }
}

private data class EditorRequest(val record: LedgerRecord?, val confirm: Boolean = false)

@Composable
private fun PageHeader(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun BillsPage(records: List<LedgerRecord>, filter: Int, onFilter: (Int) -> Unit, padding: PaddingValues, onOpen: (LedgerRecord) -> Unit) {
    val filtered = records.filter {
        when (filter) {
            1 -> it.status == RecordStatus.CONFIRMED.name
            2 -> it.status == RecordStatus.REVIEW.name
            else -> true
        }
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(padding).testTag("bill_list"),
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { PageHeader("我的账单", "把每一笔，记清楚。") }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("全部", "已确认", "待核对").forEachIndexed { index, label ->
                    FilterChip(selected = filter == index, onClick = { onFilter(index) }, label = { Text(label) }, modifier = Modifier.testTag("filter_$index"))
                }
            }
        }
        if (filtered.isEmpty()) {
            item {
                InformationCard(
                    if (filter == 2) "没有待核对账单" else if (filter == 1) "没有已确认账单" else "还没有账单",
                    "可点右下角补记。自动记账需先在设置中开启开关及系统权限；真实支付识别仍待手机验证。",
                )
            }
        }
        items(filtered, key = { it.id }) { record ->
            Card(
                modifier = Modifier.fillMaxWidth().clickable { onOpen(record) }.testTag("bill_${record.id}"),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                        Text(record.merchant?.takeIf { it.isNotBlank() } ?: "商户未知", Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(amountLabel(record.amountMinor), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Text("${kindLabel(record.kind)} · ${if (record.status == RecordStatus.CONFIRMED.name) "已确认" else "待核对"} · ${record.category}", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
                    Text("平台：${record.platform?.takeIf { it.isNotBlank() } ?: "未知"}　支付：${channelLabel(record.channel)}", style = MaterialTheme.typography.bodySmall)
                    Text(formatTime(record.occurredAt), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                    Text(timeSourceLabel(record.timeSource), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                    if (record.status == RecordStatus.REVIEW.name) Text(record.reviewReason ?: "请核对金额及交易状态", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}

@Composable
private fun RecordDetail(record: LedgerRecord, duplicate: LedgerRecord?, onDismiss: () -> Unit, onEdit: () -> Unit, onConfirm: () -> Unit, onMerge: () -> Unit, onDelete: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("账单明细") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(amountLabel(record.amountMinor), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                DetailLine("状态", if (record.status == RecordStatus.CONFIRMED.name) "已确认" else "待核对，不计入支出")
                DetailLine("资金类型", kindLabel(record.kind))
                DetailLine("商户", record.merchant ?: "未知")
                DetailLine("时间", formatTime(record.occurredAt))
                Text(timeSourceLabel(record.timeSource), color = MaterialTheme.colorScheme.secondary)
                DetailLine("消费平台", record.platform ?: "未知")
                DetailLine("支付渠道", channelLabel(record.channel))
                DetailLine("消费分类", record.category)
                DetailLine("备注", record.note.ifBlank { "无" })
                DetailLine("来源", record.sourceTypes.split(',').joinToString("、") { when (it.trim()) { "NOTIFICATION" -> "交易通知"; "ACCESSIBILITY" -> "付款页面"; "WEB_RECEIPT" -> "网页账单（来源待核对）"; "MANUAL" -> "手动补记"; else -> "其他" } })
                record.transactionId?.let { DetailLine("交易标识", it) }
                if (record.status == RecordStatus.REVIEW.name) {
                    HorizontalDivider()
                    Text(record.reviewReason ?: "请核对交易是否成功及实际金额。", color = MaterialTheme.colorScheme.error)
                    if (record.possibleDuplicateOf != null) {
                        Text("可能与已有账单重复。确认为同一笔请选择合并；确认为另一笔真实付款，请保留为独立交易并核对。")
                        duplicate?.let { Text("已有账单：${it.merchant ?: "商户未知"} · ${amountLabel(it.amountMinor)}\n${formatTime(it.occurredAt)}") }
                        OutlinedButton(onClick = onMerge, modifier = Modifier.fillMaxWidth()) { Text("合并到原账单") }
                    }
                    Button(onClick = onConfirm, modifier = Modifier.fillMaxWidth().testTag("review_record")) { Text(if (record.possibleDuplicateOf != null) "保留为独立交易并核对" else "核对并确认") }
                    Text("请先确认付款已成功。退款、转账、充值、提现在本阶段保持待核对，不计入消费；只有明确实际用途时再改为支出。", style = MaterialTheme.typography.bodySmall)
                }
                OutlinedButton(onClick = onDelete, modifier = Modifier.fillMaxWidth().testTag("delete_bill")) { Text("删除账单", color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = onEdit, modifier = Modifier.testTag("edit_bill")) { Text("编辑") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun DetailLine(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.secondary)
        Text(value, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StatisticsPage(records: List<LedgerRecord>, padding: PaddingValues) {
    val confirmedExpenses = records.filter { it.status == RecordStatus.CONFIRMED.name && it.kind == EntryKind.EXPENSE.name }
    val total = confirmedExpenses.fold(java.math.BigInteger.ZERO) { sum, record -> sum + java.math.BigInteger.valueOf(record.amountMinor ?: 0L) }
    val totalText = java.math.BigDecimal(total, 2).toPlainString()
    Page("开销统计", "先把账记准，再看完整开销。", padding) {
        InformationCard("已确认支出总额", "$totalText 元 · ${confirmedExpenses.size} 笔\n全部本地记录，不限日期。")
        InformationCard("待核对 ${records.count { it.status == RecordStatus.REVIEW.name }} 笔", "待核对、退款、转账、充值和提现均不计入上方支出。")
        Text("本阶段仅提供支出总览。每日、每周、每月、每年及分类统计、退款关联和净支出将在后续开放。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
    }
}

@Composable
private fun SettingsPage(resumeCount: Int, foregroundRecovery: NotificationRecoveryResult?, padding: PaddingValues) {
    val context = LocalContext.current
    val preferences = remember(context) { CapturePreferences(context) }
    var settings by remember(resumeCount) { mutableStateOf(preferences.read()) }
    var refreshCount by remember { mutableIntStateOf(0) }
    val connections by PermissionHelper.connectionState.collectAsState()
    val permissions = remember(resumeCount, settings, refreshCount, connections) { PermissionHelper.snapshot(context) }
    val diagnostics = remember(resumeCount, settings, refreshCount, connections) { CaptureDiagnostics.snapshot() }
    val compatibility = remember(resumeCount, refreshCount) { CaptureDiagnostics.compatibilitySnapshot() }
    LaunchedEffect(compatibility.active) {
        if (compatibility.active) { delay(compatibility.remainingMillis + 50L); refreshCount++ }
    }
    var reportCopied by remember { mutableStateOf(false) }
    var copyError by remember { mutableStateOf(false) }
    var recoveryResult by remember(resumeCount, foregroundRecovery) {
        mutableStateOf(foregroundRecovery?.takeUnless {
            it == NotificationRecoveryResult.ACCESS_NOT_GRANTED || it == NotificationRecoveryResult.CAPTURE_DISABLED
        })
    }
    var openError by remember { mutableStateOf(false) }
    Page("设置与状态", "数据留在手机，权限由你掌握。", padding) {
        SettingsCard("自动记账") {
            SettingSwitch("自动记账总开关", "默认关闭，开启后仅处理所选渠道的必要交易信息。", settings.enabled, "capture_enabled") {
                preferences.setEnabled(it)
                settings = preferences.read()
                if (it) recoveryResult = PermissionHelper.maybeRecoverNotificationsOnForeground(context)
            }
            HorizontalDivider()
            PaymentChannel.entries.forEach { channel ->
                SettingSwitch(channel.label, "页面及交易通知", if (channel == PaymentChannel.WECHAT) settings.wechatEnabled else settings.alipayEnabled, "channel_${channel.name}") {
                    val allChannelsWereOff = !settings.wechatEnabled && !settings.alipayEnabled
                    preferences.setChannelEnabled(channel, it)
                    settings = preferences.read()
                    if (it && allChannelsWereOff) recoveryResult = PermissionHelper.maybeRecoverNotificationsOnForeground(context)
                }
            }
            Text(if (compatibility.active) "只读兼容性检查中，自动记录暂停；开关保持原设置。" else if (settings.enabled) "已开启识别。下方系统权限与服务状态仍需检查。" else "当前已暂停自动识别，手动补记仍可使用。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
        SettingsCard("系统权限与服务") {
            PermissionRow("通知使用权", permissions.notificationAccess, permissions.notificationConnected, openLabel = "打开通知权限设置", statusTag = "notification_connection_status") {
                if (!PermissionHelper.openNotifications(context)) openError = true
            }
            OutlinedButton(
                onClick = {
                    recoveryResult = PermissionHelper.recoverNotifications(context)
                    refreshCount++
                },
                modifier = Modifier.fillMaxWidth().testTag("recover_notification_listener"),
            ) { Text("尝试恢复通知监听") }
            recoveryResult?.let { result ->
                Text(notificationRecoveryMessage(result, permissions.notificationConnected), modifier = Modifier.testTag("notification_recovery_result"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            }
            Text("恢复请求已提交不等于服务已连接，连接状态以系统回调为准。恢复不会补抓过去漏记的账单，请核对并手动补记。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            HorizontalDivider()
            PermissionRow("无障碍服务", permissions.accessibilityAccess, permissions.accessibilityConnected, statusTag = "accessibility_connection_status") {
                if (!PermissionHelper.openAccessibility(context)) openError = true
            }
            TextButton(onClick = { refreshCount++ }) { Text("刷新权限状态") }
            Text("已授权与服务已连接是不同状态。系统可能限制后台运行，状态不能保证永不漏记。服务连接变化会自动更新，返回此页也会刷新系统权限。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            OutlinedButton(onClick = { if (!PermissionHelper.openBatterySettings(context)) openError = true }, modifier = Modifier.fillMaxWidth()) { Text("检查电池与后台限制") }
            OutlinedButton(onClick = { if (!PermissionHelper.openAppSettings(context)) openError = true }, modifier = Modifier.fillMaxWidth()) { Text("打开应用系统设置") }
            Text("不同品牌手机可在系统应用管理中检查电池使用、允许后台活动等选项，名称随品牌和系统版本变化。若服务断开，检查开关或在系统设置中重新启用。", style = MaterialTheme.typography.bodySmall)
        }
        SettingsCard("本次运行诊断") {
            Text("微信网页账单结构命中 ${diagnostics.webReceiptMatches} 次（结果仍需核对来源）", style = MaterialTheme.typography.bodyMedium)
            Text("已选渠道窗口事件 ${diagnostics.windowEvents} 次（支持类型 ${diagnostics.supportedWindowEvents} 次）\n已选渠道通知事件 ${diagnostics.notificationEvents} 次\n进入解析 ${diagnostics.observedCount} 次\n处理完成 ${diagnostics.parsedCount} 次（含重复检查）\n筛选跳过 ${diagnostics.skippedCount} 次\n异常 ${diagnostics.failureCount} 次", style = MaterialTheme.typography.bodyMedium)
            Text("事件不是付款笔数；进入解析才表示已通过前面的筛选。普通页面、会话通知等可能被跳过。零事件也可能来自进程重启或系统没有提供信息。", style = MaterialTheme.typography.bodySmall)
            diagnostics.lastSkip?.let { Text("最近跳过：${it.label}", style = MaterialTheme.typography.bodySmall) }
            Text("这些是处理事件次数，不是新增账单笔数。计数仅保留在内存中，应用进程重启后归零；服务已连接仍不能保证不漏记。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
            Text("通知监听恢复请求 ${diagnostics.notificationReconnectRequests} 次", style = MaterialTheme.typography.bodySmall)
            diagnostics.lastNotificationReconnectAt?.let { Text("最近请求时间：${formatTime(it)}", style = MaterialTheme.typography.bodySmall) }
            diagnostics.lastSource?.let { source ->
                Text("最近监听：${diagnostics.lastChannel?.label ?: "渠道未知"} · ${when (source.name) { "NOTIFICATION" -> "交易通知"; "ACCESSIBILITY" -> "付款页面"; else -> "其他来源" }}", style = MaterialTheme.typography.bodySmall)
            }
            diagnostics.lastObservedAt?.let { Text("监听时间：${formatTime(it)}", style = MaterialTheme.typography.bodySmall) }
            diagnostics.lastFailure?.let { failure ->
                val description = when (failure.name) {
                    "NOTIFICATION_REBIND" -> "通知监听恢复请求未能发起。请检查通知使用权和后台限制；缺失账单请手动补记。"
                    "NOTIFICATION_READ" -> "通知读取未完成。请检查上方通知使用权及服务状态，核对账单，缺失时手动补记。"
                    "ACCESSIBILITY_READ" -> "付款页面读取未完成。请检查上方无障碍权限及服务状态，核对账单，缺失时手动补记。"
                    "PARSE_OR_STORAGE" -> "识别或本地保存未完成。请返回账单核对，缺失时手动补记；若反复发生，可暂停自动记账后重新开启。"
                    "SYSTEM_SETTINGS" -> "未能打开系统设置。请从手机系统应用管理检查权限、服务和电池限制，缺失账单可手动补记。"
                    "QUEUE_FULL" -> "短时间内事件过多，部分信息未处理。请核对账单，缺失时手动补记。"
                    "SKIPPED_INCOMPLETE" -> "付款信息不完整，无法可靠识别。请核对账单，缺失时手动补记。"
                    else -> "部分交易信息未处理。请检查上方权限及服务状态，核对账单，缺失时手动补记。"
                }
                Text("最近一次异常：$description", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            TextButton(onClick = { refreshCount++ }) { Text("刷新诊断状态") }
        }
        SettingsCard("只读兼容性检查") {
            Text("本次进程内检查期间暂停自动记录，最多 5 分钟。只在内存保留最近 6 个支付应用的程序窗口类型；不读取付款文字、聊天、截图，检查本身不新增账单。结束或进程重启后按原有开关采集，手动补记仍可用。", style = MaterialTheme.typography.bodySmall)
            Text(if (compatibility.active) "检查中 · 暂停自动记录" else "检查未运行", modifier = Modifier.testTag("compatibility_status"))
            if (compatibility.active) {
                OutlinedButton(onClick = { CaptureDiagnostics.stopCompatibilityCheck(); refreshCount++ }, modifier = Modifier.fillMaxWidth().testTag("stop_compatibility")) { Text("结束兼容性检查") }
            } else {
                OutlinedButton(onClick = { CaptureDiagnostics.startCompatibilityCheck(); reportCopied = false; refreshCount++ }, modifier = Modifier.fillMaxWidth().testTag("start_compatibility")) { Text("开始只读检查（5 分钟）") }
            }
            Text("开始后直接切到微信账单，打开这次已有付款的详情，再回这里刷新；期间请勿清后台。无需重新付款或分享付款页面。若采集开关或渠道关闭，该渠道不会收集诊断。", style = MaterialTheme.typography.bodySmall)
            compatibility.windows.forEach { Text("${it.channel.label} · ${it.className}", style = MaterialTheme.typography.bodySmall) }
            TextButton(onClick = { refreshCount++; reportCopied = false }) { Text("刷新检查结果") }
            OutlinedButton(onClick = {
                try {
                    context.getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText("兼容性诊断", CaptureDiagnostics.compatibilityReport(BuildConfig.VERSION_NAME, Build.VERSION.SDK_INT)))
                    reportCopied = true
                    copyError = false
                } catch (_: RuntimeException) { reportCopied = false; copyError = true }
            }, modifier = Modifier.fillMaxWidth().testTag("copy_compatibility")) { Text(if (reportCopied) "已复制诊断摘要" else "复制无账单原文的诊断摘要") }
            if (copyError) Text("复制未完成，可手动记录上方的事件数字和窗口类型。", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { CaptureDiagnostics.clearCompatibilityCheck(); reportCopied = false; refreshCount++ }) { Text("清除窗口类型结果") }
        }
        InformationCard("识别小样 · 真机待验证", "微信网页账单仅在完整栏目结构命中后尝试识别，结果进入待核对，不自动计入支出；来源与字段需你核对。网页筛选只比较有界短栏目，未匹配内容不解析、不保存。微信、支付宝的其他支付、免密及后台扣费仍需逐项实测；没有可读信息时请手动补记。")
        SettingsCard("隐私与数据") {
            Text("仅处理微信、支付宝必要交易页面和通知，开关关闭后停止处理。识别、分类和保存均在本地，不申请联网权限。")
            Text("不保存整屏原文、截图、聊天、密码或验证码。系统账目自动备份已禁用。")
            Text("目前只支持本地账单。账单导入导出、加密备份恢复与商户分类规则将在后续开放。卸载或清除应用数据会丢失账单，请保留应用。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        Text("本地账本 · ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
    }
    if (openError) AlertDialog(onDismissRequest = { openError = false }, title = { Text("未能打开系统页面") }, text = { Text("请从手机系统设置 → 应用管理找到本地账本，检查权限、通知使用权、无障碍及电池设置。") }, confirmButton = { TextButton(onClick = { openError = false }) { Text("知道了") } })
}

private fun notificationRecoveryMessage(result: NotificationRecoveryResult, connected: Boolean): String = when (result) {
    NotificationRecoveryResult.REQUESTED -> if (connected) "恢复请求已提交，现已收到系统连接回调。" else "已提交恢复请求，正在等待系统连接；请观察上方连接状态。"
    NotificationRecoveryResult.ALREADY_CONNECTED -> if (connected) "当前已收到系统连接回调，无需重复恢复。" else "服务在检查后又断开，请重新检查权限或尝试恢复。"
    NotificationRecoveryResult.ACCESS_NOT_GRANTED -> "尚未授予通知使用权，未发起恢复。请先打开通知权限设置。"
    NotificationRecoveryResult.CAPTURE_DISABLED -> "自动记账或全部支付渠道已关闭，未发起恢复。请先开启总开关和至少一个渠道。"
    NotificationRecoveryResult.THROTTLED -> "近期已尝试恢复，本次未重复请求。请稍后刷新状态；持续未连接时检查系统权限或后台限制。"
    NotificationRecoveryResult.ALREADY_REQUESTED -> "本次断开后已尝试过自动恢复，本次未重复请求。仍未连接时可检查系统权限，或稍后手动尝试恢复。"
    NotificationRecoveryResult.FAILED -> "恢复请求未能发起。请打开通知权限设置检查；仍未连接时可在系统中关闭再开启通知使用权。"
}

@Composable
private fun PermissionRow(label: String, authorized: Boolean, connected: Boolean, openLabel: String = "检查 / 恢复$label", statusTag: String = "permission_status", onOpen: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.titleSmall)
        Text("${if (authorized) "已授权" else "未授权"} · ${if (connected) "服务已连接" else "服务未连接"}", modifier = Modifier.testTag(statusTag), style = MaterialTheme.typography.bodySmall, color = if (authorized && connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        TextButton(onClick = onOpen) { Text(openLabel) }
    }
}

@Composable
private fun SettingSwitch(title: String, description: String, checked: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        }
        Switch(checked = checked, onCheckedChange = onChange, modifier = Modifier.testTag(tag))
    }
}

@Composable
private fun SettingsCard(title: String, content: @Composable () -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun Page(title: String, subtitle: String, padding: PaddingValues, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        PageHeader(title, subtitle)
        content()
        Spacer(Modifier.height(12.dp))
    }
}

@Composable
private fun InformationCard(title: String, description: String) {
    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
