package dev.tsdroid.data

/**
 * Process-wide cache of the persisted language tag.
 *
 * [android.app.Activity.attachBaseContext] runs before any composition and
 * needs the tag right now; reading DataStore there put disk I/O on the cold
 * start path of every Activity creation (cold start, rotation, recreate()).
 * The value is seeded on first use and updated whenever the setting changes,
 * so an activity recreate after switching languages still sees the new tag.
 */
object LanguageCache {

    /** Default when the preference has never been written or cannot be read. */
    const val DEFAULT = "zh"

    @Volatile
    private var cached: String? = null

    /** Cached tag, or null when this process has not read it yet. */
    fun get(): String? = cached

    fun put(tag: String) {
        if (tag.isNotBlank()) cached = tag
    }

    fun clear() {
        cached = null
    }
}
