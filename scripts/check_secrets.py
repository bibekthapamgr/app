#!/usr/bin/env python3
"""Fails if signing material or credentials are in the repository.

    scripts/check-secrets.sh             files git would commit now: tracked files
                                         plus untracked ones that are not ignored
    scripts/check-secrets.sh --history   every file of every commit reachable from
                                         HEAD, so a secret that was committed and
                                         deleted later is still found
    scripts/check-secrets.sh --self-test each rule against built-in samples

Findings name the rule, file and line, never the matched text, so a real secret
is not copied into CI logs. A line containing "check-secrets: allow" is skipped
(for a reviewed false positive). Exit status: 0 clean, 1 something found,
2 the scan itself failed, so an error is never mistaken for a clean result.
"""

from __future__ import annotations

import re
import subprocess
import sys
from dataclasses import dataclass

ALLOW_MARKER = b"check-secrets: allow"


@dataclass(frozen=True)
class NameRule:
    label: str
    pattern: re.Pattern[str]  # matched against the lower-case file name (no directory)


@dataclass(frozen=True)
class ContentRule:
    label: str
    pattern: re.Pattern[bytes]
    paths: re.Pattern[str] | None = None  # only files whose path matches; None means every text file


def _name(label: str, regex: str) -> NameRule:
    return NameRule(label, re.compile(regex))


def _content(label: str, regex: str, paths: str | None = None) -> ContentRule:
    return ContentRule(label, re.compile(regex.encode()), re.compile(paths) if paths else None)


NAME_RULES = [
    _name("signing keystore", r"\.(jks|keystore|bks)(\.[^/]*)?$"),
    _name("private key or certificate bundle", r"\.(p12|pfx|key|p8)$"),
    _name("SSH private key", r"^id_(rsa|dsa|ecdsa|ed25519)$"),
    _name(
        "machine-local or secret configuration",
        r"^(keystore|signing|secrets|local)\.properties$|^google-services\.json$|^service-account.*\.json$",
    ),
    _name("environment file", r"^\.env(\.(?!example$|sample$|template$)[^/]+)?$"),
]

# Environment-variable style names that hold credentials, e.g. a *_PASSWORD or *_TOKEN variable.
_CREDENTIAL_NAME = r"(?<![A-Za-z0-9_])[A-Z][A-Z0-9_]*(?:PASSWORD|PASSWD|SECRET|TOKEN|API_KEY)"
# Files where values are usually unquoted: .env, properties, shell and YAML.
_KEY_VALUE_FILES = r"(^|/)\.env(\.[^/]+)?$|\.(properties|sh|bash|ya?ml)$"

CONTENT_RULES = [
    _content("private key", r"-----BEGIN (?:[A-Z0-9]+ )*PRIVATE KEY(?: BLOCK)?-----"),
    _content("AWS access key id", r"(?<![A-Z0-9])(?:AKIA|ASIA)[0-9A-Z]{16}(?![0-9A-Z])"),
    _content("GitHub token", r"(?<![A-Za-z0-9])gh[pousr]_[A-Za-z0-9]{36,}"),
    _content("GitHub fine-grained token", r"(?<![A-Za-z0-9])github_pat_[A-Za-z0-9_]{22,}"),
    _content("Google API key", r"(?<![A-Za-z0-9])AIza[0-9A-Za-z_\-]{35}"),
    _content("Google OAuth client secret", r"(?<![A-Za-z0-9])GOCSPX-[A-Za-z0-9_\-]{20,}"),
    _content("Slack token", r"(?<![A-Za-z0-9])xox[baprs]-[A-Za-z0-9\-]{10,}"),
    _content("Stripe live key", r"(?<![A-Za-z0-9])[rs]k_live_[0-9A-Za-z]{20,}"),
    _content("Anthropic API key", r"(?<![A-Za-z0-9])sk-ant-[A-Za-z0-9_\-]{20,}"),
    _content("signing password written in a build script", r"(?:storePassword|keyPassword)\s*=\s*\"[^\"$]+\""),
    _content("signing password written in a build script", r"(?:storePassword|keyPassword)\s*=?\s*'[^'$]+'"),
    _content(
        "signing password in a properties file",
        r"^\s*(?:storePassword|keyPassword|store\.password|key\.password)\s*[=:]\s*\S",
        paths=r"\.properties$",
    ),
    _content("credential written as a quoted literal", _CREDENTIAL_NAME + r"\s*[:=]\s*[\"'][^\"'$<{(\s]{4,}[\"']"),
    _content(
        "credential written as a literal value",
        r"^\s*(?:export\s+)?" + _CREDENTIAL_NAME + r"\s*[:=]\s*[^\s\"'$<{(#]{4,}",
        paths=_KEY_VALUE_FILES,
    ),
]


@dataclass(frozen=True)
class Finding:
    label: str
    where: str


def check_name(path: str) -> list[Finding]:
    name = path.rsplit("/", 1)[-1].lower()
    return [Finding(rule.label, path) for rule in NAME_RULES if rule.pattern.search(name)]


def check_content(path: str, data: bytes) -> list[Finding]:
    if b"\0" in data[:8000]:  # binary, as git decides it
        return []
    rules = [rule for rule in CONTENT_RULES if rule.paths is None or rule.paths.search(path)]
    findings = []
    for number, line in enumerate(data.splitlines(), start=1):
        if ALLOW_MARKER in line:
            continue
        for rule in rules:
            if rule.pattern.search(line):
                findings.append(Finding(rule.label, f"{path}:{number}"))
    return findings


def git(*args: str) -> bytes:
    return subprocess.run(["git", *args], check=True, capture_output=True).stdout


def scan_worktree() -> tuple[list[Finding], int]:
    findings: list[Finding] = []
    paths = [p.decode() for p in git("ls-files", "-z", "--cached", "--others", "--exclude-standard").split(b"\0") if p]
    checked = 0
    for path in paths:
        try:
            with open(path, "rb") as f:
                data = f.read()
        except FileNotFoundError:  # deleted in the working tree, not yet staged
            continue
        except IsADirectoryError:  # a submodule
            continue
        checked += 1
        findings += check_name(path)
        findings += check_content(path, data)
    return findings, checked


def scan_history() -> tuple[list[Finding], int]:
    commits = git("rev-list", "HEAD").decode().split()
    first_seen: dict[str, str] = {}  # path -> short commit where it first appears (oldest first)
    blobs: dict[str, tuple[str, str]] = {}  # blob id -> (path, short commit)
    for commit in reversed(commits):
        short = commit[:10]
        for entry in git("ls-tree", "-r", "-z", "--full-tree", commit).split(b"\0"):
            if not entry:
                continue
            meta, path_bytes = entry.split(b"\t", 1)
            _mode, kind, blob = meta.decode().split()
            path = path_bytes.decode()
            first_seen.setdefault(path, short)
            if kind == "blob":
                blobs.setdefault(blob, (path, short))

    findings = [Finding(f.label, f"{f.where} (commit {first_seen[path]})") for path in first_seen for f in check_name(path)]

    # Every distinct file version once, read in one batch.
    ids = list(blobs)
    batch = subprocess.run(
        ["git", "cat-file", "--batch"], input="".join(f"{i}\n" for i in ids).encode(), check=True, capture_output=True
    ).stdout
    offset = 0
    for blob_id in ids:
        header_end = batch.index(b"\n", offset)
        _id, _kind, size = batch[offset:header_end].decode().split()
        start = header_end + 1
        data = batch[start : start + int(size)]
        offset = start + int(size) + 1  # content is followed by a newline
        path, short = blobs[blob_id]
        findings += [Finding(f.label, f"{f.where} (commit {short})") for f in check_content(path, data)]
    return findings, len(commits)


def self_test() -> int:
    """Each rule must catch a sample and pass a near miss. Samples are assembled here so this file holds none."""
    k = "A" * 40
    store, key = "store" + "Password", "key" + "Password"
    caught = {
        "signing keystore": ["release.JKS", "upload.keystore", "upload.jks.bak"],
        "private key or certificate bundle": ["key.p12", "server.key"],
        "SSH private key": ["id_ed25519"],
        "machine-local or secret configuration": ["keystore.properties", "local.properties", "google-services.json"],
        "environment file": [".env", ".env.production"],
    }
    passed_names = ["keystore.properties.example", ".env.example", "server.pem.pub", "README.md", "envelope.kt"]
    content_hits = [
        ("a.pem", "-----BEGIN " + "OPENSSH PRIVATE KEY-----"),
        ("a.asc", "-----BEGIN " + "PGP PRIVATE KEY BLOCK-----"),
        ("a.txt", "id=" + "AKIA" + "Q" * 16),
        ("a.txt", "token " + "ghp_" + k),
        ("a.txt", "github_pat_" + k),
        ("a.txt", "AIza" + "x" * 35),
        ("a.txt", "xoxb-" + "1" * 12),
        ("a.txt", "sk-ant-" + "a" * 24),
        ("build.gradle.kts", store + ' = "hunter22"'),
        ("build.gradle", key + " 'hunter22'"),
        ("keystore.properties", store + "=hunter22"),
        ("Config.kt", "const val API_" + 'TOKEN = "abcd1234"'),
        (".env", "export DB_" + "PASSWORD=hunter22"),
        ("ci.yml", "  SIGNING_" + "SECRET: hunter22"),
    ]
    content_passes = [
        ("keystore.properties.example", store + "="),
        ("build.gradle.kts", store + ' = signing.getValue("' + store + '")'),
        ("build.gradle.kts", f'"{store}" to value("{store}", "PROXYFLOW_KEYSTORE_' + 'PASSWORD"),'),
        ("ci.yml", "  PROXYFLOW_KEY_" + "PASSWORD: ${{ secrets.PROXYFLOW_KEY_PASSWORD }}"),
        ("run.sh", 'export PF_PROXY_' + 'PASSWORD="$1"'),
        ("main.go", 'cfg.Password = os.Getenv("PF_PROXY_' + 'PASSWORD")'),
        ("Notes.kt", 'val x = "' + "AKIA" + "Q" * 17 + '"'),
        ("allowed.txt", "ghp_" + k + "  # check-secrets: " + "allow"),
    ]
    failures = []
    for label, names in caught.items():
        for name in names:
            if label not in {f.label for f in check_name(name)}:
                failures.append(f"name rule '{label}' missed {name!r}")
    for name in passed_names:
        if check_name(name):
            failures.append(f"name rules flagged {name!r}")
    for path, text in content_hits:
        if not check_content(path, text.encode()):
            failures.append(f"content rules missed a sample in {path}")
    for path, text in content_passes:
        if check_content(path, text.encode()):
            failures.append(f"content rules flagged a near miss in {path}: {[f.label for f in check_content(path, text.encode())]}")
    for failure in failures:
        print(f"check-secrets self-test: {failure}", file=sys.stderr)
    if failures:
        return 1
    print(f"check-secrets self-test: OK ({len(NAME_RULES)} name rules, {len(CONTENT_RULES)} content rules)")
    return 0


def main(argv: list[str]) -> int:
    mode = argv[1] if len(argv) > 1 else "--worktree"
    if mode == "--self-test":
        return self_test()
    if mode == "--history":
        findings, count = scan_history()
        scope = f"{count} commits"
    elif mode == "--worktree":
        findings, count = scan_worktree()
        scope = f"{count} files"
    else:
        print(f"usage: {argv[0]} [--worktree | --history | --self-test]", file=sys.stderr)
        return 2
    for finding in sorted(set(findings), key=lambda f: (f.where, f.label)):
        print(f"check-secrets: possible {finding.label}: {finding.where}", file=sys.stderr)
    if findings:
        print(
            "check-secrets: FAILED. Keep keys and passwords out of the repository: keystore.properties "
            "(git-ignored) or environment variables, see README. A secret that reached a commit must be "
            "revoked, not just deleted.",
            file=sys.stderr,
        )
        return 1
    print(f"check-secrets: OK ({scope} checked)")
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main(sys.argv))
    except Exception as error:  # noqa: BLE001 - any failure of the scan itself must fail loudly
        print(f"check-secrets: the scan failed: {error!r}", file=sys.stderr)
        sys.exit(2)
