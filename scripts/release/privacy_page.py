#!/usr/bin/env python3
"""The public privacy-policy page Google Play links to (M11, owner decision D9: GitHub Pages), and the site's front
page, written from the app's own strings — the same source as the in-app policy and docs/m9/privacy-policy.md, so the
three cannot differ (scripts/tests/test_privacy_page.py checks the page's text against the document's).

    scripts/release/privacy_page.py --out site     writes site/privacy.html and site/index.html

Published by .github/workflows/pages.yml at https://<owner>.github.io/<repository>/privacy.html once the owner turns
GitHub Pages on for the repository (Settings → Pages → Source: GitHub Actions). The page is one file: no script, no
fonts or images fetched from anywhere, no analytics — a page about a privacy policy collects nothing itself.
"""

from __future__ import annotations

import argparse
import html
import os
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


def privacy_html() -> str:
    text = privacy_policy.strings()
    parts = [
        f"<h1>ProxyFlow — {html.escape(text['privacy_title'])}</h1>\n",
        f'<p class="muted"><em>{html.escape(text["privacy_applies"].replace("%1$s", privacy_policy.version()))}</em></p>\n',
        f"<p>{html.escape(text['privacy_summary'])}</p>\n",
    ]
    for title, paragraphs in privacy_policy.sections():
        parts.append(f"<h2>{html.escape(text[title])}</h2>\n")
        parts.extend(f"<p>{html.escape(text[p])}</p>\n" for p in paragraphs)
    parts.append('<footer><p>This page is the policy the app shows under About &amp; privacy → Privacy policy, published from the same text. <a href="./">ProxyFlow</a></p></footer>\n')
    return page("ProxyFlow — Privacy policy", "".join(parts), text["privacy_summary"])


def index_html() -> str:
    body = (
        "<h1>ProxyFlow</h1>\n"
        "<p>ProxyFlow is a free Android app that sends your apps' TCP connections through an HTTP (CONNECT) or SOCKS5 proxy "
        "you already have. It has no account and no servers of its own, and does not sell or provide proxies. UDP and ping "
        "are blocked while connected, never sent around your proxy; your proxy provider can see which sites you open.</p>\n"
        '<p><a href="privacy.html">Privacy policy</a></p>\n'
        "<p>Support: the contact details on ProxyFlow's Google Play page.</p>\n"
        "<footer><p>Google Play and Android are trademarks of Google LLC.</p></footer>\n"
    )
    return page("ProxyFlow", body, "ProxyFlow: your apps' TCP connections, through a proxy you already have.")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--out", required=True)
    args = parser.parse_args(argv)
    os.makedirs(args.out, exist_ok=True)
    with open(os.path.join(args.out, "privacy.html"), "w", encoding="utf-8") as f:
        f.write(privacy_html())
    with open(os.path.join(args.out, "index.html"), "w", encoding="utf-8") as f:
        f.write(index_html())
    with open(os.path.join(args.out, ".nojekyll"), "w", encoding="utf-8") as f:
        f.write("")
    print(f"wrote {args.out}/privacy.html and {args.out}/index.html")
    return 0


if __name__ == "__main__":
    sys.exit(main())
