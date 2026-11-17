package dev.mark.lineover.core.ui.tools

/**
 * Finds tools by name, synonym ("erase object" → AI remove) or category. Pure and
 * allocation-light enough to run on every keystroke over the whole registry.
 */
object ToolSearch {
    /** Tools matching [query], best match first; empty for a blank query. */
    fun search(
        query: String,
        tools: List<ToolSpec> = ToolRegistry.all,
    ): List<ToolSpec> {
        val q = normalize(query)
        if (q.isEmpty()) return emptyList()
        return tools
            .mapIndexedNotNull { index, tool -> score(q, tool).takeIf { it > 0 }?.let { Triple(tool, it, index) } }
            .sortedWith(compareByDescending<Triple<ToolSpec, Int, Int>> { it.second }.thenBy { it.third })
            .map { it.first }
    }

    /** 0 = no match; higher is better. */
    fun score(
        normalizedQuery: String,
        tool: ToolSpec,
    ): Int {
        val name = normalize(tool.name)
        val synonyms = tool.synonyms.map(::normalize)
        val category = tool.category?.let { normalize(it.label) }.orEmpty()
        val queryWords = normalizedQuery.split(' ')
        val haystackWords = (name.split(' ') + synonyms.flatMap { it.split(' ') } + category.split(' ')).filter { it.isNotEmpty() }
        return when {
            name == normalizedQuery -> EXACT_NAME
            normalizedQuery in synonyms -> EXACT_SYNONYM
            name.startsWith(normalizedQuery) -> NAME_PREFIX
            name.split(' ').any { it.startsWith(normalizedQuery) } -> NAME_WORD_PREFIX
            synonyms.any { it.startsWith(normalizedQuery) } -> SYNONYM_PREFIX
            queryWords.all { word -> haystackWords.any { it.startsWith(word) } } -> ALL_WORDS
            category.isNotEmpty() && category.startsWith(normalizedQuery) -> CATEGORY
            else -> 0
        }
    }

    /** Lower case, "&" kept as a word, everything else that is not a letter or digit becomes a space. */
    fun normalize(text: String): String =
        text
            .lowercase()
            .replace("colour", "color")
            .replace("greyscale", "grayscale")
            .map { if (it.isLetterOrDigit() || it == '&') it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")

    private const val EXACT_NAME = 100
    private const val EXACT_SYNONYM = 90
    private const val NAME_PREFIX = 80
    private const val NAME_WORD_PREFIX = 70
    private const val SYNONYM_PREFIX = 60
    private const val ALL_WORDS = 40
    private const val CATEGORY = 20
}
