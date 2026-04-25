package de.dervomsee.voice2txt.audio

import kotlin.math.PI
import kotlin.math.tan

/**
 * A simple 2nd order IIR Bandpass Filter using the Bilinear Transform.
 * Designed to filter audio to the human speech range (e.g., 300Hz - 3000Hz).
 */
class BandpassFilter(
    private val sampleRate: Double,
    private val centerFreq: Double,
    private val bandwidth: Double
) {
    private var b0 = 0.0
    private var b1 = 0.0
    private var b2 = 0.0
    private var a1 = 0.0
    private var a2 = 0.0

    private var x1 = 0.0
    private var x2 = 0.0
    private var y1 = 0.0
    private var y2 = 0.0

    init {
        val omega = 2.0 * PI * centerFreq / sampleRate
        val sn = kotlin.math.sin(omega)
        val cs = kotlin.math.cos(omega)
        val alpha = sn * kotlin.math.sinh(kotlin.math.log2(2.0) / 2.0 * bandwidth * omega / sn)

        val a0 = 1.0 + alpha
        b0 = alpha / a0
        b1 = 0.0
        b2 = -alpha / a0
        a1 = -2.0 * cs / a0
        a2 = (1.0 - alpha) / a0
    }

    fun process(input: Short): Short {
        val x0 = input.toDouble()
        val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2

        x2 = x1
        x1 = x0
        y2 = y1
        y1 = y0

        return y0.coerceIn(-32768.0, 32767.0).toInt().toShort()
    }

    fun reset() {
        x1 = 0.0
        x2 = 0.0
        y1 = 0.0
        y2 = 0.0
    }
}
