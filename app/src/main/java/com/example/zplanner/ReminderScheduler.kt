package com.example.zplanner

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import com.example.zplanner.data.ApiEvent
import com.example.zplanner.data.ApiTask
import javax.inject.Inject
import javax.inject.Singleton

private const val CHANNEL_ID = "zplanner_reminders"
private const val PREFS = "zplanner_reminders"
private const val SCHEDULES = "schedules"
private const val ACTION_REMINDER = "com.example.zplanner.REMINDER"

@Singleton
class ReminderScheduler @Inject constructor(private val context: Context) {
    private val alarmManager = context.getSystemService(AlarmManager::class.java)
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(CHANNEL_ID, "Nhắc việc", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Thông báo cho công việc và sự kiện sắp đến"
            }
            context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
    }

    fun schedule(tasks: List<ApiTask>, events: List<ApiEvent>, account: String, enabled: Boolean) {
        cancelAll()
        if (!enabled || account.isBlank()) return
        ensureChannel()
        val records = JSONArray()
        tasks.forEach { task ->
            val trigger = runCatching {
                LocalDateTime.parse("${task.date} ${task.startTime}", DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"))
                    .atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            }.getOrNull() ?: return@forEach
            if (trigger > System.currentTimeMillis()) {
                val record = JSONObject().apply {
                    put("id", "task_${task.id}"); put("title", task.title); put("text", "Bắt đầu công việc lúc ${task.startTime}")
                    put("trigger", trigger); put("account", account)
                }
                scheduleRecord(record); records.put(record)
            }
        }
        events.forEach { event ->
            val trigger = runCatching { Instant.parse(event.startAt).minusSeconds(event.reminderMinutes.toLong() * 60).toEpochMilli() }.getOrNull() ?: return@forEach
            if (trigger > System.currentTimeMillis()) {
                val record = JSONObject().apply {
                    put("id", "event_${event.id}"); put("title", event.title); put("text", if (event.reminderMinutes > 0) "Sắp diễn ra sau ${event.reminderMinutes} phút" else "Sự kiện bắt đầu ngay bây giờ")
                    put("trigger", trigger); put("account", account)
                }
                scheduleRecord(record); records.put(record)
            }
        }
        prefs.edit().putString(SCHEDULES, records.toString()).apply()
    }

    fun cancelAll() {
        val records = readRecords()
        records.forEach { cancelRecord(it) }
        prefs.edit().remove(SCHEDULES).apply()
    }

    fun restore() {
        ensureChannel()
        readRecords().forEach { record ->
            if (record.optLong("trigger") > System.currentTimeMillis()) scheduleRecord(record) else cancelRecord(record)
        }
    }

    private fun readRecords(): List<JSONObject> {
        val raw = prefs.getString(SCHEDULES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).map { array.getJSONObject(it) }
        }.getOrDefault(emptyList())
    }

    private fun requestCode(id: String): Int = id.hashCode() and 0x7fffffff

    private fun pendingIntent(record: JSONObject): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            action = ACTION_REMINDER
            putExtra("id", record.optString("id")); putExtra("title", record.optString("title")); putExtra("text", record.optString("text"))
        }
        return PendingIntent.getBroadcast(context, requestCode(record.optString("id")), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    private fun scheduleRecord(record: JSONObject) {
        val trigger = record.optLong("trigger")
        if (trigger <= System.currentTimeMillis()) return
        runCatching { alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, trigger, pendingIntent(record)) }
    }

    private fun cancelRecord(record: JSONObject) {
        runCatching { alarmManager.cancel(pendingIntent(record)) }
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Intent.ACTION_BOOT_COMPLETED || intent.action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            ReminderScheduler(context).restore()
            return
        }
        if (intent.action != ACTION_REMINDER) return
        ReminderScheduler(context).ensureChannel()
        if (Build.VERSION.SDK_INT >= 33 && context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) return
        val id = intent.getStringExtra("id") ?: return
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(com.example.zplanner.R.drawable.ic_task)
            .setContentTitle(intent.getStringExtra("title") ?: "ZPlanner")
            .setContentText(intent.getStringExtra("text") ?: "Đến giờ nhắc việc")
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .build()
        if (Build.VERSION.SDK_INT < 33 || context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            NotificationManagerCompat.from(context).notify(id.hashCode() and 0x7fffffff, notification)
        }
    }
}
