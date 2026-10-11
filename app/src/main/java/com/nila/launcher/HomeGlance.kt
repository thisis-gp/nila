package com.nila.launcher

import android.Manifest
import android.app.AlarmManager
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.AlarmClock
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A calendar event for home's glance: [id] opens it in the calendar app. */
internal data class GlanceEvent(val id: Long, val title: String, val start: LocalDateTime)

internal enum class GlanceIcon { Calendar, Reminder, Alarm, Steps }

internal sealed interface GlanceTarget {
    data class Event(val event: GlanceEvent) : GlanceTarget
    data object Alarms : GlanceTarget
    data object MyNila : GlanceTarget
}

internal data class GlanceLine(val icon: GlanceIcon, val text: String, val target: GlanceTarget)

internal enum class ClockStyle(val pattern: String) { Hours12("h:mm"), Hours24("HH:mm") }

/** Everything home can glance at, read off the main thread. Missing data is null or empty. */
internal data class GlanceData(
    val events: List<GlanceEvent> = emptyList(),
    val reminders: List<NilaReminder> = emptyList(),
    val alarm: LocalDateTime? = null,
    val steps: Int? = null,
)

private const val MAX_GLANCE_LINES = 2
private const val EVENT_WINDOW_HOURS = 12L
private const val COUNTDOWN_UNDER_HOURS = 3L
private const val ALARM_WINDOW_HOURS = 24L
private const val MINUTES_PER_HOUR = 60L

/**
 * The lines under home's greeting, most useful first, at most two: the next calendar event (12 hours),
 * the next My NILA reminder (today or tomorrow), the next alarm (24 hours), then steps so far today.
 * A line appears only when its data exists.
 */
internal fun glanceLines(data: GlanceData, now: LocalDateTime, clock: ClockStyle = ClockStyle.Hours24): List<GlanceLine> {
    val time = DateTimeFormatter.ofPattern(clock.pattern)
    fun dayWord(at: LocalDateTime) = when (at.toLocalDate()) {
        now.toLocalDate() -> ""
        now.toLocalDate().plusDays(1) -> " tomorrow"
        else -> " ${at.format(DateTimeFormatter.ofPattern("EEE"))}"
    }
    val event = data.events
        .filter { !it.start.isBefore(now) && it.start.isBefore(now.plusHours(EVENT_WINDOW_HOURS)) }
        .minByOrNull { it.start }
        ?.let { e ->
            val minutes = Duration.between(now, e.start).toMinutes()
            val countdown = if (minutes < COUNTDOWN_UNDER_HOURS * MINUTES_PER_HOUR) " · ${countdown(minutes)}" else ""
            GlanceLine(GlanceIcon.Calendar, "${e.title} at ${e.start.format(time)}$countdown", GlanceTarget.Event(e))
        }
    val endOfTomorrow = now.toLocalDate().plusDays(2).atStartOfDay()
    val reminder = data.reminders
        .mapNotNull { r -> nextFiring(r, now)?.takeIf { it.isBefore(endOfTomorrow) }?.let { r to it } }
        .minByOrNull { it.second }
        ?.let { (r, at) -> GlanceLine(GlanceIcon.Reminder, "${r.title}${dayWord(at)} at ${at.format(time)}", GlanceTarget.MyNila) }
    val alarm = data.alarm
        ?.takeIf { it.isAfter(now) && it.isBefore(now.plusHours(ALARM_WINDOW_HOURS)) }
        ?.let { GlanceLine(GlanceIcon.Alarm, "Alarm ${it.format(time)}${dayWord(it)}", GlanceTarget.Alarms) }
    val steps = data.steps?.takeIf { it > 0 }
        ?.let { GlanceLine(GlanceIcon.Steps, "${String.format(Locale.US, "%,d", it)} steps today", GlanceTarget.MyNila) }
    return listOfNotNull(event, reminder, alarm, steps).take(MAX_GLANCE_LINES)
}

/** "in 1 h 22 m", "in 2 h", "in 45 m", or "now" inside the last minute. */
internal fun countdown(minutes: Long): String {
    val h = minutes / MINUTES_PER_HOUR
    val m = minutes % MINUTES_PER_HOUR
    return when {
        minutes < 1 -> "now"
        h == 0L -> "in $m m"
        m == 0L -> "in $h h"
        else -> "in $h h $m m"
    }
}

private fun granted(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

/** Reads calendar, reminders, alarm and steps. Asks for no permission: ungranted sources are skipped. */
internal suspend fun loadGlance(context: Context, now: LocalDateTime): GlanceData = withContext(Dispatchers.IO) {
    val zone = ZoneId.systemDefault()
    val alarm = context.getSystemService(AlarmManager::class.java)?.nextAlarmClock?.let {
        LocalDateTime.ofInstant(Instant.ofEpochMilli(it.triggerTime), zone)
    }
    val steps = if (granted(context, Manifest.permission.ACTIVITY_RECOGNITION)) {
        FitnessStore(context).steps()?.takeIf { it.day == LocalDate.now() }?.let(::stepsToday)
    } else null
    GlanceData(
        events = if (granted(context, Manifest.permission.READ_CALENDAR)) upcomingEvents(context, now) else emptyList(),
        reminders = runCatching { NilaReminders(context).all() }.getOrDefault(emptyList()),
        alarm = alarm,
        steps = steps,
    )
}

private fun upcomingEvents(context: Context, now: LocalDateTime): List<GlanceEvent> = runCatching {
    val zone = ZoneId.systemDefault()
    val from = now.atZone(zone).toInstant().toEpochMilli()
    val to = now.plusHours(EVENT_WINDOW_HOURS).atZone(zone).toInstant().toEpochMilli()
    val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().also {
        ContentUris.appendId(it, from)
        ContentUris.appendId(it, to)
    }.build()
    val projection = arrayOf(CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY)
    buildList {
        context.contentResolver.query(uri, projection, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
            while (c.moveToNext()) {
                if (c.getInt(3) == 1) continue
                add(GlanceEvent(c.getLong(0), c.getString(1)?.takeIf(String::isNotBlank) ?: "Event", LocalDateTime.ofInstant(Instant.ofEpochMilli(c.getLong(2)), zone)))
            }
        }
    }
}.getOrDefault(emptyList())

/** The intent a glance line opens, or null for My NILA (handled by the caller). */
internal fun glanceIntent(target: GlanceTarget): Intent? = when (target) {
    is GlanceTarget.Event -> Intent(Intent.ACTION_VIEW, ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, target.event.id))
        .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, target.event.start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
    GlanceTarget.Alarms -> Intent(AlarmClock.ACTION_SHOW_ALARMS)
    GlanceTarget.MyNila -> null
}
