package de.noctunix.obdbridge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CxAdapterHandleRegistryTest {

    @Test
    fun theSameAddressAlwaysYieldsTheSameHandle() {
        val registry = CxAdapterHandleRegistry()

        val first = registry.handleFor("AA:BB:CC:DD:EE:FF")
        val second = registry.handleFor("AA:BB:CC:DD:EE:FF")

        assertEquals(first, second)
    }

    @Test
    fun differentAddressesYieldDifferentHandles() {
        val registry = CxAdapterHandleRegistry()

        val first = registry.handleFor("AA:BB:CC:DD:EE:FF")
        val second = registry.handleFor("11:22:33:44:55:66")

        assertNotEquals(first, second)
    }

    @Test
    fun theHandleNeverEqualsTheUnderlyingAddressItself() {
        val registry = CxAdapterHandleRegistry()
        val address = "AA:BB:CC:DD:EE:FF"

        val handle = registry.handleFor(address)

        assertNotEquals(address, handle)
    }

    @Test
    fun addressForResolvesAHandleBackToItsAddress() {
        val registry = CxAdapterHandleRegistry()
        val address = "AA:BB:CC:DD:EE:FF"

        val handle = registry.handleFor(address)

        assertEquals(address, registry.addressFor(handle))
    }

    @Test
    fun addressForAnUnknownHandleIsNull() {
        val registry = CxAdapterHandleRegistry()

        assertNull(registry.addressFor("some-handle-never-issued"))
    }
}
