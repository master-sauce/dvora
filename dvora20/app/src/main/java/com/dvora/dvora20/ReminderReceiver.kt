package com.dvora.dvora20

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat

class ReminderReceiver : BroadcastReceiver() {
    companion object {
        const val CHANNEL_ID       = "dvora_reminders"
        const val EXTRA_TITLE      = "extra_title"
        const val EXTRA_IMDB_ID    = "extra_imdb_id"
        const val EXTRA_IMDB_URL   = "extra_imdb_url"
        const val EXTRA_MEDIA_TYPE = "extra_media_type"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val title     = intent.getStringExtra(EXTRA_TITLE) ?: "Reminder"
        val imdbId    = intent.getStringExtra(EXTRA_IMDB_ID) ?: ""
        val imdbUrl   = intent.getStringExtra(EXTRA_IMDB_URL) ?: "https://www.imdb.com"
        val mediaType = intent.getStringExtra(EXTRA_MEDIA_TYPE)

        createChannel(context)

        val emoji = if (mediaType?.contains("tv", ignoreCase = true) == true ||
            mediaType?.contains("series", ignoreCase = true) == true) "📺" else "🎬"

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, imdbId.hashCode(), openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("🐝 Dvora Reminder")
            .setContentText("$emoji $title")
            .setStyle(NotificationCompat.BigTextStyle().bigText("$emoji $title\nTap to open"))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setSilent(true)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(imdbId.hashCode(), notification)

        BookmarksManager.load(context)
        BookmarksManager.clearReminderSilent(context, imdbId)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Dvora Reminders",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Silent reminders for bookmarked movies and shows"
                setSound(null, null)
                enableVibration(false)
                enableLights(false)
            }
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }
}

class BootReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
            BookmarksManager.load(context)
            ReminderHelper.rescheduleAll(context, BookmarksManager.bookmarks)
        }
    }
}

object ReminderHelper {

    fun schedule(context: Context, bm: Bookmark, triggerAtMillis: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_TITLE, bm.title)
            putExtra(ReminderReceiver.EXTRA_IMDB_ID, bm.imdbId)
            putExtra(ReminderReceiver.EXTRA_IMDB_URL, bm.imdbUrl)
            putExtra(ReminderReceiver.EXTRA_MEDIA_TYPE, bm.mediaType)
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context, bm.imdbId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
                }
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            } else {
                alarmManager.setExact(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
            }
        } catch (_: SecurityException) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    fun cancel(context: Context, imdbId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, imdbId.hashCode(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    fun rescheduleAll(context: Context, bookmarks: List<Bookmark>) {
        val now = System.currentTimeMillis()
        bookmarks.forEach { bm ->
            val dateStr = bm.reminderDate ?: return@forEach
            val timeStr = bm.reminderTime ?: "09:00"
            val triggerAt = datetimeToMillis(dateStr, timeStr)
            if (triggerAt > now) {
                schedule(context, bm, triggerAt)
            }
        }
    }

    fun datetimeToMillis(dateStr: String, timeStr: String): Long {
        val date  = java.time.LocalDate.parse(dateStr)
        val parts = timeStr.split(":")
        val hour   = parts[0].toInt()
        val minute = parts[1].toInt()
        val dateTime = date.atTime(hour, minute)
        return dateTime.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
    }
}