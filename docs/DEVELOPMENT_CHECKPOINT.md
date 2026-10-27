# 开发暂停记录：2026-10-27

用户最新要求：「下次再做，先保存记录」。本轮开发暂停，不继续实现、构建或发布；等待用户再次明确开始。手机测试仍暂停，无需安装、消费、截图或提供真实账单。

## 当前可恢复基线

- 最新已发布版本仍为 `0.3.0-prototype`，versionCode 10，源码与验证归档见 [0.3.0 交付记录](RELEASE_0.3.0.md)。本轮开始时仓库提交为 `9ea205c`。
- [现有 APK](https://github.com/xiaochuan45/auto-ledger-android/releases/download/prototype-v0.3.0/auto-ledger-prototype.apk) SHA-256：`4c028f24ef7f160e5e203d4515719dc6aeb7e1e9dc31afa0fd985883672d58f3`。
- 本轮仅做方案和依赖下载准备；没有修改应用源码、Gradle 依赖、版本号或数据库，没有生成新版 APK，也没有新增测试通过证据。
- 既有云端验证不等于微信、支付宝或电商场景真机成功。0.2.x 真机未能自动记录的反馈继续保留，0.3.0 的实际支付场景仍待验证。
- 产品目标是一个本地账本覆盖微信、支付宝及多种购物软件；不需要同步到一木、钱迹等其他记账软件。

## 下次实施范围（尚未实现）

1. 用户主动通过系统文件选择器选择付款截图，在手机本地 OCR；不做持续截屏或扩大无障碍读取范围。图片和整段识别文字只在内存中处理，不保存原图或全文。先展示必要账单字段，OCR 结果保存为待核对，不能凭图片识别直接断言支付成功。
2. 用户主动选择微信、支付宝个人账单 CSV / XLSX；先解析并展示预览，再确认批量写入。支持范围由明确表头和虚构样本验证，不把商户对账单或未知格式当作已支持。旧式 XLS、加密压缩包和未知模板暂不承诺。
3. 接入现有交易号和来源去重；重复导入、跨来源重复、删除后重识别、连续同金额不同交易分别验证。未知渠道不能猜测合并；保留用户已编辑内容。
4. 支付成功、金额和时间明确的导入消费才可确认为消费；失败、取消、待支付不计支出。退款申请、原订单的退款标记不等于实际退款到账。缺实际退款日期、金额或关联依据的记录留待核对，不能用原付款日期和金额编造退款。
5. 限制图片字节数和像素、CSV 行列及单元格、XLSX 解压体积和 XML；拒绝公式、外部实体和异常格式。先完整解析再以事务保存，失败不能留下半批账单。
6. 使用虚构数据验证 OCR 引擎、CSV / XLSX、金额状态边界、去重和覆盖升级；检查最终 APK 无联网权限、无云端识别或统计 SDK，并保留包名、签名和已有数据。

暂定下一版 `0.4.0-prototype` / code11；实际版本以完成实现和验证后的交付为准。自动截图触发方案另行评估，本轮没有实现或承诺自动 OCR 覆盖全部付款场景。

## 离线 OCR 候选依赖与下载记录

候选为 Tesseract4Android 4.9.0 与 `tessdata_fast` 中英文模型。目标是随 APK 打包模型，首次使用也不需要联网。当前仅下载成功并计算哈希，尚未完成 Android 集成、原生库 16 KB 页兼容性、实际识别、性能、许可证清单和最终包权限审查；不能写成已验证可用。

- Maven 坐标：`cz.adaptech.tesseract4android:tesseract4android:4.9.0`，候选仓库 JitPack，若采用应严格限制依赖组。
- [包装库源码](https://github.com/adaptech-cz/Tesseract4Android/tree/4.9.0)；[AAR 下载地址](https://jitpack.io/cz/adaptech/tesseract4android/tesseract4android/4.9.0/tesseract4android-4.9.0.aar)。POM 列出的运行依赖为 `androidx.annotation:annotation:1.3.0`，最终仍须复查依赖树和合并 Manifest。
- [模型固定源码版本](https://github.com/tesseract-ocr/tessdata_fast/tree/87416418657359cb625c412a48b6e1d6d41c29bd)，固定提交 `87416418657359cb625c412a48b6e1d6d41c29bd` 下 `chi_sim.traineddata`、`eng.traineddata` 和 `LICENSE`。采用前补齐包装库、Tesseract、Leptonica 及模型的第三方许可说明；项目自身许可证仍待用户选择。

| 已下载文件 | SHA-256 |
| --- | --- |
| `ocr.aar` | `bce5d6413a1a5ae3d7240033fbbc851ba3217d0a08d9769400e17a077f42cb2a` |
| `ocr.pom` | `be3268bbfdc6755b23e4edcf2395fa70a253820caad8f5af5dafd679961be1c2` |
| `chi_sim.traineddata` | `a5fcb6f0db1e1d6d8522f39db4e848f05984669172e584e8d76b6b3141e1f730` |
| `eng.traineddata` | `7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2` |
| 模型 `LICENSE` | `cfc7749b96f63bd31c3c42b5c471bf756814053e847c10f3eb003417bc523d30` |

临时下载位于当前云端工作区 `/workspace/scratch/offline-intake-research/`，未加入 Git。工作区可能被回收，不能把临时目录当作持久归档；以上公开来源、固定版本和哈希用于重新取得并核验。下载过不代表哈希具有上游签名背书。

## 恢复开发时

先阅读本记录、[仓库约定](../AGENTS.md)和[当前状态](PHASE2_STATUS.md)，检查工作区、仓库权限和工具链，再实施上述功能。无需重复要求用户配置 GitHub 或支付测试。保持手机测试暂停，除非用户明确恢复。完成后分别说明代码、云端自动化、实际 APK 和真机证据，不把本记录里的设计当成已交付功能。
