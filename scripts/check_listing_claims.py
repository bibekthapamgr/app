#!/usr/bin/env python3
"""The Google Play listing's text (docs/m11/store-listing.md) checked like the app's own strings (M11).

What is read: the fenced blocks ```listing-title, ```listing-short, ```listing-full and ```listing-notes, and the
screenshot captions in the §4 table (`| N | `source` | caption |`). What fails:

  - any claim the app may not make (CopyClaimsTest's rules, ported): coverage of all traffic or every app, a full or
    system-wide VPN, anonymity, hiding identity, blanket security, encrypting everything, protection, "no logs", games
    and calls, a guarantee — unless the sentence denies it ("does not make you anonymous");
  - Play's metadata rules this can check: the title over 30 characters, the short description over 80, the full over
    4,000, the release notes over 500; "best", "#1", "top", "free" or an emoji in the title or a caption; a title or
    short description in capitals;
  - anything left to fill in: ‹…›;
  - a limitation not stated in the full description: TCP, UDP blocked, ping, what the proxy provider can see, not
    anonymous, Android's VPN feature (Play's VpnService policy: the listing documents the use), no VPN server.

Run: python3 scripts/check_listing_claims.py [docs/m11/store-listing.md]
"""

from __future__ import annotations

import re
import sys

FORBIDDEN: list[tuple[str, re.Pattern[str]]] = [
    ("guarantee", re.compile(r"(?i)\bguarantee")),
    (
        "all traffic",
        re.compile(
            r"(?i)\ball\s+(of\s+)?(your\s+|the\s+|this\s+)?"
            r"(device's\s+|device\s+|phone's\s+|phone\s+|apps'\s+|internet\s+|network\s+)?traffic"
        ),
    ),
    (
        "every app",
        re.compile(
            r"(?i)\b(all|every)\s+(of\s+)?(your\s+|the\s+)?(apps?|applications?)\b"
            r"|\bworks?\s+with\s+(any|every)\s+(apps?|applications?)\b"
        ),
    ),
    ("full VPN", re.compile(r"(?i)\b(full|complete|whole)[ -](device\s+)?(vpn|tunnel|protection|coverage)|\b(system|device)[ -]?wide\b")),
    ("anonymity", re.compile(r"(?i)\banonym|\buntrace|\buntrack|\bincognito|\binvisible online")),
    ("hiding identity", re.compile(r"(?i)\b(hid(e|es|ing)|mask(s|ing)?|conceal(s|ing)?)\s+(your\s+)?(real\s+)?(ip|identity|location|activity)")),
    (
        "blanket security",
        re.compile(
            r"(?i)\b(military|bank)[ -](grade|level)|\b100\s?%|\bcompletely\s+(private|secure|safe)"
            r"|\bfully\s+(private|secure|encrypted)|\bsecures?\s+(every|all)\b"
        ),
    ),
    ("encrypts everything", re.compile(r"(?i)\bencrypt(s|ed)?\s+(all|everything|your\s+traffic|all\s+your)")),
    (
        "protection",
        re.compile(r"(?i)\bprotection\b|\bprotect(s|ed|ing)?\s+(you|your)\b|\b(is|are|stays?|keeps?(\s+\w+){1,3})\s+protected\b"),
    ),
    ("no logs", re.compile(r"(?i)\bno[- ]logs?\b")),
    (
        "games and calls",
        re.compile(
            r"(?i)\bsupports?\s+(\w+\s+)?(games|gaming|calls|quic|udp)"
            r"|\bworks?\s+(with|for)\s+(\w+\s+)?(games|gaming|calls|voip|quic|udp)|\bgreat\s+for\s+(gaming|games|calls)"
        ),
    ),
    # The listing's own extra rules (review G-1): the passive and periphrastic forms of the same claims, as VPN marketing
    # writes them, which the app's strings never needed.
    ("hidden identity", re.compile(r"(?i)\b(ip( address)?|identity|location|activity|connection|data|traffic|everything)\s+(is|are|gets?|becomes?|stays?|remains?|kept|will be|will remain)\s+(hidden|masked|concealed|invisible|encrypted|secret|private\b(?!\s+(information|data)))|\bkeep(s|ing)?\s+(your\s+)?(real\s+)?(ip|identity|location|data)\s+(address\s+)?(hidden|masked|private|secret)|\bkeeps?\s+you\s+(safe|anonymous|hidden)")),
    ("nobody sees you", re.compile(r"(?i)\b(nobody|no one|no-one|no site|no website|websites? never|sites? never|trackers? never|your isp cannot|not even your isp)\s+(can\s+)?(see|sees|know|knows|track|tracks)\s+(your\s+)?(real\s+)?(ip|identity|location|activity|traffic|what you)|\bcannot be tracked\b|\bnobody is tracking you\b|\bhides? who you are\b")),
    ("no logs", re.compile(r"(?i)\b(zero|no)[- ]?log(s|ging)?\b|\blogs? nothing\b|\bnever logs\b|\bnothing is logged\b")),
    ("getting around blocks", re.compile(r"(?i)\bbypass|\baccess (blocked|any|geo)|\bget around\b|\bspoof|\bchange your ip\b|\bnew ip address\b|\bbrowse from anywhere\b")),
    ("total privacy", re.compile(r"(?i)\b(total|complete|absolute)\s+privacy\b|\btotally private\b|\b100\s+percent\b")),
    ("secures everything", re.compile(r"(?i)\bsecures?\s+(your\s+)?(connection|traffic|phone|device)\b|\bshields?\s+your\b|\bsafeguards?\b|\b(entire|whole)\s+(device|phone)\b|\btunnels? everything\b|\b(enterprise|government)[- ]grade\b|\bunbreakable\b")),
    ("games and calls, again", re.compile(r"(?i)\b(perfect|ideal|good|great)\s+(for|in)\s+(gaming|games|calls|voip)|\bplay games\b")),
    # "encrypts the connection between your phone and the proxy" is TLS to the proxy, said as the app says it (ADR-0011).
    ("encrypts your connection", re.compile(r"(?i)\bencrypt(s|ed|ing)?\s+(your|the)\s+(connection|data|internet|browsing)\b(?!\s+(between|to|with)\b)")),
    ("another location", re.compile(r"(?i)\b(appear|browse|be)\s+(to be\s+)?(as if\s+)?(in|from)\s+(another|any|a different)\s+(country|location|region)|\bunblock")),
    ("secure browsing", re.compile(r"(?i)\b(secure|private|safe)\s+(and\s+(secure|private|safe)\s+)?browsing\b")),
]
# A claim is allowed only when its own clause denies it, the denial at most three words before the claim's first word
# ("does not make you anonymous", "is not a system-wide VPN", "no guarantee"): a "not" earlier in the sentence, or in
# another clause, about something else ("Never slow, protects your privacy"; "Not only fast: works with every app"),
# allows nothing (reviews C-1, F-2, F-3). Clauses end at a comma, a colon, a semicolon, a dash, and "and", "but", "or".
# The denial may stand before a noun phrase and a relative or complement clause ("is not a VPN that hides your IP",
# "does not promise that all traffic …"), and "no" may have a word or two after it ("no more secure browsing").
NEGATION = re.compile(
    r"(?i)(\b(doesn't|does not|don't|do not|isn't|is not|never|not|cannot|can't|won't|will not|neither|nor)(\s+\S+){0,3}"
    r"(\s+(that|which|to)(\s+\S+){0,3})?|\bno(\s+\S+){0,2})\s*$"
)
# "Not only …", and a question's "isn't it …", deny nothing.
NOT_A_DENIAL = re.compile(r"(?i)\bnot\s+(only|just|merely)\b|\b(isn't|doesn't|don't|won't|can't|aren't|wouldn't)\s+(it|you|we|they|this|that)\s+(great|good|nice|amazing|wonderful|better|time|want|love|like)\b")
CLAUSE = re.compile(
    r"[,:;—()/]|\s-+\s|\s(and|but|or|because|since|so|yet|while|though|although|if|unless|when|whenever|as|whereas|until|after|before|once|where)\s"
)
# Play's metadata policy: no performance, ranking or promotional words in the title (and so in the captions, which are
# pictures of the listing), and no emoji there.
PROMOTIONAL = re.compile(r"(?i)(?<!\w)(best|top|free|number[ -]one|award|no\.\s?1|n[\u00b0\u00ba]\s?1|1st|\u2460)(?!\w)|(?<!\w)#\s?1\b")
# Emoji and the symbols Play's policy means by them: the emoticon, pictograph, transport, flag and supplemental blocks,
# the dingbats and miscellaneous symbols, the few symbols outside them that render as emoji, and the variation selector.
EMOJI = re.compile(
    r"[\U0001F000-\U0001FAFF\u2600-\u27BF\u2B00-\u2BFF\u2300-\u23FF\u203C\u2049\u2122\u2139\u2194-\u21AA\u24C2\u25AA-\u25FE\u3030\u303D\u3297\u3299\uFE0F]"
)
PLACEHOLDER = re.compile(r"‹[^›]*›")
LIMITS = {"listing-title": 30, "listing-short": 80, "listing-full": 4000, "listing-notes": 500}
REQUIRED_IN_FULL = [
    ("TCP", r"\bTCP\b"),
    ("UDP blocked", r"(?i)\bUDP\b.*\b(blocked|do not work|fall back)"),
    ("ping", r"(?i)\bping\b"),
    ("what the proxy provider sees", r"(?i)provider can see"),
    ("not anonymous", r"(?i)\b(does not|doesn't) make you anonymous"),
    ("Android's VPN feature", r"(?i)Android's VPN feature"),
    ("no VPN server", r"(?i)\bno VPN server\b"),
    ("the privacy policy", r"(?i)privacy policy"),
]


def normalized(text: str) -> str:
    return (
        re.sub(r"[\u00a0\u1680\u2000-\u200a\u202f\u205f\u3000]", " ", text)
        .replace("\uff03", "#")
        .translate(str.maketrans({c: "-" for c in "\u2010\u2011\u2012\u2013\u2212"}))
        .replace("\u2019", "'")
    )


def denied(before: str) -> bool:
    """Whether the text before a claim, in the claim's own clause, denies it."""
    clause = CLAUSE.split(before)[-1]
    return bool(NEGATION.search(clause)) and not NOT_A_DENIAL.search(clause)


def sentences(text: str) -> list[str]:
    return [s.strip() for s in re.split(r"(?<=[.!?])\s+|\n+", text) if s.strip()]


def blocks(markdown: str) -> dict[str, str]:
    out: dict[str, str] = {}
    name = None
    for line in markdown.splitlines():
        if line.startswith("```listing-"):
            name = line[3:].strip()
            out[name] = ""
        elif line.startswith("```"):
            name = None
        elif name:
            out[name] += line + "\n"
    return {k: v.strip() for k, v in out.items()}


def captions(markdown: str) -> list[str]:
    out = []
    for line in markdown.splitlines():
        cells = [c.strip() for c in line.strip().strip("|").split("|")]
        if len(cells) == 3 and cells[0].isdigit() and cells[1].startswith("`"):
            out.append(cells[2])
    return out


def check(markdown: str) -> list[str]:
    problems: list[str] = []
    found = blocks(markdown)
    for name in LIMITS:
        if name not in found:
            problems.append(f"no ```{name} block")
    texts = {name: normalized(text) for name, text in found.items()}
    shots = [normalized(c) for c in captions(markdown)]
    for i, caption in enumerate(shots, start=1):
        texts[f"caption {i}"] = caption
    if not 2 <= len(shots) <= 8:
        problems.append(f"{len(shots)} screenshot captions; Play takes two to eight screenshots")

    for name, text in texts.items():
        for sentence in sentences(text):
            for claim, pattern in FORBIDDEN:
                for m in pattern.finditer(sentence):
                    if not denied(sentence[: m.start()]):
                        problems.append(f"{name}: \"{sentence[:120]}\" ({claim})")
                        break
        if name in LIMITS and len(text) > LIMITS[name]:
            problems.append(f"{name}: {len(text)} characters, over Play's {LIMITS[name]}")
        if (name == "listing-title" or name.startswith("caption")) and PROMOTIONAL.search(text):
            problems.append(f"{name}: \"{text[:80]}\" has a promotional or ranking word (Play's metadata policy)")
        if (name == "listing-title" or name.startswith("caption")) and EMOJI.search(text):
            problems.append(f"{name}: has an emoji (Play's metadata policy)")
        if name in ("listing-title", "listing-short") and text.isupper():
            problems.append(f"{name}: in capitals (Play's metadata policy)")
        if PLACEHOLDER.search(text):
            problems.append(f"{name}: has something left to fill in: {PLACEHOLDER.search(text).group(0)}")
    for what, pattern in REQUIRED_IN_FULL:
        if "listing-full" in texts and not re.search(pattern, texts["listing-full"]):
            problems.append(f"listing-full does not say: {what}")
    return problems


def left_to_owner(markdown: str) -> list[str]:
    """What the document outside the blocks leaves to fill in (the owner's decisions): reported, not failed. The literal
    `‹…›` is the document talking about placeholders, not one (review F-4: a backticked placeholder with content is one)."""
    return [m.group(0) for m in PLACEHOLDER.finditer(markdown.replace("`‹…›`", ""))]


def main(argv: list[str]) -> int:
    path = argv[1] if len(argv) > 1 else "docs/m11/store-listing.md"
    with open(path, encoding="utf-8") as f:
        markdown = f.read()
    problems = check(markdown)
    for problem in problems:
        print(f"PROBLEM {problem}")
    if problems:
        print(f"::error title=Store listing::{'; '.join(p.replace(chr(10), ' ') for p in problems)[:1500]}")
        return 1
    for item in left_to_owner(markdown):
        print(f"::warning title=Store listing::left to the owner: {item}")
    found = blocks(markdown)
    owner = len(left_to_owner(markdown))
    print(
        f"OK: title {len(found['listing-title'])}/30, short {len(found['listing-short'])}/80, "
        f"full {len(found['listing-full'])}/4000, notes {len(found['listing-notes'])}/500 characters, "
        f"{len(captions(markdown))} captions; no forbidden claim, every limitation stated; "
        + (f"{owner} item(s) left to the owner outside the blocks" if owner else "nothing left to fill in")
    )
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
