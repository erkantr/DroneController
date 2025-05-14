package com.bysoftware.dronecontroller.service

import android.content.Context
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbManager
import android.util.Log
import com.bysoftware.dronecontroller.config.AppConfig
import com.bysoftware.dronecontroller.model.DirectTelemetryState
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.util.SerialInputOutputManager
import io.dronefleet.mavlink.MavlinkConnection
import io.dronefleet.mavlink.MavlinkDialect
import io.dronefleet.mavlink.MavlinkMessage
import io.dronefleet.mavlink.ardupilotmega.ArdupilotmegaDialect
import io.dronefleet.mavlink.common.* // Attitude, BatteryStatus, CommandLong etc.
import io.dronefleet.mavlink.minimal.* // Heartbeat, MavAutopilot, MavModeFlag etc.
import io.dronefleet.mavlink.standard.StandardDialect
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedSendChannelException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream

class DirectConnectionService(
    private val context: Context,
    private val scope: CoroutineScope,
    private val showErrorCallback: (message: String, type: String) -> Unit,
    private val onConnectionStateChange: (Boolean) -> Unit // isConnecting için
) {
    private val _directTelemetryState = MutableStateFlow(DirectTelemetryState())
    val directTelemetryState: StateFlow<DirectTelemetryState> = _directTelemetryState.asStateFlow()

    private val _isDirectConnected = MutableStateFlow(false)
    val isDirectConnected: StateFlow<Boolean> = _isDirectConnected.asStateFlow()

    private val _connectionStatusDirect = MutableStateFlow("Bağlı Değil (Direkt)")
    val connectionStatusDirect: StateFlow<String> = _connectionStatusDirect.asStateFlow()

    private var directUsbPort: UsbSerialPort? = null
    private var directUsbConnection: UsbDeviceConnection? = null
    private var directIoManager: SerialInputOutputManager? = null
    private var directParserJob: Job? = null
    private val directDataChannel: Channel<ByteArray> = Channel(Channel.BUFFERED)
    private var directCommandOutputStream: OutputStream? = null
    private var mavlinkConnection: MavlinkConnection? = null // For sending commands
    private var pipedInputStreamForParser: PipedInputStream? = null
    private var pipeWriterJob: Job? = null

    // MAVLink parser için gerekli importlar buraya eklenecek
    // Örnek: import com.MAVLink.MAVLinkPacket
    // import com.MAVLink.Parser
    // import com.MAVLink.common.*

    companion object {
        private const val TAG = AppConfig.TAG + "_DirectSvc"
    }

    // SerialInputOutputManager için Listener
    private inner class DirectUsbSerialListener : SerialInputOutputManager.Listener {
        override fun onNewData(data: ByteArray) {
            scope.launch(Dispatchers.IO) {
                try {
                    if (!directDataChannel.isClosedForSend) {
                        directDataChannel.send(data)
                    }
                } catch (e: ClosedSendChannelException) {
                    Log.w(TAG, "[Listener] Kanal kapalıyken veri gönderme denemesi.")
                } catch (e: Exception) {
                    Log.e(TAG, "[Listener] Veri gönderirken hata", e)
                }
            }
        }

        override fun onRunError(e: Exception) {
            Log.e(TAG, "[Listener] IO Manager Run Error: ${e.message}", e)
            scope.launch(Dispatchers.Main) {
                showErrorCallback("Direkt Seri port hatası: ${e.message?.take(100)}", "Direct")
                disconnectDirectUsb(showStatusUpdate = false) // Hata zaten gösterildi.
            }
        }
    }

    fun connectDirectUsb(port: UsbSerialPort, connection: UsbDeviceConnection) {
        if (_isDirectConnected.value) {
            showErrorCallback("Zaten direkt bir bağlantı aktif.", "Direct")
            return
        }
        onConnectionStateChange(true) // isConnecting = true
        _connectionStatusDirect.value = "Bağlanılıyor (Direkt)..."
        directUsbPort = port
        directUsbConnection = connection

        scope.launch(Dispatchers.IO) {
            try {
                directUsbPort!!.open(directUsbConnection)
                directUsbPort!!.setParameters(AppConfig.DEFAULT_BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                _connectionStatusDirect.value = "Port açıldı, IO Yöneticisi başlatılıyor..."

                directIoManager = SerialInputOutputManager(directUsbPort, DirectUsbSerialListener())
                directIoManager!!.start() // Arka planda çalışır.
                directCommandOutputStream = directUsbPort!!.outputStream // Komut gönderme için

                val pipedOutputStreamFromSerial = PipedOutputStream()
                pipedInputStreamForParser = PipedInputStream(pipedOutputStreamFromSerial)

                pipeWriterJob = scope.launch(Dispatchers.IO + CoroutineName("direct-pipe-writer")) {
                    Log.d(TAG, "[Pipe Writer] Başlatıldı.")
                    try {
                        for (data in directDataChannel) {
                            try {
                                pipedOutputStreamFromSerial.write(data)
                            } catch (writeError: IOException) {
                                if (isActive) Log.e(TAG, "[Pipe Writer] Pipe'a yazma hatası: ${writeError.message}", writeError)
                                break
                            }
                        }
                    } catch (e: Exception) {
                        if (isActive) Log.e(TAG, "[Pipe Writer] Beklenmedik hata: ${e.message}", e)
                    } finally {
                        try {
                            pipedOutputStreamFromSerial.close()
                        } catch (e: IOException) { Log.e(TAG, "[Pipe Writer] PipedOutputStream kapatılırken hata", e) }
                        Log.d(TAG, "[Pipe Writer] Tamamlandı.")
                    }
                }
                
                mavlinkConnection = MavlinkConnection.builder(pipedInputStreamForParser, directCommandOutputStream)
                    .dialect(MavAutopilot.MAV_AUTOPILOT_GENERIC, StandardDialect())
                    .dialect(MavAutopilot.MAV_AUTOPILOT_ARDUPILOTMEGA, ArdupilotmegaDialect())
                    .build()

                _connectionStatusDirect.value = "MAVLink ayrıştırıcısı başlatılıyor..."
                directParserJob = startDirectMavlinkParserInternal(mavlinkConnection!!)

                _isDirectConnected.value = true
                _connectionStatusDirect.value = "Bağlı (Direkt)"
                Log.i(TAG, "Direkt USB bağlantısı başarılı.")

            } catch (e: IOException) {
                Log.e(TAG, "Direkt USB bağlantı hatası", e)
                showErrorCallback("Direkt USB bağlantı hatası: ${e.message}", "Direct")
                cleanUpDirectResources()
                _isDirectConnected.value = false
                _connectionStatusDirect.value = "Bağlantı Hatası (Direkt)"
            } finally {
                 onConnectionStateChange(false) // isConnecting = false
            }
        }
    }

    private fun startDirectMavlinkParserInternal(connection: MavlinkConnection): Job {
        return scope.launch(Dispatchers.IO + CoroutineName("DirectMavlinkParser")) {
            Log.d(TAG, "[Parser] MAVLink Parser Coroutine başlatıldı.")
            try {
                while (isActive) {
                    val message = connection.next()
                    if (message != null) {
                        processMavlinkMessage(message)
                    } else {
                        Log.d(TAG, "[Parser] connection.next() null döndü, stream sonlandı.")
                        break
                    }
                }
            } catch (e: IOException) {
                if (isActive) {
                    Log.e(TAG, "[Parser] IO hatası: ${e.message}", e)
                    withContext(Dispatchers.Main + NonCancellable) {
                        showErrorCallback("Stream okuma hatası: ${e.message?.take(100)}", "Direct")
                        disconnectDirectUsb(showStatusUpdate = false)
                    }
                }
            } catch (e: CancellationException) {
                Log.i(TAG, "[Parser] MAVLink Parser Coroutine iptal edildi.")
            } catch (e: Exception) {
                if (isActive) {
                    Log.e(TAG, "[Parser] MAVLink ayrıştırma hatası", e)
                    withContext(Dispatchers.Main + NonCancellable) {
                        showErrorCallback("Parser hatası: ${e.message?.take(100)}", "Direct")
                        disconnectDirectUsb(showStatusUpdate = false)
                    }
                }
            } finally {
                Log.d(TAG, "[Parser] MAVLink Parser Coroutine tamamlandı.")
                try {
                    pipedInputStreamForParser?.close()
                } catch (e: IOException) { Log.e(TAG, "[Parser] PipedInputStream kapatılırken hata", e)}
            }
        }
    }
    
    private fun processMavlinkMessage(message: MavlinkMessage<*>) {
        val payload = message.payload
        val timestamp = System.currentTimeMillis()

        scope.launch(Dispatchers.Main) {
            var newState = _directTelemetryState.value.copy(
                systemId = message.originSystemId,
                componentId = message.originComponentId,
                lastMessageTimestamp = timestamp
            )
            newState = when (val p = payload) {
                is Heartbeat -> {
                    val currentMode = try {
                        MavMode.valueOf(p.customMode().toString())?.name ?: "CUSTOM(${p.customMode()})"
                    } catch (e: Exception) {
                        "CUSTOM(${p.customMode()})"
                    }
                    newState.copy(
                        armed = p.baseMode().flagsEnabled(MavModeFlag.MAV_MODE_FLAG_SAFETY_ARMED),
                        mode = currentMode,
                        autopilotType = p.autopilot().value(),
                        systemStatus = p.systemStatus().value(),
                        mavlinkVersion = p.mavlinkVersion().toInt()
                    )
                }
                is GpsRawInt -> newState.copy(
                    fixType = p.fixType().value(),
                    satellitesVisible = p.satellitesVisible().toInt(),
                    latitude = p.lat() / 1E7,
                    longitude = p.lon() / 1E7,
                    heading = if (p.cog() == 65535) null else p.cog() / 100.0
                )
                is GlobalPositionInt -> newState.copy(
                    latitude = p.lat() / 1E7,
                    longitude = p.lon() / 1E7,
                    altitudeMsl = p.alt() / 1000.0,
                    relativeAltitude = p.relativeAlt() / 1000.0,
                    heading = if (p.hdg() == 65535) null else p.hdg() / 100.0
                )
                is VfrHud -> newState.copy(
                    airSpeed = p.airspeed().toDouble(),
                    groundSpeed = p.groundspeed().toDouble(),
                    throttle = p.throttle().toInt(),
                    climbRate = p.climb().toDouble()
                )
                is Attitude -> newState.copy(
                    roll = Math.toDegrees(p.roll().toDouble()),
                    pitch = Math.toDegrees(p.pitch().toDouble()),
                    yaw = Math.toDegrees(p.yaw().toDouble())
                )
                is SysStatus -> newState.copy(
                    batteryVoltage = p.voltageBattery() / 1000.0,
                    batteryCurrent = p.currentBattery().let { if (it < 0) null else it / 100.0 },
                    batteryRemaining = p.batteryRemaining().let { if (it < 0) null else it.toInt() }
                )
                is BatteryStatus -> {
                    if (p.id().toInt() == 0) {
                        newState.copy(
                            batteryCurrent = p.currentBattery().let { if (it < 0) null else it / 100.0 },
                            batteryRemaining = p.batteryRemaining().let { if (it < 0) null else it.toInt() }
                        )
                    } else newState
                }
                is Statustext -> {
                    val severity = p.severity().value()
                    val textMessage = try {
                        String(p.text().takeWhile { it.code.toByte() != 0.toByte() }.toByteArray())
                    } catch (e: Exception) { "Decode Error" }
                    newState.copy(statusText = "[$severity] $textMessage")
                }
                else -> newState
            }
            _directTelemetryState.value = newState
        }
    }

    fun sendMavlinkCommand(payload: Any) {
        if (!isDirectConnected.value || mavlinkConnection == null) {
            Log.w(TAG, "[Direct Send] Bağlı değilken veya mavlinkConnection null iken komut gönderme denemesi")
            scope.launch { showErrorCallback("Komut göndermek için Direct bağlantı kurulu ve aktif olmalı.", "Direct") }
            return
        }
        scope.launch(Dispatchers.IO) {
            try {
                Log.d(TAG, "[Direct Send] Komut gönderiliyor: ${payload::class.java.simpleName}")
                when (payload) {
                    is CommandLong -> mavlinkConnection!!.send1(255, 0, payload)
                    is Heartbeat -> mavlinkConnection!!.send1(255, 0, payload)
                    is ParamSet -> mavlinkConnection!!.send1(255,0, payload)
                    // Diğer MAVLink mesaj türleri için case'ler eklenebilir
                    else -> {
                        Log.e(TAG, "[Direct Send] Desteklenmeyen payload tipi: ${payload::class.java.simpleName}")
                        showErrorCallback("Desteklenmeyen MAVLink komut tipi", "Direct")
                        return@launch
                    }
                }
                Log.i(TAG, "[Direct Send] Komut başarıyla gönderildi: ${payload::class.java.simpleName}")
            } catch (e: IOException) {
                Log.e(TAG, "[Direct Send] IO Hatası: ${e.message}", e)
                showErrorCallback("Komut gönderme IO Hatası: ${e.message?.take(100)}", "Direct")
            } catch (e: Exception) {
                Log.e(TAG, "[Direct Send] Beklenmedik hata: ${e.message}", e)
                showErrorCallback("Komut gönderme hatası: ${e.message?.take(100)}", "Direct")
            }
        }
    }
    
    // Örnek komut gönderme yardımcı fonksiyonları (MainActivity'den taşındı, sendMavlinkCommand kullanacak şekilde uyarlandı)
    fun sendArmDisarmCommand(arm: Boolean) {
        val command = CommandLong.builder()
            .targetSystem(1)
            .targetComponent(1)
            .command(MavCmd.MAV_CMD_COMPONENT_ARM_DISARM)
            .confirmation(0)
            .param1(if (arm) 1f else 0f)
            .build()
        sendMavlinkCommand(command)
    }

    fun sendRequestGpsStreamCommand() {
        val command = CommandLong.builder()
            .targetSystem(1)
            .targetComponent(1)
            .command(MavCmd.MAV_CMD_SET_MESSAGE_INTERVAL)
            .param1(GlobalPositionInt.MAVLINK_MSG_ID_GLOBAL_POSITION_INT.toFloat()) // Mesaj ID
            .param2(1000000f) // Interval in microseconds (1 saniye)
            .confirmation(0)
            .build()
        sendMavlinkCommand(command)
         val command2 = CommandLong.builder()
            .targetSystem(1)
            .targetComponent(1)
            .command(MavCmd.MAV_CMD_SET_MESSAGE_INTERVAL)
            .param1(GpsRawInt.MAVLINK_MSG_ID_GPS_RAW_INT.toFloat()) // Mesaj ID
            .param2(1000000f) // Interval in microseconds (1 saniye)
            .confirmation(0)
            .build()
        sendMavlinkCommand(command2)
    }
    
    fun sendRcCalibrationCommand() {
        val command = CommandLong.builder()
            .targetSystem(1) 
            .targetComponent(1) 
            .command(MavCmd.MAV_CMD_START_RX_PAIR) 
            .param1(0f) // 0=kalibrasyon başlat
            .confirmation(0)
            .build()
        sendMavlinkCommand(command)
    }

    fun disconnectDirectUsb(showStatusUpdate: Boolean = true) {
        if (!_isDirectConnected.value && directIoManager == null && directUsbPort == null) {
            if (showStatusUpdate) _connectionStatusDirect.value = "Bağlı Değil (Direkt)"
            return
        }
        Log.d(TAG, "Direkt USB bağlantısı kesiliyor...")
        cleanUpDirectResources()
        _isDirectConnected.value = false
        if (showStatusUpdate) _connectionStatusDirect.value = "Bağlantı Kesildi (Direkt)"
        Log.i(TAG, "Direkt USB bağlantısı kesildi.")
    }

    private fun cleanUpDirectResources() {
        pipeWriterJob?.cancel()
        pipeWriterJob = null
        directParserJob?.cancel()
        directParserJob = null

        directIoManager?.stop() // Önce I/O manager'ı durdur
        directIoManager = null

        directCommandOutputStream?.tryClose(TAG, "CommandOutputStream")
        directCommandOutputStream = null
        
        // directDataChannel'ın zaten parser job'un finally bloğunda kapatılması beklenir.
        if (!directDataChannel.isClosedForReceive) {
             directDataChannel.close()
        }

        directUsbPort?.tryClose(TAG, "UsbSerialPort")
        directUsbPort = null
        
        // UsbDeviceConnection MainActivity tarafından yönetildiği için burada kapatılmamalı,
        // MainActivity'nin kendisi bağlantıyı açıp kapatmalı. Servis sadece portu kullanır.
        // directUsbConnection?.close() // Bu satır kaldırıldı.
        directUsbConnection = null // Referansı temizle

        _directTelemetryState.value = DirectTelemetryState() // Durumu sıfırla
        mavlinkConnection = null

        try {
            pipedInputStreamForParser?.close()
        } catch (e: IOException) { Log.e(TAG, "[CleanUp] PipedInputStream kapatılırken hata", e)}
        pipedInputStreamForParser = null
    }
}

// OutputStream için güvenli kapatma yardımcı fonksiyonu
fun OutputStream.tryClose(tag: String, streamName: String) {
    try {
        this.close()
    } catch (e: IOException) {
        Log.e(tag, "Error closing $streamName in finally", e)
    }
}

// UsbSerialPort için güvenli kapatma yardımcı fonksiyonu
fun UsbSerialPort.tryClose(tag: String, portName: String) {
    try {
        if (this.isOpen) {
            this.close()
        }
    } catch (e: IOException) {
        Log.e(tag, "Error closing $portName in finally", e)
    }
} 