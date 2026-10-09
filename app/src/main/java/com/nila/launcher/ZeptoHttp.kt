package com.nila.launcher

import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

internal data class HttpResult(val body: String, val headers: Map<String, String>)

/** One shared POST helper for Zepto's OAuth and MCP endpoints. No JSON/HTTP library added: HttpURLConnection and org.json already ship with Android. */
internal fun httpPostRaw(
    url: String,
    body: String,
    contentType: String,
    bearer: String? = null,
    extraHeaders: Map<String, String> = emptyMap(),
): HttpResult {
    val connection = URL(url).openConnection() as HttpURLConnection
    connection.requestMethod = "POST"
    connection.doOutput = true
    connection.connectTimeout = 15_000
    connection.readTimeout = 15_000
    connection.setRequestProperty("Content-Type", contentType)
    connection.setRequestProperty("Accept", "application/json, text/event-stream")
    bearer?.let { connection.setRequestProperty("Authorization", "Bearer $it") }
    extraHeaders.forEach { (key, value) -> connection.setRequestProperty(key, value) }

    connection.outputStream.use { stream ->
        OutputStreamWriter(stream, Charsets.UTF_8).use { it.write(body) }
    }

    val ok = connection.responseCode in 200..299
    val text = (if (ok) connection.inputStream else connection.errorStream)
        .bufferedReader(Charsets.UTF_8).use { it.readText() }
    check(ok) { "Zepto request to $url failed: HTTP ${connection.responseCode} $text" }
    val headers = connection.headerFields.mapNotNull { (key, values) -> key?.let { it to values.firstOrNull().orEmpty() } }.toMap()
    return HttpResult(text, headers)
}

internal fun httpPost(url: String, body: String, contentType: String, bearer: String? = null): String =
    httpPostRaw(url, body, contentType, bearer).body

internal fun formEncode(fields: Map<String, String>): String =
    fields.entries.joinToString("&") { (key, value) ->
        "${URLEncoder.encode(key, "UTF-8")}=${URLEncoder.encode(value, "UTF-8")}"
    }
