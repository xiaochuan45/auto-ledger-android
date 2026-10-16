package io.github.xiaochuan45.autoledger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = lightColorScheme(
                    primary = Color(0xFF176D55),
                    secondary = Color(0xFF52645B),
                    background = Color(0xFFF7F9F6),
                    surface = Color(0xFFFFFFFF),
                    primaryContainer = Color(0xFFE2F1E9),
                ),
            ) {
                LedgerPreview()
            }
        }
    }
}

private data class Destination(val title: String, val icon: ImageVector)

@Composable
private fun LedgerPreview() {
    val destinations = listOf(
        Destination("账单", Icons.AutoMirrored.Filled.List),
        Destination("统计", Icons.Default.DateRange),
        Destination("设置", Icons.Default.Settings),
    )
    var selected by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                destinations.forEachIndexed { index, destination ->
                    NavigationBarItem(
                        selected = selected == index,
                        onClick = { selected = index },
                        icon = { Icon(destination.icon, contentDescription = null) },
                        label = { Text(destination.title) },
                    )
                }
            }
        },
    ) { padding ->
        when (selected) {
            0 -> BillsPage(padding)
            1 -> StatisticsPage(padding)
            else -> SettingsPage(padding)
        }
    }
}

@Composable
private fun Page(title: String, subtitle: String, padding: PaddingValues, content: @Composable () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(padding)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
        }
        content()
    }
}

@Composable
private fun BillsPage(padding: PaddingValues) {
    Page("我的账单", "把每一笔，记清楚。", padding) {
        InformationCard("安装验证版", "先确认手机能够正常安装和打开。此版本尚未记录账单，自动记账将在后续版本开放。")
        Box(
            modifier = Modifier.fillMaxWidth().padding(vertical = 42.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(
                    Modifier.size(72.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(24.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(34.dp))
                }
                Text("还没有账单", style = MaterialTheme.typography.titleMedium)
                Text("后续可自动记录，也可手动补记", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.secondary)
            }
        }
    }
}

@Composable
private fun StatisticsPage(padding: PaddingValues) {
    Page("开销统计", "了解支出，也留意退款。", padding) {
        InformationCard("暂无统计数据", "有账单后，这里会展示每日、每周、每月和每年的支出、退款及净支出。")
        InformationCard("分类统计将在后续开放", "消费平台、支付渠道和消费分类分别记录；分类未知时保留为未分类。")
    }
}

@Composable
private fun SettingsPage(padding: PaddingValues) {
    Page("设置与状态", "数据留在手机，使用保持简单。", padding) {
        InformationCard("自动记账尚未启用", "本版本不读取页面或通知，也不需要开启无障碍或通知使用权。识别小样就绪后，再引导设置相关权限。")
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("隐私与数据", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text("不申请联网权限，不接入广告或统计服务。", style = MaterialTheme.typography.bodyMedium)
                HorizontalDivider()
                Text("系统自动备份已禁用。加密备份、导入和导出将在后续开放。", style = MaterialTheme.typography.bodyMedium)
            }
        }
        Text("本地账本 · ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        Text("此预览版仅验证安装与启动。请暂勿用于保存真实账单。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun InformationCard(title: String, description: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(description, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
