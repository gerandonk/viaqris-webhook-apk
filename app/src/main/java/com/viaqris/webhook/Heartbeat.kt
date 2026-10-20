package com.viaqris.webhook

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Konfigurasi Heartbeat Monitoring (viaQris `POST /api/v2/heartbeat`).
 *
 * Heartbeat memakai token dan (opsional) secret HMAC tersendiri, berbeda dari
 * webhook penerusan transaksi.
 */
data class HeartbeatConfig(
    val enabled: Boolean = false,
    val url: String = "",
    val token: String = "",
    val secret: String = "",
    val deviceId: String = "",
    val intervalSeconds: Int = 60,
    val lastSentAt: Long = 0L,
    val lastCode: Int = 0,
    val lastResponse: String = "",
    val totalSent: Int = 0,
    val totalFailed: Int = 0
)

object HeartbeatStore {
    const val PREFS_NAME = "viaqris_prefs"
    private const val KEY = "heartbeat_json"

    fun load(context: Context): HeartbeatConfig {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY, null) ?: return HeartbeatConfig()
        return try {
            val obj = JSONObject(raw)
            HeartbeatConfig(
                enabled = obj.optBoolean("enabled", false),
                url = obj.optString("url", ""),
                token = obj.optString("token", ""),
                secret = obj.optString("secret", ""),
                deviceId = obj.optString("deviceId", ""),
                intervalSeconds = obj.optInt("intervalSeconds", 60),
                lastSentAt = obj.optLong("lastSentAt", 0L),
                lastCode = obj.optInt("lastCode", 0),
                lastResponse = obj.optString("lastResponse", ""),
                totalSent = obj.optInt("totalSent", 0),
                totalFailed = obj.optInt("totalFailed", 0)
            )
        } catch (_: Exception) {
            HeartbeatConfig()
        }
    }

    fun save(context: Context, config: HeartbeatConfig) {
        val obj = JSONObject().apply {
            put("enabled", config.enabled)
            put("url", config.url)
            put("token", config.token)
            put("secret", config.secret)
            put("deviceId", config.deviceId)
            put("intervalSeconds", config.intervalSeconds)
            put("lastSentAt", config.lastSentAt)
            put("lastCode", config.lastCode)
            put("lastResponse", config.lastResponse)
            put("totalSent", config.totalSent)
            put("totalFailed", config.totalFailed)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, obj.toString())
            .apply()
    }

    /** Perbarui hanya hasil pengiriman terakhir tanpa menyentuh konfigurasi lain. */
    fun record(context: Context, code: Int, response: String, success: Boolean) {
        val current = load(context)
        save(
            context,
            current.copy(
                lastSentAt = System.currentTimeMillis(),
                lastCode = code,
                lastResponse = response.take(350),
                totalSent = if (success) current.totalSent + 1 else current.totalSent,
                totalFailed = if (success) current.totalFailed else current.totalFailed + 1
            )
        )
    }
}

object HeartbeatSender {
    /**
     * Mengirim satu heartbeat. Bila `secret` diisi, ditandatangani HMAC-SHA256
     * atas `timestamp.body` sesuai yang diterima server viaQris.
     */
    fun send(config: HeartbeatConfig): Pair<Int, String> {
        if (config.url.isBlank() || config.token.isBlank()) {
            return -1 to "URL / Token Heartbeat belum diisi"
        }

        val bodyObj = JSONObject().apply {
            put("device_id", config.deviceId.ifBlank { Build.MODEL })
            put("app_version", BuildConfig.VERSION_NAME)
            put("listener_connected", true)
            put("battery_optimized", false)
            put("sent_at", System.currentTimeMillis() / 1000)
        }
        val body = bodyObj.toString()

        var statusCode = 0
        var responseText = ""
        try {
            val conn = (URL(buildEndpoint(config)).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 15000
                doOutput = true
                doInput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-Heartbeat-Token", config.token)
                if (config.secret.isNotEmpty()) {
                    val ts = (System.currentTimeMillis() / 1000).toString()
                    val sig = hmacSha256(config.secret, "$ts.$body")
                    setRequestProperty("X-Viaqris-Timestamp", ts)
                    setRequestProperty("X-Viaqris-Signature", "sha256=$sig")
                }
            }

            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
                writer.write(body)
                writer.flush()
            }

            statusCode = conn.responseCode
            val stream = if (statusCode in 200..299) conn.inputStream else conn.errorStream
            if (stream != null) {
                BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                    responseText = reader.readText().take(350)
                }
            }
            conn.disconnect()
        } catch (e: Exception) {
            statusCode = -1
            responseText = e.message ?: "Network Error"
        }
        return statusCode to responseText
    }

    private fun buildEndpoint(config: HeartbeatConfig): String {
        val base = config.url.trim().trimEnd('/')
        val endpoint = if (base.endsWith("/api/v2/heartbeat")) base else "$base/api/v2/heartbeat"
        return "$endpoint?token=${config.token}"
    }

    private fun hmacSha256(secret: String, data: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(data.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    }

    fun generateSecret(length: Int = 32): String {
        val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
        val rnd = SecureRandom()
        return (1..length).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
    }
}

/**
 * Foreground service yang mengirim heartbeat secara berkala sesuai interval
 * konfigurasi. Berjalan independen dari listener notifikasi.
 */
class HeartbeatService : Service() {
    private var scheduler: ScheduledExecutorService? = null
    private var task: ScheduledFuture<*>? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundCompat()
        scheduleNext()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundCompat()
        // Jadwal ulang agar perubahan interval langsung berlaku.
        task?.cancel(false)
        scheduleNext()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        task?.cancel(true)
        scheduler?.shutdownNow()
        scheduler = null
        super.onDestroy()
    }

    private fun scheduleNext() {
        val config = HeartbeatStore.load(this)
        if (!config.enabled) {
            stopSelf()
            return
        }

        val interval = config.intervalSeconds.coerceAtLeast(30).toLong()
        val exec = scheduler ?: Executors.newSingleThreadScheduledExecutor().also { scheduler = it }
        task = exec.scheduleAtFixedRate({
            val current = HeartbeatStore.load(this)
            if (!current.enabled) {
                stopSelf()
                return@scheduleAtFixedRate
            }
            val (code, resp) = HeartbeatSender.send(current)
            HeartbeatStore.record(this, code, resp, code in 200..299)
        }, 0, interval, TimeUnit.SECONDS)
    }

    private fun startForegroundCompat() {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    CHANNEL_ID,
                    "Heartbeat ViaQRIS",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Mengirim sinyal heartbeat berkala ke server viaQris"
                    setShowBadge(false)
                }
                manager.createNotificationChannel(channel)
            }

            val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java), pendingFlags
            )

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("Heartbeat ViaQRIS Aktif")
                .setContentText("Mengirim sinyal berkala ke server viaQris")
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIF_ID, notification)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    companion object {
        private const val CHANNEL_ID = "viaqris_heartbeat_channel"
        private const val NOTIF_ID = 7789

        /** Mulai atau hentikan service sesuai status `enabled` di konfigurasi. */
        fun sync(context: Context) {
            val config = HeartbeatStore.load(context)
            val intent = Intent(context, HeartbeatService::class.java)
            if (config.enabled && config.url.isNotBlank() && config.token.isNotBlank()) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } else {
                context.stopService(intent)
            }
        }

        @Suppress("unused")
        fun isBatteryOptimized(context: Context): Boolean {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                !pm.isIgnoringBatteryOptimizations(context.packageName)
            } else false
        }
    }
}
