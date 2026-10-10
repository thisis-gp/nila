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

    fun disconnect() = prefs.edit().remove("api_key").apply()
}

/** A pasted Tavily key, trimmed; null when it is not one, so a typo never shows as "connected". */
fun tavilyKey(pasted: String): String? = pasted.trim().takeIf { Regex("""tvly-[A-Za-z0-9_-]{8,}""").matches(it) }

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

/**
 * Web answers with no key: DuckDuckGo's free Instant Answer API (abstract + related links). It has no full
 * results page, so the caller always adds "open in browser". Tavily is used instead once the user adds a key.
 */
object InstantAnswer {
    private const val URL = "https://api.duckduckgo.com/"
    private const val MAX_RELATED = 4
    private val http = okhttp3.OkHttpClient()

    fun search(query: String): TavilySearch.Answer {
        val url = "$URL?format=json&no_html=1&skip_disambig=1&q=" + java.net.URLEncoder.encode(query, "UTF-8")
        val body = http.newCall(okhttp3.Request.Builder().url(url).header("User-Agent", "NILA-Launcher/0.1").build()).execute()
            .use { it.body?.string().orEmpty() }
        val json = JSONObject(body)
        val related = json.optJSONArray("RelatedTopics") ?: JSONArray()
        val items = (0 until related.length()).mapNotNull { i ->
            val topic = related.optJSONObject(i) ?: return@mapNotNull null
            val text = topic.optString("Text").ifBlank { return@mapNotNull null }
            WebResult(text.substringBefore(" - ").take(80), topic.optString("FirstURL"), text)
        }.take(MAX_RELATED)
        val abstract = json.optString("AbstractText").ifBlank { null }
        val source = json.optString("AbstractURL").ifBlank { null }
        val lead = if (abstract != null && source != null) listOf(WebResult(json.optString("Heading").ifBlank { query }, source, abstract)) else emptyList()
        return TavilySearch.Answer(abstract, lead + items)
    }
}
