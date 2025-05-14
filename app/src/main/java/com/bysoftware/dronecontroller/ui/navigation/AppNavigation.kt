package com.bysoftware.dronecontroller.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.bysoftware.dronecontroller.ui.screens.DroneControllerMainScreen
import com.bysoftware.dronecontroller.ui.screens.DroneData
import com.bysoftware.dronecontroller.ui.screens.MapScreen

enum class AppScreen {
    Main,
    Map
}

class AppNavigationActions(private val navController: NavHostController) {
    val navigateToMain: () -> Unit = {
        navController.navigate(AppScreen.Main.name) {
            popUpTo(AppScreen.Main.name) { inclusive = true }
        }
    }
    
    val navigateToMap: () -> Unit = {
        navController.navigate(AppScreen.Map.name) {
            launchSingleTop = true
        }
    }
}

@Composable
fun AppNavigation(
    droneData: DroneData,
    connectProxy: (device: Any) -> Unit,
    connectDirectUsb: (device: Any) -> Unit,
    disconnectProxy: () -> Unit,
    disconnectDirectUsb: () -> Unit,
    requestUsbPermission: (device: Any, onResult: (Boolean, Any?) -> Unit) -> Unit,
    checkUsbPermission: (Any) -> Boolean,
    requestInternetPermission: () -> Unit,
    getUsbDevices: () -> List<Any>,
    navController: NavHostController = rememberNavController()
) {
    // AppNavigationActions instance rememberNavController'dan sonra oluşturulmalı
    val navigationActions = remember(navController) {
        AppNavigationActions(navController)
    }
    
    NavHost(
        navController = navController,
        startDestination = AppScreen.Main.name
    ) {
        composable(AppScreen.Main.name) {
            DroneControllerMainScreen(
                droneData = droneData,
                connectProxy = connectProxy,
                connectDirectUsb = connectDirectUsb,
                disconnectProxy = disconnectProxy,
                disconnectDirectUsb = disconnectDirectUsb,
                requestUsbPermission = requestUsbPermission,
                checkUsbPermission = checkUsbPermission,
                requestInternetPermission = requestInternetPermission,
                getUsbDevices = getUsbDevices,
                onNavigateToMap = navigationActions.navigateToMap
            )
        }
        
        composable(AppScreen.Map.name) {
            MapScreen(
                droneData = droneData,
                onNavigateToSettings = navigationActions.navigateToMain
            )
        }
    }
} 