package io.github.xiaochuan45.autoledger

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LaunchSmokeTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun launcherShowsAnExplicitEmptyPreview() {
        compose.onNodeWithText("我的账单").assertIsDisplayed()
        compose.onNodeWithText("安装验证版").assertIsDisplayed()
        compose.onNodeWithText("还没有账单").assertIsDisplayed()
    }

    @Test
    fun dailyDestinationsAndServiceStatusAreReachable() {
        compose.onNodeWithText("统计").performClick()
        compose.onNodeWithText("开销统计").assertIsDisplayed()
        compose.onNodeWithText("暂无统计数据").assertIsDisplayed()
        compose.onNodeWithText("设置").performClick()
        compose.onNodeWithText("设置与状态").assertIsDisplayed()
        compose.onNodeWithText("自动记账尚未启用").assertIsDisplayed()
        compose.onNodeWithText("账单").performClick()
        compose.onNodeWithText("我的账单").assertIsDisplayed()
    }
}
