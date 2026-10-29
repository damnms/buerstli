package io.github.damnms.buerstli

import io.github.damnms.buerstli.domain.DriverRegistry
import io.github.damnms.buerstli.drivers.laifen.LaifenWaveV1Driver

/** Composition root: every supported toothbrush is registered here. */
object Drivers {
    val registry = DriverRegistry(
        listOf(
            LaifenWaveV1Driver,
        ),
    )
}
