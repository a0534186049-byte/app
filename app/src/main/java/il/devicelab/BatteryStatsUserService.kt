package il.devicelab

import android.os.RemoteException
import java.io.ByteArrayOutputStream
import java.util.concurrent.TimeUnit

/**
 * Shizuku UserService. Runs as shell only after user-started Shizuku
 * and explicit permission granted to Device Lab.
 */
class BatteryStatsUserService : IBatteryStatsUserService.Stub() {
    @Throws(RemoteException::class)
    override fun getChargedCheckin(): String {
        val process = try {
            Runtime.getRuntime().exec(arrayOf("dumpsys", "batterystats", "--charged", "--checkin"))
        } catch (t: Throwable) {
            throw RemoteException("Unable to start dumpsys: ${t.message}")
        }
        val stdout = ByteArrayOutputStream()
        val stderr = ByteArrayOutputStream()
        val outThread = Thread { runCatching { process.inputStream.use { it.copyTo(stdout) } } }
        val errThread = Thread { runCatching { process.errorStream.use { it.copyTo(stderr) } } }
        outThread.start(); errThread.start()
        val finished = try { process.waitFor(35, TimeUnit.SECONDS) }
        catch (t: InterruptedException) { Thread.currentThread().interrupt(); false }
        if (!finished) {
            process.destroy()
            throw RemoteException("dumpsys batterystats timed out after 35 seconds")
        }
        try { outThread.join(2_000L); errThread.join(2_000L) }
        catch (t: InterruptedException) { Thread.currentThread().interrupt() }
        val output = stdout.toString(Charsets.UTF_8.name())
        val error = stderr.toString(Charsets.UTF_8.name()).trim()
        if (process.exitValue() != 0) {
            throw RemoteException("dumpsys batterystats failed with exit ${process.exitValue()}: ${error.ifBlank { output.take(1000) }}")
        }
        if (output.isBlank()) throw RemoteException("dumpsys batterystats returned empty output: ${error.take(1000)}")
        return output
    }
    override fun destroy() { System.exit(0) }
}
