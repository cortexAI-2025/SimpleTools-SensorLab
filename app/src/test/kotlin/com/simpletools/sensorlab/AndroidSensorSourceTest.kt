package com.simpletools.sensorlab

import android.app.Application
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.test.core.app.ApplicationProvider
import com.simpletools.sensorlab.core.sensors.AndroidSensorSource
import com.simpletools.sensorlab.core.sensors.SensorKind
import com.simpletools.sensorlab.core.sensors.SensorUnavailableException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSensor
import org.robolectric.shadows.ShadowSensorManager

@RunWith(RobolectricTestRunner::class)
class AndroidSensorSourceTest {
    private val context: Application = ApplicationProvider.getApplicationContext()
    private val sm get() = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager

    private fun addSensor(type: Int): Sensor {
        val sensor = ShadowSensor.newInstance(type)
        shadowOf(sm).addSensor(sensor)
        return sensor
    }

    // 1. detection of available sensors
    @Test fun detectsOnlyTheSensorsThePhoneReports() {
        addSensor(Sensor.TYPE_ACCELEROMETER)
        addSensor(Sensor.TYPE_LIGHT)
        val inv = AndroidSensorSource(context).inventory().associateBy { it.kind }
        assertEquals(SensorKind.entries.size, inv.size)
        assertTrue(inv.getValue(SensorKind.ACCELEROMETER).available)
        assertTrue(inv.getValue(SensorKind.LIGHT).available)
        assertTrue(inv.getValue(SensorKind.VIBRATION).available) // built on the accelerometer
        assertEquals(Sensor.TYPE_ACCELEROMETER, inv.getValue(SensorKind.ACCELEROMETER).androidType)
    }

    // 2. absence of a sensor
    @Test fun missingSensorsAreReportedAbsentNotAssumed() {
        addSensor(Sensor.TYPE_ACCELEROMETER)
        val inv = AndroidSensorSource(context).inventory().associateBy { it.kind }
        for (k in listOf(SensorKind.GYROSCOPE, SensorKind.MAGNETOMETER, SensorKind.PRESSURE, SensorKind.AMBIENT_TEMPERATURE, SensorKind.LINEAR_ACCELERATION, SensorKind.LIGHT)) {
            assertFalse("$k must be absent", inv.getValue(k).available)
            assertNull(inv.getValue(k).androidType)
            assertNull(inv.getValue(k).name)
        }
    }

    @Test fun phoneWithoutAnySensorHasNoAvailableModule() {
        val inv = AndroidSensorSource(context).inventory()
        assertTrue(inv.none { it.available })
    }

    @Test fun inclinometerPrefersRotationVectorAndFallsBackToGravity() {
        addSensor(Sensor.TYPE_ACCELEROMETER)
        var incl = AndroidSensorSource(context).inventory().first { it.kind == SensorKind.INCLINOMETER }
        assertTrue(incl.available); assertTrue(incl.usesFallback)
        assertEquals("gravity-tilt", incl.method)
        assertEquals(Sensor.TYPE_ACCELEROMETER, incl.androidType)

        addSensor(Sensor.TYPE_ROTATION_VECTOR)
        incl = AndroidSensorSource(context).inventory().first { it.kind == SensorKind.INCLINOMETER }
        assertFalse(incl.usesFallback)
        assertEquals("rotation-vector-tilt", incl.method)
        assertEquals(Sensor.TYPE_ROTATION_VECTOR, incl.androidType)
    }

    @Test fun inclinometerIsAbsentWithoutAnyUsableSensor() {
        assertFalse(AndroidSensorSource(context).inventory().first { it.kind == SensorKind.INCLINOMETER }.available)
    }

    @Test fun hardwareListExposesRealSensors() {
        addSensor(Sensor.TYPE_GYROSCOPE)
        assertTrue(AndroidSensorSource(context).hardwareSensors().any { it.androidType == Sensor.TYPE_GYROSCOPE })
    }

    // 20. hardware errors
    @Test fun streamingAnAbsentSensorFailsExplicitly() {
        val source = AndroidSensorSource(context)
        val info = source.inventory().first { it.kind == SensorKind.GYROSCOPE }
        try {
            runBlocking { source.stream(info.copy(androidType = Sensor.TYPE_GYROSCOPE), 20_000).first() }
            fail("expected SensorUnavailableException")
        } catch (e: SensorUnavailableException) {
            assertNotNull(e.message)
        }
    }

    @Test fun streamDeliversRealEventsAndCopiesTheirValues() {
        val sensor = addSensor(Sensor.TYPE_ACCELEROMETER)
        val source = AndroidSensorSource(context)
        val info = source.inventory().first { it.kind == SensorKind.ACCELEROMETER }
        val event = ShadowSensorManager.createSensorEvent(3).apply {
            this.sensor = sensor
            values[0] = 0.1f; values[1] = 0.2f; values[2] = 9.81f
            timestamp = 123_456_789L
            accuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
        }
        val got = runBlocking {
            withTimeout(5_000) {
                val d = async(kotlinx.coroutines.Dispatchers.Default) { source.stream(info, 20_000).first() }
                while (!d.isCompleted) { shadowOf(sm).sendSensorEventToListeners(event); delay(20) }
                d.await()
            }
        }
        assertEquals(123_456_789L, got.timestampNs)
        assertEquals(9.81f, got.values[2], 0f)
        assertEquals(SensorManager.SENSOR_STATUS_ACCURACY_HIGH, got.accuracy)
    }
}
