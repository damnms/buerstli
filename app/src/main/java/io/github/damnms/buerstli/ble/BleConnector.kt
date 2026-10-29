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
import io.github.damnms.buerstli.domain.Advertisement
import io.github.damnms.buerstli.domain.BrushSession
import io.github.damnms.buerstli.domain.BrushState
import io.github.damnms.buerstli.domain.DriverRegistry
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
    data class Connected(val name: String, val driver: ToothbrushDriver, val session: BrushSession) : Connection
    data class Unsupported(val name: String) : Connection
    data class Disconnected(val name: String, val driver: ToothbrushDriver, val lastState: BrushState?) : Connection
    data class Failed(val reason: String) : Connection
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

    fun find() {
        val a = adapter ?: run { _state.value = Connection.NoBluetooth; return }
        if (!a.isEnabled) { _state.value = Connection.BluetoothOff; return }
        val scanner = a.bluetoothLeScanner ?: run { _state.value = Connection.BluetoothOff; return }

        stopScan()
        closeGatt()
        _state.value = Connection.Scanning

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name
                val driver = registry.driverFor(Advertisement(name)) ?: return
                scope.launch {
                    if (scanCallback == null) return@launch // someone else won the race
                    stopScan()
                    connect(result.device, name ?: result.device.address, driver)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                scope.launch {
                    stopScan()
                    _state.value = Connection.Failed("scan error $errorCode")
                }
            }
        }
        scanCallback = cb
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        scanTimeout = scope.launch {
            delay(SCAN_TIMEOUT_MS)
            if (scanCallback != null) {
                stopScan()
                _state.value = Connection.NotFound
            }
        }
    }

    fun disconnect() {
        stopScan()
        gatt?.disconnect() ?: run { _state.value = Connection.Idle }
    }

    fun close() {
        stopScan()
        closeGatt()
    }

    private fun stopScan() {
        scanTimeout?.cancel()
        scanTimeout = null
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
        gatt = device.connectGatt(appContext, false, Callback(name, driver), BluetoothDevice.TRANSPORT_LE)
    }

    private inner class Callback(val name: String, val driver: ToothbrushDriver) : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
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
            if (status != BluetoothGatt.GATT_SUCCESS) {
                _state.value = Connection.Unsupported(name)
                g.disconnect()
                return
            }
            val l = AndroidBrushLink(g)
            val s = driver.open(l)
            link = l
            session = s
            scope.launch {
                try {
                    s.start()
                    _state.value = Connection.Connected(name, driver, s)
                } catch (e: IOException) {
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
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) session?.onNotification(ch.uuid, ch.value ?: return)
        }

        private fun result(status: Int, value: ByteArray) {
            if (status == BluetoothGatt.GATT_SUCCESS) link?.complete(value) else link?.fail("GATT status $status")
        }
    }

    private companion object {
        // The brush can take a while to advertise again after a disconnect.
        const val SCAN_TIMEOUT_MS = 30_000L
    }
}
