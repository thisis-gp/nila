package com.nila.launcher

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A user-authored, one-step skill. It stores data only; every call still goes through NILA's registry. */
data class NilaSkill(
    val id: String,
    val name: String,
    val trigger: String,
    val capabilityId: String,
    val arguments: Map<String, String> = emptyMap(),
    val enabled: Boolean = true,
)

internal const val MAX_NILA_SKILLS = 100

internal fun matchingSkill(input: String, skills: List<NilaSkill>): NilaSkill? {
    if (input.length > 120) return null
    val phrase = skillTriggerKey(input)
    return skills.firstOrNull { it.enabled && phrase.isNotBlank() && skillTriggerKey(it.trigger) == phrase }
}

internal fun skillTriggerKey(text: String) = text.trim().lowercase().trimEnd('.', '!', '?').replace(Regex("\\s+"), " ")

/** Rejects malformed saved data at the same registry boundary used for model-proposed calls. */
internal fun skillCall(skill: NilaSkill, registry: Map<String, CapabilityDescriptor>): ToolCall? {
    if (skill.id.length !in 1..64 || skill.name.trim().length !in 1..60 || skill.trigger.trim().length !in 1..120 ||
        skill.arguments.size > 12 || skill.arguments.any { (key, value) -> key.length !in 1..64 || value.length > 256 }
    ) return null
    val capability = registry[skill.capabilityId] ?: return null
    val args = coerceArgs(skill.arguments.filterValues(String::isNotBlank), capability)
    val call = ToolCall(skill.capabilityId, args)
    return (checkCall(call, registry) as? CallCheck.Ok)?.call
}

internal fun parseSkillArguments(raw: String): Map<String, String>? {
    if (raw.length > 500) return null
    if (raw.isBlank()) return emptyMap()
    val pairs = raw.split(',').map { part ->
        val pieces = part.trim().split('=', limit = 2)
        if (pieces.size != 2 || pieces.any(String::isBlank)) return null
        pieces[0].trim() to pieces[1].trim()
    }
    return pairs.toMap().takeIf { it.size == pairs.size && it.size <= 12 && it.all { (key, value) -> key.length <= 64 && value.length <= 256 } }
}

internal fun encodeSkills(skills: List<NilaSkill>): String = JSONArray().apply {
    skills.forEach { skill ->
        put(JSONObject().apply {
            put("id", skill.id)
            put("name", skill.name)
            put("trigger", skill.trigger)
            put("capabilityId", skill.capabilityId)
            put("enabled", skill.enabled)
            put("arguments", JSONObject(skill.arguments))
        })
    }
}.toString()

internal fun decodeSkills(raw: String?): List<NilaSkill> = runCatching {
    val items = JSONArray(raw ?: return emptyList())
    (0 until minOf(items.length(), MAX_NILA_SKILLS)).mapNotNull { index ->
        val item = items.optJSONObject(index) ?: return@mapNotNull null
        val args = item.optJSONObject("arguments") ?: JSONObject()
        NilaSkill(
            id = item.optString("id").takeIf(String::isNotBlank) ?: return@mapNotNull null,
            name = item.optString("name"),
            trigger = item.optString("trigger"),
            capabilityId = item.optString("capabilityId"),
            arguments = args.keys().asSequence().associateWith { args.optString(it) },
            enabled = item.optBoolean("enabled", true),
        )
    }
}.getOrDefault(emptyList())

internal class NilaSkillStore(context: Context) {
    private val appContext = context.applicationContext
    private val masterKey by lazy { MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build() }
    private val prefs by lazy {
        EncryptedSharedPreferences.create(
            appContext,
            "nila_skills",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun all() = decodeSkills(prefs.getString(KEY, null))

    fun save(skill: NilaSkill) {
        val updated = all().filterNot { it.id == skill.id } + skill
        if (updated.size <= MAX_NILA_SKILLS) prefs.edit().putString(KEY, encodeSkills(updated)).apply()
    }

    fun delete(id: String) {
        prefs.edit().putString(KEY, encodeSkills(all().filterNot { it.id == id })).apply()
    }

    companion object {
        private const val KEY = "skills"
        fun newId() = UUID.randomUUID().toString()
    }
}
