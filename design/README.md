# Design

| Path | What |
|---|---|
| `screens/*.dc.html`, `screens/canvas.json` | The 43 boards of "ProxyFlow V1 — Screens & Error States" (design canvas format; they render inside the canvas, which supplies `support.js`). Board index and states: `docs/m1/04-ux-screens-and-error-states.md` |
| `tokens/proxyflow.tokens.json` | Design tokens (W3C format): colours for light and dark, typography, spacing, radii, motion |
| `tokens/contrast-report.md` | WCAG 2.2 contrast check of every text/icon pair (0 failures) |
| `tools/check_contrast.py` | Regenerates the contrast report from the tokens: `python3 design/tools/check_contrast.py` |

The interactive canvas (clickable prototype) is linked from `docs/m1/M1-acceptance-report.md`. All artwork and icons are original to ProxyFlow.
