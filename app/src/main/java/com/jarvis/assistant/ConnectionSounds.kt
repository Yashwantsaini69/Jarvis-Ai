package com.jarvis.assistant

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

object ConnectionSounds {
    private const val SAMPLE_RATE = 22050
    private const val AMPLITUDE = 0.30

    fun playConnected() = play(renderTone(rising = true))
    fun playDisconnected() = play(renderTone(rising = false))

    private fun play(samples: ShortArray) {
        Thread({
            try {
                val track = AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STATIC)
                    .setBufferSizeInBytes(samples.size * 2)
                    .build()
                track.write(samples, 0, samples.size)
                track.play()
                val holdMs = samples.size * 1000L / SAMPLE_RATE + 80L
                Thread.sleep(holdMs)
                try {
                    track.stop()
                } catch (_: Throwable) {
                }
                track.release()
            } catch (_: Throwable) {
            }
        }, "jarvis-chime").apply { isDaemon = true }.start()
    }

    private fun renderTone(rising: Boolean): ShortArray {
        val notes = if (rising) {
            doubleArrayOf(660.0, 990.0)
        } else {
            doubleArrayOf(880.0, 587.33)
        }
        val noteSeconds = doubleArrayOf(0.085, 0.115)
        val out = mutableListOf<Short>()
        for (index in notes.indices) {
            val freq = notes[index]
            val seconds = noteSeconds[index]
            val count = (SAMPLE_RATE * seconds).toInt()
            for (i in 0 until count) {
                val t = i.toDouble() / SAMPLE_RATE
                var sample = sin(2.0 * PI * freq * t)
                sample += 0.10 * sin(2.0 * PI * freq * 2.0 * t)
                sample += 0.04 * sin(2.0 * PI * freq * 3.0 * t)
                sample *= envelope(i, count)
                val scaled = (sample * AMPLITUDE * Short.MAX_VALUE).toInt().coerceIn(-32768, 32767)
                out.add(scaled.toShort())
            }
        }
        return out.toShortArray()
    }

    private fun envelope(index: Int, total: Int): Double {
        val attackSamples = (0.006 * SAMPLE_RATE).toInt()
        val releaseSamples = (0.09 * SAMPLE_RATE).toInt()
        val attack = if (index < attackSamples) {
            val t = index.toDouble() / attackSamples
            t * t * (3.0 - 2.0 * t)
        } else 1.0
        val remaining = total - index
        val release = if (remaining < releaseSamples) {
            val t = remaining.toDouble() / releaseSamples
            exp(-1.2 * (1.0 - t))
        } else 1.0
        return attack * release
    }
}
