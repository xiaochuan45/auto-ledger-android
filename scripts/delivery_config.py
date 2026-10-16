#!/usr/bin/env python3
"""Load non-secret release identities shared by Gradle and APK verification."""

from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path
import re
import sys


@dataclass(frozen=True)
class Version:
    code: int
    name: str


@dataclass(frozen=True)
class DeliveryConfig:
    current: Version
    previous: Version
    previous_release_tag: str
    previous_apk_sha256: str


def load_delivery_config() -> DeliveryConfig:
    path = Path(__file__).resolve().parents[1] / "config/delivery.properties"
    values = {}
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip() or line.lstrip().startswith("#"):
            continue
        key, separator, value = line.partition("=")
        key = key.strip()
        if not separator or key in values:
            raise ValueError("Malformed or duplicate public delivery configuration")
        values[key] = value.strip()
    expected = {"current.versionCode", "current.versionName", "previous.versionCode",
                "previous.versionName", "previous.releaseTag", "previous.apkSha256"}
    if set(values) != expected:
        raise ValueError("Public delivery configuration has unexpected or missing fields")
    versions = []
    for role in ("current", "previous"):
        code = values[role + ".versionCode"]
        name = values[role + ".versionName"]
        if not re.fullmatch(r"[1-9][0-9]{0,8}", code) or not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,80}", name):
            raise ValueError("Invalid release version identity")
        versions.append(Version(int(code), name))
    if versions[0].code <= versions[1].code:
        raise ValueError("The delivery version must be newer than the previous release")
    if not re.fullmatch(r"[0-9a-f]{64}", values["previous.apkSha256"]):
        raise ValueError("The previous APK must have a pinned SHA-256")
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,100}", values["previous.releaseTag"]):
        raise ValueError("Invalid previous release tag")
    return DeliveryConfig(versions[0], versions[1], values["previous.releaseTag"], values["previous.apkSha256"])


if __name__ == "__main__":
    if sys.argv[1:] != ["--codes"]:
        raise SystemExit("Usage: delivery_config.py --codes")
    config = load_delivery_config()
    print(config.previous.code, config.current.code)
