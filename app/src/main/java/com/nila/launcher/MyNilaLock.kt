package com.nila.launcher

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.os.Build
import android.provider.Settings
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** How long My NILA stays unlocked once NILA has left the screen. */
internal const val LOCK_BACKGROUND_LIMIT_MS = 5 * 60 * 1000L
private const val LOCK_PREF = "lock_my_nila"
private const val ANDROID_11 = Build.VERSION_CODES.R

/**
 * True when an unlock no longer holds: the screen turned off since it (screenOffAt), or NILA has been in the
 * background for [LOCK_BACKGROUND_LIMIT_MS] (backgroundSince). Never unlocked means nothing to expire.
 */
internal fun lockExpired(unlockedAt: Long?, now: Long, screenOffAt: Long?, backgroundSince: Long?): Boolean {
    if (unlockedAt == null) return false
    if (screenOffAt != null && screenOffAt >= unlockedAt) return true
    return backgroundSince != null && now - backgroundSince >= LOCK_BACKGROUND_LIMIT_MS
}

internal enum class LockSupport { Ready, NoScreenLock, Unavailable }

/** The phone's own lock (fingerprint, face, PIN, pattern or password) guards My NILA; NILA builds no PIN screen. */
internal object MyNilaLock {
    var enabled by mutableStateOf(true)
        private set
    var unlocked by mutableStateOf(false)
        private set
    private var unlockedAt: Long? = null
    private var screenOffAt: Long? = null
    private var backgroundSince: Long? = null
    private var prompting = false

    private val authenticators = if (Build.VERSION.SDK_INT >= ANDROID_11) BIOMETRIC_STRONG or DEVICE_CREDENTIAL else BIOMETRIC_WEAK or DEVICE_CREDENTIAL

    fun load(prefs: SharedPreferences) {
        enabled = prefs.getBoolean(LOCK_PREF, true)
    }

    fun setEnabled(prefs: SharedPreferences, on: Boolean) {
        enabled = on
        prefs.edit().putBoolean(LOCK_PREF, on).apply()
    }

    fun support(context: Context): LockSupport = when (BiometricManager.from(context).canAuthenticate(authenticators)) {
        BiometricManager.BIOMETRIC_SUCCESS -> LockSupport.Ready
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> LockSupport.NoScreenLock
        else -> LockSupport.Unavailable
    }

    fun onScreenOff(now: Long) {
        screenOffAt = now
    }

    fun onStop(now: Long) {
        backgroundSince = now
    }

    /** Called when NILA comes back to the front, before anything is drawn. */
    fun onStart(now: Long) {
        if (unlocked && lockExpired(unlockedAt, now, screenOffAt, backgroundSince)) lock()
        backgroundSince = null
    }

    private fun lock() {
        unlocked = false
        unlockedAt = null
        screenOffAt = null
    }

    /** Opens the system prompt. [onResult] gets true only on success. One prompt at a time. */
    fun authenticate(activity: FragmentActivity, onResult: (Boolean) -> Unit) {
        if (prompting) return
        prompting = true
        val prompt = BiometricPrompt(
            activity,
            ContextCompat.getMainExecutor(activity),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    prompting = false
                    onResult(true)
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    prompting = false
                    onResult(false)
                }
            },
        )
        prompt.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle("Unlock My NILA")
                .setSubtitle("Your personal details stay on this phone")
                .setAllowedAuthenticators(authenticators)
                .build(),
        )
    }

    /** Asks for the phone's lock and, on success, opens My NILA for this session. */
    fun unlock(activity: FragmentActivity) = authenticate(activity) { ok ->
        if (!ok) return@authenticate
        unlockedAt = System.currentTimeMillis()
        screenOffAt = null
        unlocked = true
    }

    /** Opens the phone's own screen-lock settings. */
    fun openSecuritySettings(context: Context) {
        runCatching { context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}
