package com.nila.launcher

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import java.time.LocalDate
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import androidx.core.content.ContextCompat

// My NILA's fitness tracker: steps from the phone's own step counter, plus workouts and weight the user
// mentions in chat. Everything stays on this phone.

/** A workout or a weight the user told NILA about. Numbers are exactly the ones they said. */
data class FitnessEntry(
    val id: String,
    val date: LocalDate,
    val activity: String,
    val minutes: Int? = null,
    val km: Double? = null,
    val kg: Double? = null,
)

/** Shown in chat after NILA logs a workout or weight. */
data class FitnessLogged(val entry: FitnessEntry) : NilaResponse

/** "How many steps today": answered from the step counter. */
data class StepsAnswer(val today: Int?, val week: List<Pair<LocalDate, Int>>, val trackingStarted: Boolean = false) : NilaResponse

private val activities = linkedMapOf(
    "run" to Regex("""\b(ran|run|running|jog|jogged|jogging)\b"""),
    "walk" to Regex("""\b(walk|walked|walking)\b"""),
    "cycle" to Regex("""\b(cycle|cycled|cycling|bike|biked|biking)\b"""),
    "swim" to Regex("""\b(swim|swam|swimming)\b"""),
    "yoga" to Regex("""\byoga\b"""),
    "gym" to Regex("""\b(gym|weights|lifting|workout|worked out|exercise|exercised|training)\b"""),
)
private val Km = Regex("""(\d+(?:\.\d+)?)\s*(km|kms|kilometers?|kilometres?)\b""")
private val Minutes = Regex("""(\d+)\s*(min|mins|minutes?)\b""")
private val Hours = Regex("""(\d+(?:\.\d+)?)\s*(hr|hrs|hours?)\b""")
private val Weight = Regex("""\b(weigh|weight|weighed)\b[^\d]{0,20}(\d{2,3}(?:\.\d)?)\s*(kg|kgs|kilos?)?\b""")
private val FirstPerson = Regex("""\b(i|i've|i'm|today|yesterday|my)\b""")
private val RequestStart = Regex("""^\s*(please\s+)?(calculate|convert|order|buy|book|find|search|show|open|play|set|start|call|take me)\b""")

/**
 * "I ran 5 km in 30 minutes", "did 45 min yoga", "my weight is 72 kg": a workout or weight to log, with the
 * numbers exactly as said. Null when it isn't one, or it is a request ("start a 10 minute timer").
 */
fun fitnessEntry(text: String, today: LocalDate): FitnessEntry? {
    val lower = text.lowercase()
    if (RequestStart.containsMatchIn(lower) || Regex("""\btimer\b""").containsMatchIn(lower)) return null
    val date = if (Regex("""\byesterday\b""").containsMatchIn(lower)) today.minusDays(1) else today
    Weight.find(lower)?.let { m -> return FitnessEntry(UUID.randomUUID().toString(), date, "weight", kg = m.groupValues[2].toDouble()) }
    if (!FirstPerson.containsMatchIn(lower)) return null
    val activity = activities.entries.firstOrNull { (_, words) -> words.containsMatchIn(lower) }?.key ?: return null
    val km = Km.find(lower)?.groupValues?.get(1)?.toDouble()
    val minutes = Minutes.find(lower)?.groupValues?.get(1)?.toInt()
        ?: Hours.find(lower)?.groupValues?.get(1)?.toDouble()?.let { (it * MINUTES_PER_HOUR).toInt() }
    if (km == null && minutes == null) return null
    return FitnessEntry(UUID.randomUUID().toString(), date, activity, minutes, km)
}

private const val MINUTES_PER_HOUR = 60

private val StepsQuestion = Regex("""\b(how many|how much|my)\b.*\b(steps?|walked)\b|\bsteps?\b.*\b(today|this week|so far)\b""")

/** "how many steps today", "my steps this week". */
internal fun asksForSteps(text: String): Boolean = StepsQuestion.containsMatchIn(text.lowercase()) && fitnessEntry(text, LocalDate.now()) == null

internal fun hasStepPermission(context: Context): Boolean = Build.VERSION.SDK_INT < Build.VERSION_CODES.Q ||
    ContextCompat.checkSelfPermission(context, android.Manifest.permission.ACTIVITY_RECOGNITION) == PackageManager.PERMISSION_GRANTED

/** "Run · 5 km · 30 min", "Weight · 72 kg". */
internal fun entryLine(e: FitnessEntry): String = listOfNotNull(
    e.activity.replaceFirstChar(Char::uppercase),
    e.km?.let { "${trim(it)} km" },
    e.minutes?.let { "$it min" },
    e.kg?.let { "${trim(it)} kg" },
).joinToString(" · ")

private fun trim(d: Double) = if (d % 1.0 == 0.0) d.toInt().toString() else d.toString()

/**
 * Where the step counter stood: the phone counts steps since it last started, so NILA keeps the count at the
 * start of today and each finished day's total.
 */
data class StepState(val day: LocalDate, val dayStart: Long, val last: Long, val days: Map<LocalDate, Int>)

/**
 * The step state after reading [counter] on [today]. A new day closes yesterday at the last reading; a
 * counter lower than the last reading means the phone restarted, so counting continues from zero.
 */
internal fun stepsAfter(state: StepState?, counter: Long, today: LocalDate): StepState {
    if (state == null) return StepState(today, counter, counter, emptyMap())
    // A restart reset the phone's count: carry today's steps so far into the new count.
    val restarted = counter < state.last
    val start = if (restarted) counter - (state.last - state.dayStart) else state.dayStart
    if (state.day == today) return state.copy(dayStart = start, last = counter)
    val closed = (state.last - state.dayStart).toInt().coerceAtLeast(0)
    val days = (state.days + (state.day to closed)).filterKeys { it.isAfter(today.minusDays(KEEP_DAYS)) }
    val newStart = if (restarted) counter else state.last
    return StepState(today, newStart, counter, days)
}

internal fun stepsToday(state: StepState?) = state?.let { (it.last - it.dayStart).toInt().coerceAtLeast(0) }

private const val KEEP_DAYS = 60L

/** Fitness data on the phone: step state, workouts and weights. */
internal class FitnessStore(context: Context) {
    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun steps(): StepState? = prefs.getString(STEPS, null)?.let { raw ->
        runCatching {
            val o = JSONObject(raw)
            val days = o.optJSONObject("days")?.let { d -> d.keys().asSequence().associate { LocalDate.parse(it) to d.getInt(it) } }.orEmpty()
            StepState(LocalDate.parse(o.getString("day")), o.getLong("dayStart"), o.getLong("last"), days)
        }.getOrNull()
    }

    fun saveSteps(state: StepState) {
        prefs.edit().putString(STEPS, JSONObject().put("day", state.day.toString()).put("dayStart", state.dayStart).put("last", state.last)
            .put("days", JSONObject().apply { state.days.forEach { (d, n) -> put(d.toString(), n) } }).toString()).apply()
    }

    fun entries(): List<FitnessEntry> = prefs.getString(ENTRIES, null)?.let { raw ->
        runCatching { JSONArray(raw) }.getOrNull()?.let { a ->
            (0 until a.length()).mapNotNull { i ->
                val o = a.optJSONObject(i) ?: return@mapNotNull null
                runCatching {
                    FitnessEntry(
                        o.getString("id"), LocalDate.parse(o.getString("date")), o.getString("activity"),
                        o.optInt("minutes", -1).takeIf { it >= 0 }, o.optDouble("km", -1.0).takeIf { it >= 0 }, o.optDouble("kg", -1.0).takeIf { it >= 0 },
                    )
                }.getOrNull()
            }
        }
    }.orEmpty().sortedByDescending { it.date }

    fun add(entry: FitnessEntry): List<FitnessEntry> = (entries() + entry).also(::write)

    fun delete(id: String): List<FitnessEntry> = entries().filterNot { it.id == id }.also(::write)

    private fun write(list: List<FitnessEntry>) {
        prefs.edit().putString(ENTRIES, JSONArray().apply {
            list.forEach { e ->
                put(JSONObject().put("id", e.id).put("date", e.date.toString()).put("activity", e.activity)
                    .put("minutes", e.minutes ?: -1).put("km", e.km ?: -1.0).put("kg", e.kg ?: -1.0))
            }
        }.toString()).apply()
    }

    private companion object {
        const val PREFS = "nila_fitness"
        const val STEPS = "steps"
        const val ENTRIES = "entries"
    }
}

/** Reads the phone's step counter once; null without the sensor, the permission, or a reading within a few seconds. */
internal suspend fun readStepCounter(context: Context): Long? {
    val sensors = context.getSystemService(SensorManager::class.java) ?: return null
    val counter = sensors.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) ?: return null
    return withTimeoutOrNull(SENSOR_WAIT_MS) {
        suspendCancellableCoroutine { done ->
            val listener = object : SensorEventListener {
                override fun onSensorChanged(event: SensorEvent) {
                    sensors.unregisterListener(this)
                    if (done.isActive) done.resume(event.values[0].toLong())
                }

                override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
            }
            if (!sensors.registerListener(listener, counter, SensorManager.SENSOR_DELAY_NORMAL)) done.resume(null)
            done.invokeOnCancellation { sensors.unregisterListener(listener) }
        }
    }
}

private const val SENSOR_WAIT_MS = 5_000L

/** Reads the counter and saves the new state; returns today's steps, or null when the counter can't be read. */
internal suspend fun sampleSteps(context: Context, today: LocalDate = LocalDate.now()): Int? {
    if (!hasStepPermission(context)) return null
    val counter = readStepCounter(context) ?: return null
    val store = FitnessStore(context)
    return stepsAfter(store.steps(), counter, today).also(store::saveSteps).let(::stepsToday)
}

/**
 * Samples the step counter about every hour, so a day's total is close even when NILA isn't opened.
 * ponytail: hourly inexact sampling; steps after the last sample of a day count towards the next day.
 */
class StepSampleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                sampleSteps(context)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        /** Starts hourly sampling; safe to call again. */
        fun schedule(context: Context) {
            val intent = PendingIntent.getBroadcast(
                context, SAMPLE_REQUEST, Intent(context, StepSampleReceiver::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            context.getSystemService(AlarmManager::class.java)?.setInexactRepeating(
                AlarmManager.ELAPSED_REALTIME, android.os.SystemClock.elapsedRealtime() + AlarmManager.INTERVAL_HOUR,
                AlarmManager.INTERVAL_HOUR, intent,
            )
        }

        private const val SAMPLE_REQUEST = 7_207
    }
}

// ---- My NILA › Fitness ---------------------------------------------------------

/** A day's step goal: the common 8,000. */
internal const val STEP_GOAL = 8_000

/** The last [n] days' steps, oldest first, with today's live count. */
internal fun lastDays(state: StepState?, today: LocalDate, n: Int = 7): List<Pair<LocalDate, Int>> =
    (n - 1 downTo 0).map { back ->
        val day = today.minusDays(back.toLong())
        day to (if (day == today) stepsToday(state)?.takeIf { state?.day == today } ?: 0 else state?.days?.get(day) ?: 0)
    }
