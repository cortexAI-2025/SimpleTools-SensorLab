package com.simpletools.sensorlab

import com.simpletools.sensorlab.core.charts.NiceScale
import com.simpletools.sensorlab.core.measurement.DisplayUnits
import com.simpletools.sensorlab.core.measurement.ExponentialSmoother
import com.simpletools.sensorlab.core.measurement.FieldVariationLevel
import com.simpletools.sensorlab.core.measurement.FieldVariationTracker
import com.simpletools.sensorlab.core.measurement.HighPassFilter
import com.simpletools.sensorlab.core.measurement.LightCategory
import com.simpletools.sensorlab.core.measurement.MultiStats
import com.simpletools.sensorlab.core.measurement.Orientation
import com.simpletools.sensorlab.core.measurement.RunningStats
import com.simpletools.sensorlab.core.measurement.SampleWindow
import com.simpletools.sensorlab.core.measurement.SamplingRateEstimator
import com.simpletools.sensorlab.core.measurement.Stats
import com.simpletools.sensorlab.core.measurement.Units
import com.simpletools.sensorlab.core.measurement.WindowedRms
import com.simpletools.sensorlab.core.sensors.SensorKind
import com.simpletools.sensorlab.core.sensors.VectorMath
import com.simpletools.sensorlab.core.settings.AngularUnit
import com.simpletools.sensorlab.core.settings.AppSettings
import com.simpletools.sensorlab.core.settings.PressureUnit
import com.simpletools.sensorlab.core.settings.TemperatureUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt

class MeasurementMathTest {

    // 3. unit conversions
    @Test fun unitConversions() {
        assertEquals(180.0, Units.radToDeg(PI), 1e-9)
        assertEquals(PI, Units.degToRad(180.0), 1e-9)
        assertEquals(760.0, Units.hpaToMmHg(1013.25), 0.1)
        assertEquals(1013.25, Units.mmHgToHpa(Units.hpaToMmHg(1013.25)), 1e-9)
        assertEquals(98.6, Units.celsiusToFahrenheit(37.0), 1e-9)
        assertEquals(37.0, Units.fahrenheitToCelsius(98.6), 1e-9)
        assertEquals(1.0, Units.mpsToG(9.80665), 1e-12)
    }

    @Test fun displayUnitsFollowSettings() {
        val s = AppSettings(angularUnit = AngularUnit.DEG_PER_S, pressureUnit = PressureUnit.MMHG, temperatureUnit = TemperatureUnit.FAHRENHEIT)
        val u = DisplayUnits(s)
        assertEquals("°/s", u.unit(SensorKind.GYROSCOPE))
        assertEquals(180.0, u.convert(SensorKind.GYROSCOPE, PI), 1e-9)
        assertEquals("mmHg", u.unit(SensorKind.PRESSURE))
        assertEquals("°F", u.unit(SensorKind.AMBIENT_TEMPERATURE))
        assertEquals(32.0, u.convert(SensorKind.AMBIENT_TEMPERATURE, 0.0), 1e-9)
        // untouched kinds keep their native unit
        assertEquals("m/s²", u.unit(SensorKind.ACCELEROMETER))
        assertEquals(5.0, u.convert(SensorKind.ACCELEROMETER, 5.0), 0.0)
        assertEquals("—", u.format(SensorKind.LIGHT, Double.NaN))
    }

    // 4. vector norm
    @Test fun vectorNorm() {
        assertEquals(5f, VectorMath.norm(3f, 4f, 0f), 1e-6f)
        assertEquals(9.80665f, VectorMath.norm(0f, 0f, 9.80665f), 1e-5f)
        assertEquals(sqrt(3f), VectorMath.norm(1f, 1f, 1f), 1e-6f)
        assertEquals(5f, SensorKind.ACCELEROMETER.scalarOf(floatArrayOf(3f, 0f, 4f)), 1e-6f)
        assertEquals(12.5f, SensorKind.LIGHT.scalarOf(floatArrayOf(12.5f)), 0f)
        assertEquals(7f, SensorKind.INCLINOMETER.scalarOf(floatArrayOf(1f, 2f, 7f)), 0f) // headline = tilt
    }

    // 5/6/7. mean, RMS, min, max
    @Test fun runningStats() {
        val s = RunningStats()
        listOf(1.0, -2.0, 3.0, 4.0).forEach(s::add)
        val r = s.snapshot()
        assertEquals(4, r.count)
        assertEquals(-2.0, r.min, 0.0)
        assertEquals(4.0, r.max, 0.0)
        assertEquals(1.5, r.mean, 1e-12)
        assertEquals(sqrt((1 + 4 + 9 + 16) / 4.0), r.rms, 1e-12)
        assertEquals(Stats.rms(doubleArrayOf(1.0, -2.0, 3.0, 4.0)), r.rms, 1e-12)
        assertEquals(1.5, Stats.mean(doubleArrayOf(1.0, -2.0, 3.0, 4.0)), 1e-12)
    }

    @Test fun statsIgnoreNonFiniteAndHandleEmpty() {
        val s = RunningStats()
        assertTrue(s.snapshot().isEmpty)
        assertTrue(s.snapshot().mean.isNaN())
        s.add(Double.NaN); s.add(Double.POSITIVE_INFINITY); s.add(2.0)
        assertEquals(1, s.snapshot().count)
        s.reset()
        assertTrue(s.snapshot().isEmpty)
    }

    @Test fun multiStatsTracksEachChannelAndScalar() {
        val m = MultiStats(3)
        m.add(floatArrayOf(3f, 0f, 0f), 3f)
        m.add(floatArrayOf(-3f, 4f, 0f), 5f)
        assertEquals(-3.0, m.channels[0].snapshot().min, 0.0)
        assertEquals(4.0, m.channels[1].snapshot().max, 0.0)
        assertEquals(4.0, m.scalar.snapshot().mean, 1e-12)
    }

    // 8. filtering
    @Test fun highPassRemovesConstantComponent() {
        val f = HighPassFilter(1, cutoffHz = 1.0)
        var last = 0f
        for (i in 0 until 500) last = f.process(i * 10_000_000L, floatArrayOf(9.81f))[0] // 100 Hz, constant "gravity"
        assertEquals(0f, last, 0.01f)
    }

    @Test fun highPassPassesFastOscillationAndBlocksSlowOne() {
        fun peak(freq: Double): Double {
            val f = HighPassFilter(1, cutoffHz = 1.0)
            var peak = 0.0
            for (i in 0 until 2000) {
                val t = i * 0.01
                val y = f.process((t * 1e9).toLong(), floatArrayOf(sin(2 * PI * freq * t).toFloat()))[0]
                if (i > 1000) peak = maxOf(peak, abs(y.toDouble()))
            }
            return peak
        }
        assertTrue("20 Hz should pass", peak(20.0) > 0.9)
        assertTrue("0.1 Hz should be attenuated", peak(0.1) < 0.2)
    }

    @Test fun highPassFirstSampleIsZeroAndResetWorks() {
        val f = HighPassFilter(3, 1.0)
        assertEquals(0f, f.process(0L, floatArrayOf(1f, 2f, 3f))[2], 0f)
        f.process(10_000_000L, floatArrayOf(5f, 5f, 5f))
        f.reset()
        assertEquals(0f, f.process(20_000_000L, floatArrayOf(9f, 9f, 9f))[0], 0f)
    }

    @Test fun exponentialSmootherConvergesAndStartsAtFirstValue() {
        val s = ExponentialSmoother(1, 0.1)
        assertEquals(10f, s.process(0L, floatArrayOf(10f))[0], 0f)
        var y = 0f
        for (i in 1..200) y = s.process(i * 10_000_000L, floatArrayOf(0f))[0]
        assertEquals(0f, y, 0.01f)
    }

    @Test fun windowedRmsForgetsOldSamples() {
        val w = WindowedRms(1_000_000_000L)
        var r = 0.0
        for (i in 0..100) r = w.add(i * 10_000_000L, 2.0) // 1 s of constant 2
        assertEquals(2.0, r, 1e-9)
        for (i in 101..300) r = w.add(i * 10_000_000L, 0.0) // 2 s of zeros: window now only zeros
        assertEquals(0.0, r, 1e-6)
    }

    // 9. sampling rate
    @Test fun samplingRateEstimator() {
        val e = SamplingRateEstimator(50)
        assertNull(e.hz)
        e.add(0L)
        assertNull(e.hz)
        for (i in 1..100) e.add(i * 20_000_000L) // 50 Hz
        assertEquals(50.0, e.hz!!, 0.01)
        e.reset(); assertNull(e.hz)
    }

    // inclinometer maths
    @Test fun orientationFlatAndTilted() {
        val flat = Orientation.upFromGravity(floatArrayOf(0f, 0f, 9.81f))!!
        assertEquals(0.0, Orientation.tiltDeg(flat), 1e-4)
        assertEquals(0.0, Orientation.pitchDeg(flat), 1e-4)
        val upright = Orientation.upFromGravity(floatArrayOf(0f, 9.81f, 0f))!! // portrait, held vertically
        assertEquals(90.0, Orientation.tiltDeg(upright), 1e-3)
        assertEquals(90.0, Orientation.pitchDeg(upright), 1e-3)
        val rolled = Orientation.upFromGravity(floatArrayOf(9.81f * 0.5f, 0f, 9.81f * 0.866f))!!
        assertEquals(30.0, Orientation.rollDeg(rolled), 0.1)
        assertEquals(30.0, Orientation.tiltDeg(rolled), 0.1)
        assertNull(Orientation.upFromGravity(floatArrayOf(0f, 0f, 0f)))
    }

    @Test fun orientationFromRotationVectorIdentityAndRotation() {
        val identity = Orientation.upFromRotationVector(floatArrayOf(0f, 0f, 0f, 1f))!!
        assertEquals(0.0, Orientation.tiltDeg(identity), 1e-4)
        // 90° about the device X axis: quaternion (sin45, 0, 0, cos45) => the device stands on its edge
        val s = sin(PI / 4).toFloat()
        val rotated = Orientation.upFromRotationVector(floatArrayOf(s, 0f, 0f, s))!!
        assertEquals(90.0, Orientation.tiltDeg(rotated), 1e-3)
        // w omitted (3-value vector) must give the same result
        val noW = Orientation.upFromRotationVector(floatArrayOf(s, 0f, 0f))!!
        assertEquals(90.0, Orientation.tiltDeg(noW), 0.1)
        assertNull(Orientation.upFromRotationVector(floatArrayOf(0f, 0f)))
    }

    @Test fun calibrationMakesReferenceSurfaceZero() {
        val reference = Orientation.upFromGravity(floatArrayOf(0f, 9.81f * 0.1f, 9.81f * 0.995f))!!
        val angles = Orientation.angles(reference, reference)
        assertEquals(0.0, angles[0].toDouble(), 1e-3)
        assertEquals(0.0, angles[1].toDouble(), 1e-3)
        assertEquals(0.0, angles[2].toDouble(), 0.05)
        val raw = Orientation.angles(reference, null)
        assertTrue(raw[2] > 5f)
    }

    @Test fun magneticVariationIsDescriptive() {
        val t = FieldVariationTracker()
        var level = FieldVariationLevel.STABLE
        for (i in 0 until 100) level = t.add(i * 20_000_000L, 48.0)
        assertEquals(FieldVariationLevel.STABLE, level)
        level = t.add(100 * 20_000_000L, 70.0)
        assertEquals(FieldVariationLevel.STRONG, level)
        assertTrue(t.deviationUt > 10)
        assertEquals(FieldVariationLevel.MODERATE, t.levelOf(5.0))
    }

    @Test fun lightCategoriesAreOrdered() {
        assertEquals(LightCategory.VERY_DARK, LightCategory.of(0.2))
        assertEquals(LightCategory.NORMAL_INDOOR, LightCategory.of(300.0))
        assertEquals(LightCategory.FULL_DAYLIGHT, LightCategory.of(50_000.0))
    }

    @Test fun sampleWindowIsBoundedByAgeAndCount() {
        val w = SampleWindow(1, maxPoints = 100)
        for (i in 0 until 10_000) w.add(i * 0.01, floatArrayOf(i.toFloat()), windowS = 30.0)
        assertTrue(w.size <= 100)
        val snap = w.snapshot(nowS = 99.99, maxOut = 20)
        assertTrue(snap.t.size <= 20)
        assertTrue(snap.t.all { it <= 0f })
        val aged = SampleWindow(1)
        for (i in 0 until 1000) aged.add(i * 0.1, floatArrayOf(0f), windowS = 10.0)
        assertTrue(aged.size in 100..102)
    }

    @Test fun niceScaleProducesRoundTicks() {
        val s = NiceScale.compute(0.13, 9.87)
        assertTrue(s.min <= 0.13 && s.max >= 9.87)
        assertTrue(s.ticks.size in 3..10)
        val flat = NiceScale.compute(5.0, 5.0)
        assertTrue(flat.max > flat.min)
        assertNotNull(NiceScale.compute(Double.NaN, 1.0))
        assertFalse(NiceScale.compute(-1.0, 1.0).ticks.isEmpty())
    }
}
