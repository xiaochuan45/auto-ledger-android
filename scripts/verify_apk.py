#!/usr/bin/env python3
"""Audit a packaged ledger APK with Android SDK tools; no third-party modules."""

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
APPLICATION_ID = "io.github.xiaochuan45.autoledger"
BACKUP_DOMAINS = {
    "root", "file", "database", "sharedpref", "external", "device_root",
    "device_file", "device_database", "device_sharedpref",
}
CAPTURE_PACKAGES = {"com.tencent.mm", "com.eg.android.AlipayGphone"}


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


def qualified_name(package: str, name: str) -> str:
    if name.startswith("."):
        return package + name
    return name if "." in name else package + "." + name


def audit_manifest(root: ET.Element, package: str, version_code: int,
                   version_name: str, build_type: str) -> dict:
    require(root.tag == "manifest", "APK manifest has an unexpected root")
    require(root.get("package") == package, "Unexpected application ID")
    require(root.get(ANDROID + "versionCode") == str(version_code), "Unexpected versionCode")
    require(root.get(ANDROID + "versionName") == version_name, "Unexpected versionName")
    sdk = root.find("uses-sdk")
    require(sdk is not None, "Missing SDK declaration")
    require(sdk.get(ANDROID + "minSdkVersion") == "26", "minSdk must be 26")
    require(sdk.get(ANDROID + "targetSdkVersion") == "35", "targetSdk must be 35")

    # AndroidX Core's app-owned signature permission grants no system-data access.
    internal_permission = package + ".DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
    permissions = [node.get(ANDROID + "name", "") for node in root
                   if node.tag.startswith("uses-permission")]
    require(set(permissions) <= {internal_permission},
            f"Unexpected requested permissions: {permissions}")
    declared = root.findall("permission")
    require(len(declared) <= 1, "Unexpected permission declarations")
    for node in declared:
        require(node.get(ANDROID + "name") == internal_permission, "Unexpected permission declaration")
        protection_level = node.get(ANDROID + "protectionLevel", "")
        if protection_level != "signature":
            require(int(protection_level, 0) == 2, "Internal permission must be signature protected")
    if internal_permission in permissions:
        require(len(declared) == 1, "Internal permission needs a signature declaration")

    app = root.find("application")
    require(app is not None, "Missing application")
    require(app.get(ANDROID + "allowBackup") == "false", "allowBackup must be false")
    require(app.get(ANDROID + "usesCleartextTraffic") == "false", "Cleartext traffic must be disabled")
    debuggable = app.get(ANDROID + "debuggable", "false")
    require(debuggable == ("true" if build_type == "debug" else "false"),
            "Unexpected debuggable value for build type")
    if build_type == "release":
        require(app.get(ANDROID + "testOnly", "false") == "false",
                "Delivery APK must install normally without adb's test-only override")
    require(not app.findall("receiver"), "The app must not register receivers")
    require(not app.findall("activity-alias"), "Unexpected activity alias")
    activities = app.findall("activity")
    require(len(activities) == 1, "The app must have exactly one Activity")
    activity = activities[0]
    require(qualified_name(package, activity.get(ANDROID + "name", "")) == package + ".MainActivity",
            "Unexpected Activity")
    require(activity.get(ANDROID + "exported") == "true", "Launcher Activity must be exported")
    require(not activity.get(ANDROID + "permission"), "Launcher must be directly startable")
    launcher = any(
        any(action.get(ANDROID + "name") == "android.intent.action.MAIN" for action in item.findall("action"))
        and any(category.get(ANDROID + "name") == "android.intent.category.LAUNCHER" for category in item.findall("category"))
        for item in activity.findall("intent-filter")
    )
    require(launcher, "The Activity must provide a MAIN/LAUNCHER intent filter")

    expected_services = {
        package + ".capture.LedgerNotificationService": (
            "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
            "android.service.notification.NotificationListenerService"),
        package + ".capture.LedgerAccessibilityService": (
            "android.permission.BIND_ACCESSIBILITY_SERVICE",
            "android.accessibilityservice.AccessibilityService"),
    }
    services = app.findall("service")
    require(len(services) == 2, "Only the two system-protected capture services are allowed")
    found = {}
    for service in services:
        name = qualified_name(package, service.get(ANDROID + "name", ""))
        require(name in expected_services and name not in found, "Unexpected or duplicate service")
        permission, action = expected_services[name]
        require(service.get(ANDROID + "exported") == "true", "Capture service must bind from system")
        require(service.get(ANDROID + "permission") == permission, "Capture service lacks system binding protection")
        actions = [item.get(ANDROID + "name") for item in service.findall("intent-filter/action")]
        require(actions == [action], "Unexpected capture service actions")
        require(not service.get(ANDROID + "process"), "Capture service must use the app process")
        found[name] = service

    providers = app.findall("provider")
    require(len(providers) <= 1, "Unexpected additional provider")
    for provider in providers:
        require(provider.get(ANDROID + "name") == "androidx.startup.InitializationProvider", "Unexpected provider")
        require(provider.get(ANDROID + "exported") == "false", "AndroidX startup provider must be private")
        require(provider.get(ANDROID + "authorities") == package + ".androidx-startup", "Unexpected startup authority")
        require(provider.get(ANDROID + "grantUriPermissions", "false") == "false", "Provider must not grant URI access")
    require(root.find("instrumentation") is None, "Test instrumentation must not ship in the app APK")
    return {"application": app, "permissions": permissions,
            "launcher": activity.get(ANDROID + "name"), "provider_count": len(providers),
            "accessibility": found[package + ".capture.LedgerAccessibilityService"]}


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
        candidates = [entry for entry in archive.namelist()
                      if re.fullmatch(r"res/xml(?:-[^/]+)?/" + re.escape(name) + r"\.xml", entry)]
    require(candidates == [f"res/xml/{name}.xml"],
            f"Expected one unqualified XML for {name}, got {candidates}")
    xml_text = run(analyzer, "resources", "xml", "--file", "/" + candidates[0], str(apk))
    (output_dir / (name + ".xml")).write_text(xml_text, encoding="utf-8")
    return ET.fromstring(xml_text)


def audit_exclusions(section: ET.Element | None, label: str) -> None:
    require(section is not None, f"Missing {label} backup section")
    require(not section.findall("include"), f"{label} must not include any data")
    excluded = {node.get("domain") for node in section.findall("exclude") if node.get("path") == "."}
    require(BACKUP_DOMAINS <= excluded,
            f"{label} must exclude every backup domain; missing {sorted(BACKUP_DOMAINS - excluded)}")


def audit_backup(apk: Path, app: ET.Element, analyzer: str, resources: str, output_dir: Path) -> None:
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


def audit_accessibility(apk: Path, service: ET.Element, analyzer: str,
                        resources: str, output_dir: Path) -> None:
    metadata = service.findall("meta-data")
    require(len(metadata) == 1, "Accessibility service needs exactly one configuration")
    item = metadata[0]
    require(item.get(ANDROID + "name") == "android.accessibilityservice", "Unexpected accessibility metadata")
    config = packaged_xml(apk, resource_name(item.get(ANDROID + "resource", ""), resources), analyzer, output_dir)
    require(config.tag == "accessibility-service", "Invalid accessibility configuration root")
    packages = {name.strip() for name in config.get(ANDROID + "packageNames", "").split(",") if name.strip()}
    require(packages == CAPTURE_PACKAGES, "Accessibility must only inspect WeChat and Alipay")
    event_types = config.get(ANDROID + "accessibilityEventTypes", "")
    if not re.fullmatch(r"(?:0x[0-9a-fA-F]+|[0-9]+)", event_types):
        require(set(event_types.split("|")) == {"typeWindowStateChanged", "typeWindowContentChanged"},
                "Unexpected accessibility event types")
    else:
        require(int(event_types, 0) == (32 | 2048), "Unexpected accessibility event mask")
    flags = config.get(ANDROID + "accessibilityFlags", "0")
    require(flags in {"0", "0x0", "flagDefault"}, "Accessibility flags must not expand window access")
    require(config.get(ANDROID + "canRetrieveWindowContent") == "true", "Payment parsing needs window content")
    for attribute in ("canPerformGestures", "canTakeScreenshot", "canRequestTouchExplorationMode",
                      "canRequestFilterKeyEvents", "canControlMagnification",
                      "canRequestFingerprintGestures", "canRequestEnhancedWebAccessibility"):
        require(config.get(ANDROID + attribute, "false") == "false", f"Disallowed accessibility capability: {attribute}")
    require(config.get(ANDROID + "notificationTimeout") == "300", "Accessibility debounce must remain 300 ms")


def verified_certificate(apk: Path) -> tuple[str, str]:
    signer = sdk_tool("apksigner", "build-tools/35.0.0")
    signature_text = run(signer, "verify", "--verbose", "--print-certs", str(apk))
    require(re.search(r"Verified using v[234].*:\s*true", signature_text) is not None,
            "APK must verify with a modern APK signing scheme")
    certificates = re.findall(r"Signer #\d+ certificate SHA-256 digest:\s*([0-9a-fA-F:]+)", signature_text)
    require(len(certificates) == 1, "Expected exactly one APK signer")
    return certificates[0].replace(":", "").lower(), signature_text


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--output-dir", type=Path, required=True)
    parser.add_argument("--application-id", default=APPLICATION_ID)
    parser.add_argument("--version-code", type=int, default=2)
    parser.add_argument("--version-name", default="0.2.0-prototype")
    parser.add_argument("--build-type", choices=("debug", "release"), default="debug")
    parser.add_argument("--unsigned", action="store_true", help="CI-only release build; never an installable delivery")
    parser.add_argument("--expected-cert-sha256", help="Public signing certificate fingerprint, never a private key")
    args = parser.parse_args()
    require(not args.unsigned or args.build_type == "release", "Only release CI builds may skip signature verification")
    require(not args.unsigned or not args.expected_cert_sha256, "An unsigned audit cannot verify a certificate")
    args.output_dir.mkdir(parents=True, exist_ok=True)
    require(args.apk.is_file(), "APK does not exist")
    analyzer = sdk_tool("apkanalyzer", "cmdline-tools/latest/bin")
    aapt2 = sdk_tool("aapt2", "build-tools/35.0.0")
    manifest_text = run(analyzer, "manifest", "print", str(args.apk))
    (args.output_dir / "AndroidManifest.xml").write_text(manifest_text, encoding="utf-8")
    result = audit_manifest(ET.fromstring(manifest_text), args.application_id,
                            args.version_code, args.version_name, args.build_type)
    resources = run(aapt2, "dump", "resources", str(args.apk))
    audit_backup(args.apk, result["application"], analyzer, resources, args.output_dir)
    audit_accessibility(args.apk, result["accessibility"], analyzer, resources, args.output_dir)
    certificate = "未检查：仅供 CI 的未签名 release 构建，禁止分发安装"
    if not args.unsigned:
        certificate, signature_text = verified_certificate(args.apk)
        if args.expected_cert_sha256:
            require(certificate == args.expected_cert_sha256.replace(":", "").lower(), "Signing certificate changed")
        (args.output_dir / "signature-report.txt").write_text(signature_text, encoding="utf-8")
    digest = hashlib.sha256(args.apk.read_bytes()).hexdigest()
    (args.output_dir / (args.apk.name + ".sha256")).write_text(f"{digest}  {args.apk.name}\n", encoding="utf-8")
    permissions = "、".join(result["permissions"]) or "无"
    report = f"""# 自动识别小样 APK 审计

此报告检查指定 APK 的打包内容；调试构建与未签名构建不能作为正式交付。

| 项目 | 结果 |
| --- | --- |
| 文件 | `{args.apk.name}` |
| 应用 ID | `{args.application_id}` |
| 版本 | `{args.version_name}` / `{args.version_code}` |
| Android 范围 | minSdk 26 / targetSdk 35 |
| 请求的权限 | {permissions} |
| 网络与其余系统敏感 uses-permission | 无；包括 INTERNET、ACCESS_NETWORK_STATE |
| 内部权限例外 | 如存在，仅允许应用自身定义的 signature 级接收器权限 |
| 备份 | allowBackup=false；旧版关闭或排除全部域；云备份和设备迁移九个域全部排除 |
| 对外 Activity | 仅启动 Activity：`{result['launcher']}` |
| 对外服务 | 仅通知监听和无障碍；分别受系统 BIND_* 权限保护 |
| 广播接收器 | 无 |
| 无障碍范围 | 仅微信、支付宝；仅窗口变化和内容变化；默认 flags；无截图、手势或全部窗口访问 |
| AndroidX 初始化 Provider | {result['provider_count']} 个；如存在，必须不可导出 |
| 签名证书 SHA-256 | `{certificate}` |
| 构建类型 | `{args.build_type}`；debuggable={str(args.build_type == 'debug').lower()} |
| APK SHA-256 | `{digest}` |

本报告不代表支付识别、后台运行或用户手机真机验证通过。识别规则仍需逐场景真机适配。
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
