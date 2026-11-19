package dev.mark.lineover.core.model

/**
 * A parsed .cube 3D LUT (Stage 11): [size]^3 RGB triples in [table], red changing fastest,
 * then green, then blue; the input [domainMin] .. [domainMax] per channel.
 */
class CubeLut(
    val size: Int,
    val table: FloatArray,
    val domainMin: FloatArray = floatArrayOf(0f, 0f, 0f),
    val domainMax: FloatArray = floatArrayOf(1f, 1f, 1f),
    val title: String = "",
) {
    /** The domain as the engine takes it: min r, g, b then max r, g, b. */
    val domain: FloatArray get() = domainMin + domainMax
}

/** A .cube file that cannot be used, with a message for the person importing it. */
class CubeFormatException(
    message: String,
) : Exception(message)

/**
 * The .cube parser (Adobe / Resolve): `LUT_3D_SIZE N` (2..65), optional `TITLE`,
 * `DOMAIN_MIN` / `DOMAIN_MAX` (or `LUT_3D_INPUT_RANGE`), `#` comments, then N^3 lines
 * "r g b". Exactly tools/golden/lut_reference.parse_cube, messages included.
 */
object CubeParser {
    const val MIN_SIZE = 2
    const val MAX_SIZE = 65

    @Suppress("CyclomaticComplexMethod", "LongMethod", "ThrowsCount")
    fun parse(text: String): CubeLut {
        var size: Int? = null
        var dmin = floatArrayOf(0f, 0f, 0f)
        var dmax = floatArrayOf(1f, 1f, 1f)
        var title = ""
        val seen = HashSet<String>()
        var rows = 0
        var table = FloatArray(0)
        text.lineSequence().forEachIndexed { index, raw ->
            val number = index + 1
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEachIndexed
            val word = line.substringBefore(' ').substringBefore('\t')
            if (number(word) == null) {
                val key = word.uppercase()
                if (rows > 0) fail("line $number: $key after the table")
                if (!seen.add(key)) fail("line $number: $key twice")
                val rest = line.substring(word.length).trim()
                when (key) {
                    "TITLE" -> {
                        title = rest.trim('"')
                    }

                    "LUT_1D_SIZE" -> {
                        fail("line $number: 1D LUTs are not supported")
                    }

                    "LUT_3D_SIZE" -> {
                        val n = rest.toIntOrNull() ?: fail("line $number: LUT_3D_SIZE is not a whole number")
                        if (n !in MIN_SIZE..MAX_SIZE) fail("line $number: LUT_3D_SIZE $n is outside $MIN_SIZE..$MAX_SIZE")
                        size = n
                        table = FloatArray(n * n * n * CHANNELS)
                    }

                    "DOMAIN_MIN" -> {
                        dmin = numbers(rest, number, key)
                    }

                    "DOMAIN_MAX" -> {
                        dmax = numbers(rest, number, key)
                    }

                    "LUT_1D_INPUT_RANGE", "LUT_3D_INPUT_RANGE" -> {
                        val (lo, hi) = numbers(rest, number, key, 2).let { it[0] to it[1] }
                        dmin = floatArrayOf(lo, lo, lo)
                        dmax = floatArrayOf(hi, hi, hi)
                    }

                    else -> {
                        fail("line $number: unknown keyword $key")
                    }
                }
                return@forEachIndexed
            }
            val n = size ?: fail("line $number: table data before LUT_3D_SIZE")
            val values = numbers(line, number, "table")
            if (rows < n * n * n) values.copyInto(table, rows * CHANNELS)
            rows++
        }
        val n = size ?: fail("no LUT_3D_SIZE")
        if (rows != n * n * n) fail("$rows table rows, LUT_3D_SIZE $n needs ${n * n * n}")
        if ((0 until CHANNELS).any { dmax[it] <= dmin[it] }) fail("DOMAIN_MAX must be above DOMAIN_MIN")
        return CubeLut(n, table, dmin, dmax, title)
    }

    /** A .cube text of [lut] (6 decimals), as lut_reference.write_cube. */
    fun write(lut: CubeLut): String =
        buildString {
            if (lut.title.isNotEmpty()) append("TITLE \"").append(lut.title).append("\"\n")
            append("LUT_3D_SIZE ").append(lut.size).append('\n')
            if (lut.domainMin.any { it != 0f } || lut.domainMax.any { it != 1f }) {
                append("DOMAIN_MIN ").append(lut.domainMin.joinToString(" ") { fixed(it) }).append('\n')
                append("DOMAIN_MAX ").append(lut.domainMax.joinToString(" ") { fixed(it) }).append('\n')
            }
            for (i in 0 until lut.size * lut.size * lut.size) {
                append(fixed(lut.table[i * CHANNELS])).append(' ')
                append(fixed(lut.table[i * CHANNELS + 1])).append(' ')
                append(fixed(lut.table[i * CHANNELS + 2])).append('\n')
            }
        }

    private fun fixed(v: Float) = "%.6f".format(java.util.Locale.ROOT, v)

    private fun numbers(
        text: String,
        line: Int,
        what: String,
        count: Int = CHANNELS,
    ): FloatArray {
        val parts = text.split(' ', '\t').filter { it.isNotEmpty() }
        if (parts.size != count) fail("line $line: $what needs $count numbers")
        val values = parts.map { number(it) ?: fail("line $line: $what has a value that is not a number") }
        if (values.any { !it.isFinite() }) fail("line $line: $what has a value that is not finite")
        return FloatArray(count) { values[it].toFloat() }
    }

    /** A number as Python's float() reads it (nan / inf in any case included). */
    private fun number(word: String): Double? =
        when (word.lowercase()) {
            "nan", "+nan", "-nan" -> Double.NaN
            "inf", "+inf", "infinity", "+infinity" -> Double.POSITIVE_INFINITY
            "-inf", "-infinity" -> Double.NEGATIVE_INFINITY
            else -> word.toDoubleOrNull()
        }

    private fun fail(message: String): Nothing = throw CubeFormatException(message)

    private const val CHANNELS = 3
}
