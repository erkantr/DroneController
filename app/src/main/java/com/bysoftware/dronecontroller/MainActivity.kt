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
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import com.bysoftware.dronecontroller.config.AppConfig
import com.bysoftware.dronecontroller.service.DirectConnectionService
import com.bysoftware.dronecontroller.service.ProxyConnectionService
import com.bysoftware.dronecontroller.ui.navigation.AppNavigation
import com.bysoftware.dronecontroller.ui.screens.DroneData
import com.bysoftware.dronecontroller.ui.theme.DroneControllerTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var usbManager: UsbManager
    private var usbPermissionCallback: ((Boolean, UsbDevice?) -> Unit)? = null

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
                    usbPermissionCallback = null 
                }
            }
        }
    }

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { permissions ->
            permissions.entries.forEach {
                Log.d(AppConfig.TAG, "${it.key} = ${it.value}")
                if (!it.value) {
                    Toast.makeText(this, "${it.key} izni gerekli!", Toast.LENGTH_LONG).show()
                }
            }
        }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge() 

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        val filter = IntentFilter(AppConfig.ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbPermissionReceiver, filter)
        }

        val requiredPermissions = mutableListOf(Manifest.permission.INTERNET)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Gerekirse Bluetooth izinleri eklenebilir
        }
        requestPermissionLauncher.launch(requiredPermissions.toTypedArray())

        setContent {
            DroneControllerTheme {
                val context = LocalContext.current
                val scope = rememberCoroutineScope() 
                val snackbarHostState = remember { SnackbarHostState() } // Bu AppNavigation'a taşınabilir veya orada da oluşturulabilir.

                val directService = remember {
                    DirectConnectionService(context, scope, { msg, type -> 
                         scope.launch { snackbarHostState.showSnackbar("Hata ($type): $msg") }
                    }, { /* Bağlantı durumu değiştiğinde isConnecting state'i güncellenir. */ })
                }
                val proxyService = remember {
                    ProxyConnectionService(context, scope, { msg, type -> 
                        scope.launch { snackbarHostState.showSnackbar("Hata ($type): $msg") }
                    }, { /* Bağlantı durumu değiştiğinde isConnecting state'i güncellenir. */ })
                }

                val directTelemetryState by directService.directTelemetryState.collectAsState()
                val isDirectConnected by directService.isDirectConnected.collectAsState()
                val connectionStatusDirect by directService.connectionStatusDirect.collectAsState()

                val telemetryDataProxy by proxyService.telemetryDataProxy.collectAsState()
                val isProxyConnected by proxyService.isProxyConnected.collectAsState()
                val connectionStatusProxy by proxyService.connectionStatusProxy.collectAsState()

                val droneDataForMainScreen by remember(directTelemetryState, telemetryDataProxy, isDirectConnected, isProxyConnected) {
                    derivedStateOf {
                        if (isDirectConnected) {
                            // DirectTelemetryState'den DroneData'ya tam eşleme
                            DroneData(
                                latitude = directTelemetryState.latitude,
                                longitude = directTelemetryState.longitude,
                                altitudeMsl = directTelemetryState.altitudeMsl, // MapScreen'deki ana altitude için MSL kullanılıyor
                                altitude = directTelemetryState.altitudeMsl, // Harita için birincil irtifa
                                relativeAltitude = directTelemetryState.relativeAltitude,
                                heading = directTelemetryState.heading,
                                groundspeed = directTelemetryState.groundSpeed,
                                airSpeed = directTelemetryState.airSpeed,
                                roll = directTelemetryState.roll,
                                pitch = directTelemetryState.pitch,
                                yaw = directTelemetryState.yaw,
                                batteryRemaining = directTelemetryState.batteryRemaining,
                                batteryVoltage = directTelemetryState.batteryVoltage,
                                batteryCurrent = directTelemetryState.batteryCurrent,
                                armed = directTelemetryState.armed,
                                mode = directTelemetryState.mode,
                                fixType = directTelemetryState.fixType,
                                satellitesVisible = directTelemetryState.satellitesVisible,
                                throttle = directTelemetryState.throttle,
                                climbRate = directTelemetryState.climbRate,
                                telemetryTimestamp = directTelemetryState.lastMessageTimestamp,
                                isProxyData = false
                            )
                        } else if (isProxyConnected) {
                            // TODO: Proxy için DroneData dönüşümü burada detaylı yapılmalı.
                            // ProxyService'in telemetryDataProxy string'ini veya MAVSDK telemetri objelerini ayrıştırmanız gerekir.
                            DroneData(
                                // Örnek alanlar, proxy verisine göre doldurulmalı
                                // latitude = proxyParsedTelemetry.latitude,
                                // longitude = proxyParsedTelemetry.longitude,
                                telemetryTimestamp = System.currentTimeMillis(), // veya proxy'den gelen zaman damgası
                                isProxyData = true
                                // Diğer alanlar proxy verisine göre doldurulmalı
                            )
                        } else {
                            DroneData() // Bağlantı yoksa boş veri
                        }
                    }
                }

                AppNavigation(
                    mainActivity = this,
                    requestUsbPermission = ::requestPermission,
                    checkUsbPermission = ::checkUsbPermission,
                    getUsbDevices = ::getUsbDevices,
                    openUsbDeviceConnection = ::openDeviceConnection,
                    directConnectionService = directService, // Servisler doğrudan AppNavigation'a geçiliyor
                    proxyConnectionService = proxyService,
                    droneData = droneDataForMainScreen, 
                    requestInternetPermission = { 
                        requestPermissionLauncher.launch(arrayOf(Manifest.permission.INTERNET)) 
                    },
                    isDirectConnected = isDirectConnected,
                    connectionStatusDirect = connectionStatusDirect,
                    isProxyConnected = isProxyConnected,
                    connectionStatusProxy = connectionStatusProxy,
                    telemetryDataProxy = telemetryDataProxy // Proxy'nin ham telemetri string'i
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbPermissionReceiver)
    }

    fun requestPermission(device: UsbDevice, callback: (Boolean, UsbDevice?) -> Unit) {
        if (usbManager.hasPermission(device)) {
            Log.d(AppConfig.TAG, "USB izni zaten var: ${device.deviceName}")
            callback(true, device)
            return
        }
        Log.d(AppConfig.TAG, "USB izni isteniyor: ${device.deviceName}")
        this.usbPermissionCallback = callback 
        val intentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        val permissionIntent = PendingIntent.getBroadcast(
            this,
            AppConfig.USB_PERMISSION_REQUEST_CODE,
            Intent(AppConfig.ACTION_USB_PERMISSION),
            intentFlags
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