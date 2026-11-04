package com.jumpmaster.app.core.root

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/** Executes the same capture and input operations as ADB through a local root shell. */
object RootCommandService {
    private const val TAG = "RootCommandService"

    enum class State { UNKNOWN, CHECKING, GRANTED, DENIED }

    val state = mutableStateOf(State.UNKNOWN)

    suspend fun requestAccess(): Boolean {
        state.value = State.CHECKING
        val result = withContext(Dispatchers.IO) { execute("id", 15_000) }
        val granted = result.exitCode == 0 && result.stdout.toString(Charsets.UTF_8).contains("uid=0")
        state.value = if (granted) State.GRANTED else State.DENIED
        return granted
    }

    suspend fun captureScreen(): Bitmap? = withContext(Dispatchers.IO) {
        val result = execute("screencap -p", 15_000)
        if (result.exitCode != 0 || result.stdout.isEmpty()) return@withContext null
        BitmapFactory.decodeByteArray(result.stdout, 0, result.stdout.size)
    }

    suspend fun performLongPress(
        x: Int,
        y: Int,
        endX: Int,
        endY: Int,
        durationMs: Long
    ): Boolean = withContext(Dispatchers.IO) {
        execute("input swipe $x $y $endX $endY $durationMs", durationMs + 5_000).exitCode == 0
    }

    suspend fun getScreenDensity(): Int? = withContext(Dispatchers.IO) {
        val result = execute("wm density", 5_000)
        if (result.exitCode != 0) return@withContext null
        Regex("(?:Override|Physical) density:\\s*(\\d+)")
            .findAll(result.stdout.toString(Charsets.UTF_8))
            .lastOrNull()
            ?.groupValues
            ?.get(1)
            ?.toIntOrNull()
    }

    private data class CommandResult(
        val exitCode: Int,
        val stdout: ByteArray,
        val stderr: ByteArray
    )

    private fun execute(command: String, timeoutMs: Long): CommandResult {
        var process: Process? = null
        return try {
            process = ProcessBuilder("su", "-c", command).start()
            val stdout = ByteArrayOutputStream()
            val stderr = ByteArrayOutputStream()
            val stdoutReader = Thread { process.inputStream.use { it.copyTo(stdout) } }
            val stderrReader = Thread { process.errorStream.use { it.copyTo(stderr) } }
            stdoutReader.start()
            stderrReader.start()

            val finished = process.waitFor(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) process.destroyForcibly()
            stdoutReader.join(1_000)
            stderrReader.join(1_000)
            val exitCode = if (finished) process.exitValue() else -1
            if (exitCode != 0) {
                Log.w(TAG, "Root command failed ($exitCode): $command; ${stderr.toString(Charsets.UTF_8)}")
            }
            CommandResult(exitCode, stdout.toByteArray(), stderr.toByteArray())
        } catch (e: Exception) {
            Log.e(TAG, "Root command error: $command", e)
            process?.destroyForcibly()
            CommandResult(-1, byteArrayOf(), e.message.orEmpty().toByteArray())
        }
    }
}
