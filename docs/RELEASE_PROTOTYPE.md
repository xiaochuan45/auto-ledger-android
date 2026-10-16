# 本地账本 · 自动识别小样 0.2.0-prototype

这是供真实手机逐项验证的原型，不承诺全部微信、支付宝支付场景均能自动捕获。安装文件为 [auto-ledger-prototype.apk](https://github.com/xiaochuan45/auto-ledger-android/releases/download/prototype-v0.2.0/auto-ledger-prototype.apk)，对应 [prototype-v0.2.0 发布页](https://github.com/xiaochuan45/auto-ledger-android/releases/tag/prototype-v0.2.0)。正式签名安装包已发布；只安装 `auto-ledger-prototype.apk`。

## 本轮实现

- 微信、支付宝的限定交易通知与无障碍付款页面采集、本地金额及成功状态解析。
- 有明确成功证据、实际金额且不存在其他歧义的消费自动保存；分类未知记为未分类。金额、状态或重复关联不清楚时进入待核对，不累计已确认支出。
- 优先按稳定交易标识和来源事件去重；无共同交易号的相似记录保留核对入口，不按相同金额和短时间窗口强行合并。
- Room 本地保存、账单列表及明细、已确认 / 待核对筛选、手动补记、编辑、删除撤销和明确的时间来源。
- 设置中的总开关、渠道开关、通知与无障碍授权 / 连接状态和系统恢复入口；默认关闭自动记账。
- 无联网权限，无后端、云端模型、广告、统计或云日志服务；最终 APK 审计覆盖合并权限、受系统保护的服务和备份排除规则。

## 安装身份与数据

| 项目 | 内容 |
|---|---|
| 版本名 / 版本号 | `0.2.0-prototype` / `2` |
| 应用 ID | `io.github.xiaochuan45.autoledger` |
| 最低系统 | Android 8.0 / API 26；各品牌系统与具体支付版本另行实测 |
| 交付构建 | release，`debuggable=false`；以归档审计报告及公开证书指纹为准 |
| 文件大小 | 7,122,330 bytes，约 6.79 MiB / 7.1 MB |
| APK SHA-256 | `871b5e5d006d12d712c85493e76921744f7fc9a4044fae3f1ec3edaf8ddb9f32` |
| 生产源码构建基线 | [`9c473e6169909d682a699c3f5e8537330dd7db1a`](https://github.com/xiaochuan45/auto-ledger-android/commit/9c473e6169909d682a699c3f5e8537330dd7db1a) |
| 签名证书 SHA-256 | `0e2a128391be8ae3d39140ed5d2cd7de8256d62b19233b3593d9ef97180b0073` |
| 与第一阶段关系 | 旧 `.preview` 包为独立安装预览；本版另行安装，不继承旧包身份 |
| 后续更新 | 保持本包名与同一签名，提高版本号后覆盖安装 |

本轮签名私钥和口令不进入仓库、CI 日志、测试附件或 Release。签名材料保管于受管云端私有目录；在重建或更换该环境之前需完成持久密钥保管与恢复配置，否则不能重新生成可覆盖更新的同签名 APK。普通 CI 不会使用临时调试签名自动发布用户记账包。

本版尚无账单导出、加密备份恢复。卸载或清除应用数据会丢失账单，试用期间请保留其他账目来源。Android 自动云备份及设备自动迁移均被禁用。

## 验证证据的范围

| 证据 | 本轮结果 |
|---|---|
| 代码已实现 | 上述识别、本地存储和界面逻辑存在；生产源码基线见上表 |
| 最终源码 CI | [run 38847932839](https://github.com/xiaochuan45/auto-ledger-android/actions/runs/38847932839) 成功；验证脚本提交 `3523e70bc01c4770c99f407785b7acfe011c9c3e` |
| JVM / Python / Lint | JVM 46 项、Python 8 项通过；Lint 通过 |
| API 26/35 源码模拟器矩阵 | 两个系统各 27 项通过 |
| 实际签名 release APK 审计 | [正式审计报告](https://github.com/xiaochuan45/auto-ledger-android/blob/main/docs/evidence/0.2.0-prototype/permissions-audit.md) 通过，无联网权限及其余系统敏感请求权限，两项采集服务受系统权限保护，自动备份与设备迁移均排除 |
| 实际签名 APK 测试 | [run 38847326540](https://github.com/xiaochuan45/auto-ledger-android/actions/runs/38847326540) 成功；API 35 上 27 项通过 |
| 同签名覆盖更新 | 写入虚构数据 1 项通过，真实 `adb install -r` 从 versionCode 2 → 3 后校验 1 项通过 |
| 签名包验证附件 | [artifact 12023792801](https://github.com/xiaochuan45/auto-ledger-android/actions/runs/38847326540/artifacts/12023792801)（GitHub 登录下载） |
| 用户手机真机 | 第一阶段旧预览安装启动已确认；本阶段安装、权限、扫码、被扫码、网购、免密、后台扣费及后台稳定性全部待验证 |

签名交付检查针对上表同一 APK 字节完成，哈希保持不变；后续仅文档与测试脚本修改，已确认生产源码无变化。普通 CI 的 debug / unsigned release 构建不作为交付替代。模拟器不包含真实付款应用，不代表支付链路实测成功。覆盖更新测试使用虚构账目，仅验证本版数据库格式的保留，未来 schema 变更需另加迁移测试。

## 自动记账如何触发

默认关闭采集。按 [手机步骤](https://github.com/xiaochuan45/auto-ledger-android/blob/main/docs/PHASE2_TESTING.md) 一次开启总开关和所需渠道，并授予通知使用权、启用无障碍；随后由系统交易通知或受支持付款页面事件触发对应服务，无需账单应用界面常开，不循环读屏。服务需获准后台运行，强停、权限撤销或省电限制可能中断，可从状态页检查和恢复。没有可读页面及交易通知时无法捕获，当前手动补记，导入属于后续功能。

## 已知限制与下一步

- 交易通知标题、付款 Activity 类名和文字格式采用保守白名单。应用版本或系统不暴露信息时可能忽略；此时手动补记，不能承诺所有付款实时捕获。
- 无交易号或可靠付款时间的历史页面可能进入待核对；无共同标识的跨来源相似记录可能需要手动合并。连续同金额的独立交易不会仅凭相似性自动合并，但缺少标识时可能需要核对第二笔。
- 退款、转账、充值、提现仅作为待核对信息，本阶段不自动作为消费，也没有完成退款到账关联、跨期抵减统计。
- 统计只显示全部已确认支出总额。日 / 周 / 月 / 年、分类占比、退款与净支出仍待实现。
- 商户分类记忆、自定义分类管理、微信 / 支付宝账单导入、导出、加密备份恢复、可关闭的入账完成提醒属于后续阶段。
- 授权与服务连接不保证后台始终运行；品牌省电策略、锁屏、系统重启和跨夜场景需要逐项实测。

安装、权限、恢复与手机测试步骤见 [PHASE2_TESTING.md](https://github.com/xiaochuan45/auto-ledger-android/blob/main/docs/PHASE2_TESTING.md)。下一步依据结构化真机反馈调整可读范围和规则，再完善分类、统计、退款及数据管理。
