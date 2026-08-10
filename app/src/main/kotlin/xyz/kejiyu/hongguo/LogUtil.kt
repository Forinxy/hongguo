package xyz.kejiyu.hongguo

import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 文件日志工具 —— 写入到指定目录，类似 LSPilot 插件的 log/ 目录。
 *
 * 日志目录：/storage/emulated/0/Android/media/com.phoenix.read/zonghe_logs/
 * 每次进程启动创建新文件：yyyyMMdd_HHmmss.log
 */
object LogUtil {

    private const val LOG_TAG = "ZongHe"
    private const val LOG_DIR = "/storage/emulated/0/Android/media/com.phoenix.read/zonghe_logs"

    private var logFile: File? = null
    private var writer: FileWriter? = null
    private var initialized = false
    private val timeFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())
    private val fileFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault())

    // 诊断计数器（和 LSPilot 插件一样的思路）
    private val counters = mutableMapOf<String, Int>()
    private var lastDiagDump = 0L

    @Synchronized
    fun init() {
        if (initialized) return
        try {
            val dir = File(LOG_DIR)
            if (!dir.exists()) dir.mkdirs()
            logFile = File(dir, "${fileFormat.format(Date())}.log")
            writer = FileWriter(logFile, true)
            initialized = true
            info("═══════════════════════════════════")
            info("日志系统初始化 | 文件=${logFile?.absolutePath}")
            info("═══════════════════════════════════")
        } catch (e: Exception) {
            Log.e(LOG_TAG, "日志文件初始化失败", e)
        }
    }

    @Synchronized
    fun info(msg: String) {
        write("INFO", msg)
        Log.i(LOG_TAG, msg)
    }

    @Synchronized
    fun warn(msg: String) {
        write("WARN", msg)
        Log.w(LOG_TAG, msg)
    }

    @Synchronized
    fun error(msg: String, t: Throwable? = null) {
        val full = if (t != null) "$msg | ${t.javaClass.simpleName}: ${t.message}" else msg
        write("ERROR", full)
        Log.e(LOG_TAG, full, t)
        if (t != null) {
            val sw = StringWriter()
            t.printStackTrace(PrintWriter(sw))
            writeRaw(sw.toString())
        }
    }

    @Synchronized
    fun debug(msg: String) {
        write("DEBUG", msg)
        Log.d(LOG_TAG, msg)
    }

    // ===== 诊断计数器 =====
    @Synchronized
    fun incr(key: String) {
        counters[key] = (counters[key] ?: 0) + 1
    }

    @Synchronized
    fun diagDump(force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && (now - lastDiagDump) < 10000) return
        lastDiagDump = now
        val sb = StringBuilder()
        sb.appendLine("DIAG ──────────────────────────────")
        sb.appendLine("DIAG " + counters.entries.joinToString(" | ") { "${it.key}=${it.value}" })
        sb.appendLine("DIAG ──────────────────────────────")
        writeRaw(sb.toString())
    }

    @Synchronized
    fun flush() {
        try { writer?.flush() } catch (_: Exception) {}
    }

    @Synchronized
    fun getFilePath(): String = logFile?.absolutePath ?: "(未初始化)"

    // ===== 内部 =====
    private fun write(level: String, msg: String) {
        if (!initialized) {
            // 降级：初始化失败时只输出 Logcat
            Log.i(LOG_TAG, "[文件日志未就绪] $level: $msg")
            return
        }
        val ts = timeFormat.format(Date())
        writeRaw("[$ts] [$level] $msg")
    }

    private fun writeRaw(text: String) {
        try {
            writer?.apply {
                write(text)
                write("\n")
                flush()
            }
        } catch (_: Exception) {}
    }
}
