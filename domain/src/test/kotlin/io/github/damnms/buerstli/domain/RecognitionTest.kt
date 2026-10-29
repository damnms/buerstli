package io.github.damnms.buerstli.domain

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.UUID

class RecognitionTest {

    private class StubDriver(private val prefix: String) : ToothbrushDriver {
        override val model = BrushModel("Stub", prefix)
        override val supportLevel = SupportLevel.VERIFIED
        override val capabilities = Capabilities(modes = emptyList())
        override fun recognizes(advertisement: Advertisement) = advertisement.name?.startsWith(prefix) == true
        override fun open(link: BrushLink): BrushSession = error("not needed")
    }

    private val supported = StubDriver("GOOD")
    private val sonicareService = UUID.fromString("477ea600-a260-11e4-ae37-0002a5d50001")
    private val registry = DriverRegistry(
        drivers = listOf(supported),
        knownUnsupported = listOf(
            KnownBrush("Philips Sonicare") { ad ->
                ad.name?.contains("Sonicare") == true || sonicareService in ad.serviceUuids
            },
            KnownBrush("Oral-B") { ad -> 0x00DC in ad.manufacturerData },
        ),
    )

    @Test
    fun `a brush with a driver is supported`() {
        assertEquals(Recognition.Supported(supported), registry.recognize(Advertisement("GOOD-1")))
    }

    @Test
    fun `known brushes without a driver are recognized by name, service or manufacturer`() {
        assertEquals(Recognition.Unsupported("Philips Sonicare"), registry.recognize(Advertisement("Philips Sonicare")))
        assertEquals(
            Recognition.Unsupported("Philips Sonicare"),
            registry.recognize(Advertisement(name = null, serviceUuids = listOf(sonicareService))),
        )
        assertEquals(
            Recognition.Unsupported("Oral-B"),
            registry.recognize(Advertisement(name = null, manufacturerData = mapOf(0x00DC to byteArrayOf(1, 2)))),
        )
    }

    @Test
    fun `everything else is unknown`() {
        assertEquals(Recognition.Unknown, registry.recognize(Advertisement("Kitchen speaker")))
        assertEquals(Recognition.Unknown, registry.recognize(Advertisement(null)))
    }

    @Test
    fun `a driver wins over a known-unsupported entry`() {
        val r = DriverRegistry(listOf(supported), listOf(KnownBrush("Catch-all") { true }))
        assertEquals(Recognition.Supported(supported), r.recognize(Advertisement("GOOD-2")))
    }
}
