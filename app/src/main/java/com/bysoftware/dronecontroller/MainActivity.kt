package com.bysoftware.dronecontroller

// import android.provider.Settings // Kullanılmıyorsa kaldırılabilir
// import androidx.compose.ui.graphics.Color // Kullanılmıyorsa kaldırılabilir
// Kanalı recieveAsFlow ile okuyorsak kapandığında ClosedReceiveChannelException fırlatabilir

// === MAVLink Kütüphanesi Importları ===
// Yaygın kullanılan MAVLink mesajları (common dialect)
// İhtiyaç duyulan enum ve flag importları (common dialect):

// Eğer ArduPilot kullanıyorsanız ArduPilot dialect importunu ve builder'a eklemeyi unutmayın
// import io.dronefleet.mavlink.ardupilotmega.ArduPilotDialect

import android.Manifest
import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Context.USB_SERVICE
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.os.Message
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.bysoftware.dronecontroller.MainActivity.Companion.TAG
import com.bysoftware.dronecontroller.ui.theme.DroneControllerTheme
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import io.dronefleet.mavlink.MavlinkConnection
import io.dronefleet.mavlink.MavlinkDialect
import io.dronefleet.mavlink.MavlinkMessage
import io.dronefleet.mavlink.ardupilotmega.ArdupilotmegaDialect
import io.dronefleet.mavlink.common.Attitude
import io.dronefleet.mavlink.common.BatteryStatus
import io.dronefleet.mavlink.common.CommandLong
import io.dronefleet.mavlink.common.GlobalPositionInt
import io.dronefleet.mavlink.common.GpsFixType
import io.dronefleet.mavlink.common.GpsRawInt
import io.dronefleet.mavlink.common.MavCmd
import io.dronefleet.mavlink.common.MavMode
import io.dronefleet.mavlink.common.MavSeverity
import io.dronefleet.mavlink.common.ParamSet
import io.dronefleet.mavlink.common.Statustext
import io.dronefleet.mavlink.common.SysStatus
import io.dronefleet.mavlink.common.VfrHud
import io.dronefleet.mavlink.minimal.Heartbeat
import io.dronefleet.mavlink.minimal.MavAutopilot
import io.dronefleet.mavlink.minimal.MavModeFlag
import io.dronefleet.mavlink.minimal.MavState
import io.dronefleet.mavlink.minimal.MavType
import io.dronefleet.mavlink.standard.StandardDialect
import io.mavsdk.System
import io.mavsdk.mavsdkserver.MavsdkServer
import io.reactivex.android.schedulers.AndroidSchedulers
import io.reactivex.disposables.CompositeDisposable
import io.reactivex.schedulers.Schedulers
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.text.DecimalFormat


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
        Toast.makeText(this, "İnternet izni ${if (isGranted) "verildi" else "reddedildi"}", Toast.LENGTH_SHORT).show()
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
                    val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
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
            Log.w(TAG,"Receiver zaten kayıtlı değildi veya kaldırılamadı.")
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
    val directDataChannel = remember { Channel<ByteArray>(Channel.BUFFERED) } // Buffer boyutu ayarlanabilir

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
    fun Double?.format(digits: Int = 6): String = if (this == null) "N/A" else if (digits <= 2) decimalFormatShort.format(this) else decimalFormat.format(this)
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
        try { directCommandOutputStream?.close() } catch (e: IOException) { Log.e(MainActivity.TAG, "[Direct] Command OutputStream kapatılırken hata", e) } finally { directCommandOutputStream = null }


        // USB Port ve Connection'ı kapatmadan önce null kontrolü
        // Portu kapatmak, IO Manager'ın (eğer durdurulmadıysa) hata vermesine neden olur.
        try { directUsbPort?.close() } catch (e: IOException) { Log.e(MainActivity.TAG, "[Direct] USB port kapatılırken hata", e) } finally { directUsbPort = null }
        try { directUsbConnection?.close() } catch (e: Exception) { Log.e(MainActivity.TAG, "[Direct] USB connection kapatılırken hata", e) } finally { directUsbConnection = null }

        Log.d(MainActivity.TAG, "[Direct] Kaynaklar temizlendi.")
    }

    // Direct USB bağlantısını keser ve kaynakları temizler
    fun disconnectDirectUsb(showStatus: Boolean = true) {
        // Zaten bağlı değilse veya kaynaklar temizse çık
        if (!isDirectConnected && directIoManager == null && directUsbPort == null && directParserJob == null && directDataChannel.isClosedForSend && directCommandOutputStream == null) {
            Log.d(MainActivity.TAG, "[Direct] Zaten bağlı değil veya temizlenmiş durumda, bağlantı kesme iptal edildi.")
            return
        }
        Log.d(MainActivity.TAG, "[Direct] Bağlantı kesiliyor...")
        if (showStatus) connectionStatusDirect = "Bağlantı kesiliyor..."
        cleanUpDirectResources() // Tüm kaynakları temizleyen fonksiyonu çağır
        isDirectConnected = false // Bağlantı durumu state'ini false yap
        if (showStatus) connectionStatusDirect = "Bağlantı kesildi (Direct)" // UI durum mesajını güncelle
        directTelemetryState = DirectTelemetryState() // Telemetri verilerini sıfırla
        Log.d(MainActivity.TAG, "[Direct] Bağlantı kesildi state güncellendi.")
        if (isConnecting == "Direct") isConnecting = null // Bağlanma işlemi sırasında kesildiyse durumu sıfırla
    }

    // Proxy bağlantı ile ilgili tüm kaynakları temizler
    fun cleanUpProxyResources() {
        Log.d(MainActivity.TAG, "[Proxy] Kaynaklar temizleniyor...")
        proxyJob?.cancel(); proxyJob = null; Log.d(MainActivity.TAG, "[Proxy] Proxy görevleri iptal edildi.")
        // Socket, Port, Connection kapatmadan önce null kontrolü
        try { udpSocket?.close() } catch (e: Exception) { Log.e(MainActivity.TAG, "[Proxy] UDP soketi kapatılırken hata", e)} finally { udpSocket = null }
        try { proxyUsbPort?.close() } catch (e: IOException) { Log.e(MainActivity.TAG, "[Proxy] USB port kapatılırken hata", e)} finally { proxyUsbPort = null }
        try { proxyUsbConnection?.close() } catch (e: Exception) { Log.e(MainActivity.TAG, "[Proxy] USB connection kapatılırken hata", e)} finally { proxyUsbConnection = null }
        disposablesProxy.clear() // RxJava aboneliklerini temizle
        // Drone ve Server'ı dispose/stop etmeden önce null kontrolü
        try { drone?.dispose() } catch (e: Exception){ Log.e(MainActivity.TAG, "[Proxy] Drone dispose edilirken hata", e)} finally { drone = null }
        try { mavsdkServer?.stop() } catch (e: Exception) { Log.e(MainActivity.TAG, "[Proxy] MAVSDK Server durdurulurken hata", e)} finally { mavsdkServer = null }
        Log.d(MainActivity.TAG, "[Proxy] Kaynaklar temizlendi.")
    }

    // Proxy bağlantısını keser ve kaynakları temizler
    fun disconnectProxy(showStatus: Boolean = true) {
        // Zaten bağlı değilse veya kaynaklar temizse çık
        if (!isProxyConnected && proxyJob == null && drone == null && mavsdkServer == null && proxyUsbPort == null && udpSocket == null) {
            Log.d(MainActivity.TAG, "[Proxy] Zaten bağlı değil veya temizlenmiş durumda, bağlantı kesme iptal edildi.")
            return
        }
        Log.d(MainActivity.TAG, "[Proxy] Bağlantı kesiliyor...")
        if (showStatus) connectionStatusProxy = "Bağlantı kesiliyor..."
        cleanUpProxyResources() // Tüm kaynakları temizleyen fonksiyonu çağır
        isProxyConnected = false // Bağlantı durumu state'ini false yap
        if (showStatus) connectionStatusProxy = "Bağlantı kesildi (Proxy)" // UI durum mesajını güncelle
        telemetryDataProxy = "Veri bekleniyor (Proxy)..." // Telemetri metnini sıfırla
        Log.d(MainActivity.TAG, "[Proxy] Bağlantı kesildi state güncellendi.")
        if (isConnecting == "Proxy") isConnecting = null // Bağlanma işlemi sırasında kesildiyse durumu sıfırla
    }

    // --- UDP Proxy Coroutine ---
    // USB Seri Port <-> UDP Soket arasında veri köprüsü kurar.
    // MAVSDK Proxy bağlantısı için kullanılır.
    fun startUdpProxy(currentUsbPort: UsbSerialPort, currentUdpSocket: DatagramSocket): Job {
        return scope.launch(Dispatchers.IO + CoroutineName("udp-proxy")) {
            Log.d(MainActivity.TAG, "[Proxy] UDP Proxy Coroutine başlatıldı.")
            // Bu coroutine iki alt görevi paralel çalıştırır:
            // 1. USB'den oku -> UDP'ye gönder
            val usbToUdpJob = launch {
                val buffer = ByteArray(4096) // Okuma buffer boyutu
                // MAVSDK Server genellikle localhost:14540 üzerinde UDP dinler
                val serverAddress = InetSocketAddress(InetAddress.getLoopbackAddress(), MainActivity.MAVSDK_SERVER_UDP_PORT)
                Log.d(MainActivity.TAG, "[Proxy] USB -> UDP (${serverAddress}) görevi başlatıldı.")
                while (isActive) { // Korutin iptal edilmediği sürece devam et
                    try {
                        // USB porttan veri oku. read metodu blocking'dir, timeout ile kullanılır.
                        val len = currentUsbPort.read(buffer, 200) // 200ms timeout
                        if (len > 0) {
                            // Okunan veriyi UDP paketi olarak MAVSDK Server'a gönder
                            val packet = DatagramPacket(buffer, len, serverAddress)
                            currentUdpSocket.send(packet)
                        } else if (len < 0) {
                            // Genellikle bağlantı koptuğunda read -1 döndürür
                            Log.w(MainActivity.TAG, "[Proxy] USB read -1 döndü, bağlantı kopmuş olabilir.")
                            break // Döngüden çık
                        }
                        // len == 0 ise timeout oldu, veri gelmedi, döngüye devam et.
                    } catch (e: IOException) {
                        // USB okuma veya UDP gönderme sırasında IO hatası (port kapanmış olabilir)
                        if (isActive) Log.e(MainActivity.TAG, "[Proxy] USB okuma/UDP gönderme IO hatası: ${e.message}")
                        break // Hata durumunda döngüden çık
                    } catch (e: Exception) {
                        // Beklenmedik diğer hatalar
                        if (isActive) Log.e(MainActivity.TAG, "[Proxy] Beklenmedik USB->UDP hatası", e)
                        break // Hata durumunda döngüden çık
                    }
                }
                Log.d(MainActivity.TAG, "[Proxy] USB -> UDP görevi durdu.")
            }

            // 2. UDP'den al -> USB'ye yaz
            val udpToUsbJob = launch {
                val buffer = ByteArray(4096) // Okuma buffer boyutu
                val packet = DatagramPacket(buffer, buffer.size) // UDP paketi
                Log.d(MainActivity.TAG, "[Proxy] UDP (${currentUdpSocket.localPort}) -> USB görevi başlatıldı.")
                while (isActive) { // Korutin iptal edilmediği sürece devam et
                    try {
                        // UDP soketinden paket al. receive metodu blocking'dir.
                        currentUdpSocket.receive(packet)
                        // Paket alındıysa ve korutin hala aktifse
                        if (packet.length > 0 && isActive) {
                            // Gelen veriyi USB porta yaz
                            try {
                                // write metodu blocking'dir.
                                currentUsbPort.write(packet.data, packet.length)
                            } catch (writeEx: IOException) {
                                // USB yazma hatası (port kapanmış olabilir)
                                if(isActive) Log.e(MainActivity.TAG, "[Proxy] USB yazma hatası: ${writeEx.message}")
                                break // Döngüden çık
                            }
                        }
                    } catch (e: IOException) {
                        // UDP alma hatası (soket kapatılmış olabilir, örneğin bağlantı kesildiğinde)
                        if (isActive) Log.w(MainActivity.TAG, "[Proxy] UDP alma hatası (muhtemelen soket kapatıldı): ${e.message}")
                        break // Döngüden çık
                    } catch (e: Exception) {
                        // Beklenmedik diğer hatalar
                        if (isActive) Log.e(MainActivity.TAG, "[Proxy] Beklenmedik UDP->USB hatası", e)
                        break // Hata durumunda döngüden çık
                    }
                }
                Log.d(MainActivity.TAG, "[Proxy] UDP -> USB görevi durdu.")
            }

            // İki alt görevin de bitmesini bekle veya birisi hata ile biterse diğerini iptal et
            try {
                joinAll(usbToUdpJob, udpToUsbJob)
            } finally {
                Log.d(MainActivity.TAG, "[Proxy] Tüm proxy görevleri tamamlandı/iptal edildi.")
                // Eğer proxy görevleri bittiğinde ana proxy korutini hala aktifse (yani dışarıdan iptal edilmediyse),
                // bu, alt görevlerden birinin hata nedeniyle durduğunu gösterir.
                if (isActive) {
                    // Ana thread'e geçip bağlantıyı kesme işlemini yap.
                    withContext(Dispatchers.Main + NonCancellable) { // Güvenli geçiş ve iptal edilemez blok
                        if(isProxyConnected || isConnecting == "Proxy") { // Hala bağlı/bağlanmaya çalışıyorsa
                            // Bağlantı hatası olduğunu kullanıcıya bildir
                            showError("Proxy iletişim hatası veya bağlantı koptu.", "Proxy")
                            // Bağlantıyı temizle. disconnectProxy, isConnecting'i de null yapar.
                            disconnectProxy(showStatus = false) // Hata mesajını zaten gösterdik
                        } else {
                            // Bağlantı kesme işlemi sırasında doğal olarak buraya düşülmüş olabilir
                            Log.d(TAG, "[Proxy] Proxy görevleri bitti, ancak bağlantı durumu zaten kesilmişti.")
                        }
                    }
                } else {
                    // Korutin dışarıdan (örn. disconnectProxy çağrısıyla) iptal edildi.
                    Log.d(TAG, "[Proxy] Proxy görevleri dışarıdan iptal edildi.")
                }
            }
            Log.d(MainActivity.TAG, "[Proxy] UDP Proxy Coroutine tamamlandı.")
        }
    }

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
                        telemetryDataProxy = "Lat: ${position.latitudeDeg.format()}, Lon: ${position.longitudeDeg.format()}, Alt: ${position.relativeAltitudeM.format(2)} m"
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
                        Log.d(MainActivity.TAG,"[Proxy] Arm Durumu: ${if(armed) "Armed" else "Disarmed"}")
                        // İsterseniz bu durumu UI'da Proxy telemetri kısmında gösterebilirsiniz.
                    },
                    { error ->
                        Log.w(MainActivity.TAG, "[Proxy] Arm durumu alınamadı: ${error.message}")
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
                        val batteryText = battery.remainingPercent?.let { "${(it * 100).toInt()}%" } ?: "N/A"
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
                        MavMode.valueOf(p.customMode().toString())?.name ?: "CUSTOM(${p.customMode()})"
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
                    yaw = Math.toDegrees(p.yaw().toDouble()) // Genellikle manyetik yön değil, aracın kendi eksenindeki dönüşü
                )
                is SysStatus -> newState.copy(
                    batteryVoltage = p.voltageBattery() / 1000.0, // mV'tan V'a (UShort'tan Double'a)
                    // Akım -1 ise geçersizdir (null yap)
                    batteryCurrent = p.currentBattery().let { if(it < 0) null else it / 100.0 }, // cA'dan A'ya (Short'tan Double'a)
                    // Kalan batarya -1 ise geçersizdir (null yap)
                    batteryRemaining = p.batteryRemaining().let { if(it < 0) null else it?.toInt() } // Byte'tan Int'e
                )
                is BatteryStatus -> {
                    // BatteryStatus mesajı birden fazla batarya için bilgi içerebilir.
                    // Genellikle id=0 ana bataryayı temsil eder. Sadece id=0 olanı işleyelim.
                    if (p.id().toInt() == 0) { // UByte'ı Int'e çevir
                        newState.copy(
                            // Akım -1 ise geçersizdir (null yap)
                            batteryCurrent = p.currentBattery().let { if(it < 0) null else it / 100.0 }, // cA'dan A'ya (Short'tan Double'a)
                            // Kalan batarya -1 ise geçersizdir (null yap)
                            batteryRemaining = p.batteryRemaining().let { if(it < 0) null else it?.toInt() } // Byte'tan Int'e
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
                    val severity = p.severity().value()                    // p.text() ByteArray döndürür, String'e çevirirken null terminator'dan sonrasını kesmek gerekebilir.
                    val textMessage = try { String(p.text().takeWhile { it.code.toByte() != 0.toByte() }.toByteArray()) } catch (e: Exception) { "Decode Error" }
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
    fun startDirectMavlinkParser(inputStream: InputStream, outputStream: OutputStream? = null): Job {
        return scope.launch(Dispatchers.IO + CoroutineName(name = "mavlink-parser")) {
            Log.d(MainActivity.TAG, "[Direct] MAVLink Parser Coroutine başlatıldı.")

            val connection: MavlinkConnection
            try {
                // MAVLink Bağlantısını InputStream (okuma için) ve OutputStream (yazma için) ile oluştur
                // InputStream PipedInputStream olmalı, OutputStream doğrudan porta yazan implementasyon olmalı.
                connection = MavlinkConnection.builder(inputStream, outputStream)
                    // Desteklenecek MAVLink dialect'lerini ekleyin. Common genellikle zorunludur.
                    .dialect(MavAutopilot.MAV_AUTOPILOT_GENERIC,  StandardDialect())
                    .dialect(MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA,  ArdupilotmegaDialect())                    // Eğer ArduPilot kullanıyorsanız ArduPilotmega dialect'i ekleyin.
                    // .dialect(MavlinkDialect.ARDUPILOTMEGA)
                    // Eğer PX4 kullanıyorsanız veya başka dialectler gerekiyorsa ekleyin.
                    // .dialect(...)
                    .build()
                Log.d(MainActivity.TAG, "[Direct Parser] MavlinkConnection oluşturuldu.")
            } catch (e: Exception) {
                // MavlinkConnection oluşturulurken hata oluşursa
                Log.e(MainActivity.TAG, "[Direct Parser] Failed to build MavlinkConnection", e)
                withContext(Dispatchers.Main + NonCancellable) {
                    showError("Parser başlatma hatası: MavlinkConnection oluşturulamadı. ${e.message?.take(100)}", "Direct")
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
                        Log.d(TAG, "[Direct Parser] connection.next() returned null. Stream ended or closed.")
                        break // Mesaj okuma döngüsünden çık
                    }
                }
            } catch (e: IOException) {
                // InputStream'den okuma sırasında IO hatası (pipe kapatıldıysa, USB bağlantısı kesildiyse vb.)
                // Bu genellikle Direct bağlantının kesildiği anlamına gelir.
                if (isActive) { // Eğer korutin hata oluştuğunda hala aktifse (dışarıdan iptal edilmediyse)
                    Log.e(TAG, "[Direct Parser] IO error reading MAVLink stream: ${e.message}", e)
                    withContext(Dispatchers.Main + NonCancellable) { // UI thread'ine güvenli geçiş
                        showError("Bağlantı hatası (Stream Okuma): ${e.message?.take(100)}", "Direct")
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
                try { inputStream.close() } catch (e: IOException) { Log.e(TAG, "[Direct Parser] Error closing InputStream in finally", e) }
                // OutputStream (komut gönderme stream'i) de burada kapatılabilir, ancak genellikle
                // bağlı olduğu port veya pipe'ın diğer ucu başka yerde kapatılır. Yine de double close zararlı değildir.
                try { outputStream?.close() } catch (e: IOException) { Log.e(TAG, "[Direct Parser] Error closing OutputStream in finally", e) }

                Log.d(MainActivity.TAG, "[Direct Parser] MAVLink Parser Coroutine tamamlandı.")
            }
        }
    }


    // --- Serial Input Output Manager Listener ---
    // Bu listener, USB seri portundan gelen ham baytları alır (SerialInputOutputManager'ın thread'inde).
    // Aldığı baytları directDataChannel'a gönderir.
    class DirectUsbSerialListener : SerialInputOutputManager.Listener {
        // Seri porttan yeni veri geldiğinde çağrılır
        override fun onNewData(data: ByteArray) {
            // Gelen veriyi directDataChannel'a gönder.
            // Bu işlem bloklayıcı olabilir (eğer kanal doluysa), bu yüzden bir korutin içinde başlatılır.
            scope.launch(Dispatchers.IO) { // IO thread'inde korutin başlat
                try {
                    // Kanal açık ve aktifse veriyi gönder
                    if (!directDataChannel.isClosedForSend) {
                        directDataChannel.send(data) // Bayt dizisini kanala gönder
                    } else {
                        // Kanal kapatılmışsa gönderme logu
                        Log.w(MainActivity.TAG, "[Direct Listener] Attempted to send data, but channel is closed.")
                    }
                } catch (e: ClosedSendChannelException) {
                    // Kanal kapatılmış (disconnect DirectUsb çağrıldığında parser ve kanal kapatılır), beklenen durum.
                    Log.w(MainActivity.TAG, "[Direct Listener] Channel closed while trying to send data.")
                } catch (e: Exception) {
                    // Diğer olası hatalar (örn. kanal senkronizasyon sorunları vb.)
                    Log.e(MainActivity.TAG, "[Direct Listener] Error sending data to channel", e)
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
    @SuppressLint("ServiceCast")
    fun connectProxy(device: UsbDevice) {
        // Zaten başka bir bağlantı işlemi devam ediyorsa yeni bir tane başlatma
        if (isConnecting != null) {
            showError("Başka bir bağlantı işlemi sürüyor.", "Proxy");
            return
        }
        isConnecting = "Proxy" // Bağlantı durumunu güncelle
        // Varsa Direct bağlantıyı kes (ikisi aynı anda aktif olmamalı)
        disconnectDirectUsb(showStatus = false)
        // UI durum mesajlarını güncelle
        connectionStatusProxy = "Bağlanılıyor (Proxy)..."; telemetryDataProxy = "..."
        selectedUsbDevice = device // Seçilen cihazı kaydet

        // Bağlantı işlemini bir korutin içinde ve IO thread'inde başlatıyoruz
        scope.launch(Dispatchers.IO) {
            // Try bloğu dışında null olarak başlatılan kaynaklar, hata oluşursa temizlenmeyi kolaylaştırır.
            var openedConnection: UsbDeviceConnection? = null
            var openedPort: UsbSerialPort? = null // Nullable local var
            var openedSocket: DatagramSocket? = null
            var startedServer: MavsdkServer? = null
            var connectedDrone: System? = null
            var startedProxyJob: Job? = null // Proxy köprü korutininin referansı

            try {
                // 1. USB Aygıtı Aç ve Seri Portu Yapılandır
                val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
                openedConnection = usbManager?.openDevice(device)
                    ?: throw IOException("USB cihaz bağlantısı açılamadı.") // Bağlantı null dönerse hata fırlat

                val driver = UsbSerialProber.getDefaultProber().probeDevice(device) // Cihaz için uygun sürücüyü bul
                    ?: throw IOException("USB seri sürücüsü bulunamadı.") // Sürücü bulunamazsa hata fırlat

                openedPort = driver.ports.firstOrNull() // İlk kullanılabilir portu al
                    ?: throw IOException("USB seri port bulunamadı.") // Port bulunamazsa hata fırlat

                openedPort.open(openedConnection) // Portu bağlantı üzerinden aç
                openedPort.setParameters(MainActivity.DEFAULT_BAUD_RATE, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE) // Port parametrelerini ayarla (baud rate, data bits, stop bits, parity)
                Log.d(TAG, "[Proxy] USB Port açıldı ve yapılandırıldı (${MainActivity.DEFAULT_BAUD_RATE} baud).")

                // 2. UDP Soketi Oluştur
                // MAVSDK Server ile iletişim kurmak için bir UDP soketi oluşturulur.
                openedSocket = DatagramSocket() // Rastgele boş bir yerel portta soket oluşturur
                // Soketi MAVSDK Server'ın beklediği adrese (localhost) ve porta bağlar (sadece göndermek için connect kullanılır).
                openedSocket.connect(InetAddress.getLoopbackAddress(), MainActivity.MAVSDK_SERVER_UDP_PORT)
                Log.d(TAG, "[Proxy] UDP Soketi oluşturuldu ve ${MainActivity.MAVSDK_SERVER_UDP_PORT} portuna bağlandı.")

                // 3. USB <-> UDP Proxy Coroutine'i Başlat
                // Bu coroutine, USB seri portu ile UDP soketi arasında iki yönlü veri aktarımını yönetir.
                startedProxyJob = startUdpProxy(openedPort, openedSocket) // Proxy görevini başlat ve referansını al
                Log.d(TAG, "[Proxy] UDP Proxy görevi başlatıldı.")

                // 4. MAVSDK Server'ı Başlat
                // MAVSDK Server, proxy üzerinden gelen MAVLink verisini işleyecek.
                startedServer = MavsdkServer() // MAVSDK Server objesini oluştur
                startedServer.run() // Server'ı arka planda çalıştır (bloklamaz)
                Log.d(TAG, "[Proxy] MAVSDK Server başlatıldı.")

                // 5. MAVSDK System'e Bağlan
                // MAVSDK System objesi, Server ile iletişim kurarak drone'u temsil eder.
                // Server'ın ve drone'un tamamen hazır olması için biraz beklemek gerekebilir.
                // Gerçek uygulamada burada MAVSDK'nın bağlantı durumu dinlenmelidir.
                delay(5000) // Örnek amaçlı 5 saniye bekleme (İyileştirilmeli!)

                // MAVSDK System'i, MAVSDK Server'ın localhost'taki UDP portuna bağla.
                connectedDrone = System("127.0.0.1", MainActivity.MAVSDK_SERVER_UDP_PORT)
                Log.d(TAG, "[Proxy] MAVSDK System (Drone) objesi oluşturuldu.")

                // MAVSDK System üzerinden gelen telemetri verilerini dinlemek için abonelikleri ayarla.
                setupTelemetryListeners(connectedDrone) // RxJava aboneliklerini kur
                Log.d(TAG, "[Proxy] Telemetri dinleyicileri ayarlandı.")

                // Bağlantı aşamaları başarılı olduysa UI state'leri Main thread'de güncelle
                withContext(Dispatchers.Main) {
                    proxyUsbConnection = openedConnection // Bağlantı referansını kaydet
                    proxyUsbPort = openedPort // Port referansını kaydet
                    udpSocket = openedSocket // Soket referansını kaydet
                    mavsdkServer = startedServer // Server referansını kaydet
                    drone = connectedDrone // Drone referansını kaydet
                    proxyJob = startedProxyJob // Proxy görevi referansını kaydet

                    connectionStatusProxy = "Bağlandı: ${device.deviceName} (${MainActivity.DEFAULT_BAUD_RATE}) via MAVSDK Proxy" // UI durum mesajı
                    isProxyConnected = true // Bağlantı durumu state'ini true yap
                    isConnecting = null // Bağlantı işlemi tamamlandı

                    Log.d(TAG, "[Proxy] Bağlantı başarılı, state güncellendi.")
                }

            } catch (e: Exception) {
                // Bağlantı aşamalarından herhangi birinde hata oluşursa
                Log.e(TAG, "[Proxy] Bağlantı Hatası: ${e.message}", e)
                // Hata oluşursa, o ana kadar başarılı olan tüm kaynakları temizle
                try { startedProxyJob?.cancel() } catch (e: Exception) { Log.e(TAG, "[Proxy] Error cancelling proxyJob on error", e)} // Proxy görevini iptal et
                try { startedServer?.stop() } catch (e: Exception) { Log.e(TAG, "[Proxy] Error stopping server on error", e)} // MAVSDK Server'ı durdur
                try { connectedDrone?.dispose() } catch (e: Exception){ Log.e(TAG, "[Proxy] Error disposing drone on error", e)} // Drone objesini temizle
                try { openedSocket?.close() } catch (e: Exception) { Log.e(TAG, "[Proxy] Error closing socket on error", e)} // UDP soketini kapat
                try { openedPort?.close() } catch (e: IOException) { Log.e(TAG, "[Proxy] Error closing port on error", e) } // USB portunu kapat
                try { openedConnection?.close() } catch (e: Exception) { Log.e(TAG, "[Proxy] Error closing connection on error", e)} // USB bağlantısını kapat

                // UI state'lerini hata durumuna göre Main thread'de güncelle
                withContext(Dispatchers.Main) {
                    showError("Bağlantı kurulamadı: ${e.message?.take(100)}", "Proxy") // Hata mesajını kullanıcıya göster
                    // Tüm resource state'lerini null yap
                    proxyUsbConnection = null
                    proxyUsbPort = null
                    udpSocket = null
                    mavsdkServer = null
                    drone = null
                    proxyJob = null
                    isProxyConnected = false // Bağlantı durumu false
                    isConnecting = null // Bağlantı işlemi tamamlandı (başarısız)

                    Log.d(TAG, "[Proxy] Bağlantı başarısız, state güncellendi.")
                }
            }
        }
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

                val driver = UsbSerialProber.getDefaultProber().probeDevice(device) // Cihaz için uygun sürücüyü bul
                    ?: throw IOException("USB seri sürücüsü bulunamadı.") // Sürücü bulunamazsa hata

                port = driver.ports.firstOrNull() // İlk kullanılabilir portu al
                    ?: throw IOException("USB seri port bulunamadı.") // Port bulunamazsa hata

                port.open(connection) // Portu bağlantı üzerinden aç
                port.setParameters(baudRate, UsbSerialPort.DATABITS_8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE) // Port parametrelerini ayarla
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
                val pipeWriterJob = scope.launch(Dispatchers.IO + CoroutineName(name = "direct-pipe-writer")) {
                    Log.d(TAG, "[Direct Pipe Writer] Başlatıldı.")
                    try {
                        // directDataChannel'dan gelen byte array'lerini oku (kanal kapandığında döngü sona erer)
                        for (data in directDataChannel) {
                            try {
                                // Gelen veriyi Pipe OutputStream'e yaz. Bu işlem Pipe'ın buffer'ı doluysa bloklayıcıdır.
                                pipedOutputStreamFromSerial.write(data)
                            } catch (writeError: IOException) {
                                // Pipe'a yazma sırasında hata oluşursa (örn. Pipe'ın diğer ucu (InputStream) kapatıldıysa)
                                if (isActive) Log.e(TAG, "[Direct Pipe Writer] Pipe'a yazma hatası: ${writeError.message}", writeError)
                                break // Yazma hatasında döngüden çık
                            }
                        }
                        Log.d(TAG, "[Direct Pipe Writer] Kanal okuma döngüsü bitti (kanal kapatıldı veya hata oluştu).")
                    } catch (e: Exception) {
                        // Kanal toplama sırasında beklenmedik bir hata oluşursa
                        if (isActive) Log.e(TAG, "[Direct Pipe Writer] Beklenmedik hata: ${e.message}", e)
                    } finally {
                        // Pipe yazar korutini bittiğinde Pipe OutputStream'i kapat.
                        // Bu, Pipe'ın okuma ucuna (PipedInputStream) stream sonu sinyali gönderir.
                        try {
                            pipedOutputStreamFromSerial.close()
                            Log.d(TAG, "[Direct Pipe Writer] PipedOutputStream kapatıldı.")
                        } catch (e: IOException) {
                            Log.e(TAG, "[Direct Pipe Writer] PipedOutputStream kapatılırken hata: ${e.message}", e)
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
                            Log.e(TAG, "[Direct OutputStream] Tek bayt yazma hatası: ${e.message}", e)
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
                            Log.e(TAG, "[Direct OutputStream] Bayt dizisi yazma hatası (${len} bayt): ${e.message}", e)
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
                directParserJob = startDirectMavlinkParser(pipedInputStreamForParser, commandOutputStream)

                // IO Manager, Port ve Connection başarılı olduysa UI state'leri Main thread'de güncelle
                withContext(Dispatchers.Main) {
                    directUsbConnection = connection // Bağlantı referansını kaydet
                    directUsbPort = port // Port referansını kaydet
                    directIoManager = ioManager // IO Manager referansını kaydet
                    directCommandOutputStream = commandOutputStream // Command OutputStream referansını state'e kaydet
                    // directParserJob state'i zaten yukarıda atandı

                    connectionStatusDirect = "Bağlandı: ${device.deviceName} (${baudRate})" // UI durum mesajı
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
                try { ioManager?.stop() } catch (e: Exception) { Log.e(MainActivity.TAG, "[Direct] Error stopping ioManager on error", e)}
                // Port ve Connection kapatılacak
                try { port?.close() } catch (e: IOException) { Log.e(MainActivity.TAG, "[Direct] Error closing port on error", e) }
                try { connection?.close() } catch (e: Exception) { Log.e(MainActivity.TAG, "[Direct] Error closing connection on error", e)}
                // Pipe input stream'i kapat (eğer oluşturulduysa). Bu parser job'ı (varsa) sonlandırır.
                try { pipedInputStreamForParser?.close() } catch (e: IOException) { Log.e(MainActivity.TAG, "[Direct] Error closing pipe input stream on error", e) }
                // Command OutputStream'i kapat (eğer oluşturulduysa).
                try { commandOutputStream?.close() } catch (e: IOException) { Log.e(MainActivity.TAG, "[Direct] Error closing command output stream on error", e) }
                // Pipe writer job'ı (varsa) iptal et. directDataChannel.close() zaten cleanUpResources içinde yapılıyor,
                // bu genellikle pipeWriterJob'ı sonlandırır. Explicit cancel da yapılabilir.
                // Eğer pipeWriterJob bu catch bloğundan önce başlatıldıysa ve hala aktifse, iptal etmek gerekebilir.
                // Bu yapıda pipeWriterJob, connectDirectUsb scope'u içinde başlatılmıyor, startDirectMavlinkParser içinde de değil.
                // PipeWriterJob, startDirectMavlinkParser'a taşınmalıdır. Önceki adımda yaptığımız gibi.
                // Düzeltme: PipeWriterJob, startDirectMavlinkParser'a taşınmıştı. disconnectDirectUsb çağrıldığında parser job iptal edilir, o da pipeWriterJob'ı sonlandırır.

                // UI state'lerini hata durumuna göre Main thread'de güncelle
                withContext(Dispatchers.Main) {
                    showError("Bağlantı kurulamadı: ${e.message?.take(100)}", "Direct") // Hata mesajını kullanıcıya göster
                    // Tüm resource state'lerini null yap
                    directUsbConnection = null
                    directUsbPort = null
                    directIoManager = null
                    directCommandOutputStream = null
                    directParserJob = null // Hata durumunda parser job başlatılamamış veya hata vermiş olmalı
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

    fun startRcCalibration(mavlinkConnection : MavlinkConnection) {
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
                Log.d(TAG, "[Direct Send] Komut gönderme başlatıldı: ${payload::class.java.simpleName}")

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
                    is Heartbeat  -> {
                        mavConn.send1(255, 0, payload) // MAVLink 1.0
                        // mavConn.send2(255, 0, payload) // MAVLink 2.0
                    }
                    else -> throw IllegalArgumentException("Geçersiz payload tipi")
                }

                Log.d(TAG, "[Direct Send] Komut başarıyla gönderildi: ${payload::class.java.simpleName}")

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
                                connectProxy(device) // İzin varsa doğrudan bağlan
                            } else {
                                // İzin yoksa izin iste ve sonuç geldiğinde bağlanmayı dene
                                requestUsbPermission(device) { granted, grantedDevice ->
                                    if (granted && grantedDevice != null) {
                                        connectProxy(grantedDevice)
                                    } else {
                                        showError("USB izni verilmedi.", "Proxy") // İzin verilmediyse hata göster
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
                                connectDirectUsb(device, MainActivity.DEFAULT_BAUD_RATE) // İzin varsa doğrudan bağlan
                            } else {
                                // İzin yoksa izin iste ve sonuç geldiğinde bağlanmayı dene
                                requestUsbPermission(device) { granted, grantedDevice ->
                                    if (granted && grantedDevice != null) {
                                        connectDirectUsb(grantedDevice, MainActivity.DEFAULT_BAUD_RATE)
                                    } else {
                                        showError("USB izni verilmedi.", "Direct") // İzin verilmediyse hata göster
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
                    onDismissRequest = { showDeviceSelector = false }, // Dialog dışında tıklanınca kapat
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
                                            Text(device.deviceName ?: "Bilinmiyor", fontWeight = FontWeight.Bold)
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

            Divider(modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)) // Bölücü çizgi

            // Bağlantı Durumları Bölümü
            Text("Proxy Durumu: $connectionStatusProxy", fontWeight = FontWeight.Bold) // Proxy durumu metni
            Text("Direct Durumu: $connectionStatusDirect", fontWeight = FontWeight.Bold) // Direct durumu metni

            Divider(modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)) // Bölücü çizgi

            // Telemetri Verileri Bölümü
            Text("Telemetri Verileri", fontSize = 20.sp, fontWeight = FontWeight.Bold) // Telemetri başlığı

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
                        Text("Yükseklik: MSL ${altitudeMsl.format(2)} m, Rel ${relativeAltitude.format(2)} m")
                        Text("Yön (Heading): ${heading.format(1)}°")

                        // Uçuş Verileri
                        Text("Hız: Yer ${groundSpeed.format(2)} m/s, Hava ${airSpeed.format(2)} m/s")
                        Text("Gaz: ${throttle.format()}%")
                        Text("Tırmanma Oranı: ${climbRate.format(2)} m/s")

                        // Durum Verileri
                        Text("Tutum: Roll ${roll.format(1)}°, Pitch ${pitch.format(1)}°, Yaw ${yaw.format(1)}°")
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

            Divider(modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp)) // Bölücü çizgi

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

            Button(onClick = {val command = CommandLong.builder()
                .targetSystem(1)
                .targetComponent(1)
                .command(MavCmd.MAV_CMD_PREFLIGHT_STORAGE)
                .param1(1f) // 1=parametreleri kaydet
                .confirmation(0)
                .build()

                sendMavlinkCommand(command)}) {
                Text("Kalibrasyon Testi")
            }

            Button(onClick = { val command = CommandLong.builder()
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
                }}) {
                Text("ARM Testi")
            }

            Button(onClick = { val command = CommandLong.builder()
                .targetSystem(1)
                .targetComponent(1)
                .command(MavCmd.MAV_CMD_SET_MESSAGE_INTERVAL)
                .param1(33f) // GLOBAL_POSITION_INT mesaj ID
                .param2(1000000f) // 1 saniye (mikrosaniyede)
                .confirmation(0)
                .build()

                sendMavlinkCommand(command)}) {
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