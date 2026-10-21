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
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.res.painterResource
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

data class WebhookItem(
    val id: String,
    val name: String,
    val url: String,
    val apiToken: String,
    val aksi: String = "updateStatus",
    val statusBaru: String = "Berhasil",
    val includeTitle: Boolean = false,
    val smartRetry: Boolean = true,
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
    private var heartbeats by mutableStateOf(listOf<HeartbeatConfig>())

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
        heartbeats = HeartbeatStore.loadAll(this)
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
                        smartRetry = obj.optBoolean("smartRetry", true),
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
                put("smartRetry", item.smartRetry)
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

    private fun sendCustomTestWebhook(
        item: WebhookItem,
        customNotificationText: String,
        onFinish: (Int, String, String) -> Unit
    ) {
        executor.execute {
            val candidates = NominalParser.buildSmartPayloads(
                aksi = item.aksi.ifEmpty { "updateStatus" },
                statusBaru = item.statusBaru.ifEmpty { "Berhasil" },
                rawText = customNotificationText,
                smartRetry = item.smartRetry
            )

            val logReport = StringBuilder()
            var lastCode = 0
            var lastPayload = ""

            for ((modeTitle, payload) in candidates) {
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
                            resp = it.readText().take(350)
                        }
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    code = -1
                    resp = e.message ?: "Network Error"
                }

                lastCode = code
                lastPayload = payload.toString(2)
                logReport.append("[$modeTitle] -> HTTP $code\n")
                logReport.append("Body: $payload\n")
                logReport.append("Resp: ${resp.ifEmpty { "(Kosong)" }}\n\n")

                if (code in 200..299 || code != 404) {
                    break
                }
            }

            val isSuccess = lastCode in 200..299
            mainHandler.post {
                val updated = webhooks.map { w ->
                    if (w.id == item.id) {
                        if (isSuccess) w.copy(successCount = w.successCount + 1)
                        else w.copy(failCount = w.failCount + 1)
                    } else w
                }
                saveWebhooksToPrefs(updated)
                onFinish(lastCode, lastPayload, logReport.toString().trim())
            }
        }
    }

    @Composable
    fun MainAppScreen() {
        var currentTab by remember { mutableIntStateOf(0) }
        var editingWebhook by remember { mutableStateOf<WebhookItem?>(null) }
        var editingIndex by remember { mutableIntStateOf(-1) }
        var showForm by remember { mutableStateOf(false) }
        var showAddChooser by remember { mutableStateOf(false) }
        var showHeartbeatForm by remember { mutableStateOf(false) }
        var editingHeartbeat by remember { mutableStateOf<HeartbeatConfig?>(null) }
        var forceShowPermissions by remember { mutableStateOf(false) }

        var testTargetWebhook by remember { mutableStateOf<WebhookItem?>(null) }
        var customTestText by remember {
            mutableStateOf("Pembayaran sebesar Rp2.004 telah diterima pada transaksi 12628933938")
        }
        var testDialogResult by remember { mutableStateOf<Triple<Int, String, String>?>(null) }

        val allPermissionsReady = notifListenerGranted && postNotifGranted && batteryIgnored

        if (showForm) {
            WebhookFormScreen(
                initial = editingWebhook,
                onCancel = { showForm = false },
                onSave = { saved ->
                    val mutable = webhooks.toMutableList()
                    if (editingIndex >= 0 && editingIndex < mutable.size) {
                        mutable[editingIndex] = saved
                    } else {
                        mutable.add(saved)
                    }
                    saveWebhooksToPrefs(mutable)
                    showForm = false
                }
            )
            return
        }

        if (showHeartbeatForm) {
            HeartbeatFormScreen(
                initial = editingHeartbeat,
                onCancel = { showHeartbeatForm = false },
                onSaved = {
                    heartbeats = HeartbeatStore.loadAll(this)
                    showHeartbeatForm = false
                }
            )
            return
        }

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = {
                        Row(Modifier.verticalAlignment(Alignment.CenterVertically)) {
                            Image(
                                painter = painterResource(id = R.mipmap.ic_launcher),
                                contentDescription = "Logo ViaQRIS",
                                size = Size(30.dp.toPx()),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text("ViaQRIS Webhook", fontWeight = FontWeight.Bold)
                        }
                    },
                    actions = {
                        if (allPermissionsReady) {
                            IconButton(
                                onClick = { forceShowPermissions = !forceShowPermissions }
                            ) {
                                Icon(
                                    Icons.Default.VerifiedUser,
                                    contentDescription = "Status Izin Aktif",
                                    tint = Color(0xFF2E7D32)
                                )
                            }
                        }
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
                            showAddChooser = true
                        },
                        icon = { Icon(Icons.Default.Add, contentDescription = null) },
                        text = { Text("Tambah") }
                    )
                }
            },
            bottomBar = {
                NavigationBar {
                    NavigationBarItem(
                        selected = currentTab == 0,
                        onClick = {
                            refreshAllState()
                            currentTab = 0
                        },
                        icon = { Icon(Icons.Default.Webhook, contentDescription = null) },
                        label = { Text("Dashboard") }
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
                    showPermissionCard = !allPermissionsReady || forceShowPermissions,
                    onEdit = { item, idx ->
                        editingWebhook = item
                        editingIndex = idx
                        showForm = true
                    },
                    onTest = { item ->
                        testTargetWebhook = item
                    },
                    onEditHeartbeat = { cfg ->
                        editingHeartbeat = cfg
                        showHeartbeatForm = true
                    }
                )
            } else {
                LogsTab(modifier = Modifier.padding(innerPadding))
            }
        }

        if (showAddChooser) {
            AlertDialog(
                onDismissRequest = { showAddChooser = false },
                title = { Text("Tambah Baru") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Pilih jenis konfigurasi yang ingin ditambahkan:", fontSize = 13.sp)
                        Button(
                            onClick = {
                                showAddChooser = false
                                editingWebhook = null
                                editingIndex = -1
                                showForm = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Webhook, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Webhook Penerusan Transaksi")
                        }
                        val heartbeatConfigured = heartbeats.isNotEmpty()
                        OutlinedButton(
                            onClick = {
                                showAddChooser = false
                                editingHeartbeat = null
                                showHeartbeatForm = true
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.Favorite, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("Heartbeat Monitoring")
                        }
                        if (heartbeatConfigured) {
                            Text(
                                "Sudah ada ${heartbeats.size} heartbeat terdaftar. Tambahkan lagi bila perlu, atau kelola dari daftar di Dashboard.",
                                fontSize = 11.sp,
                                color = Color.Gray
                            )
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showAddChooser = false }) { Text("Batal") }
                }
            )
        }

        testTargetWebhook?.let { target ->
            val detectedNominal = NominalParser.extractAmount(customTestText)
            AlertDialog(
                onDismissRequest = { testTargetWebhook = null },
                title = { Text("Simulasi Test Kirim") },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            "Pastikan di PHPNuxBill / ViaQRIS sedang ada transaksi berstatus PENDING dengan nominal yang sama:",
                            fontSize = 12.sp
                        )
                        OutlinedTextField(
                            value = customTestText,
                            onValueChange = { customTestText = it },
                            label = { Text("Teks Notifikasi Simulasi") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Text(
                            text = if (detectedNominal != null)
                                "Nominal Terdeteksi (harga_transaksi): $detectedNominal"
                            else
                                "Nominal Rp belum terdeteksi",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (detectedNominal != null) Color(0xFF2E7D32) else Color(0xFFC62828)
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            val itemToTest = target
                            testTargetWebhook = null
                            Toast.makeText(this, "Mengirim test...", Toast.LENGTH_SHORT).show()
                            sendCustomTestWebhook(itemToTest, customTestText) { code, sent, resp ->
                                testDialogResult = Triple(code, sent, resp)
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

        testDialogResult?.let { (code, sentPayload, report) ->
            AlertDialog(
                onDismissRequest = { testDialogResult = null },
                title = { Text("Hasil Test ($code)") },
                text = {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        Text(
                            text = "Payload Terakhir:\n$sentPayload\n\nRiwayat Pengiriman:\n$report",
                            fontFamily = FontFamily.Monospace,
                            fontSize = 12.sp
                        )
                    }
                },
                confirmButton = {
                    TextButton(onClick = { testDialogResult = null }) {
                        Text("Tutup")
                    }
                }
            )
        }
    }

    @Composable
    fun WebhookListTab(
        modifier: Modifier = Modifier,
        showPermissionCard: Boolean,
        onEdit: (WebhookItem, Int) -> Unit,
        onTest: (WebhookItem) -> Unit,
        onEditHeartbeat: (HeartbeatConfig) -> Unit
    ) {
        LazyColumn(
            modifier = modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, top = 12.dp, end = 16.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            if (showPermissionCard) {
                item { PermissionCard() }
            }

            if (heartbeats.isNotEmpty()) {
                item {
                    Text(
                        text = "Heartbeat Monitoring (${heartbeats.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                itemsIndexed(heartbeats) { idx, cfg ->
                    HeartbeatCard(
                        config = cfg,
                        onEdit = { onEditHeartbeat(cfg) },
                        onToggle = { checked ->
                            val updated = cfg.copy(enabled = checked)
                            HeartbeatStore.upsert(this@MainActivity, updated)
                            heartbeats = HeartbeatStore.loadAll(this@MainActivity)
                            HeartbeatService.sync(this@MainActivity)
                        },
                        onDelete = {
                            HeartbeatStore.remove(this@MainActivity, cfg.id)
                            heartbeats = HeartbeatStore.loadAll(this@MainActivity)
                            HeartbeatService.sync(this@MainActivity)
                        }
                    )
                }
            }

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
                                "Belum ada Webhook ditambahkan.\nTekan tombol '+ Tambah' di kanan bawah, lalu pilih Webhook.",
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
                            Spacer(modifier = Modifier.height(8.dp))

                            // Baris Counter Sukses & Gagal
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFFE8F5E9)
                                ) {
                                    Text(
                                        text = "✓ Sukses: ${item.successCount}",
                                        color = Color(0xFF2E7D32),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                                Surface(
                                    shape = RoundedCornerShape(6.dp),
                                    color = Color(0xFFFFEBEE)
                                ) {
                                    Text(
                                        text = "✗ Gagal: ${item.failCount}",
                                        color = Color(0xFFC62828),
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 12.sp,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                                if (item.successCount > 0 || item.failCount > 0) {
                                    TextButton(
                                        onClick = {
                                            val mutable = webhooks.toMutableList()
                                            mutable[idx] = item.copy(successCount = 0, failCount = 0)
                                            saveWebhooksToPrefs(mutable)
                                        },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                                        modifier = Modifier.height(28.dp)
                                    ) {
                                        Text("Reset", fontSize = 11.sp)
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = "${item.selectedPackages.size} Aplikasi Dipilih • Mode: ${if (item.smartRetry) "Nominal Otomatis" else "Teks Notifikasi"}",
                                fontSize = 12.sp,
                                color = Color.DarkGray
                            )
                            HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                OutlinedButton(onClick = { onTest(item) }) {
                                    Icon(Icons.Default.Send, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Test Kirim")
                                }
                                Spacer(modifier = Modifier.width(8.dp))
                                OutlinedButton(onClick = { onEdit(item, idx) }) {
                                    Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Edit")
                                }
                                IconButton(
                                    onClick = {
                                        val mutable = webhooks.toMutableList()
                                        mutable.removeAt(idx)
                                        saveWebhooksToPrefs(mutable)
                                    }
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = "Hapus", tint = Color.Red)
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun HeartbeatCard(
        config: HeartbeatConfig,
        onEdit: () -> Unit,
        onToggle: (Boolean) -> Unit,
        onDelete: () -> Unit
    ) {
        val ok = config.lastCode in 200..299
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Favorite,
                        contentDescription = null,
                        tint = if (config.enabled) Color(0xFF2E7D32) else Color.Gray
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = config.name.ifBlank { "Heartbeat Monitoring" },
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                        Text(
                            text = if (config.enabled)
                                "Aktif • tiap ${config.intervalSeconds.coerceAtLeast(30)}s"
                            else "Nonaktif",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                    Switch(checked = config.enabled, onCheckedChange = onToggle)
                }
                if (config.url.isNotBlank()) {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = config.url,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFFE8F5E9)) {
                        Text(
                            text = "✓ Terkirim: ${config.totalSent}",
                            color = Color(0xFF2E7D32),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                    Surface(shape = RoundedCornerShape(6.dp), color = Color(0xFFFFEBEE)) {
                        Text(
                            text = "✗ Gagal: ${config.totalFailed}",
                            color = Color(0xFFC62828),
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
                if (config.lastSentAt > 0) {
                    Spacer(modifier = Modifier.height(6.dp))
                    val time = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())
                        .format(Date(config.lastSentAt))
                    Text(
                        text = "Terakhir: $time (HTTP ${config.lastCode})" +
                            if (config.lastResponse.isNotEmpty()) " • ${config.lastResponse}" else "",
                        fontSize = 12.sp,
                        color = if (ok) Color(0xFF2E7D32) else Color(0xFFC62828)
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("Edit")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Hapus", tint = Color.Red)
                    }
                }
            }
        }
    }

    @Composable
    fun HeartbeatFormScreen(
        initial: HeartbeatConfig?,
        onCancel: () -> Unit,
        onSaved: () -> Unit
    ) {
        var name by remember { mutableStateOf(initial?.name ?: "Heartbeat Monitoring") }
        var enabled by remember { mutableStateOf(initial?.enabled ?: true) }
        var url by remember { mutableStateOf(initial?.url ?: "") }
        var token by remember { mutableStateOf(initial?.token ?: "") }
        var secret by remember { mutableStateOf(initial?.secret ?: "") }
        var deviceId by remember { mutableStateOf(initial?.deviceId ?: "") }
        var interval by remember { mutableStateOf((initial?.intervalSeconds ?: 60).toString()) }
        var showAdvanced by remember {
            mutableStateOf(initial?.secret?.isNotBlank() == true || initial?.deviceId?.isNotBlank() == true)
        }
        var testResult by remember { mutableStateOf<Pair<Int, String>?>(null) }
        var testing by remember { mutableStateOf(false) }

        Scaffold(
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
                TopAppBar(
                    title = { Text(if (initial == null) "Tambah Heartbeat" else "Edit Heartbeat") },
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
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Favorite,
                            contentDescription = null,
                            tint = if (enabled) Color(0xFF2E7D32) else Color.Gray
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Heartbeat Monitoring", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                            Text(
                                text = if (enabled) "Aktif • tiap ${(interval.toIntOrNull() ?: 60).coerceAtLeast(30)}s"
                                else "Nonaktif",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                        Switch(checked = enabled, onCheckedChange = { enabled = it })
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "Mengirim sinyal berkala ke server viaQris agar perangkat terpantau online/offline pada halaman donasi.",
                        fontSize = 12.sp
                    )
                }
            }

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Nama / Label Heartbeat") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                label = { Text("URL Server viaQris") },
                placeholder = { Text("https://domain-anda.com") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )
            OutlinedTextField(
                value = token,
                onValueChange = { token = it },
                label = { Text("Token Heartbeat") },
                placeholder = { Text("Dari Panel Admin → Heartbeat Monitoring") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Pengaturan Lanjutan (HMAC & Device ID)", fontWeight = FontWeight.Medium)
                    Text(
                        "Isi Secret HMAC bila diaktifkan di server; Device ID opsional (default nama model HP).",
                        fontSize = 12.sp,
                        color = Color.Gray
                    )
                }
                Switch(checked = showAdvanced, onCheckedChange = { showAdvanced = it })
            }

            if (showAdvanced) {
                OutlinedTextField(
                    value = deviceId,
                    onValueChange = { deviceId = it },
                    label = { Text("Device ID (opsional)") },
                    placeholder = { Text(Build.MODEL) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                OutlinedTextField(
                    value = secret,
                    onValueChange = { secret = it },
                    label = { Text("Secret HMAC (opsional)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = interval,
                        onValueChange = { interval = it.filter { c -> c.isDigit() } },
                        label = { Text("Interval (detik)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    FilledTonalButton(
                        onClick = { secret = HeartbeatSender.generateSecret() },
                        modifier = Modifier.height(56.dp)
                    ) {
                        Text("Generate Secret")
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Button(
                    onClick = {
                        val cfg = HeartbeatConfig(
                            id = initial?.id ?: HeartbeatSender.generateId(),
                            name = name.trim().ifEmpty { "Heartbeat Monitoring" },
                            enabled = enabled,
                            url = url.trim(),
                            token = token.trim(),
                            secret = secret.trim(),
                            deviceId = deviceId.trim(),
                            intervalSeconds = interval.toIntOrNull() ?: 60,
                            lastSentAt = initial?.lastSentAt ?: 0L,
                            lastCode = initial?.lastCode ?: 0,
                            lastResponse = initial?.lastResponse ?: "",
                            totalSent = initial?.totalSent ?: 0,
                            totalFailed = initial?.totalFailed ?: 0
                        )
                        if (enabled && (cfg.url.isBlank() || cfg.token.isBlank())) {
                            Toast.makeText(
                                this@MainActivity,
                                "URL dan Token Heartbeat wajib diisi!",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@Button
                        }
                        if (enabled && !cfg.url.startsWith("http")) {
                            Toast.makeText(
                                this@MainActivity,
                                "URL harus diawali http:// atau https://",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@Button
                        }
                        HeartbeatStore.upsert(this@MainActivity, cfg)
                        HeartbeatService.sync(this@MainActivity)
                        Toast.makeText(this@MainActivity, "Konfigurasi Heartbeat disimpan", Toast.LENGTH_SHORT).show()
                        onSaved()
                    },
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                ) {
                    Icon(Icons.Default.Save, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Simpan")
                }
                OutlinedButton(
                    onClick = {
                        val cfg = HeartbeatConfig(
                            enabled = true,
                            url = url.trim(),
                            token = token.trim(),
                            secret = secret.trim(),
                            deviceId = deviceId.trim(),
                            intervalSeconds = interval.toIntOrNull() ?: 60
                        )
                        if (cfg.url.isBlank() || cfg.token.isBlank()) {
                            Toast.makeText(
                                this@MainActivity,
                                "Isi URL dan Token terlebih dahulu",
                                Toast.LENGTH_SHORT
                            ).show()
                            return@OutlinedButton
                        }
                        testing = true
                        testResult = null
                        val testCfg = HeartbeatConfig(
                            id = initial?.id ?: "",
                            enabled = true,
                            url = url.trim(),
                            token = token.trim(),
                            secret = secret.trim(),
                            deviceId = deviceId.trim(),
                            intervalSeconds = interval.toIntOrNull() ?: 60
                        )
                        executor.execute {
                            val result = HeartbeatSender.send(testCfg, applicationContext)
                            if (initial != null) {
                                HeartbeatStore.record(
                                    this@MainActivity,
                                    initial.id,
                                    result.first,
                                    result.second,
                                    result.first in 200..299
                                )
                            }
                            mainHandler.post {
                                testing = false
                                testResult = result
                            }
                        }
                    },
                    enabled = !testing,
                    modifier = Modifier
                        .weight(1f)
                        .height(50.dp)
                ) {
                    if (testing) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Send, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("Test Kirim")
                    }
                }
            }

            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Text("Statistik Pengiriman", fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("Total terkirim: ${initial?.totalSent ?: 0}", fontSize = 13.sp)
                    Text("Total gagal: ${initial?.totalFailed ?: 0}", fontSize = 13.sp)
                    val lastSentAt = initial?.lastSentAt ?: 0L
                    if (lastSentAt > 0) {
                        val time = SimpleDateFormat("dd/MM HH:mm:ss", Locale.getDefault())
                            .format(Date(lastSentAt))
                        Text("Terakhir: $time (HTTP ${initial?.lastCode ?: 0})", fontSize = 13.sp)
                    }
                    val lastResponse = initial?.lastResponse ?: ""
                    if (lastResponse.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "Resp: $lastResponse",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace
                        )
                    }
                    if (testResult != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        val ok = testResult!!.first in 200..299
                        Surface(shape = RoundedCornerShape(6.dp), color = if (ok) Color(0xFFE8F5E9) else Color(0xFFFFEBEE)) {
                            Text(
                                text = if (ok) "✓ Heartbeat berhasil (HTTP ${testResult!!.first})"
                                else "✗ Heartbeat gagal (HTTP ${testResult!!.first})",
                                color = if (ok) Color(0xFF2E7D32) else Color(0xFFC62828),
                                fontWeight = FontWeight.Bold,
                                fontSize = 12.sp,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }
            }
        }
        }
    }

    @Composable
    fun PermissionCard() {        val allReady = notifListenerGranted && postNotifGranted && batteryIgnored
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
                        text = if (allReady) "Semua Izin Aktif" else "Pengaturan Izin & Restricted Permission",
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
                    btnText = "Info Aplikasi",
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
                    title = "4. Bebas Optimasi Baterai",
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
                        val ok = log.statusCode in 200..299
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Row(
                                modifier = Modifier.padding(12.dp),
                                verticalAlignment = Alignment.Top
                            ) {
                                Surface(
                                    shape = CircleShape,
                                    color = if (ok) Color(0xFF2E7D32) else Color(0xFFC62828),
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
                                    Text("Data: ${log.kunciEntri}", fontSize = 12.sp)
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
        var smartRetry by remember { mutableStateOf(initial?.smartRetry ?: true) }
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

                Text("Konfigurasi Payload JSON", fontWeight = FontWeight.Bold)
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
                        Text("Ekstrak Nominal Otomatis (harga_transaksi)", fontWeight = FontWeight.Medium)
                        Text(
                            "Otomatis mengambil angka nominal Rp dari notifikasi. Jika gagal, otomatis mencoba mengirim teks notifikasi (kunciEntri).",
                            fontSize = 12.sp,
                            color = Color.Gray
                        )
                    }
                    Switch(checked = smartRetry, onCheckedChange = { smartRetry = it })
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Sertakan Judul Notifikasi", fontWeight = FontWeight.Medium)
                        Text(
                            "Jika mati: hanya mengambil isi teks utama notifikasi pembayaran",
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
                                smartRetry = smartRetry,
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
                    placeholder = { Text("Cari GoPay, DANA, OrderKuota, ShopeePay, BCA...") },
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
