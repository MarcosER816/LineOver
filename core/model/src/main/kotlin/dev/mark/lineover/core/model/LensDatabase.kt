package dev.mark.lineover.core.model

/** A lens mount and the mounts whose lenses fit it. */
data class LensMount(
    val name: String,
    val compat: List<String> = emptyList(),
)

/** A camera body: its sensor's crop factor and mount. */
data class LensCamera(
    val maker: String,
    val model: String,
    val mount: String,
    val crop: Double,
)

/** One calibration entry of a lens (distortion, TCA or vignetting) with its terms by name. */
data class LensCalibration(
    val model: String,
    val focal: Double,
    val aperture: Double? = null,
    val distance: Double? = null,
    val terms: Map<String, Double> = emptyMap(),
)

/** A lens of the database and its calibrations (made on a sensor of [crop] and [aspect]). */
data class LensEntry(
    val maker: String,
    val model: String,
    val mounts: List<String>,
    val crop: Double,
    val aspect: Double = DEFAULT_ASPECT,
    val distortion: List<LensCalibration> = emptyList(),
    val tca: List<LensCalibration> = emptyList(),
    val vignetting: List<LensCalibration> = emptyList(),
) {
    val name: String get() = if (model.startsWith(maker, ignoreCase = true)) model else "$maker $model"

    /** Shortest and longest calibrated focal length. */
    val focalRange: ClosedFloatingPointRange<Double>
        get() {
            val all = (distortion + tca + vignetting).map { it.focal }
            return if (all.isEmpty()) 0.0..0.0 else all.min()..all.max()
        }

    val hasCorrections: Boolean get() = distortion.isNotEmpty() || tca.isNotEmpty() || vignetting.isNotEmpty()

    companion object {
        /** Lensfun's default calibration aspect ratio (3:2). */
        const val DEFAULT_ASPECT = 1.5
    }
}

/**
 * The Lensfun lens database (format version 2, CC BY-SA 3.0, Stage 9), read from its XML
 * files with [LensXml]. Only what LineOver uses is kept: mounts, cameras, and lenses with
 * their distortion, TCA and vignetting calibrations.
 */
class LensDatabase(
    val mounts: List<LensMount>,
    val cameras: List<LensCamera>,
    val lenses: List<LensEntry>,
) {
    /** Mounts whose lenses fit [mount] (itself and its compatible mounts). */
    fun fitting(mount: String): Set<String> = setOf(mount) + mounts.filter { it.name == mount }.flatMap { it.compat }

    companion object {
        fun parse(files: List<String>): LensDatabase {
            val mounts = ArrayList<LensMount>()
            val cameras = ArrayList<LensCamera>()
            val lenses = ArrayList<LensEntry>()
            for (e in files.flatMap { LensXml.parse(it).children }) {
                when (e.name) {
                    "mount" -> mounts += LensMount(e.text("name").orEmpty(), e.all("compat").map { it.content })
                    "camera" -> camera(e)?.let { cameras += it }
                    "lens" -> lens(e)?.let { lenses += it }
                }
            }
            return LensDatabase(mounts, cameras, lenses)
        }

        private fun camera(e: LensXml.Element): LensCamera? {
            val crop = e.text("cropfactor")?.toDoubleOrNull() ?: return null
            return LensCamera(e.text("maker").orEmpty(), e.text("model") ?: return null, e.text("mount").orEmpty(), crop)
        }

        private fun lens(e: LensXml.Element): LensEntry? {
            val crop = e.text("cropfactor")?.toDoubleOrNull() ?: return null
            val calibration = e.first("calibration")

            fun entries(tag: String) =
                calibration
                    ?.all(tag)
                    ?.mapNotNull { c ->
                        val focal = c.attributes["focal"]?.toDoubleOrNull() ?: return@mapNotNull null
                        val known = setOf("model", "focal", "aperture", "distance")
                        LensCalibration(
                            model = c.attributes["model"].orEmpty(),
                            focal = focal,
                            aperture = c.attributes["aperture"]?.toDoubleOrNull(),
                            distance = c.attributes["distance"]?.toDoubleOrNull(),
                            terms =
                                c.attributes
                                    .filterKeys { it !in known }
                                    .mapNotNull { (k, v) ->
                                        v.toDoubleOrNull()?.let { k to it }
                                    }.toMap(),
                        )
                    }.orEmpty()
            return LensEntry(
                maker = e.text("maker").orEmpty(),
                model = e.text("model") ?: return null,
                mounts = e.all("mount").map { it.content },
                crop = crop,
                aspect = aspect(e.text("aspect-ratio")),
                distortion = entries("distortion"),
                tca = entries("tca"),
                vignetting = entries("vignetting"),
            )
        }

        /** "4:3" or "1.333"; Lensfun's 3:2 when absent or unreadable. */
        private fun aspect(text: String?): Double {
            val parts = text.orEmpty().split(":").map { it.trim().toDoubleOrNull() }
            val ratio =
                when {
                    parts.size == 2 && parts[0] != null && parts[1] != null -> requireNotNull(parts[0]) / requireNotNull(parts[1])
                    parts.size == 1 -> parts[0]
                    else -> null
                }
            return ratio?.takeIf { it > 0 && it.isFinite() } ?: LensEntry.DEFAULT_ASPECT
        }
    }
}

/**
 * A small XML reader for the lens database: elements, attributes, text, entities; the
 * prolog, DOCTYPE and comments are skipped. Not a general XML parser (no CDATA or
 * namespaces, which the database does not use).
 */
object LensXml {
    class Element(
        val name: String,
        val attributes: Map<String, String>,
    ) {
        val children = ArrayList<Element>()
        var content: String = ""
            internal set

        fun first(tag: String): Element? = children.firstOrNull { it.name == tag && "lang" !in it.attributes }

        fun all(tag: String): List<Element> = children.filter { it.name == tag }

        /** The text of the first [tag] child without a `lang` attribute (the database's own name). */
        fun text(tag: String): String? = first(tag)?.content?.trim()
    }

    fun parse(text: String): Element {
        val root = Element("#document", emptyMap())
        val stack = ArrayDeque<Element>().apply { addLast(root) }
        val content = StringBuilder()
        var i = 0
        while (i < text.length) {
            val lt = text.indexOf('<', i)
            if (lt < 0) break
            content.append(text, i, lt)
            when {
                text.startsWith("<!--", lt) -> {
                    i = end(text, "-->", lt)
                }

                text.startsWith("<?", lt) -> {
                    i = end(text, "?>", lt)
                }

                text.startsWith("<!", lt) -> {
                    i = end(text, ">", lt)
                }

                text.startsWith("</", lt) -> {
                    val gt = text.indexOf('>', lt)
                    require(gt > 0) { "unterminated end tag" }
                    val closed = stack.removeLast()
                    closed.content = decode(content.toString())
                    content.clear()
                    require(stack.isNotEmpty()) { "unbalanced </${closed.name}>" }
                    i = gt + 1
                }

                else -> {
                    val gt = tagEnd(text, lt)
                    val selfClosing = text[gt - 1] == '/'
                    val body = text.substring(lt + 1, if (selfClosing) gt - 1 else gt)
                    val element = element(body)
                    stack.last().children += element
                    content.clear()
                    if (!selfClosing) stack.addLast(element)
                    i = gt + 1
                }
            }
        }
        return root.children.firstOrNull() ?: root
    }

    private fun end(
        text: String,
        marker: String,
        from: Int,
    ): Int {
        val at = text.indexOf(marker, from)
        require(at >= 0) { "unterminated $marker" }
        return at + marker.length
    }

    /** The '>' closing the tag at [from], skipping quoted attribute values. */
    private fun tagEnd(
        text: String,
        from: Int,
    ): Int {
        var quote = 0.toChar()
        for (k in from + 1 until text.length) {
            val c = text[k]
            when {
                quote != 0.toChar() -> if (c == quote) quote = 0.toChar()
                c == '"' || c == '\'' -> quote = c
                c == '>' -> return k
            }
        }
        throw IllegalArgumentException("unterminated tag")
    }

    private val ATTRIBUTE = Regex("""([\w:.-]+)\s*=\s*("([^"]*)"|'([^']*)')""")

    @Suppress("MagicNumber") // regex groups: name, double- or single-quoted value
    private fun element(body: String): Element {
        val name = body.takeWhile { !it.isWhitespace() }
        val attributes =
            ATTRIBUTE.findAll(body.substring(name.length)).associate { m ->
                m.groupValues[1] to decode(m.groupValues[3].ifEmpty { m.groupValues[4] })
            }
        return Element(name, attributes)
    }

    private fun decode(s: String): String =
        if ('&' !in s) {
            s
        } else {
            s
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"")
                .replace("&apos;", "'")
                .replace("&amp;", "&")
        }
}
