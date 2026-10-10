package com.nila.launcher

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.ConcurrentHashMap

/** Sign-ins waiting for their app-link redirect, keyed by the OAuth state they sent. */
internal object PendingRedirects {
    private val waiting = ConcurrentHashMap<String, CompletableDeferred<String?>>()

    fun expect(state: String): CompletableDeferred<String?> = CompletableDeferred<String?>().also { waiting[state] = it }

    fun forget(state: String) {
        waiting.remove(state)
    }

    /** Hands the code to the sign-in that sent this state. A redirect nobody is waiting for is ignored. */
    fun deliver(uri: Uri) {
        val state = uri.getQueryParameter("state") ?: return
        waiting.remove(state)?.complete(uri.getQueryParameter("code"))
    }
}

/**
 * Receives `com.nila.launcher:/oauth?code=…` from the browser. Android delivers this intent even while the
 * cached-app freezer holds NILA, which a loopback socket never got: Zomato's redirect queued unanswered.
 */
class OAuthRedirectActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        intent?.data?.let(PendingRedirects::deliver)
        // Back to NILA, above the sign-in tab.
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))
        finish()
    }
}
