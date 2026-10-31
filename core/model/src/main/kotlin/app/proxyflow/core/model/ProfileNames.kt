package app.proxyflow.core.model

/** Names for copies of a profile ("Duplicate" in the profiles list). */
object ProfileNames {
    /** English names: "Office (copy)", then "Office (copy 2)", "Office (copy 3)"… */
    val englishCopyFormat: (base: String, number: Int) -> String = { base, number ->
        if (number == 1) "$base (copy)" else "$base (copy $number)"
    }

    /**
     * The first name [format] produces for a copy of [displayName] that is not in [taken],
     * shortening the original part so the result fits [ProfileValidator.MAX_NAME_LENGTH].
     */
    fun copyOf(displayName: String, taken: Collection<String>, format: (base: String, number: Int) -> String = englishCopyFormat): String {
        val names = taken.toSet()
        var number = 1
        while (true) {
            var base = displayName.trim()
            while (format(base, number).length > ProfileValidator.MAX_NAME_LENGTH && base.isNotEmpty()) {
                base = base.dropLast(1).trimEnd()
            }
            val candidate = format(base, number)
            if (candidate !in names) return candidate
            number++
        }
    }
}
