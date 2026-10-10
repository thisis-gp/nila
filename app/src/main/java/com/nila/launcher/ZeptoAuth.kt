package com.nila.launcher

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

object ZeptoConfig {
    const val MCP_URL = "https://mcp.zepto.co.in/mcp"
    const val AUTH_SERVER = "https://auth.zepto.co.in"
    const val SCOPES = "tools:read dev.ucp.shopping.catalog.search:read dev.ucp.common.location.search:read"
}

/**
 * OAuth 2.1 + PKCE client for Zepto's MCP, using a loopback redirect (RFC 8252) on
 * http://localhost:<ephemeral port>/callback. Confirmed 2026-10-09: Zepto's self-serve
 * registration rejects any other redirect URI (including an Android custom scheme) with
 * invalid_redirect_uri and requires a written GitHub-issue allowlist for those. Loopback on
 * "localhost" is accepted immediately, no approval needed.
 *
 * Unverified past the token exchange: nobody has completed a real OTP login against this yet.
 */
class ZeptoAuth(context: Context) {
    private val appContext = context.applicationContext

    private val prefs by lazy {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            appContext,
            "zepto_auth",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    val accessToken: String? get() = prefs.getString("access_token", null)
    val isConnected: Boolean get() = accessToken != null

    fun signOut() = prefs.edit().clear().apply()

    /**
     * Blocking: registers a fresh client, opens the browser for the user's own Zepto login,
     * waits on a local loopback socket for the redirect, then exchanges the code for a token.
     * Call from a background thread, never from the main/Compose thread.
     */
    fun connect(launchContext: Context): Result<Unit> = runCatching {
        // Same fixes as McpAuth: a foreground service keeps NILA out of Android's app freezer while
        // the browser covers it, so the loopback below can still answer the redirect.
        SignInKeepAlive.start(launchContext, "Zepto")
        try {
            signIn(launchContext)
        } finally {
            SignInKeepAlive.stop(launchContext)
        }
        // Back to NILA in front of the browser once signed in.
        launchContext.startActivity(
            Intent(launchContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun signIn(launchContext: Context) {
        ServerSocket(0, 0, InetAddress.getByName("localhost")).use { server ->
            server.soTimeout = 5 * 60_000
            val redirectUri = "http://localhost:${server.localPort}/callback"
            val clientId = registerClient(redirectUri)

            val verifier = pkceVerifier()
            val state = pkceVerifier()
            val authorizeUri = Uri.parse("${ZeptoConfig.AUTH_SERVER}/authorize").buildUpon()
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("client_id", clientId)
                .appendQueryParameter("redirect_uri", redirectUri)
                .appendQueryParameter("scope", ZeptoConfig.SCOPES)
                .appendQueryParameter("state", state)
                .appendQueryParameter("code_challenge", pkceChallenge(verifier))
                .appendQueryParameter("code_challenge_method", "S256")
                // RFC 8707 resource indicator, required by the MCP authorization spec so the auth
                // server knows which resource (Zepto's MCP) this grant is for. Omitting it is a
                // plausible cause of the Accept step hanging without ever finalizing the grant.
                .appendQueryParameter("resource", ZeptoConfig.MCP_URL)
                .build()
            launchContext.startActivity(
                Intent(Intent.ACTION_VIEW, authorizeUri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )

            val callback = awaitLoopbackCallback(server)
            val code = callback.getQueryParameter("code")
                ?: error("Zepto did not return an authorization code: ${callback.getQueryParameter("error_description") ?: callback.getQueryParameter("error")}")
            check(callback.getQueryParameter("state") == state) {
                "Zepto's response did not match the request that was sent."
            }

            val form = formEncode(
                mapOf(
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to redirectUri,
                    "client_id" to clientId,
                    "code_verifier" to verifier,
                    "resource" to ZeptoConfig.MCP_URL,
                )
            )
            val response = JSONObject(httpPost("${ZeptoConfig.AUTH_SERVER}/token", form, "application/x-www-form-urlencoded"))
            prefs.edit()
                .putString("access_token", response.getString("access_token"))
                .putString("refresh_token", response.optString("refresh_token", prefs.getString("refresh_token", "")))
                .apply()
        }
    }

    private fun registerClient(redirectUri: String): String {
        val body = JSONObject()
            .put("client_name", "NILA Launcher")
            .put("redirect_uris", JSONArray().put(redirectUri))
            .put("grant_types", JSONArray().put("authorization_code").put("refresh_token"))
            .put("response_types", JSONArray().put("code"))
            .put("token_endpoint_auth_method", "none")
        android.util.Log.d("ZeptoAuth", "register body: $body")
        val response = JSONObject(httpPost("${ZeptoConfig.AUTH_SERVER}/register", body.toString(), "application/json"))
        return response.getString("client_id")
    }

    /**
     * Serves the loopback until a request carries the code (or an OAuth error). Chrome opens an empty
     * preconnect socket first; reading only the first connection failed on it with "arrived empty"
     * and closed the socket before the real redirect came.
     */
    private fun awaitLoopbackCallback(server: ServerSocket): Uri {
        while (true) {
            val callback = server.accept().use { socket -> runCatching { readCallback(socket) }.getOrNull() }
            if (callback != null && (callback.getQueryParameter("code") != null || callback.getQueryParameter("error") != null)) return callback
        }
    }

    /** Reads one request and answers it with a plain HTML page; null for a silent connection. */
    private fun readCallback(socket: java.net.Socket): Uri? {
        socket.soTimeout = SILENT_SOCKET_MS
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.UTF_8))
        val requestLine = reader.readLine() ?: return null
        val path = requestLine.split(" ").getOrNull(1) ?: return null
        // Drain the rest of the request headers before responding, so the browser isn't still
        // writing while we reply — some clients stall waiting for us to finish reading first.
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        // Relying on "Connection: close" + socket EOF to signal the response is done is fragile —
        // some browsers kept the tab spinning even after we'd fully sent and closed. An explicit
        // Content-Length is unambiguous, so Chrome stops loading as soon as the body is read.
        val body = ("<html><body style=\"font-family:sans-serif;padding:24px\">" +
            "Signed in to Zepto. You can close this tab and return to NILA.</body></html>").toByteArray(Charsets.UTF_8)
        val headers = ("HTTP/1.1 200 OK\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n\r\n").toByteArray(Charsets.US_ASCII)
        socket.getOutputStream().apply {
            write(headers)
            write(body)
            flush()
        }
        return Uri.parse("http://localhost$path")
    }

    private companion object {
        const val SILENT_SOCKET_MS = 5_000
    }
}

internal fun pkceVerifier(): String {
    val bytes = ByteArray(32)
    SecureRandom().nextBytes(bytes)
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
}

internal fun pkceChallenge(verifier: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII))
    return Base64.getUrlEncoder().withoutPadding().encodeToString(digest)
}
