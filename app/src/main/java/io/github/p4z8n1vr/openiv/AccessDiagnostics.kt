package io.github.p4z8n1vr.openiv

import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.system.ErrnoException
import android.system.OsConstants
import rikka.shizuku.Shizuku
import rikka.shizuku.ShizukuProvider
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Reports the access available to this app at the time of the check. */
class AccessDiagnostics(context: Context) : AutoCloseable {
    enum class RootStatus(val messageResId: Int) {
        ACCESSIBLE(R.string.access_root_accessible),
        COMMAND_UNAVAILABLE(R.string.access_root_missing),
        DENIED(R.string.access_root_denied),
        TIMED_OUT(R.string.access_root_timeout),
    }

    enum class ShizukuStatus(val messageResId: Int) {
        ADB_GRANTED(R.string.access_shizuku_adb_granted),
        ADB_DENIED(R.string.access_shizuku_adb_denied),
        ROOT_GRANTED(R.string.access_shizuku_root_granted),
        ROOT_DENIED(R.string.access_shizuku_root_denied),
        CONNECTED_GRANTED(R.string.access_shizuku_other_granted),
        CONNECTED_DENIED(R.string.access_shizuku_other_denied),
        OFFLINE(R.string.access_shizuku_offline),
        MISSING(R.string.access_shizuku_missing),
        UNSUPPORTED(R.string.access_shizuku_unsupported),
    }

    data class Result(val root: RootStatus, val shizuku: ShizukuStatus)

    private val packageManager = context.applicationContext.packageManager
    private val mainThread = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor()
    private var pendingResult: ((Result) -> Unit)? = null
    private var closed = false

    /** Call from the main thread. Results are delivered on the main thread. */
    fun check(onResult: (Result) -> Unit) {
        if (closed) return
        val checking = pendingResult != null
        pendingResult = onResult
        if (checking) return

        worker.execute {
            val result = try {
                Result(checkRoot(), checkShizuku())
            } catch (_: InterruptedException) {
                return@execute
            }

            mainThread.post {
                val callback = pendingResult
                pendingResult = null
                callback?.invoke(result)
            }
        }
    }

    /** Cancels a running check and releases its worker. Call from the main thread. */
    override fun close() {
        closed = true
        pendingResult = null
        worker.shutdownNow()
    }

    private fun checkRoot(): RootStatus {
        val process = try {
            ProcessBuilder("su", "-c", "/system/bin/id -u")
                .redirectError(File("/dev/null"))
                .start()
        } catch (exception: IOException) {
            val missing = generateSequence<Throwable>(exception) { it.cause }.any {
                (it is ErrnoException && it.errno == OsConstants.ENOENT) ||
                    it.message?.let(MISSING_EXECUTABLE::containsMatchIn) == true
            }
            return if (missing) RootStatus.COMMAND_UNAVAILABLE else RootStatus.DENIED
        } catch (_: SecurityException) {
            return RootStatus.DENIED
        }

        try {
            process.outputStream.close()
            if (!process.waitFor(ROOT_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                return RootStatus.TIMED_OUT
            }
            if (process.exitValue() != 0) return RootStatus.DENIED

            val uid = process.inputStream.use { output ->
                // Avoid waiting for EOF if another process inherited the pipe.
                val available = output.available()
                if (available !in 1..MAX_UID_OUTPUT_BYTES) return RootStatus.DENIED
                output.readNBytes(available).decodeToString().trim()
            }
            return if (uid == "0") RootStatus.ACCESSIBLE else RootStatus.DENIED
        } catch (_: IOException) {
            return RootStatus.DENIED
        } finally {
            process.destroyForcibly()
        }
    }

    private fun checkShizuku(): ShizukuStatus {
        try {
            if (!Shizuku.pingBinder()) return disconnectedShizuku()
            if (Shizuku.isPreV11()) return ShizukuStatus.UNSUPPORTED

            val granted = Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
            return when (Shizuku.getUid()) {
                ROOT_UID -> if (granted) ShizukuStatus.ROOT_GRANTED else ShizukuStatus.ROOT_DENIED
                SHELL_UID -> if (granted) ShizukuStatus.ADB_GRANTED else ShizukuStatus.ADB_DENIED
                else -> if (granted) ShizukuStatus.CONNECTED_GRANTED else ShizukuStatus.CONNECTED_DENIED
            }
        } catch (_: SecurityException) {
            return ShizukuStatus.CONNECTED_DENIED
        } catch (_: RuntimeException) {
            return disconnectedShizuku()
        }
    }

    private fun disconnectedShizuku(): ShizukuStatus {
        return try {
            packageManager.getPackageInfo(
                ShizukuProvider.MANAGER_APPLICATION_ID,
                PackageManager.PackageInfoFlags.of(0),
            )
            ShizukuStatus.OFFLINE
        } catch (_: PackageManager.NameNotFoundException) {
            ShizukuStatus.MISSING
        }
    }

    private companion object {
        const val ROOT_TIMEOUT_SECONDS = 15L
        const val MAX_UID_OUTPUT_BYTES = 16
        const val ROOT_UID = 0
        const val SHELL_UID = 2000
        val MISSING_EXECUTABLE = Regex("""\berror(?:=|:\s*)${OsConstants.ENOENT}\b""")
    }
}
