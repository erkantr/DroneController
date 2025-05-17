package com.bysoftware.dronecontroller.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*

data class DroneData(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Double? = null,
    val heading: Double? = null,
    val groundspeed: Double? = null,
    val roll: Double? = null,
    val pitch: Double? = null,
    val yaw: Double? = null,
    val batteryRemaining: Int? = null,
    val armed: Boolean? = null,
    val mode: String? = null,
    val altitudeMsl: Double? = null,
    val relativeAltitude: Double? = null,
    val airSpeed: Double? = null,
    val batteryVoltage: Double? = null,
    val batteryCurrent: Double? = null,
    val fixType: Int? = null,
    val satellitesVisible: Int? = null,
    val throttle: Int? = null,
    val climbRate: Double? = null,
    val telemetryTimestamp: Long = 0L,
    val isProxyData: Boolean = false
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    droneData: DroneData,
    onNavigateToSettings: () -> Unit,
    snackbarHostState: SnackbarHostState
) {
    val droneLocation = LatLng(droneData.latitude ?: 28.00095, droneData.longitude ?: -82.000) // Örnek Orlando, FL
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(droneLocation, 15f) // Başlangıç zoom seviyesi
    }

    // Drone lokasyonu değiştikçe kamerayı güncelle
    LaunchedEffect(droneData.latitude, droneData.longitude) {
        if (droneData.latitude != null && droneData.longitude != null) {
            cameraPositionState.animate(
                update = CameraUpdateFactory.newLatLngZoom(droneLocation, cameraPositionState.position.zoom),
                durationMs = 1000
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Drone Harita Görünümü") },
                navigationIcon = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Geri")
                    }
                },
                actions = {
                    IconButton(onClick = onNavigateToSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Ayarlar")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) }
    ) { paddingValues ->
        Box(modifier = Modifier.padding(paddingValues).fillMaxSize()) {
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                properties = MapProperties(mapType = MapType.HYBRID),
                uiSettings = MapUiSettings(zoomControlsEnabled = true, mapToolbarEnabled = true)
            ) {
                if (droneData.latitude != null && droneData.longitude != null) {
                    Marker(
                        state = MarkerState(position = droneLocation),
                        title = "Drone",
                        snippet = "Lat: ${droneData.latitude.format(6)}, Lon: ${droneData.longitude.format(6)}"
                    )
                }
            }

            // Telemetri Paneli (Haritanın üzerinde)
            TelemetryOverlayPanel(droneData = droneData, modifier = Modifier.align(Alignment.BottomCenter))
        }
    }
}

@Composable
fun TelemetryOverlayPanel(droneData: DroneData, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(8.dp),
        shape = RoundedCornerShape(12.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)) // Hafif transparan
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                CompactTelemetryItem(label = "HIZ", value = droneData.groundspeed?.format(1) ?: "--", unit = "m/s")
                CompactTelemetryItem(label = "YÜKSEKLİK", value = droneData.altitude?.format(1) ?: "--", unit = "m")
                CompactTelemetryItem(label = "BATARYA", value = droneData.batteryRemaining?.format() ?: "--", unit = "%")
            }
            Divider(modifier = Modifier.padding(vertical = 4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceAround) {
                CompactTelemetryItem(label = "ROLL", value = droneData.roll?.format(1) ?: "--", unit = "°")
                CompactTelemetryItem(label = "PITCH", value = droneData.pitch?.format(1) ?: "--", unit = "°")
                CompactTelemetryItem(label = "YAW/HDG", value = "${droneData.yaw?.format(1) ?: "--"}° (${droneData.heading?.format(0) ?: "--"}°)", unit = "")
            }
        }
    }
}

@Composable
fun CompactTelemetryItem(label: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (unit.isNotBlank()) {
                Text(
                    text = unit,
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Light,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 2.dp, bottom = 2.dp)
                )
            }
        }
    }
}

// MapScreen.kt içinde veya utils içinde zaten varsa bu format extension'larına gerek yok.
// Eğer yoksa, DroneControllerMainScreen.kt'den kopyalanabilir veya ortak bir dosyaya taşınabilir.
// fun Double.format(digits: Int): String = "%.${digits}f".format(this)
// fun Int.format(): String = this.toString() 