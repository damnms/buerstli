package io.github.damnms.buerstli

import org.junit.Assert.assertEquals
import org.junit.Test

class NearbyListTest {

    @Test
    fun `devices without a name are left out`() {
        val list = NearbyList().seen("A", null, -40).seen("B", "  ", -50).seen("C", "Y10", -60)
        assertEquals(listOf("Y10"), list.sorted().map { it.name })
    }

    @Test
    fun `one entry per device, with its latest name and signal`() {
        val list = NearbyList().seen("A", "Brush", -80).seen("A", "Brush 2", -45)
        assertEquals(listOf(Nearby("A", "Brush 2", -45)), list.sorted())
    }

    @Test
    fun `the strongest signal comes first, so the brush in your hand is on top`() {
        val list = NearbyList().seen("far", "Speaker", -85).seen("near", "Y10", -38).seen("mid", "Watch", -60)
        assertEquals(listOf("Y10", "Watch", "Speaker"), list.sorted().map { it.name })
    }

    @Test
    fun `the list stays short, keeping the strongest`() {
        var list = NearbyList(limit = 3)
        (1..6).forEach { list = list.seen("d$it", "Device $it", -90 + it) }
        assertEquals(listOf("Device 6", "Device 5", "Device 4"), list.sorted().map { it.name })
    }
}
