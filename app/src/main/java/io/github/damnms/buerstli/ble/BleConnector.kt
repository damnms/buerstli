package io.github.damnms.buerstli.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import io.github.damnms.buerstli.AddressMask
import io.github.damnms.buerstli.Diagnostics
import io.github.damnms.buerstli.domain.Advertisement
import io.github.damnms.buerstli.hex
import io.github.damnms.buerstli.domain.BrushSession
import io.github.damnms.buerstli.domain.BrushState
import io.github.damnms.buerstli.domain.DriverRegistry
import io.github.damnms.buerstli.domain.Recognition
import io.github.damnms.buerstli.domain.ToothbrushDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.IOException

sealed interface Connection {
    data object Idle : Connection
    data object NoBluetooth : Connection
    data object BluetoothOff : Connection
    data object Scanning : Connection
    data object NotFound : Connection
    data class Connecting(val name: String, val driver: ToothbrushDriver) : Connection
    data class Connected(
        val name: String,
        val driver: ToothbrushDriver,
        val session: BrushSession,
        val device: BluetoothDevice,
    ) : Connection
    data class Unsupported(val name: String) : Connection
    data class Disconnected(val name: String, val driver: ToothbrushDriver, val lastState: BrushState?) : Connection
    data class Failed(val reason: String) : Connection

    /** A brush we know but can't talk to yet; the user can record a report for it. */
    data class FoundUnsupported(val name: String?, val brand: String, val device: BluetoothDevice) : Connection
}

/**
 * Adapter: scanning and connecting with Android's Bluetooth stack. Knows nothing about
 * any brand; drivers from the [DriverRegistry] decide which device is a brush and how to
 * talk to it. Callers must hold the Bluetooth permissions before calling [find].
 */
@SuppressLint("MissingPermission")
class BleConnector(
    context: Context,
    private val registry: DriverRegistry,
    private val scope: CoroutineScope,
    private val diag: Diagnostics,
) {
    private val appContext = context.applicationContext
    private val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter

    private val _state = MutableStateFlow<Connection>(Connection.Idle)
    val state: StateFlow<Connection> = _state.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var link: AndroidBrushLink? = null
    private var session: BrushSession? = null
    private var scanCallback: ScanCallback? = null
    private var scanTimeout: Job? = null
    private var unsupportedGrace: Job? = null

    fun find() {
        val a = adapter ?: run { _state.value = Connection.NoBluetooth; return }
        if (!a.isEnabled) { _state.value = Connection.BluetoothOff; return }
        val scanner = a.bluetoothLeScanner ?: run { _state.value = Connection.BluetoothOff; return }

        stopScan()
        closeGatt()
        diag.mark("find: scanning")
        _state.value = Connection.Scanning

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val record = result.scanRecord
                val name = record?.deviceName ?: result.device.name
                val manufacturer = buildMap {
                    val data = record?.manufacturerSpecificData ?: return@buildMap
                    for (i in 0 until data.size()) put(data.keyAt(i), data.valueAt(i))
                }
                val ad = Advertisement(name, record?.serviceUuids?.map { it.uuid } ?: emptyList(), manufacturer)
                when (val recognition = registry.recognize(ad)) {
                    is Recognition.Supported -> scope.launch {
                        if (scanCallback == null) return@launch // someone else won the race
                        val driver = recognition.driver
                        val shown = name?.let { AddressMask(result.device.address).text(it) }
                        diag.log("found $shown (${result.rssi} dBm) -> ${driver.model.brand} ${driver.model.name}")
                        stopScan()
                        connect(result.device, name ?: result.device.address, driver)
                    }
                    is Recognition.Unsupported -> scope.launch {
                        if (scanCallback == null || unsupportedGrace != null) return@launch
                        val shown = name?.let { AddressMask(result.device.address).text(it) }
                        diag.log("found $shown (${result.rssi} dBm): ${recognition.brand}, no driver yet")
                        // Give a supported brush nearby a moment to show up before offering the report.
                        unsupportedGrace = scope.launch {
                            delay(UNSUPPORTED_GRACE_MS)
                            if (scanCallback != null) {
                                stopScan()
                                _state.value = Connection.FoundUnsupported(name, recognition.brand, result.device)
                            }
                        }
                    }
                    Recognition.Unknown -> Unit
                }
            }

            override fun onScanFailed(errorCode: Int) {
                scope.launch {
                    stopScan()
                    diag.log("scan FAILED: error $errorCode")
                    _state.value = Connection.Failed("scan error $errorCode")
                }
            }
        }
        scanCallback = cb
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        scanTimeout = scope.launch {
            delay(SCAN_TIMEOUT_MS)
            if (scanCallback != null) {
                diag.log("nothing found, giving up")
                stopScan()
                _state.value = Connection.NotFound
            }
        }
    }

    fun disconnect() {
        diag.log("disconnect requested")
        stopScan()
        gatt?.disconnect() ?: run { _state.value = Connection.Idle }
    }

    /** Back to the start screen, e.g. after a report was recorded. */
    fun reset() {
        stopScan()
        closeGatt()
        _state.value = Connection.Idle
    }

    fun close() {
        stopScan()
        closeGatt()
    }

    private fun stopScan() {
        scanTimeout?.cancel()
        scanTimeout = null
        unsupportedGrace?.cancel()
        unsupportedGrace = null
        val cb = scanCallback ?: return
        scanCallback = null
        try {
            adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: IllegalStateException) {
            // Bluetooth was turned off meanwhile.
        }
    }

    private fun closeGatt() {
        link?.fail("closed")
        gatt?.close()
        gatt = null
        link = null
        session = null
    }

    private fun connect(device: BluetoothDevice, name: String, driver: ToothbrushDriver) {
        _state.value = Connection.Connecting(name, driver)
        diag.log("connecting")
        gatt = device.connectGatt(appContext, false, Callback(name, driver), BluetoothDevice.TRANSPORT_LE)
    }

    private inner class Callback(val name: String, val driver: ToothbrushDriver) : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            val stateName = when (newState) {
                BluetoothProfile.STATE_CONNECTED -> "connected"
                BluetoothProfile.STATE_DISCONNECTED -> "disconnected"
                else -> "state $newState"
            }
            diag.log("link $stateName (GATT status $status)")
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                diag.log("discovering services")
                g.discoverServices()
                return
            }
            val last = session?.state?.value
            link?.fail("disconnected")
            g.close()
            scope.launch {
                if (gatt === g) {
                    gatt = null
                    link = null
                    session = null
                }
                _state.value = Connection.Disconnected(name, driver, last)
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            diag.log("services discovered: ${g.services.size} (GATT status $status)")
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = Connection.Unsupported(name)
                g.disconnect()
                return
            }
            val l = AndroidBrushLink(g, diag)
            val s = driver.open(l)
            link = l
            session = s
            scope.launch {
                try {
                    s.start()
                    diag.log("ready")
                    _state.value = Connection.Connected(name, driver, s, g.device)
                } catch (e: IOException) {
                    diag.log("handshake FAILED: ${e.message}")
                    _state.value = Connection.Unsupported(name)
                    g.disconnect()
                }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) =
            result(status, ByteArray(0))

        override fun onCharacteristicWrite(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) =
            result(status, ByteArray(0))

        // Android 13+
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            result(status, value)

        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            diag.log("notification ${value.hex()}")
            session?.onNotification(ch.uuid, value)
        }

        // Android 12 and older
        @Deprecated("Pre-Android-13 callback")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) result(status, ch.value ?: ByteArray(0))
        }

        @Deprecated("Pre-Android-13 callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                val value = ch.value ?: return
                diag.log("notification ${value.hex()}")
                session?.onNotification(ch.uuid, value)
            }
        }

        private fun result(status: Int, value: ByteArray) {
            if (status == BluetoothGatt.GATT_SUCCESS) link?.complete(value) else link?.fail("GATT status $status")
        }
    }

    private companion object {
        // The brush can take a while to advertise again after a disconnect.
        const val SCAN_TIMEOUT_MS = 30_000L
        const val UNSUPPORTED_GRACE_MS = 4_000L
    }
}
