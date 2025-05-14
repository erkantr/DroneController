package com.bysoftware.dronecontroller.config

object AppConfig {
    const val TAG = "DroneControllerApp"
    const val MAVSDK_SERVER_UDP_PORT = 14540 // MAVSDK Sunucusunun dinleyeceği UDP portu
    const val DEFAULT_BAUD_RATE = 115200 // Varsayılan USB seri port baud hızı
    const val ACTION_USB_PERMISSION = "com.bysoftware.dronecontroller.USB_PERMISSION"
    const val USB_PERMISSION_REQUEST_CODE = 0 // USB izni için istek kodu

    // MAVLink Mesaj IDleri (Örnek, ihtiyaç duyuldukça eklenebilir)
    const val MAVLINK_MSG_ID_HEARTBEAT = 0
    const val MAVLINK_MSG_ID_SYS_STATUS = 1
    const val MAVLINK_MSG_ID_GPS_RAW_INT = 24
    const val MAVLINK_MSG_ID_ATTITUDE = 30
    const val MAVLINK_MSG_ID_VFR_HUD = 74
    const val MAVLINK_MSG_ID_COMMAND_ACK = 77
    const val MAVLINK_MSG_ID_STATUSTEXT = 253

    // Diğer sabitler
    const val COMMAND_TIMEOUT_MS = 3000L // Komut yanıtı için zaman aşımı (ms)
} 