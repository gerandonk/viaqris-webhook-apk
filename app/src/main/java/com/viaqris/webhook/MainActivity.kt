@file:OptIn(ExperimentalMaterial3Api::class)

package com.viaqris.webhook

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors

data class WebhookItem(
    val id: String,
    val name: String,
    val url: String,
    val apiToken: String,
    val aksi: String = "updateStatus",
    val statusBaru: String = "Berhasil",
    val includeTitle: Boolean = false,
    val filterKeyword: String = "",
    val selectedPackages: List<String> = emptyList(),
    val enabled: Boolean = true,
    val successCount: Int = 0,
    val failCount: Int = 0
)

data class InstalledAppItem(
    val appName: String,
    val packageName: String,
    val isSystem: Boolean,
    val icon: ImageBitmap?
)

data class LogItem(
    val time: String,
    val webhookName: String,
    val packageName: String,
    val kunciEntri: String,
    val statusCode: Int,
    val status: String,
    val response: String
)

class MainActivity : ComponentActivity() {
    private val PREFS_NAME = "viaqris_prefs"
    private val executor = Executors.newCachedThreadPool()
    private val mainHandler = Handler(Looper.getMainLooper())

    private var notifListenerGranted by mutableStateOf(false)
    private var postNotifGranted by mutableStateOf(false)
    private var batteryIgnored by mutableStateOf(false)
    private var webhooks by mutableStateOf(listOf<WebhookItem>())
    private var logs by mutableStateOf(listOf<LogItem>())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        refreshAllState()

        setContent {
            val currentDensity = LocalDensity.current
            val safeDensity = Density(
                density = currentDensity.density,
                fontScale = currentDensity.fontScale.coerceIn(0.85f, 1.15f)
            )

            CompositionLocalProvider(LocalDensity provides safeDensity) {
                MaterialTheme(
                    colorScheme = lightColorScheme(
                        primary = Color(0xFF0F62FE),
                        secondary = Color(0xFF0043CE)
                    )
                ) {
                    MainAppScreen()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAllState()
    }

    private fun refreshAllState() {
        notifListenerGranted = isNotificationServiceEnabled()
        postNotifGranted = isPostNotificationGranted()
        batteryIgnored = isBatteryOptimizationIgnored()
        if (notifListenerGranted) {
            rebindListenerService()
        }
        loadWebhooksFromPrefs()
        loadLogsFromPrefs()
    }

    private fun rebindListenerService() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try {
                NotificationListenerService.requestRebind(
                    ComponentName(this, WebhookNotificationListener::class.java)
                )
            } catch (_: Exception) {}
        }
    }

    private fun isNotificationServiceEnabled(): Boolean {
        val flat = Settings.Secure.getString(contentResolver, "enabled_notification_listeners")
        if (!flat.isNullOrEmpty()) {
            for (name in flat.split(":")) {
                val cn = ComponentName.unflattenFromString(name)
                if (cn != null && cn.packageName == packageName) return true
            }
        }
        return false
    }

    private fun isPostNotificationGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun isBatteryOptimizationIgnored(): Boolean {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            pm.isIgnoringBatteryOptimizations(packageName)
        } else true
    }

    private fun openAppInfoSettings() {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", packageName, null)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal membuka Info Aplikasi", Toast.LENGTH_SHORT).show()
        }
    }

    private fun openNotifListenerSettings() {
        try {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            })
        } catch (e: Exception) {
            Toast.makeText(this, "Gagal membuka Pengaturan Notifikasi", Toast.LENGTH_SHORT).show()
        }
    }

    private fun requestPostNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                1001
            )
        }
    }

    private fun requestBatteryIgnore() {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivity(intent)
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (_: Exception) {}
        }
    }

    private fun loadWebhooksFromPrefs() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString("webhooks_json", "[]") ?: "[]"
        val list = mutableListOf<WebhookItem>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val pkgsArr = obj.optJSONArray("selectedPackages") ?: JSONArray()
                val pkgs = mutableListOf<String>()
                for (p in 0 until pkgsArr.length()) {
                    pkgs.add(pkgsArr.optString(p))
                }
                list.add(
                    WebhookItem(
                        id = obj.optString("id", System.currentTimeMillis().toString()),
                        name = obj.optString("name", "Webhook"),
                        url = obj.optString("url", ""),
                        apiToken = obj.optString("apiToken", ""),
                        aksi = obj.optString("aksi", "updateStatus"),
                        statusBaru = obj.optString("statusBaru", "Berhasil"),
                        includeTitle = obj.optBoolean("includeTitle", false),
                        filterKeyword = obj.optString("filterKeyword", ""),
                        selectedPackages = pkgs,
                        enabled = obj.optBoolean("enabled", true),
                        successCount = obj.optInt("successCount", 0),
                        failCount = obj.optInt("failCount", 0)
                    )
                )
            }
        } catch (_: Exception) {}
        webhooks = list
    }

    private fun saveWebhooksToPrefs(newList: List<WebhookItem>) {
        webhooks = newList
        val arr = JSONArray()
        for (item in newList) {
            val obj = JSONObject().apply {
                put("id", item.id)
                put("name", item.name)
                put("url", item.url)
                put("apiToken", item.apiToken)
                put("aksi", item.aksi)
                put("statusBaru", item.statusBaru)
                put("includeTitle", item.includeTitle)
                put("filterKeyword", item.filterKeyword)
                put("enabled", item.enabled)
                put("successCount", item.successCount)
                put("failCount", item.failCount)
                val pkgArr = JSONArray()
                item.selectedPackages.forEach { pkgArr.put(it) }
                put("selectedPackages", pkgArr)
            }
            arr.put(obj)
        }
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("webhooks_json", arr.toString())
            .apply()
        rebindListenerService()
    }

    private fun loadLogsFromPrefs() {
        val prefs = getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString("logs_json", "[]") ?: "[]"
        val list = mutableListOf<LogItem>()
        try {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                list.add(
                    LogItem(
                        time = obj.optString("time", ""),
                        webhookName = obj.optString("webhookName", ""),
                        packageName = obj.optString("packageName", ""),
                        kunciEntri = obj.optString("kunciEntri", ""),
                        statusCode = obj.optInt("statusCode", 0),
                        status = obj.optString("status", "GAGAL"),
                        response = obj.optString("response", "")
                    )
                )
            }
        } catch (_: Exception) {}
        logs = list
    }

    private fun clearLogsInPrefs() {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString("logs_json", "[]")
            .apply()
        logs = emptyList()
    }

    private fun loadInstalledAppsAsync(
        includeSystem: Boolean,
        onResult: (List<InstalledAppItem>) -> Unit
    ) {
        executor.execute {
            val pm = packageManager
            val packages = pm.getInstalledApplications(PackageManager.GET_META_DATA)
            val result = mutableListOf<InstalledAppItem>()
            for (appInfo in packages) {
                if (appInfo.packageName == packageName) continue
                val isSys = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                val isUpdatedSys = (appInfo.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                if (!includeSystem && isSys && !isUpdatedSys) continue

                val label = pm.getApplicationLabel(appInfo).toString()
                val iconBitmap = try {
                    drawableToImageBitmap(pm.getApplicationIcon(appInfo))
                } catch (_: Exception) {
                    null
                }
                result.add(InstalledAppItem(label, appInfo.packageName, isSys, iconBitmap))
            }
            result.sortBy { it.appName.lowercase() }
            mainHandler.post { onResult(result) }
        }
    }

    private fun drawableToImageBitmap(drawable: Drawable): ImageBitmap {
        val size = 72
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        drawable.setBounds(0, 0, size, size)
        drawable.draw(canvas)
        return bmp.asImageBitmap()
    }

    private fun sendTestWebhook(
        item: WebhookItem,
        customText: String,
        onFinish: (Int, String, String) -> Unit
    ) {
        executor.execute {
            // Menggunakan struktur payload asli: "harga_transaksi"
            val payload = JSONObject().apply {
                put("aksi", item.aksi.ifEmpty { "updateStatus" })
                put("harga_transaksi", customText)
                put("statusBaru", item.statusBaru.ifEmpty { "Berhasil" })
            }
            var code = 0
            var resp = ""
            try {
                val conn = (URL(item.url).openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    connectTimeout = 15000
                    readTimeout = 15000
                    doOutput = true
                    doInput = true
                    setRequestProperty("Content-Type", "application/json; charset=utf-8")
                    setRequestProperty("Accept", "application/json")
                    if (item.apiToken.isNotEmpty()) {
                        setRequestProperty("X-API-Token", item.apiToken)
                    }
                }
                OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use {
                    it.write(payload.toString())
                    it.flush()
                }
                code = conn.responseCode
                val stream = if (code in 200..299) conn.inputStream else conn.errorStream
                if (stream != null) {
                    BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use {
                        resp = it.readText().take(400)
                    }
                }
                conn.disconnect()
            } catch (e: Exception) {
                code = -1
                resp = e.message ?: "Network Error"
            }
            val prettyJson = payload.toString(2)
            mainHandler.post { onFinish(code, prettyJson, resp) }
        }
    }

    @Composable
    fun MainAppScreen() {
        var currentTab by remember { mutableIntStateOf(0) }
        var editingWebhook by remember { mutableStateOf<WebhookItem?>(null) }
        var editingIndex by remember { mutableIntStateOf(-1) }
        var showForm by remember { mutableStateOf(false) }

        var testTargetWebhook by remember { mutableStateOf<WebhookItem?>(null) }
        var testCustomText by remember { mutableStateOf("Pembayaran QRIS Rp 15.123 berhasil diterima (Test)") }
        var testDialogData by remember { mutableStateOf<Triple<Int, String, String>?>(null) }

        if (showForm) {
            WebhookFormScreen(
                initial = editingWebhook,
                onCancel = { showForm = false },
                onSave = { saved ->
                    val mutable = webhooks.toMutableList()
                    if (editingIndex >= 0 && editingIndex < mutable.size) {
                        mutable[editingIndex] = saved.copy(
                            successCount = webhooks[editingIndex].successCount,
                            failCount = webhooks[editingIndex].failCount
                        )
                    } else {
                        mutable.add(saved)
                    }
                    saveWebhooksToPrefs(mutable)
                    showForm = false
                }
            )
            return
        }

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = { Text("ViaQRIS Webhook Forwarder", fontWeight = FontWeight.Bold) },
                    actions = {
                        IconButton(onClick = { refreshAllState() }) {
                            Icon(Icons.Default.Refresh, contentDescription = "Refresh")
                        }
                    }
                )
            },
            floatingActionButton = {
                if (currentTab == 0) {
                    ExtendedFloatingActionButton(
                        onClick = {
                            editingWebhook = null
                            editingIndex = -1
                            showForm = true
                        },
                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                        text = { Text("Tambah Webhook") }
                    )
                }
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentTab == 0,
                        onClick = { currentTab = 0 },
                        icon = { Icon(Icons.Default.Webhook, contentDescription = null) },
                        label = { Text("Webhook & Izin") }
                    )
                    NavigationBarItem(
                        selected = currentTab == 1,
                        onClick = {
                            loadLogsFromPrefs()
                            currentTab = 1
                        },
                        icon = { Icon(Icons.Default.ReceiptLong, contentDescription = null) },
                        label = { Text("Riwayat Log") }
                    )
                }
            }
        ) { innerPadding ->
            if (currentTab == 0) {
                WebhookListTab(
                    modifier = Modifier.padding(innerPadding),
                    onEdit = { item, idx ->
                        editingWebhook = item
                        editingIndex = idx
                        showForm = true
                    },
                    onTest = { item ->
                        testTargetWebhook = item
                    },
                    onResetCounter = { idx ->
                        val mutable = webhooks.toMutableList()
                        mutable[idx] = mutable[idx].copy(successCount = 0, failCount = 0)
                        saveWebhooksToPrefs(mutable)
                    }
                )
            } else {
                LogsTab(modifier = Modifier.padding(innerPadding))
            }
        }

        // Dialog Input Simulasi Test Kirim
        testTargetWebhook?.let { target ->
            AlertDialog(
                onDismissRequest = { testTargetWebhook = null },
                title = { Text("Simulasi Test Kirim Webhook") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Masukkan teks notifikasi simulasi untuk diuji ke endpoint:", fontSize = 12.sp)
                        OutlinedTextField(
                            value = testCustomText,
                            onValueChange = { testCustomText = it },
                            label = { Text("Teks Notifikasi / harga_transaksi") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val item = target
                            testTargetWebhook = null
                            Toast.makeText(this, "Mengirim test...", Toast.LENGTH_SHORT).show()
                            sendTestWebhook(item, testCustomText) { code, sent, resp ->
                                testDialogData = Triple(code, sent, resp)
                                refreshAllState()
                            }
                        }
                    ) {
                        Text("Kirim Sekarang")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { testTargetWebhook = null }) {
                        Text("Batal")
                    }
                }
            )
        }

        testDialogData?.let { (code, sentPayload, serverResp) ->
            AlertDialog(
                onDismissRequest = { testDialogData = null },
                title = { Text("Response Server ($code)") },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            text = "Payload Terkirim (harga_transaksi):\n$sentPayload\n\nResponse:\n${serverResp.ifEmpty { "(Kosong)" }}",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { testDialogData = null }) {
                        Text("Tutup")
                    }
                }
            )
        }
    }

    @Composable
    fun WebhookListTab(
        modifier: Modifier = Modifier,
        onEdit: (WebhookItem, Int) -> Unit,
        onTest: (WebhookItem) -> Unit,
        onResetCounter: (Int) -> Unit
    ) {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { PermissionCard() }

            item {
                Text(
                    text = "Daftar Webhook (${webhooks.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }

            if (webhooks.isEmpty()) {
                item {
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                Icons.Default.CloudUpload,
                                contentDescription = null,
                                modifier = Modifier.size(48.dp),
                                tint = Color.Gray
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "Belum ada Webhook ditambahkan.\nTekan tombol '+ Tambah Webhook' di kanan bawah.",
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            } else {
                itemsIndexed(webhooks) { idx, item ->
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = item.name,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp,
                                    modifier = Modifier.weight(1f)
                                )
                                Switch(
                                    checked = item.enabled,
                                    onCheckedChange = { checked ->
                                        val mutable = webhooks.toMutableList()
                                        mutable[idx] = item.copy(enabled = checked)
                                        saveWebhooksToPrefs(mutable)
                                    }
                                )
                            }
                            Text(
                                text = item.url,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "${item.selectedPackages.size} App Dipilih",
                                    fontSize = 12.sp,
                                    color = Color.DarkGray
                                )
                                // Counter Sukses & Gagal
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Surface(
                                        color = Color(0xFF2E7D32).copy(alpha = 0.15f),
                                        shape = CircleShape
                                    ) {
                                        Text(
                                            text = " ✓ ${item.successCount} ",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF2E7D32),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                    Surface(
                                        color = Color(0xFFC62828).copy(alpha = 0.15f),
                                        shape = CircleShape
                                    ) {
                                        Text(
                                            text = " ✕ ${item.failCount} ",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFC62828),
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                TextButton(onClick = { onResetCounter(idx) }) {
                                    Text("Reset Counter", fontSize = 12.sp, color = Color.Gray)
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    OutlinedButton(
                                        onClick = { onTest(item) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Test", fontSize = 12.sp)
                                    }
                                    OutlinedButton(
                                        onClick = { onEdit(item, idx) },
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("Edit", fontSize = 12.sp)
                                    }
                                    IconButton(
                                        onClick = {
                                            val mutable = webhooks.toMutableList()
                                            mutable.removeAt(idx)
                                            saveWebhooksToPrefs(mutable)
                                        },
                                        modifier = Modifier.size(36.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "Hapus", tint = Color.Red, modifier = Modifier.size(18.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun PermissionCard() {
        val allReady = notifListenerGranted && postNotifGranted && batteryIgnored
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = if (allReady) Icons.Default.VerifiedUser else Icons.Default.Warning,
                        tint = if (allReady) Color(0xFF2E7D32) else Color(0xFFEF6C00),
                        contentDescription = null
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = if (allReady) "Semua Izin Aktif (Siap 24 Jam)" else "Pengaturan Izin & Restricted Permission",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    )
                }
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "Untuk Android 13–16: Jika Akses Notifikasi terkunci (Pengaturan Terbatas), klik Langkah 1 -> tekan ikon titik tiga (⋮) di pojok kanan atas Info Aplikasi -> pilih 'Izinkan pengaturan terbatas'.",
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.height(10.dp))
                PermissionRow(
                    title = "1. Buka Kunci Pengaturan Terbatas",
                    subtitle = "Info Aplikasi -> Titik Tiga (⋮) -> Izinkan pengaturan terbatas",
                    granted = notifListenerGranted,
                    btnText = "Info",
                    onClick = { openAppInfoSettings() }
                )
                PermissionRow(
                    title = "2. Izin Akses Baca Notifikasi (Wajib)",
                    subtitle = if (notifListenerGranted) "Sudah Aktif" else "Belum Aktif",
                    granted = notifListenerGranted,
                    btnText = "Aktifkan",
                    onClick = { openNotifListenerSettings() }
                )
                PermissionRow(
                    title = "3. Izin Notifikasi Status Bar",
                    subtitle = if (postNotifGranted) "Sudah Diizinkan" else "Belum Diizinkan",
                    granted = postNotifGranted,
                    btnText = "Izinkan",
                    onClick = { requestPostNotifPermission() }
                )
                PermissionRow(
                    title = "4. Bebas Optimasi Baterai (Jalan 24 Jam)",
                    subtitle = if (batteryIgnored) "Sudah Bebas Baterai" else "Masih Dibatasi Sistem",
                    granted = batteryIgnored,
                    btnText = "Matikan",
                    onClick = { requestBatteryIgnore() }
                )
            }
        }
    }

    @Composable
    fun PermissionRow(
        title: String,
        subtitle: String,
        granted: Boolean,
        btnText: String,
        onClick: () -> Unit
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (granted) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                tint = if (granted) Color(0xFF2E7D32) else Color.Gray,
                modifier = Modifier.size(20.dp),
                contentDescription = null
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                Text(subtitle, fontSize = 11.sp, color = Color.Gray)
            }
            Spacer(modifier = Modifier.width(6.dp))
            FilledTonalButton(
                onClick = onClick,
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(btnText, fontSize = 12.sp)
            }
        }
    }

    @Composable
    fun LogsTab(modifier: Modifier = Modifier) {
        Column(modifier = modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("50 Riwayat Terakhir (${logs.size})", fontWeight = FontWeight.Bold)
                TextButton(onClick = { clearLogsInPrefs() }) {
                    Icon(Icons.Default.DeleteSweep, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("Bersihkan")
                }
            }
            if (logs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Belum ada riwayat notifikasi yang diteruskan.")
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(logs) { log ->
                        val isOk = log.status == "SUKSES"
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (isOk) Color(0xFF2E7D32) else Color(0xFFC62828),
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Text(
                                            text = if (log.statusCode > 0) "${log.statusCode}" else "ERR",
                                            color = Color.White,
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        "${log.webhookName} • ${log.time}",
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 13.sp
                                    )
                                    Text("App: ${log.packageName}", fontSize = 11.sp, color = Color.Gray)
                                    Text("kunciEntri/harga: ${log.kunciEntri}", fontSize = 12.sp)
                                    if (log.response.isNotEmpty()) {
                                        Text(
                                            "Resp: ${log.response}",
                                            fontSize = 11.sp,
                                            fontFamily = FontFamily.Monospace
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun WebhookFormScreen(
        initial: WebhookItem?,
        onCancel: () -> Unit,
        onSave: (WebhookItem) -> Unit
    ) {
        var name by remember { mutableStateOf(initial?.name ?: "ViaQRIS PHPNuxBill") }
        var url by remember { mutableStateOf(initial?.url ?: "") }
        var apiToken by remember { mutableStateOf(initial?.apiToken ?: "") }
        var aksi by remember { mutableStateOf(initial?.aksi ?: "updateStatus") }
        var statusBaru by remember { mutableStateOf(initial?.statusBaru ?: "Berhasil") }
        var includeTitle by remember { mutableStateOf(initial?.includeTitle ?: false) }
        var filterKeyword by remember { mutableStateOf(initial?.filterKeyword ?: "") }
        var selectedPackages by remember {
            mutableStateOf(initial?.selectedPackages?.toSet() ?: emptySet())
        }
        var showAppPicker by remember { mutableStateOf(false) }

        BackHandler {
            if (showAppPicker) showAppPicker = false else onCancel()
        }

        if (showAppPicker) {
            AppPickerScreen(
                initialSelected = selectedPackages,
                onDone = { result ->
                    selectedPackages = result
                    showAppPicker = false
                }
            )
            return
        }

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = { Text(if (initial == null) "Tambah Webhook" else "Edit Webhook") },
                    navigationIcon = {
                        IconButton(onClick = onCancel) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Kembali")
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nama / Label Webhook") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("URL Endpoint Webhook") },
                    placeholder = { Text("https://domain-anda.com/webhook") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = apiToken,
                    onValueChange = { apiToken = it },
                    label = { Text("API Token (Header: X-API-Token)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Text(
                            "Aplikasi Merchant / Payment Sumber Notifikasi",
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = if (selectedPackages.isEmpty()) "Belum ada aplikasi dipilih"
                            else "Terpilih (${selectedPackages.size}):\n${selectedPackages.joinToString(", ")}",
                            fontSize = 12.sp
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        Button(
                            onClick = { showAppPicker = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Apps, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Pilih dari Daftar Aplikasi Terinstal")
                        }
                    }
                }

                Text("Konfigurasi Body JSON ViaQRIS (Sesuai Asli)", fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = aksi,
                        onValueChange = { aksi = it },
                        label = { Text("Field 'aksi'") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = statusBaru,
                        onValueChange = { statusBaru = it },
                        label = { Text("Field 'statusBaru'") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Sertakan Judul Notifikasi di 'harga_transaksi'", fontWeight = FontWeight.Medium)
                        Text(
                            "Jika mati: hanya mengirim isi teks notifikasi payment",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                    Switch(checked = includeTitle, onCheckedChange = { includeTitle = it })
                }

                OutlinedTextField(
                    value = filterKeyword,
                    onValueChange = { filterKeyword = it },
                    label = { Text("Filter Kata Kunci (Opsional, pisahkan koma)") },
                    placeholder = { Text("Contoh: Rp, berhasil, diterima, QRIS") },
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(8.dp))
                Button(
                    onClick = {
                        if (url.trim().isEmpty() || !url.trim().startsWith("http")) {
                            Toast.makeText(
                                this@MainActivity,
                                "URL harus diawali http:// atau https://",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@Button
                        }
                        if (selectedPackages.isEmpty()) {
                            Toast.makeText(
                                this@MainActivity,
                                "Pilih minimal 1 aplikasi payment!",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@Button
                        }
                        onSave(
                            WebhookItem(
                                id = initial?.id ?: System.currentTimeMillis().toString(),
                                name = name.trim().ifEmpty { "Webhook" },
                                url = url.trim(),
                                apiToken = apiToken.trim(),
                                aksi = aksi.trim().ifEmpty { "updateStatus" },
                                statusBaru = statusBaru.trim().ifEmpty { "Berhasil" },
                                includeTitle = includeTitle,
                                filterKeyword = filterKeyword.trim(),
                                selectedPackages = selectedPackages.toList(),
                                enabled = initial?.enabled ?: true,
                                successCount = initial?.successCount ?: 0,
                                failCount = initial?.failCount ?: 0
                            )
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Simpan Konfigurasi Webhook")
                }
            }
        }
    }

    @Composable
    fun AppPickerScreen(
        initialSelected: Set<String>,
        onDone: (Set<String>) -> Unit
    ) {
        var apps by remember { mutableStateOf(listOf<InstalledAppItem>()) }
        var loading by remember { mutableStateOf(true) }
        var includeSystem by remember { mutableStateOf(false) }
        var search by remember { mutableStateOf("") }
        var selected by remember { mutableStateOf(initialSelected) }

        LaunchedEffect(includeSystem) {
            loading = true
            loadInstalledAppsAsync(includeSystem) { loaded ->
                apps = loaded
                loading = false
            }
        }

        val filtered = remember(apps, search) {
            if (search.isBlank()) apps
            else {
                val q = search.lowercase()
                apps.filter {
                    it.appName.lowercase().contains(q) || it.packageName.lowercase().contains(q)
                }
            }
        }

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = { Text("Pilih Aplikasi (${selected.size})") },
                    navigationIcon = {
                        IconButton(onClick = { onDone(selected) }) {
                            Icon(Icons.Default.ArrowBack, contentDescription = "Selesai")
                        }
                    },
                    actions = {
                        TextButton(onClick = { onDone(selected) }) {
                            Text("Selesai", fontWeight = FontWeight.Bold)
                        }
                    }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .padding(innerPadding)
                    .fillMaxSize()
            ) {
                OutlinedTextField(
                    value = search,
                    onValueChange = { search = it },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    placeholder = { Text("Cari GoPay, DANA, OrderKuota, BCA, BRI...") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    singleLine = true
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Tampilkan Aplikasi Sistem", modifier = Modifier.weight(1f))
                    Switch(checked = includeSystem, onCheckedChange = { includeSystem = it })
                }
                HorizontalDivider()
                if (loading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                } else {
                    LazyColumn(modifier = Modifier.fillMaxSize()) {
                        items(filtered, key = { it.packageName }) { app ->
                            val isChecked = selected.contains(app.packageName)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selected = if (isChecked) selected - app.packageName
                                        else selected + app.packageName
                                    }
                                    .padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (app.icon != null) {
                                    Image(
                                        bitmap = app.icon,
                                        contentDescription = null,
                                        modifier = Modifier.size(38.dp)
                                    )
                                } else {
                                    Icon(
                                        Icons.Default.Android,
                                        contentDescription = null,
                                        modifier = Modifier.size(38.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(app.appName, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
                                    Text(app.packageName, fontSize = 11.sp, color = Color.Gray)
                                }
                                Checkbox(
                                    checked = isChecked,
                                    onCheckedChange = { checked ->
                                        selected = if (checked) selected + app.packageName
                                        else selected - app.packageName
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
