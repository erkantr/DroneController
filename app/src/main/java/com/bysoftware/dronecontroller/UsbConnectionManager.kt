package com.bysoftware.dronecontroller

import android.content.Context
import android.hardware.usb.UsbManager
import com.hoho.android.usbserial.driver.UsbSerialDriver
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.driver.UsbSerialPort
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class UsbConnectionManager(private val context: Context) {

    private val usbManager: UsbManager by lazy {
        context.getSystemService(Context.USB_SERVICE) as UsbManager
    }

    suspend fun findConnectedDevice(): UsbSerialPort? = withContext(Dispatchers.IO) {
        val prober = UsbSerialProber.getDefaultProber()
        val drivers = prober.findAllDrivers(usbManager)

        drivers.firstOrNull()?.let { driver ->
            val port = driver.ports.first()
            if (usbManager.hasPermission(driver.device)) {
                port.open(usbManager.openDevice(driver.device))
                port.setParameters(
                    57600, // Baud rate (ArduPilot default)
                    8,     // Data bits
                    UsbSerialPort.STOPBITS_1,
                    UsbSerialPort.PARITY_NONE
                )
                port
            } else {
                null
            }
        }
    }

    fun closePort(port: UsbSerialPort?) {
        try {
            port?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}