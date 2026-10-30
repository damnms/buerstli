package io.github.damnms.buerstli.ble

import android.bluetooth.le.ScanRecord
import androidx.core.util.forEach

/** Manufacturer-specific data by Bluetooth company id, as a plain map. */
fun ScanRecord?.manufacturerMap(): Map<Int, ByteArray> = buildMap {
    this@manufacturerMap?.manufacturerSpecificData?.forEach { id, data -> put(id, data) }
}
