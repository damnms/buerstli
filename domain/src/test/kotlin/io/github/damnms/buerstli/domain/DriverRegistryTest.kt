package io.github.damnms.buerstli.domain

import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class DriverRegistryTest {

    private class StubDriver(private val prefix: String) : ToothbrushDriver {
        override val model = BrushModel("Stub", prefix)
        override val supportLevel = SupportLevel.EXPERIMENTAL
        override val capabilities = Capabilities(modes = emptyList())
        override fun recognizes(advertisement: Advertisement) =
            advertisement.name?.startsWith(prefix) == true
        override fun open(link: BrushLink): BrushSession = error("not needed")
    }

    private val a = StubDriver("AAA")
    private val b = StubDriver("BBB")
    private val registry = DriverRegistry(listOf(a, b))

    @Test
    fun `picks the driver that recognizes the brush`() {
        assertSame(b, registry.driverFor(Advertisement(name = "BBB-123")))
    }

    @Test
    fun `unknown brushes get no driver`() {
        assertNull(registry.driverFor(Advertisement(name = "Some speaker")))
        assertNull(registry.driverFor(Advertisement(name = null)))
    }
}
