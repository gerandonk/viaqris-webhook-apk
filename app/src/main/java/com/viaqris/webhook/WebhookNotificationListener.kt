package com.viaqris.webhook

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.core.app.NotificationCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

class WebhookNotificationListener : NotificationListenerService() {
    private val PREFS_NAME = "viaqris_prefs"
    private val CHANNEL_ID = "viaqris_foreground_channel"
    private val NOTIF_ID = 7788
    private val executor = Executors.newCachedThreadPool()
    private val recentNotifications = LinkedHashMap<String, Long>()

    override fun onCreate() {
        super.onCreate()
        startForegroundServiceCompat()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundServiceCompat()
        return Service.START_STICKY
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        startForegroundServiceCompat()
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        requestRebindSafe()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        startForegroundServiceCompat()
        requestRebindSafe()
    }

    private fun requestRebindSafe() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                requestRebind(ComponentName(this, WebhookNotificationListener::class.java))
            } catch (_: Exception) {}
        }
    }

    private fun startForegroundServiceCompat() {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "ViaQRIS Webhook Service",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Menjaga pemantauan notifikasi pembayaran tetap aktif 24 jam"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }

            val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }

            val openAppIntent = Intent(this, MainActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(this, 0, openAppIntent, pendingFlags)

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("ViaQRIS Webhook Aktif (24 Jam)")
                .setContentText("Memantau notifikasi pembayaran secara real-time...")
                .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(
                    NOTIF_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        if (sbn == null || sbn.isOngoing) return
        val notification = sbn.notification ?: return
        if ((notification.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return

        val pkg = sbn.packageName ?: return
        if (pkg == packageName) return

        val extras = notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim() ?: ""
        val bigText = extras.getCharSequence(Notification.EXTRA_BIG_TEXT)?.toString()?.trim()
        val normalText = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()?.trim() ?: ""
        val bodyText = if (!bigText.isNullOrEmpty()) bigText else normalText

        if (title.isEmpty() && bodyText.isEmpty()) return

        val dedupKey = "$pkg|$title|$bodyText"
        val now = System.currentTimeMillis()
        synchronized(recentNotifications) {
            val lastTime = recentNotifications[dedupKey]
            if (lastTime != null && (now - lastTime) < 4000) return
            recentNotifications[dedupKey] = now
            if (recentNotifications.size > 60) {
                val eldest = recentNotifications.entries.iterator().next()
                recentNotifications.remove(eldest.key)
            }
        }

        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val webhooksJsonStr = prefs.getString("webhooks_json", "[]") ?: "[]"
        val webhooksArray = try {
            JSONArray(webhooksJsonStr)
        } catch (e: Exception) {
            return
        }

        for (i in 0 until webhooksArray.length()) {
            val webhook = webhooksArray.optJSONObject(i) ?: continue
            if (!webhook.optBoolean("enabled", true)) continue

            val packagesArray = webhook.optJSONArray("selectedPackages") ?: JSONArray()
            var isPackageMatched = false
            for (p in 0 until packagesArray.length()) {
                if (packagesArray.optString(p) == pkg) {
                    isPackageMatched = true
                    break
                }
            }
            if (!isPackageMatched) continue

            val filterKeyword = webhook.optString("filterKeyword", "").trim()
            val combinedText = "$title $bodyText"
            if (filterKeyword.isNotEmpty()) {
                val keywords = filterKeyword.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                val hasMatch = keywords.any { kw -> combinedText.contains(kw, ignoreCase = true) }
                if (!hasMatch) continue
            }

            val includeTitle = webhook.optBoolean("includeTitle", false)
            val kunciEntri = if (includeTitle && title.isNotEmpty()) {
                "$title - $bodyText"
            } else {
                bodyText
            }

            val webhookName = webhook.optString("name", "Webhook")
            val url = webhook.optString("url", "").trim()
            val apiToken = webhook.optString("apiToken", "").trim()
            val aksi = webhook.optString("aksi", "updateStatus").ifEmpty { "updateStatus" }
            val statusBaru = webhook.optString("statusBaru", "Berhasil").ifEmpty { "Berhasil" }

            if (url.isEmpty()) continue

            executor.execute {
                sendWebhookPost(
                    webhookName = webhookName,
                    targetUrl = url,
                    apiToken = apiToken,
                    aksi = aksi,
                    kunciEntri = kunciEntri,
                    statusBaru = statusBaru,
                    packageName = pkg
                )
            }
        }
    }

    private fun sendWebhookPost(
        webhookName: String,
        targetUrl: String,
        apiToken: String,
        aksi: String,
        kunciEntri: String,
        statusBaru: String,
        packageName: String
    ) {
        var statusCode = 0
        var responseText = ""

        val payloadJson = JSONObject().apply {
            put("aksi", aksi)
            put("kunciEntri", kunciEntri)
            put("statusBaru", statusBaru)
        }

        try {
            val url = URL(targetUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 15000
                doOutput = true
                doInput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                if (apiToken.isNotEmpty()) {
                    setRequestProperty("X-API-Token", apiToken)
                }
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(payloadJson.toString())
                writer.flush()
            }

            statusCode = conn.responseCode
            val stream = if (statusCode in 200..299) conn.inputStream else conn.errorStream
            if (stream != null) {
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    responseText = reader.readText().take(300)
                }
            }
            conn.disconnect()
        } catch (e: Exception) {
            statusCode = -1
            responseText = e.message ?: "Network Error"
        }

        saveLog(webhookName, packageName, kunciEntri, statusCode, responseText)
    }

    private fun saveLog(
        webhookName: String,
        packageName: String,
        kunciEntri: String,
        statusCode: Int,
        response: String
    ) {
        try {
            val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            synchronized(this) {
                val logsStr = prefs.getString("logs_json", "[]") ?: "[]"
                val logsArray = try {
                    JSONArray(logsStr)
                } catch (e: Exception) {
                    JSONArray()
                }

                val timeStr = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault()).format(Date())
                val newEntry = JSONObject().apply {
                    put("time", timeStr)
                    put("webhookName", webhookName)
                    put("packageName", packageName)
                    put("kunciEntri", kunciEntri)
                    put("statusCode", statusCode)
                    put("response", response)
                }

                val updatedArray = JSONArray()
                updatedArray.put(newEntry)
                val limit = minOf(logsArray.length(), 49)
                for (i in 0 until limit) {
                    updatedArray.put(logsArray.get(i))
                }

                prefs.edit().putString("logs_json", updatedArray.toString()).apply()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
