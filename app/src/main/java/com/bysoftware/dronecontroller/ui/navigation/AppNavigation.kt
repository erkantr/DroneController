package com.bysoftware.dronecontroller.ui.navigation

import android.content.Context
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bysoftware.dronecontroller.MainActivity
import com.bysoftware.dronecontroller.config.AppConfig
import com.bysoftware.dronecontroller.service.DirectConnectionService
import com.bysoftware.dronecontroller.service.ProxyConnectionService
import com.bysoftware.dronecontroller.ui.screens.DroneControllerMainScreen
import com.bysoftware.dronecontroller.ui.screens.DroneData
import com.bysoftware.dronecontroller.ui.screens.MapScreen
import com.hoho.android.usbserial.driver.*
import kotlinx.coroutines.launch

enum class AppScreen {
    Main,
    Map
}

class AppNavigationActions(private val navController: NavHostController) {
    val navigateToMain: () -> Unit = {
        navController.navigate(AppScreen.Main.name) {
            popUpTo(navController.graph.startDestinationId) {}
            launchSingleTop = true
        }
    }
    
    val navigateToMap: () -> Unit = {
        navController.navigate(AppScreen.Map.name) {
            popUpTo(navController.graph.startDestinationId) {}
            launchSingleTop = true
        }
    }
}

@Composable
fun AppNavigation(
    navController: NavHostController = rememberNavController(),
    mainActivity: MainActivity,
    requestUsbPermission: (UsbDevice, (Boolean, UsbDevice?) -> Unit) -> Unit,
    checkUsbPermission: (UsbDevice) -> Boolean,
    getUsbDevices: () -> List<UsbDevice>,
    openUsbDeviceConnection: (UsbDevice) -> UsbDeviceConnection?,
    directConnectionService: DirectConnectionService,
    proxyConnectionService: ProxyConnectionService,
    droneData: DroneData,
    requestInternetPermission: () -> Unit,
    isDirectConnected: Boolean,
    connectionStatusDirect: String,
    isProxyConnected: Boolean,
    connectionStatusProxy: String,
    telemetryDataProxy: String
) {
    val actions = remember(navController) { AppNavigationActions(navController) }
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val showError: (String, String) -> Unit = { message, type ->
        scope.launch {
            snackbarHostState.showSnackbar("Hata ($type): $message")
        }
        Log.e(AppConfig.TAG, "AppNavigation Hata ($type): $message")
    }

    fun initiateConnectionAndConnect(device: UsbDevice, type: String) {
        val connection = openUsbDeviceConnection(device)
        if (connection == null) {
            Log.e(AppConfig.TAG, "USB device connection failed for $type device: ${device.deviceName}")
            showError("USB cihaz bağlantısı (connection) kurulamadı.", type)
            return
        }

        val knownDrivers = listOf(
            CdcAcmSerialDriver(device),
            Cp21xxSerialDriver(device),
            FtdiSerialDriver(device),
            ProlificSerialDriver(device),
            Ch34xSerialDriver(device)
        )
        val driver = knownDrivers.find { it.ports.isNotEmpty() }

        if (driver == null || driver.ports.isEmpty()) {
            Log.e(AppConfig.TAG, "No suitable driver/port for USB device ${device.deviceName} (Manually Listed) for $type")
            showError("Bu USB cihazı için uygun sürücü/port bulunamadı.", type)
            connection.close()
            return
        }
        val port = driver.ports[0]
        Log.i(AppConfig.TAG, "Using driver: ${driver.javaClass.simpleName} and port: ${port.portNumber} for ${device.deviceName}")

        if (type == "Proxy") {
            directConnectionService.disconnectDirectUsb(showStatusUpdate = false)
            proxyConnectionService.connectProxy(port, connection)
        } else if (type == "Direct") {
            proxyConnectionService.disconnectProxy(showStatusUpdate = false)
            directConnectionService.connectDirectUsb(port, connection)
        }
    }

    NavHost(navController, startDestination = AppScreen.Map.name) {
        composable(AppScreen.Main.name) {
            DroneControllerMainScreen(
                droneData = droneData,
                connectProxy = { device -> 
                    if (!checkUsbPermission(device)) {
                        requestUsbPermission(device) { granted, permittedDevice ->
                            if (granted && permittedDevice != null) {
                                initiateConnectionAndConnect(permittedDevice, "Proxy")
                            } else {
                                showError("Proxy için USB izni reddedildi.", "Proxy")
                            }
                        }
                    } else {
                        initiateConnectionAndConnect(device, "Proxy")
                    }
                },
                connectDirectUsb = { device -> 
                    if (!checkUsbPermission(device)) {
                        requestUsbPermission(device) { granted, permittedDevice ->
                            if (granted && permittedDevice != null) {
                                initiateConnectionAndConnect(permittedDevice, "Direct")
                            } else {
                                showError("Direct için USB izni reddedildi.", "Direct")
                            }
                        }
                    } else {
                        initiateConnectionAndConnect(device, "Direct")
                    }
                },
                disconnectProxy = { proxyConnectionService.disconnectProxy() },
                disconnectDirectUsb = { directConnectionService.disconnectDirectUsb(true) },
                requestUsbPermission = requestUsbPermission,
                checkUsbPermission = checkUsbPermission,
                requestInternetPermission = requestInternetPermission,
                getUsbDevices = getUsbDevices,
                onNavigateToMap = actions.navigateToMap,
                isDirectConnected = isDirectConnected,
                connectionStatusDirect = connectionStatusDirect,
                isProxyConnected = isProxyConnected,
                connectionStatusProxy = connectionStatusProxy,
                telemetryDataProxy = telemetryDataProxy,
                snackbarHostState = snackbarHostState
            )
        }
        
        composable(AppScreen.Map.name) {
            MapScreen(
                droneData = droneData,
                onNavigateToSettings = actions.navigateToMain,
                snackbarHostState = snackbarHostState
            )
        }
    }
} 