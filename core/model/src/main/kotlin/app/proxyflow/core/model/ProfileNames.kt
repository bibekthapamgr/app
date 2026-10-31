package app.proxyflow.core.model

/** Names for copies of a profile ("Duplicate" in the profiles list), and name length limits. */
object ProfileNames {
    /** English names: "Office (copy)", then "Office (copy 2)", "Office (copy 3)"… */
    val englishCopyFormat: (base: String, number: Int) -> String = { base, number ->
        if (number == 1) "$base (copy)" else "$base (copy $number)"
    }

    /** Used when a (translated) pattern ignores the copy number, so it can't make a new name. */
    private val numberedFormat: (base: String, number: Int) -> String = { base, number -> "$base ($number)" }

    /**
     * The first name [format] produces for a copy of [displayName] that is not in [taken],
     * shortening the original part so the result fits [ProfileValidator.MAX_NAME_LENGTH].
     * Always ends: a pattern that ignores the number falls back to plain numbering.
     */
    fun copyOf(displayName: String, taken: Collection<String>, format: (base: String, number: Int) -> String = englishCopyFormat): String {
        val names = taken.toSet()
        val base = displayName.trim()
        // names.size + 1 different candidates always include one that is free.
        for (pattern in listOf(format, numberedFormat)) {
            for (number in 1..names.size + 1) {
                val candidate = fit(base, number, pattern)
                if (candidate !in names) return candidate
            }
        }
        return fit(base, names.size + 1, numberedFormat)
    }

    /** [text] cut to at most [max] UTF-16 units, never between the two halves of a character such as an emoji. */
    fun truncate(text: String, max: Int): String {
        if (text.length <= max) return text
        val end = if (max > 0 && text[max - 1].isHighSurrogate()) max - 1 else max
        return text.substring(0, end)
    }

    private fun fit(name: String, number: Int, format: (String, Int) -> String): String {
        var base = name
        while (format(base, number).length > ProfileValidator.MAX_NAME_LENGTH && base.isNotEmpty()) {
            base = dropLastCharacter(base).trimEnd()
        }
        return format(base, number)
    }

    private fun dropLastCharacter(text: String): String {
        val cut = if (text.length >= 2 && text.last().isLowSurrogate() && text[text.length - 2].isHighSurrogate()) 2 else 1
        return text.dropLast(cut)
    }
}
