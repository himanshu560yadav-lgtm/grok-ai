package com.panda.ai.utilities

import android.content.Context
import android.content.SharedPreferences

object ApiKeyManager {
    private const val PREFS_NAME = "honey_prefs"
    private const val KEY_API = "gemini_api_key" // kept as-is so old saved keys still load
    private const val KEY_SELECTED_MODEL = "selected_model_id"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun saveApiKey(context: Context, apiKey: String) {
        getPrefs(context).edit().putString(KEY_API, apiKey).apply()
    }

    fun getApiKey(context: Context): String {
        return getPrefs(context).getString(KEY_API, "") ?: ""
    }

    fun hasApiKey(context: Context): Boolean {
        return getApiKey(context).isNotEmpty()
    }

    fun clearApiKey(context: Context) {
        getPrefs(context).edit().remove(KEY_API).remove(KEY_SELECTED_MODEL).apply()
    }

    fun saveSelectedModel(context: Context, modelId: String) {
        getPrefs(context).edit().putString(KEY_SELECTED_MODEL, modelId).apply()
    }

    fun getSelectedModel(context: Context): String {
        return getPrefs(context).getString(KEY_SELECTED_MODEL, "") ?: ""
    }

    fun hasSelectedModel(context: Context): Boolean {
        return getSelectedModel(context).isNotEmpty()
    }
}
