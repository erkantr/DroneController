package com.bysoftware.dronecontroller.service

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.usb.UsbDeviceConnection
import android.util.Log
import com.bysoftware.dronecontroller.config.AppConfig
import com.bysoftware.dronecontroller.utils.format
import com.hoho.android.usbserial.driver.UsbSerialPort
import io.mavsdk.System
import io.mavsdk.mavsdkserver.MavsdkServer
import io.reactivex.rxjava3.android.schedulers.AndroidSchedulers
import io.reactivex.rxjava3.disposables.CompositeDisposable
import io.reactivex.rxjava3.schedulers.Schedulers
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.IOException
import java.net.*

class ProxyConnectionService(
    private val context: Context,
    private val scope: CoroutineScope,
    private val showErrorCallback: (message: String, type: String) -> Unit,
    private val onConnectionStateChange: (Boolean) -> Unit
) {
    private val _connectionStatusProxy = MutableStateFlow("Bağlı Değil (Proxy)")
    val connectionStatusProxy: StateFlow<String> = _connectionStatusProxy.asStateFlow()

    private val _telemetryDataProxy = MutableStateFlow("Proxy telemetri bekleniyor...")
    val telemetryDataProxy: StateFlow<String> = _telemetryDataProxy.asStateFlow()

    private val _isProxyConnected = MutableStateFlow(false)
    val isProxyConnected: StateFlow<Boolean> = _isProxyConnected.asStateFlow()

    private var mavsdkServer: MavsdkServer? = null
    var drone: System? = null
        private set
    private val disposablesProxy = CompositeDisposable()

    private var proxyUsbPort: UsbSerialPort? = null
    private var proxyUsbConnection: UsbDeviceConnection? = null
    private var udpSocket: DatagramSocket? = null
    private var proxyJob: Job? = null
    private var serverJob: Job? = null

    companion object {
        private const val TAG = AppConfig.TAG + "_ProxySvc"
        private const val UDP_BUFFER_SIZE = 4096
    }

    @SuppressLint("ServiceCast")
    fun connectProxy(port: UsbSerialPort, connection: UsbDeviceConnection) {
        if (_isProxyConnected.value) {
            showErrorCallback("Zaten bir proxy bağlantısı aktif.", "Proxy")
            return
        }
        onConnectionStateChange(true)
        _connectionStatusProxy.value = "Bağlanılıyor (Proxy)..."
        proxyUsbPort = port
        proxyUsbConnection = connection

        serverJob = scope.launch(Dispatchers.IO + CoroutineName("MavsdkServerRunner")) {
            var openedConnectionInternal: UsbDeviceConnection? = connection
            var openedPortInternal: UsbSerialPort? = port
            var openedSocketInternal: DatagramSocket? = null
            var startedServerInternal: MavsdkServer? = null
            var connectedDroneInternal: System? = null
            var startedProxyJobInternal: Job? = null

            try {
                _connectionStatusProxy.value = "MAVSDK sunucusu başlatılıyor..."
                Log.d(TAG, "Adım 1: MAVSDK Sunucusu Başlatılıyor...")
                startedServerInternal = MavsdkServer()
                val serverPort = startedServerInternal.run()
                mavsdkServer = startedServerInternal
                Log.i(TAG, "Adım 1 Tamamlandı: MAVSDK sunucusu UDP ${serverPort} portunda çalışıyor.")

                _connectionStatusProxy.value = "USB portu açılıyor..."
                Log.d(TAG, "Adım 2: USB Seri Port Açılıyor... (Port: ${openedPortInternal?.portNumber})")
                openedPortInternal!!.open(openedConnectionInternal)
                openedPortInternal.setParameters(AppConfig.DEFAULT_BAUD_RATE, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)
                Log.i(TAG, "Adım 2 Tamamlandı: USB portu açıldı ve ayarlandı.")

                _connectionStatusProxy.value = "UDP soketi oluşturuluyor..."
                Log.d(TAG, "Adım 3: Yerel UDP Soketi Oluşturuluyor...")
                openedSocketInternal = DatagramSocket()
                udpSocket = openedSocketInternal
                Log.i(TAG, "Adım 3 Tamamlandı: Yerel UDP soketi ${openedSocketInternal.localAddress}:${openedSocketInternal.localPort} üzerinde oluşturuldu.")

                _connectionStatusProxy.value = "UDP proxy başlatılıyor..."
                Log.d(TAG, "Adım 4: Çift Yönlü UDP Proxy Başlatılıyor...")
                startedProxyJobInternal = startUdpProxyInternal(
                    usbPort = openedPortInternal,
                    udpSocket = openedSocketInternal,
                    targetAddress = InetAddress.getLoopbackAddress(),
                    targetPort = serverPort
                )
                proxyJob = startedProxyJobInternal
                Log.i(TAG, "Adım 4 Tamamlandı: Çift Yönlü UDP Proxy görevi başlatıldı.")

                _connectionStatusProxy.value = "Drone keşfediliyor..."
                Log.d(TAG, "Adım 5: MAVSDK System Keşfediliyor (Timeout: 15sn)... Port: $serverPort")
                val discoveryTimeout = 15_000L
                val mavsdkSystemAddress = "127.0.0.1"
                Log.d(TAG, "MAVSDK System için hedef: udp://$mavsdkSystemAddress:$serverPort")

                connectedDroneInternal = withTimeoutOrNull(discoveryTimeout) {
                    val newDrone = System(mavsdkSystemAddress, serverPort)
                    delay(3000)
                    newDrone
                }

                if (connectedDroneInternal == null) {
                    throw IOException("Drone keşfi zaman aşımına uğradı veya MAVSDK System oluşturulamadı.")
                }
                drone = connectedDroneInternal
                Log.i(TAG, "Adım 5 Tamamlandı: Drone (System objesi) oluşturuldu.")

                _connectionStatusProxy.value = "Telemetri ayarlanıyor..."
                Log.d(TAG, "Adım 6: Telemetri Abonelikleri Ayarlanıyor...")
                setupTelemetryListenersInternal(drone!!)
                Log.i(TAG, "Adım 6 Tamamlandı: Telemetri abonelikleri ayarlandı.")

                _isProxyConnected.value = true
                _connectionStatusProxy.value = "Bağlı (Proxy): ${port.device.deviceName}"
                Log.i(TAG, "Proxy bağlantısı başarıyla kuruldu.")

            } catch (e: Exception) {
                Log.e(TAG, "Proxy bağlantı hatası", e)
                showErrorCallback("Proxy Hatası: ${e.message?.take(150)}", "Proxy")
                cleanUpProxyResources()
                _isProxyConnected.value = false
                _connectionStatusProxy.value = "Bağlantı Hatası (Proxy)"
            } finally {
                if (!_isProxyConnected.value) {
                    cleanUpProxyResources()
                }
                onConnectionStateChange(false)
            }
        }
    }

    private fun startUdpProxyInternal(
        usbPort: UsbSerialPort?,
        udpSocket: DatagramSocket?,
        targetAddress: InetAddress,
        targetPort: Int
    ): Job {
        if (usbPort == null || udpSocket == null) {
            Log.e(TAG, "[UDP Proxy] USB portu veya UDP soketi null, proxy başlatılamıyor.")
            throw IllegalStateException("Proxy için USB portu veya UDP soketi null.")
        }
        Log.d(TAG, "[UDP Proxy] Başlatılıyor: USB(${usbPort.portNumber}) <-> UDP(${udpSocket.localPort} -> ${targetAddress.hostAddress}:$targetPort)")

        return scope.launch(Dispatchers.IO + CoroutineName("UdpProxy")) {
            val usbToUdpJob = launch(CoroutineName("UsbToUdp")) {
                val buffer = ByteArray(UDP_BUFFER_SIZE)
                try {
                    while (isActive) {
                        val len = usbPort.read(buffer, buffer.size)
                        if (len > 0) {
                            val packet = DatagramPacket(buffer, len, targetAddress, targetPort)
                            udpSocket.send(packet)
                            Log.d(TAG, "[UDP Proxy] USB -> UDP: $len byte gönderildi porta: $targetPort")
                        } else if (len < 0) {
                            Log.w(TAG, "[UDP Proxy] USB'den okuma hatası (EOF?) - USB->UDP durduruluyor.")
                            break
                        }
                    }
                } catch (e: IOException) {
                    if (isActive) Log.e(TAG, "[UDP Proxy] USB->UDP IO Hatası", e)
                } catch (e: Exception) {
                    if (isActive) Log.e(TAG, "[UDP Proxy] USB->UDP Genel Hata", e)
                } finally {
                    Log.d(TAG, "[UDP Proxy] USB->UDP korutini tamamlandı.")
                }
            }

            val udpToUsbJob = launch(CoroutineName("UdpToUsb")) {
                val buffer = ByteArray(UDP_BUFFER_SIZE)
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    while (isActive) {
                        udpSocket.receive(packet)
                        if (packet.length > 0) {
                            usbPort.write(packet.data, packet.offset, packet.length, 500)
                            Log.d(TAG, "[UDP Proxy] UDP -> USB: ${packet.length} byte gönderildi.")
                        } else {
                             Log.w(TAG, "[UDP Proxy] UDP'den boş paket alındı.")
                        }
                    }
                } catch (e: SocketException) {
                     if (isActive) Log.w(TAG, "[UDP Proxy] UDP Alma Soket Hatası (muhtemelen kapatıldı)", e)
                } catch (e: IOException) {
                    if (isActive) Log.e(TAG, "[UDP Proxy] UDP->USB IO Hatası", e)
                } catch (e: Exception) {
                    if (isActive) Log.e(TAG, "[UDP Proxy] UDP->USB Genel Hata", e)
                } finally {
                    Log.d(TAG, "[UDP Proxy] UDP->USB korutini tamamlandı.")
                }
            }

            usbToUdpJob.invokeOnCompletion { cause ->
                if (cause != null && cause !is CancellationException) Log.w(TAG, "[UDP Proxy] usbToUdpJob hata ile sonlandı", cause)
                else Log.d(TAG, "[UDP Proxy] usbToUdpJob normal sonlandı.")
                if (udpToUsbJob.isActive) udpToUsbJob.cancel(CancellationException("USB->UDP sonlandı."))
            }
            udpToUsbJob.invokeOnCompletion { cause ->
                 if (cause != null && cause !is CancellationException) Log.w(TAG, "[UDP Proxy] udpToUsbJob hata ile sonlandı", cause)
                 else Log.d(TAG, "[UDP Proxy] udpToUsbJob normal sonlandı.")
                if (usbToUdpJob.isActive) usbToUdpJob.cancel(CancellationException("UDP->USB sonlandı."))
            }
             Log.i(TAG, "[UDP Proxy] Çift yönlü proxy korutinleri başlatıldı.")
        }
    }

    @SuppressLint("CheckResult")
    private fun setupTelemetryListenersInternal(currentDrone: System) {
        disposablesProxy.clear()
        val telemetryText = StringBuilder()

        disposablesProxy.add(
            currentDrone.core.connectionState
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { state ->
                        Log.i(TAG, "[Proxy Telemetry] Bağlantı Durumu: ${state.isConnected}")
                        if (!state.isConnected && _isProxyConnected.value) {
                            showErrorCallback("Proxy bağlantısı koptu.", "Proxy")
                        }
                    },
                    { error -> Log.e(TAG, "[Proxy Telemetry] Bağlantı durumu alınamadı", error) }
                )
        )

        disposablesProxy.add(
            currentDrone.telemetry.position
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { position ->
                        val text = "Lat: ${position.latitudeDeg.format(6)}, Lon: ${position.longitudeDeg.format(6)}, RelAlt: ${position.relativeAltitudeM.format(1)}m\n"
                        appendTelemetry(text)
                    },
                    { error -> Log.w(TAG, "[Proxy Telemetry] Konum verisi alınamadı: ${error.message}") }
                )
        )

        disposablesProxy.add(
            currentDrone.telemetry.armed
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { armed -> appendTelemetry("Armed: $armed\n") },
                    { error -> Log.w(TAG, "[Proxy Telemetry] Armed durumu alınamadı: ${error.message}") }
                )
        )
        
        disposablesProxy.add(
            currentDrone.telemetry.flightMode
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { mode -> appendTelemetry("Mod: $mode\n") },
                    { error -> Log.w(TAG, "[Proxy Telemetry] Uçuş Modu alınamadı: ${error.message}") }
                )
        )

        disposablesProxy.add(
            currentDrone.telemetry.battery
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { battery ->
                        val batteryPercent = battery.remainingPercent?.let { (it * 100).format(0) + "%" } ?: "N/A"
                        appendTelemetry("Pil: $batteryPercent, Volt: ${battery.voltageFv.format(2)}V\n")
                    },
                    { error -> Log.w(TAG, "[Proxy Telemetry] Pil verisi alınamadı: ${error.message}") }
                )
        )
        
        disposablesProxy.add(
            currentDrone.telemetry.attitudeEuler
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { attitude ->
                        appendTelemetry("Roll: ${attitude.rollDeg.format(1)}°, Pitch: ${attitude.pitchDeg.format(1)}°, Yaw: ${attitude.yawDeg.format(1)}°\n")
                    },
                    { error -> Log.w(TAG, "[Proxy Telemetry] Tutum (Attitude) verisi alınamadı: ${error.message}") }
                )
        )
        
        disposablesProxy.add(
            currentDrone.telemetry.gpsInfo
                .subscribeOn(Schedulers.io())
                .observeOn(AndroidSchedulers.mainThread())
                .subscribe(
                    { gpsInfo ->
                        appendTelemetry("GPS Fix: ${gpsInfo.fixType}, Uydular: ${gpsInfo.numSatellites}\n")
                    },
                    { error -> Log.w(TAG, "[Proxy Telemetry] GPS Info alınamadı: ${error.message}") }
                )
        )

        _telemetryDataProxy.value = "Veriler yükleniyor..."
    }

    private fun appendTelemetry(text: String) {
        scope.launch(Dispatchers.Main) {
             val current = _telemetryDataProxy.value
             if (current == "Veriler yükleniyor..." || current == "Proxy telemetri bekleniyor...") {
                 _telemetryDataProxy.value = text
             } else {
                val lines = current.lines().toMutableList()
                val prefix = text.substringBefore(':').trim()
                val index = lines.indexOfFirst { it.startsWith(prefix) }
                if (index != -1) {
                    lines[index] = text.trimEnd()
                } else {
                    lines.add(text.trimEnd())
                }
                _telemetryDataProxy.value = lines.joinToString("\n")
            }
        }
    }

    fun disconnectProxy(showStatusUpdate: Boolean = true) {
        if (!_isProxyConnected.value && mavsdkServer == null && proxyJob == null) {
            if (showStatusUpdate) _connectionStatusProxy.value = "Bağlı Değil (Proxy)"
            return
        }
        Log.d(TAG, "Proxy bağlantısı kesiliyor...")
        cleanUpProxyResources()
        _isProxyConnected.value = false
        if (showStatusUpdate) _connectionStatusProxy.value = "Bağlantı Kesildi (Proxy)"
        drone = null
        Log.i(TAG, "Proxy bağlantısı kesildi.")
    }

    private fun cleanUpProxyResources() {
        Log.d(TAG, "Proxy kaynakları temizleniyor...")
        disposablesProxy.clear()

        proxyJob?.cancel(CancellationException("Proxy bağlantısı kapatılıyor."))
        proxyJob = null
        Log.d(TAG, "UDP Proxy işi iptal edildi.")

        serverJob?.cancel(CancellationException("Proxy bağlantısı kapatılıyor."))
        serverJob = null
        Log.d(TAG, "MAVSDK server işi iptal edildi.")
        
        mavsdkServer?.stop()
        mavsdkServer = null
        Log.d(TAG, "MAVSDK sunucusu durduruldu.")

        udpSocket?.close()
        udpSocket = null
        Log.d(TAG, "UDP soketi kapatıldı.")

        proxyUsbPort?.tryClose(TAG, "ProxyUsbSerialPort")
        proxyUsbPort = null
        Log.d(TAG, "Proxy USB portu kapatıldı.")
        
        proxyUsbConnection?.close()
        proxyUsbConnection = null
        Log.d(TAG, "Proxy USB bağlantı referansı temizlendi.")

        _telemetryDataProxy.value = "Proxy telemetri bekleniyor..."
        Log.d(TAG, "Proxy kaynak temizleme tamamlandı.")
    }
}

// UsbSerialPort için tryClose zaten DirectConnectionService.kt içinde tanımlı.
// Eğer ayrı dosyada olacaksa buraya da eklenebilir veya ortak bir utils dosyasına taşınabilir. 