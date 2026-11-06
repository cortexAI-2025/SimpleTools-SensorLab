package com.simpletools.sensorlab.core.measurement

/**
 * Bounded history used by live charts. Old points are dropped both by age ([windowS]) and by
 * count ([maxPoints]), so memory stays constant however long the screen stays open.
 */
class SampleWindow(private val channels: Int, private val maxPoints: Int = 20_000) {
    private val times = ArrayDeque<Double>()
    private val values = ArrayDeque<FloatArray>()

    val size: Int get() = times.size

    fun clear() { times.clear(); values.clear() }

    fun add(tSeconds: Double, v: FloatArray, windowS: Double) {
        times.addLast(tSeconds); values.addLast(v)
        while (times.isNotEmpty() && (tSeconds - times.first() > windowS || times.size > maxPoints)) {
            times.removeFirst(); values.removeFirst()
        }
    }

    /** Drops points older than the window (e.g. when the user shrinks the window). */
    fun trim(nowS: Double, windowS: Double) {
        while (times.isNotEmpty() && nowS - times.first() > windowS) { times.removeFirst(); values.removeFirst() }
    }

    /**
     * Copy of the window as per-channel arrays, at most [maxOut] points (uniform decimation) so the
     * render cost does not depend on the acquisition rate. Times are relative to [nowS] (<= 0).
     */
    fun snapshot(nowS: Double, maxOut: Int = 600): WindowSnapshot {
        val n = times.size
        if (n == 0) return WindowSnapshot(FloatArray(0), List(channels) { FloatArray(0) })
        val stride = ((n + maxOut - 1) / maxOut).coerceAtLeast(1)
        val outN = (n + stride - 1) / stride
        val t = FloatArray(outN)
        val ch = List(channels) { FloatArray(outN) }
        var o = 0
        var i = 0
        while (i < n && o < outN) {
            t[o] = (times[i] - nowS).toFloat()
            val row = values[i]
            for (c in 0 until channels) ch[c][o] = row[c]
            o++; i += stride
        }
        return WindowSnapshot(t, ch)
    }
}

class WindowSnapshot(val t: FloatArray, val channels: List<FloatArray>)
