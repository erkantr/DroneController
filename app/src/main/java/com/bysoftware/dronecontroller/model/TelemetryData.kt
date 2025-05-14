package com.bysoftware.dronecontroller.model

// === MAVLink Kütüphanesi Importları ===
// Yaygın kullanılan MAVLink mesajları (common dialect)
// İhtiyaç duyulan enum ve flag importları (common dialect):

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