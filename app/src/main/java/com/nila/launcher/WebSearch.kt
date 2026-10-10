package com.nila.launcher

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject

data class WebResult(val title: String, val url: String, val snippet: String)

/**
 * Stores the user's own Tavily API key (tavily.com/dashboard, self-serve signup, free tier).
 * Tavily auths with a static bearer key, not OAuth, so there is no connect flow beyond pasting it.
 */
class TavilyAuth(context: Context) {
    private val appContext = context.applicationContext

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            appContext,
            "tavily_auth",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var apiKey: String?
        get() = prefs.getString("api_key", null)
        set(value) = prefs.edit().putString("api_key", value).apply()

    val isConnected: Boolean get() = !apiKey.isNullOrBlank()
}

/** Confirmed 2026-10-10 against Tavily's published API reference: Bearer auth, results[].{title,url,content}. */
object TavilySearch {
    private const val URL = "https://api.tavily.com/search"

    data class Answer(val summary: String?, val items: List<WebResult>)

    fun search(apiKey: String, query: String, maxResults: Int = 5): Answer {
        val body = JSONObject()
            .put("query", query)
            .put("max_results", maxResults)
            .put("include_answer", true)
        val response = JSONObject(httpPost(URL, body.toString(), "application/json", apiKey))
        val results = response.optJSONArray("results") ?: JSONArray()
        val items = (0 until results.length()).map { index ->
            val result = results.getJSONObject(index)
            WebResult(
                title = result.optString("title", "Result"),
                url = result.optString("url", ""),
                snippet = result.optString("content", ""),
            )
        }
        return Answer(response.optString("answer").takeIf { it.isNotBlank() }, items)
    }
}

private val WEB_SEARCH_TRIGGERS = listOf("search for ", "search the web for ", "web search ", "look up ", "google ")

/** Strips a leading web-search phrase and returns the query, or null if the prompt doesn't start with one. */
fun extractWebSearchQuery(prompt: String): String? {
    val lower = prompt.lowercase()
    val trigger = WEB_SEARCH_TRIGGERS.firstOrNull { lower.startsWith(it) } ?: return null
    return prompt.substring(trigger.length).trim().takeIf { it.isNotBlank() }
}
