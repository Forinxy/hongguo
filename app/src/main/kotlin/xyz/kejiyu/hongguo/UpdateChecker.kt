package xyz.kejiyu.hongguo

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import java.net.HttpURLConnection
import java.net.URL

/**
 * 更新检查：不调用 GitHub API。
 * 直接访问 https://github.com/KEJIYUNB/hongguo/releases/latest，
 * 跟随 302 跳转拿到 /releases/tag/<版本号>，再和当前版本号逐段比较。
 *
 * 提示策略：静默检查，只有检测到新版本才弹窗。
 * 每个进程（每次启动）最多弹一次，避免同一次启动里反复弹；
 * 手动检查弹过后，重新打开 App 会再次提示。
 */
object UpdateChecker {

    const val REPO_URL = "https://github.com/KEJIYUNB/hongguo"
    const val RELEASES_URL = "$REPO_URL/releases/latest"
    const val TG_CHANNEL_URL = "https://t.me/Kmodify"

    @Volatile var currentVersion: String = ""
        private set
    @Volatile var latestVersion: String? = null
        private set
    @Volatile var hasUpdate: Boolean = false
        private set
    @Volatile var checking: Boolean = false
        private set
    @Volatile var lastError: String? = null
        private set

    /** 本进程内是否已经弹过（进程重启后自动复位） */
    @Volatile private var shownOnce = false

    private val callbacks = mutableListOf<(String?) -> Unit>()
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * 启动一次版本检查（去重：检查中再调用只挂回调）。
     * 完成后在主线程回调 onResult：有新版本时返回最新版本号，否则 null。
     */
    fun checkUpdate(version: String, onResult: ((String?) -> Unit)? = null) {
        if (currentVersion.isEmpty()) currentVersion = version
        if (onResult != null) synchronized(callbacks) { callbacks.add(onResult) }
        if (checking) return
        checking = true
        Thread {
            var newer: String? = null
            try {
                val tag = fetchLatestTag()
                latestVersion = tag
                lastError = null
                if (tag != null && compareVersions(tag, version) > 0) {
                    hasUpdate = true
                    newer = tag
                } else {
                    hasUpdate = false
                }
            } catch (e: Exception) {
                lastError = e.message ?: "网络错误"
                hasUpdate = false
            } finally {
                checking = false
            }
            mainHandler.post {
                val list = synchronized(callbacks) { callbacks.toList().also { callbacks.clear() } }
                list.forEach { it(newer) }
            }
        }.start()
    }

    /**
     * 有更新才弹窗；同一进程内最多弹一次（每次启动/重新打开 App 时由调用方 resetShown）。
     * 需要 Activity 上下文；无更新/失败/已弹过时静默返回。
     */
    fun showUpdateDialogIfNeeded(ctx: Context) {
        if (!hasUpdate) return
        val latest = latestVersion ?: return
        if (ctx !is Activity || ctx.isFinishing) return
        if (shownOnce) return
        shownOnce = true
        showUpdateDialog(ctx, currentVersion.ifEmpty { "未知" }, latest)
    }

    /** 清空"本进程已弹过"标记：模块设置页每次打开时调用，保证每次打开都能提示。 */
    fun resetShown() { shownOnce = false }

    fun showUpdateDialog(ctx: Context, current: String, latest: String) {
        try {
            AlertDialog.Builder(ctx)
                .setTitle("发现新版本")
                .setMessage("当前版本：$current\n最新版本：$latest\n\n是否前往 GitHub 下载更新？")
                .setPositiveButton("去更新") { _, _ -> openUrl(ctx, RELEASES_URL) }
                .setNegativeButton("取消", null)
                .show()
        } catch (e: Exception) {
            LogUtil.error("更新弹窗失败", e)
        }
    }

    /** 不依赖 API：访问 /releases/latest，跟随 302 跳转拿到 /releases/tag/<tag>。 */
    private fun fetchLatestTag(): String? {
        val conn = URL(RELEASES_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
            conn.requestMethod = "GET"
            val status = conn.responseCode
            val loc = conn.getHeaderField("Location")?.trim()?.trimEnd('/')
            if (status in 300..399 && !loc.isNullOrEmpty()) {
                // https://github.com/KEJIYUNB/hongguo/releases/tag/v1.0.1
                return loc.substringAfterLast('/').removePrefix("v").trim()
            }
            // 兜底：直接解析 HTML 里的 /releases/tag/
            val html = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            return Regex("""/KEJIYUNB/hongguo/releases/tag/([^"'\s<>?]+)""").find(html)
                ?.groupValues?.get(1)?.removePrefix("v")?.trim()
        } finally {
            conn.disconnect()
        }
    }

    /** 逐段数字比较，支持 v1.0.1 / 1.0.1-beta 之类格式。 */
    fun compareVersions(a: String, b: String): Int {
        fun parse(s: String): List<Int> = s.trim().removePrefix("v")
            .split('.')
            .map { seg -> seg.filter { it.isDigit() }.toIntOrNull() ?: 0 }
        val pa = parse(a)
        val pb = parse(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val x = pa.getOrNull(i) ?: 0
            val y = pb.getOrNull(i) ?: 0
            if (x != y) return x.compareTo(y)
        }
        return 0
    }

    fun openUrl(ctx: Context, url: String) {
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.applicationContext.startActivity(i)
        } catch (e: Exception) {
            LogUtil.error("打开链接失败 $url", e)
        }
    }
}
