package de.noctunix.obdbridge

import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattService
import java.util.UUID
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Exercises [CxServiceCheck] against real (Robolectric-shadowed)
 * `BluetoothGattService`/`BluetoothGattCharacteristic` objects — unlike
 * [CxBleClientTest]'s `serviceMissing` case, which only exercises
 * [CxBleClient] being told the result, this is the actual presence/property
 * check [CxGattManager.isRequiredServiceSupported] runs.
 */
@RunWith(RobolectricTestRunner::class)
class CxServiceCheckTest {

    private fun service(vararg characteristics: BluetoothGattCharacteristic): BluetoothGattService =
        BluetoothGattService(CxBleUuids.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY).apply {
            characteristics.forEach { addCharacteristic(it) }
        }

    private fun characteristic(uuid: UUID, properties: Int) =
        BluetoothGattCharacteristic(uuid, properties, 0)

    @Test
    fun allUuidsWithTheRightPropertiesAreSupported() {
        val result = CxServiceCheck.check(
            service(
                characteristic(CxBleUuids.NOTIFY, BluetoothGattCharacteristic.PROPERTY_NOTIFY),
                characteristic(CxBleUuids.WRITE, BluetoothGattCharacteristic.PROPERTY_WRITE),
            ),
        )

        assertTrue(result.isSupported)
    }

    @Test
    fun writeNoResponseAlsoCountsAsWritable() {
        val result = CxServiceCheck.check(
            service(
                characteristic(CxBleUuids.NOTIFY, BluetoothGattCharacteristic.PROPERTY_NOTIFY),
                characteristic(CxBleUuids.WRITE, BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE),
            ),
        )

        assertTrue(result.isSupported)
    }

    @Test
    fun missingServiceIsNotSupported() {
        val result = CxServiceCheck.check(null)

        assertFalse(result.isSupported)
        assertNull(result.notify)
        assertNull(result.write)
    }

    @Test
    fun serviceWithOnlyOneOfTheTwoRequiredCharacteristicsIsNotSupported() {
        // A foreign device advertising the same generic UART service UUID but
        // only one of the two characteristics this client actually needs.
        val result = CxServiceCheck.check(
            service(characteristic(CxBleUuids.NOTIFY, BluetoothGattCharacteristic.PROPERTY_NOTIFY)),
        )

        assertFalse(result.isSupported)
    }

    @Test
    fun characteristicPresentUnderTheRightUuidButWithoutTheRequiredPropertyIsNotSupported() {
        // The right UUID, but READ-only: it exists, yet cannot actually be
        // subscribed to, so it must not count as the notify characteristic.
        val result = CxServiceCheck.check(
            service(
                characteristic(CxBleUuids.NOTIFY, BluetoothGattCharacteristic.PROPERTY_READ),
                characteristic(CxBleUuids.WRITE, BluetoothGattCharacteristic.PROPERTY_WRITE),
            ),
        )

        assertFalse(result.isSupported)
    }

    @Test
    fun writeCharacteristicPresentButWithoutWriteOrWriteNoResponseIsNotSupported() {
        // The right UUID, but READ-only: it exists, yet cannot actually be
        // written to, so it must not count as the write characteristic.
        val result = CxServiceCheck.check(
            service(
                characteristic(CxBleUuids.NOTIFY, BluetoothGattCharacteristic.PROPERTY_NOTIFY),
                characteristic(CxBleUuids.WRITE, BluetoothGattCharacteristic.PROPERTY_READ),
            ),
        )

        assertFalse(result.isSupported)
    }

    @Test
    fun serviceWithNoCharacteristicsAtAllIsNotSupported() {
        val result = CxServiceCheck.check(service())

        assertFalse(result.isSupported)
    }
}
