#!/usr/bin/env python3
"""Sign an APK with the previously pinned private key. Passwords never enter argv or logs."""
import argparse
import hashlib
import os
from pathlib import Path
import re
import subprocess
import tempfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--private-dir", type=Path, required=True)
    parser.add_argument("--apk", type=Path, required=True)
    parser.add_argument("--out", type=Path, required=True)
    args = parser.parse_args()
    repo = Path(__file__).resolve().parent.parent
    pin = (repo / "config/signing-certificate.sha256").read_text().strip()
    private = args.private_dir.resolve()
    if private == repo or repo in private.parents:
        raise SystemExit("Private signing directory must be outside the repository")
    key, password = private / "ledger.p12", private / "password"
    if not key.is_file() or not password.is_file():
        raise SystemExit("Stable key unavailable: restore it; refusing to generate a replacement")
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
    tools = sdk / "build-tools/35.0.0"
    args.out.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="ledger-sign-") as directory:
        aligned = Path(directory) / "aligned.apk"
        subprocess.run([str(tools / "zipalign"), "-P", "16", "-f", "4", str(args.apk), str(aligned)], check=True)
        subprocess.run([
            str(tools / "apksigner"), "sign", "--ks", str(key), "--ks-key-alias", "auto-ledger",
            "--ks-pass", "file:" + str(password), "--key-pass", "file:" + str(password),
            "--v4-signing-enabled", "false", "--out", str(args.out), str(aligned),
        ], check=True, capture_output=True)
    result = subprocess.run([
        str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(args.out),
    ], check=True, text=True, capture_output=True).stdout
    fingerprint = re.search(r"Signer #1 certificate SHA-256 digest: ([0-9a-fA-F]+)", result)
    if fingerprint is None or fingerprint.group(1).lower() != pin:
        args.out.unlink(missing_ok=True)
        raise SystemExit("APK signing certificate did not match the public pin")
    subprocess.run([str(tools / "zipalign"), "-c", "-P", "16", "4", str(args.out)], check=True)
    digest = hashlib.sha256(args.out.read_bytes()).hexdigest()
    args.out.with_name(args.out.name + ".sha256").write_text(f"{digest}  {args.out.name}\n")
    print("Signed APK:", args.out.name, "SHA-256:", digest)


if __name__ == "__main__":
    main()
