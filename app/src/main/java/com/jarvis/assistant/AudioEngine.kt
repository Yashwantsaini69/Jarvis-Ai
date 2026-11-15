package com.jarvis.assistant

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.os.Build
import android.util.Log
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlin.math.max
import kotlin.math.sqrt

class AudioEngine(
    private val context: Context? = null,
    private val onMicChunk: (ByteArray) -> Unit,
    private val onMicLevel: (Float) -> Unit,
    private val onPlaybackLevel: (Float) -> Unit
) {
    @Volatile
    private var capturing = false

    @Volatile
    private var record: AudioRecord? = null
    private var captureThread: Thread? = null
    private var aec: AcousticEchoCanceler? = null
    private var audioManager: AudioManager? = null
    private var scoActive = false
    private val playback = Playback(onPlaybackLevel)

    fun startCapture(): Boolean {
        if (capturing) return true
        Diagnostics.log("AUDIO", "startCapture requested")
        activateBluetoothInput()
        val audioRecord = createRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION)
            ?: createRecord(MediaRecorder.AudioSource.MIC)
            ?: run {
                Diagnostics.log("AUDIO", "startCapture failed: no usable AudioRecord")
                releaseBluetoothInput()
                return false
            }
        return try {
            audioRecord.startRecording()
            record = audioRecord
            capturing = true
            captureThread = Thread({ captureLoop(audioRecord) }, "jarvis-mic").apply {
                priority = Thread.MAX_PRIORITY
                start()
            }
            Diagnostics.log("AUDIO", "recording started")
            true
        } catch (t: Throwable) {
            Log.w("JarvisAudio", "microphone start failed", t)
            Diagnostics.log("AUDIO", "startRecording failed: ${t.message ?: t.javaClass.simpleName}")
            try {
                audioRecord.release()
            } catch (_: Throwable) {
            }
            releaseBluetoothInput()
            false
        }
    }

    fun stopCapture() {
        if (capturing) Diagnostics.log("AUDIO", "stopCapture")
        capturing = false
        val current = record
        record = null
        try {
            aec?.enabled = false
            aec?.release()
        } catch (_: Throwable) {
        }
        aec = null
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
        releaseBluetoothInput()
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
        val sourceName = sourceName(source)
        Diagnostics.log("AUDIO", "trying $sourceName 16000 mono pcm16")
        val minBuffer = AudioRecord.getMinBufferSize(
            16000,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            Diagnostics.log("AUDIO", "$sourceName minBuffer=$minBuffer")
            return null
        }
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
                Diagnostics.log("AUDIO", "$sourceName initialized buffer=$bufferSize")
                Diagnostics.setMicSource("$sourceName/16000/mono/pcm16")
                enableAec(created)
                preferBluetoothInput(created)
                created
            } else {
                Diagnostics.log("AUDIO", "$sourceName not initialized state=${created.state}")
                created.release()
                null
            }
        } catch (t: Throwable) {
            Log.w("JarvisAudio", "AudioRecord create failed", t)
            Diagnostics.log("AUDIO", "$sourceName create failed: ${t.message ?: t.javaClass.simpleName}")
            null
        }
    }

    private fun enableAec(audioRecord: AudioRecord) {
        try {
            if (!AcousticEchoCanceler.isAvailable()) {
                Diagnostics.log("AUDIO", "AEC not available")
                return
            }
            aec?.release()
            aec = AcousticEchoCanceler.create(audioRecord.audioSessionId)?.apply { enabled = true }
            Diagnostics.log("AUDIO", "AEC enabled=${aec?.enabled == true}")
        } catch (t: Throwable) {
            Diagnostics.log("AUDIO", "AEC setup failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun audioManager(): AudioManager? {
        audioManager?.let { return it }
        val appContext = context ?: return null
        val manager = try {
            appContext.getSystemService(AudioManager::class.java)
        } catch (_: Throwable) {
            null
        }
        audioManager = manager
        return manager
    }

    private fun hasBluetoothPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
        val appContext = context ?: return false
        return appContext.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun activateBluetoothInput() {
        val manager = audioManager() ?: return
        try {
            logAudioDevices(manager)
            if (!hasBluetoothPermission()) {
                Diagnostics.log("AUDIO", "SCO skipped: BLUETOOTH_CONNECT not granted")
                return
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                var sco = manager.availableCommunicationDevices.firstOrNull {
                    it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                }
                if (sco == null && hasA2dpOutput(manager)) {
                    Diagnostics.log("AUDIO", "SCO missing, requesting HFP")
                    try {
                        @Suppress("DEPRECATION")
                        manager.startBluetoothSco()
                        @Suppress("DEPRECATION")
                        manager.isBluetoothScoOn = true
                    } catch (t: Throwable) {
                        Diagnostics.log(
                            "AUDIO",
                            "startBluetoothSco failed: ${t.message ?: t.javaClass.simpleName}"
                        )
                    }
                    scheduleScoRecheck()
                    return
                }
                if (sco == null) {
                    Diagnostics.log("AUDIO", "SCO device: none")
                    return
                }
                val ok = manager.setCommunicationDevice(sco)
                scoActive = ok
                Diagnostics.log("AUDIO", "SCO route=$ok name=${sco.productName}")
            } else {
                @Suppress("DEPRECATION")
                manager.startBluetoothSco()
                @Suppress("DEPRECATION")
                manager.isBluetoothScoOn = true
                scoActive = true
                Diagnostics.log("AUDIO", "SCO legacy start")
            }
        } catch (t: Throwable) {
            Diagnostics.log("AUDIO", "SCO setup failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun hasA2dpOutput(manager: AudioManager): Boolean = try {
        manager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any {
            it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP
        }
    } catch (_: Throwable) {
        false
    }

    private fun scheduleScoRecheck() {
        Thread({
            repeat(4) {
                try {
                    Thread.sleep(300)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                if (!capturing && record == null) return@Thread
                val manager = audioManager() ?: return@Thread
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return@Thread
                val sco = try {
                    manager.availableCommunicationDevices.firstOrNull {
                        it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
                    }
                } catch (_: Throwable) {
                    null
                }
                if (sco != null) {
                    val routed = try {
                        manager.setCommunicationDevice(sco)
                    } catch (_: Throwable) {
                        false
                    }
                    val preferred = try {
                        record?.setPreferredDevice(sco) ?: false
                    } catch (_: Throwable) {
                        false
                    }
                    scoActive = routed || preferred
                    Diagnostics.log(
                        "AUDIO",
                        "SCO recheck route=$routed preferred=$preferred"
                    )
                    return@Thread
                }
            }
            Diagnostics.log("AUDIO", "SCO recheck: still none")
        }, "jarvis-sco").apply { isDaemon = true }.start()
    }

    private fun logAudioDevices(manager: AudioManager) {
        try {
            val all = manager.getDevices(AudioManager.GET_DEVICES_ALL)
                .joinToString(limit = 8) { "${typeName(it.type)}:${it.productName}" }
            Diagnostics.log("BT", "devices=$all")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val comm = manager.availableCommunicationDevices
                    .joinToString(limit = 8) { "${typeName(it.type)}:${it.productName}" }
                Diagnostics.log("BT", "comm=$comm")
            }
        } catch (t: Throwable) {
            Diagnostics.log("BT", "device scan failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun typeName(type: Int): String = when (type) {
        AudioDeviceInfo.TYPE_BUILTIN_MIC -> "MIC"
        AudioDeviceInfo.TYPE_BUILTIN_SPEAKER -> "SPEAKER"
        AudioDeviceInfo.TYPE_BLUETOOTH_A2DP -> "A2DP"
        AudioDeviceInfo.TYPE_BLUETOOTH_SCO -> "SCO"
        AudioDeviceInfo.TYPE_WIRED_HEADPHONES -> "WIRED"
        AudioDeviceInfo.TYPE_WIRED_HEADSET -> "WIRED_HS"
        AudioDeviceInfo.TYPE_USB_HEADSET -> "USB_HS"
        else -> type.toString()
    }

    private fun preferBluetoothInput(audioRecord: AudioRecord) {
        if (!hasBluetoothPermission()) return
        try {
            val manager = audioManager() ?: return
            val input = manager.getDevices(AudioManager.GET_DEVICES_INPUTS).firstOrNull {
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO
            } ?: return
            val ok = audioRecord.setPreferredDevice(input)
            Diagnostics.log("AUDIO", "preferred input BT_SCO=$ok name=${input.productName}")
        } catch (t: Throwable) {
            Diagnostics.log("AUDIO", "preferred input failed: ${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun releaseBluetoothInput() {
        val manager = audioManager() ?: return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (scoActive) manager.clearCommunicationDevice()
            }
            if (scoActive) {
                @Suppress("DEPRECATION")
                manager.isBluetoothScoOn = false
                @Suppress("DEPRECATION")
                manager.stopBluetoothSco()
            }
        } catch (_: Throwable) {
        }
        if (scoActive) Diagnostics.log("AUDIO", "SCO released")
        scoActive = false
    }

    private fun sourceName(source: Int): String = when (source) {
        MediaRecorder.AudioSource.VOICE_COMMUNICATION -> "VOICE_COMMUNICATION"
        MediaRecorder.AudioSource.MIC -> "MIC"
        else -> "source#$source"
    }

    private fun captureLoop(audioRecord: AudioRecord) {
        val buffer = ByteArray(3200)
        var loggedFirst = false
        while (capturing) {
            val read = try {
                audioRecord.read(buffer, 0, buffer.size)
            } catch (t: Throwable) {
                Diagnostics.log("AUDIO", "read threw ${t.message ?: t.javaClass.simpleName}")
                -1
            }
            if (read > 0) {
                if (!loggedFirst) {
                    loggedFirst = true
                    Diagnostics.log("AUDIO", "first read bytes=$read")
                }
                val chunk = buffer.copyOf(read)
                onMicChunk(chunk)
                onMicLevel(levelOf(chunk))
            } else if (read < 0) {
                if (capturing) Diagnostics.log("AUDIO", "read error=$read")
                break
            }
        }
        Diagnostics.log("AUDIO", "capture loop ended")
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
