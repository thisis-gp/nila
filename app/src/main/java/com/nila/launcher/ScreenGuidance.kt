package com.nila.launcher

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONObject

internal data class ScreenTarget(val label: String, val x: Float, val y: Float)

internal sealed interface ScreenTargetResult {
    data class Suggestions(val targets: List<ScreenTarget>) : ScreenTargetResult
    data object Unavailable : ScreenTargetResult
    data object Unreadable : ScreenTargetResult
}

internal data class ScreenMapping(val packageName: String, val appVersion: Long, val targets: List<ScreenTarget>)

internal object ScreenGuidanceRuntime {
    @Volatile private var brain: NilaBrain? = null
    fun bind(instance: NilaBrain) { brain = instance }
    fun unbind(instance: NilaBrain) { if (brain === instance) brain = null }
    suspend fun suggest(jpeg: ByteArray): ScreenTargetResult = brain?.suggestScreenTargets(jpeg) ?: ScreenTargetResult.Unavailable
}

internal fun parseScreenTargets(raw: String): List<ScreenTarget>? = runCatching {
    val json = JSONObject(raw.substring(raw.indexOf('{'), raw.lastIndexOf('}') + 1))
    val array = json.getJSONArray("targets")
    require(array.length() <= 8)
    (0 until array.length()).map { index ->
        val item = array.getJSONObject(index)
        val label = item.getString("label").trim()
        val x = item.getDouble("x")
        val y = item.getDouble("y")
        require(label.isNotEmpty() && label.length <= 60 && x.isFinite() && y.isFinite() && x in 0.0..1.0 && y in 0.0..1.0)
        ScreenTarget(label, x.toFloat(), y.toFloat())
    }
}.getOrNull()

internal fun currentScreenMapping(mapping: ScreenMapping?, packageName: String, appVersion: Long): ScreenMapping? =
    mapping?.takeIf { it.packageName == packageName && it.appVersion == appVersion }

internal fun canRunScreenGuidance(model: ModelSpec?, assistantLoaded: Boolean, memory: DeviceMemory): Boolean =
    model != null && ModelCapability.VISION in model.capabilities && assistantLoaded && !memory.lowMemory && memory.availableBytes >= GIB

/** Stores only encrypted labels/coordinates; screenshots are never persisted. */
internal class ScreenGuidanceStore(context: Context) {
    private val preferences by lazy {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context,
            "nila_screen_guidance",
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun get(packageName: String, appVersion: Long): ScreenMapping? {
        val mapping = preferences.getString(packageName, null)?.let { decode(it, packageName) }
        if (mapping != null && currentScreenMapping(mapping, packageName, appVersion) == null) preferences.edit().remove(packageName).apply()
        return currentScreenMapping(mapping, packageName, appVersion)
    }

    fun save(mapping: ScreenMapping) {
        val targets = org.json.JSONArray().apply {
            mapping.targets.forEach { target ->
                put(JSONObject().put("label", target.label).put("x", target.x.toDouble()).put("y", target.y.toDouble()))
            }
        }
        preferences.edit().putString(
            mapping.packageName,
            JSONObject().put("version", mapping.appVersion).put("targets", targets).toString(),
        ).apply()
    }

    private fun decode(raw: String, packageName: String): ScreenMapping? = runCatching {
        val json = JSONObject(raw)
        val targets = parseScreenTargets(JSONObject().put("targets", json.getJSONArray("targets")).toString()) ?: error("invalid targets")
        ScreenMapping(packageName, json.getLong("version"), targets)
    }.getOrNull()
}
