#!/usr/bin/env bash
# Creates offreel's release signing key. Run it ONCE; every future release must use the same key,
# or phones refuse to install the update over the old version.
#
#   bash scripts/make-release-key.sh            # creates ~/offreel-release.jks
#   bash scripts/make-release-key.sh other.jks
#
# keytool asks for a password; you type it, nothing here stores it.
set -euo pipefail
KEY="${1:-$HOME/offreel-release.jks}"
if [ -e "$KEY" ]; then
    echo "$KEY already exists. Keep using it (and keep a backup)."
    exit 1
fi
KEYTOOL="${JAVA_HOME:-/opt/android-studio/jbr}/bin/keytool"
[ -x "$KEYTOOL" ] || KEYTOOL=keytool

"$KEYTOOL" -genkeypair -keystore "$KEY" -storetype PKCS12 -alias offreel \
    -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=offreel"

cat <<MSG

Created $KEY
  1. Back it up (password manager / cloud drive). Lose it and you can never update offreel on phones.
  2. Add two secrets to the GitHub repo (Settings -> Secrets and variables -> Actions -> New secret):
       OFFREEL_KEYSTORE_BASE64   the output of:  base64 -w0 "$KEY"
       OFFREEL_STORE_PASSWORD    the password you just typed
     Or with the GitHub CLI (it asks for the password; replace OWNER):
       base64 -w0 "$KEY" | gh secret set OFFREEL_KEYSTORE_BASE64 --repo OWNER/offreel
       gh secret set OFFREEL_STORE_PASSWORD --repo OWNER/offreel
MSG
