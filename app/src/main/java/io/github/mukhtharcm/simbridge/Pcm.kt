package io.github.mukhtharcm.simbridge

import kotlin.math.sqrt

/** Call audio everywhere in simbridge: 16 kHz, mono, signed 16-bit, 20 ms frames. */
object Pcm {
    const val RATE = 16000
    const val FRAME = RATE / 50

    fun rms(frame: ShortArray, n: Int = frame.size): Int {
        if (n == 0) return 0
        var sum = 0.0
        for (i in 0 until n) sum += frame[i].toDouble() * frame[i]
        return sqrt(sum / n).toInt()
    }

    fun toStereo(mono: ShortArray): ShortArray {
        val out = ShortArray(mono.size * 2)
        for (i in mono.indices) { out[2 * i] = mono[i]; out[2 * i + 1] = mono[i] }
        return out
    }
}

/**
 * Holds audio from a client for a moment before it is played, so frames that arrive unevenly over
 * the network still play evenly. It waits for [prebuffer] frames before starting, and starts over
 * after running dry. When [capacity] is reached the oldest frame goes, which bounds the delay.
 */
class JitterBuffer(private val prebuffer: Int = 4, private val capacity: Int = 50) {
    private val frames = ArrayDeque<ShortArray>()
    private var playing = false
    var played = 0; private set
    var gaps = 0; private set
    var dropped = 0; private set

    @Synchronized fun put(frame: ShortArray) {
        if (frames.size == capacity) { frames.removeFirst(); dropped++ }
        frames.addLast(frame)
    }

    /** The next frame to play, or null when silence should be played instead. */
    @Synchronized fun take(): ShortArray? {
        if (!playing) {
            if (frames.size < prebuffer) return null
            playing = true
        }
        val frame = frames.removeFirstOrNull()
        if (frame == null) { playing = false; gaps++; return null }
        played++
        return frame
    }

    @Synchronized fun reset() { frames.clear(); playing = false; played = 0; gaps = 0; dropped = 0 }

    @Synchronized override fun toString() = "played=$played gaps=$gaps dropped=$dropped"
}
