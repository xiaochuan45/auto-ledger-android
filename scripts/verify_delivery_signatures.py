#!/usr/bin/env python3
"""Check the exact delivery, instrumentation, and upgrade APKs before emulator use."""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

from verify_apk import ANDROID, APPLICATION_ID, require, run, sdk_tool, verified_certificate


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--directory", type=Path, required=True)
    parser.add_argument("--expected-cert-sha256", required=True)
    args = parser.parse_args()
    expected_certificate = args.expected_cert_sha256.replace(":", "").lower()
    require(len(expected_certificate) == 64 and all(char in "0123456789abcdef" for char in expected_certificate),
            "Expected a public SHA-256 signing certificate fingerprint")
    analyzer = sdk_tool("apkanalyzer", "cmdline-tools/latest/bin")
    result = {}
    for name in ("auto-ledger-prototype.apk", "verification-instrumentation.apk", "verification-upgrade.apk"):
        apk = args.directory / name
        require(apk.is_file(), f"Missing verification file: {name}")
        certificate, _ = verified_certificate(apk)
        require(certificate == expected_certificate, f"Signing certificate mismatch: {name}")
        manifest = ET.fromstring(run(analyzer, "manifest", "print", str(apk)))
        if name == "verification-instrumentation.apk":
            require(manifest.get("package") == APPLICATION_ID + ".test", "Unexpected instrumentation package")
            instrumentation = manifest.findall("instrumentation")
            require(len(instrumentation) == 1, "Expected one instrumentation runner")
            require(instrumentation[0].get(ANDROID + "targetPackage") == APPLICATION_ID,
                    "Instrumentation does not target the delivery app")
            require(instrumentation[0].get(ANDROID + "name") == "androidx.test.runner.AndroidJUnitRunner",
                    "Unexpected instrumentation runner")
            permissions = {item.get(ANDROID + "name") for item in manifest if item.tag.startswith("uses-permission")}
            require(not permissions & {"android.permission.INTERNET", "android.permission.ACCESS_NETWORK_STATE"},
                    "Instrumentation must not request network access")
        else:
            require(manifest.get("package") == APPLICATION_ID, "Unexpected delivery application ID")
            expected_version = "2" if name == "auto-ledger-prototype.apk" else "3"
            require(manifest.get(ANDROID + "versionCode") == expected_version, "Unexpected delivery/upgrade versionCode")
            require(manifest.get(ANDROID + "versionName") == "0.2.0-prototype", "Unexpected delivery versionName")
        result[name] = {"sha256": hashlib.sha256(apk.read_bytes()).hexdigest(), "certificate_sha256": certificate}
    (args.directory / "delivery-signatures.json").write_text(json.dumps(result, indent=2) + "\n", encoding="utf-8")
    print("Delivery, test, and upgrade APK signatures match the expected public certificate.")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError, ET.ParseError) as error:
        print(f"Delivery signature verification failed: {error}", file=sys.stderr)
        sys.exit(1)
