package io.github.mukhtharcm.simbridge

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * How to get call audio in and out on one chip family. Loaded from assets/profiles/<name>.json.
 *
 * The mixer lists are tinymix argument strings and run as root, so profiles only ever come from
 * inside the APK, never from the network or from storage another app can write.
 */
class Profile(json: JSONObject) {
    val name: String = json.getString("name")
    val platforms = json.getJSONArray("platforms").strings()
    val speakerRoute = json.optBoolean("speakerRoute")
    val routeChangeDelayMs = json.optLong("routeChangeDelayMs")
    val routeSettleDelayMs = json.optLong("routeSettleDelayMs")
    val halCallVsids = json.optJSONArray("halCallVsids").strings()
    val incallMusicParam: String = json.optString("incallMusicParam")
    val playbackStereo = json.optBoolean("playbackStereo")
    val playbackBufferMs = json.optInt("playbackBufferMs", 100)
    val mixerSetup = json.optJSONArray("mixerSetup").strings()
    val mixerAfterPlayback = json.optJSONArray("mixerAfterPlayback").strings()
    val mixerAfterCapture = json.optJSONArray("mixerAfterCapture").strings()
    val mixerRestore = json.optJSONArray("mixerRestore").strings()

    companion object {
        /** The bundled profile for this phone's chip, or null when there is none. */
        fun forThisDevice(context: Context): Profile? {
            val platform = Root.plain("getprop ro.board.platform").trim().lowercase()
            val assets = context.assets
            return assets.list("profiles").orEmpty()
                .map { Profile(JSONObject(assets.open("profiles/$it").bufferedReader().readText())) }
                .firstOrNull { platform in it.platforms }
        }

        private fun JSONArray?.strings(): List<String> =
            if (this == null) emptyList() else (0 until length()).map { getString(it) }
    }
}
