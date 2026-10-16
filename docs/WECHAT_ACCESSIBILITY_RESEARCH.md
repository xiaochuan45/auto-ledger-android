# 微信网页账单：GitHub 对照与适配依据

日期：2026-10-16。首次设备一加 Ace 5，Android API 36，微信 8.0.78。这里只记录固定结构、公开实现依据和脱敏结果；未上传用户截图、实际金额、时间、商户或订单号。

## 目前真正知道什么

0.2.3 的手机反馈：已选渠道窗口事件 146，网页栏目命中 0、进入解析 0、完成 0；14 次被栏目结构拒绝，异常 0。另有未验证窗口 21、不支持页面 7、窗口变化 1、只读保护 45。计数为本进程累计，不是交易笔数；最近一次跳过为只读保护不表示复制时仍开启，摘要已显示检查未运行。

这定位到「网页结构检查」这一层，尚不能确定缺哪个栏目、节点是否合并或有没有可读文字。0.2.3 用每个栏目单独一个节点的虚构页面测试，无法代表真实 XWeb 树。其七项栏目必须各恰好一次、仅有独立短标签的条件会拒绝合并行、父子重复、导航栏目缺失等合法结构；原来的广度优先读取还可能把浅层状态表放在深层金额标题之前。0.2.4 用这些可复现差异补足测试。

## 查阅结果

1. [AutoAccounting 的无障碍服务](https://github.com/AutoAccountingOrg/AutoAccounting/blob/34b38f82ca9ad6d7905c715c66891977f8809c88/app/src/main/java/com/google/android/accessibility/selecttospeak/SelectToSpeakService.kt)：说明 WebView 文字查找不通用，专门遍历子节点并读取叶子的 text/contentDescription，避免容器重复；该项目另有页面结构记忆及 OCR。
2. [服务配置](https://github.com/AutoAccountingOrg/AutoAccounting/blob/34b38f82ca9ad6d7905c715c66891977f8809c88/app/src/main/res/xml/accessibility_service_config.xml) 包含未标记为重要的节点、全部窗口及截图等能力。这里仅采用必要的 `flagIncludeNotImportantViews`，仍限定微信/支付宝及必要页面，不启用全部窗口、截图、手势或增强网页等其他能力；也不照搬其包名或日志实现。
3. [GKD #1425](https://github.com/gkd-kit/gkd/issues/1425)：微信 8.0.76 / Origin OS6 的报告提示页面没有无障碍信息，维护者回答「该界面不提供无障碍信息」。这是相近版本的可读性限制案例，**页面、系统及微信版本不同，不能证明本手机同因**。[#1405](https://github.com/gkd-kit/gkd/issues/1405) 另有 8.0.72 后部分功能失效的报告，也不能推广为所有微信页面失效。
4. [AutoAccounting #1342](https://github.com/AutoAccountingOrg/AutoAccounting/issues/1342) 讨论无障碍 OCR 的自动触发和用户记住已成功页面；[项目说明](https://github.com/AutoAccountingOrg/AutoAccounting/blob/34b38f82ca9ad6d7905c715c66891977f8809c88/README.md) 区分 Hook、通知与 OCR。这些是不同数据来源，不代表普通通知监听或 Activity 白名单就能拿到账单。
5. [AutoBookKeepingBeta 的通知入口](https://github.com/FuShengDuoBuYu/AutoBookKeepingBeta/blob/bef698d16d942d1cdc06e49352d4bb71162f506c/AutoBookKeeping/app/src/main/java/com/beta/autobookkeeping/service/NotificationReceiver.java) 依赖收到系统通知，不能补抓重新打开的旧网页。当前通知事件 0 不能由此推断付款当时没有通知。
6. [AOSP AccessibilityServiceInfo](https://github.com/aosp-mirror/platform_frameworks_base/blob/1cdfff555f4a21f71ccc978290e2e212e2f8b168/core/java/android/accessibilityservice/AccessibilityServiceInfo.java) 把 `FLAG_REQUEST_ENHANCED_WEB_ACCESSIBILITY` 标为 Deprecated / No longer used，不能把类似项目设置了该 flag 当成 API 36 的确定修复。

仅借鉴公开机制和设计思路，本轮实现为本仓库独立编写；没有复制或引入 GPL 项目的代码、依赖、Hook、服务器或 AI。AutoAccounting 仓库为 GPL-3.0；文件中的许可文字不一致也不是直接复制的依据。

## 本轮落地

- 自动识别仍限微信 `MMWebViewUI`。必要栏目只用当前状态、支付时间、支付方式、交易单号；商户单号、收单机构、账单服务为可选，不要求导航栏决定交易。
- 有界比较标签前缀，支持「当前状态支付成功」和「支付时间: 日期」等合并行；同名父子节点只记存在，不误当多笔。分类前不复制字段值，长正文不作为短栏目。包名缺失的虚拟子节点仅在根节点包名和窗口已验证后允许，明确其他应用仍拒绝。
- 网页采用文档顺序和叶子文字，保留明确栏目行；金额候选仍需状态明确，不猜正负方向、不凭平台猜分类。所有网页新记录强制待核对，已有同交易号账单不被修改。
- 原生元数据最多 256 节点、网页最多 512 节点；读取仍至多 4000 字符、48 片段。任一缺节点、超限或敏感保护都丢弃整次样本；原生付款页/通知既有上限不变。
- 分开报告无 WebView、多个 WebView、无文字、节点被可见性/窗口/安全规则过滤、必要栏目不齐、缺节点及结构超限；只保留固定枚举、节点整数及四个栏目是否存在，不保留任何交易值或完整树。
- 可主动请求一次读取：30 秒内仅针对当前已验证微信网页窗口尝试一次，可绕过自动结构预筛选，之后仍需最小交易字段才形成待核对；不能触发其他应用/微信聊天/小程序，超时、服务断开、只读检查或关闭开关即结束。初始加载及内容变化短暂防抖，不循环采集。

## 未解决的边界

本轮未实现 OCR、截图、Root、Shizuku、修改微信或云端处理。若完整检查未超限、未缺节点、没有过滤且真机显示无文字，主动一次读取也没有字段，就停止在文字规则上继续试错；下一条独立路线是本机临时 OCR 或账单导入。它需要单独验证离线模型、只处理必要页面/用户选定图片、不保留图像、APK 依赖和权限，再交付，不能用本轮测试冒充已实现。

即使文字可读，网页容器与栏目也不认证来源，因此不能直接把普通网页当作已付款证据并自动确认。云端测试验证的是模拟结构和金额/去重约束，真实微信字段读取及新付款仍待手机结果。
