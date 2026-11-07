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
    _name("private key or certificate bundle", r"\.(p12|pfx|key|p8)(\.[^/]*)?$"),
    _name("SSH private key", r"^id_(rsa|dsa|ecdsa|ed25519)$"),
    _name(
        "machine-local or secret configuration",
        r"^(keystore|signing|secrets|local)\.properties$|^google-services\.json$|^service-account.*\.json$",
    ),
    _name("environment file", r"^\.env(\.(?!example$|sample$|template$)[^/]+)?$"),
]

# Environment-variable style names that hold credentials, e.g. a *_PASSWORD or *_TOKEN variable.
_CREDENTIAL_NAME = r"(?<![A-Za-z0-9_])[A-Z][A-Z0-9_]*(?:PASSWORD|PASSWD|SECRET|TOKEN|API_KEY|KEYSTORE_BASE64|PRIVATE_KEY)"
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
    # M11 (A-2, F-5): a signing password as an unquoted value in any text file ("storePassword=hunter22" in notes.txt,
    # with or without a trailing comment), the value neither a reference ($, {, <, () nor a comment; a build script's
    # quoted form is the rule above.
    _content(
        "signing password written as a literal value",
        r"^\s*(?:storePassword|keyPassword|store\.password|key\.password)\s*[=:]\s*[^\s\"'$<{(#]{4,}\s*(#.*)?$",
    ),
    # M11 (A-2): a keystore or private key as base64 on one line (a PKCS12 or DER file starts with 0x30 0x82, "MII" in
    # base64; a JKS with FE ED FE ED, "/u3+7Q"): 200 or more base64 characters beginning so, in any text file. Wrapped
    # base64 (several lines) is joined and read by check_blobs; a PEM certificate's body (public) is left out there.
    _content("keystore or private key as base64", r"(?<![A-Za-z0-9+/_-])(MII|/u3\+7Q)[A-Za-z0-9+/_-]{194,}"),
    _content("credential written as a quoted literal", _CREDENTIAL_NAME + r"\"?\s*[:=]\s*[\"'][^\"'$<{(]{4,}[\"']"),
    # M11 (G-3): JSON's "storePassword": "…"; a password flag on a command line (keytool -storepass, bundletool
    # --ks-pass=pass:, the forms the release workflow never uses); the workflow's own PF_STORE_PASS=…; a NAME=value that
    # starts a command line (the variable, "=", the password, then the command).
    _content("signing password written in JSON", r"\"(?:storePassword|keyPassword|store\.password|key\.password)\"\s*:\s*\"[^\"$]{4,}\""),
    _content("password on a command line", r"(?:-storepass|-keypass|-srcstorepass|-deststorepass)\s+(?!:env\b|:file\b)[^\s\"'$<{(:]{4,}|--(?:ks|key)-pass=pass:[^\s\"'$]{4,}"),
    _content("credential written as a literal value", r"(?i)^\s*(?:export\s+)?(?:[a-z][a-z0-9_]*_)?(?:store|key|ks)_pass\s*=\s*(?!(?:true|false|null|none|yes|no|nil)\b)[^\s\"'$<{(#]{4,}"),
    _content("credential written as a literal value", r"(?i)(?<![a-z0-9_])[a-z][a-z0-9_]*_(?:password|passwd|secret|token|api_key|keystore_base64|private_key)=(?!(?:true|false|null|none|yes|no|nil)\b)[^\s\"'$<{(#`]{4,}\s+\S"),
    _content(
        "credential written as a literal value",
        r"^\s*(?:export\s+)?" + _CREDENTIAL_NAME + r"\s*[:=]\s*[^\s\"'$<{(#]{4,}",
        paths=_KEY_VALUE_FILES,
    ),
    # M11 (A-2, F-5): the same at the start of a line of any text file ("PROXYFLOW_KEYSTORE_PASSWORD=hunter22" or
    # "PROXYFLOW_KEYSTORE_PASSWORD: hunter22" in notes, a trailing comment allowed), in either case.
    _content(
        "credential written as a literal value",
        r"(?i)^\s*(?:export\s+)?[a-z][a-z0-9_]*_(?:password|passwd|secret|token|api_key|keystore_base64|private_key)"
        r"\s*[:=]\s*(?!(?:true|false|null|none|yes|no|nil)\b)[^\s\"'$<{(#]{4,}\s*(#.*)?$",
    ),
]


@dataclass(frozen=True)
class Finding:
    label: str
    where: str


def check_name(path: str) -> list[Finding]:
    name = path.rsplit("/", 1)[-1].lower()
    return [Finding(rule.label, path) for rule in NAME_RULES if rule.pattern.search(name)]


# M11 (A-2): a keystore under any name, by its first bytes: a JKS file starts with FE ED FE ED; a PKCS12 (or any DER)
# file with 30 82 (a SEQUENCE with a two-byte length) followed, for PKCS12, by a version INTEGER of 3 (02 01 03).
def check_binary(path: str, data: bytes) -> list[Finding]:
    """A keystore by its first bytes: JKS (FE ED FE ED); PKCS12 — a DER SEQUENCE with a two-, three- or four-byte
    length (30 82, 30 83, 30 84: a store of 64 KiB or more, with a CA chain, has three) followed by the version INTEGER 3."""
    if data.startswith(b"\xfe\xed\xfe\xed"):
        return [Finding("signing keystore (JKS by content)", path)]
    if len(data) > 10 and data[0] == 0x30 and data[1] in (0x82, 0x83, 0x84):
        offset = 2 + (data[1] - 0x80)
        if data[offset : offset + 3] == b"\x02\x01\x03":
            return [Finding("PKCS12 keystore by content", path)]
    return []


# A base64 line, with a Markdown quote's or a comment's marker before it allowed (one marker, then spaces).
_BASE64_LINE = re.compile(rb"^(?:(?:[>#*]|//)[ \t]*)?([A-Za-z0-9+/=_-]{40,80})$")


def check_blobs(path: str, data: bytes) -> list[Finding]:
    """A keystore pasted as wrapped base64 (what `base64` prints without -w0: 76-column lines): three or more
    consecutive base64 lines are joined, and the first bytes they decode to are checked like a binary file (M11, F-5).
    The lines of a PEM certificate (between BEGIN/END CERTIFICATE) are a public certificate and are left out."""
    import base64
    import binascii

    findings = []
    run: list[bytes] = []
    start = 0
    in_certificate = False
    lines = data.splitlines()
    for number, line in enumerate(lines + [b""], start=1):
        stripped = line.strip()
        if stripped.lstrip(b"># /*").startswith(b"-----BEGIN CERTIFICATE"):
            in_certificate = True
        elif stripped.lstrip(b"># /*").startswith(b"-----END CERTIFICATE"):
            in_certificate = False
        m = _BASE64_LINE.match(stripped)
        if m and not in_certificate and ALLOW_MARKER not in line:
            if not run:
                start = number
            run.append(m.group(1))
            continue
        if len(run) >= 3:
            # The first bytes of the blob, and of each line of it (a hash or another blob may stand first: G-3).
            for offset, piece in enumerate(run):
                chunk = piece[:64].replace(b"-", b"+").replace(b"_", b"/")
                try:
                    head = base64.b64decode(chunk + b"=" * (-len(chunk) % 4), validate=False)
                except (binascii.Error, ValueError):
                    continue
                found = check_binary(path, head + b"\0" * 16)
                if found:
                    findings += [Finding(f.label + " (wrapped base64)", f"{path}:{start + offset}") for f in found]
                    break
        run = []
    return findings


def check_content(path: str, data: bytes) -> list[Finding]:
    if b"\0" in data[:8000]:  # binary, as git decides it: only its first bytes are read
        return check_binary(path, data)
    findings = check_blobs(path, data)
    rules = [rule for rule in CONTENT_RULES if rule.paths is None or rule.paths.search(path)]
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
        "private key or certificate bundle": ["key.p12", "server.key", "upload.p12.bak"],
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
        ("release.txt", "PROXYFLOW_KEYSTORE_" + "PASSWORD=hunter22hunter22"),
        ("b.txt", "PROXYFLOW_KEYSTORE_" + "PASSWORD: hunter22hunter22"),
        ("c.txt", "proxyflow_keystore_" + "password=hunter22hunter22"),
        ("d.txt", "PROXYFLOW_KEYSTORE_" + "PASSWORD=hunter22hunter22 # my pw"),
        ("signing.cfg", store + "=hunter22"),
        ("e.cfg", store + " = hunter22   # prod"),
        ("notes.yml", "PROXYFLOW_KEYSTORE_" + "BASE64: " + "MII" + "A" * 300),
        ("notes.md", "the keystore: " + "MII" + "Q" * 240 + "=="),
        ("jks.txt", "/u3+7Q" + "A" * 240),
        ("wrapped.txt", "\n".join(["MIIKtAIBAzCCCm4GCSqGSIb3DQEHAaCCCl8EggpbMIIKVzCCBW4GCSqGSIb3DQEHAaCCBV8EggVbMIIF"] + ["A" * 76] * 4)),
        ("wrapped-jks.txt", "\n".join(["/u3+7QAAAAIAAAABAAAAAQAIcHJveHlmbG93AAABkQ" + "A" * 34] + ["A" * 76] * 3)),
        ("quoted.md", "\n".join(["> MIIKtAIBAzCCCm4GCSqGSIb3DQEHAaCCCl8EggpbMIIKVzCCBW4GCSqGSIb3DQEHAaCCBV8EggVbMIIF"] + ["> " + "A" * 76] * 4)),
        ("commented.kt", "\n".join(["// MIIKtAIBAzCCCm4GCSqGSIb3DQEHAaCCCl8EggpbMIIKVzCCBW4GCSqGSIb3DQEHAaCCBV8EggVbMIIF"] + ["// " + "A" * 76] * 4)),
        ("w50.txt", "\n".join(["MIIKtAIBAzCCCm4GCSqGSIb3DQEHAaCCCl8EggpbMIIKVzCC"] + ["A" * 50] * 4)),
        ("sha-then.txt", "\n".join(["a" * 64, "MIIKtAIBAzCCCm4GCSqGSIb3DQEHAaCCCl8EggpbMIIKVzCCBW4GCSqGSIb3DQEHAaCCBV8EggVbMIIF"] + ["A" * 76] * 3)),
        ("a.json", '  "PROXYFLOW_KEYSTORE_' + 'PASSWORD": "hunter22hunter22",'),
        ("secrets.json", '  "' + store + '": "hunter22hunter22"'),
        ("b.txt", "PROXYFLOW_KEYSTORE_" + 'PASSWORD="correct horse battery staple"'),
        ("c.md", "Run it as `PROXYFLOW_KEYSTORE_" + "PASSWORD=hunter22hunter22 ./gradlew bundleRelease`"),
        ("d.txt", "PF_STORE_" + "PASS=hunter22hunter22"),
        ("e.txt", "keytool -genkeypair -store" + "pass hunter22hunter22 -alias up"),
        ("e2.txt", "bundletool build-apks --ks-" + "pass=pass:hunter22hunter22"),
    ]
    binary_hits = [
        ("release.bin", b"\xfe\xed\xfe\xed" + b"\0" * 100),
        ("upload.dat", b"\x30\x82\x12\x34\x02\x01\x03" + b"\0" * 100),
        ("big.dat", b"\x30\x83\x01\x12\x34\x02\x01\x03" + b"\0" * 100),
    ]
    binary_passes = [("a.png", b"\x89PNG\r\n\x1a\n" + b"\0" * 100), ("cert.der", b"\x30\x82\x05\x00\x30\x82\x03" + b"\0" * 100)]
    content_passes = [
        ("keystore.properties.example", store + "="),
        ("build.gradle.kts", store + ' = signing.getValue("' + store + '")'),
        ("build.gradle.kts", f'"{store}" to value("{store}", "PROXYFLOW_KEYSTORE_' + 'PASSWORD"),'),
        ("ci.yml", "  PROXYFLOW_KEY_" + "PASSWORD: ${{ secrets.PROXYFLOW_KEY_PASSWORD }}"),
        ("run.sh", 'export PF_PROXY_' + 'PASSWORD="$1"'),
        ("main.go", 'cfg.Password = os.Getenv("PF_PROXY_' + 'PASSWORD")'),
        ("Notes.kt", 'val x = "' + "AKIA" + "Q" * 17 + '"'),
        ("allowed.txt", "ghp_" + k + "  # check-secrets: " + "allow"),
        ("README.md", "storePassword=       # left empty"),
        ("Screen.kt", "                hasSaved" + "Password = true,"),
        ("release.yml", "keytool -list -keystore x -store" + "pass:env PF_STORE_PASS -alias up"),
        ("release.yml", "--ks-" + "pass=\"file:$RUNNER_TEMP/ks-pass.txt\""),
        ("script.sh", "export PF_STORE_" + 'PASS="$store_pass"'),
        ("doc.md", "the variable PROXYFLOW_KEYSTORE_" + "PASSWORD holds the password you typed"),
        ("Vm.kt", "            keepsSaved" + "Password = keeps,"),
        ("flags.ini", "keystore_" + "password = false"),
        ("sha.txt", "a" * 64),
        ("cert.pem", "MIIB" + "a" * 60),
        ("cert.pem", "-----BEGIN CERTIFICATE-----\n" + "\n".join(["MIIDszCCAp" + "a" * 54] + ["a" * 64] * 5) + "\n-----END CERTIFICATE-----"),
        ("hashes.txt", "\n".join(["a" * 64] * 5)),
        ("notes.md", "the fingerprint SHA256: " + ":".join(["AB"] * 32)),
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
    for path, data in binary_hits:
        if not check_content(path, data):
            failures.append(f"the keystore-by-content rule missed {path}")
    for path, data in binary_passes:
        if check_content(path, data):
            failures.append(f"the keystore-by-content rule flagged {path}")
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
