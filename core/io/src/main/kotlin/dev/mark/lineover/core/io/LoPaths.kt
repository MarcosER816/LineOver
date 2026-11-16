package dev.mark.lineover.core.io

/** Fixed file-system conventions of LineOver (rule R0 / D1). */
object LoPaths {
    /** Extension of LineOver project files, without the dot. */
    const val PROJECT_EXTENSION = "loproj"

    /** MediaStore relative path that exports are written to. */
    const val EXPORT_RELATIVE_PATH = "Pictures/LineOver"

    /** Returns [baseName] with the project extension, e.g. `holiday` -> `holiday.loproj`. */
    fun projectFileName(baseName: String): String {
        require(baseName.isNotBlank()) { "Project name must not be blank" }
        return "$baseName.$PROJECT_EXTENSION"
    }

    fun isProjectFile(fileName: String): Boolean = fileName.endsWith(".$PROJECT_EXTENSION", ignoreCase = true)
}
