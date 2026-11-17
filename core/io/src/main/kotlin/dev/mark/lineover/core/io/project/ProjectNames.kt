package dev.mark.lineover.core.io.project

/** Naming rules for projects, free of Android so they can be unit-tested. */
object ProjectNames {
    const val MAX_LENGTH = 80
    private const val FALLBACK = "Untitled"
    private val COPY_SUFFIX = Regex(""" copy(?: (\d+))?$""")

    /** Project name for a file: its display name without the extension, trimmed. */
    fun fromDisplayName(displayName: String?): String {
        val base = displayName?.trim().orEmpty().substringBeforeLast('.', missingDelimiterValue = displayName?.trim().orEmpty())
        return clean(base)
    }

    /** Trims, collapses whitespace and limits the length; blank becomes "Untitled". */
    fun clean(name: String): String {
        val collapsed =
            name
                .trim()
                .replace(Regex("""\s+"""), " ")
                .take(MAX_LENGTH)
                .trim()
        return collapsed.ifEmpty { FALLBACK }
    }

    /** "Beach" -> "Beach copy" -> "Beach copy 2" -> "Beach copy 3". */
    fun copyOf(name: String): String {
        val match = COPY_SUFFIX.find(name) ?: return clean("$name copy")
        val next = (match.groupValues[1].toIntOrNull() ?: 1) + 1
        return clean(name.removeRange(match.range) + " copy $next")
    }

    fun isProjectFile(displayName: String?): Boolean = displayName?.endsWith(".loproj", ignoreCase = true) == true
}
