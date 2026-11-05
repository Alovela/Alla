package ru.mishka.app

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.app.Notification
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

object ReminderStore {
    private const val PREFS = "mishka"
    fun save(ctx: Context, json: String) =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("reminders", json).apply()
    fun load(ctx: Context): JSONArray = try {
        JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("reminders", "[]"))
    } catch (e: Exception) { JSONArray() }
}

object Scheduler {
    private fun parseTime(s: String?): LocalTime = try {
        val p = (s ?: "09:00").split(":"); LocalTime.of(p[0].toInt(), p[1].toInt())
    } catch (e: Exception) { LocalTime.of(9, 0) }

    /** Ближайшее срабатывание напоминания строго после [after], или null. */
    fun nextFire(r: JSONObject, after: LocalDateTime): LocalDateTime? {
        val time = parseTime(r.optString("time"))
        return when (r.optString("kind")) {
            "daily" -> {
                val t = after.toLocalDate().atTime(time)
                if (t.isAfter(after)) t else t.plusDays(1)
            }
            "once" -> try {
                val t = LocalDate.parse(r.optString("date")).atTime(time)
                if (t.isAfter(after)) t else null
            } catch (e: Exception) { null }
            "yearly" -> {
                val m = r.optInt("m", 1); val d = r.optInt("d", 1); val off = r.optLong("offset", 0)
                for (y in after.year..after.year + 1) {
                    try {
                        val t = LocalDate.of(y, m, minOf(d, LocalDate.of(y, m, 1).lengthOfMonth()))
                            .plusDays(off).atTime(time)
                        if (t.isAfter(after)) return t
                    } catch (e: Exception) {}
                }
                null
            }
            else -> null
        }
    }

    fun scheduleNext(ctx: Context) {
        val list = ReminderStore.load(ctx)
        val now = LocalDateTime.now()
        var best: LocalDateTime? = null
        for (i in 0 until list.length()) {
            val t = nextFire(list.getJSONObject(i), now) ?: continue
            if (best == null || t.isBefore(best)) best = t
        }
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = PendingIntent.getBroadcast(
            ctx, 1, Intent(ctx, AlarmReceiver::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        am.cancel(pi)
        val at = best ?: return
        val ms = at.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        ctx.getSharedPreferences("mishka", Context.MODE_PRIVATE).edit().putLong("nextAt", ms).apply()
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, ms, pi)
    }
}

object Notifier {
    const val CHANNEL = "mishka_reminders"
    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, "Напоминания мишки", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "Вода, лекарства, события и дни рождения"
            ch.enableVibration(true)
            nm.createNotificationChannel(ch)
        }
    }
    fun show(ctx: Context, id: Int, title: String, text: String) {
        ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n = Notification.Builder(ctx, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setColor(0xFFE0314B.toInt())
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(id, n) } catch (e: SecurityException) {}
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val prefs = ctx.getSharedPreferences("mishka", Context.MODE_PRIVATE)
        val planned = prefs.getLong("nextAt", 0L)
        val now = LocalDateTime.now()
        // всё, что должно было сработать за последние 3 минуты до запланированного момента
        val from = java.time.Instant.ofEpochMilli(planned).atZone(ZoneId.systemDefault()).toLocalDateTime().minusMinutes(3)
        val list = ReminderStore.load(ctx)
        for (i in 0 until list.length()) {
            val r = list.getJSONObject(i)
            val t = Scheduler.nextFire(r, from) ?: continue
            if (!t.isAfter(now.plusMinutes(1))) {
                Notifier.show(ctx, r.optString("id").hashCode(), r.optString("title"), r.optString("text"))
            }
        }
        Scheduler.scheduleNext(ctx)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) = Scheduler.scheduleNext(ctx)
}
