"""Sign an already-built APK using the owner's external, DPAPI-protected identity.

No password arguments, Gradle properties, private files or secrets enter the source tree.
Requires Android SDK build-tools, a JDK, and the local Haoke credential helper.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("apk", type=Path)
    parser.add_argument("output", type=Path)
    parser.add_argument("--identity", type=Path, default=Path(os.environ["LOCALAPPDATA"]) / "Haoke/AndroidSigning/nextplayer-release/signing-identity.json")
    parser.add_argument("--build-tools", type=Path, required=True)
    parser.add_argument("--credential-reference", type=Path, help="Override the nonsecret reference path when application data is redirected")
    args = parser.parse_args()
    identity = json.loads(args.identity.read_text(encoding="utf-8"))
    if identity["applicationId"] != "dev.anilbeesetti.nextplayer.haoke":
        raise RuntimeError("Wrong product identity")
    expected = identity["certificateSha256"]
    if expected != "d27867cecea4c6e041195cc87501c4663cefa6a6c6240a0fca835998a87c09f9":
        raise RuntimeError("Signing identity changed; restore the established key")
    badging = subprocess.run([str(args.build_tools / "aapt.exe"), "dump", "badging", str(args.apk)],
                             capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=30)
    package = re.search(r"^package: name='([^']+)'", badging.stdout)
    if badging.returncode or not package or package[1] not in {identity["applicationId"], identity["applicationId"] + ".test"}:
        raise RuntimeError("Input APK is not this product or its isolated device-test package")
    credential_ref = args.credential_reference or Path(identity["credentialReference"])
    reference = json.loads(credential_ref.read_text(encoding="utf-8"))
    helper = Path.home() / ".claude/skills/apk-remote-popup/scripts"
    sys.path.insert(0, str(helper))
    from local_credentials import unseal
    secret = unseal((credential_ref.parent / reference["credentialsFile"]).read_bytes())
    env = os.environ.copy()
    env["NEXTPLAYER_STORE_PASS"] = secret["store_pass"]
    env["NEXTPLAYER_KEY_PASS"] = secret["key_pass"]
    try:
        tool = str(args.build_tools / "apksigner.bat")
        result = subprocess.run([tool, "sign", "--ks", str(args.identity.parent / identity["keystoreFile"]),
                                 "--ks-key-alias", identity["alias"], "--ks-pass", "env:NEXTPLAYER_STORE_PASS",
                                 "--key-pass", "env:NEXTPLAYER_KEY_PASS", "--out", str(args.output), str(args.apk)],
                                env=env, capture_output=True, timeout=120)
        if result.returncode:
            raise RuntimeError("APK signing failed; no secret-bearing subprocess output is displayed")
        verification = subprocess.run([tool, "verify", "--verbose", "--print-certs", str(args.output)],
                                      capture_output=True, text=True, timeout=60)
        certificates = re.findall(r"certificate SHA-256 digest: ([0-9a-f]{64})", verification.stdout)
        if verification.returncode or "Number of signers: 1" not in verification.stdout or not certificates or set(certificates) != {expected}:
            raise RuntimeError("Signed APK certificate verification failed")
        with args.output.open("rb") as artifact:
            digest = hashlib.file_digest(artifact, "sha256").hexdigest()
        print(json.dumps({"apk": str(args.output.resolve()), "applicationId": package[1], "certificateSha256": expected,
                          "sizeBytes": args.output.stat().st_size,
                          "apkSha256": digest}, indent=2))
    finally:
        env.pop("NEXTPLAYER_STORE_PASS", None)
        env.pop("NEXTPLAYER_KEY_PASS", None)
        secret.clear()


if __name__ == "__main__":
    main()
