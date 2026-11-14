package com.jarvis.assistant

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.sqrt

class AudioEngine(
    private val onMicChunk: (ByteArray) -> Unit,
    private val onMicLevel: (Float) -> Unit,
    private val onPlaybackLevel: (Float) -> Unit
) {
    @Volatile
    private var capturing = false

    private var record: AudioRecord? = null
    private var captureThread: Thread? = null
    private val playback = Playback(onPlaybackLevel)

    fun startCapture(): Boolean {
        if (capturing) return true
        val audioRecord = createRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            ?: createRecord(MediaRecorder.AudioSource.MIC)
            ?: return false
        return try {
            audioRecord.startRecording()
            record = audioRecord
            capturing = true
            captureThread = Thread({ captureLoop(audioRecord) }, "jarvis-mic").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            true
        } catch (t: Throwable) {
            Log.w("JarvisAudio", "microphone start failed", t)
            try {
                audioRecord.release()
            } catch (_: Throwable) {
            }
            false
        }
    }

    fun stopCapture() {
        capturing = false
        val current = record
        record = null
        try {
            current?.stop()
        } catch (_: Throwable) {
        }
        try {
            current?.release()
        } catch (_: Throwable) {
        }
        try {
            captureThread?.join(500)
        } catch (_: Throwable) {
        }
        captureThread = null
    }

    fun play(data: ByteArray) {
        playback.enqueue(data)
    }

    fun interrupt() {
        playback.clear()
    }

    fun isPlaybackIdle(): Boolean = playback.isIdle()

    fun release() {
        stopCapture()
        playback.release()
    }

    private fun createRecord(source: Int): AudioRecord? {
        val minBuffer = AudioRecord.getMinBufferSize(
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return null
        val bufferSize = max(minBuffer * 2, 6400)
        return try {
            val created = AudioRecord(
                source,
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize
            )
            if (created.state == AudioRecord.STATE_INITIALIZED) {
                created
            } else {
                created.release()
                null
            }
        } catch (t: Throwable) {
            Log.w("JarvisAudio", "AudioRecord create failed", t)
            null
        }
    }

    private fun captureLoop(audioRecord: AudioRecord) {
        val buffer = ByteArray(3200)
        while (capturing) {
            val read = try {
                audioRecord.read(buffer, 0, buffer.size)
            } catch (_: Throwable) {
                -1
            }
            if (read > 0) {
                val chunk = buffer.copyOf(read)
                onMicChunk(chunk)
                onMicLevel(levelOf(chunk))
            } else if (read < 0) {
                break
            }
        }
    }

    private class Playback(private val onLevel: (Float) -> Unit) {
        private val queue = LinkedBlockingQueue<ByteArray>()

        @Volatile
        private var running = true

        @Volatile
        private var track: AudioTrack? = null

        @Volatile
        private var framesWritten = 0L

        private val worker = Thread({ loop() }, "jarvis-playback").apply {
            priority = Thread.MAX_PRIORITY
            isDaemon = true
            start()
        }

        fun enqueue(data: ByteArray) {
            if (running && data.isNotEmpty()) queue.offer(data)
        }

        fun clear() {
            queue.clear()
            framesWritten = 0L
            val t = track ?: return
            try {
                t.pause()
                t.flush()
                t.play()
            } catch (_: Throwable) {
            }
        }

        fun isIdle(): Boolean {
            if (!queue.isEmpty()) return false
            val t = track ?: return true
            val written = framesWritten
            val played = try {
                t.playbackHeadPosition.toLong() and 0xFFFFFFFFL
            } catch (_: Throwable) {
                return true
            }
            return played >= written - 1024L
        }

        fun release() {
            running = false
            queue.clear()
            try {
                worker.interrupt()
                worker.join(500)
            } catch (_: Throwable) {
            }
        }

        private fun loop() {
            val minBuffer = AudioTrack.getMinBufferSize(
                24000,
                AudioFormat.CHANNEL_OUT_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            val bufferSize = max(minBuffer * 2, 96000)
            val audioTrack = try {
                AudioTrack.Builder()
                    .setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_MEDIA)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    .setAudioFormat(
                        AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(24000)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .build()
                    )
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(bufferSize)
                    .build()
            } catch (t: Throwable) {
                Log.w("JarvisAudio", "AudioTrack create failed", t)
                return
            }
            track = audioTrack
            try {
                audioTrack.play()
            } catch (_: Throwable) {
            }
            while (running) {
                val chunk = try {
                    queue.poll(250, TimeUnit.MILLISECONDS)
                } catch (_: InterruptedException) {
                    null
                } ?: continue
                var offset = 0
                while (running && offset < chunk.size) {
                    val written = try {
                        audioTrack.write(chunk, offset, chunk.size - offset)
                    } catch (_: Throwable) {
                        -1
                    }
                    if (written <= 0) break
                    offset += written
                    framesWritten += written / 2
                }
                onLevel(levelOf(chunk))
            }
            try {
                audioTrack.pause()
                audioTrack.flush()
                audioTrack.release()
            } catch (_: Throwable) {
            }
        }
    }

    companion object {
        fun levelOf(data: ByteArray): Float {
            var sum = 0.0
            var count = 0
            var i = 0
            while (i + 1 < data.size) {
                val sample = ((data[i].toInt() and 0xFF) or (data[i + 1].toInt() shl 8)).toShort().toInt()
                sum += sample.toDouble() * sample.toDouble()
                count++
                i += 2
            }
            if (count == 0) return 0f
            val rms = sqrt(sum / count) / 32768.0
            return (rms * 3.6).coerceIn(0.0, 1.0).toFloat()
        }
    }
}
