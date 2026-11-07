#!/usr/bin/env bash
# Makes ProxyFlow's upload key for Google Play, on the owner's own computer, outside the repository (M11).
#
# Google Play signs what users install with its own app signing key (Play App Signing); the upload key only proves to
# Play that a bundle came from the owner. Lost or leaked, it can be reset through Play Console with the app signing key
# untouched, so it is kept simple: one PKCS12 keystore with one RSA 4096 key, valid 50 years, as Play asks (at least 25).
#
# Where it goes: $PROXYFLOW_KEY_DIR, by default ~/ProxyFlow-release — never inside a checkout of the repository, whose
# .gitignore and secrets scan refuse keystores anyway. The passwords are typed, never echoed, never written anywhere by
# this script: keep them in a password manager. Nothing here is sent anywhere.
#
# Afterwards: back the keystore up (two copies, offline: an encrypted USB stick and a second one elsewhere), keep the
# passwords in the password manager with the keystore's fingerprint, and put the three values into the repository's
# GitHub secrets for the release workflow (.github/workflows/release.yml): PROXYFLOW_KEYSTORE_BASE64 (the keystore's
# bytes, base64), PROXYFLOW_KEYSTORE_PASSWORD, PROXYFLOW_KEY_PASSWORD; the alias is "proxyflow-upload" unless the
# repository variable PROXYFLOW_KEY_ALIAS says otherwise (a variable, not a secret: a secret's value is masked in every
# log line, which would hide the alias in the workflow's own error messages). The upload certificate
# (upload-certificate.pem) is what Play Console asks for when an app is set up with an existing upload key or the key
# is reset; it holds no secret.
#
# Needs: keytool (ships with a JDK; Android Studio's JBR has one at <studio>/jbr/bin/keytool), openssl, base64.
set -euo pipefail

dir="${PROXYFLOW_KEY_DIR:-$HOME/ProxyFlow-release}"
alias="${PROXYFLOW_KEY_ALIAS:-proxyflow-upload}"
keystore="$dir/proxyflow-upload.p12"

# Inside a checkout? Judged on the nearest directory that exists, since $dir usually does not yet (review A-1: a check
# on $dir itself passed any new directory, even one under a checkout).
probe="$dir"
while [ ! -d "$probe" ]; do probe="$(dirname "$probe")"; done
if git -C "$probe" rev-parse --is-inside-work-tree >/dev/null 2>&1; then
    echo "refusing: $dir is inside a git checkout ($(git -C "$probe" rev-parse --show-toplevel)); keep the key outside any repository (set PROXYFLOW_KEY_DIR)" >&2
    exit 1
fi
if [ -e "$keystore" ]; then
    echo "refusing: $keystore exists; this script never overwrites a key (move it away first if it is a mistake)" >&2
    exit 1
fi
command -v keytool >/dev/null || { echo "keytool not found: install a JDK, or add Android Studio's jbr/bin to PATH" >&2; exit 1; }

mkdir -p "$dir"
chmod 700 "$dir"

# IFS= so that a password with a space at either end is taken as typed (review F-6).
IFS= read -r -s -p "Keystore password (12 characters or more; it is not shown): " store_pass; echo
IFS= read -r -s -p "The same again: " store_pass2; echo
[ "$store_pass" = "$store_pass2" ] || { echo "the passwords differ" >&2; exit 1; }
[ "${#store_pass}" -ge 12 ] || { echo "the password is shorter than 12 characters" >&2; exit 1; }
# PKCS12 keeps one password for the store and the key; the key password is the store's.
key_pass="$store_pass"

# The certificate names the app, not the owner: Play shows nothing of it, and it holds no personal detail. The password
# reaches keytool through the environment (-storepass:env), never on its command line, which other users of the computer
# could read from the process table (review A-6); keytool's own messages are shown, as it never echoes the password.
export PF_STORE_PASS="$store_pass" PF_KEY_PASS="$key_pass"
keytool -genkeypair \
    -keystore "$keystore" -storetype PKCS12 -storepass:env PF_STORE_PASS -keypass:env PF_KEY_PASS \
    -alias "$alias" -keyalg RSA -keysize 4096 -sigalg SHA256withRSA -validity 18250 \
    -dname "CN=ProxyFlow upload key, O=ProxyFlow, C=JP" >/dev/null
chmod 600 "$keystore"

keytool -exportcert -rfc -keystore "$keystore" -storepass:env PF_STORE_PASS -alias "$alias" -file "$dir/upload-certificate.pem" >/dev/null
unset PF_STORE_PASS PF_KEY_PASS store_pass store_pass2 key_pass
fingerprint="$(openssl x509 -in "$dir/upload-certificate.pem" -noout -fingerprint -sha256 | sed 's/^.*=//')"

cat <<EOT

Made:
  keystore     $keystore  (PKCS12, alias "$alias", RSA 4096, valid 50 years)
  certificate  $dir/upload-certificate.pem  (public; for Play Console's "upload key certificate")
  SHA-256      $fingerprint

Next:
  1. Back the keystore up: two copies, offline (an encrypted USB stick, and one kept elsewhere). Write the
     fingerprint above beside the password in your password manager.
  2. GitHub → the repository → Settings → Secrets and variables → Actions → New repository secret, three times:
       PROXYFLOW_KEYSTORE_BASE64    the output of:  base64 -w0 "$keystore"   (macOS: base64 -i "$keystore")
       PROXYFLOW_KEYSTORE_PASSWORD  the password you typed
       PROXYFLOW_KEY_PASSWORD       the same password (PKCS12 keeps one)
     The alias is "$alias"; if it is not "proxyflow-upload", also add the repository *variable* (not secret)
     PROXYFLOW_KEY_ALIAS with it.
  3. The release workflow (Actions → release → Run workflow, on main) then signs the App Bundle with it and checks the
     certificate's fingerprint against the one above, which you must give it as the input "fingerprint": that run's
     bundle is the one to upload (its release-facts.txt says mode=upload-key and event=workflow_dispatch).
EOT
