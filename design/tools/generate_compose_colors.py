"""Generate the Compose colour file from the design tokens.

    python3 design/tools/generate_compose_colors.py

Writes core/designsystem/src/main/kotlin/app/proxyflow/core/designsystem/theme/Color.kt.
The tokens (design/tokens/proxyflow.tokens.json) are the single source of truth;
TokenColorsTest in :core:designsystem fails when the Kotlin file drifts from them,
and design/tools/check_contrast.py proves every required pair meets WCAG 2.2 AA.
"""
from __future__ import annotations

import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
TOKENS = ROOT / "design/tokens/proxyflow.tokens.json"
OUT = ROOT / "core/designsystem/src/main/kotlin/app/proxyflow/core/designsystem/theme/Color.kt"

# Material 3 ColorScheme parameters, in the order lightColorScheme() declares them.
M3_ROLES = [
    "primary", "onPrimary", "primaryContainer", "onPrimaryContainer", "inversePrimary",
    "secondary", "onSecondary", "secondaryContainer", "onSecondaryContainer",
    "tertiary", "onTertiary", "tertiaryContainer", "onTertiaryContainer",
    "background", "onBackground", "surface", "onSurface", "surfaceVariant", "onSurfaceVariant",
    "inverseSurface", "inverseOnSurface", "error", "onError", "errorContainer", "onErrorContainer",
    "outline", "outlineVariant", "scrim", "surfaceBright", "surfaceContainer", "surfaceContainerHigh",
    "surfaceContainerHighest", "surfaceContainerLow", "surfaceContainerLowest", "surfaceDim",
]
# ProxyFlow roles Material 3 has no slot for (warning, the teal accent fill, status colours).
EXTENDED_ROLES = [
    "warning", "onWarning", "warningContainer", "onWarningContainer", "accentFill", "onAccentFill",
    "statusConnected", "statusTransition", "statusOff", "statusError",
]


def resolve(tokens: dict, value: str) -> str:
    m = re.fullmatch(r"\{([\w.]+)\}", value)
    if not m:
        return value
    node = tokens
    for part in m.group(1).split("."):
        node = node[part]
    return resolve(tokens, node["$value"])


def argb(hex_rgb: str) -> str:
    return "0xFF" + hex_rgb.lstrip("#").upper()


def main() -> None:
    tokens = json.loads(TOKENS.read_text())
    lines = [
        "// GENERATED from design/tokens/proxyflow.tokens.json by design/tools/generate_compose_colors.py.",
        "// Do not edit by hand: change the tokens and run the script; TokenColorsTest checks this file.",
        "package app.proxyflow.core.designsystem.theme",
        "",
        "import androidx.compose.material3.darkColorScheme",
        "import androidx.compose.material3.lightColorScheme",
        "import androidx.compose.ui.graphics.Color",
        "",
    ]
    for mode, fn in (("light", "lightColorScheme"), ("dark", "darkColorScheme")):
        roles = tokens["color"][mode]
        lines.append(f"internal val {mode.capitalize()}ColorScheme =")
        lines.append(f"    {fn}(")
        for role in M3_ROLES:
            lines.append(f"        {role} = Color({argb(resolve(tokens, roles[role]['$value']))}),")
        lines.append("    )")
        lines.append("")
    for mode in ("light", "dark"):
        roles = tokens["color"][mode]
        lines.append(f"internal val {mode.capitalize()}ExtendedColors =")
        lines.append("    ProxyFlowExtendedColors(")
        for role in EXTENDED_ROLES:
            lines.append(f"        {role} = Color({argb(resolve(tokens, roles[role]['$value']))}),")
        lines.append("    )")
        lines.append("")
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text("\n".join(lines).rstrip() + "\n")
    print(f"wrote {OUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
