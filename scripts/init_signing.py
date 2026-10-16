#!/usr/bin/env python3
"""Initialize a private cloud signing key once; never replace a pinned key implicitly."""
import argparse
import hashlib
import os
from pathlib import Path
import secrets
import subprocess


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--private-dir", type=Path, required=True)
    parser.add_argument("--initialize", action="store_true")
    args = parser.parse_args()
    private = args.private_dir.resolve()
    repo = Path(__file__).resolve().parent.parent
    if private == repo or repo in private.parents:
        raise SystemExit("Signing material must be outside the repository")
    pin = repo / "config/signing-certificate.sha256"
    key, password = private / "ledger.p12", private / "password"
    if not key.exists() or not password.exists():
        if pin.exists() or key.exists() or password.exists() or not args.initialize:
            raise SystemExit("Pinned signing key unavailable: restore its private backup; do not create a replacement")
        private.mkdir(parents=True, mode=0o700, exist_ok=True)
        os.chmod(private, 0o700)
        password.write_text(secrets.token_urlsafe(48), encoding="utf-8")
        os.chmod(password, 0o600)
        command = [
            "keytool", "-genkeypair", "-storetype", "PKCS12", "-keystore", str(key),
            "-alias", "auto-ledger", "-storepass:file", str(password), "-keypass:file", str(password),
            "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
            "-dname", "CN=Auto Ledger Android, O=Auto Ledger, C=CN",
        ]
        generated = subprocess.run(command, capture_output=True)
        if generated.returncode:
            raise SystemExit("Signing key generation failed; inspect keytool locally without exposing passwords")
        os.chmod(key, 0o600)
    exported = subprocess.run([
        "keytool", "-exportcert", "-keystore", str(key), "-alias", "auto-ledger",
        "-storepass:file", str(password),
    ], check=True, capture_output=True).stdout
    fingerprint = hashlib.sha256(exported).hexdigest()
    if pin.exists():
        if pin.read_text().strip() != fingerprint:
            raise SystemExit("Private key does not match the pinned public certificate")
    else:
        pin.parent.mkdir(exist_ok=True)
        pin.write_text(fingerprint + "\n", encoding="ascii")
    print("Signing key ready; pinned public certificate SHA-256:", fingerprint)


if __name__ == "__main__":
    main()
