#!/usr/bin/env python3
"""The public privacy-policy page Google Play links to (M11, owner decision D9: GitHub Pages), and the site's front
page, written from the app's own strings — the same source as the in-app policy and docs/m9/privacy-policy.md, so the
three cannot differ (scripts/tests/test_privacy_page.py checks the page's text against the document's).

    scripts/release/privacy_page.py --out site [--contact EMAIL] [--developer NAME] [--notice docs/m12/known-issue.md]
                                                                                       writes site/privacy.html, index.html

The notice (M12, docs/m12/incident-response.md §3): when the file named by --notice holds text, the front page shows
it in a box above everything else — the known-issue notice of an incident, published by pushing the file to main
(pages.yml passes docs/m12/known-issue.md; an empty file shows nothing). Plain paragraphs; no HTML is interpreted.

The contact (decision D10: the support address the listing shows) and the developer's name as Play Console shows it
are given at build time — pages.yml passes the repository variables PROXYFLOW_SUPPORT_EMAIL and PROXYFLOW_DEVELOPER_NAME
— and the page then names both, as Play's User Data policy asks of a linked policy (the entity, a point of contact);
without them the page says what the app says: the contact details on the Google Play page.

Published by .github/workflows/pages.yml at https://<owner>.github.io/<repository>/privacy.html once the owner turns
GitHub Pages on for the repository (Settings → Pages → Source: GitHub Actions). The page is one file: no script, no
fonts or images fetched from anywhere, no analytics — a page about a privacy policy collects nothing itself.
"""

from __future__ import annotations

import argparse
import html
import os
import re
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__)), ".."))
import privacy_policy  # noqa: E402

STYLE = """
:root { color-scheme: light dark; --fg: #14243a; --bg: #f6f8fb; --muted: #4b5563; --accent: #157a6d; }
@media (prefers-color-scheme: dark) { :root { --fg: #e6ebf2; --bg: #101820; --muted: #a9b4c2; --accent: #20b8a5; } }
body { margin: 0; background: var(--bg); color: var(--fg); font: 17px/1.55 system-ui, -apple-system, "Segoe UI", Roboto, sans-serif; }
main { max-width: 42rem; margin: 0 auto; padding: 2rem 1rem 4rem; }
h1 { font-size: 1.9rem; line-height: 1.2; margin: 0 0 .5rem; }
h2 { font-size: 1.25rem; margin: 2rem 0 .5rem; }
p { margin: 0 0 1rem; }
.muted { color: var(--muted); }
.notice { border: 2px solid var(--accent); border-radius: .5rem; padding: .75rem 1rem; margin: 1rem 0 1.5rem; }
.notice h2 { margin-top: 0; }
a { color: var(--accent); }
footer { margin-top: 3rem; font-size: .9rem; color: var(--muted); }
"""


def page(title: str, body: str, description: str) -> str:
    return (
        "<!doctype html>\n"
        '<html lang="en">\n<head>\n<meta charset="utf-8">\n'
        '<meta name="viewport" content="width=device-width, initial-scale=1">\n'
        f"<title>{html.escape(title)}</title>\n"
        f'<meta name="description" content="{html.escape(description)}">\n'
        f"<style>{STYLE}</style>\n</head>\n<body>\n<main>\n{body}</main>\n</body>\n</html>\n"
    )


def contact_html(contact: str | None, developer: str | None = None) -> str:
    who = html.escape(developer) if developer else "ProxyFlow's developer, as named on its Google Play page"
    if contact:
        return f'<p>Contact: <a href="mailto:{html.escape(contact)}">{html.escape(contact)}</a> ({who}).</p>\n'
    return f"<p>Contact: {who}, through the contact details on ProxyFlow's Google Play page.</p>\n"


def privacy_html(contact: str | None = None, developer: str | None = None) -> str:
    text = privacy_policy.strings()
    parts = [
        f"<h1>ProxyFlow — {html.escape(text['privacy_title'])}</h1>\n",
        f'<p class="muted"><em>{html.escape(text["privacy_applies"].replace("%1$s", privacy_policy.version()))}</em></p>\n',
        f"<p>{html.escape(text['privacy_summary'])}</p>\n",
    ]
    for title, paragraphs in privacy_policy.sections():
        parts.append(f"<h2>{html.escape(text[title])}</h2>\n")
        parts.extend(f"<p>{html.escape(text[p])}</p>\n" for p in paragraphs)
    parts.append(contact_html(contact, developer))
    parts.append('<footer><p>This page is the policy the app shows under About &amp; privacy → Privacy policy, published from the same text. <a href="./">ProxyFlow</a></p></footer>\n')
    return page("ProxyFlow — Privacy policy", "".join(parts), text["privacy_summary"])


def notice_html(notice: str | None) -> str:
    """The known-issue notice as a box: the first line its heading, each further paragraph a <p>; nothing when empty."""
    if not notice or not notice.strip():
        return ""
    paragraphs = [p.strip() for p in re.split(r"\n\s*\n", notice.strip()) if p.strip()]
    heading = paragraphs[0].lstrip("# ").strip()
    rest = "".join(f"<p>{html.escape(' '.join(p.split()))}</p>\n" for p in paragraphs[1:])
    return f'<aside class="notice" role="note">\n<h2>{html.escape(heading)}</h2>\n{rest}</aside>\n'


def index_html(contact: str | None = None, developer: str | None = None, notice: str | None = None) -> str:
    body = (
        "<h1>ProxyFlow</h1>\n"
        + notice_html(notice)
        + 
        "<p>ProxyFlow is a free Android app that sends your apps' TCP connections through an HTTP (CONNECT) or SOCKS5 proxy "
        "you already have. It has no account and no servers of its own, and does not sell or provide proxies. UDP and ping "
        "are blocked while connected, never sent around your proxy; your proxy provider can see which sites you open.</p>\n"
        '<p><a href="privacy.html">Privacy policy</a></p>\n'
        + contact_html(contact, developer).replace("Contact:", "Support:")
        + "<footer><p>Google Play and Android are trademarks of Google LLC.</p></footer>\n"
    )
    return page("ProxyFlow", body, "ProxyFlow: your apps' TCP connections, through a proxy you already have.")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", required=True)
    parser.add_argument("--contact", default=os.environ.get("PROXYFLOW_SUPPORT_EMAIL") or None)
    parser.add_argument("--developer", default=os.environ.get("PROXYFLOW_DEVELOPER_NAME") or None, help="the developer name as Play Console shows it")
    parser.add_argument("--notice", help="a text file whose content, when not empty, is shown as a notice on the front page")
    args = parser.parse_args(argv)
    notice = None
    if args.notice and os.path.isfile(args.notice):
        with open(args.notice, encoding="utf-8") as f:
            notice = f.read()
    if args.contact and ("@" not in args.contact or any(c in args.contact for c in "<>\"' ")):
        print(f"not an e-mail address: {args.contact!r}", file=sys.stderr)
        return 2
    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "privacy.html"), "w", encoding="utf-8") as f:
        f.write(privacy_html(args.contact, args.developer))
    with open(os.path.join(args.out, "index.html"), "w", encoding="utf-8") as f:
        f.write(index_html(args.contact, args.developer, notice))
    if notice and notice.strip():
        print(f"::notice title=Front page::the known-issue notice from {args.notice} is on the front page")
    if not args.contact:
        print("::warning title=Privacy page::no contact address: set the repository variable PROXYFLOW_SUPPORT_EMAIL (decision D10) so the page names one")
    with open(os.path.join(args.out, ".nojekyll"), "w", encoding="utf-8") as f:
        f.write("")
    print(f"wrote {args.out}/privacy.html and {args.out}/index.html")
    return 0


if __name__ == "__main__":
    sys.exit(main())
