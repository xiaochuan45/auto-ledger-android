# 本地账本 · 通知连接恢复修复 0.2.1-prototype

**代码、源码 CI 与准确签名 APK 的覆盖更新验证均通过，已公开发布。** 版本 `0.2.1-prototype` / versionCode `3`，应用 ID `io.github.xiaochuan45.autoledger`，Android 8.0 及以上。手机上的恢复、付款识别及后台运行仍待用户验证。

发布页：[prototype-v0.2.1](https://github.com/xiaochuan45/auto-ledger-android/releases/tag/prototype-v0.2.1)；安装文件：[auto-ledger-prototype.apk](https://github.com/xiaochuan45/auto-ledger-android/releases/download/prototype-v0.2.1/auto-ledger-prototype.apk)。

| 项目 | 本轮 APK |
|---|---|
| 生产源码 | [`0da2dedb14efb7ba982f91ba5c0d7bbaf414f348`](https://github.com/xiaochuan45/auto-ledger-android/commit/0da2dedb14efb7ba982f91ba5c0d7bbaf414f348) |
| 文件大小 | 7,138,714 bytes，约 6.81 MiB / 7.14 MB |
| APK SHA-256 | `c50a327439792f8669a1be670f9f067794483cb86ab3399ab54f414e86d26862` |
| 签名证书 SHA-256 | `0e2a128391be8ae3d39140ed5d2cd7de8256d62b19233b3593d9ef97180b0073`，与 `0.2.0` 相同 |
| 构建类型 | 固定私密签名 release，`debuggable=false`；[归档审计报告](https://github.com/xiaochuan45/auto-ledger-android/blob/main/docs/evidence/0.2.1-prototype/permissions-audit.md) 通过，权限范围未扩大 |

## 问题与实现内容

用户在 `0.2.0` 中完成授权，两项服务曾显示已连接；清理后台后，一次拼多多微信付款漏记，返回账本发现通知仍已授权但未连接，无障碍仍已连接。付款金额、页面类名及格式未收集，尚不能确定漏记的唯一原因。

本轮代码已实现：

- 在用户已开启总开关、所需渠道且系统通知授权有效时，通过 Android 官方 `requestRebind` 请求通知服务重连；30 秒节流，每个断开周期自动尝试一次。
- 分开提供“打开通知权限设置”和“尝试恢复通知监听”操作。
- 根据真实服务回调自动更新设置页状态；发出恢复请求不直接显示为已连接。

不新增权限，不扩大付款页面或应用白名单，不回扫旧通知，不补抓已漏掉的交易。无法绕过系统强行停止或厂商省电限制，也不能承诺所有后台付款可捕获。

## 更新与使用

使用同一私密签名、相同包名，从已安装 `0.2.0` / code `2` **直接覆盖**到 `0.2.1` / code `3`，不要先卸载或清除数据。CI 已用旧公开 APK 和准确新 APK 真实执行覆盖安装，验证账单及总/渠道开关保留；证据未使用旧版 code `3` 测试 fixture 代替。

新版中通知仍已授权但未连接时，点“尝试恢复通知监听”，等待真实连接回调。历史 `0.2.0` 可在系统中关闭再开启通知使用权，回账本刷新状态。可按品牌实际设置允许后台活动或锁定最近任务，这些选项不保证常驻。

本次漏记手动补记即可，无需重新消费；重连不能找回旧记录。离开账本界面与清理后台、强行停止不同，不需要一直开着账本界面。更完整步骤见 [手机权限与测试](https://github.com/xiaochuan45/auto-ledger-android/blob/main/docs/PHASE2_TESTING.md)。

## 本轮证据

| 层级 | 当前状态 |
|---|---|
| 代码实现 | 通知重连约束、独立按钮及真实连接状态更新已实现，源码见上表 |
| JVM / Python | 本地及 CI 的 JVM 53 项（含新增 7 项恢复约束测试）、Python 8 项通过 |
| 构建 / Lint / APK 审计 | 本地及 CI 均通过；准确固定签名 APK 审计通过，无权限扩大 |
| 最终源码 CI | [run 38850260300](https://github.com/xiaochuan45/auto-ledger-android/actions/runs/38850260300) 成功 |
| API 26 模拟器 | 实际执行 29 项，全部通过；API 29+ 系统通知 shell 测试按 SDK 条件不执行，不记作 30 项通过 |
| API 35 模拟器 | 30 项全部通过 |
| 实际固定签名 APK 与 code 2 → 3 覆盖更新 | [run 38850352682](https://github.com/xiaochuan45/auto-ledger-android/actions/runs/38850352682) 成功；旧公开 code `2` 写入虚构账目 1 项通过，真实覆盖准确新 code `3` 后账单及总/渠道开关保留校验 1 项通过，随后普通 30 项全部通过 |
| 签名交付证据 | [artifact 12024723869](https://github.com/xiaochuan45/auto-ledger-android/actions/runs/38850352682/artifacts/12024723869)（GitHub 登录下载） |
| APK 公开下载 | APK 与校验文件均已验证 HTTP 200，下载后的 SHA-256 与被测签名包一致 |
| 用户手机 0.2.0 | 安装、授权和连接观察已确认；一次拼多多微信付款漏记失败 |
| 用户手机 0.2.1 | 安装、恢复、真实付款与后台运行全部待验证 |

历史失败与当前证据分别记录在 [第二阶段记录](https://github.com/xiaochuan45/auto-ledger-android/blob/main/docs/PHASE2_STATUS.md)。不提交用户截图、真实账单或原始通知。

API 35 已通过真实系统通知授权/撤销后设置页自动更新检查，使用虚构环境并关闭采集。这不代表已在用户手机复现或修复清理后台后的漏记，不能替代拼多多、微信付款及厂商后台真机测试。

本版仍无完整分类规则、周期统计、退款到账关联、导入导出和加密备份恢复，请保留其他账目来源。私钥的保管和跨环境恢复限制见 [第二阶段记录中的签名说明](https://github.com/xiaochuan45/auto-ledger-android/blob/main/docs/PHASE2_STATUS.md)，本轮不改变该限制。
