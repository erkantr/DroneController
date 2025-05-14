package com.bysoftware.dronecontroller

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.lifecycle.lifecycleScope
import com.bysoftware.dronecontroller.config.AppConfig
import com.bysoftware.dronecontroller.model.DirectTelemetryState
import com.bysoftware.dronecontroller.service.DirectConnectionService
import com.bysoftware.dronecontroller.service.ProxyConnectionService
import com.bysoftware.dronecontroller.ui.navigation.AppNavigation
import com.bysoftware.dronecontroller.ui.theme.DroneControllerTheme
import com.bysoftware.dronecontroller.utils.format
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.IOException
import java.text.DecimalFormat
import io.dronefleet.mavlink.minimal.Heartbeat // DirectConnectionService'e taşınacak komutlar için gerekli olabilir
import io.dronefleet.mavlink.minimal.MavAutopilot
import io.dronefleet.mavlink.minimal.MavState
import io.dronefleet.mavlink.minimal.MavType
import io.dronefleet.mavlink.minimal.MavModeFlag


class MainActivity : ComponentActivity() {

    private lateinit var usbManager: UsbManager
    private var usbPermissionCallback: ((Boolean, UsbDevice?) -> Unit)? = null

    // USB İzin Alıcısı
    private val usbPermissionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (AppConfig.ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let {
                            Log.i(AppConfig.TAG, "USB izni verildi: ${it.deviceName}")
                            usbPermissionCallback?.invoke(true, it)
                        }
                    } else {
                        Log.w(AppConfig.TAG, "USB izni reddedildi: ${device?.deviceName}")
                        Toast.makeText(context, "USB izni reddedildi.", Toast.LENGTH_SHORT).show()
                        usbPermissionCallback?.invoke(false, device)
                    }
                    usbPermissionCallback = null // Callback'i temizle
                }
            }
        }
    }

    // Activity Result Launcher (İzinler için)
    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            permissions.entries.forEach {
                Log.d(AppConfig.TAG, "${it.key} = ${it.value}")
                if (!it.value) {
                    Toast.makeText(this, "${it.key} izni gerekli!", Toast.LENGTH_LONG).show()
                }
            }
        }

    // --- Activity Yaşam Döngüsü ---
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() // Kenardan kenara UI için

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        // USB izin alıcısını kaydet
        val filter = IntentFilter(AppConfig.ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbPermissionReceiver, filter)
        }

        // Gerekli izinleri iste (Android 12+ için Bluetooth izni örneği eklendi)
        val requiredPermissions = mutableListOf(Manifest.permission.INTERNET)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // requiredPermissions.add(Manifest.permission.BLUETOOTH_SCAN)
            // requiredPermissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        requestPermissionLauncher.launch(requiredPermissions.toTypedArray())


        setContent {
            DroneControllerTheme {
                AppNavigation(
                    mainActivity = this,
                    requestUsbPermission = ::requestPermission, // MainActivity::requestPermission olarak geçer
                    checkUsbPermission = ::checkUsbPermission, // MainActivity::checkUsbPermission
                    getUsbDevices = ::getUsbDevices, // MainActivity::getUsbDevices
                    openUsbDeviceConnection = ::openDeviceConnection // MainActivity::openDeviceConnection
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbPermissionReceiver)
        // Servisler ve diğer kaynaklar DroneControllerApp içinde veya ViewModel'da temizlenmeli
    }

    // --- USB İzin ve Bağlantı Yardımcıları ---
    fun requestPermission(device: UsbDevice, callback: (Boolean, UsbDevice?) -> Unit) {
        if (usbManager.hasPermission(device)) {
            Log.d(AppConfig.TAG, "USB izni zaten var: ${device.deviceName}")
            callback(true, device)
            return
        }
        Log.d(AppConfig.TAG, "USB izni isteniyor: ${device.deviceName}")
        this.usbPermissionCallback = callback // Callback'i sakla
        val permissionIntent = PendingIntent.getBroadcast(
            this,
            AppConfig.USB_PERMISSION_REQUEST_CODE,
            Intent(AppConfig.ACTION_USB_PERMISSION),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT // SDK 31+ için MUTABLE gerekli
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    fun checkUsbPermission(device: UsbDevice): Boolean {
        return usbManager.hasPermission(device)
    }

    fun getUsbDevices(): List<UsbDevice> {
        return usbManager.deviceList.values.toList()
    }

    fun openDeviceConnection(device: UsbDevice): UsbDeviceConnection? {
        return try {
            usbManager.openDevice(device)
        } catch (e: Exception) {
            Log.e(AppConfig.TAG, "USB cihaz bağlantısı açılamadı: ${device.deviceName}", e)
            null
        }
    }
}


// ==================================
// DroneControllerApp Ana Bestekeri
// ==================================
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DroneControllerApp(
    mainActivity: MainActivity, // USB işlemleri için
    requestUsbPermission: (UsbDevice, (Boolean, UsbDevice?) -> Unit) -> Unit,
    checkUsbPermission: (UsbDevice) -> Boolean,
    getUsbDevices: () -> List<UsbDevice>,
    openUsbDeviceConnection: (UsbDevice) -> UsbDeviceConnection?,
    navigateToMap: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // --- State Değişkenleri ---
    var selectedUsbDevice by remember { mutableStateOf<UsbDevice?>(null) }
    var usbDevices by remember { mutableStateOf(emptyList<UsbDevice>()) }
    var showDeviceSelector by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf<String?>(null) } // "Direct" veya "Proxy" veya null


    // --- Servis Örnekleri ---
    val directConnectionService = remember {
        DirectConnectionService(
            context = context,
            scope = scope,
            showErrorCallback = { message, type ->
                scope.launch { snackbarHostState.showSnackbar("Hata ($type): $message") }
                if (type == "Direct") isConnecting = null
            },
            onConnectionStateChange = { connecting -> if(connecting) isConnecting = "Direct" else if(isConnecting == "Direct") isConnecting = null }
        )
    }

    val proxyConnectionService = remember {
        ProxyConnectionService(
            context = context,
            scope = scope,
            showErrorCallback = { message, type ->
                scope.launch { snackbarHostState.showSnackbar("Hata ($type): $message") }
                 if (type == "Proxy") isConnecting = null
            },
            onConnectionStateChange = { connecting -> if(connecting) isConnecting = "Proxy" else if(isConnecting == "Proxy") isConnecting = null }
        )
    }

    // Servislerden gelen durumları topla
    val directTelemetryState by directConnectionService.directTelemetryState.collectAsState()
    val isDirectConnected by directConnectionService.isDirectConnected.collectAsState()
    val connectionStatusDirect by directConnectionService.connectionStatusDirect.collectAsState()

    val telemetryDataProxy by proxyConnectionService.telemetryDataProxy.collectAsState()
    val isProxyConnected by proxyConnectionService.isProxyConnected.collectAsState()
    val connectionStatusProxy by proxyConnectionService.connectionStatusProxy.collectAsState()


    // --- Efektler ---
    LaunchedEffect(Unit) { // Bir kerelik yükleme
        usbDevices = getUsbDevices()
    }

    // Activity destroy olduğunda servisleri temizle
    DisposableEffect(Unit) {
        onDispose {
            Log.d(AppConfig.TAG, "DroneControllerApp onDispose, servisler temizleniyor.")
            directConnectionService.disconnectDirectUsb(false)
            proxyConnectionService.disconnectProxy(false)
            // Gerekirse burada diğer kaynakları da temizle
        }
    }

    // --- Yardımcı Fonksiyonlar (UI için) ---
    fun showError(message: String, type: String = "Genel") {
        scope.launch {
            snackbarHostState.showSnackbar("Hata ($type): $message")
        }
        // Bağlantı durumlarını da güncelle
        if (type == "Proxy" && isConnecting == "Proxy") {
            // proxyConnectionService.updateStatus("Hata (Proxy)") // Servis kendi durumunu günceller
            isConnecting = null
        }
        if (type == "Direct" && isConnecting == "Direct") {
            // directConnectionService.updateStatus("Hata (Direkt)") // Servis kendi durumunu günceller
            isConnecting = null
        }
        Log.e(AppConfig.TAG, "UI Hata: $message (Tip: $type)")
    }


    // --- USB Cihaz Seçimi ve Bağlantı Başlatma ---
    fun connectToDevice(device: UsbDevice, type: String) {
        selectedUsbDevice = device
        if (!checkUsbPermission(device)) {
            requestUsbPermission(device) { granted, permittedDevice ->
                if (granted && permittedDevice != null) {
                    Log.i(AppConfig.TAG, "USB izni alındı, bağlantı kuruluyor: ${permittedDevice.deviceName}, Tip: $type")
                    initiateConnection(permittedDevice, type, openUsbDeviceConnection)
                } else {
                    showError("USB izni reddedildi.", type)
                }
            }
        } else {
             Log.i(AppConfig.TAG, "USB izni zaten var, bağlantı kuruluyor: ${device.deviceName}, Tip: $type")
            initiateConnection(device, type, openUsbDeviceConnection)
        }
    }

    fun initiateConnection(device: UsbDevice, type: String, openConnection: (UsbDevice) -> UsbDeviceConnection?) {
        val connection = openConnection(device)
        if (connection == null) {
            showError("USB cihazına bağlantı (connection) kurulamadı.", type)
            isConnecting = null
            return
        }

        val drivers = UsbSerialProber.getDefaultProber().findAllDrivers(mainActivity.getSystemService(Context.USB_SERVICE) as UsbManager)
        val driver = drivers.find { it.device == device }
        if (driver == null || driver.ports.isEmpty()) {
            showError("Bu USB cihazı için uygun sürücü bulunamadı veya port yok.", type)
            connection.close()
            isConnecting = null
            return
        }
        val port = driver.ports[0] // Genellikle ilk port kullanılır

        if (type == "Proxy") {
            directConnectionService.disconnectDirectUsb(showStatusUpdate = false) // Diğer bağlantıyı kes
            proxyConnectionService.connectProxy(port, connection)
        } else if (type == "Direct") {
            proxyConnectionService.disconnectProxy(showStatusUpdate = false) // Diğer bağlantıyı kes
            directConnectionService.connectDirectUsb(port, connection)
        }
    }


    // --- UI ---
    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Drone Controller") },
                actions = {
                    IconButton(onClick = navigateToMap) {
                        Icon(Icons.Filled.Map, contentDescription = "Harita")
                    }
                    // Ayarlar butonu (örnek)
                    IconButton(onClick = { /* Ayarlar ekranına git */ }) {
                        Icon(Icons.Filled.Settings, contentDescription = "Ayarlar")
                    }
                }
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()), // Dikey kaydırma
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // --- Cihaz Seçim Alanı ---
            Button(
                onClick = {
                    usbDevices = getUsbDevices() // Listeyi yenile
                    showDeviceSelector = true
                },
                enabled = isConnecting == null // Sadece bağlantı yokken aktif
            ) {
                Text("USB Cihaz Seç (${usbDevices.size})")
            }

            if (showDeviceSelector) {
                DeviceSelectorDialog(
                    devices = usbDevices,
                    onDeviceSelected = { device, type ->
                        showDeviceSelector = false
                        // connectToDevice(device, type) // Bu satır MainActivity içinde zaten var, tekrar tanımlamaya gerek yok
                         if (!checkUsbPermission(device)) {
                            requestUsbPermission(device) { granted, permittedDevice ->
                                if (granted && permittedDevice != null) {
                                    initiateConnection(permittedDevice, type, openUsbDeviceConnection)
                                } else {
                                    scope.launch{ snackbarHostState.showSnackbar("USB izni reddedildi.")}
                                }
                            }
                        } else {
                            initiateConnection(device, type, openUsbDeviceConnection)
                        }
                    },
                    onDismiss = { showDeviceSelector = false }
                )
            }

            Text("Bağlantı Durumu (Direkt): $connectionStatusDirect", fontWeight = FontWeight.Bold)
            Text("Bağlantı Durumu (Proxy): $connectionStatusProxy", fontWeight = FontWeight.Bold)
            if (isConnecting != null) {
                 CircularProgressIndicator(modifier = Modifier.size(24.dp))
                 Text("$isConnecting bağlantısı deneniyor...", fontSize = 12.sp, color = Color.Gray)
            }


            // --- Kontrol Butonları ---
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = { directConnectionService.disconnectDirectUsb() },
                    enabled = isDirectConnected && isConnecting == null,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Direkt Bağlantıyı Kes") }

                Button(
                    onClick = { proxyConnectionService.disconnectProxy() },
                    enabled = isProxyConnected && isConnecting == null,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Proxy Bağlantıyı Kes") }
            }


            // --- Telemetri Göstergeleri ---
            if (isDirectConnected) {
                DirectTelemetryCard(telemetry = directTelemetryState)
                // Direct MAVLink Komut Gönderme Butonları
                Spacer(Modifier.height(10.dp))
                Text("Direkt Komutlar", style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { 
                        val heartbeat = Heartbeat.builder()
                            .type(MavType.MAV_TYPE_GCS)
                            .autopilot(MavAutopilot.MAV_AUTOPILOT_INVALID)
                            .baseMode(MavModeFlag.MAV_MODE_FLAG_MANUAL_INPUT_ENABLED)
                            .customMode(0)
                            .systemStatus(MavState.MAV_STATE_ACTIVE)
                            .mavlinkVersion(3)
                            .build()
                        directConnectionService.sendMavlinkCommand(heartbeat)
                    }) { Text("Heartbeat Gönder") }
                    
                    Button(onClick = { directConnectionService.sendArmDisarmCommand(true) }) { Text("ARM") }
                    Button(onClick = { directConnectionService.sendArmDisarmCommand(false) }) { Text("DISARM") }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                     Button(onClick = { directConnectionService.sendRequestGpsStreamCommand() }) { Text("GPS Stream İste") }
                     Button(onClick = { directConnectionService.sendRcCalibrationCommand() }) { Text("RC Kalibrasyon") }
                }
            }

            if (isProxyConnected) {
                ProxyTelemetryCard(telemetryData = telemetryDataProxy, drone = proxyConnectionService.drone)
            }

            Spacer(modifier = Modifier.weight(1f)) // Alt kısmı itmek için
            Text("MAVLink V2 Kotlin Projesi", fontSize = 10.sp, color = Color.Gray)
        }
    }
}

// --- Yardımcı Bestekerler (Dialog, Kartlar vb.) ---
@Composable
fun DeviceSelectorDialog(
    devices: List<UsbDevice>,
    onDeviceSelected: (UsbDevice, String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("USB Cihazı Seçin") },
        text = {
            LazyColumn {
                items(devices) { device ->
                    TextButton(onClick = { onDeviceSelected(device, "Direct") }) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            Text(device.deviceName ?: "Bilinmiyor", fontWeight = FontWeight.Bold)
                            Text("Vendor: ${device.vendorId}, Product: ${device.productId}")
                        }
                    }
                    Divider()
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text("Kapat")
            }
        }
    )
}

// --- Telemetry State Data Class ---
data class DirectTelemetryState(
    // GPS Verileri
    val fixType: Int? = null, // GPS fix tipi (örn., 0: No GPS, 2: 2D Fix, 3: 3D Fix) - GpsFixType enum ordinal'ı olabilir
    val satellitesVisible: Int? = null, // Görünür uydu sayısı
    val latitude: Double? = null, // Enlem (derece)
    val longitude: Double? = null, // Boylam (derece)
    val altitudeMsl: Double? = null, // Deniz seviyesinden yükseklik (metre)
    val relativeAltitude: Double? = null, // Kalkış noktasından göreceli yükseklik (metre)
    val heading: Double? = null, // Manyetik yön (Pusula - derece, 0-359) veya GPS yönü (COG)

    // Uçuş Verileri
    val groundSpeed: Double? = null, // Yere göre hız (m/s)
    val airSpeed: Double? = null, // Havaya göre hız (m/s) - Varsa
    val throttle: Int? = null, // Gaz yüzdesi (0-100)
    val climbRate: Double? = null, // Tırmanma/Alçalma hızı (m/s)

    // Durum Verileri
    val roll: Double? = null, // Yalpa açısı (derece)
    val pitch: Double? = null, // Yunuslama açısı (derece)
    val yaw: Double? = null, // Dönme açısı (derece) - Genellikle heading ile aynı veya ilişkili
    val armed: Boolean? = null, // Motorların kurulu olup olmadığı
    val mode: String? = null, // Aktif uçuş modu (örn., "STABILIZE", "POSCTL", "AUTO")

    // Batarya Verileri
    val batteryVoltage: Double? = null, // Batarya voltajı (Volt)
    val batteryCurrent: Double? = null, // Anlık akım tüketimi (Amper) - Pozitif olmalı
    val batteryRemaining: Int? = null, // Kalan batarya yüzdesi (0-100)

    // Sistem Mesajları ve Durumu
    val statusText: String? = null, // Araçtan gelen önemli durum mesajları
    val mavlinkVersion: Int? = null, // Kullanılan MAVLink protokol versiyonu (Heartbeat'ten alınabilir)
    val systemId: Int = 0, // Aracın MAVLink sistem ID'si
    val componentId: Int = 0, // Mesajı gönderen bileşenin ID'si (örn. 1: Autopilot)
    val autopilotType: Int? = null, // Otopilot tipi (MavAutopilot enum değeri) (Eğer Heartbeat'ten alınıyorsa)
    val systemStatus: Int? = null, // Genel sistem durumu (MavState enum değeri) (Eğer Heartbeat'ten alınıyorsa)
    var lastMessageTimestamp: Long = 0L // Son mesajın alınma zamanı (ms)
)

// --- MainActivity ---
class MainActivity : ComponentActivity() {
    private lateinit var usbManager: UsbManager
    private val usbPermissionReceiver = UsbPermissionReceiver()
    private var usbPermissionCallback: ((Boolean, UsbDevice?) -> Unit)? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        Toast.makeText(
            this,
            "İnternet izni ${if (isGranted) "verildi" else "reddedildi"}",
            Toast.LENGTH_SHORT
        ).show()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        val filter = IntentFilter(ACTION_USB_PERMISSION)
        // Android Tiramisu (API 33) ve sonrası için Receiver Flag ayarı
        val receiverFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Context.RECEIVER_NOT_EXPORTED // Daha güvenli: Receiver'ı export etme
        } else {
            0 // Eski sürümler için flag gerekmez
        }
        registerReceiver(usbPermissionReceiver, filter, receiverFlags)


        enableEdgeToEdge()
        setContent {
            DroneControllerTheme {
                DroneControllerApp(
                    requestUsbPermission = { device, onResult ->
                        usbPermissionCallback = onResult
                        requestPermission(device)
                    },
                    checkUsbPermission = { device ->
                        usbManager.hasPermission(device)
                    },
                    requestInternetPermission = {
                        requestPermissionLauncher.launch(Manifest.permission.INTERNET)
                    },
                    getUsbDevices = { usbManager.deviceList.values.toList() }
                )
            }
        }
    }

    private fun requestPermission(device: UsbDevice) {
        // Android S (API 31) ve sonrası için PendingIntent Flag ayarı
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // MUTABLE yerine IMMUTABLE kullanmak daha güvenli olabilir eğer broadcast alıcısı
            // PendingIntent'i değiştirmiyorsa. Burada sadece intent action'ı kontrol ediliyor,
            // MUTABLE gerekli olmayabilir. Ancak örneklerde MUTABLE da görülebiliyor.
            // Eğer sorun yaşanırsa MUTABLE yerine IMMUTABLE deneyin.
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val permissionIntent = PendingIntent.getBroadcast(
            this, USB_PERMISSION_REQUEST_CODE, Intent(ACTION_USB_PERMISSION), flags
        )
        Log.d(TAG, "USB izni isteniyor: ${device.deviceName}")
        usbManager.requestPermission(device, permissionIntent)
    }

    inner class UsbPermissionReceiver : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (ACTION_USB_PERMISSION == intent.action) {
                synchronized(this) {
                    // Android Tiramisu (API 33) ve sonrası için güvenli getParcelableExtra kullanımı
                    val device: UsbDevice? =
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            intent.getParcelableExtra(
                                UsbManager.EXTRA_DEVICE,
                                UsbDevice::class.java
                            )
                        } else {
                            @Suppress("DEPRECATION") // Eski sürümler için Suppress
                            intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                        }
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    Log.d(TAG, "USB izin yanıtı alındı: ${device?.deviceName}, İzin: $granted")
                    // Callback null değilse çağır ve sonra null yap
                    usbPermissionCallback?.invoke(granted, device)
                    usbPermissionCallback = null // Callback'i temizle
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(usbPermissionReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver zaten kayıtlı değilse hata vermesini engelle
            Log.w(TAG, "Receiver zaten kayıtlı değildi veya kaldırılamadı.")
        }
        usbPermissionCallback = null // Callback referansını temizle
        Log.d(TAG, "MainActivity onDestroy")
    }

    companion object {
        const val TAG = "DroneController"
        const val ACTION_USB_PERMISSION = "com.bysoftware.dronecontroller.USB_PERMISSION"
        const val USB_PERMISSION_REQUEST_CODE = 1234
        const val MAVSDK_SERVER_UDP_PORT = 14540
        const val DEFAULT_BAUD_RATE = 115200 // Drone'unuza göre ayarlayın (57600, 921600 vb. olabilir)
    }
}

// --- DroneControllerApp Composable Function ---
@SuppressLint("CoroutineCreationDuringComposition")
@Composable
fun DroneControllerApp(
    requestUsbPermission: (device: UsbDevice, onResult: (Boolean, UsbDevice?) -> Unit) -> Unit,
    checkUsbPermission: (UsbDevice) -> Boolean,
    requestInternetPermission: () -> Unit,
    getUsbDevices: () -> List<UsbDevice>
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbarHostState = remember { SnackbarHostState() }

    // --- State Variables ---
    var connectionStatusProxy by remember { mutableStateOf("Bağlantı yok (Proxy)") }
    var telemetryDataProxy by remember { mutableStateOf("Veri bekleniyor (Proxy)...") }
    var isProxyConnected by remember { mutableStateOf(false) }
    var connectionStatusDirect by remember { mutableStateOf("Bağlantı yok (Direct)") }
    var directTelemetryState by remember { mutableStateOf(DirectTelemetryState()) }
    var isDirectConnected by remember { mutableStateOf(false) }
    var selectedUsbDevice by remember { mutableStateOf<UsbDevice?>(null) }
    var usbDevices by remember { mutableStateOf<List<UsbDevice>>(emptyList()) }
    var showDeviceSelector by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf<String?>(null) } // "Proxy" or "Direct" or null

    // --- Resources ---
    // Proxy Resources
    var mavsdkServer by remember { mutableStateOf<MavsdkServer?>(null) }
    var drone by remember { mutableStateOf<System?>(null) }
    val disposablesProxy = remember { CompositeDisposable() } // Proxy için RxJava abonelikleri
    var proxyUsbPort by remember { mutableStateOf<UsbSerialPort?>(null) }
    var proxyUsbConnection by remember { mutableStateOf<UsbDeviceConnection?>(null) }
    var udpSocket by remember { mutableStateOf<DatagramSocket?>(null) }
    var proxyJob by remember { mutableStateOf<Job?>(null) } // USB <-> UDP köprü korutini

    // Direct Resources
    var directUsbPort by remember { mutableStateOf<UsbSerialPort?>(null) }
    var directUsbConnection by remember { mutableStateOf<UsbDeviceConnection?>(null) }
    var directIoManager by remember { mutableStateOf<SerialInputOutputManager?>(null) }
    var directParserJob by remember { mutableStateOf<Job?>(null) } // MAVLink ayrıştırma korutini (ve pipe yazarını içerir)
    // Veri kanalı: IO Manager -> PipeWriter Coroutine -> Pipe -> MavlinkConnection Parser
    // Bu kanal, IO Manager'dan gelen ham baytları pipe'a yazan korutine iletir.
    val directDataChannel =
        remember { Channel<ByteArray>(Channel.BUFFERED) } // Buffer boyutu ayarlanabilir

    // Direct bağlantı için MAVLink yazma (komut gönderme) OutputStream'i
    // MAVLink komutlarını göndermek için MavlinkConnection.send() metodunu kullanırken
    // bu OutputStream'e yazılacaktır.
    var directCommandOutputStream by remember { mutableStateOf<OutputStream?>(null) }


    // --- Helper Functions ---
    // Hata mesajlarını göstermek ve loglamak için yardımcı fonksiyon
    fun showError(message: String, type: String = "Genel") {
        Log.e(MainActivity.TAG, "[$type Hata] $message")
        scope.launch { snackbarHostState.showSnackbar("[$type Hata] $message") }
        // Eğer hata, devam eden bağlantı işlemi sırasında oluştuysa, işlemi durdur
        if (isConnecting == type || type == "Genel") {
            isConnecting = null
            // Hatanın türüne göre ilgili bağlantı durumunu güncelle
            when (type) {
                "Proxy" -> connectionStatusProxy = "Hata: $message"
                "Direct" -> connectionStatusDirect = "Hata: $message"
                else -> { // Genel hata durumunda her ikisini de güncelle
                    connectionStatusProxy = "Hata: $message"
                    connectionStatusDirect = "Hata: $message"
                }
            }
        } else {
            // Bağlantı sırasında değilse sadece durumu güncelle
            when (type) {
                "Proxy" -> connectionStatusProxy = "Hata: $message"
                "Direct" -> connectionStatusDirect = "Hata: $message"
            }
        }
    }
    // Double/Float/Int değerleri formatlamak için yardımcı fonksiyonlar
    val decimalFormat = remember { DecimalFormat("#.######") } // Daha fazla hassasiyet için
    val decimalFormatShort = remember { DecimalFormat("#.##") } // Daha az hassasiyet için
    fun Double?.format(digits: Int = 6): String =
        if (this == null) "N/A" else if (digits <= 2) decimalFormatShort.format(this) else decimalFormat.format(
            this
        )

    fun Float?.format(digits: Int = 2): String = this?.toDouble().format(digits)
    fun Int?.format(): String = this?.toString() ?: "N/A"


    // --- Resource Cleanup Functions ---
    // Direct bağlantı ile ilgili tüm kaynakları temizler
    fun cleanUpDirectResources() {
        Log.d(MainActivity.TAG, "[Direct] Kaynaklar temizleniyor...")

        // Parser Job'ı iptal et. Bu, MAVLink okuma döngüsünü ve bağlıysa pipeWriterJob'ı sonlandırır.
        directParserJob?.cancel(); directParserJob = null
        Log.d(MainActivity.TAG, "[Direct] Parser Job iptal edildi.")

        // Kanalı kapat. Bu, pipeWriterJob'ın channel.receiveAsFlow().collect döngüsünü sonlandırır.
        // Eğer pipeWriterJob zaten channel.receive() kullanıyorsa, bu, receive() metodunda ClosedReceiveChannelException fırlatır.
        // Her iki durumda da writer job'ın sonlanmasına yardımcı olur.
        directDataChannel.close()
        Log.d(MainActivity.TAG, "[Direct] Data Channel kapatıldı.")

        // IO Manager'ı durdurmadan önce null kontrolü
        directIoManager?.let {
            it.stop() // IO Manager'ın kendi thread'ini durdurur
            Log.d(MainActivity.TAG, "[Direct] IO Manager durduruldu.")
        }
        directIoManager = null

        // Command OutputStream'i kapat (eğer varsa). Bu, MavlinkConnection'ın yazma ucunu kapatır.
        // Eğer bu bir PipedOutputStream ise, diğer ucun (MavlinkConnection içindeki PipeInputStream) hata vermesine neden olur.
        try {
            directCommandOutputStream?.close()
        } catch (e: IOException) {
            Log.e(MainActivity.TAG, "[Direct] Command OutputStream kapatılırken hata", e)
        } finally {
            directCommandOutputStream = null
        }


        // USB Port ve Connection'ı kapatmadan önce null kontrolü
        // Portu kapatmak, IO Manager'ın (eğer durdurulmadıysa) hata vermesine neden olur.
        try {
            directUsbPort?.close()
        } catch (e: IOException) {
            Log.e(MainActivity.TAG, "[Direct] USB port kapatılırken hata", e)
        } finally {
            directUsbPort = null
        }
        try {
            directUsbConnection?.close()
        } catch (e: Exception) {
            Log.e(MainActivity.TAG, "[Direct] USB connection kapatılırken hata", e)
        } finally {
            directUsbConnection = null
        }

        Log.d(MainActivity.TAG, "[Direct] Kaynaklar temizlendi.")
    }

    // Direct USB bağlantısını keser ve kaynakları temizler
    fun disconnectDirectUsb(showStatus: Boolean = true) {
        // Zaten bağlı değilse veya kaynaklar temizse çık
        if (!isDirectConnected && directIoManager == null && directUsbPort == null && directParserJob == null && directDataChannel.isClosedForSend && directCommandOutputStream == null) {
            Log.d(
                MainActivity.TAG,
                "[Direct] Zaten bağlı değil veya temizlenmiş durumda, bağlantı kesme iptal edildi."
            )
            return
        }
        Log.d(MainActivity.TAG, "[Direct] Bağlantı kesiliyor...")
        if (showStatus) connectionStatusDirect = "Bağlantı kesiliyor..."
        cleanUpDirectResources() // Tüm kaynakları temizleyen fonksiyonu çağır
        isDirectConnected = false // Bağlantı durumu state'ini false yap
        if (showStatus) connectionStatusDirect =
            "Bağlantı kesildi (Direct)" // UI durum mesajını güncelle
        directTelemetryState = DirectTelemetryState() // Telemetri verilerini sıfırla
        Log.d(MainActivity.TAG, "[Direct] Bağlantı kesildi state güncellendi.")
        if (isConnecting == "Direct") isConnecting =
            null // Bağlanma işlemi sırasında kesildiyse durumu sıfırla
    }

    // Proxy bağlantı ile ilgili tüm kaynakları temizler
    fun cleanUpProxyResources() {
        Log.d(MainActivity.TAG, "[Proxy] Kaynaklar temizleniyor...")
        proxyJob?.cancel(); proxyJob = null; Log.d(
            MainActivity.TAG,
            "[Proxy] Proxy görevleri iptal edildi."
        )
        // Socket, Port, Connection kapatmadan önce null kontrolü
        try {
            udpSocket?.close()
        } catch (e: Exception) {
            Log.e(MainActivity.TAG, "[Proxy] UDP soketi kapatılırken hata", e)
        } finally {
            udpSocket = null
        }
        try {
            proxyUsbPort?.close()
        } catch (e: IOException) {
            Log.e(MainActivity.TAG, "[Proxy] USB port kapatılırken hata", e)
        } finally {
            proxyUsbPort = null
        }
        try {
            proxyUsbConnection?.close()
        } catch (e: Exception) {
            Log.e(MainActivity.TAG, "[Proxy] USB connection kapatılırken hata", e)
        } finally {
            proxyUsbConnection = null
        }
        disposablesProxy.clear() // RxJava aboneliklerini temizle
        // Drone ve Server'ı dispose/stop etmeden önce null kontrolü
        try {
            drone?.dispose()
        } catch (e: Exception) {
            Log.e(MainActivity.TAG, "[Proxy] Drone dispose edilirken hata", e)
        } finally {
            drone = null
        }
        try {
            mavsdkServer?.stop()
        } catch (e: Exception) {
            Log.e(MainActivity.TAG, "[Proxy] MAVSDK Server durdurulurken hata", e)
        } finally {
            mavsdkServer = null
        }
        Log.d(MainActivity.TAG, "[Proxy] Kaynaklar temizlendi.")
    }

    // Proxy bağlantısını keser ve kaynakları temizler
    fun disconnectProxy(showStatus: Boolean = true) {
        // Zaten bağlı değilse veya kaynaklar temizse çık
        if (!isProxyConnected && proxyJob == null && drone == null && mavsdkServer == null && proxyUsbPort == null && udpSocket == null) {
            Log.d(
                MainActivity.TAG,
                "[Proxy] Zaten bağlı değil veya temizlenmiş durumda, bağlantı kesme iptal edildi."
            )
            return
        }
        Log.d(MainActivity.TAG, "[Proxy] Bağlantı kesiliyor...")
        if (showStatus) connectionStatusProxy = "Bağlantı kesiliyor..."
        cleanUpProxyResources() // Tüm kaynakları temizleyen fonksiyonu çağır
        isProxyConnected = false // Bağlantı durumu state'ini false yap
        if (showStatus) connectionStatusProxy =
            "Bağlantı kesildi (Proxy)" // UI durum mesajını güncelle
        telemetryDataProxy = "Veri bekleniyor (Proxy)..." // Telemetri metnini sıfırla
        Log.d(MainActivity.TAG, "[Proxy] Bağlantı kesildi state güncellendi.")
        if (isConnecting == "Proxy") isConnecting =
            null // Bağlanma işlemi sırasında kesildiyse durumu sıfırla
    }

    // --- UDP Proxy Coroutine ---
    // USB Seri Port <-> UDP Soket arasında veri köprüsü kurar.
    // MAVSDK Proxy bağlantısı için kullanılır.
    // --- UDP Proxy Coroutine ---
// USB Seri Port <-> UDP Soket arasında **ÇİFT YÖNLÜ** veri köprüsü kurar.
    fun startUdpProxy(
        usbPort: UsbSerialPort,
        udpSocket: DatagramSocket,
        targetAddress: InetAddress, // Hedef MAVSDK sunucu adresi (örn. localhost)
        targetPort: Int,        // Hedef MAVSDK sunucu portu (örn. 14540)
        bufferSize: Int = 1024  // Buffer boyutunu artırmak faydalı olabilir
    ): Job {
        // Ana proxy korutinini başlat
        return CoroutineScope(Dispatchers.IO + CoroutineName("udp-proxy-main")).launch {
            Log.d("UdpProxy", "Çift yönlü proxy başlatılıyor...")

            // 1. USB -> UDP yönü için korutin
            val usbToUdpJob = launch(CoroutineName("proxy-usb-to-udp")) {
                val usbBuffer = ByteArray(bufferSize)
                try {
                    while (isActive) {
                        // USB'den oku (blocking olabilir, ama IO dispatcher'da)
                        // Daha sağlam olması için timeout ile okuma düşünülebilir: usbPort.read(usbBuffer, usbBuffer.size, READ_WAIT_MILLIS)
                        val len = usbPort.read(usbBuffer, usbBuffer.size)
                        if (len > 0) {
                            val payload = usbBuffer.copyOf(len)
                            // İsteğe bağlı: Detaylı loglama
                            // val hexData = payload.joinToString(" ") { b -> b.toUByte().toString(16).padStart(2, '0') }
                            // Log.d("UdpProxy", "USB -> UDP [$len byte]: $hexData")

                            // Geçersiz MAVLink baytlarını kontrol et (isteğe bağlı, loglama için)
                            if (payload.isNotEmpty() && payload[0].toUByte().toInt() != 0xFE && payload[0].toUByte().toInt() != 0xFD) {
                                Log.w("UdpProxy", "USB'den gelen veri MAVLink ile başlamıyor (ilk bayt: 0x${payload[0].toUByte().toString(16)})")
                            }

                            // UDP'ye gönder (DatagramPacket hedef adresi belirtmeli)
                            val packet = DatagramPacket(payload, payload.size, targetAddress, targetPort)
                            try {
                                udpSocket.send(packet)
                            } catch (e: Exception) {
                                Log.e("UdpProxy", "UDP gönderme hatası (USB->UDP): ${e.message}", e)
                                // Hata devam ederse proxy'yi durdurmak gerekebilir.
                                // delay(100) // Hata durumunda kısa bekleme
                            }
                        } else if (len < 0) {
                            // Okuma hatası (örn. port kapandı)
                            Log.e("UdpProxy", "USB okuma hatası (len < 0). USB->UDP durduruluyor.")
                            break // Döngüyü sonlandır
                        }
                        // else len == 0 -> Veri yok, döngüye devam et
                        // CPU'yu yormamak için küçük bir gecikme eklenebilir (veri gelmediğinde)
                        if (len == 0) delay(1) // Çok kısa bekleme
                    }
                } catch (e: IOException) {
                    if (isActive) {
                        Log.e("UdpProxy", "USB okuma IO Hatası (USB->UDP): ${e.message}", e)
                    } else {
                        Log.d("UdpProxy", "USB okuma iptal edildi (USB->UDP).")
                    }
                } catch (e: Exception) {
                    if (isActive) Log.e("UdpProxy", "Beklenmedik Hata (USB->UDP): ${e.message}", e)
                } finally {
                    Log.d("UdpProxy", "USB -> UDP yönü tamamlandı.")
                    // Bir yön biterse diğerini de durdurmak için ana korutini iptal etmeyi düşünebiliriz
                    // cancel() // Bu, diğer job'ın da bitmesini sağlar
                }
            }

            // 2. UDP -> USB yönü için korutin
            val udpToUsbJob = launch(CoroutineName("proxy-udp-to-usb")) {
                val udpBuffer = ByteArray(bufferSize)
                val packet = DatagramPacket(udpBuffer, udpBuffer.size)
                try {
                    while (isActive) {
                        try {
                            // UDP soketinden veri al (blocking)
                            udpSocket.receive(packet)

                            val len = packet.length
                            if (len > 0) {
                                val payload = packet.data.copyOf(len) // Alınan veriyi kopyala
                                // İsteğe bağlı: Detaylı loglama
                                // val hexData = payload.joinToString(" ") { b -> b.toUByte().toString(16).padStart(2, '0') }
                                // Log.d("UdpProxy", "UDP -> USB [$len byte] from ${packet.socketAddress}: $hexData")

                                // Alınan veriyi USB porta yaz
                                try {
                                    // Timeout ile yazma daha güvenli olabilir: usbPort.write(payload, WRITE_WAIT_MILLIS)
                                    usbPort.write(payload, 500) // 500ms timeout ile yazma
                                } catch(writeError: IOException) {
                                    Log.e("UdpProxy", "USB yazma hatası (UDP->USB): ${writeError.message}")
                                    break // Yazma hatasında bu yönü durdur
                                }
                            }
                        } catch (e: IOException) {
                            // UDP receive sırasında IO hatası (örn. soket kapandı)
                            if (isActive) {
                                Log.e("UdpProxy", "UDP alma IO Hatası (UDP->USB): ${e.message}", e)
                                break // Soket hatasında bu yönü durdur
                            } else {
                                Log.d("UdpProxy", "UDP alma iptal edildi (UDP->USB).")
                            }
                        }
                    }
                } catch (e: Exception) {
                    if (isActive) Log.e("UdpProxy", "Beklenmedik Hata (UDP->USB): ${e.message}", e)
                } finally {
                    Log.d("UdpProxy", "UDP -> USB yönü tamamlandı.")
                    // Bir yön biterse diğerini de durdurmak için ana korutini iptal etmeyi düşünebiliriz
                    // cancel() // Bu, diğer job'ın da bitmesini sağlar
                }
            }

            // İki yönlü iş bitene kadar bekle (veya ana korutin iptal edilene kadar)
            try {
                // İki job'ın da tamamlanmasını bekle. joinAll iptal edilirse exception fırlatmaz.
                joinAll(usbToUdpJob, udpToUsbJob)
            } finally {
                Log.d("UdpProxy", "Çift yönlü proxy tamamlandı veya iptal edildi.")
                // Soketi ve portu kapatmak ana bağlantı fonksiyonunun (connectProxy)
                // catch veya finally bloğunun sorumluluğudur. Proxy job bittiğinde
                // otomatik kapanmazlar.
            }
        } // Ana proxy korutini sonu
    }


// `connectProxy` fonksiyonunda `startUdpProxy` çağrısını şu şekilde güncelleyin:
// ...
// 5. USB <-> UDP proxy'yi BAŞLAT
// Çift yönlü proxy fonksiyonunu çağırıyoruz.
// ...


    // --- MAVSDK Telemetry Listener ---
    // MAVSDK System objesi üzerinden gelen telemetri verilerini dinler ve UI state'lerini günceller.
    // Proxy bağlantısı için kullanılır.
    @SuppressLint("CheckResult") // RxJava abonelik sonuçlarını manuel yönettiğimiz için uyarıyı bastır
    fun setupTelemetryListeners(droneSystem: System) {
        Log.d(MainActivity.TAG, "[Proxy] Telemetri dinleyicileri ayarlanıyor...")
        disposablesProxy.clear() // Önceki RxJava aboneliklerini temizle

        // Konum Bilgisi aboneliği
        disposablesProxy.add(
            droneSystem.telemetry.position
                .subscribeOn(Schedulers.io()) // Dinleme işlemini IO thread'inde yap
                .observeOn(AndroidSchedulers.mainThread()) // UI güncellemesini Main thread'de yap
                .subscribe(
                    { position ->
                        // Gelen konum verisi ile telemetri stringini güncelle
                        telemetryDataProxy =
                            "Lat: ${position.latitudeDeg.format()}, Lon: ${position.longitudeDeg.format()}, Alt: ${
                                position.relativeAltitudeM.format(2)
                            } m"
                    },
                    { error ->
                        // Hata durumunu logla
                        Log.w(MainActivity.TAG, "[Proxy] Konum verisi alınamadı: ${error.message}")
                    }
                )
        )

        // Armed Durumu aboneliği
        disposablesProxy.add(
            droneSystem.telemetry.armed
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { armed ->
                        Log.d(
                            MainActivity.TAG,
                            "[Proxy] Arm Durumu: ${if (armed) "Armed" else "Disarmed"}"
                        )
                        // İsterseniz bu durumu UI'da Proxy telemetri kısmında gösterebilirsiniz.
                    },
                    { error ->
                        Log.w(MainActivity.TAG, "[Proxy] Arm durumu alınamadı: ${error.message}")
                    }
                )
        )

        disposablesProxy.add(
            droneSystem.telemetry.altitude
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { imu ->
                        println("imu = " +imu)

                    },
                    { error ->
                        Log.w(MainActivity.TAG, "[Proxy] Imu verisi alınamadı: ${error.message}")
                    }
                )

        )

        // Batarya Durumu aboneliği
        disposablesProxy.add(
            droneSystem.telemetry.battery
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { battery ->
                        // Batarya yüzdesini al, null ise "N/A" göster
                        val batteryText =
                            battery.remainingPercent?.let { "${(it * 100).toInt()}%" } ?: "N/A"
                        Log.d(MainActivity.TAG, "[Proxy] Pil: $batteryText")
                        // İsterseniz bu durumu UI'da Proxy telemetri kısmında gösterebilirsiniz.
                    },
                    { error ->
                        Log.w(MainActivity.TAG, "[Proxy] Pil verisi alınamadı: ${error.message}")
                    }
                )
        )

        // Diğer telemetri verileri (hız, tutum, uçuş modu vb.) için benzer abonelikler eklenebilir.
        // droneSystem.telemetry.flightMode.subscribe(...)
        // droneSystem.telemetry.attitudeEuler.subscribe(...)
        // droneSystem.telemetry.velocityNed.subscribe(...)
    }


    // --- MAVLink Mesaj İşleyici (Direct Connection için) ---
    // io.dronefleet.mavlink kütüphanesinden gelen MAVLink mesajlarını işler ve DirectTelemetryState'i günceller.
    // Bu fonksiyon, MAVLink parser korutini içinden çağrılır.
    fun processMavlinkMessage(message: MavlinkMessage<*>) {
        val payload = message.payload // Mesajın asıl içeriği (payload)
        val timestamp = java.lang.System.currentTimeMillis() // Mesajın işlenme zamanı

        // UI state güncellemeleri güvenli bir şekilde Main thread'de yapılmalı.
        // Mevcut korutin zaten IO thread'inde çalışıyor olabilir, bu yüzden Main thread'e geçiş yapıyoruz.
        scope.launch(Dispatchers.Main) {
            // Mevcut telemetry durumunu kopyalayarak başlıyoruz.
            // Bu, sadece gelen mesajın ilgili alanlarını güncellemeyi sağlar.
            // Her mesaj geldiğinde tüm state sıfırlanmaz.
            var newState = directTelemetryState.copy(
                // Her mesaj için geçerli olan alanları güncelle
                systemId = message.originSystemId, // Mesajı gönderen aracın sistem ID'si
                componentId = message.originComponentId, // Mesajı gönderen bileşenin ID'si
                lastMessageTimestamp = timestamp // Son mesajın alınma zamanı
                // Mavlink versiyonu Heartbeat mesajından güncellenir
            )

            // Gelen mesajın payload türüne göre ilgili alanları güncelle
            newState = when (val p = payload) { // 'p' değişkeni gelen mesaj payload'ını temsil eder
                is Heartbeat -> {
                    // customMode değeri dialect'e göre farklı anlamlara gelebilir.
                    // Temel MAVLink'te 0'dır, ArduPilot, PX4 gibi sistemlerde özel modları belirtir.
                    // MavMode enum'unu kullanarak değeri string'e çevirmeyi deniyoruz.
                    val currentMode = try {
                        // fromValue metodunu kullanarak raw integer değerden enum'a çevir
                        MavMode.valueOf(p.customMode().toString())?.name
                            ?: "CUSTOM(${p.customMode()})"
                    } catch (e: Exception) {
                        // Eğer fromValue hata verirse (örn. customMode enum'da yoksa) ham değeri göster
                        "CUSTOM(${p.customMode()})"
                    }

                    newState.copy(
                        // baseMode field'ı MavModeFlag bit maskesidir. ARMED durumu MAV_MODE_FLAG_SAFETY_ARMED flag'i ile kontrol edilir.
                        armed = p.baseMode().flagsEnabled(MavModeFlag.MAV_MODE_FLAG_SAFETY_ARMED),
                        mode = currentMode,
                        // Autopilot ve SystemStatus (MavState) enumlarını raw değerden almaya çalışıyoruz.
                        // fromValue null dönebilir eğer değer enum'da yoksa.
                        //autopilotType = MavAutopilot.entries.find { it.value == p.autopilot().value() }?.ordinal
                        //autopilotValue = p.autopilot().value()
                        //autoPi = MavAutopilot.entries.find { it.value == autopilotValue }?.ordinal
                        autopilotType = p.autopilot().value(), // ordinal yerine direkt int değeri
                        systemStatus = p.systemStatus().value(), // .value() raw int değeri verir
                        // Mavlink versiyonunu Heartbeat mesajından al
                        mavlinkVersion = p.mavlinkVersion().toInt() // Byte'ı Int'e çevir
                    )
                }

                is GpsRawInt -> newState.copy(
                    // GpsFixType enum'unu raw integer değerden al ve ordinal değerini kullan (0:NoFix, 1:NoGPS, 2:2D, 3:3D, ...)
                    fixType = p.fixType().value(),
                    satellitesVisible = p.satellitesVisible().toInt(), // UByte'ı Int'e çevir
                    latitude = p.lat() / 1E7, // Ölçek faktörünü uygula (int'ten double'a)
                    longitude = p.lon() / 1E7, // Ölçek faktörünü uygula (int'ten double'a)
                    // COG (Course Over Ground) değeri 65535 ise geçersizdir (N/A)
                    heading = if (p.cog() == 65535) null else p.cog() / 100.0 // Santidereceden dereceye çevir
                )

                is GlobalPositionInt -> newState.copy(
                    latitude = p.lat() / 1E7, // int'ten double'a
                    longitude = p.lon() / 1E7, // int'ten double'a
                    altitudeMsl = p.alt() / 1000.0, // mm'den m'ye (int'ten double'a)
                    relativeAltitude = p.relativeAlt() / 1000.0, // mm'den m'ye (int'ten double'a)
                    // Heading değeri 65535 ise geçersizdir (N/A)
                    heading = if (p.hdg() == 65535) null else p.hdg() / 100.0 // Santidereceden dereceye çevir
                )

                is VfrHud -> newState.copy(
                    airSpeed = p.airspeed().toDouble(), // Float'tan Double'a
                    groundSpeed = p.groundspeed().toDouble(), // Float'tan Double'a
                    throttle = p.throttle().toInt(), // UShort'tan Int'e (Yüzde değeri)
                    climbRate = p.climb().toDouble() // Float'tan Double'a
                )

                is Attitude -> newState.copy(
                    // Radyan cinsinden açıları dereceye çevir (Float'tan Double'a)
                    roll = Math.toDegrees(p.roll().toDouble()),
                    pitch = Math.toDegrees(p.pitch().toDouble()),
                    yaw = Math.toDegrees(
                        p.yaw().toDouble()
                    ) // Genellikle manyetik yön değil, aracın kendi eksenindeki dönüşü
                )

                is SysStatus -> newState.copy(
                    batteryVoltage = p.voltageBattery() / 1000.0, // mV'tan V'a (UShort'tan Double'a)
                    // Akım -1 ise geçersizdir (null yap)
                    batteryCurrent = p.currentBattery()
                        .let { if (it < 0) null else it / 100.0 }, // cA'dan A'ya (Short'tan Double'a)
                    // Kalan batarya -1 ise geçersizdir (null yap)
                    batteryRemaining = p.batteryRemaining()
                        .let { if (it < 0) null else it?.toInt() } // Byte'tan Int'e
                )

                is BatteryStatus -> {
                    // BatteryStatus mesajı birden fazla batarya için bilgi içerebilir.
                    // Genellikle id=0 ana bataryayı temsil eder. Sadece id=0 olanı işleyelim.
                    if (p.id().toInt() == 0) { // UByte'ı Int'e çevir
                        newState.copy(
                            // Akım -1 ise geçersizdir (null yap)
                            batteryCurrent = p.currentBattery()
                                .let { if (it < 0) null else it / 100.0 }, // cA'dan A'ya (Short'tan Double'a)
                            // Kalan batarya -1 ise geçersizdir (null yap)
                            batteryRemaining = p.batteryRemaining()
                                .let { if (it < 0) null else it?.toInt() } // Byte'tan Int'e
                            // Not: BatteryStatus'ta voltaj genellikle liste halindedir (p.voltages()),
                            // Tekil voltaj için SysStatus daha güvenilir olabilir veya voltages[0] kullanılabilir.
                            // batteryVoltage = p.voltages().firstOrNull()?.let { if(it == UShort.MAX_VALUE.toUShort()) null else it.toDouble() / 1000.0 } // mV'tan V'a
                        )
                    } else {
                        newState // Diğer bataryaları şimdilik göz ardı et
                    }
                }

                is Statustext -> {
                    // Severity enum'unu raw integer değerden al ve formatla
                    val severity = p.severity()
                        .value()                    // p.text() ByteArray döndürür, String'e çevirirken null terminator'dan sonrasını kesmek gerekebilir.
                    val textMessage = try {
                        String(p.text().takeWhile { it.code.toByte() != 0.toByte() }.toByteArray())
                    } catch (e: Exception) {
                        "Decode Error"
                    }
                    newState.copy(statusText = "[$severity] $textMessage")
                }

                else -> {
                    // Bilinmeyen veya işlenmeyen mesaj türü için mevcut durumu koru
                    // Hangi mesajların işlenmediğini görmek için loglama ekleyebilirsiniz:
                    // Log.d(TAG, "[Direct Parser] Unhandled MAVLink message type: ${payload::class.java.simpleName}")
                    newState
                }
            }
            // Son güncellenmiş durumu state değişkenine ata
            directTelemetryState = newState
        }
    }


    // --- Serial Input Output Manager Listener ---
    // USB seri portundan gelen ham baytları alır ve directDataChannel'a gönderir.
    // Direct bağlantı için kullanılır.
    /* private inner class DirectUsbSerialListener2 : SerialInputOutputManager.Listener {
         override fun onNewData(data: ByteArray) {
             // Gelen veriyi directDataChannel'a gönder
             // Bu, IO Manager'ın kendi thread'inde çalışır.
             // Kanal kapalıysa ClosedSendChannelException fırlatır, bu yüzden try-catch içinde alıyoruz.
             // UI thread'ini bloke etmemek için scope.launch kullanıyoruz ancak burada zaten IO thread'indeyiz.
             // Yine de korutin içinde send kullanmak IO güvenlidir.
             scope.launch(Dispatchers.IO) {
                 try {
                     // Kanal açık ve aktifse veriyi gönder
                     if (!directDataChannel.isClosedForSend) {
                         directDataChannel.send(data) // Bloklayabilir eğer kanal doluysa
                     } else {
                         // Kanal kapalıysa gönderme logu
                         Log.w(MainActivity.TAG, "[Direct Listener] Attempted to send data, but channel is closed.")
                     }
                 } catch (e: ClosedSendChannelException) {
                     // Kanal kapatılmış, bu normal bir durum olabilir (bağlantı kesildiğinde parser durdurulur ve kanal kapanır)
                     Log.w(MainActivity.TAG, "[Direct Listener] Channel closed while trying to send data.")
                 } catch (e: Exception) {
                     // Diğer olası hatalar
                     Log.e(MainActivity.TAG, "[Direct Listener] Error sending data to channel", e)
                 }
             }
         }

         // IO Manager'da bir hata oluştuğunda çağrılır (örn. USB bağlantısı kesildiğinde)
         override fun onRunError(e: Exception) {
             Log.e(MainActivity.TAG, "[Direct Listener] IO Manager Run Error: ${e.message}", e)
             // IO Manager hata verdiğinde (örn. USB bağlantısı kesildiğinde) bağlantıyı kes
             // UI thread'ine geçerek hata mesajı göster ve bağlantıyı kesme fonksiyonunu çağır.
             scope.launch(Dispatchers.Main) {
                 // Hata mesajını göster
                 showError("Seri port hatası: ${e.message?.take(100)}", "Direct")
                 // Bağlantıyı temizle. Bu Direct bağlantı ile ilgili tüm kaynakları kapatır.
                 disconnectDirectUsb()
             }
         }
     }*/

    // --- MAVLink Parser Coroutine (Direct Connection) ---
    // SerialInputOutputManager'dan gelen ham baytları (kanal ve pipe üzerinden) MavlinkConnection ile okuyup ayrıştırır.
    // Aynı zamanda MAVLink komutlarını göndermek için bir OutputStream kullanır.
    fun startDirectMavlinkParser(
        inputStream: InputStream,
        outputStream: OutputStream? = null
    ): Job {
        return scope.launch(Dispatchers.IO + CoroutineName(name = "mavlink-parser")) {
            Log.d(MainActivity.TAG, "[Direct] MAVLink Parser Coroutine başlatıldı.")

            val connection: MavlinkConnection
            try {
                // MAVLink Bağlantısını InputStream (okuma için) ve OutputStream (yazma için) ile oluştur
                // InputStream PipedInputStream olmalı, OutputStream doğrudan porta yazan implementasyon olmalı.
                connection = MavlinkConnection.builder(inputStream, outputStream)
                    // Desteklenecek MAVLink dialect'lerini ekleyin. Common genellikle zorunludur.
                    .dialect(MavAutopilot.MAV_AUTOPILOT_GENERIC, StandardDialect())
                    .dialect(
                        MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA,
                        ArdupilotmegaDialect()
                    )                    // Eğer ArduPilot kullanıyorsanız ArduPilotmega dialect'i ekleyin.
                    // .dialect(MavlinkDialect.ARDUPILOTMEGA)
                    // Eğer PX4 kullanıyorsanız veya başka dialectler gerekiyorsa ekleyin.
                    // .dialect(...)
                    .build()
                Log.d(MainActivity.TAG, "[Direct Parser] MavlinkConnection oluşturuldu.")
            } catch (e: Exception) {
                // MavlinkConnection oluşturulurken hata oluşursa
                Log.e(MainActivity.TAG, "[Direct Parser] Failed to build MavlinkConnection", e)
                withContext(Dispatchers.Main + NonCancellable) {
                    showError(
                        "Parser başlatma hatası: MavlinkConnection oluşturulamadı. ${
                            e.message?.take(
                                100
                            )
                        }", "Direct"
                    )
                }
                // Bu korutini sonlandır
                return@launch
            }

            // MAVLink mesajlarını oku ve işle
            try {
                while (isActive) { // Korutin veya bağlı stream (InputStream) aktif olduğu sürece devam et
                    // connection.next() metodu, InputStream'den okur ve tam bir MAVLink mesajı gelene kadar bloklanır.
                    // Eğer InputStream kapanırsa, null döndürebilir veya IOException fırlatabilir.
                    val message = connection.next()
                    if (message != null) {
                        // Başarıyla ayrıştırılan MAVLink mesajını işleyici fonksiyona gönder
                        // processMavlinkMessage fonksiyonu zaten UI güncellemesi için Main thread'e geçiş yapıyor.
                        processMavlinkMessage(message)
                        // İsteğe bağlı log: Her ayrıştırılan mesajı loglayabilirsiniz (performans etkisi olabilir)
                        // Log.d(TAG, "[Direct Parser] Processed message: ${message.payload::class.java.simpleName}")
                    } else {
                        // next() null döndürürse genellikle stream'in sonuna ulaşıldığı veya kapatıldığı anlamına gelir.
                        Log.d(
                            TAG,
                            "[Direct Parser] connection.next() returned null. Stream ended or closed."
                        )
                        break // Mesaj okuma döngüsünden çık
                    }
                }
            } catch (e: IOException) {
                // InputStream'den okuma sırasında IO hatası (pipe kapatıldıysa, USB bağlantısı kesildiyse vb.)
                // Bu genellikle Direct bağlantının kesildiği anlamına gelir.
                if (isActive) { // Eğer korutin hata oluştuğunda hala aktifse (dışarıdan iptal edilmediyse)
                    Log.e(TAG, "[Direct Parser] IO error reading MAVLink stream: ${e.message}", e)
                    withContext(Dispatchers.Main + NonCancellable) { // UI thread'ine güvenli geçiş
                        showError(
                            "Bağlantı hatası (Stream Okuma): ${e.message?.take(100)}",
                            "Direct"
                        )
                        disconnectDirectUsb() // Hata durumunda bağlantıyı kes ve kaynakları temizle
                    }
                } else {
                    // Korutin dışarıdan (örn. disconnectDirectUsb çağrısıyla) iptal edildiğinde ve
                    // connection.next() bloklanmış durumda iken fırlayan IOException beklenen bir durumdur.
                    Log.d(TAG, "[Direct Parser] IO error during cancellation: ${e.message}")
                }
            } catch (e: Exception) {
                // Diğer işleme veya ayrıştırma hataları (örn. malformed MAVLink mesajı, checksum hatası vb.)
                if (isActive) { // Eğer korutin hata oluştuğunda hala aktifse
                    Log.e(TAG, "[Direct Parser] Error processing MAVLink message: ${e.message}", e)
                    withContext(Dispatchers.Main + NonCancellable) { // UI thread'ine güvenli geçiş
                        // Spesifik olmayan hatalarda da bağlantıyı kesmek güvenli olabilir
                        showError("Parser işleme hatası: ${e.message?.take(100)}", "Direct")
                        disconnectDirectUsb() // Hata durumunda bağlantıyı kes ve kaynakları temizle
                    }
                }
            } finally {
                // Parser korutini tamamlandığında (başarılı, hata veya iptal ile) burası çalışır.
                Log.d(TAG, "[Direct Parser] Parser Job finally block.")
                // InputStream (pipe input stream) bu korutin bittiğinde kapatılmalıdır.
                try {
                    inputStream.close()
                } catch (e: IOException) {
                    Log.e(TAG, "[Direct Parser] Error closing InputStream in finally", e)
                }
                // OutputStream (komut gönderme stream'i) de burada kapatılabilir, ancak genellikle
                // bağlı olduğu port veya pipe'ın diğer ucu başka yerde kapatılır. Yine de double close zararlı değildir.
                try {
                    outputStream?.close()
                } catch (e: IOException) {
                    Log.e(TAG, "[Direct Parser] Error closing OutputStream in finally", e)
                }

                Log.d(MainActivity.TAG, "[Direct Parser] MAVLink Parser Coroutine tamamlandı.")
            }
        }
    }


    // --- Serial Input Output Manager Listener ---
    // Bu listener, USB seri portundan gelen ham baytları alır (SerialInputOutputManager'ın thread'inde).
    // Aldığı baytları directDataChannel'a gönderir.
    class DirectUsbSerialListener : SerialInputOutputManager.Listener {
        // Seri porttan yeni veri geldiğinde çağrılır
       /* val mavlinkBuffer = ByteArrayOutputStream()

        override fun onNewData(data: ByteArray) {
            for (byte in data) {
                if (mavlinkBuffer.size() == 0 && byte.toInt() != 0xFE && byte.toInt() != 0xFD) {
                    continue  // MAVLink başlangıç baytını bekleyin
                }
                mavlinkBuffer.write(byte.toInt())

                if (mavlinkBuffer.size() > 280) {  // MAVLink max paket boyutu
                    mavlinkBuffer.reset()
                }
            }
        }*/

        override fun onNewData(data: ByteArray) {
            // MAVLink veri kontrolü yapabilirsiniz (isteğe bağlı)
            scope.launch(Dispatchers.IO) {
                try {
                    if (!directDataChannel.isClosedForSend) {
                        directDataChannel.send(data)
                    }
                } catch (e: ClosedSendChannelException) {
                    Log.w(MainActivity.TAG, "[Direct Listener] Kanal kapalıyken veri gönderme denemesi.")
                } catch (e: Exception) {
                    Log.e(MainActivity.TAG, "[Direct Listener] Veri gönderirken hata", e)
                }
            }
        }

        // IO Manager'ın çalışması sırasında bir hata oluştuğunda çağrılır (örn. USB bağlantısı kesildiğinde)
        override fun onRunError(e: Exception) {
            Log.e(MainActivity.TAG, "[Direct Listener] IO Manager Run Error: ${e.message}", e)
            // IO Manager hata verdiğinde (muhtemelen bağlantı koptu) bağlantıyı kesme işlemini başlat.
            scope.launch(Dispatchers.Main) { // Main thread'de korutin başlat
                showError("Seri port hatası: ${e.message?.take(100)}", "Direct")
                disconnectDirectUsb()
            }
        }
    }
    // --- Bağlantı Fonksiyonları ---

    // 1. UDP Proxy ile Bağlanma (MAVSDK Server + USB/UDP Bridge)
// --- Bağlantı Fonksiyonları ---
// 1. UDP Proxy ile Bağlanma (MAVSDK Server + USB/UDP Bridge)
// --- Bağlantı Fonksiyonları ---
// 1. UDP Proxy ile Bağlanma (MAVSDK Server + USB/UDP Bridge)
    @SuppressLint("ServiceCast")
    fun connectProxy(device: UsbDevice) {
        if (isConnecting != null) {
            showError("Başka bir bağlantı işlemi sürüyor.", "Proxy")
            return
        }
        isConnecting = "Proxy"
        disconnectDirectUsb(showStatus = false)
        connectionStatusProxy = "Bağlanılıyor (Proxy)..."
        telemetryDataProxy = "..."
        selectedUsbDevice = device
        scope.launch(Dispatchers.IO) {
            var openedConnection: UsbDeviceConnection? = null
            var openedPort: UsbSerialPort? = null
            var openedSocket: DatagramSocket? = null
            var startedServer: MavsdkServer? = null
            var connectedDrone: System? = null
            var startedProxyJob: Job? = null
            var serverJob: Job? = null // Sunucu korutini için Job referansı

            Log.d(TAG, "[Proxy Connect] Bağlantı süreci başlatılıyor...") // ++ EKLENDİ: Genel başlangıç logu

            try {
                // 1. USB bağlantısını aç ve portu yapılandır
                Log.d(TAG, "[Proxy Connect] Adım 1: USB Bağlantısı Açılıyor...") // ++ EKLENDİ
                val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
                openedConnection = usbManager?.openDevice(device)
                    ?: throw IOException("USB cihaz bağlantısı açılamadı.")
                val drivers = listOf(
                    CdcAcmSerialDriver(device),
                    Ch34xSerialDriver(device),
                    Cp21xxSerialDriver(device),
                    FtdiSerialDriver(device)
                )
                val driver = drivers.firstOrNull { it.ports.isNotEmpty() }
                    ?: throw IOException("USB seri sürücüsü bulunamadı.")
                openedPort = driver.ports.firstOrNull()
                    ?: throw IOException("USB seri port bulunamadı.")
                openedPort.open(openedConnection)
                openedPort.setParameters(
                    MainActivity.DEFAULT_BAUD_RATE,
                    UsbSerialPort.DATABITS_8,
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE
                )
                Log.d(TAG, "[Proxy Connect] Adım 1 Tamamlandı: USB Port açıldı ve yapılandırıldı.") // ++ EKLENDİ

                // 2. MAVSDK Server'ı BAŞLAT (Ayrı bir korutinde)
                Log.d(TAG, "[Proxy Connect] Adım 2: MAVSDK Server Başlatılıyor...") // ++ EKLENDİ
                startedServer = MavsdkServer()
                serverJob = scope.launch(Dispatchers.IO + CoroutineName("mavsdk-server-runner")) {
                    try {
                        Log.i(TAG, "[Proxy Server Coroutine] MAVSDK Server çalıştırılıyor (run)...") // ++ GÜNCELLENDİ: Info seviyesi log
                        startedServer.run() // Bu potansiyel olarak bloklayıcı olabilir
                        Log.i(TAG, "[Proxy Server Coroutine] MAVSDK Server çalışması bitti (normalde bitmemeli).") // ++ GÜNCELLENDİ: Info seviyesi log
                    } catch (e: Exception) {
                        // ++ EKLENDİ: Sunucu çalışma hatası için detaylı log
                        Log.e(TAG, "[Proxy Server Coroutine] MAVSDK Server run() sırasında KRİTİK HATA: ${e.message}", e)
                        // Ana bağlantı sürecini de iptal etmeye çalışabiliriz
                        this@launch.cancel(CancellationException("MAVSDK Server run() hatası: ${e.message}", e))
                    } finally {
                        Log.i(TAG, "[Proxy Server Coroutine] MAVSDK Server korutini sonlanıyor.") // ++ EKLENDİ: Finally logu
                    }
                }
                Log.d(TAG, "[Proxy Connect] Adım 2 Tamamlandı: MAVSDK Server başlatma korutini (serverJob) başlatıldı.") // ++ EKLENDİ

                // 3. MAVSDK Server'ın başlaması için kısa bir süre bekle
                val serverWaitDelay = 3000L // ++ GÜNCELLENDİ: Bekleme süresi 3 saniyeye çıkarıldı
                Log.d(TAG, "[Proxy Connect] Adım 3: MAVSDK Server'ın başlaması için ${serverWaitDelay}ms bekleniyor...") // ++ EKLENDİ
                delay(serverWaitDelay)
                // ++ EKLENDİ: Sunucu korutininin hala aktif olup olmadığını kontrol et
                if (serverJob?.isActive != true) {
                    throw IOException("MAVSDK Server korutini ${serverWaitDelay}ms bekleme sonrası aktif değil. Başlatma hatası olabilir.")
                }
                Log.d(TAG, "[Proxy Connect] Adım 3 Tamamlandı: MAVSDK Server için beklendi, korutin aktif.") // ++ EKLENDİ

                // 4. UDP soketini oluştur ve BAĞLAN
                Log.d(TAG, "[Proxy Connect] Adım 4: UDP Soketi Oluşturuluyor ve Bağlanıyor (localhost:${MainActivity.MAVSDK_SERVER_UDP_PORT})...") // ++ EKLENDİ
                openedSocket = DatagramSocket() // Önce soketi oluştur
                try {
                    val targetAddress = InetAddress.getLoopbackAddress()
                    val targetPort = MainActivity.MAVSDK_SERVER_UDP_PORT
                    Log.d(TAG, "[Proxy Connect] UDP connect() çağrılıyor: ${targetAddress}:${targetPort}") // ++ EKLENDİ
                    openedSocket.connect(targetAddress, targetPort)
                    Log.i(TAG, "[Proxy Connect] Adım 4 Başarılı: UDP soketi MAVSDK Server adresine bağlandı.") // ++ GÜNCELLENDİ: Başarı logu
                } catch (socketError: Exception) {
                    Log.e(TAG, "[Proxy Connect] Adım 4 KRİTİK HATA: UDP soketini bağlarken HATA: ${socketError.message}", socketError) // ++ GÜNCELLENDİ: Hata logu
                    serverJob?.cancel() // Sunucu korutinini de iptal etmeyi dene
                    throw IOException("UDP soketi MAVSDK Server'a bağlanamadı (${socketError.message}). Sunucu çalışmıyor veya port (${MainActivity.MAVSDK_SERVER_UDP_PORT}) meşgul olabilir.", socketError)
                }

            // ++ YENİ EKLENDİ: UDP Soket bağlandıktan sonra kısa bir bekleme ++
            val postSocketConnectDelay = 500L // Yarım saniye bekleme
            Log.d(TAG, "[Proxy Connect] UDP soket bağlandı, proxy başlatılmadan önce ${postSocketConnectDelay}ms bekleniyor...")
            delay(postSocketConnectDelay)
            // ++ YENİ EKLENDİ SONU ++

            // 5. USB <-> UDP proxy'yi BAŞLAT
            Log.d(TAG, "[Proxy Connect] Adım 5: Çift Yönlü UDP Proxy Başlatılıyor...") // ++ EKLENDİ
            startedProxyJob = startUdpProxy(
                usbPort = openedPort,
                udpSocket = openedSocket,
                targetAddress = InetAddress.getLoopbackAddress(), // Hedef MAVSDK sunucu adresi
                targetPort = MainActivity.MAVSDK_SERVER_UDP_PORT // Hedef MAVSDK sunucu portu
            )
            Log.d(TAG, "[Proxy Connect] Adım 5 Tamamlandı: Çift Yönlü UDP Proxy görevi başlatıldı.") // ++ EKLENDİ

            // 6. MAVSDK System'i keşfet
            // ... (kalan kod aynı)

                // 6. MAVSDK System'i keşfet
                Log.d(TAG, "[Proxy Connect] Adım 6: MAVSDK System Keşfediliyor (Timeout: 15sn)...") // ++ EKLENDİ
                val discoveryTimeout = 15_000L
                val discoveredSystem = withTimeoutOrNull(discoveryTimeout) {
                    var system: System? = null
                    while (isActive) {
                        // ++ EKLENDİ: Keşif döngüsü başlangıcı logu
                        Log.d(TAG, "[Proxy Discovery] Yeni System() objesi oluşturuluyor ve bağlantı durumu dinleniyor...")
                        val tempSystem = System("127.0.0.1", MainActivity.MAVSDK_SERVER_UDP_PORT)
                        val connected = CompletableDeferred<Boolean>()
                        val disposable = tempSystem.core.connectionState
                            .observeOn(AndroidSchedulers.mainThread()) // UI thread'inde gözlemle (log için sorun olmaz)
                            .subscribe(
                                { state ->
                                    Log.d(TAG, "[Proxy Discovery] Connection State: ${state.isConnected}")
                                    if (state.isConnected && !connected.isCompleted) {
                                        Log.i(TAG, "[Proxy Discovery] System connected!") // ++ GÜNCELLENDİ: Bağlantı logu
                                        connected.complete(true)
                                    }
                                },
                                { error ->
                                    Log.w(TAG, "[Proxy Discovery] Connection state observation failed: ${error.message}")
                                    if (!connected.isCompleted) {
                                        connected.completeExceptionally(error)
                                    }
                                }
                            )
                        try {
                            Log.d(TAG, "[Proxy Discovery] connected.await() bekleniyor...") // ++ EKLENDİ
                            connected.await()
                            Log.i(TAG, "[Proxy Discovery] Await successful, system found.") // ++ GÜNCELLENDİ
                            system = tempSystem
                            break // Döngüden çık
                        } catch (e: Exception) {
                            Log.w(TAG, "[Proxy Discovery] Await failed or threw exception: ${e.message}")
                            tempSystem.dispose()
                            if (!isActive) break
                            Log.d(TAG, "[Proxy Discovery] Tekrar denemeden önce 200ms bekleniyor...") // ++ EKLENDİ
                            delay(200)
                        } finally {
                            Log.d(TAG, "[Proxy Discovery] RxJava disposable dispose ediliyor.") // ++ EKLENDİ
                            disposable.dispose()
                        }
                        if (!isActive) {
                            Log.d(TAG, "[Proxy Discovery] Korutin iptal edildi, keşif döngüsü sonlandırılıyor.") // ++ EKLENDİ
                            break
                        }
                    }
                    system
                }

                if (discoveredSystem == null) {
                    Log.e(TAG, "[Proxy Connect] Adım 6 KRİTİK HATA: MAVSDK System keşfedilemedi (${discoveryTimeout}ms timeout).") // ++ GÜNCELLENDİ: Hata logu
                    serverJob?.cancel()
                    throw IOException("MAVSDK System keşfedilemedi (${discoveryTimeout}ms timeout), bağlantı başarısız.")
                }
                connectedDrone = discoveredSystem
                Log.i(TAG, "[Proxy Connect] Adım 6 Başarılı: MAVSDK System keşfedildi.") // ++ GÜNCELLENDİ: Başarı logu

                // 7. Telemetri dinleyicilerini ayarla
                Log.d(TAG, "[Proxy Connect] Adım 7: Telemetri Dinleyicileri Ayarlanıyor...") // ++ EKLENDİ
                setupTelemetryListeners(connectedDrone)
                Log.d(TAG, "[Proxy Connect] Adım 7 Tamamlandı: Telemetri Dinleyicileri Ayarlandı.") // ++ EKLENDİ

                // 8. Başarılı bağlantı durumunu UI'da güncelle
                Log.d(TAG, "[Proxy Connect] Adım 8: Başarılı Bağlantı - UI Güncelleniyor...") // ++ EKLENDİ
                withContext(Dispatchers.Main) {
                    proxyUsbConnection = openedConnection
                    proxyUsbPort = openedPort
                    udpSocket = openedSocket
                    mavsdkServer = startedServer
                    drone = connectedDrone
                    proxyJob = startedProxyJob
                    connectionStatusProxy =
                        "Bağlandı: ${device.deviceName} (${MainActivity.DEFAULT_BAUD_RATE}) via MAVSDK Proxy"
                    isProxyConnected = true
                    isConnecting = null
                    Log.i(TAG, "[Proxy Connect] Bağlantı Başarılı, UI güncellendi.") // ++ GÜNCELLENDİ: Başarı logu
                }
            } catch (e: Exception) {
                // ++ GÜNCELLENDİ: Hatanın hangi aşamada olduğunu anlamak için daha detaylı log
                Log.e(TAG, "[Proxy Connect] Bağlantı sürecinde KRİTİK HATA: ${e.message}", e)

                // Hata durumunda tüm başlatılan kaynakları GÜVENLİ bir şekilde temizle
                Log.d(TAG, "[Proxy Connect Hata Temizleme] Başlatılan kaynaklar temizleniyor...") // ++ EKLENDİ
                try { serverJob?.cancel() } catch (_: Exception) { Log.e(TAG, "Hata Temizleme: serverJob iptal edilemedi") }
                try { startedProxyJob?.cancel() } catch (_: Exception) { Log.e(TAG, "Hata Temizleme: proxyJob iptal edilemedi") }
                try { startedServer?.stop() } catch (_: Exception) { Log.e(TAG, "Hata Temizleme: MAVSDK Server durdurulamadı") }
                try { connectedDrone?.dispose() } catch (_: Exception) { Log.e(TAG, "Hata Temizleme: Drone dispose edilemedi") }
                try { openedSocket?.close() } catch (_: Exception) { Log.e(TAG, "Hata Temizleme: UDP Soketi kapatılamadı") }
                try { openedPort?.close() } catch (_: Exception) { Log.e(TAG, "Hata Temizleme: USB Port kapatılamadı") }
                try { openedConnection?.close() } catch (_: Exception) { Log.e(TAG, "Hata Temizleme: USB Bağlantısı kapatılamadı") }
                Log.d(TAG, "[Proxy Connect Hata Temizleme] Kaynak temizleme tamamlandı.") // ++ EKLENDİ

                withContext(Dispatchers.Main) {
                    showError("Proxy Bağlantı kurulamadı: ${e.message?.take(100)}", "Proxy") // Hata mesajını göster
                    proxyUsbConnection = null
                    proxyUsbPort = null
                    udpSocket = null
                    mavsdkServer = null
                    drone = null
                    proxyJob = null
                    isProxyConnected = false
                    isConnecting = null
                    Log.d(TAG, "[Proxy Connect] Bağlantı Başarısız, UI güncellendi.") // ++ EKLENDİ
                }
            } finally {
                Log.d(TAG, "[Proxy Connect] Ana bağlantı korutini finally bloğu.") // ++ EKLENDİ
                // Eğer 'isConnecting' hala "Proxy" ise (başarılı olmadan bittiyse) null yap
                if(isConnecting == "Proxy") {
                    withContext(Dispatchers.Main + NonCancellable) {
                        isConnecting = null
                        Log.d(TAG, "[Proxy Connect] 'isConnecting' durumu finally içinde null yapıldı.") // ++ EKLENDİ
                    }
                }
            }
        } // scope.launch sonu
    }



    // 2. Direct MAVLink ile Bağlanma (io.dronefleet.mavlink + USB)
    // USB seri portu üzerinden doğrudan MAVLink mesajlarını alır ve ayrıştırır, komut gönderir.
    @SuppressLint("ServiceCast")
    fun connectDirectUsb(device: UsbDevice, baudRate: Int) {
        // Zaten başka bir bağlantı işlemi devam ediyorsa yeni bir tane başlatma
        if (isConnecting != null) {
            showError("Başka bir bağlantı işlemi sürüyor.", "Direct");
            return
        }
        isConnecting = "Direct" // Bağlantı durumunu güncelle
        // Varsa Proxy bağlantıyı kes (ikisi aynı anda aktif olmamalı)
        disconnectProxy(showStatus = false)
        // UI durum mesajlarını güncelle ve telemetriyi sıfırla
        connectionStatusDirect = "Bağlanılıyor (Direct)..."
        directTelemetryState = DirectTelemetryState() // Önceki verileri temizle
        selectedUsbDevice = device // Seçilen cihazı kaydet

        // Bağlantı işlemini bir korutin içinde ve IO thread'inde başlatıyoruz
        scope.launch(Dispatchers.IO) {
            // Try bloğu dışında null olarak başlatılan kaynaklar
            var connection: UsbDeviceConnection? = null
            var port: UsbSerialPort? = null
            var ioManager: SerialInputOutputManager? = null
            // Direct bağlantı için MAVLink yazma (komut gönderme) OutputStream'i
            var commandOutputStream: OutputStream? = null
            // MAVLink parser'ı için PipedInputStream
            var pipedInputStreamForParser: InputStream? = null

            try {
                @SuppressLint("ServiceCast")
                // 1. USB Aygıtı Aç ve Seri Portu Yapılandır
                val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
                connection = usbManager?.openDevice(device)
                    ?: throw IllegalStateException("USB Manager servisi bulunamadı.") // Bağlantı null dönerse hata
                usbManager.deviceList.values.forEach { device ->
                    Log.d(
                        "USB_DEBUG", """
        Device: ${device.deviceName}
        VID: ${device.vendorId} (0x${device.vendorId.toString(16)})
        PID: ${device.productId} (0x${device.productId.toString(16)})
        Protocol: ${device.deviceProtocol}
    """.trimIndent()
                    )
                }
                val drivers = listOf(
                    CdcAcmSerialDriver(device),  // STM32 için en yaygın
                    Ch34xSerialDriver(device),   // CH340 çipli cihazlar için
                    Cp21xxSerialDriver(device),  // CP210x çipli cihazlar için
                    FtdiSerialDriver(device)     // FTDI çipli cihazlar için
                )

                val driver = drivers.firstOrNull { it.ports.isNotEmpty() }
                //val driver = UsbSerialProber.getDefaultProber()
                //    .probeDevice(device) // Cihaz için uygun sürücüyü bul
                    ?: throw IOException("USB seri sürücüsü bulunamadı.") // Sürücü bulunamazsa hata

                port = driver.ports.firstOrNull() // İlk kullanılabilir portu al
                    ?: throw IOException("USB seri port bulunamadı.") // Port bulunamazsa hata

                port.open(connection) // Portu bağlantı üzerinden aç
                port.setParameters(
                    baudRate,
                    UsbSerialPort.DATABITS_8,
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE
                ) // Port parametrelerini ayarla
                Log.d(TAG, "[Direct] USB Port açıldı ve yapılandırıldı (${baudRate} baud).")

                // 2. SerialInputOutputManager'ı Başlat
                // IO Manager, USB porttan okuma ve yazma işlemlerini yönetir.
                // Gelen veriyi DirectUsbSerialListener aracılığıyla directDataChannel'a gönderecek.
                val listener = DirectUsbSerialListener()
                // IO Manager'ı default executor ile başlatıyoruz. Kendi IO thread'ini kullanır.
                ioManager = SerialInputOutputManager(port, listener)
                ioManager.start() // IO Manager'ı başlat
                Log.d(MainActivity.TAG, "[Direct] SerialInputOutputManager başlatıldı.")

                // 3. MAVLink Ayrıştırma (Okuma) İçin Pipe Kurulumu
                // SerialInputOutputManager'dan gelen veriyi (channel üzerinden) MAVLink parser'ına iletmek için Pipe kullanılır.
                val pipedOutputStreamFromSerial = PipedOutputStream()
                // PipedInputStream, PipedOutputStream'e bağlanır. MAVLink connection bu InputStream'den okuyacaktır.
                pipedInputStreamForParser = PipedInputStream(pipedOutputStreamFromSerial)

                // directDataChannel'dan okuyup Pipe OutputStream'ine yazacak Korutin (IO Thread'inde çalışacak)
                // Bu korutin, IO Manager'ın listener'ı tarafından kanala gönderilen baytları alır ve pipe'a yazar.
                val pipeWriterJob =
                    scope.launch(Dispatchers.IO + CoroutineName(name = "direct-pipe-writer")) {
                        Log.d(TAG, "[Direct Pipe Writer] Başlatıldı.")
                        try {
                            // directDataChannel'dan gelen byte array'lerini oku (kanal kapandığında döngü sona erer)
                            for (data in directDataChannel) {
                                try {
                                    // Gelen veriyi Pipe OutputStream'e yaz. Bu işlem Pipe'ın buffer'ı doluysa bloklayıcıdır.
                                    pipedOutputStreamFromSerial.write(data)
                                } catch (writeError: IOException) {
                                    // Pipe'a yazma sırasında hata oluşursa (örn. Pipe'ın diğer ucu (InputStream) kapatıldıysa)
                                    if (isActive) Log.e(
                                        TAG,
                                        "[Direct Pipe Writer] Pipe'a yazma hatası: ${writeError.message}",
                                        writeError
                                    )
                                    break // Yazma hatasında döngüden çık
                                }
                            }
                            Log.d(
                                TAG,
                                "[Direct Pipe Writer] Kanal okuma döngüsü bitti (kanal kapatıldı veya hata oluştu)."
                            )
                        } catch (e: Exception) {
                            // Kanal toplama sırasında beklenmedik bir hata oluşursa
                            if (isActive) Log.e(
                                TAG,
                                "[Direct Pipe Writer] Beklenmedik hata: ${e.message}",
                                e
                            )
                        } finally {
                            // Pipe yazar korutini bittiğinde Pipe OutputStream'i kapat.
                            // Bu, Pipe'ın okuma ucuna (PipedInputStream) stream sonu sinyali gönderir.
                            try {
                                pipedOutputStreamFromSerial.close()
                                Log.d(TAG, "[Direct Pipe Writer] PipedOutputStream kapatıldı.")
                            } catch (e: IOException) {
                                Log.e(
                                    TAG,
                                    "[Direct Pipe Writer] PipedOutputStream kapatılırken hata: ${e.message}",
                                    e
                                )
                            }
                            Log.d(TAG, "[Direct Pipe Writer] Tamamlandı.")
                        }
                        // pipeWriterJob bittiğinde, bu işi başlatan ana korutini (connectDirectUsb içindeki) haberdar etmenin yolları olabilir,
                        // ancak burada parserJob'ın sonlanması disconnect sürecini tetikler.
                    }

                // 4. MAVLink Komutları Gönderme İçin OutputStream Kurulumu
                // MAVLink komutlarını göndermek için MavlinkConnection.send() metodu kullanılacaktır.
                // Bu metot, bağlantı oluşturulurken sağlanan OutputStream'e yazar.
                // Doğrudan USB porta yazan bir OutputStream implementasyonu oluşturuyoruz.
                commandOutputStream = object : OutputStream() {
                    // Bu OutputStream'e yazıldığında veriyi doğrudan USB porta gönderir.
                    // write metotları blocking olabilir, bu nedenle IO thread'inde çağrılmalıdır.
                    // SerialInputOutputManager'ın write metodu thread-safe'dir, bu yüzden doğrudan kullanılabilir.
                    override fun write(b: Int) {
                        val byteArray = byteArrayOf(b.toByte())
                        try {
                            // Doğrudan SerialInputOutputManager'ın write metodunu kullan
                            ioManager.writeAsync(byteArray) // Asenkron yazma (bloklamaz)
                            // Veya blocking yazma (dikkatli kullanılmalı):
                            // port.write(byteArray, 100) // 100ms timeout ile yaz
                        } catch (e: IOException) {
                            Log.e(
                                TAG,
                                "[Direct OutputStream] Tek bayt yazma hatası: ${e.message}",
                                e
                            )
                            throw e // Hatayı tekrar fırlat
                        }
                    }

                    override fun write(b: ByteArray, off: Int, len: Int) {
                        try {
                            // Doğrudan SerialInputOutputManager'ın write metodunu kullan
                            ioManager.writeAsync(b) // Asenkron yazma (bloklamaz)
                            // Veya blocking yazma (dikkatli kullanılmalı):
                            // port.write(b, off, len, 100) // 100ms timeout ile yaz
                        } catch (e: IOException) {
                            Log.e(
                                TAG,
                                "[Direct OutputStream] Bayt dizisi yazma hatası (${len} bayt): ${e.message}",
                                e
                            )
                            throw e // Hatayı tekrar fırlat
                        }
                    }

                    // flush ve close metotları duruma göre implement edilebilir.
                    // SerialInputOutputManager'ın writeAsync metodu internal buffering yapar, flush gerekli olmayabilir.
                    override fun flush() {
                        // SerialInputOutputManager'da doğrudan flush metodu yok.
                        // Eğer blocking write kullanılıyorsa veya özel bir buffer yönetimi varsa implement edilebilir.
                    }

                    // OutputStream kapatıldığında çağrılır. Portun kendisi bağlantı kesilirken kapatılıyor.
                    override fun close() {
                        // Portun kendisi başka yerde kapatılıyor (cleanUpDirectResources içinde).
                        // Burada doğrudan porta yazan OutputStream için explicit bir kapatma işlemi gerekmeyebilir.
                        Log.d(TAG, "[Direct OutputStream] Kapatıldı (Pseudo-close).")
                    }
                }


                // 5. MAVLink Ayrıştırma Korutinini Başlat (Pipe'tan okuyacak ve commandOutputStream'ı kullanacak)
                // Bu korutin, pipedInputStreamForParser'dan okuyarak MAVLink mesajlarını ayrıştırır.
                // Oluşturulan commandOutputStream da MAVLink mesajları göndermek için kullanılacaktır.
                directParserJob =
                    startDirectMavlinkParser(pipedInputStreamForParser, commandOutputStream)

                // IO Manager, Port ve Connection başarılı olduysa UI state'leri Main thread'de güncelle
                withContext(Dispatchers.Main) {
                    directUsbConnection = connection // Bağlantı referansını kaydet
                    directUsbPort = port // Port referansını kaydet
                    directIoManager = ioManager // IO Manager referansını kaydet
                    directCommandOutputStream =
                        commandOutputStream // Command OutputStream referansını state'e kaydet
                    // directParserJob state'i zaten yukarıda atandı

                    connectionStatusDirect =
                        "Bağlandı: ${device.deviceName} (${baudRate})" // UI durum mesajı
                    isDirectConnected = true // Bağlantı durumu state'ini true yap
                    isConnecting = null // Bağlantı işlemi tamamlandı

                    Log.d(TAG, "[Direct] Bağlantı başarılı, state güncellendi.")
                }

                // pipeWriterJob'ın veya directParserJob'ın sonlanmasını beklemek isterseniz burada join() kullanabilirsiniz,
                // ancak genellikle bu bağlantı korutini sürekli çalışır ve bağlantı kesilince veya hata olunca sonlanır.
                // Bu korutin tamamlandığında (örn. hata veya iptal ile), finally bloğu kaynakları temizler.

            } catch (e: Exception) {
                // Bağlantı kurma aşamalarından herhangi birinde hata oluşursa
                Log.e(MainActivity.TAG, "[Direct] Bağlantı Hatası: ${e.message}", e)
                // Hata oluşursa, o ana kadar başarılı olan tüm kaynakları temizle
                // IO Manager durdurulacak (bu listener'ın hata vermesine neden olabilir, dikkatli ol)
                try {
                    ioManager?.stop()
                } catch (e: Exception) {
                    Log.e(MainActivity.TAG, "[Direct] Error stopping ioManager on error", e)
                }
                // Port ve Connection kapatılacak
                try {
                    port?.close()
                } catch (e: IOException) {
                    Log.e(MainActivity.TAG, "[Direct] Error closing port on error", e)
                }
                try {
                    connection?.close()
                } catch (e: Exception) {
                    Log.e(MainActivity.TAG, "[Direct] Error closing connection on error", e)
                }
                // Pipe input stream'i kapat (eğer oluşturulduysa). Bu parser job'ı (varsa) sonlandırır.
                try {
                    pipedInputStreamForParser?.close()
                } catch (e: IOException) {
                    Log.e(MainActivity.TAG, "[Direct] Error closing pipe input stream on error", e)
                }
                // Command OutputStream'i kapat (eğer oluşturulduysa).
                try {
                    commandOutputStream?.close()
                } catch (e: IOException) {
                    Log.e(
                        MainActivity.TAG,
                        "[Direct] Error closing command output stream on error",
                        e
                    )
                }
                // Pipe writer job'ı (varsa) iptal et. directDataChannel.close() zaten cleanUpResources içinde yapılıyor,
                // bu genellikle pipeWriterJob'ı sonlandırır. Explicit cancel da yapılabilir.
                // Eğer pipeWriterJob bu catch bloğundan önce başlatıldıysa ve hala aktifse, iptal etmek gerekebilir.
                // Bu yapıda pipeWriterJob, connectDirectUsb scope'u içinde başlatılmıyor, startDirectMavlinkParser içinde de değil.
                // PipeWriterJob, startDirectMavlinkParser'a taşınmalıdır. Önceki adımda yaptığımız gibi.
                // Düzeltme: PipeWriterJob, startDirectMavlinkParser'a taşınmıştı. disconnectDirectUsb çağrıldığında parser job iptal edilir, o da pipeWriterJob'ı sonlandırır.

                // UI state'lerini hata durumuna göre Main thread'de güncelle
                withContext(Dispatchers.Main) {
                    showError(
                        "Bağlantı kurulamadı: ${e.message?.take(100)}",
                        "Direct"
                    ) // Hata mesajını kullanıcıya göster
                    // Tüm resource state'lerini null yap
                    directUsbConnection = null
                    directUsbPort = null
                    directIoManager = null
                    directCommandOutputStream = null
                    directParserJob =
                        null // Hata durumunda parser job başlatılamamış veya hata vermiş olmalı
                    isDirectConnected = false // Bağlantı durumu false
                    isConnecting = null // Bağlantı işlemi tamamlandı (başarısız)

                    Log.d(TAG, "[Direct] Bağlantı başarısız, state güncellendi.")
                }
            }
        }
    }

    // --- MAVLink Komut Gönderme Fonksiyonu ---
    // Bu fonksiyon, Direct bağlantı aktifken MAVLink komutları göndermek için kullanılır.
    // Komut, MAVLink kütüphanesi kullanılarak serileştirilir ve directCommandOutputStream'a yazılır.

    fun saveParameters(mavlinkConnection: MavlinkConnection) {
        val command = CommandLong.builder()
            .targetSystem(1)
            .targetComponent(1)
            .command(MavCmd.MAV_CMD_PREFLIGHT_STORAGE)
            .param1(1f) // 1=parametreleri kaydet
            .confirmation(0)
            .build()

        mavlinkConnection.send1(255, 0, command)
    }

    fun startRcCalibration(mavlinkConnection: MavlinkConnection) {
        val command = CommandLong.builder()
            .targetSystem(1) // Drone system ID
            .targetComponent(1) // Drone component ID
            .command(MavCmd.MAV_CMD_START_RX_PAIR)
            .param1(0f) // 0=kalibrasyon başlat
            .confirmation(0)
            .build()

        scope.launch(Dispatchers.IO) {
            repeat(3) { // 3 kez gönder (güvenlik için)
                mavlinkConnection.send1(255, 0, command)
                delay(500)
            }
        }
    }


    fun handleError(e: Exception) {
        scope.launch(Dispatchers.Main) {
            showError("Hata: ${e.message?.take(100)}", "Direct")
        }
    }

    fun isDroneArmed(heartbeat: Heartbeat): Boolean {
        val baseMode = heartbeat.baseMode().value()  // Int değer (örnek: 128)
        val armFlag = 128  // MAV_MODE_FLAG_SAFETY_ARMED (MAVLink spesifikasyonu)

        // Bitwise AND işlemi (Kotlin'de `and` fonksiyonu kullanılır)
        return (baseMode and armFlag) == armFlag
    }

    suspend fun listenForResponse(connection: MavlinkConnection) {
        try {
            withTimeout(5000) { // 5 saniye timeout
                while (isActive) {
                    val packet = connection.next()
                    when (val message = packet.payload) {
                        is Heartbeat -> {
                            Log.d(TAG, "Heartbeat alındı - ARM durumu: ${isDroneArmed(message)}")
                        }
                        // Diğer mesaj tipleri...
                    }
                }
            }
        } catch (e: TimeoutCancellationException) {
            Log.d(TAG, "Yanıt dinleme zaman aşımına uğradı")
        } catch (e: Exception) {
            Log.e(TAG, "Yanıt dinleme hatası", e)
        }
    }

    fun sendMavlinkCommand(payload: Any) {
        if (!isDirectConnected || directCommandOutputStream == null) {
            Log.w(TAG, "[Direct] Bağlı değilken komut gönderme denemesi")
            scope.launch {
                snackbarHostState.showSnackbar("Komut göndermek için Direct bağlantı kurulu olmalı.")
            }
            return
        }

        scope.launch(Dispatchers.IO) {
            try {
                Log.d(
                    TAG,
                    "[Direct Send] Komut gönderme başlatıldı: ${payload::class.java.simpleName}"
                )

                // Tek seferlik bağlantı oluşturma
                val mavConn = MavlinkConnection.builder(null, directCommandOutputStream)
                    .dialect(MavAutopilot.MAV_AUTOPILOT_GENERIC, StandardDialect())
                    .dialect(MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA, ArdupilotmegaDialect())
                    .build()

                // MAVLink versiyonuna göre gönderim
                when (payload) {
                    is CommandLong -> {
                        mavConn.send1(255, 0, payload) // MAVLink 1.0

                    }

                    is Heartbeat -> {
                        mavConn.send1(255, 0, payload) // MAVLink 1.0
                        // mavConn.send2(255, 0, payload) // MAVLink 2.0
                    }

                    else -> throw IllegalArgumentException("Geçersiz payload tipi")
                }

                Log.d(
                    TAG,
                    "[Direct Send] Komut başarıyla gönderildi: ${payload::class.java.simpleName}"
                )

                // RC kalibrasyonu başlat
                startRcCalibration(mavConn)

                // Yanıt dinleme
                listenForResponse(mavConn)

            } catch (e: IOException) {
                Log.e(TAG, "[Direct Send] IO hatası: ${e.message}", e)
                handleError(e)
            } catch (e: Exception) {
                Log.e(TAG, "[Direct Send] Beklenmedik hata: ${e.message}", e)
                handleError(e)
            }
        }
    }


    // --- UI Composable ---
    Scaffold(snackbarHost = { SnackbarHost(snackbarHostState) }) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState()), // İçeriği kaydırılabilir yap
            horizontalAlignment = Alignment.CenterHorizontally, // Yatayda ortala
            verticalArrangement = Arrangement.spacedBy(12.dp) // Dikeyde boşluk bırak
        ) {
            // Başlık Metni
            Text("Drone Kontrol Uygulaması", fontSize = 24.sp, fontWeight = FontWeight.Bold)

            // USB Cihaz Seçimi ve Bağlantı Butonları Bölümü
            Text("USB Cihaz Durumu:")
            // "USB Cihazlarını Tara" butonu: Tıklandığında cihazları listeler ve seçici dialogunu açar
            Button(onClick = {
                usbDevices = getUsbDevices() // Cihazları listele
                showDeviceSelector = true // Cihaz seçici dialogunu göster
            }, enabled = isConnecting == null) { // Bağlantı işlemi yokken aktif
                Text("USB Cihazlarını Tara")
            }

            fun isInBootMode(device: UsbDevice): Boolean {
                // STM32 Bootloader VID/PID
                return (device.vendorId == 0x0483 &&
                        (device.productId == 0xDF11 || device.productId == 0xDEAD))
            }

// Bağlantı denemeden önce kontrol

            // Seçilen bir USB cihazı varsa detaylarını ve bağlantı butonlarını göster
            selectedUsbDevice?.let { device ->
                Text("Seçilen Cihaz: ${device.deviceName ?: "Bilinmiyor"}") // Cihaz adını göster
                Text("Vendor ID: ${device.vendorId}, Product ID: ${device.productId}") // Vendor ve Product ID'lerini göster
                // Baud Rate seçimi eklenebilir, şu an sabit DEFAULT_BAUD_RATE kullanılıyor.

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { // Bağlantı ve Bağlantı Kesme butonları için yatay sıra
                    // Proxy Bağlantı Butonu
                    Button(
                        onClick = {
                            // USB izni kontrolü ve isteme
                            if (checkUsbPermission(device)) {
                                /*if (isInBootMode(device)) {
                                     showError("Kart boot modunda! Lütfen BOOT0 pinini GND'ye bağlayıp reset atın")
                                    // DFU modunda özel işlemler
                                    val driver = FtdiSerialDriver(device) // STM32 bootloader genellikle FTDI gibi davranır
                                    try {
                                        // Bootloader ile iletişim kur
                                        port.write("0x7F".toByteArray(), 1000) // STM32 bootloader sync karakteri
                                        // Firmware yükleme işlemleri...
                                    } catch (e: Exception) {
                                        showError("Bootloader modunda firmware yükleyin")
                                    }
                                } else {
                                    // Normal bağlantı
                                    connectProxy((device))
                                }*/
                                connectProxy(device) // İzin varsa doğrudan bağlan
                            } else {
                                // İzin yoksa izin iste ve sonuç geldiğinde bağlanmayı dene
                                requestUsbPermission(device) { granted, grantedDevice ->
                                    if (granted && grantedDevice != null) {
                                        connectProxy(grantedDevice)
                                    } else {
                                        showError(
                                            "USB izni verilmedi.",
                                            "Proxy"
                                        ) // İzin verilmediyse hata göster
                                    }
                                }
                            }
                        },
                        enabled = !isProxyConnected && isConnecting == null // Zaten bağlı değilse ve başka işlem yoksa aktif
                    ) {
                        Text(if (isConnecting == "Proxy") "Bağlanılıyor..." else "Proxy Bağlan") // Duruma göre buton metni
                    }

                    // Direct Bağlantı Butonu
                    Button(
                        onClick = {
                            // USB izni kontrolü ve isteme
                            if (checkUsbPermission(device)) {
                                connectDirectUsb(
                                    device,
                                    MainActivity.DEFAULT_BAUD_RATE
                                ) // İzin varsa doğrudan bağlan
                            } else {
                                // İzin yoksa izin iste ve sonuç geldiğinde bağlanmayı dene
                                requestUsbPermission(device) { granted, grantedDevice ->
                                    if (granted && grantedDevice != null) {
                                        connectDirectUsb(
                                            grantedDevice,
                                            MainActivity.DEFAULT_BAUD_RATE
                                        )
                                    } else {
                                        showError(
                                            "USB izni verilmedi.",
                                            "Direct"
                                        ) // İzin verilmediyse hata göster
                                    }
                                }
                            }
                        },
                        enabled = !isDirectConnected && isConnecting == null // Zaten bağlı değilse ve başka işlem yoksa aktif
                    ) {
                        Text(if (isConnecting == "Direct") "Bağlanılıyor..." else "Direct Bağlan") // Duruma göre buton metni
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { // Bağlantı Kesme butonları için yatay sıra
                    // Proxy Bağlantı Kes Butonu
                    Button(
                        onClick = { disconnectProxy() }, // Bağlantıyı kesme fonksiyonunu çağır
                        enabled = isProxyConnected && isConnecting == null // Proxy bağlıysa ve başka işlem yoksa aktif
                    ) {
                        Text("Proxy Bağlantıyı Kes")
                    }
                    // Direct Bağlantı Kes Butonu
                    Button(
                        onClick = { disconnectDirectUsb() }, // Bağlantıyı kesme fonksiyonunu çağır
                        enabled = isDirectConnected && isConnecting == null // Direct bağlıysa ve başka işlem yoksa aktif
                    ) {
                        Text("Direct Bağlantıyı Kes")
                    }
                }
            } ?: Text("Lütfen bir USB cihazı seçin.") // Seçilen cihaz yoksa bu metni göster

            // Cihaz Seçici Dialog
            if (showDeviceSelector) { // showDeviceSelector true ise dialogu göster
                AlertDialog(
                    onDismissRequest = {
                        showDeviceSelector = false
                    }, // Dialog dışında tıklanınca kapat
                    title = { Text("USB Cihazı Seçin") }, // Dialog başlığı
                    text = { // Dialog içeriği (cihaz listesi)
                        LazyColumn { // Kaydırılabilir liste
                            if (usbDevices.isEmpty()) {
                                item { Text("Cihaz bulunamadı.") } // Cihaz yoksa mesaj göster
                            } else {
                                items(usbDevices) { device -> // Cihazları liste olarak göster
                                    TextButton(onClick = {
                                        selectedUsbDevice = device // Cihazı seç
                                        showDeviceSelector = false // Dialogu kapat
                                    }) {
                                        Column(modifier = Modifier.fillMaxWidth()) { // Cihaz bilgileri
                                            Text(
                                                device.deviceName ?: "Bilinmiyor",
                                                fontWeight = FontWeight.Bold
                                            )
                                            Text("Vendor: ${device.vendorId}, Product: ${device.productId}")
                                        }
                                    }
                                    Divider() // Cihazlar arasına çizgi çiz
                                }
                            }
                        }
                    },
                    confirmButton = { // Dialog kapatma butonu
                        Button(onClick = { showDeviceSelector = false }) {
                            Text("Kapat")
                        }
                    }
                )
            }

            Divider(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) // Bölücü çizgi

            // Bağlantı Durumları Bölümü
            Text(
                "Proxy Durumu: $connectionStatusProxy",
                fontWeight = FontWeight.Bold
            ) // Proxy durumu metni
            Text(
                "Direct Durumu: $connectionStatusDirect",
                fontWeight = FontWeight.Bold
            ) // Direct durumu metni

            Divider(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) // Bölücü çizgi

            // Telemetri Verileri Bölümü
            Text(
                "Telemetri Verileri",
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold
            ) // Telemetri başlığı

            // Proxy Telemetri Kartı (Basit gösterim)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("Proxy (MAVSDK)", fontWeight = FontWeight.SemiBold)
                    Text(telemetryDataProxy) // Proxy'den gelen telemetri stringi
                }
            }

            // Direct Telemetri Kartı (Detaylı gösterim)
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text("Direct (MAVLink)", fontWeight = FontWeight.SemiBold)
                    // DirectTelemetryState objesindeki verileri formatlayarak göster
                    with(directTelemetryState) {
                        // GPS Verileri
                        Text("GPS Fix: ${fixType.format()} (Uydular: ${satellitesVisible.format()})")
                        Text("Konum: Lat ${latitude.format()}, Lon ${longitude.format()}")
                        Text(
                            "Yükseklik: MSL ${altitudeMsl.format(2)} m, Rel ${
                                relativeAltitude.format(
                                    2
                                )
                            } m"
                        )
                        Text("Yön (Heading): ${heading.format(1)}°")

                        // Uçuş Verileri
                        Text("Hız: Yer ${groundSpeed.format(2)} m/s, Hava ${airSpeed.format(2)} m/s")
                        Text("Gaz: ${throttle.format()}%")
                        Text("Tırmanma Oranı: ${climbRate.format(2)} m/s")

                        // Durum Verileri
                        Text(
                            "Tutum: Roll ${roll.format(1)}°, Pitch ${pitch.format(1)}°, Yaw ${
                                yaw.format(
                                    1
                                )
                            }°"
                        )
                        Text("Durum: ${if (armed == true) "Armed" else if (armed == false) "Disarmed" else "N/A"}, Mod: ${mode ?: "N/A"}")

                        // Batarya Verileri
                        Text("Batarya: ${batteryVoltage.format(2)} V, ${batteryCurrent.format(2)} A, Kalan %${batteryRemaining.format()}")

                        // Sistem Mesajları
                        Text("Status: ${statusText ?: "Yok"}")

                        // Debug Bilgileri
                        Text("Son Mesaj: Sys ${systemId}, Comp ${componentId}, Vers ${mavlinkVersion.format()} @ ${lastMessageTimestamp}")
                        Text("Otopilot Tip: ${autopilotType.format()}, Sistem Durumu (Raw): ${systemStatus.format()}")
                    }
                }
            }

            Divider(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) // Bölücü çizgi

            // Örnek Komut Gönderme Butonu (Direct bağlıyken aktif)

            Button(
                onClick = {
                    // Örnek bir MAVLink Heartbeat mesajı oluşturup sendMavlinkCommand fonksiyonunu çağır
                    // Buraya göndermek istediğiniz gerçek MAVLink komutlarını oluşturma mantığını ekleyeceksiniz.

                    val exampleHeartbeat = Heartbeat.builder()
                        .type(MavType.MAV_TYPE_GCS) // Ground Control Station tipi
                        .autopilot(MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA) // Otopilot tipi (GCS için INVALID)
                        .baseMode(MavModeFlag.MAV_MODE_FLAG_CUSTOM_MODE_ENABLED) // Örnek base mode flag
                        .customMode(0) // Örnek custom mode
                        .systemStatus(MavState.MAV_STATE_ACTIVE) // Sistem durumu

                        .mavlinkVersion(3) // MAVLink 1.0 için 3
                        .build()
//                    val heartbeatMessage = exampleHeartbeat as MavlinkMessage<Heartbeat>


                    sendMavlinkCommand(exampleHeartbeat) // Komutu gönderme fonksiyonunu çağır
                },
                enabled = isDirectConnected && isConnecting == null // Direct bağlıysa ve başka işlem yoksa aktif
            ) {
                Text("Örnek Komut Gönder (Heartbeat)") // Buton metni
            }

            Button(onClick = {
                val command = CommandLong.builder()
                    .targetSystem(1)
                    .targetComponent(1)
                    .command(MavCmd.MAV_CMD_PREFLIGHT_STORAGE)
                    .param1(1f) // 1=parametreleri kaydet
                    .confirmation(0)
                    .build()

                sendMavlinkCommand(command)
            }) {
                Text("Kalibrasyon Testi")
            }

            Button(onClick = {
                val command = CommandLong.builder()
                    .targetSystem(1)      // Drone'un system ID'si (genellikle 1)
                    .targetComponent(1) // Drone'un component ID'si (genellikle 1)
                    .command(MavCmd.MAV_CMD_COMPONENT_ARM_DISARM)
                    .confirmation(0)                  // Onay kodu (0 genellikle kullanılır)
                    .param1(if (true) 1f else 0f)      // 1=ARM, 0=DISARM
                    .param2(0f)                       // Force parametresi (0=normal, 1=zorla)
                    .build()

                scope.launch(Dispatchers.IO) {
                    try {
                        sendMavlinkCommand(command)
                        Log.d("ARM", "${if (true) "ARM" else "DISARM"} komutu gönderildi")
                    } catch (e: Exception) {
                        Log.e("ARM", "Komut gönderilemedi", e)
                    }
                }
            }) {
                Text("ARM Testi")
            }

            Button(onClick = {
                val command = CommandLong.builder()
                    .targetSystem(1)
                    .targetComponent(1)
                    .command(MavCmd.MAV_CMD_TURN_LIGHT)
                    .param1(33f) // GLOBAL_POSITION_INT mesaj ID
                    .param2(1000000f) // 1 saniye (mikrosaniyede)
                    .confirmation(0)
                    .build()

                sendMavlinkCommand(command)
            }) {
                Text("GPS Etkinleştir")
            }
            // İnternet İzni İsteme Butonu (Proxy için gerekebilir)
            Button(onClick = { requestInternetPermission() }) {
                Text("İnternet İzni İste")
            }
        }
    }

}

// Önizleme Composable Fonksiyonu
@Preview(showBackground = true) // Arka planı gösteren önizleme
@Composable
fun DefaultPreview() {
    DroneControllerTheme { // Tema içinde önizleme
        // Gerekli dependency'leri sağlayan sahte fonksiyonlarla DroneControllerApp'i çağır
        DroneControllerApp(
            requestUsbPermission = { _, _ -> }, // Boş lambda
            checkUsbPermission = { true }, // İzinli olduğunu varsay
            requestInternetPermission = {}, // Boş lambda
            getUsbDevices = { emptyList() } // Cihaz listesini boş döndür
        )
    }
}