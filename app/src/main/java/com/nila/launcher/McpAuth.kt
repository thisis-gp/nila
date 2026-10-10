package com.nila.launcher

import android.content.Context
import android.net.Uri
import android.util.Log
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import kotlin.coroutines.resume

/** One MCP food service's OAuth endpoints. Swiggy and Zomato use the same flow with different paths. */
internal data class McpProvider(
    val name: String,
    val base: String,
    val registerPath: String,
    val authorizePath: String,
    val tokenPath: String,
    val prefs: String,
    /** Used when the token response has no expires_in. */
    val defaultLifetimeSeconds: Long,
)

internal val SWIGGY = McpProvider("Swiggy", "https://mcp.swiggy.com", "/auth/register", "/auth/authorize", "/auth/token", "nila_swiggy", 432_000L)
internal val ZOMATO = McpProvider("Zomato", "https://mcp-server.zomato.com", "/register", "/authorize", "/token", "nila_zomato", 86_400L)

internal val SwiggyAuth = McpAuth(SWIGGY)
internal val ZomatoAuth = McpAuth(ZOMATO)

/**
 * Runs sign-ins in a scope that outlives the screen that started them. While the browser tab is open the
 * launcher's screen is torn down; a sign-in tied to it was cancelled before it listened for the redirect,
 * so Zomato's "Redirecting you back" reached a socket nobody accepted.
 */
internal object SignIns {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Bumped after every sign-in or sign-out, so screens re-read the stored sessions. */
    var revision by mutableIntStateOf(0)
        private set

    fun start(auth: McpAuth, context: Context, onFailed: () -> Unit) {
        scope.launch {
            if (auth.login(context) == null) onFailed()
            revision++
        }
    }

    fun signOut(auth: McpAuth, context: Context) {
        auth.logout(context)
        revision++
    }
}

/**
 * OAuth 2.1 + PKCE against an MCP auth server (Swiggy's first), using dynamic client
 * registration (RFC 7591) and an in-app loopback redirect — the documented
 * dev-mode path (mcp.swiggy.com/builders/docs/start/authenticate/) that works
 * today without waiting on builders@swiggy.in to approve a `nila://oauth`
 * custom scheme. Access tokens last 5 days and are never refreshed; once
 * expired, the full flow must run again.
 */
internal class McpAuth(private val provider: McpProvider) {
    private val http = OkHttpClient()

    internal data class Session(val accessToken: String, val expiresAtMillis: Long)

    private fun prefs(context: Context) = context.getSharedPreferences(provider.prefs, Context.MODE_PRIVATE)

    fun currentSession(context: Context): Session? {
        val p = prefs(context)
        val token = p.getString("access_token", null) ?: return null
        val expiresAt = p.getLong("expires_at", 0L)
        if (System.currentTimeMillis() >= expiresAt) return null
        return Session(token, expiresAt)
    }

    fun logout(context: Context) {
        prefs(context).edit().remove("access_token").remove("expires_at").apply()
    }

    /**
     * Runs the full login flow: register a client if needed, open the Swiggy
     * consent screen in a Custom Tab, catch the redirect on a local loopback
     * server, and exchange the code for a token. Returns null on failure or
     * a 5-minute timeout (user never completed the browser consent step).
     */
    suspend fun login(context: Context): Session? {
        // The loopback port is random per login, so the registered redirect_uri
        // must match this run's exact callback URI — register after opening the
        // socket, not once-and-cache, and always with the real redirectUri.
        val server = runCatching { ServerSocket(0) }.getOrNull() ?: return null
        trace(context, "start port=${server.localPort}")
        // Closed on every exit, cancellation included: a cancelled sign-in left this socket queueing redirects.
        // A foreground service while the tab is open: Android's cached-app freezer froze NILA behind the
        // browser, so Zomato's redirect queued on a socket whose thread could not run.
        SignInKeepAlive.start(context, provider.name)
        try {
            return loginWith(context, server)
        } finally {
            runCatching { server.close() }
            SignInKeepAlive.stop(context)
        }
    }

    private suspend fun loginWith(context: Context, server: ServerSocket): Session? {
        // 127.0.0.1, not "localhost": RFC 8252 §8.3 recommends the IP literal, and
        // Chrome's Private Network Access checks treat a public HTTPS page
        // redirecting to "localhost" more strictly than to the loopback literal —
        // login completed on Swiggy's side but hung forever on "Redirecting you
        // back to the application..." until this was the redirect target.
        val redirectUri = "http://127.0.0.1:${server.localPort}/callback"

        // registerClient/exchangeCode do blocking OkHttp calls — without this
        // they'd run on the caller's dispatcher (Main, from rememberCoroutineScope)
        // and throw NetworkOnMainThreadException, silently swallowed by runCatching.
        val clientId = withContext(Dispatchers.IO) { registerClient(redirectUri) }
        trace(context, "registered=${clientId != null}")
        if (clientId == null) {
            runCatching { server.close() }
            return null
        }
        val verifier = randomUrlSafe(64)
        val challenge = base64UrlSha256(verifier)
        val state = randomUrlSafe(16)

        val authorizeUri = Uri.parse(provider.base + provider.authorizePath).buildUpon()
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("code_challenge", challenge)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("state", state)
            .appendQueryParameter("scope", "mcp:tools")
            .build()

        CustomTabsIntent.Builder().build().launchUrl(context, authorizeUri)
        trace(context, "tab opened")

        // Phone number + OTP sign-in takes minutes: Zomato's login finished after the old 90s wait had
        // already closed the loopback socket, and the tab hung on "Redirecting you back to the application".
        val code = withTimeoutOrNull(LOGIN_WAIT_MS) { awaitCallback(server, state) }
        trace(context, "callback code=${code != null}")
        if (code == null) {
            runCatching { server.close() }
            return null
        }

        val tokenJson = withContext(Dispatchers.IO) { exchangeCode(clientId, code, verifier, redirectUri) }
        trace(context, "token=${tokenJson?.has("access_token")}")
        tokenJson ?: return null
        val accessToken = tokenJson.optString("access_token").ifBlank { return null }
        val expiresIn = tokenJson.optLong("expires_in", provider.defaultLifetimeSeconds)
        val expiresAt = System.currentTimeMillis() + expiresIn * 1000

        prefs(context).edit()
            .putString("access_token", accessToken)
            .putLong("expires_at", expiresAt)
            .apply()
        return Session(accessToken, expiresAt)
    }

    /**
     * Serves the loopback until a request carries the code. Chrome opens an empty "preconnect" socket before
     * the real redirect: reading only the first connection waited on that silent socket forever while the
     * redirect with the code sat unaccepted in the queue. Silent or code-less connections are answered and skipped.
     */
    private var appFiles: java.io.File? = null

    // vivo drops app logcat; each sign-in step goes to files/auth.txt instead. No codes or tokens are written.
    private fun trace(context: Context, step: String) {
        appFiles = context.filesDir
        runCatching { java.io.File(context.filesDir, "auth.txt").appendText("${provider.name} $step\n") }
    }

    private suspend fun awaitCallback(server: ServerSocket, expectedState: String): String? =
        suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { runCatching { server.close() } }
            Thread {
                runCatching { java.io.File(appFiles, "auth.txt").appendText("${provider.name} accept thread\n") }
                var code: String? = null
                while (code == null && !server.isClosed) {
                    val socket = runCatching { server.accept() }.getOrNull() ?: break
                    code = runCatching {
                        socket.soTimeout = SILENT_SOCKET_MS
                        val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                        val path = reader.readLine()?.split(" ")?.getOrNull(1).orEmpty()
                        val uri = Uri.parse("http://localhost$path")
                        val found = uri.getQueryParameter("code")?.takeIf { uri.getQueryParameter("state") == expectedState }
                        val body = if (found != null) "Signed in to NILA. You can close this tab." else "Waiting for sign-in…"
                        socket.getOutputStream().write("HTTP/1.1 200 OK\r\nContent-Type: text/html\r\nConnection: close\r\n\r\n<html><body>$body</body></html>".toByteArray())
                        socket.getOutputStream().flush()
                        found
                    }.getOrNull()
                    runCatching { socket.close() }
                }
                runCatching { server.close() }
                if (cont.isActive) cont.resume(code)
            }.start()
        }

    // Registers fresh on every login: Android's org.json.JSONObject.put(String, Any)
    // does NOT convert a Kotlin List to a JSONArray the way the reference org.json
    // does — it silently stores the list's toString(), so the server saw
    // redirect_uris: "[http://localhost]" (a string) instead of a real JSON array
    // and rejected the request. That 4xx was swallowed by `return null` before
    // ever reaching the onFailure handler. Also: the port is random per login, so
    // caching client_id against a stale redirect_uri would break the next login.
    private fun registerClient(redirectUri: String): String? {
        val body = JSONObject().apply {
            put("client_name", "NILA Launcher")
            put("redirect_uris", JSONArray().put(redirectUri))
            put("token_endpoint_auth_method", "none") // public client, PKCE-only
            put("grant_types", JSONArray().put("authorization_code"))
            put("response_types", JSONArray().put("code"))
        }
        val request = Request.Builder()
            .url(provider.base + provider.registerPath)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        return runCatching {
            http.newCall(request).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (!resp.isSuccessful) {
                    Log.e("NilaMcp", "${provider.name} register ${resp.code}: $text")
                    return null
                }
                JSONObject(text).optString("client_id").ifBlank { null }
            }
        }.onFailure { Log.e("NilaMcp", "${provider.name} register failed", it) }.getOrNull()
    }

    private fun exchangeCode(clientId: String, code: String, verifier: String, redirectUri: String): JSONObject? {
        val form = "grant_type=authorization_code" +
            "&code=${Uri.encode(code)}" +
            "&code_verifier=${Uri.encode(verifier)}" +
            "&redirect_uri=${Uri.encode(redirectUri)}" +
            "&client_id=${Uri.encode(clientId)}"
        val request = Request.Builder()
            .url(provider.base + provider.tokenPath)
            .post(form.toRequestBody("application/x-www-form-urlencoded".toMediaType()))
            .build()
        return runCatching {
            http.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return null
                JSONObject(resp.body?.string().orEmpty())
            }
        }.getOrNull()
    }

    private companion object {
        const val LOGIN_WAIT_MS = 5 * 60_000L
        const val SILENT_SOCKET_MS = 5_000
    }

    private fun randomUrlSafe(bytes: Int): String {
        val raw = ByteArray(bytes)
        SecureRandom().nextBytes(raw)
        return android.util.Base64.encodeToString(raw, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
    }

    private fun base64UrlSha256(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return android.util.Base64.encodeToString(digest, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
    }
}
