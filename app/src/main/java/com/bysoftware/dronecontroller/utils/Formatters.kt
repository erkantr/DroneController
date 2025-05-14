package com.bysoftware.dronecontroller.utils

import java.text.DecimalFormat

// Sayı formatları
val decimalFormat = DecimalFormat("#.##")
val decimalFormatShort = DecimalFormat("#.##") // Hız gibi kısa gösterimler için

// Genişletme fonksiyonları (Extension Functions)
fun Double?.format(digits: Int = 2): String {
    if (this == null) return "N/A"
    val df = if (digits == 1) decimalFormatShort else decimalFormat
    df.maximumFractionDigits = digits
    df.minimumFractionDigits = digits
    return df.format(this)
}

fun Float?.format(digits: Int = 2): String {
    if (this == null) return "N/A"
    val df = if (digits == 1) decimalFormatShort else decimalFormat
    df.maximumFractionDigits = digits
    df.minimumFractionDigits = digits
    return df.format(this)
}

fun Int?.format(): String {
    if (this == null) return "N/A"
    return this.toString()
} 