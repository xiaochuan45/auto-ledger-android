#!/usr/bin/env python3
"""Audit the packaged preview APK using Android SDK tools; no third-party modules."""

from __future__ import annotations

import argparse
import hashlib
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import xml.etree.ElementTree as ET
import zipfile


ANDROID = "{http://schemas.android.com/apk/res/android}"
BACKUP_DOMAINS = {
    "root", "file", "database", "sharedpref", "external", "device_root",
    "device_file", "device_database", "device_sharedpref",
}


def require(condition: bool, message: str) -> None:
    if not condition:
        raise ValueError(message)


def sdk_tool(name: str, relative_path: str) -> str:
    for variable in ("ANDROID_SDK_ROOT", "ANDROID_HOME"):
        sdk_root = os.environ.get(variable)
        if sdk_root:
            candidate = Path(sdk_root) / relative_path / name
            if candidate.is_file():
                return str(candidate)
    executable = shutil.which(name)
    require(executable is not None, f"Missing Android SDK tool: {name}")
    return str(executable)


def run(*command: str) -> str:
    completed = subprocess.run(command, capture_output=True, text=True, check=False)
    if completed.returncode != 0:
        raise ValueError(
            f"{Path(command[0]).name} failed ({completed.returncode}): "
            f"{completed.stderr.strip() or completed.stdout.strip()}"
        )
    return completed.stdout


def audit_manifest(root: ET.Element, package: str) -> dict:
    require(root.tag == "manifest", "APK manifest has an unexpected root")
    require(root.get("package") == package, "Unexpected application ID")
    require(root.get(ANDROID + "versionCode") == "1", "Unexpected preview versionCode")
    require(root.get(ANDROID + "versionName") == "0.1.0-preview", "Unexpected preview versionName")
    sdk = root.find("uses-sdk")
    require(sdk is not None, "Missing SDK declaration")
    require(sdk.get(ANDROID + "minSdkVersion") == "26", "minSdk must be 26")
    require(sdk.get(ANDROID + "targetSdkVersion") == "35", "targetSdk must be 35")

    # AndroidX Core may declare this app-owned signature permission for safe receivers.
    # It grants no network or system-data access. No other requested permission is allowed.
    internal_permission = package + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    permissions = [
        node.get(ANDROID + "name", "")
        for node in root
        if node.tag.startswith("uses-permission")
    ]
    require(set(permissions) <= {internal_permission}, f"Unexpected requested permissions: {permissions}")
    declared = root.findall("permission")
    for node in declared:
        require(node.get(ANDROID + "name") == internal_permission, "Unexpected permission declaration")
        protection_level = node.get(ANDROID + "protectionLevel", "")
        if protection_level != "signature":
            require(int(protection_level, 0) == 2, "Internal receiver permission must be signature protected")
    if internal_permission in permissions:
        require(len(declared) == 1, "Requested internal permission needs one signature declaration")

    app = root.find("application")
    require(app is not None, "Missing application")
    require(app.get(ANDROID + "allowBackup") == "false", "allowBackup must be false")
    require(app.get(ANDROID + "debuggable") == "true", "This audit is only for the debug installation preview")
    require(not app.findall("service"), "The installation preview must not register services")
    require(not app.findall("receiver"), "The installation preview must not register receivers")
    require(not app.findall("activity-alias"), "Unexpected activity alias")
    activities = app.findall("activity")
    require(len(activities) == 1, "The preview must have exactly one Activity")
    activity = activities[0]
    require(activity.get(ANDROID + "exported") == "true", "Launcher Activity must be exported")
    require(not activity.get(ANDROID + "permission"), "Launcher must be directly startable")
    launcher = any(
        any(action.get(ANDROID + "name") == "android.intent.action.MAIN" for action in item.findall("action"))
        and any(category.get(ANDROID + "name") == "android.intent.category.LAUNCHER" for category in item.findall("category"))
        for item in activity.findall("intent-filter")
    )
    require(launcher, "The Activity must provide a MAIN/LAUNCHER intent filter")
    providers = app.findall("provider")
    require(len(providers) <= 1, "Unexpected additional provider")
    for provider in providers:
        require(provider.get(ANDROID + "name") == "androidx.startup.InitializationProvider", "Unexpected provider")
        require(provider.get(ANDROID + "exported") == "false", "AndroidX startup provider must be private")
        require(provider.get(ANDROID + "authorities") == package + ".androidx-startup", "Unexpected startup authority")
        require(provider.get(ANDROID + "grantUriPermissions", "false") == "false", "Provider must not grant URI access")
    require(root.find("instrumentation") is None, "Test instrumentation must not ship in the app APK")
    return {"application": app, "permissions": permissions, "launcher": activity.get(ANDROID + "name"), "provider_count": len(providers)}


def resource_name(value: str, resource_dump: str) -> str:
    if value.startswith("@xml/"):
        return value.removeprefix("@xml/")
    match = re.fullmatch(r"@(?:ref/)?(?:0x)?([0-9a-fA-F]{8})", value)
    require(match is not None, f"Expected an XML resource reference, got {value!r}")
    resource_id = int(match.group(1), 16)
    for item in re.finditer(r"resource\s+(0x[0-9a-fA-F]+)\s+(?:[\w.]+:)?xml/([\w.]+)", resource_dump):
        if int(item.group(1), 16) == resource_id:
            return item.group(2)
    raise ValueError(f"Could not resolve XML resource {value}")


def packaged_xml(apk: Path, name: str, analyzer: str, output_dir: Path) -> ET.Element:
    with zipfile.ZipFile(apk) as archive:
        candidates = [
            entry for entry in archive.namelist()
            if re.fullmatch(r"res/xml(?:-[^/]+)?/" + re.escape(name) + r"\.xml", entry)
        ]
    require(candidates == [f"res/xml/{name}.xml"], f"Expected one unqualified backup XML for {name}, got {candidates}")
    xml_text = run(analyzer, "resources", "xml", "--file", "/" + candidates[0], str(apk))
    (output_dir / (name + ".xml")).write_text(xml_text, encoding="utf-8")
    return ET.fromstring(xml_text)


def audit_exclusions(section: ET.Element | None, label: str) -> None:
    require(section is not None, f"Missing {label} backup section")
    require(not section.findall("include"), f"{label} must not include any data")
    excluded = {
        node.get("domain") for node in section.findall("exclude")
        if node.get("path") == "."
    }
    require(BACKUP_DOMAINS <= excluded, f"{label} must exclude every backup domain; missing {sorted(BACKUP_DOMAINS - excluded)}")


def audit_backup(apk: Path, app: ET.Element, analyzer: str, aapt2: str, output_dir: Path) -> None:
    resources = run(aapt2, "dump", "resources", str(apk))
    old_rules = app.get(ANDROID + "fullBackupContent", "")
    if old_rules != "false":
        old_root = packaged_xml(apk, resource_name(old_rules, resources), analyzer, output_dir)
        require(old_root.tag == "full-backup-content", "Invalid legacy backup root")
        audit_exclusions(old_root, "legacy")
    new_rules = app.get(ANDROID + "dataExtractionRules", "")
    require(bool(new_rules), "Android 12+ data extraction rules must be declared")
    new_root = packaged_xml(apk, resource_name(new_rules, resources), analyzer, output_dir)
    require(new_root.tag == "data-extraction-rules", "Invalid data extraction root")
    audit_exclusions(new_root.find("cloud-backup"), "cloud-backup")
    audit_exclusions(new_root.find("device-transfer"), "device-transfer")


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--application-id", default="io.github.xiaochuan45.autoledger.preview")
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    require(args.apk.is_file(), "APK does not exist")
    analyzer = sdk_tool("apkanalyzer", "cmdline-tools/latest/bin")
    signer = sdk_tool("apksigner", "build-tools/35.0.0")
    aapt2 = sdk_tool("aapt2", "build-tools/35.0.0")
    manifest_text = run(analyzer, "manifest", "print", str(args.apk))
    (args.output_dir / "AndroidManifest.xml").write_text(manifest_text, encoding="utf-8")
    result = audit_manifest(ET.fromstring(manifest_text), args.application_id)
    audit_backup(args.apk, result["application"], analyzer, aapt2, args.output_dir)
    signature_text = run(signer, "verify", "--verbose", "--print-certs", str(args.apk))
    require(
        re.search(r"Verified using v[234].*:\s*true", signature_text) is not None,
        "APK must verify with a modern APK signing scheme",
    )
    (args.output_dir / "signature-report.txt").write_text(signature_text, encoding="utf-8")
    digest = hashlib.sha256(args.apk.read_bytes()).hexdigest()
    (args.output_dir / (args.apk.name + ".sha256")).write_text(f"{digest}  {args.apk.name}\n", encoding="utf-8")
    permissions = "、".join(result["permissions"]) or "无"
    report = f"""# 安装预览 APK 审计

此报告由脚本检查实际打包的 APK 生成。全部检查通过后才会发布。

| 项目 | 结果 |
| --- | --- |
| 文件 | `{args.apk.name}` |
| 应用 ID | `{args.application_id}` |
| 版本 | `0.1.0-preview` / `1` |
| Android 范围 | minSdk 26 / targetSdk 35 |
| 请求的权限 | {permissions} |
| 网络与系统敏感权限 | 无；包括 INTERNET、ACCESS_NETWORK_STATE |
| 内部权限例外 | 如存在，仅允许应用自身定义的 signature 级接收器权限 |
| 备份 | allowBackup=false；旧版完全关闭或排除全部域；云备份和设备迁移全部域均排除 |
| 对外组件 | 仅启动 Activity：`{result['launcher']}` |
| 服务 / 广播接收器 | 无 |
| AndroidX 初始化 Provider | {result['provider_count']} 个；如存在，必须不可导出 |
| 签名验证 | 通过 apksigner；使用本次构建的临时 debug 签名 |
| 调试属性 | debuggable=true，仅供安装预览 |
| SHA-256 | `{digest}` |

本报告不代表支付识别、后台运行或手机真机验证通过。此版本没有账单存储和自动识别能力。
临时 debug 签名不保证跨构建覆盖安装；正式版必须改用稳定私密签名，升级保留数据尚未验证。
"""
    (args.output_dir / "permissions-audit.md").write_text(report, encoding="utf-8")
    print(f"APK audit passed: {args.apk.name}; SHA-256 {digest}")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, ET.ParseError, OSError, zipfile.BadZipFile) as error:
        print(f"APK audit failed: {error}", file=sys.stderr)
        sys.exit(1)
