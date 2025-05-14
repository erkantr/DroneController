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
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.*
import io.dronefleet.mavlink.common.Attitude
import io.dronefleet.mavlink.common.GlobalPositionInt
import io.dronefleet.mavlink.common.SysStatus
import io.dronefleet.mavlink.common.VfrHud

data class DroneData(
    val latitude: Double? = null,
    val longitude: Double? = null,
    val altitude: Float? = null,
    val heading: Float? = null,
    val velocity: Float? = null,
    val batteryRemaining: Int? = null,
    val groundspeed: Float? = null,
    val roll: Float? = null,
    val pitch: Float? = null,
    val yaw: Float? = null
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapScreen(
    droneData: DroneData,
    onNavigateToSettings: () -> Unit
) {
    val defaultLocation = LatLng(39.9334, 32.8597) // Ankara (varsayılan)
    val dronePosition = droneData.latitude?.let { lat ->
        droneData.longitude?.let { lon ->
            LatLng(lat, lon)
        }
    }
    
    val cameraPositionState = rememberCameraPositionState {
        position = CameraPosition.fromLatLngZoom(
            dronePosition ?: defaultLocation, 15f
        )
    }

    // Update camera position when drone position changes
    LaunchedEffect(dronePosition) {
        dronePosition?.let {
            cameraPositionState.position = CameraPosition.fromLatLngZoom(it, 15f)
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
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Google Maps
            GoogleMap(
                modifier = Modifier.fillMaxSize(),
                cameraPositionState = cameraPositionState,
                properties = MapProperties(
                    isMyLocationEnabled = true,
                    mapType = MapType.HYBRID
                )
            ) {
                // Drone marker
                dronePosition?.let { position ->
                    Marker(
                        state = MarkerState(position = position),
                        title = "Drone",
                        snippet = "Lat: ${position.latitude}, Lng: ${position.longitude}"
                    )
                }
            }
            
            // Telemetri Paneli (QGC benzeri) - Box içinde Alignment.BottomCenter ile konumlandırılır
            TelemetryPanel(
                droneData = droneData,
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }
    }
}

@Composable
fun TelemetryPanel(
    droneData: DroneData,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp)
            .padding(bottom = 32.dp)
            .background(
                color = Color(0x80000000),
                shape = RoundedCornerShape(16.dp)
            )
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TelemetryItem(
                title = "HIZ",
                value = droneData.groundspeed?.toString() ?: "-- ",
                unit = "m/s"
            )
            
            TelemetryItem(
                title = "YÜKSEKLİK",
                value = droneData.altitude?.toString() ?: "-- ",
                unit = "m"
            )
            
            TelemetryItem(
                title = "BATARYA",
                value = droneData.batteryRemaining?.toString() ?: "-- ",
                unit = "%"
            )
        }
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            TelemetryItem(
                title = "ROLL",
                value = droneData.roll?.toString() ?: "-- ",
                unit = "°"
            )
            
            TelemetryItem(
                title = "PITCH",
                value = droneData.pitch?.toString() ?: "-- ",
                unit = "°"
            )
            
            TelemetryItem(
                title = "YAW",
                value = droneData.yaw?.toString() ?: "-- ",
                unit = "°"
            )
        }
    }
}

@Composable
fun TelemetryItem(title: String, value: String, unit: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = title,
            fontSize = 12.sp,
            color = Color.LightGray
        )
        
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                fontSize = 20.sp,
                fontWeight = FontWeight.Bold,
                color = Color.White
            )
            
            Text(
                text = unit,
                fontSize = 12.sp,
                color = Color.LightGray,
                modifier = Modifier.padding(start = 2.dp, bottom = 2.dp)
            )
        }
    }
} 