package com.nila.launcher

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.time.LocalDate
import java.time.LocalTime
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * A My NILA reminder: once on [at] (a health follow-up), or every month on [monthlyDay] at [at]'s time
 * (the electricity bill). A day the month doesn't have is its last day: the 30th is the 28th in February.
 * [due] is when the alarm is set to fire, so a reminder missed while the phone was off fires at the next start.
 */
data class NilaReminder(
    val id: String,
    val title: String,
    val text: String,
    val at: LocalDateTime,
    val monthlyDay: Int? = null,
    val due: LocalDateTime? = null,
)

/** The next time a reminder fires after [now]: [NilaReminder.at] itself, or the next month's day for a monthly one. */
internal fun nextFiring(reminder: NilaReminder, now: LocalDateTime): LocalDateTime? {
    val day = reminder.monthlyDay ?: return reminder.at.takeIf { it.isAfter(now) }
    fun inMonth(month: YearMonth) = month.atDay(minOf(day, month.lengthOfMonth())).atTime(reminder.at.toLocalTime())
    val thisMonth = inMonth(YearMonth.from(now))
    return if (thisMonth.isAfter(now)) thisMonth else inMonth(YearMonth.from(now).plusMonths(1))
}

/** A health follow-up as a reminder at 9 in the morning of its day; null when the record has none. */
internal fun followUpReminder(record: HealthRecord): NilaReminder? = record.followUp?.let { day ->
    NilaReminder(
        id = "followup-${record.id}",
        title = "Follow-up: ${record.title}",
        text = listOf("Today", record.place).filter(String::isNotBlank).joinToString(" · ") + ". From your report in My NILA.",
        at = day.atTime(FOLLOW_UP_HOUR, 0),
    )
}

private const val FOLLOW_UP_HOUR = 9

/** A monthly reminder from chat ("pay the electricity bill every month on the 5th"). */
internal fun monthlyReminderOf(reminder: MonthlyReminder, now: LocalDateTime): NilaReminder =
    NilaReminder(
        id = UUID.randomUUID().toString(),
        title = reminder.title,
        text = "Your monthly reminder from My NILA.",
        at = firstReminder(reminder, now),
        monthlyDay = reminder.day,
    )

/**
 * Reminders kept on the phone and scheduled with Android's alarm service, so they fire with NILA closed.
 * ponytail: inexact alarms (setAndAllowWhileIdle) can arrive a few minutes late; switch to exact alarms if
 * a reminder must be on the minute.
 */
internal class NilaReminders(context: Context) {
    private val appContext = context.applicationContext
    private val prefs by lazy {
        val masterKey = MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            appContext,
            SECURE_PREFS,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        ).also(::moveFromPlain)
    }

    private fun moveFromPlain(secure: SharedPreferences) {
        val plain = appContext.getSharedPreferences(PLAIN_PREFS, Context.MODE_PRIVATE)
        val reminders = plain.getString(LIST, null) ?: return
        // Delete the readable copy only after the encrypted write is on disk.
        if (secure.edit().putString(LIST, secure.getString(LIST, null) ?: reminders).commit()) {
            plain.edit().clear().commit()
            appContext.deleteSharedPreferences(PLAIN_PREFS)
        }
    }

    fun all(): List<NilaReminder> = prefs.getString(LIST, null)?.let(::decode).orEmpty().sortedBy { nextFiring(it, LocalDateTime.now()) ?: it.at }

    fun add(reminder: NilaReminder): List<NilaReminder> =
        (all().filterNot { it.id == reminder.id } + schedule(reminder)).also(::write)

    fun remove(id: String): List<NilaReminder> {
        alarms().cancel(pending(id))
        return all().filterNot { it.id == id }.also(::write)
    }

    /**
     * After a restart or a clock change Android's alarms are gone or wrong: a reminder whose time passed
     * meanwhile notifies now (once), and every reminder is set again for its next time.
     */
    fun rescheduleAll(now: LocalDateTime = LocalDateTime.now()) {
        val kept = all().mapNotNull { reminder ->
            if (missed(reminder, now)) notifyReminder(appContext, reminder)
            if (reminder.monthlyDay == null && nextFiring(reminder, now) == null) null else schedule(reminder, now)
        }
        write(kept)
    }

    /** Called when a reminder fires: a monthly one moves to next month, a one-off one is done. */
    fun fired(id: String) {
        val reminder = all().firstOrNull { it.id == id } ?: return
        write(all().filterNot { it.id == id } + listOfNotNull(reminder.takeIf { it.monthlyDay != null }?.let { schedule(it) }))
    }

    fun find(id: String) = all().firstOrNull { it.id == id }

    /** Sets the alarm for the reminder's next time and returns it with that time as [NilaReminder.due]. */
    private fun schedule(reminder: NilaReminder, now: LocalDateTime = LocalDateTime.now()): NilaReminder {
        val next = nextFiring(reminder, now) ?: return reminder.copy(due = null)
        val millis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        alarms().setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending(reminder.id))
        return reminder.copy(due = next)
    }

    private fun alarms() = appContext.getSystemService(AlarmManager::class.java)

    // Each reminder's intent has its own data URI, so two reminders never share a PendingIntent
    // (a request code from hashCode could collide and one alarm would replace the other).
    private fun pending(id: String) = PendingIntent.getBroadcast(
        appContext,
        0,
        Intent(appContext, ReminderReceiver::class.java).setAction(ACTION).setData(Uri.parse("nila-reminder:$id")).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun write(list: List<NilaReminder>) {
        prefs.edit().putString(LIST, JSONArray().apply {
            list.forEach { r ->
                put(
                    JSONObject().put("id", r.id).put("title", r.title).put("text", r.text).put("at", r.at.toString())
                        .put("monthlyDay", r.monthlyDay ?: 0).put("due", r.due?.toString() ?: ""),
                )
            }
        }.toString()).apply()
    }

    private fun decode(raw: String): List<NilaReminder> {
        val list = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            runCatching {
                NilaReminder(
                    o.getString("id"), o.optString("title"), o.optString("text"), LocalDateTime.parse(o.getString("at")),
                    o.optInt("monthlyDay").takeIf { it in 1..31 }, o.optString("due").takeIf { it.isNotBlank() }?.let(LocalDateTime::parse),
                )
            }.getOrNull()
        }
    }

    companion object {
        private const val PLAIN_PREFS = "nila_reminders"
        private const val SECURE_PREFS = "nila_reminders_secure"
        private const val LIST = "reminders"
        const val ACTION = "com.nila.launcher.REMINDER"
        const val ACTION_STOP = "com.nila.launcher.REMINDER_STOP"
        const val EXTRA_ID = "id"
        const val CHANNEL = "my_nila"
    }
}

/** A reminder whose alarm time passed without it firing (the phone was off, or the clock moved forward). */
internal fun missed(reminder: NilaReminder, now: LocalDateTime): Boolean = reminder.due?.let { !it.isAfter(now) } ?: false

/**
 * Shows a My NILA reminder as a notification; tapping it opens NILA. The lock screen shows only
 * "My NILA reminder": the title can be health details.
 */
internal fun notifyReminder(context: Context, reminder: NilaReminder) {
    val manager = context.getSystemService(NotificationManager::class.java) ?: return
    manager.createNotificationChannel(NotificationChannel(NilaReminders.CHANNEL, "My NILA reminders", NotificationManager.IMPORTANCE_HIGH))
    val open = PendingIntent.getActivity(
        context,
        0,
        Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
        PendingIntent.FLAG_IMMUTABLE,
    )
    val onLockScreen = Notification.Builder(context, NilaReminders.CHANNEL)
        .setSmallIcon(android.R.drawable.ic_popup_reminder)
        .setContentTitle("My NILA reminder")
        .build()
    val notification = Notification.Builder(context, NilaReminders.CHANNEL)
        .setSmallIcon(android.R.drawable.ic_popup_reminder)
        .setContentTitle(reminder.title)
        .setContentText(reminder.text)
        .setVisibility(Notification.VISIBILITY_PRIVATE)
        .setPublicVersion(onLockScreen)
        .setContentIntent(open)
        .setAutoCancel(true)
        .addAction(
            Notification.Action.Builder(
                null,
                if (reminder.monthlyDay != null) "Stop reminding" else "Done",
                PendingIntent.getBroadcast(
                    context, reminder.id.hashCode() + 1,
                    Intent(context, ReminderReceiver::class.java).setAction(NilaReminders.ACTION_STOP).putExtra(NilaReminders.EXTRA_ID, reminder.id),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
                ),
            ).build(),
        )
        .build()
    runCatching { manager.notify(reminder.id, 0, notification) }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminders = NilaReminders(context)
        val id = intent.getStringExtra(NilaReminders.EXTRA_ID) ?: return
        // "Stop reminding" on the notification: reminders are only notifications, so this is where they end.
        if (intent.action == NilaReminders.ACTION_STOP) {
            reminders.remove(id)
            context.getSystemService(NotificationManager::class.java)?.cancel(id, 0)
            return
        }
        val reminder = reminders.find(id) ?: return
        notifyReminder(context, reminder)
        reminders.fired(id)
    }
}

/** Puts My NILA's reminders back after the phone restarts or its clock or time zone changes. */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action in RESCHEDULE_ON) NilaReminders(context).rescheduleAll()
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) StepSampleReceiver.schedule(context)
    }

    private companion object {
        val RESCHEDULE_ON = setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)
    }
}

/** The day a reminder next fires, for My NILA's list: "Thu 5 Nov, 10:00". */
internal fun reminderWhen(reminder: NilaReminder, now: LocalDateTime = LocalDateTime.now()): String {
    val next = nextFiring(reminder, now) ?: return "Done"
    return eventWhen(next.toLocalDate(), next.toLocalTime()) + if (reminder.monthlyDay != null) " · every month" else ""
}

// ---- One-time reminders from chat ------------------------------------------------

/** A one-time reminder: "remind me to call Amma at 6 pm". */
data class OneOffReminder(val title: String, val at: LocalDateTime) : NilaResponse

private val ReminderWord = Regex("""\b(remind|reminder|reminders)\b""")
private val EveryMonth = Regex("""\b(every month|each month|monthly)\b""")
private val InTime = Regex("""\bin\s+(\d{1,3})\s*(min|mins|minutes?|hr|hrs|hours?)\b""")
private val AtTime = Regex("""\b(?:at|by)\s+(\d{1,2})(?:[:.](\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?(?!\s*(?:min|hour|day))""")
private val PartOfDayTime = mapOf(
    Regex("""\btonight\b""") to LocalTime.of(21, 0),
    Regex("""\b(this )?evening\b""") to LocalTime.of(18, 0),
    Regex("""\b(this )?afternoon\b""") to LocalTime.of(14, 0),
    Regex("""\b(this |tomorrow )?morning\b""") to LocalTime.of(9, 0),
    Regex("""\bnoon\b""") to LocalTime.NOON,
)
private val ReminderLead = Regex(
    """^\s*(please\s+)?(can you\s+)?(help me\s+(to\s+)?)?((set|add|create|make|put)\s+(up\s+)?(a\s+|me\s+a\s+)?reminder|remind me|reminder)\s*(to|about|for|that|of)?\s*""",
)
private val ReminderDay = Regex("""\b(today|tomorrow|tonight|(on\s+)?(next\s+)?(monday|tuesday|wednesday|thursday|friday|saturday|sunday))\b""")
private const val DEFAULT_HOUR = 9

/**
 * "remind me to call Amma at 6 pm", "set a reminder to pay rent tomorrow", "remind me in 2 hours to take
 * medicine". The time comes only from the user's words; with no time or nothing to remind about, NILA asks.
 * Null when the message isn't a reminder, or is a monthly one (handled by [monthlyReminderArgs]).
 */
fun oneOffReminder(text: String, now: LocalDateTime): NilaResponse? {
    val lower = text.lowercase().trim().trimEnd('.', '!', '?')
    if (!ReminderWord.containsMatchIn(lower) || EveryMonth.containsMatchIn(lower) || !ReminderLead.containsMatchIn(lower) ||
        Regex("""\b(cancel|stop|delete|remove)\b""").containsMatchIn(lower)
    ) return null
    val day = when {
        Regex("""\btomorrow\b""").containsMatchIn(lower) -> now.toLocalDate().plusDays(1)
        else -> java.time.DayOfWeek.entries.firstOrNull { Regex("""\b${it.name.lowercase()}\b""").containsMatchIn(lower) }
            ?.let { now.toLocalDate().with(java.time.temporal.TemporalAdjusters.next(it)) }
    }
    val at: LocalDateTime? = InTime.find(lower)?.let { m ->
        val n = m.groupValues[1].toLong()
        if (m.groupValues[2].startsWith("h")) now.plusHours(n) else now.plusMinutes(n)
    } ?: AtTime.find(lower)?.let { m ->
        val hour = m.groupValues[1].toInt()
        val minute = m.groupValues[2].toIntOrNull() ?: 0
        if (hour > 23 || minute > 59) return NilaResponse.Text("That time doesn't look right. Try \"at 6 pm\".")
        val hours = when (m.groupValues[3].replace(".", "")) {
            "am" -> listOf(hour % 12)
            "pm" -> listOf(hour % 12 + 12)
            // "at 6" with no am/pm: whichever 6 o'clock comes next.
            else -> if (hour > 12 || hour == 0) listOf(hour) else listOf(hour % 12, hour % 12 + 12)
        }
        val date = day ?: now.toLocalDate()
        hours.map { date.atTime(it, minute) }.firstOrNull { day != null || it.isAfter(now) }
            ?: date.plusDays(1).atTime(hours.first(), minute)
    } ?: PartOfDayTime.entries.firstOrNull { (words) -> words.containsMatchIn(lower) }?.let { (_, time) ->
        val date = day ?: if (lower.contains("tomorrow")) now.toLocalDate().plusDays(1) else now.toLocalDate()
        date.atTime(time).let { if (!it.isAfter(now)) it.plusDays(1) else it }
    } ?: day?.atTime(DEFAULT_HOUR, 0)

    val title = ReminderLead.replace(lower, "")
        .replace(InTime, " ").replace(AtTime, " ").replace(ReminderDay, " ")
        .let { t -> PartOfDayTime.keys.fold(t) { acc, words -> acc.replace(words, " ") } }
        .replace(Regex("""\b(to|about|for|me)\s*$"""), "").replace(Regex("""^\s*(to|about|me)\b"""), "")
        .replace(Regex("""\s+"""), " ").trim()
    return when {
        title.isBlank() && at == null -> NilaResponse.Text("Sure. What should I remind you about, and when? Like \"remind me to call Amma at 6 pm\".")
        at == null -> NilaResponse.Text("When should I remind you to $title? Like \"at 6 pm\", \"tomorrow at 9\" or \"in 2 hours\".")
        title.isBlank() -> NilaResponse.Text("What should I remind you about at ${"%02d:%02d".format(at.hour, at.minute)}?")
        else -> OneOffReminder(title.replaceFirstChar(Char::uppercase), at)
    }
}

