package app.proxyflow.core.model

/**
 * [text] the user wrote (a profile's name), or one of ProxyFlow's own lines, set where the paragraph around it takes
 * another direction: Unicode's first-strong isolate (U+2068 … U+2069), so it keeps the direction of its first letter —
 * left to right when it has none — whatever the paragraph's, and leaves the paragraph's order alone. The text's own
 * directional formatting characters (embeddings, overrides, isolates and their ends) are left out, so it cannot end the
 * isolate early and turn the rest round (M10-94). In ProxyFlow's sentences (the design system's `isolated`), and in the
 * Quick Settings tile's subtitle, which Android 12 and later lay out in the phone's language's direction (M10-106).
 */
fun isolatedText(text: String): String = "⁨" + text.filterNot(::isDirectionalFormatting) + "⁩"

/** U+202A–U+202E (LRE, RLE, PDF, LRO, RLO) and U+2066–U+2069 (LRI, RLI, FSI, PDI). */
private fun isDirectionalFormatting(c: Char): Boolean = c in '‪'..'‮' || c in '⁦'..'⁩'
