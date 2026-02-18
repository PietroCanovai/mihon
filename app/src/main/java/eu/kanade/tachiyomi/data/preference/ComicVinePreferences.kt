package eu.kanade.tachiyomi.data.preference

import android.content.Context
import androidx.core.content.edit

class ComicVinePreferences(context: Context) {

    private val prefs = context.getSharedPreferences("comicvine_prefs", Context.MODE_PRIVATE)

    fun getApiKey(): String {
        return prefs.getString(KEY_API_KEY, "") ?: ""
    }

    fun setApiKey(key: String) {
        prefs.edit { putString(KEY_API_KEY, key) }
    }

    fun getCachedVolumeId(mangaId: Long): Int? {
        val value = prefs.getInt("volume_$mangaId", -1)
        return if (value == -1) null else value
    }

    fun setCachedVolumeId(mangaId: Long, volumeId: Int) {
        prefs.edit { putInt("volume_$mangaId", volumeId) }
    }

    fun clearVolumeCache() {
        val keys = prefs.all.keys.filter { it.startsWith("volume_") }
        prefs.edit { keys.forEach { remove(it) } }
    }

    companion object {
        private const val KEY_API_KEY = "comicvine_api_key"
    }
}
