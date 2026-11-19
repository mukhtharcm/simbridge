package io.github.mukhtharcm.simbridge

import android.util.Log
import java.util.concurrent.TimeUnit

object Root {
    private const val TAG = "simbridge"

    /** Run tinymix once per argument string, in one root shell. Arguments come from the bundled profile only. */
    fun tinymix(args: List<String>) {
        if (args.isEmpty()) return
        // simbridge's own build, in a folder only root can write. A tinymix on the PATH is the fallback.
        val pick = "T=/data/adb/modules/simbridge/tinymix; [ -x \$T ] || T=tinymix; "
        val out = run(listOf("su", "-c", pick + args.joinToString("; ") { "\$T $it 2>&1" }))
        if (out.isNotBlank()) Log.i(TAG, "tinymix: ${out.trim().take(600)}")
    }

    /** Run a command without root. */
    fun plain(cmd: String): String = run(listOf("sh", "-c", cmd))

    private fun run(argv: List<String>): String = try {
        val p = ProcessBuilder(argv).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        // ponytail: one process per call and a flat 10 s limit; keep one root shell open if this gets slow.
        if (!p.waitFor(10, TimeUnit.SECONDS)) p.destroyForcibly()
        out
    } catch (e: Exception) {
        Log.w(TAG, "${argv.first()} failed: ${e.message}")
        ""
    }
}
