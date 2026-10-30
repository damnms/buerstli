package io.github.damnms.buerstli.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattConnectionSettings
import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.asExecutor

/**
 * GATT callbacks run on one background thread, one after another, like the callbacks of the
 * old connectGatt did. Order matters (e.g. notifications).
 */
@OptIn(ExperimentalCoroutinesApi::class)
private val gattCallbacks = Dispatchers.IO.limitedParallelism(1).asExecutor()

/**
 * Direct (not auto-) connection over Bluetooth LE. Android 17 (API 37) deprecates the old
 * connectGatt overloads; the replacement only exists from API 37 on, so older Android keeps
 * the old call.
 */
@SuppressLint("MissingPermission")
fun BluetoothDevice.connectLe(context: Context, callback: BluetoothGattCallback): BluetoothGatt? =
    if (Build.VERSION.SDK_INT >= 37) {
        val settings = BluetoothGattConnectionSettings.Builder()
            .setAutoConnectEnabled(false)
            .setTransport(BluetoothDevice.TRANSPORT_LE)
            .build()
        connectGatt(settings, gattCallbacks, callback)
    } else {
        @Suppress("DEPRECATION")
        connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)
    }
