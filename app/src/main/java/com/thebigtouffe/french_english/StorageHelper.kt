package com.thebigtouffe.french_english

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray

object StorageHelper {

    private const val PREFS_NAME = "dictionary_prefs"
    private const val KEY_HISTORY = "history"
    private const val KEY_FAVORITES = "favorites"
    private const val MAX_HISTORY = 100

    private fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // --- History ---

    fun addToHistory(context: Context, word: String) {
        val list = getHistory(context).toMutableList()
        list.remove(word) // remove duplicate
        list.add(0, word) // add to front
        if (list.size > MAX_HISTORY) list.removeAt(list.size - 1)
        saveList(context, KEY_HISTORY, list)
    }

    fun getHistory(context: Context): List<String> =
        loadList(context, KEY_HISTORY)

    fun clearHistory(context: Context) =
        saveList(context, KEY_HISTORY, emptyList())

    // --- Favorites ---

    fun addFavorite(context: Context, word: String) {
        val list = getFavorites(context).toMutableList()
        if (!list.contains(word)) {
            list.add(0, word)
            saveList(context, KEY_FAVORITES, list)
        }
    }

    fun removeFavorite(context: Context, word: String) {
        val list = getFavorites(context).toMutableList()
        list.remove(word)
        saveList(context, KEY_FAVORITES, list)
    }

    fun isFavorite(context: Context, word: String): Boolean =
        getFavorites(context).contains(word)

    fun getFavorites(context: Context): List<String> =
        loadList(context, KEY_FAVORITES)

    // --- Internal ---

    private fun saveList(context: Context, key: String, list: List<String>) {
        val json = JSONArray(list).toString()
        prefs(context).edit().putString(key, json).apply()
    }

    private fun loadList(context: Context, key: String): List<String> {
        val json = prefs(context).getString(key, null) ?: return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (e: Exception) {
            emptyList()
        }
    }
}
