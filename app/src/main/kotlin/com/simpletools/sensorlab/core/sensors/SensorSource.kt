package com.simpletools.sensorlab.core.sensors

import kotlinx.coroutines.flow.Flow

/**
 * Abstraction over the phone's sensors. The production implementation is [AndroidSensorSource];
 * tests provide simulated sources. Simulated data must never reach the production UI.
 */
interface SensorSource {
    /** Detects what the phone really has. Cheap; does not start any sensor. */
    fun inventory(): List<SensorInfo>

    /** Every sensor the OS lists (for the "all hardware sensors" view). */
    fun hardwareSensors(): List<HardwareSensor>

    /**
     * Cold flow of raw samples for [info]. The listener is registered when collection starts and
     * unregistered when it stops. Fails with [SensorUnavailableException] if the sensor is absent
     * or registration is refused.
     */
    fun stream(info: SensorInfo, samplingPeriodUs: Int): Flow<RawSample>
}
