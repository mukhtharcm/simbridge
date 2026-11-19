package io.github.mukhtharcm.simbridge

import android.content.Intent
import android.telecom.Call
import android.telecom.CallAudioState
import android.telecom.InCallService
import android.util.Log
import org.json.JSONObject

/**
 * Android hands every SIM call to this service while simbridge is the default phone app.
 * It reports each call to the API's clients and bridges the audio while the call is up.
 * It never answers by itself: a client does, or the call rings out.
 */
class CallService : InCallService() {
    private var bridge: AudioBridge? = null
    @Volatile private var lastState = -1

    private val callback = object : Call.Callback() {
        override fun onStateChanged(call: Call, state: Int) = handle(call, state)
    }

    override fun onCallAdded(call: Call) {
        try { startForegroundService(Intent(this, BridgeService::class.java)) } catch (e: Exception) {
            Log.w(TAG, "foreground service did not start: ${e.message}")
        }
        Hub.newCall(call)
        lastState = -1
        call.registerCallback(callback)
        @Suppress("DEPRECATION")
        handle(call, call.state)
    }

    override fun onCallRemoved(call: Call) {
        call.unregisterCallback(callback)
        handle(call, Call.STATE_DISCONNECTED)
        if (Hub.call === call) Hub.call = null
        setAudioRoute(CallAudioState.ROUTE_EARPIECE)
    }

    private fun handle(call: Call, state: Int) {
        if (call !== Hub.call || state == lastState) return
        lastState = state
        val event = JSONObject().put("event", "call").put("id", Hub.callId)
        val number = call.details?.handle?.schemeSpecificPart.orEmpty()
        when (state) {
            Call.STATE_RINGING -> event.put("state", "ringing").put("direction", "in").put("number", number)
            Call.STATE_DIALING, Call.STATE_CONNECTING ->
                event.put("state", "dialing").put("direction", "out").put("number", number)
            // Reported once audio can flow, about two seconds after the answer, so clients can
            // start talking on "active" without their first words being lost.
            Call.STATE_ACTIVE -> { event.put("state", "active"); startBridge { if (lastState == Call.STATE_ACTIVE) Hub.event(event) }; return }
            Call.STATE_DISCONNECTED -> {
                event.put("state", "ended")
                    .put("reason", Rules.endReason(call.details?.disconnectCause?.code ?: -1))
                stopBridge()
            }
            else -> return
        }
        Hub.event(event)
    }

    private fun startBridge(onUp: () -> Unit) {
        if (bridge != null) return
        val profile = Profile.forThisDevice(this)
        if (profile == null) {
            Log.e(TAG, "no profile for this chip; the call carries no bridged audio")
            onUp()
            return
        }
        if (profile.speakerRoute) setAudioRoute(CallAudioState.ROUTE_SPEAKER)
        // Telecom owns the call's mute and re-applies it on route changes, so ask it too.
        setMuted(true)
        bridge = AudioBridge(this, profile, Hub::forCall, Hub::fromCall).also { it.start(onUp) }
    }

    private fun stopBridge() {
        if (bridge != null) Log.i(TAG, "audio into the call: ${Hub.toCall}")
        bridge?.stop {}
        bridge = null
    }

    companion object { private const val TAG = "simbridge" }
}
