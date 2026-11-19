package io.github.mukhtharcm.simbridge

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.util.Log
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * Moves the audio of the SIM call that is up now: what the caller says goes to [sink], and frames
 * from [source] are played into the call. Both are 16 kHz mono, 20 ms frames (see [Pcm]).
 *
 * The phone's own microphone and speaker stay out of the call. The order of the steps in [setUp]
 * is the one gsm2sip arrived at for this chip family; each step notes why it sits where it does.
 */
class AudioBridge(
    context: Context,
    private val profile: Profile,
    private val source: () -> ShortArray,
    private val sink: (ShortArray, Int) -> Unit,
) {
    private val am = context.getSystemService(AudioManager::class.java)
    // Set-up and tear-down take turns on one thread, so a call that ends during set-up is undone in order.
    private val worker = Executors.newSingleThreadExecutor()
    @Volatile private var running = false
    private var record: AudioRecord? = null
    private var track: AudioTrack? = null
    private var loops = emptyList<Thread>()

    /** [onUp] runs once audio can flow, or once set-up has failed, so a caller is never left waiting. */
    fun start(onUp: () -> Unit) {
        running = true
        worker.execute {
            try { setUp() } catch (e: Exception) { Log.e(TAG, "audio set-up failed", e) }
            onUp()
        }
    }

    fun stop(onDone: () -> Unit) {
        running = false
        worker.execute {
            try { tearDown() } catch (e: Exception) { Log.e(TAG, "audio tear-down failed", e) }
            onDone()
        }
        worker.shutdown()
    }

    private fun setUp() {
        silenceHandset()
        // Injection into the call follows the media volume on this chip family.
        am.setStreamVolume(AudioManager.STREAM_MUSIC, am.getStreamMaxVolume(AudioManager.STREAM_MUSIC), 0)
        // Telecom is still switching the route; mixer writes made before it settles are overwritten.
        Thread.sleep(profile.routeChangeDelayMs)
        silenceHandset()
        Root.tinymix(profile.mixerSetup)
        // The HAL picks the in-call record path when the record stream is created, and only if it
        // already believes a call is up. Telling it afterwards leaves the capture silent.
        halCallState(2)
        Thread.sleep(profile.routeSettleDelayMs)
        if (!running) return

        val recBuf = maxOf(
            AudioRecord.getMinBufferSize(Pcm.RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT),
            Pcm.FRAME * 2 * 2,
        )
        // Downlink only: VOICE_CALL also carries the uplink, which is the audio we inject ourselves.
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_DOWNLINK, Pcm.RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recBuf,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "capture did not open (state=${rec.state}); is CAPTURE_AUDIO_OUTPUT granted?")
            rec.release()
        } else {
            // Recording starts before the playback track exists, or the uplink can wait on a voice
            // path that is not open yet.
            rec.startRecording()
            record = rec
        }

        // Before the track: this HAL chooses the output when the track is created.
        incallMusic(true)
        val mask = if (profile.playbackStereo) AudioFormat.CHANNEL_OUT_STEREO else AudioFormat.CHANNEL_OUT_MONO
        val channels = if (profile.playbackStereo) 2 else 1
        // A minimum-size buffer gets the low-latency output, which plays on the speaker and not into the call.
        val playBuf = maxOf(
            AudioTrack.getMinBufferSize(Pcm.RATE, mask, AudioFormat.ENCODING_PCM_16BIT),
            Pcm.RATE * 2 * channels * profile.playbackBufferMs / 1000,
        )
        val trk = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(Pcm.RATE).setChannelMask(mask).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setTransferMode(AudioTrack.MODE_STREAM)
            .setBufferSizeInBytes(playBuf)
            .build()
        am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            .firstOrNull { it.type == AudioDeviceInfo.TYPE_TELEPHONY }
            ?.let { Log.i(TAG, "playback to telephony: accepted=${trk.setPreferredDevice(it)}") }
            ?: Log.w(TAG, "no telephony output listed; playback may come out of the speaker")
        trk.play()
        track = trk
        Root.tinymix(profile.mixerAfterPlayback)

        loops = listOfNotNull(
            record?.let { thread(name = "sb-capture") { captureLoop(it) } },
            thread(name = "sb-playback") { playbackLoop(trk) },
        )
        // After the capture stream is open: the HAL discards these mutes until then.
        Root.tinymix(profile.mixerAfterCapture)
        Log.i(TAG, "bridge up: profile=${profile.name} capture=${record != null} routed=${trk.routedDevice?.type}")
    }

    private fun captureLoop(rec: AudioRecord) {
        val frame = ShortArray(Pcm.FRAME)
        var frames = 0
        var peak = 0
        while (running) {
            val n = rec.read(frame, 0, frame.size)
            if (n <= 0) break
            sink(frame, n)
            peak = maxOf(peak, Pcm.rms(frame, n))
            if (++frames % 50 == 0) { Log.i(TAG, "caller level (rms, last second): $peak"); peak = 0 }
        }
    }

    private fun playbackLoop(trk: AudioTrack) {
        while (running) {
            val mono = source()
            val out = if (profile.playbackStereo) Pcm.toStereo(mono) else mono
            // Blocking write paces the loop to real time.
            if (trk.write(out, 0, out.size) < 0) break
        }
    }

    private fun tearDown() {
        incallMusic(false)
        halCallState(1)
        try { track?.pause(); track?.flush() } catch (_: Exception) {}
        try { record?.stop() } catch (_: Exception) {}
        loops.forEach { it.join(1000) }
        record?.release(); record = null
        track?.release(); track = null
        Root.tinymix(profile.mixerRestore)
        am.isMicrophoneMute = false
        try { am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_UNMUTE, 0) } catch (_: SecurityException) {}
        Log.i(TAG, "bridge down")
    }

    private fun silenceHandset() {
        try { am.adjustStreamVolume(AudioManager.STREAM_VOICE_CALL, AudioManager.ADJUST_MUTE, 0) } catch (_: SecurityException) {}
        am.isMicrophoneMute = true
    }

    private fun halCallState(state: Int) {
        for (vsid in profile.halCallVsids) am.setParameters("vsid=$vsid;call_state=$state")
    }

    private fun incallMusic(on: Boolean) {
        val param = profile.incallMusicParam
        if (param.isEmpty()) return
        am.setParameters("$param=false")
        if (on) { Thread.sleep(50); am.setParameters("$param=true") }
    }

    companion object { private const val TAG = "simbridge" }
}
