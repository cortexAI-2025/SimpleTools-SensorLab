package com.simpletools.sensorlab.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp
import com.simpletools.sensorlab.core.sensors.SensorKind

/** Small stroke icons drawn here so no icon library is needed. Tinted by the caller. */
object SensorIcons {
    private fun icon(name: String, block: PathBuilder.() -> Unit): ImageVector =
        ImageVector.Builder(name, 24.dp, 24.dp, 24f, 24f).apply {
            path(
                stroke = SolidColor(Color.Black), strokeLineWidth = 1.8f,
                strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = block,
            )
        }.build()

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, true, true, cx + r, cy)
        arcTo(r, r, 0f, true, true, cx - r, cy)
    }

    private val accel = icon("accel") { // phone with motion arrows
        moveTo(8f, 4f); lineTo(16f, 4f); lineTo(16f, 20f); lineTo(8f, 20f); close()
        moveTo(2.5f, 9f); lineTo(5.5f, 12f); lineTo(2.5f, 15f)
        moveTo(21.5f, 9f); lineTo(18.5f, 12f); lineTo(21.5f, 15f)
    }
    private val linear = icon("linear") {
        moveTo(3f, 12f); lineTo(21f, 12f); moveTo(16f, 7f); lineTo(21f, 12f); lineTo(16f, 17f)
        moveTo(3f, 7f); lineTo(3f, 17f)
    }
    private val gyro = icon("gyro") {
        moveTo(20f, 12f); arcTo(8f, 8f, 0f, true, true, 12f, 4f)
        moveTo(12f, 4f); lineTo(15.5f, 3f); moveTo(12f, 4f); lineTo(13f, 7.5f)
        circle(12f, 12f, 1.5f)
    }
    private val magnet = icon("magnet") {
        moveTo(5f, 4f); lineTo(5f, 12f); arcTo(7f, 7f, 0f, false, false, 19f, 12f); lineTo(19f, 4f)
        moveTo(5f, 4f); lineTo(9f, 4f); lineTo(9f, 12f); arcTo(3f, 3f, 0f, false, false, 15f, 12f); lineTo(15f, 4f); lineTo(19f, 4f)
    }
    private val light = icon("light") {
        circle(12f, 12f, 4f)
        moveTo(12f, 2.5f); lineTo(12f, 5f); moveTo(12f, 19f); lineTo(12f, 21.5f)
        moveTo(2.5f, 12f); lineTo(5f, 12f); moveTo(19f, 12f); lineTo(21.5f, 12f)
        moveTo(5.3f, 5.3f); lineTo(7f, 7f); moveTo(17f, 17f); lineTo(18.7f, 18.7f)
        moveTo(5.3f, 18.7f); lineTo(7f, 17f); moveTo(17f, 7f); lineTo(18.7f, 5.3f)
    }
    private val pressure = icon("pressure") {
        circle(12f, 12f, 9f)
        moveTo(12f, 12f); lineTo(16f, 8f)
        moveTo(6f, 15f); lineTo(7.2f, 14.4f); moveTo(18f, 15f); lineTo(16.8f, 14.4f)
    }
    private val temperature = icon("temperature") {
        moveTo(10f, 14f); lineTo(10f, 5f); arcTo(2f, 2f, 0f, false, true, 14f, 5f); lineTo(14f, 14f)
        arcTo(4f, 4f, 0f, true, true, 10f, 14f)
        moveTo(12f, 10f); lineTo(12f, 16f)
    }
    private val level = icon("level") {
        moveTo(3f, 8f); lineTo(21f, 8f); lineTo(21f, 16f); lineTo(3f, 16f); close()
        circle(12f, 12f, 2f)
        moveTo(8f, 8f); lineTo(8f, 10.5f); moveTo(16f, 8f); lineTo(16f, 10.5f)
    }
    private val vibration = icon("vibration") {
        moveTo(2f, 12f); lineTo(5f, 12f); lineTo(7f, 5f); lineTo(10f, 19f); lineTo(13f, 7f)
        lineTo(15.5f, 16f); lineTo(17.5f, 12f); lineTo(22f, 12f)
    }

    val compare = icon("compare") {
        moveTo(2f, 9f); lineTo(5f, 9f); lineTo(7f, 4f); lineTo(10f, 14f); lineTo(13f, 7f); lineTo(22f, 7f)
        moveTo(2f, 17f); lineTo(6f, 17f); lineTo(9f, 12f); lineTo(12f, 20f); lineTo(15f, 15f); lineTo(22f, 15f)
    }

    fun of(kind: SensorKind): ImageVector = when (kind) {
        SensorKind.ACCELEROMETER -> accel
        SensorKind.LINEAR_ACCELERATION -> linear
        SensorKind.GYROSCOPE -> gyro
        SensorKind.MAGNETOMETER -> magnet
        SensorKind.LIGHT -> light
        SensorKind.PRESSURE -> pressure
        SensorKind.AMBIENT_TEMPERATURE -> temperature
        SensorKind.INCLINOMETER -> level
        SensorKind.VIBRATION -> vibration
    }
}
