package io.github.damnms.buerstli.ble

import android.bluetooth.le.ScanRecord
import androidx.core.util.forEach

/** "0xFE95" for a standard 16-bit UUID, else the full UUID. */
fun shortUuid(uuid: java.util.UUID): String {
    val s = uuid.toString()
    return if (s.startsWith("0000") && s.endsWith("-0000-1000-8000-00805f9b34fb")) "0x" + s.substring(4, 8).uppercase() else s
}

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
        address = device.address,
    )
}
