package com.nila.launcher

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.ZoneId
import java.util.UUID
import org.json.JSONArray
import org.json.JSONObject

/**
 * A My NILA reminder: once on [at] (a health follow-up), or every month on [monthlyDay] at [at]'s time
 * (the electricity bill). A day after the 28th repeats on the last day of the month.
 */
data class NilaReminder(
    val id: String,
    val title: String,
    val text: String,
    val at: LocalDateTime,
    val monthlyDay: Int? = null,
)

private const val LAST_SAFE_DAY = 28

/** The next time a reminder fires after [now]: [NilaReminder.at] itself, or the next month's day for a monthly one. */
internal fun nextFiring(reminder: NilaReminder, now: LocalDateTime): LocalDateTime? {
    val day = reminder.monthlyDay ?: return reminder.at.takeIf { it.isAfter(now) }
    fun inMonth(month: YearMonth) =
        (if (day > LAST_SAFE_DAY) month.atEndOfMonth() else month.atDay(minOf(day, month.lengthOfMonth()))).atTime(reminder.at.toLocalTime())
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
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(): List<NilaReminder> = prefs.getString(LIST, null)?.let(::decode).orEmpty().sortedBy { nextFiring(it, LocalDateTime.now()) ?: it.at }

    fun add(reminder: NilaReminder): List<NilaReminder> {
        val list = all().filterNot { it.id == reminder.id } + reminder
        write(list)
        schedule(reminder)
        return list
    }

    fun remove(id: String): List<NilaReminder> {
        alarms().cancel(pending(id))
        return all().filterNot { it.id == id }.also(::write)
    }

    /** After a restart Android has forgotten every alarm: put them all back. */
    fun rescheduleAll() = all().forEach(::schedule)

    /** Called when a reminder fires: a monthly one moves to next month, a one-off one is done. */
    fun fired(id: String) {
        val reminder = all().firstOrNull { it.id == id } ?: return
        if (reminder.monthlyDay != null) schedule(reminder) else write(all().filterNot { it.id == id })
    }

    fun find(id: String) = all().firstOrNull { it.id == id }

    private fun schedule(reminder: NilaReminder) {
        val next = nextFiring(reminder, LocalDateTime.now()) ?: return
        val millis = next.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        alarms().setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pending(reminder.id))
    }

    private fun alarms() = appContext.getSystemService(AlarmManager::class.java)

    private fun pending(id: String) = PendingIntent.getBroadcast(
        appContext,
        id.hashCode(),
        Intent(appContext, ReminderReceiver::class.java).setAction(ACTION).putExtra(EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun write(list: List<NilaReminder>) {
        prefs.edit().putString(LIST, JSONArray().apply {
            list.forEach { r ->
                put(JSONObject().put("id", r.id).put("title", r.title).put("text", r.text).put("at", r.at.toString()).put("monthlyDay", r.monthlyDay ?: 0))
            }
        }.toString()).apply()
    }

    private fun decode(raw: String): List<NilaReminder> {
        val list = runCatching { JSONArray(raw) }.getOrNull() ?: return emptyList()
        return (0 until list.length()).mapNotNull { i ->
            val o = list.optJSONObject(i) ?: return@mapNotNull null
            runCatching {
                NilaReminder(o.getString("id"), o.optString("title"), o.optString("text"), LocalDateTime.parse(o.getString("at")), o.optInt("monthlyDay").takeIf { it in 1..31 })
            }.getOrNull()
        }
    }

    companion object {
        private const val PREFS = "nila_reminders"
        private const val LIST = "reminders"
        const val ACTION = "com.nila.launcher.REMINDER"
        const val EXTRA_ID = "id"
        const val CHANNEL = "my_nila"
    }
}

/** Shows a My NILA reminder as a notification; tapping it opens NILA. */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val reminders = NilaReminders(context)
        val id = intent.getStringExtra(NilaReminders.EXTRA_ID) ?: return
        val reminder = reminders.find(id) ?: return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        manager.createNotificationChannel(NotificationChannel(NilaReminders.CHANNEL, "My NILA reminders", NotificationManager.IMPORTANCE_HIGH))
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = Notification.Builder(context, NilaReminders.CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(reminder.title)
            .setContentText(reminder.text)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(id.hashCode(), notification) }
        reminders.fired(id)
    }
}

/** Puts My NILA's reminders back after the phone restarts. */
class ReminderBootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) NilaReminders(context).rescheduleAll()
    }
}

/** The day a reminder next fires, for My NILA's list: "Thu 5 Nov, 10:00". */
internal fun reminderWhen(reminder: NilaReminder, now: LocalDateTime = LocalDateTime.now()): String {
    val next = nextFiring(reminder, now) ?: return "Done"
    return eventWhen(next.toLocalDate(), next.toLocalTime()) + if (reminder.monthlyDay != null) " · every month" else ""
}
