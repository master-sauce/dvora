package com.dvora.dvora20

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import androidx.core.app.NotificationCompat

fun imdbRequestCode(imdbId: String): Int =
    imdbId.removePrefix("tt").toIntOrNull() ?: imdbId.hashCode()

class ReminderReceiver : BroadcastReceiver() {
    companion object {
        const val CHANNEL_ID       = "dvora_reminders"
        const val EXTRA_TITLE      = "extra_title"
        const val EXTRA_IMDB_ID    = "extra_imdb_id"
        const val EXTRA_IMDB_URL   = "extra_imdb_url"
        const val EXTRA_MEDIA_TYPE = "extra_media_type"
        const val EXTRA_RECURRENCE = "extra_recurrence"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val title      = intent.getStringExtra(EXTRA_TITLE)      ?: "Reminder"
        val imdbId     = intent.getStringExtra(EXTRA_IMDB_ID)    ?: ""
        val mediaType  = intent.getStringExtra(EXTRA_MEDIA_TYPE)
        val recurrence = intent.getStringExtra(EXTRA_RECURRENCE) ?: "ONCE"

        createChannel(context)

        val emoji = if (
            mediaType?.contains("tv",     ignoreCase = true) == true ||
            mediaType?.contains("series", ignoreCase = true) == true
        ) "📺" else "🎬"

        val recLabel = when (recurrence) {
            "DAILY"   -> "  🔁 Daily"
            "WEEKLY"  -> "  🔁 Weekly"
            "MONTHLY" -> "  🔁 Monthly"
            else      -> ""
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            imdbRequestCode(imdbId),
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_bee_notification)
            .setLargeIcon(emojiBitmap(context, "🐝", 64))
            .setContentTitle("🐝 Dvora Reminder")
            .setContentText("$emoji $title$recLabel")
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText("$emoji $title$recLabel\nTap to open Dvora")
            )
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setSound(soundUri)
            .build()

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(imdbRequestCode(imdbId), notification)

        BookmarksManager.load(context)
        if (recurrence == "ONCE") {
            BookmarksManager.clearReminderSilent(context, imdbId)
        } else {
            BookmarksManager.advanceRecurringReminder(context, imdbId)
        }
    }

    private fun emojiBitmap(context: Context, emoji: String, sizeDp: Int): Bitmap {
        val scale  = context.resources.displayMetrics.density
        val sizePx = (sizeDp * scale).toInt()
        val bmp    = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        val paint  = Paint().apply {
            isAntiAlias = true
            textSize    = sizePx * 0.75f
            textAlign   = Paint.Align.CENTER
        }
        canvas.drawText(emoji, sizePx / 2f, sizePx * 0.85f, paint)
        return bmp
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()

            val channel = NotificationChannel(
                CHANNEL_ID,
                "Dvora Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Reminders for bookmarked movies and shows"
                setSound(soundUri, audioAttributes)
                enableVibration(true)
                enableLights(true)
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
            BookmarksManager.rescheduleAllReminders(context)
        }
    }
}

object ReminderHelper {

    fun schedule(context: Context, bm: Bookmark, triggerAtMillis: Long) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_TITLE,      bm.title)
            putExtra(ReminderReceiver.EXTRA_IMDB_ID,    bm.imdbId)
            putExtra(ReminderReceiver.EXTRA_IMDB_URL,   bm.imdbUrl)
            putExtra(ReminderReceiver.EXTRA_MEDIA_TYPE, bm.mediaType)
            putExtra(ReminderReceiver.EXTRA_RECURRENCE, bm.reminderRecurrence ?: "ONCE")
        }
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            imdbRequestCode(bm.imdbId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        try {
            when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                    if (alarmManager.canScheduleExactAlarms()) {
                        alarmManager.setExactAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent
                        )
                    } else {
                        alarmManager.setAndAllowWhileIdle(
                            AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent
                        )
                    }
                }
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.M -> {
                    alarmManager.setExactAndAllowWhileIdle(
                        AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent
                    )
                }
                else -> {
                    alarmManager.setExact(
                        AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent
                    )
                }
            }
        } catch (_: SecurityException) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAtMillis, pendingIntent)
        }
    }

    fun cancel(context: Context, imdbId: String) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val intent = Intent(context, ReminderReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            imdbRequestCode(imdbId),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        alarmManager.cancel(pendingIntent)
    }

    fun datetimeToMillis(dateStr: String, timeStr: String): Long {
        val date   = java.time.LocalDate.parse(dateStr)
        val parts  = timeStr.split(":")
        val hour   = parts[0].toInt()
        val minute = parts[1].toInt()
        return date.atTime(hour, minute)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant().toEpochMilli()
    }

    fun computeNextDate(currentDate: String, recurrence: String): java.time.LocalDate {
        val d = java.time.LocalDate.parse(currentDate)
        return when (recurrence) {
            "DAILY"   -> d.plusDays(1)
            "WEEKLY"  -> d.plusWeeks(1)
            "MONTHLY" -> d.plusMonths(1)
            else      -> d
        }
    }
}