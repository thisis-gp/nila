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
            context.getSystemService(NotificationManager::class.java)?.cancel(id.hashCode())
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
