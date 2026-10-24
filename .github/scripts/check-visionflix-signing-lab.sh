#!/usr/bin/env bash
# Signing V1 LAB-only regressions: no private key or secret value is required here.
set -euo pipefail
workflow=".github/workflows/build-apks.yml"
gradle="app/build.gradle"
ciphertext=".github/signing/Visionflix_Signing_Key_V1.p12.enc.b64"
expected_cert="ae10b6198ccb567386af37ad8b92d9ad40aa5bada469b8528811d447fc9612f6"
expected_cipher_sha256="fb15a0054401ca7b8f925977a51e0585965ef5edfb4cca3d00a3d92550526799"
fail() { printf '::error::Signing V1 LAB policy: %s\n' "$1" >&2; exit 65; }
test -s "$workflow" || fail "workflow missing"
test -s "$gradle" || fail "Gradle config missing"
test -s "$ciphertext" || fail "encrypted key missing"
grep -Fq "Restore permanent signing identity (fail closed)" "$workflow" || fail "permanent key restore missing"
grep -Fq "$expected_cert" "$workflow" || fail "public certificate fingerprint pin missing"
grep -Fq "VISIONFLIX_SIGNING_SECRET:" "$workflow" || fail "repository secret binding missing"
grep -Fq "VISIONFLIX_SIGNING_V1_PASSWORD" "$workflow" || fail "protected secret name missing"
grep -Fq "keyAlias 'visionflix-v1'" "$gradle" || fail "permanent key alias missing"
grep -Fq "storePassword ciSigningPassword" "$gradle" || fail "Gradle signing password is not sourced from CI secret"
grep -Fq "keyPassword ciSigningPassword" "$gradle" || fail "Gradle key password is not sourced from CI secret"
grep -Fq "MOBILE_CERTIFICATES=" "$workflow" || fail "mobile APK signature verification missing"
grep -Fq "VV_CERTIFICATES=" "$workflow" || fail "TV APK signature verification missing"
grep -Fq "if: steps.stage.outputs.stage == 'MADRE'" "$workflow" || fail "publication must require MADRE stage"
if grep -Eq 'visionflix-debug-keystore-v1|actions/cache/restore@v4|lab-ephemeral' "$workflow"; then
  fail "old temporary/cache signing fallback still active"
fi
if grep -Eq "(storePassword|keyPassword) 'android'" "$gradle"; then
  fail "old debug keystore password still active"
fi
if grep -Fq "visionflix-v1.p12" .gitignore; then
  : # .gitignore can contain this concrete file name if necessary
fi
grep -Fxq '*.p12' .gitignore || fail "plaintext PKCS12 keystores must be ignored"
cipher_tmp="$(mktemp)"
trap 'rm -f "$cipher_tmp"' EXIT
base64 --decode "$ciphertext" > "$cipher_tmp" || fail "invalid base64 ciphertext"
printf '%s  %s\n' "$expected_cipher_sha256" "$cipher_tmp" | sha256sum --check --status || fail "encrypted key bytes changed"
echo "[SIGNING_LAB_TEST=PASS] Permanent V1 signing contract, LAB release guard, ciphertext hash, Mobile+TV certificate assertions."
