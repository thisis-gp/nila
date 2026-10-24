package com.nila.launcher.skills

import java.time.Clock
import java.time.Instant
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** The phone's bank and UPI payments as items (sub-project 3). Tests pass a fixed list. */
fun interface TransactionSource {
    fun transactions(): List<Item>
}

sealed interface DataResult {
    data class Items(val items: List<Item>) : DataResult
    /** The user has not granted, or has revoked, what this data needs; the app shows a "grant access" state. */
    data class Denied(val permission: String) : DataResult
}

sealed interface ActionResult {
    data object Done : ActionResult
    data class Denied(val permission: String) : ActionResult
    data class Rejected(val reason: String) : ActionResult
}

/**
 * Runs one installed recipe: reads its data, computes its values and performs its actions. Every read and write
 * checks [grants] (spec §8), so revoking a permission takes effect at once, without reinstalling the app.
 */
class AppRuntime(
    recipe: JsonObject,
    private val grants: Set<String>,
    private val store: FileAppStore,
    private val transactions: TransactionSource,
    private val clock: Clock,
) {
    private val sources: Map<String, JsonObject> =
        (recipe["data"] as? JsonObject).orEmpty().mapNotNull { (key, value) -> (value as? JsonObject)?.let { key to it } }.toMap()
    private val recipeRules: List<JsonObject> = (recipe["rules"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }

    fun items(key: String): DataResult {
        val source = sources[key] ?: return DataResult.Items(emptyList())
        val provider = providerOf(source) ?: return DataResult.Items(emptyList())
        if (provider.permission !in grants) return DataResult.Denied(provider.permission)
        val raw = if (provider.ownStore) source.string(STORE)?.let(store::list).orEmpty() else transactions.transactions()
        val rules = recipeRules + learnedRules()
        return DataResult.Items(raw.map { RulesEngine.apply(rules, it) })
    }

    /** [back] reads the same value that many periods ago, for the screen's comparison line. */
    fun value(fn: JsonObject, back: Int = 0): FnValue = when (val data = items(keyOf(fn.string("of")))) {
        is DataResult.Items -> RecipeFunctions.evaluate(fn, data.items, clock, back)
        is DataResult.Denied -> FnValue.Unsupported(DENIED)
    }

    /** Chart points: totals of `amount` per [groupBy] value (counts when items have no amount), or per day without [groupBy]. */
    fun chart(dataPath: String, groupBy: String?, period: String?): Map<String, Double> {
        val items = (items(keyOf(dataPath)) as? DataResult.Items)?.items ?: return emptyMap()
        val shown = RecipeFunctions.inPeriod(items, period, clock)
        val valueField = AMOUNT.takeIf { shown.any { it.number(AMOUNT) != null } }
        val keyed = if (groupBy != null) shown else shown.map { it.copy(fields = it.fields + (DAY to JsonPrimitive(dayOf(it)))) }
        return RecipeFunctions.group(keyed, groupBy ?: DAY, valueField)
    }

    fun dispatch(action: JsonObject, target: Item?, values: Map<String, JsonElement>): ActionResult =
        when (val name = action.string("action")) {
            STORAGE_ADD, COUNTER_STEP -> write(userSource()) { storeName ->
                val dated = if (RecipeFunctions.DATE_FIELD in values) values else values + (RecipeFunctions.DATE_FIELD to JsonPrimitive(clock.millis()))
                store.add(storeName, dated)
                ActionResult.Done
            }
            STORAGE_UPDATE -> write(userSource()) { storeName ->
                if (target != null && store.update(storeName, target.id, values) != null) ActionResult.Done else ActionResult.Rejected(NO_ITEM)
            }
            STORAGE_DELETE -> write(userSource()) { storeName ->
                if (target != null && store.delete(storeName, target.id)) ActionResult.Done else ActionResult.Rejected(NO_ITEM)
            }
            RULES_LEARN -> write(rulesSource()) { storeName -> learn(storeName, action, target, values) }
            else -> ActionResult.Rejected("unknown action $name")
        }

    private fun learn(storeName: String, action: JsonObject, target: Item?, values: Map<String, JsonElement>): ActionResult {
        val key = action.string("key") ?: MERCHANT
        val value = target?.string(key) ?: return ActionResult.Rejected("nothing to learn from")
        val set = values.mapNotNull { (field, v) -> v.stringOrNull()?.let { field to it } }.toMap()
        if (set.isEmpty()) return ActionResult.Rejected("nothing to set")
        val learned = RulesEngine.learn(learnedRules(), key, value, set)
        store.replaceAll(storeName, learned.mapIndexed { index, rule -> Item("$RULE_ID_PREFIX$index", rule) })
        return ActionResult.Done
    }

    private fun write(source: JsonObject?, block: (String) -> ActionResult): ActionResult {
        val provider = source?.let(::providerOf) ?: return ActionResult.Rejected("this app has nowhere to keep that")
        if (provider.permission !in grants) return ActionResult.Denied(provider.permission)
        val storeName = source.string(STORE) ?: return ActionResult.Rejected("this app has nowhere to keep that")
        return block(storeName)
    }

    private fun learnedRules(): List<JsonObject> {
        val source = rulesSource() ?: return emptyList()
        if (providerOf(source)?.permission !in grants) return emptyList()
        return source.string(STORE)?.let(store::list).orEmpty().map { JsonObject(it.fields) }
    }

    private fun providerOf(source: JsonObject) = RecipeCatalog.providers[source.string("provider")]
    private fun userSource() = sources.values.firstOrNull { providerOf(it)?.userAdds == true }
    private fun rulesSource() = sources.values.firstOrNull { it.string("provider") == RULES_PROVIDER }
    private fun keyOf(path: String?) = path.orEmpty().removePrefix("/").substringBefore('/')
    private fun dayOf(item: Item) = item.number(RecipeFunctions.DATE_FIELD)
        ?.let { Instant.ofEpochMilli(it.toLong()).atZone(clock.zone).toLocalDate().toString() } ?: RecipeFunctions.OTHER_GROUP

    private companion object {
        const val STORE = "store"
        const val AMOUNT = "amount"
        const val DAY = "day"
        const val MERCHANT = "merchant"
        const val RULES_PROVIDER = "rules"
        const val RULE_ID_PREFIX = "rule-"
        const val DENIED = "denied"
        const val NO_ITEM = "no such item"
        const val STORAGE_ADD = "storage.add"
        const val STORAGE_UPDATE = "storage.update"
        const val STORAGE_DELETE = "storage.delete"
        const val COUNTER_STEP = "counter.step"
        const val RULES_LEARN = "rules.learn"
    }
}
