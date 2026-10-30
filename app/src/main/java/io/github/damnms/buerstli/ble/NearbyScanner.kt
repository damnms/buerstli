package io.github.damnms.buerstli.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import io.github.damnms.buerstli.Diagnostics
import io.github.damnms.buerstli.Nearby
import io.github.damnms.buerstli.NearbyList
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Lists named Bluetooth devices nearby, so an owner can pick a brush Bürstli doesn't recognize.
 * The list only lives on screen; nothing about the other devices is logged or kept.
 */
@SuppressLint("MissingPermission")
class NearbyScanner(context: Context, private val scope: CoroutineScope, private val diag: Diagnostics) {
    private val adapter = context.applicationContext.getSystemService(BluetoothManager::class.java)?.adapter

    /** Null while closed. */
    private val _list = MutableStateFlow<List<Nearby>?>(null)
    val list: StateFlow<List<Nearby>?> = _list.asStateFlow()

    private var seen = NearbyList()
    private val devices = mutableMapOf<String, BluetoothDevice>()
    private var callback: ScanCallback? = null
    private var publisher: Job? = null

    fun start() {
        stop()
        val scanner = adapter?.bluetoothLeScanner ?: return
        seen = NearbyList()
        devices.clear()
        _list.value = emptyList()
        diag.log("listing nearby devices")
        val cb = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val name = result.toAdvertisement().name
                scope.launch {
                    if (callback == null) return@launch
                    devices[result.device.address] = result.device
                    seen = seen.seen(result.device.address, name, result.rssi)
                }
            }
        }
        callback = cb
        scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), cb)
        // Publish at most once a second, so entries don't jump around while you try to tap one.
        publisher = scope.launch {
            while (isActive) {
                _list.value = seen.sorted()
                delay(1_000)
            }
        }
    }

    fun device(address: String): BluetoothDevice? = devices[address]

    fun stop() {
        publisher?.cancel()
        publisher = null
        val cb = callback
        callback = null
        if (cb != null) {
            try {
                adapter?.bluetoothLeScanner?.stopScan(cb)
            } catch (_: IllegalStateException) {
                // Bluetooth was turned off meanwhile.
            }
        }
        _list.value = null
    }
}
