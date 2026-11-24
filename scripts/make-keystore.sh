#!/usr/bin/env bash
# Creates the upload keystore for a Play-ready CallerX release build.
#
#   ./scripts/make-keystore.sh
#
# Then add these four GitHub secrets (Settings → Secrets and variables → Actions):
#   KEYSTORE_BASE64     = the output of:  base64 -w0 callerx-release.jks
#   KEYSTORE_PASSWORD   = the store password you typed
#   KEY_ALIAS           = callerx (or the alias you typed)
#   KEY_PASSWORD        = the key password you typed
#
# The CI job then builds a signed app-release.apk and a Play-ready app-release.aab
# on every push; without the secrets it keeps building the debug APK only.
set -euo pipefail
cd "$(dirname "$0")/.."

ALIAS="${KEY_ALIAS:-callerx}"
OUT="${OUT:-callerx-release.jks}"

if [ -f "$OUT" ]; then
  echo "!! $OUT already exists — refusing to overwrite your signing key."
  echo "   Move it somewhere safe or run with OUT=another-name.jks"
  exit 1
fi

keytool -genkeypair -v \
  -keystore "$OUT" \
  -alias "$ALIAS" \
  -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=CallerX, OU=Mobile, O=CallerX, L=Dhaka, S=Dhaka, C=BD"

cat > keystore.properties <<PROPS
storeFile=android/app/callerx-release.jks
storePassword=CHANGE_ME
keyAlias=$ALIAS
keyPassword=CHANGE_ME
PROPS

echo
echo "Keystore created: $OUT"
echo "1) copy it to android/app/callerx-release.jks for local signed builds"
echo "2) fill in the real passwords in keystore.properties (git-ignored)"
echo "3) base64 it for CI:"
base64 -w0 "$OUT" 2>/dev/null || base64 "$OUT"
echo
