package dev.mark.lineover.core.io.prefs

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Colour around the photo on the canvas (Settings → Canvas surround, DESIGN_SYSTEM `canvasSurround`). */
enum class CanvasSurround(
    val id: String,
) {
    Dark("dark"),
    MidGrey("mid_grey"),
    ;

    companion object {
        fun fromId(id: String?): CanvasSurround = entries.firstOrNull { it.id == id } ?: Dark
    }
}

/** Small UI preferences that survive restarts. Reads are cheap; writes apply asynchronously. */
interface AppPreferences {
    /** Last category opened in the editor (a `ToolCategory.id`), restored on the next open. */
    val lastCategory: StateFlow<String?>

    /** True once the editor's coach marks were finished or skipped ("dismissible forever"). */
    val coachMarksSeen: StateFlow<Boolean>

    /** Tool ids pinned to the Favorites category, in the order they were added. */
    val favorites: StateFlow<List<String>>

    val surround: StateFlow<CanvasSurround>

    fun setLastCategory(id: String)

    fun setCoachMarksSeen(seen: Boolean)

    fun setFavorites(ids: List<String>)

    fun setSurround(surround: CanvasSurround)
}

/** [AppPreferences] in a SharedPreferences file (`lineover_ui`). */
class SharedAppPreferences(
    context: Context,
) : AppPreferences {
    private val prefs: SharedPreferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    private val lastCategoryFlow = MutableStateFlow(prefs.getString(KEY_CATEGORY, null))
    private val coachFlow = MutableStateFlow(prefs.getBoolean(KEY_COACH, false))
    private val favoritesFlow = MutableStateFlow(decode(prefs.getString(KEY_FAVORITES, null)))
    private val surroundFlow = MutableStateFlow(CanvasSurround.fromId(prefs.getString(KEY_SURROUND, null)))

    override val lastCategory = lastCategoryFlow.asStateFlow()
    override val coachMarksSeen = coachFlow.asStateFlow()
    override val favorites = favoritesFlow.asStateFlow()
    override val surround = surroundFlow.asStateFlow()

    override fun setLastCategory(id: String) {
        lastCategoryFlow.value = id
        prefs.edit().putString(KEY_CATEGORY, id).apply()
    }

    override fun setCoachMarksSeen(seen: Boolean) {
        coachFlow.value = seen
        prefs.edit().putBoolean(KEY_COACH, seen).apply()
    }

    override fun setFavorites(ids: List<String>) {
        favoritesFlow.value = ids
        prefs.edit().putString(KEY_FAVORITES, ids.joinToString(SEPARATOR)).apply()
    }

    override fun setSurround(surround: CanvasSurround) {
        surroundFlow.value = surround
        prefs.edit().putString(KEY_SURROUND, surround.id).apply()
    }

    private companion object {
        const val FILE = "lineover_ui"
        const val KEY_CATEGORY = "editor.last_category"
        const val KEY_COACH = "editor.coach_marks_seen"
        const val KEY_FAVORITES = "editor.favorites"
        const val KEY_SURROUND = "canvas.surround"
        const val SEPARATOR = ","

        fun decode(value: String?): List<String> = value?.split(SEPARATOR)?.filter { it.isNotBlank() }.orEmpty()
    }
}

/** [AppPreferences] held in memory: previews, screenshot tests and unit tests. */
class InMemoryAppPreferences(
    lastCategory: String? = null,
    coachMarksSeen: Boolean = true,
    favorites: List<String> = emptyList(),
    surround: CanvasSurround = CanvasSurround.Dark,
) : AppPreferences {
    override val lastCategory = MutableStateFlow(lastCategory)
    override val coachMarksSeen = MutableStateFlow(coachMarksSeen)
    override val favorites = MutableStateFlow(favorites)
    override val surround = MutableStateFlow(surround)

    override fun setLastCategory(id: String) {
        lastCategory.value = id
    }

    override fun setCoachMarksSeen(seen: Boolean) {
        coachMarksSeen.value = seen
    }

    override fun setFavorites(ids: List<String>) {
        favorites.value = ids
    }

    override fun setSurround(surround: CanvasSurround) {
        this.surround.value = surround
    }
}
