package io.github.damnms.buerstli.ble

import android.bluetooth.le.ScanRecord
import androidx.core.util.forEach

/** Manufacturer-specific data by Bluetooth company id, as a plain map. */
fun ScanRecord?.manufacturerMap(): Map<Int, ByteArray> = buildMap {
    this@manufacturerMap?.manufacturerSpecificData?.forEach { id, data -> put(id, data) }
}

/** What the domain needs to know about a scan result. */
fun android.bluetooth.le.ScanResult.toAdvertisement(): io.github.damnms.buerstli.domain.Advertisement {
    val record = scanRecord
    @android.annotation.SuppressLint("MissingPermission")
    val name = record?.deviceName ?: device.name
    return io.github.damnms.buerstli.domain.Advertisement(
        name = name,
        serviceUuids = record?.serviceUuids?.map { it.uuid } ?: emptyList(),
        manufacturerData = record.manufacturerMap(),
        serviceData = record?.serviceData?.mapKeys { it.key.uuid } ?: emptyMap(),
    )
}
