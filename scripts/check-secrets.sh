#!/usr/bin/env bash
# Fails if signing material or credentials are about to be committed.
# Checks every file git would track (tracked plus untracked-not-ignored), so it
# works before the first commit, in CI, and as a pre-commit hook:
#   ln -s ../../scripts/check-secrets.sh .git/hooks/pre-commit
set -euo pipefail
cd "$(git rev-parse --show-toplevel)"

fail=0
report() { echo "check-secrets: $1" >&2; fail=1; }

mapfile -d '' files < <(git ls-files -z --cached --others --exclude-standard)

for f in "${files[@]}"; do
  [[ -f "$f" ]] || continue
  case "$f" in
    *.jks|*.keystore|*.p12|*.pfx|*.key) report "signing/key file must not be committed: $f" ;;
    keystore.properties|local.properties|*/google-services.json|google-services.json)
      report "machine-local or secret config must not be committed: $f" ;;
  esac
done

# Content patterns: private keys, common token formats, and signing passwords
# written as literals. Lines that read a value from a property or environment
# variable are fine.
patterns=(
  '-----BEGIN ([A-Z]+ )?PRIVATE KEY-----'
  'AKIA[0-9A-Z]{16}'
  'gh[pousr]_[A-Za-z0-9]{36,}'
  'AIza[0-9A-Za-z_-]{35}'
  'xox[baprs]-[A-Za-z0-9-]{10,}'
  '(storePassword|keyPassword)[[:space:]]*=[[:space:]]*"[^"$]+"'
)
for p in "${patterns[@]}"; do
  # -I skips binary files; the spike lab writes throwaway keys only to ignored paths.
  if matches=$(printf '%s\0' "${files[@]}" | xargs -0 grep -I -n -E -e "$p" -- 2>/dev/null | grep -v '^scripts/check-secrets.sh:'); then
    report "possible secret matching /$p/:"$'\n'"$matches"
  fi
done

if [[ $fail -ne 0 ]]; then
  echo "check-secrets: FAILED. Move secrets to keystore.properties or environment variables (see README)." >&2
  exit 1
fi
echo "check-secrets: OK (${#files[@]} files checked)"
