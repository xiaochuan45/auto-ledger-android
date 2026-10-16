# 本地账本 · 微信网页读取修正 0.2.4-prototype

用户在微信 8.0.78 / Android API 36 的同一笔旧详情仍未识别；0.2.3 日志明确有 14 次网页结构拒绝，未进入解析。本版根据 GitHub 公开实现和相近版本问题，修复合并行、非必要栏目、重复父子节点和读取顺序等可复现差异，并新增分层可读性报告和主动一次读取。**仍未证明这台手机已能读到账单，不承诺新的付款或后台扣费已修复。**

版本 `0.2.4-prototype` / code `6`，同包名 `io.github.xiaochuan45.autoledger`，沿用固定签名覆盖 `0.2.3` / code `5`，数据库格式未变。不要卸载或清除数据。

## 本轮变化

- 自动网页检查仍仅限微信 `MMWebViewUI`，必要栏目改为当前状态、支付时间、支付方式、交易单号。商户单号、收单机构及导航文字不再作为必要条件。
- 支持标签和值合并、父子重复；在根包名和窗口已验证后允许虚拟子节点缺包名，明确其他应用仍拒绝。按文档顺序读取叶子和明确栏目行，避免浅层状态表排在深层金额标题前。
- 仅新增 `flagIncludeNotImportantViews` 以接收未标记为重要的节点；仍限定两个支付应用、必要事件和页面，不请求全部窗口、截图、手势、联网或其他新增权限。它不是可读性保证，也没有把记账应用声明成读屏辅助工具。
- 自动检查最多 256 个原生结构节点、512 个网页节点；网页正文至多 4000 字符、48 片段，异常、超限、缺节点和敏感保护均丢弃整次样本。原生付款/通知既有文字上限不变。
- 分别报告无网页、多个网页、无文字、节点被可见性/窗口/安全规则过滤、缺必要栏目、缺节点或结构超限；仅保存固定原因、计数及四栏目存在标记，不保存字段值、完整树或原文。
- 新增「读取一次已有微信账单」：用户主动点击后 30 秒内，只对一个已验证微信网页窗口尝试一次，可跳过自动结构预筛选。仍需最小交易字段才生成待核对；无法提供文字时不能识别。请求到期、已尝试、服务断开、关闭开关或启动只读检查即结束，不循环读取。
- 网页新记录依然一律待核对，不自动计支出；同交易号复用原记录，不修改旧账单。未知字段保留未知，不按平台猜类别。没有新增 OCR、截图、Root、Shizuku、云端识别或原文日志。

公开依据、许可证边界和适用限制见 [GitHub 调研记录](https://github.com/xiaochuan45/auto-ledger-android/blob/main/docs/WECHAT_ACCESSIBILITY_RESEARCH.md)。参考了机制和思路，没有复制 GPL 项目代码。

## 这次手机只做一次，不消费

1. 直接覆盖安装本版，确认总开关、微信渠道及两项服务；结束「只读兼容性检查」。
2. 设置中点「读取一次已有微信账单」。30 秒内切到微信，先退出详情，再重新打开同一笔已有付款，停留几秒；不要清后台。
3. 回账本查看待核对；核对字段是否正确，尚未核对前无需确认。已有共同交易号时应复用原记录；已手动补记且无共同号时不能保证自动关联，避免确认重复项。
4. 回设置刷新诊断并复制无账单原文的摘要。新增「最近网页检查」、节点计数和四栏目标记能区分是否提供文字、规则缺项或超限；不用发送真实金额、时间、订单号或截图。

若完整、未过滤的检查显示无文字，一次读取也没有字段，就不能靠继续堆栏目规则解决。根据实际摘要选择本机临时 OCR 或账单文件导入作为下一独立路线；它们尚未实现，不算本轮交付。

## 验证与交付

| 项目 | 证据 |
|---|---|
| 下载 | [APK](https://github.com/xiaochuan45/auto-ledger-android/releases/download/prototype-v0.2.4/auto-ledger-prototype.apk) / [Release](https://github.com/xiaochuan45/auto-ledger-android/releases/tag/prototype-v0.2.4) |
| 生产源码 | [`f082dbf65a48abab22074df757aa71431e89224f`](https://github.com/xiaochuan45/auto-ledger-android/commit/f082dbf65a48abab22074df757aa71431e89224f) |
| APK 大小 / SHA-256 | 7,155,098 bytes / `c72b06923d5caaca9240a5d2ee8c5ffe99bf44640093eb3013a7232859d32532` |
| 签名证书 SHA-256 | `0e2a128391be8ae3d39140ed5d2cd7de8256d62b19233b3593d9ef97180b0073`，沿用原固定签名 |
| 本地构建、规则、审计 | JVM 73 项、Python 10 项、构建、Lint、准确签名包权限审计通过 |
| 验证源码 | [`a721b7a97becd28f05caaad99585d3fc455485c7`](https://github.com/xiaochuan45/auto-ledger-android/commit/a721b7a97becd28f05caaad99585d3fc455485c7)，仅修正测试环境：启用真实非工具服务、WebView 销毁与缓存，不改生产代码或 APK |
| 源码矩阵 | [run 38864436447](https://github.com/xiaochuan45/auto-ledger-android/actions/runs/38864436447) 成功：API 26 实际执行 41 项，API 35 / 36 各 42 项，全部通过；API 29+ 通知 shell 用例在 API 26 按 SDK 条件不执行 |
| 最终准确签名 APK | [run 38861719901](https://github.com/xiaochuan45/auto-ledger-android/actions/runs/38861719901) 成功：API 35 普通 42 项；公开旧 code5 写入虚构数据 1 项、真实覆盖准确 code6 后账单、去重及采集开关保留 1 项均通过 |
| 权限与证据 | [审计报告](evidence/0.2.4-prototype/permissions-audit.md) / [artifact 12029407380](https://github.com/xiaochuan45/auto-ledger-android/actions/runs/38861719901/artifacts/12029407380)，无联网、截图或全部窗口权限 |
| 公开下载 | APK 与校验文件均 HTTP 200；公开下载 APK 与被测签名文件逐字节相同 |
| 用户手机 0.2.4 | 安装启动、微信文字可读、字段正确、去重、新付款及后台运行均待真机验证；0.2.3 已有详情失败事实保留 |

代码已实现；上述模拟测试只打开本应用自己的虚构 HTML，没有打开真实微信或拼多多。新增合并字段、缺少可选栏、父子重复、嵌套金额的结构检查→文档顺序读取→解析连通测试；与生产服务使用相同的未标记重要节点 flag。一次读取还覆盖限定渠道/类型、仅一次、超时、取消、进程更换和只读保护。未来 schema 迁移、真实支付通知格式或所有系统后台常驻不在这些通过声明内。

当前仍缺少完整分类规则、周期统计、退款关联、账单导入导出和加密备份，不把小样作为唯一账本。私有签名保管边界见 [项目状态](https://github.com/xiaochuan45/auto-ledger-android/blob/main/docs/PHASE2_STATUS.md)。

初次 [run 38861693271](https://github.com/xiaochuan45/auto-ledger-android/actions/runs/38861693271) 的 API 26 / 35 通过，API 36 两项网页测试在节点就绪检查超时；缓存与销毁修正后的 [run 38862653174](https://github.com/xiaochuan45/auto-ledger-android/actions/runs/38862653174) 中 API 35 仍有一次就绪超时，API 36 的金额断言缺值，均记为失败。日志中 WebView 报告没有真实无障碍服务启用，测试只依靠 UiAutomation 与用户已启用服务的环境不同。[run 38863575984](https://github.com/xiaochuan45/auto-ledger-android/actions/runs/38863575984) 又发现测试设置命令把引号作为服务名一部分，导致服务未启用，已修正为无空白的字面参数。最终测试在模拟器临时启用本应用真实、非 accessibilityTool 的服务，并恢复原设置，UiAutomation 使用不压制实际服务的连接；支付包名过滤和生产节点 flag 不变。另明确销毁 WebView、刷新测试缓存并输出仅虚构样本的失败片段。没有放宽金额断言、跳过测试或改变生产读取器；最终矩阵结果见上表，APK 与准确签名 API 35 的结果保持不变。
