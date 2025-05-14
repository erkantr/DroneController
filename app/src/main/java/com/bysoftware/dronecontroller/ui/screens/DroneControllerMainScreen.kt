package com.bysoftware.dronecontroller.ui.screens

import android.hardware.usb.UsbDevice
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DroneControllerMainScreen(
    droneData: DroneData,
    connectProxy: (device: Any) -> Unit,
    connectDirectUsb: (device: Any) -> Unit,
    disconnectProxy: () -> Unit,
    disconnectDirectUsb: () -> Unit,
    requestUsbPermission: (device: Any, onResult: (Boolean, Any?) -> Unit) -> Unit,
    checkUsbPermission: (Any) -> Boolean,
    requestInternetPermission: () -> Unit,
    getUsbDevices: () -> List<Any>,
    onNavigateToMap: () -> Unit
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    // --- State Variables ---
    var connectionStatusProxy by remember { mutableStateOf("Bağlantı yok (Proxy)") }
    var telemetryDataProxy by remember { mutableStateOf("Veri bekleniyor (Proxy)...") }
    var isProxyConnected by remember { mutableStateOf(false) }
    var connectionStatusDirect by remember { mutableStateOf("Bağlantı yok (Direct)") }
    var isDirectConnected by remember { mutableStateOf(false) }
    var selectedUsbDevice by remember { mutableStateOf<Any?>(null) }
    var usbDevices by remember { mutableStateOf<List<Any>>(emptyList()) }
    var showDeviceSelector by remember { mutableStateOf(false) }
    var isConnecting by remember { mutableStateOf<String?>(null) } // "Proxy" or "Direct" or null

    // Refresh USB device list
    LaunchedEffect(Unit) {
        usbDevices = getUsbDevices()
        requestInternetPermission()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Drone Controller") },
                actions = {
                    IconButton(onClick = onNavigateToMap) {
                        Icon(
                            imageVector = Icons.Default.Star,
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
                .verticalScroll(rememberScrollState())
        ) {
            // Heading - Connection Status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "Bağlantı Durumu",
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isDirectConnected) connectionStatusDirect else if (isProxyConnected) connectionStatusProxy else "Bağlantı yok",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.secondary
                    )
                }
                
                Row {
                    Button(
                        onClick = { showDeviceSelector = true },
                        enabled = !isProxyConnected && !isDirectConnected && isConnecting == null
                    ) {
                        Text("Bağlan")
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    Button(
                        onClick = {
                            if (isProxyConnected) disconnectProxy()
                            if (isDirectConnected) disconnectDirectUsb()
                        },
                        enabled = (isProxyConnected || isDirectConnected) && isConnecting == null
                    ) {
                        Text("Bağlantıyı Kes")
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            
            // Direct Connection Telemetry
            if (isDirectConnected) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Telemetri Verileri",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        // Attitude Data Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TelemetryDataCard(
                                title = "Roll",
                                value = droneData.roll?.toString() ?: "N/A",
                                unit = "°"
                            )
                            TelemetryDataCard(
                                title = "Pitch",
                                value = droneData.pitch?.toString() ?: "N/A",
                                unit = "°"
                            )
                            TelemetryDataCard(
                                title = "Yaw",
                                value = droneData.yaw?.toString() ?: "N/A",
                                unit = "°"
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // Position & Status Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TelemetryDataCard(
                                title = "Hız",
                                value = droneData.groundspeed?.toString() ?: "N/A",
                                unit = "m/s"
                            )
                            TelemetryDataCard(
                                title = "Yükseklik",
                                value = droneData.altitude?.toString() ?: "N/A",
                                unit = "m"
                            )
                            TelemetryDataCard(
                                title = "Batarya",
                                value = droneData.batteryRemaining?.toString() ?: "N/A",
                                unit = "%"
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(16.dp))
                        
                        // GPS Position Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            TelemetryDataCard(
                                title = "Enlem",
                                value = droneData.latitude?.toString() ?: "N/A",
                                unit = "°"
                            )
                            TelemetryDataCard(
                                title = "Boylam",
                                value = droneData.longitude?.toString() ?: "N/A",
                                unit = "°"
                            )
                            TelemetryDataCard(
                                title = "Heading",
                                value = droneData.heading?.toString() ?: "N/A",
                                unit = "°"
                            )
                        }
                    }
                }
            } else if (isProxyConnected) {
                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp)
                    ) {
                        Text(
                            text = "Proxy Telemetri Verileri",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = telemetryDataProxy,
                            fontSize = 14.sp,
                            lineHeight = 1.5.em
                        )
                    }
                }
            }
            
            // Command Panel Section
            if (isDirectConnected || isProxyConnected) {
                Spacer(modifier = Modifier.height(24.dp))
                CommandPanel(
                    isDirectConnection = isDirectConnected,
                    onSendCommand = { /* Command logic */ }
                )
            }
        }
    }
    
    // Device Selection Dialog
    if (showDeviceSelector) {
        DeviceSelectionDialog(
            devices = usbDevices.filterIsInstance<UsbDevice>(),
            onDeviceSelected = { device, connectionType ->
                selectedUsbDevice = device
                showDeviceSelector = false
                
                if (connectionType == "direct") {
                    connectDirectUsb(device)
                } else if (connectionType == "proxy") {
                    connectProxy(device)
                }
            },
            onDismiss = {
                showDeviceSelector = false
            }
        )
    }
}

@Composable
fun TelemetryDataCard(
    title: String,
    value: String,
    unit: String
) {
    Card(
        modifier = Modifier
            .width(100.dp)
            .height(80.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.secondary
            )
            
            Spacer(modifier = Modifier.height(4.dp))
            
            Text(
                text = value,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
            
            Text(
                text = unit,
                fontSize = 10.sp,
                color = MaterialTheme.colorScheme.secondary
            )
        }
    }
}

@Composable
fun CommandPanel(
    isDirectConnection: Boolean,
    onSendCommand: (String) -> Unit
) {
    Column {
        Text(
            text = "Komut Paneli",
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            Button(onClick = { onSendCommand("ARM") }) {
                Text("ARM")
            }
            
            Button(onClick = { onSendCommand("DISARM") }) {
                Text("DISARM")
            }
            
            Button(onClick = { onSendCommand("TAKEOFF") }) {
                Text("KALKIŞ")
            }
            
            Button(onClick = { onSendCommand("LAND") }) {
                Text("İNİŞ")
            }
        }
    }
}

@Composable
fun DeviceSelectionDialog(
    devices: List<UsbDevice>,
    onDeviceSelected: (UsbDevice, String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("USB Cihaz Seçin") },
        text = {
            Column {
                Text("Bağlanmak için bir USB cihazı ve bağlantı tipi seçin:")
                Spacer(modifier = Modifier.height(8.dp))
                
                if (devices.isEmpty()) {
                    Text(
                        "Hiçbir USB cihazı bulunamadı. Cihazınızı bağlayın ve yeniden deneyin.",
                        color = MaterialTheme.colorScheme.error
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 200.dp)
                    ) {
                        items(devices) { device ->
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 8.dp)
                            ) {
                                Text(
                                    text = "${device.manufacturerName ?: "Bilinmeyen"} ${device.productName ?: "Cihaz"} (${device.deviceId})",
                                    fontWeight = FontWeight.Bold
                                )
                                
                                Spacer(modifier = Modifier.height(4.dp))
                                
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceEvenly
                                ) {
                                    Button(onClick = { onDeviceSelected(device, "direct") }) {
                                        Text("Direct Bağlan")
                                    }
                                    
                                    Button(onClick = { onDeviceSelected(device, "proxy") }) {
                                        Text("Proxy Bağlan")
                                    }
                                }
                                
                                Divider(modifier = Modifier.padding(top = 8.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("İptal")
            }
        }
    )
} 