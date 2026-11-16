#!/usr/bin/env bash
# Decodes the release keystore from the KEYSTORE_B64 secret and checks it against
# the other signing secrets, so that a bad secret fails here with a clear message
# instead of somewhere inside the Gradle build.
#
# usage: decode-keystore.sh <output.jks>
# env:   KEYSTORE_B64, LINEOVER_KEYSTORE_PASS, LINEOVER_KEY_ALIAS
set -euo pipefail

out="$1"
fail() {
    echo "::error::$1"
    rm -f "$out"
    exit 1
}

# Secrets pasted on a phone often carry line breaks or spaces; base64 has none.
clean="$(printf '%s' "$KEYSTORE_B64" | tr -d '[:space:]')"

if ! printf '%s' "$clean" | base64 --decode >"$out" 2>/dev/null; then
    # Describe the shape of the value without revealing any of it.
    length=${#clean}
    foreign="$(printf '%s' "$clean" | tr -d 'A-Za-z0-9+/=' | wc -c)"
    hint="unknown problem"
    if [ "$length" -lt 1000 ]; then
        hint="far too short for a keystore (expected about 3500-4500 characters); it looks like a file name, a password or a cut-off copy was pasted"
    elif [ "$foreign" -gt 0 ]; then
        hint="it contains $foreign character(s) that are not base64 (allowed: A-Z a-z 0-9 + / =)"
    elif [ $((length % 4)) -ne 0 ]; then
        hint="its length is not a multiple of 4, so the end of the text was cut off when copying"
    fi
    fail "KEYSTORE_B64 is not valid base64: $length characters after removing whitespace; $hint. Re-create it with base64 -w 0 on the .jks file and paste the whole output, nothing else."
fi
[ -s "$out" ] || fail "KEYSTORE_B64 decodes to an empty file."

[ -n "${LINEOVER_KEYSTORE_PASS:-}" ] || fail "KEYSTORE_PASS secret is missing."
[ -n "${LINEOVER_KEY_ALIAS:-}" ] || fail "KEY_ALIAS secret is missing."

if ! keytool -list -keystore "$out" -storepass:env LINEOVER_KEYSTORE_PASS >/dev/null 2>&1; then
    fail "The keystore in KEYSTORE_B64 cannot be opened with KEYSTORE_PASS (wrong password, or the secret is not a keystore file)."
fi
if ! keytool -list -keystore "$out" -storepass:env LINEOVER_KEYSTORE_PASS -alias "$LINEOVER_KEY_ALIAS" >/dev/null 2>&1; then
    fail "The keystore has no key with the alias given in KEY_ALIAS."
fi
echo "Release keystore decoded and verified."
