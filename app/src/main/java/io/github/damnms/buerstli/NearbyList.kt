package io.github.damnms.buerstli

/** A Bluetooth device nearby, for picking a brush Bürstli doesn't recognize. */
data class Nearby(val address: String, val name: String, val rssi: Int)

/** What a scan has seen so far: named devices only, one entry each, strongest first. */
data class NearbyList(
    private val byAddress: Map<String, Nearby> = emptyMap(),
    private val limit: Int = 30,
) {
    fun seen(address: String, name: String?, rssi: Int): NearbyList {
        if (name.isNullOrBlank()) return this
        return copy(byAddress = byAddress + (address to Nearby(address, name.trim(), rssi)))
    }

    fun sorted(): List<Nearby> = byAddress.values
        .sortedWith(compareByDescending<Nearby> { it.rssi }.thenBy { it.name })
        .take(limit)
}
