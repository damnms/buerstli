package io.github.damnms.buerstli

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
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

sealed interface ConnState {
    data object Idle : ConnState
    data object NoBluetooth : ConnState
    data object BluetoothOff : ConnState
    data object Scanning : ConnState
    data object NotFound : ConnState
    data class Connecting(val name: String) : ConnState
    data class Connected(val name: String, val status: BrushProtocol.Status?, val rawHex: String?) : ConnState
    data class Unsupported(val name: String) : ConnState
    data class Disconnected(val name: String, val lastStatus: BrushProtocol.Status?) : ConnState
    data class Failed(val reason: String) : ConnState
}

/**
 * Finds the brush, connects, subscribes to notifications and keeps the latest status.
 * Callers must hold the Bluetooth permissions before calling [find].
 */
@SuppressLint("MissingPermission")
class BrushConnection(context: Context) {

    private val appContext = context.applicationContext
    private val adapter = appContext.getSystemService(BluetoothManager::class.java)?.adapter
    private val main = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<ConnState>(ConnState.Idle)
    val state: StateFlow<ConnState> = _state.asStateFlow()

    private var gatt: BluetoothGatt? = null
    private var scanCallback: ScanCallback? = null
    private var deviceName = ""
    private var lastStatus: BrushProtocol.Status? = null

    private val scanTimeout = Runnable {
        if (scanCallback != null) {
            stopScan()
            _state.value = ConnState.NotFound
        }
    }

    fun find() {
        val a = adapter ?: run { _state.value = ConnState.NoBluetooth; return }
        if (!a.isEnabled) { _state.value = ConnState.BluetoothOff; return }
        val scanner = a.bluetoothLeScanner ?: run { _state.value = ConnState.BluetoothOff; return }

        stopScan()
        closeGatt()
        _state.value = ConnState.Scanning

        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.scanRecord?.deviceName ?: result.device.name ?: return
                if (!name.startsWith(BrushProtocol.NAME_PREFIX)) return
                main.post {
                    if (scanCallback == null) return@post // already handled
                    stopScan()
                    connect(result.device, name)
                }
            }

            override fun onScanFailed(errorCode: Int) {
                main.post {
                    stopScan()
                    _state.value = ConnState.Failed("scan error $errorCode")
                }
            }
        }
        scanCallback = cb
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        scanner.startScan(null, settings, cb)
        main.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
    }

    fun disconnect() {
        stopScan()
        gatt?.disconnect() ?: run { _state.value = ConnState.Idle }
    }

    fun close() {
        stopScan()
        closeGatt()
    }

    private fun stopScan() {
        main.removeCallbacks(scanTimeout)
        val cb = scanCallback ?: return
        scanCallback = null
        try {
            adapter?.bluetoothLeScanner?.stopScan(cb)
        } catch (_: IllegalStateException) {
            // Bluetooth was turned off meanwhile; nothing to stop.
        }
    }

    private fun closeGatt() {
        gatt?.close()
        gatt = null
    }

    private fun connect(device: BluetoothDevice, name: String) {
        deviceName = name
        lastStatus = null
        _state.value = ConnState.Connecting(name)
        gatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun handlePacket(value: ByteArray) {
        val status = BrushProtocol.parseStatus(value)
        if (status != null) lastStatus = status
        _state.value = ConnState.Connected(deviceName, lastStatus, BrushProtocol.toHex(value))
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED && status == BluetoothGatt.GATT_SUCCESS) {
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED || status != BluetoothGatt.GATT_SUCCESS) {
                g.close()
                main.post {
                    if (gatt === g) gatt = null
                    _state.value = ConnState.Disconnected(deviceName, lastStatus)
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val ch = g.getService(BrushProtocol.SERVICE_UUID)
                ?.getCharacteristic(BrushProtocol.CHARACTERISTIC_UUID)
            val cccd = ch?.getDescriptor(BrushProtocol.CCCD_UUID)
            if (status != BluetoothGatt.GATT_SUCCESS || ch == null || cccd == null) {
                _state.value = ConnState.Unsupported(deviceName)
                g.disconnect()
                return
            }
            _state.value = ConnState.Connected(deviceName, null, null)
            g.setCharacteristicNotification(ch, true)
            val enable = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(cccd, enable)
            } else {
                @Suppress("DEPRECATION")
                cccd.value = enable
                @Suppress("DEPRECATION")
                g.writeDescriptor(cccd)
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            // Notifications are on; ask for the current status once.
            descriptor.characteristic?.let { g.readCharacteristic(it) }
        }

        // Android 13+
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray) {
            handlePacket(value)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) handlePacket(value)
        }

        // Android 12 and older
        @Deprecated("Pre-Android-13 callback")
        override fun onCharacteristicChanged(g: BluetoothGatt, ch: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) handlePacket(ch.value ?: return)
        }

        @Deprecated("Pre-Android-13 callback")
        override fun onCharacteristicRead(g: BluetoothGatt, ch: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU && status == BluetoothGatt.GATT_SUCCESS) {
                handlePacket(ch.value ?: return)
            }
        }
    }

    private companion object {
        // The brush can take a while to advertise again after a disconnect.
        const val SCAN_TIMEOUT_MS = 30_000L
    }
}
