#!/usr/bin/env bash
# Decodes the release keystore from the KEYSTORE_B64 secret and checks it against
# the other signing secrets, so that a bad secret fails here with a clear message
# instead of somewhere inside the Gradle build.
#
# usage: decode-keystore.sh <output.jks>
# env:   KEYSTORE_B64, LINEOVER_KEYSTORE_PASS, LINEOVER_KEY_ALIAS
#        KEYSTORE_PROBLEM_LEVEL  "error" (default) or "warning": how a bad secret is
#                                annotated. The exit status is 1 either way; the
#                                caller decides whether that fails the job.
set -euo pipefail

out="$1"
level="${KEYSTORE_PROBLEM_LEVEL:-error}"
fail() {
    echo "::$level::$1"
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

# A keystore encoded twice (base64 of the .b64 file) decodes to base64 text again.
if [ "$(head -c 3 "$out")" = "MII" ] && ! LC_ALL=C grep -q '[^A-Za-z0-9+/=[:space:]]' "$out"; then
    if tr -d '[:space:]' <"$out" | base64 --decode >"$out.tmp" 2>/dev/null && [ -s "$out.tmp" ]; then
        mv "$out.tmp" "$out"
        echo "::notice::KEYSTORE_B64 was base64-encoded twice; decoded it a second time."
    else
        rm -f "$out.tmp"
    fi
fi

# PKCS12 files start with an ASN.1 SEQUENCE (0x30), JKS files with FEEDFEED.
size="$(wc -c <"$out")"
magic="$(head -c 4 "$out" | od -An -tx1 | tr -d ' \n')"
case "$magic" in
    30*) format="PKCS12" ;;
    feedfeed) format="JKS" ;;
    *) fail "KEYSTORE_B64 decodes to $size bytes that are not a keystore file (neither PKCS12 nor JKS). Encode the .jks file itself: base64 -w 0 lineover-release.jks" ;;
esac

[ -n "${LINEOVER_KEYSTORE_PASS:-}" ] || fail "KEYSTORE_PASS secret is missing."
[ -n "${LINEOVER_KEY_ALIAS:-}" ] || fail "KEY_ALIAS secret is missing."

opens_with() {
    KEYSTORE_TRY_PASS="$1" keytool -list -keystore "$out" -storepass:env KEYSTORE_TRY_PASS >/dev/null 2>&1
}
if ! opens_with "$LINEOVER_KEYSTORE_PASS"; then
    trimmed="$(printf '%s' "$LINEOVER_KEYSTORE_PASS" | sed -e 's/^[[:space:]]*//' -e 's/[[:space:]]*$//')"
    if [ "$trimmed" != "$LINEOVER_KEYSTORE_PASS" ] && opens_with "$trimmed"; then
        fail "KEYSTORE_PASS has a space or line break before or after the password. The keystore opens once that is removed: re-enter KEYSTORE_PASS (and KEY_PASS) without it."
    fi
    # Neither value below is secret: the tool's own error line, and a fingerprint of
    # the decoded file that can be compared with the original on the phone.
    keytool_says="$(KEYSTORE_TRY_PASS="$LINEOVER_KEYSTORE_PASS" keytool -list -keystore "$out" -storepass:env KEYSTORE_TRY_PASS 2>&1 | grep -v '^Picked up' | head -n 1 | tr -d '\r' || true)"
    sha="$(sha256sum "$out" | cut -c1-16)"
    if [ "$format" = "PKCS12" ] && openssl pkcs12 -in "$out" -noout -passin env:LINEOVER_KEYSTORE_PASS >/dev/null 2>&1; then
        fail "KEYSTORE_PASS is correct (OpenSSL opens the keystore), but this JDK's keytool cannot read the file [$keytool_says]. This is a problem in the build setup, not in your secrets."
    fi
    fail "The $format keystore in KEYSTORE_B64 ($size bytes, sha256 starts with $sha) does not open with KEYSTORE_PASS [$keytool_says]. Either the password is wrong or the pasted text was damaged. On the phone, 'sha256sum lineover-release.jks' must start with the same 16 characters: if it does, the password is wrong; if not, KEYSTORE_B64 was not copied intact."
fi
if ! keytool -list -keystore "$out" -storepass:env LINEOVER_KEYSTORE_PASS -alias "$LINEOVER_KEY_ALIAS" >/dev/null 2>&1; then
    trimmed_alias="$(printf '%s' "$LINEOVER_KEY_ALIAS" | tr -d '[:space:]')"
    if [ "$trimmed_alias" != "$LINEOVER_KEY_ALIAS" ]; then
        fail "KEY_ALIAS contains a space or line break. Re-enter it without one."
    fi
    count="$(keytool -list -keystore "$out" -storepass:env LINEOVER_KEYSTORE_PASS 2>/dev/null | grep -c 'PrivateKeyEntry' || true)"
    fail "The keystore opens, but it has no key with the alias given in KEY_ALIAS (it holds $count private key(s)). List the alias with: keytool -list -keystore lineover-release.jks"
fi
echo "Release keystore decoded and verified."
