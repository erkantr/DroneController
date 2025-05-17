package com.bysoftware.dronecontroller.utils

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import com.bysoftware.dronecontroller.config.AppConfig

class UsbPermissionHelper(private val context: Context) {

    private val usbManager: UsbManager by lazy {
        context.getSystemService(Context.USB_SERVICE) as UsbManager
    }

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

    fun registerReceiver() {
        val filter = IntentFilter(AppConfig.ACTION_USB_PERMISSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(usbPermissionReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            ContextCompat.registerReceiver(
                context,
                usbPermissionReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
        }
    }

    fun unregisterReceiver() {
        try {
            context.unregisterReceiver(usbPermissionReceiver)
        } catch (e: IllegalArgumentException) {
            // Zaten kayıttan kaldırılmış olabilir
        }
    }

    @SuppressLint("MutableImplicitPendingIntent")
    fun requestPermission(device: UsbDevice, callback: (Boolean, UsbDevice?) -> Unit) {
        if (usbManager.hasPermission(device)) {
            Log.d(AppConfig.TAG, "USB izni zaten var: ${device.deviceName}")
            callback(true, device)
            return
        }
        Log.d(AppConfig.TAG, "USB izni isteniyor: ${device.deviceName}")
        usbPermissionCallback = callback
        val permissionIntent = PendingIntent.getBroadcast(
            context,
            AppConfig.USB_PERMISSION_REQUEST_CODE,
            Intent(AppConfig.ACTION_USB_PERMISSION),
            PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        usbManager.requestPermission(device, permissionIntent)
    }
} 