#!/usr/bin/env bash
# Fails if signing material or credentials are in the repository; the rules are
# in check_secrets.py. Usage: scripts/check-secrets.sh [--worktree | --history | --self-test]
# Works before the first commit, in CI, and as a pre-commit hook:
#   ln -s ../../scripts/check-secrets.sh .git/hooks/pre-commit
set -euo pipefail
root="$(git rev-parse --show-toplevel)"
cd "$root"
exec python3 "$root/scripts/check_secrets.py" "$@"
