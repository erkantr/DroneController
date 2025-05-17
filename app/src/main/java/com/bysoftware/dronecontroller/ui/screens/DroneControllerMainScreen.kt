package com.bysoftware.dronecontroller.ui.screens

import android.hardware.usb.UsbDevice
import android.util.Log
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Usb
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.SignalWifi4Bar
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.bysoftware.dronecontroller.config.AppConfig
import com.bysoftware.dronecontroller.service.DirectConnectionService
import com.bysoftware.dronecontroller.service.ProxyConnectionService
import io.dronefleet.mavlink.common.*
import io.dronefleet.mavlink.minimal.Heartbeat
import io.dronefleet.mavlink.minimal.MavAutopilot
import io.dronefleet.mavlink.minimal.MavModeFlag
import io.dronefleet.mavlink.minimal.MavState
import io.dronefleet.mavlink.minimal.MavType
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DroneControllerMainScreen(
    droneData: DroneData,
    connectProxy: (device: UsbDevice) -> Unit,
    connectDirectUsb: (device: UsbDevice) -> Unit,
    disconnectProxy: () -> Unit,
    disconnectDirectUsb: () -> Unit,
    requestUsbPermission: (device: UsbDevice, onResult: (Boolean, UsbDevice?) -> Unit) -> Unit,
    checkUsbPermission: (UsbDevice) -> Boolean,
    requestInternetPermission: () -> Unit,
    getUsbDevices: () -> List<UsbDevice>,
    onNavigateToMap: () -> Unit,
    isDirectConnected: Boolean,
    connectionStatusDirect: String,
    isProxyConnected: Boolean,
    connectionStatusProxy: String,
    telemetryDataProxy: String,
    snackbarHostState: SnackbarHostState,
    directConnectionService: DirectConnectionService? = null,
    proxyConnectionService: ProxyConnectionService? = null
) {
    val scope = rememberCoroutineScope()

    var selectedUsbDevice by remember { mutableStateOf<UsbDevice?>(null) }
    var usbDevices by remember { mutableStateOf<List<UsbDevice>>(emptyList()) }
    var showDeviceSelector by remember { mutableStateOf(false) }
    var connectionTypeToEstablish by remember { mutableStateOf<String?>(null) }

    fun showError(message: String) {
        scope.launch {
            snackbarHostState.showSnackbar(message)
        }
        Log.e(AppConfig.TAG, "DroneControllerMainScreen Hata: $message")
    }

    LaunchedEffect(Unit) {
        usbDevices = getUsbDevices()
        requestInternetPermission()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Drone Bağlantı Arayüzü") },
                actions = {
                    IconButton(onClick = onNavigateToMap) {
                        Icon(
                            imageVector = Icons.Filled.Map,
                            contentDescription = "Harita Görünümü"
                        )
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Card(elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)) {
                Column(
                    modifier = Modifier
                        .padding(16.dp)
                        .fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "Bağlantı Merkezi",
                        style = MaterialTheme.typography.titleLarge
                    )

                    Button(
                        onClick = {
                            usbDevices = getUsbDevices()
                            if (usbDevices.isNotEmpty()) {
                                showDeviceSelector = true
                            } else {
                                showError("Kullanılabilir USB cihazı bulunamadı.")
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.Usb, contentDescription = "USB Cihazları")
                        Spacer(Modifier.width(8.dp))
                        Text("USB Cihazlarını Listele (${usbDevices.size})")
                    }

                    selectedUsbDevice?.let {
                        Text(
                            text = "Seçili Cihaz: ${it.deviceName}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Button(
                            onClick = {
                                selectedUsbDevice?.let {
                                    connectionTypeToEstablish = "Direct"
                                    connectDirectUsb(it)
                                } ?: showError("Lütfen önce bir USB cihazı seçin.")
                            },
                            enabled = selectedUsbDevice != null && !isDirectConnected,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDirectConnected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
                            )
                        ) {
                            Icon(Icons.Filled.SignalWifi4Bar, contentDescription = "Direct Bağlantı")
                            Spacer(Modifier.width(8.dp))
                            Text("Direct USB")
                        }

                        Button(
                            onClick = {
                                selectedUsbDevice?.let {
                                    connectionTypeToEstablish = "Proxy"
                                    connectProxy(it)
                                    requestInternetPermission()
                                } ?: showError("Lütfen önce bir USB cihazı seçin.")
                            },
                            enabled = selectedUsbDevice != null && !isProxyConnected,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isProxyConnected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.secondaryContainer
                            )
                        ) {
                            Icon(Icons.Filled.Wifi, contentDescription = "Proxy Bağlantı")
                            Spacer(Modifier.width(8.dp))
                            Text("Proxy (MAVSDK)")
                        }
                    }

                    Text(connectionStatusDirect, fontWeight = FontWeight.SemiBold, color = if(isDirectConnected) Color.Green else Color.Gray)
                    Text(connectionStatusProxy, fontWeight = FontWeight.SemiBold, color = if(isProxyConnected) Color.Green else Color.Gray)

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        Button(
                            onClick = disconnectDirectUsb,
                            enabled = isDirectConnected,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Text("Direct Kes")
                        }
                        Button(
                            onClick = disconnectProxy,
                            enabled = isProxyConnected,
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                        ) {
                            Text("Proxy Kes")
                        }
                    }
                }
            }

            if (isDirectConnected) {
                DirectTelemetryCard(telemetry = droneData, directConnectionService = directConnectionService)
            }
            if (isProxyConnected) {
                ProxyTelemetryCard(telemetryDataProxy = telemetryDataProxy, proxyConnectionService = proxyConnectionService)
            }

            if (showDeviceSelector) {
                DeviceSelectorDialog(
                    devices = usbDevices,
                    onDeviceSelected = { device ->
                        selectedUsbDevice = device
                        showDeviceSelector = false
                    },
                    onDismiss = { showDeviceSelector = false }
                )
            }
        }
    }
}

@Composable
fun DeviceSelectorDialog(
    devices: List<UsbDevice>,
    onDeviceSelected: (UsbDevice) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(shape = RoundedCornerShape(12.dp)) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("USB Cihazı Seçin", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(bottom = 12.dp))
                if (devices.isEmpty()) {
                    Text("Uygun USB cihaz bulunamadı.")
                } else {
                    LazyColumn(modifier = Modifier.fillMaxWidth()) {
                        items(devices) { device ->
                            TextButton(
                                onClick = { onDeviceSelected(device) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(horizontalAlignment = Alignment.Start, modifier = Modifier.fillMaxWidth()) {
                                    Text(device.deviceName ?: "Bilinmeyen Cihaz", fontWeight = FontWeight.Bold)
                                    Text("VID: ${device.vendorId} - PID: ${device.productId}", fontSize = 12.sp)
                                }
                            }
                            if (devices.last() != device) {
                                Divider(Modifier.padding(vertical = 4.dp))
                            }
                        }
                    }
                }
                Spacer(Modifier.height(16.dp))
                Button(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                    Text("Kapat")
                }
            }
        }
    }
}

@Composable
fun DirectTelemetryCard(
    telemetry: DroneData,
    directConnectionService: DirectConnectionService?
) {
    val scope = rememberCoroutineScope()
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Direct Telemetri (MAVLink)", style = MaterialTheme.typography.titleMedium)
            Divider()
            TelemetryItem("Durum", if (telemetry.armed == true) "ARMED" else if (telemetry.armed == false) "DISARMED" else "-", "Mod", telemetry.mode ?: "-")
            TelemetryItem("Pozisyon", "Lat: ${telemetry.latitude?.format(6) ?: "-"}, Lon: ${telemetry.longitude?.format(6) ?: "-"}")
            TelemetryItem("Yükseklik", "MSL: ${telemetry.altitudeMsl?.format(2) ?: "-"} m", "Rel: ${telemetry.relativeAltitude?.format(2) ?: "-"} m")
            TelemetryItem("Hız", "Yer: ${telemetry.groundspeed?.format(2) ?: "-"} m/s", "Hava: ${telemetry.airSpeed?.format(2) ?: "-"} m/s")
            TelemetryItem("Açılar", "Roll: ${telemetry.roll?.format(1) ?: "-"}°", "Pitch: ${telemetry.pitch?.format(1) ?: "-"}°", "Yaw: ${telemetry.yaw?.format(1) ?: "-"}° (Hdg: ${telemetry.heading?.format(1) ?: "-"}°)")
            TelemetryItem("Batarya", "${telemetry.batteryVoltage?.format(2) ?: "-"} V, ${telemetry.batteryCurrent?.format(2) ?: "-"} A", "Kalan: ${telemetry.batteryRemaining?.format() ?: "-"}%")
            TelemetryItem("GPS", "Fix: ${telemetry.fixType?.toString() ?: "-"}", "Uydular: ${telemetry.satellitesVisible?.toString() ?: "-"}")
            TelemetryItem("Gaz", "${telemetry.throttle?.format() ?: "-"}%", "Tırmanma", "${telemetry.climbRate?.format(2) ?: "-"} m/s")

            if (directConnectionService != null) {
                Spacer(Modifier.height(10.dp))
                Text("Direct Komutlar", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = { directConnectionService.requestDataStreams() }, modifier = Modifier.weight(1f)) { Text("Veri Akışlarını İste") }
                }
            }
        }
    }
}

@Composable
fun ProxyTelemetryCard(
    telemetryDataProxy: String,
    proxyConnectionService: ProxyConnectionService?
) {
    Card(elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)) {
        Column(modifier = Modifier.padding(12.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Proxy Telemetri (MAVSDK)", style = MaterialTheme.typography.titleMedium)
            Divider()
            Text(telemetryDataProxy)
            
            proxyConnectionService?.drone?.let { droneSystem ->
                Spacer(Modifier.height(10.dp))
                Text("Proxy Komutları", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Button(onClick = {
                        try {
                            droneSystem.action.arm().subscribe()
                            Log.i(AppConfig.TAG, "Proxy ARM komutu gönderildi.")
                        } catch (e: Exception) {
                            Log.e(AppConfig.TAG, "Proxy ARM komutu gönderilemedi", e)
                        }
                    }, modifier = Modifier.weight(1f)) { Text("ARM (Proxy)") }

                    Button(onClick = {
                        try {
                            droneSystem.action.disarm().subscribe()
                            Log.i(AppConfig.TAG, "Proxy DISARM komutu gönderildi.")
                        } catch (e: Exception) {
                            Log.e(AppConfig.TAG, "Proxy DISARM komutu gönderilemedi", e)
                        }
                    }, modifier = Modifier.weight(1f)) { Text("DISARM (Proxy)") }
                }
            }
        }
    }
}

@Composable
fun TelemetryItem(label1: String, value1: String, label2: String? = null, value2: String? = null, label3: String? = null, value3: String? = null) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text("$label1: ", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.4f))
        Text(value1, modifier = Modifier.weight(0.6f))
    }
    if (label2 != null && value2 != null) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("  $label2: ", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.4f))
            Text(value2, modifier = Modifier.weight(0.6f))
        }
    }
    if (label3 != null && value3 != null) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("    $label3: ", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(0.4f))
            Text(value3, modifier = Modifier.weight(0.6f))
        }
    }
}

fun Double.format(digits: Int): String = "%.${digits}f".format(this)
fun Int.format(): String = this.toString() 