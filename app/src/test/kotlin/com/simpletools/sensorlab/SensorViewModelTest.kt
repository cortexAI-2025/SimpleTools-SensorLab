package com.simpletools.sensorlab

import android.app.Application
import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.simpletools.sensorlab.core.database.AppDatabase
import com.simpletools.sensorlab.core.measurement.RecorderState
import com.simpletools.sensorlab.core.sensors.RawSample
import com.simpletools.sensorlab.core.sensors.SensorKind
import com.simpletools.sensorlab.feature.sensor.LiveStatus
import com.simpletools.sensorlab.feature.sensor.SensorViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class SensorViewModelTest {
    private val app: Application = ApplicationProvider.getApplicationContext<Context>() as Application
    private lateinit var dispatcher: TestDispatcher
    private lateinit var db: AppDatabase

    @Before fun setUp() {
        dispatcher = StandardTestDispatcher()
        Dispatchers.setMain(dispatcher)
        db = Room.inMemoryDatabaseBuilder(app, AppDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() { Dispatchers.resetMain(); db.close() }

    private fun container(source: SimulatedSensorSource) =
        AppContainer(app, source, { db }, FakeSettingsRepository())

    private fun TestDispatcher.settle() { scheduler.runCurrent() }

    /** Room completes on its own threads: pump the test dispatcher until the condition holds. */
    private fun awaitUntil(what: String, cond: () -> Boolean) {
        val end = System.currentTimeMillis() + 5_000
        while (!cond()) {
            dispatcher.scheduler.runCurrent()
            if (System.currentTimeMillis() > end) error("timeout waiting for $what")
            Thread.sleep(5)
        }
    }

    private fun emit(src: SimulatedSensorSource, n: Int, startMs: Int = 0, v: Float = 9.81f, stepMs: Int = 20) {
        for (i in 0 until n) src.samples.tryEmit(RawSample((startMs + i * stepMs) * 1_000_000L, floatArrayOf(0f, 0f, v), 3))
        dispatcher.settle()
    }

    @Test fun liveValuesAppearOnlyWhenRealSamplesArrive() {
        val src = SimulatedSensorSource(setOf(SensorKind.ACCELEROMETER))
        val vm = SensorViewModel(SensorKind.ACCELEROMETER, container(src))
        dispatcher.settle()
        assertTrue(vm.ui.value.values == null)           // nothing is shown before the sensor reports
        vm.onStart(); dispatcher.settle()
        assertEquals(LiveStatus.WAITING, vm.ui.value.status)
        emit(src, 10)
        dispatcher.scheduler.advanceTimeBy(200)
        val s = vm.ui.value
        assertEquals(LiveStatus.LIVE, s.status)
        assertEquals(9.81f, s.scalar, 1e-4f)
        assertEquals(50.0, s.observedHz!!, 0.5)
        assertEquals(10, s.scalarStats.count)
        assertTrue(s.window!!.t.isNotEmpty())
        vm.onStop(false)
    }

    // power: listeners are unregistered when the screen stops
    @Test fun streamIsClosedWhenTheScreenStops() {
        val src = SimulatedSensorSource(setOf(SensorKind.ACCELEROMETER))
        val vm = SensorViewModel(SensorKind.ACCELEROMETER, container(src))
        vm.onStart(); dispatcher.settle()
        assertEquals(1, src.streamsOpened)
        vm.onStop(configChange = false); dispatcher.settle()
        assertEquals(1, src.streamsClosed)
        assertEquals(LiveStatus.STOPPED, vm.ui.value.status)
        vm.onStart(); dispatcher.settle()
        assertEquals(2, src.streamsOpened)
        vm.onStop(false)
    }

    // 19. screen rotation
    @Test fun rotationKeepsMeasurementAndRecordingRunning() {
        val src = SimulatedSensorSource(setOf(SensorKind.ACCELEROMETER))
        val vm = SensorViewModel(SensorKind.ACCELEROMETER, container(src))
        vm.onStart(); dispatcher.settle()
        emit(src, 5, stepMs = 100)
        vm.startRecording("Rotation"); awaitUntil("recording") { vm.ui.value.recording.state == RecorderState.RECORDING }
        // activity recreated: stop(config change) then start again
        vm.onStop(configChange = true); dispatcher.settle()
        assertEquals(0, src.streamsClosed)                       // sensor stream not interrupted
        vm.onStart(); dispatcher.settle()
        assertEquals(1, src.streamsOpened)                       // and not opened twice
        assertEquals(RecorderState.RECORDING, vm.ui.value.recording.state)
        emit(src, 5, startMs = 1000, stepMs = 100)
        dispatcher.scheduler.advanceTimeBy(100)
        assertEquals(5, vm.ui.value.recording.samples)
        vm.onStop(false)
    }

    // 11/12 through the view model: pause on background, resume, finish
    @Test fun leavingTheScreenPausesRecordingAndFinishPersistsTheSession() {
        val src = SimulatedSensorSource(setOf(SensorKind.LIGHT))
        val c = container(src)
        val vm = SensorViewModel(SensorKind.LIGHT, c)
        vm.onStart(); dispatcher.settle()
        src.samples.tryEmit(RawSample(0, floatArrayOf(120f), 3)); dispatcher.settle()
        vm.startRecording("Lux"); awaitUntil("start") { vm.ui.value.recording.state == RecorderState.RECORDING }
        for (i in 1..30) src.samples.tryEmit(RawSample(i * 100_000_000L, floatArrayOf(100f + i), 3))
        dispatcher.settle()
        vm.onStop(configChange = false); dispatcher.settle()
        assertEquals(RecorderState.PAUSED, vm.ui.value.recording.state)
        vm.onStart(); dispatcher.settle()
        vm.resumeRecording()
        for (i in 31..40) src.samples.tryEmit(RawSample(i * 100_000_000L, floatArrayOf(100f + i), 3))
        dispatcher.settle()
        vm.finishRecording()
        awaitUntil("finished") { vm.ui.value.recording.state == RecorderState.FINISHED }
        dispatcher.scheduler.advanceTimeBy(100)
        val id = vm.ui.value.recording.savedSessionId
        assertNotNull(id)
        val saved = runBlocking { c.sessions.get(id!!) }!!
        assertEquals("COMPLETED", saved.status)
        assertEquals("light", saved.kindKey)
        assertTrue(saved.sampleCount >= 35)
        assertEquals(runBlocking { c.sessions.observeAll().first() }.size, 1)
        vm.onStop(false)
    }

    // 2. absent sensor
    @Test fun absentSensorNeverStartsAStreamNorShowsValues() {
        val src = SimulatedSensorSource(emptySet())
        val vm = SensorViewModel(SensorKind.GYROSCOPE, container(src))
        vm.onStart(); dispatcher.settle()
        assertEquals(LiveStatus.UNAVAILABLE, vm.ui.value.status)
        assertFalse(vm.ui.value.info.available)
        assertEquals(0, src.streamsOpened)
        vm.startRecording("never"); dispatcher.settle()
        assertEquals(RecorderState.IDLE, vm.ui.value.recording.state)
    }

    // 20. hardware errors
    @Test fun hardwareFailureIsSurfacedAsAnErrorState() {
        val src = SimulatedSensorSource(setOf(SensorKind.MAGNETOMETER), failOnStream = true)
        val vm = SensorViewModel(SensorKind.MAGNETOMETER, container(src))
        vm.onStart(); dispatcher.settle()
        dispatcher.scheduler.advanceTimeBy(200)
        assertEquals(LiveStatus.ERROR, vm.ui.value.status)
        vm.onStop(false)
    }

    @Test fun inclinometerAndVibrationDeriveTheirChannelsFromRealSamples() {
        val src = SimulatedSensorSource(setOf(SensorKind.INCLINOMETER))
        val incl = SensorViewModel(SensorKind.INCLINOMETER, container(src))
        incl.onStart(); dispatcher.settle()
        src.samples.tryEmit(RawSample(0, floatArrayOf(0f, 0f, 9.81f), 3)); dispatcher.settle()
        dispatcher.scheduler.advanceTimeBy(100)
        assertEquals(0f, incl.ui.value.values!![2], 0.01f)       // lying flat => tilt 0°
        incl.calibrate()
        src.samples.tryEmit(RawSample(20_000_000, floatArrayOf(0f, 0f, 9.81f), 3)); dispatcher.settle()
        assertTrue(incl.ui.value.calibrated)
        incl.onStop(false)
    }
}
