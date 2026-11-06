package com.simpletools.sensorlab.core.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.callbackFlow

/** Production [SensorSource] built on the official SensorManager API. */
class AndroidSensorSource(context: Context) : SensorSource {

    private val sensorManager: SensorManager? =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager

    override fun inventory(): List<SensorInfo> = SensorKind.entries.map { resolve(it) }

    private fun resolve(kind: SensorKind): SensorInfo {
        val sm = sensorManager ?: return SensorInfo(kind, available = false)
        val primary = sm.getDefaultSensor(kind.androidType)
        if (primary != null) return primary.toInfo(kind, usesFallback = false)
        val fallbackType = kind.fallbackAndroidType
        val fallback = fallbackType?.let { sm.getDefaultSensor(it) }
        if (fallback != null) {
            return fallback.toInfo(kind, usesFallback = true).copy(method = "gravity-tilt")
        }
        return SensorInfo(kind, available = false)
    }

    private fun Sensor.toInfo(kind: SensorKind, usesFallback: Boolean) = SensorInfo(
        kind = kind,
        available = true,
        androidType = type,
        name = name.orEmpty(),
        vendor = vendor.orEmpty(),
        resolution = resolution,
        maxRange = maximumRange,
        powerMilliAmp = power,
        minDelayUs = minDelay,
        reportingMode = when (reportingMode) {
            Sensor.REPORTING_MODE_CONTINUOUS -> ReportingMode.CONTINUOUS
            Sensor.REPORTING_MODE_ON_CHANGE -> ReportingMode.ON_CHANGE
            Sensor.REPORTING_MODE_ONE_SHOT -> ReportingMode.ONE_SHOT
            else -> ReportingMode.SPECIAL
        },
        usesFallback = usesFallback,
    )

    override fun hardwareSensors(): List<HardwareSensor> =
        sensorManager?.getSensorList(Sensor.TYPE_ALL).orEmpty().map {
            HardwareSensor(it.name.orEmpty(), it.vendor.orEmpty(), it.type, it.stringType.orEmpty())
        }

    override fun stream(info: SensorInfo, samplingPeriodUs: Int): Flow<RawSample> = callbackFlow {
        val sm = sensorManager ?: throw SensorUnavailableException("SensorManager unavailable")
        val type = info.androidType
        val sensor = type?.let { sm.getDefaultSensor(it) }
            ?: throw SensorUnavailableException("Sensor ${info.kind.key} not present")

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                // Copy: Android reuses the values array between events.
                trySend(RawSample(event.timestamp, event.values.copyOf(), event.accuracy))
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        }
        val registered = try {
            sm.registerListener(listener, sensor, samplingPeriodUs)
        } catch (e: SecurityException) {
            throw SensorUnavailableException("Sensor access refused by the system", e)
        }
        if (!registered) throw SensorUnavailableException("Sensor registration refused")
        awaitClose { sm.unregisterListener(listener) }
    }.buffer(capacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
}
