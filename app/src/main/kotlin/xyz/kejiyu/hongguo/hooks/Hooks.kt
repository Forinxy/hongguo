package xyz.kejiyu.hongguo.hooks

import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.graphics.Color
import android.view.WindowManager
import android.view.WindowInsetsController
import android.widget.*
import xyz.kejiyu.hongguo.LogUtil
import xyz.kejiyu.hongguo.MainHook
import xyz.kejiyu.hongguo.BuildConfig
import xyz.kejiyu.hongguo.UpdateChecker
import io.github.libxposed.api.XposedInterface
import io.github.libxposed.api.XposedInterface.Hooker

object Hooks {

    private var gPrefs: SharedPreferences? = null
    private var gMasterOn = true
    private var gStatusOn = true
    private var gControlOn = true
    private var gPlayerOn = true
    private var gAdOn = true
    private var gRefreshOff = true
    private var gTopZoneOn = true
    private var gNavBarOff = false
    private var gProgressOff = false
    private var gRestoreControlsOnPause = false
    private var gVipOn = true
    private var gVipIconOn = true
    @Volatile private var gVideoPaused = false
    private val gVideoToolbarLayers = mutableListOf<java.lang.ref.WeakReference<Any>>()
    private data class ShortVideoHolderState(
        val ref: java.lang.ref.WeakReference<Any>,
        var playbackState: Int,
        var updatedAt: Long,
    )
    private val gShortVideoHolders = mutableListOf<ShortVideoHolderState>()
    private val gShortSeriesFragments =
        mutableListOf<java.lang.ref.WeakReference<Any>>()
    private var gLastVideoStateAt = 0L
    private var gLastVideoStateReason = "init"
    private var gLastWindowedMode: Boolean? = null

    private var gReceiverRegistered = false
    private var gCurrentActivity: Activity? = null

    private val gTargetIdSet = mutableSetOf<Int>()
    private val hideClasses = mutableListOf("ry1.e", "fw4.e")

    private val mainHandler = Handler(Looper.getMainLooper())
    private var appCtx: Context? = null
    private var gIsNight = false
    private var gSettingsActivity: Activity? = null
    private val gKejiyuBtnTag = "KEJIYU_BTN_TAG"

    private var gTopZoneTracking = false
    private var gTopZoneActive = false
    private var gTopZoneStartY = 0f
    private const val TOP_ZONE_HEIGHT = 250

    private val seedIds = intArrayOf(
        0x7F0B03E3, 0x7F0B071E, 0x7F0B0412,
        0x7F0B0BCB, 0x7F0A00D2, 0x7F0B0C6A,
    )

    // ===== 工具 =====
    private fun initPrefs(ctx: Context) {
        if (gPrefs == null) {
            gPrefs = ctx.getSharedPreferences("lspilot_kejiyu", 0)
            gMasterOn = gPrefs!!.getBoolean("master_on", true)
            gStatusOn = gPrefs!!.getBoolean("status_bar", true)
            gControlOn = gPrefs!!.getBoolean("control_hide", true)
            gPlayerOn = gPrefs!!.getBoolean("player_bar", true)
            gAdOn = gPrefs!!.getBoolean("ad_block", true)
            gRefreshOff = gPrefs!!.getBoolean("pull_refresh", true)
            gTopZoneOn = gPrefs!!.getBoolean("top_zone", true)
            gNavBarOff = gPrefs!!.getBoolean("nav_bar_off", false)
            gProgressOff = gPrefs!!.getBoolean("progress_off", false)
            gRestoreControlsOnPause = gPrefs!!.getBoolean("restore_controls_pause", false)
            gVipOn = gPrefs!!.getBoolean("vip_unlock", true)
            gVipIconOn = gPrefs!!.getBoolean("vip_icon", true)
        }
    }
    private fun savePref(key: String, value: Boolean) { gPrefs?.edit()?.putBoolean(key, value)?.apply() }
    private fun resolveEntryId(entry: String, root: View?) {
        if (root == null) return
        try { val id = root.resources.getIdentifier(entry, "id", "com.phoenix.read"); if (id > 0 && gTargetIdSet.add(id)) LogUtil.info("资源 $entry ID: $id") } catch (_: Exception) {}
    }
    private fun quickMatch(v: View?): Boolean {
        if (v == null) return false
        LogUtil.incr("matchCall")
        try { val id = v.id; if (id > 0 && gTargetIdSet.contains(id)) { LogUtil.incr("matchHit"); return true } } catch (_: Exception) {}
        try { if (v.javaClass.name in hideClasses) { LogUtil.incr("matchHit"); return true } } catch (_: Exception) {}
        return false
    }
    private fun blindView(v: View?) {
        if (v == null || !gMasterOn || !gControlOn || (gRestoreControlsOnPause && gVideoPaused)) return
        try { v.visibility = View.INVISIBLE; LogUtil.incr("blindOK") } catch (_: Exception) {}
    }
    private fun restoreView(v: View?) {
        if (v == null) return
        try { v.visibility = View.VISIBLE } catch (_: Exception) {}
        try { v.alpha = 1.0f } catch (_: Exception) {}
        // ★ 恢复后强制父容器重新布局，避免残留塌陷/黑条
        try { v.requestLayout() } catch (_: Exception) {}
        try { (v.parent as? View)?.requestLayout() } catch (_: Exception) {}
    }
    private fun isRedGuoAd(v: View?): Boolean {
        if (v == null) return false
        try { if (v is TextView && (v.text?.toString() ?: "").contains("红果")) return true } catch (_: Exception) {}
        if (v is ViewGroup) for (i in 0 until v.childCount) if (isRedGuoAd(v.getChildAt(i))) return true
        return false
    }
    private fun scanTreeQuick(v: View?) {
        if (v == null || !gMasterOn || !gControlOn || (gRestoreControlsOnPause && gVideoPaused)) return
        LogUtil.incr("scanTree")
        if (quickMatch(v)) { blindView(v); return }
        if (v is ViewGroup) for (i in 0 until v.childCount) scanTreeQuick(v.getChildAt(i))
    }
    private fun restoreAllControls() {
        mainHandler.post {
            restoreShortVideoNativeControls()
            val roots = collectAllWindows()
            for (root in roots) {
                scanTreeRestore(root)
                scanTreeProgressRestore(root)
            }
        }
    }

    // 首页短剧使用自己的“清屏”状态机。只恢复 View.visibility 并不会退出清屏，
    // 下一帧仍会被 App 隐藏，所以暂停时必须同步通知 ov4.i -> dw4.t.za(false, true)。
    // 反过来也一样：只把按钮设成 INVISIBLE 不会清掉 c3 播放器遮罩，画面会整体发暗。
    private fun registerShortVideoHolder(holder: Any?, playbackState: Int? = null) {
        if (holder == null) return
        val now = android.os.SystemClock.uptimeMillis()
        synchronized(gShortVideoHolders) {
            gShortVideoHolders.removeAll { it.ref.get() == null }
            val old = gShortVideoHolders.firstOrNull { it.ref.get() === holder }
            if (old != null) {
                gShortVideoHolders.remove(old)
                if (playbackState != null) old.playbackState = playbackState
                old.updatedAt = now
                gShortVideoHolders.add(old)
            } else {
                gShortVideoHolders.add(
                    ShortVideoHolderState(
                        java.lang.ref.WeakReference(holder),
                        playbackState ?: 0,
                        now,
                    )
                )
            }
        }
    }

    private fun shortVideoHolderSnapshot(): List<Pair<Any, Int>> = synchronized(gShortVideoHolders) {
        val result = mutableListOf<Pair<Any, Int>>()
        val iterator = gShortVideoHolders.iterator()
        while (iterator.hasNext()) {
            val item = iterator.next()
            val holder = item.ref.get()
            if (holder == null) iterator.remove() else result.add(Pair(holder, item.playbackState))
        }
        result
    }

    private fun findFieldValue(instance: Any, fieldName: String): Any? {
        var clazz: Class<*>? = instance.javaClass
        while (clazz != null) {
            try {
                return clazz.getDeclaredField(fieldName).apply { isAccessible = true }.get(instance)
            } catch (_: Throwable) {}
            clazz = clazz.superclass
        }
        return null
    }

    private fun registerShortSeriesFragment(fragment: Any?) {
        if (fragment == null) return
        synchronized(gShortSeriesFragments) {
            gShortSeriesFragments.removeAll { it.get() == null }
            val old = gShortSeriesFragments.firstOrNull { it.get() === fragment }
            if (old != null) gShortSeriesFragments.remove(old)
            gShortSeriesFragments.add(java.lang.ref.WeakReference(fragment))
        }
    }

    private fun shortSeriesFragmentSnapshot(): List<Any> = synchronized(gShortSeriesFragments) {
        val result = mutableListOf<Any>()
        val iterator = gShortSeriesFragments.iterator()
        while (iterator.hasNext()) {
            val fragment = iterator.next().get()
            if (fragment == null) iterator.remove() else result.add(fragment)
        }
        result
    }

    private fun activityFromFragment(fragment: Any?): Activity? {
        if (fragment == null) return null
        return try {
            fragment.javaClass.getMethod("getActivity").invoke(fragment) as? Activity
        } catch (_: Throwable) { null }
    }

    private fun shortVideoHolderRoot(holder: Any): View? {
        try {
            val root = holder.javaClass.getMethod("getRootView").invoke(holder) as? View
            if (root != null) return root
        } catch (_: Throwable) {}
        return findFieldValue(holder, "itemView") as? View
    }

    private fun isShortVideoHolderVisible(holder: Any): Boolean {
        val root = shortVideoHolderRoot(holder) ?: return false
        return try {
            if (!root.isShown || root.windowToken == null || root.width <= 0 || root.height <= 0) return false
            val visible = Rect()
            if (!root.getGlobalVisibleRect(visible)) return false
            val visibleArea = visible.width().toLong() * visible.height().toLong()
            val totalArea = root.width.toLong() * root.height.toLong()
            visibleArea * 4L >= totalArea
        } catch (_: Throwable) { false }
    }

    private fun detectPausedFromShortVideoHolders(): Boolean? {
        for ((holder, state) in shortVideoHolderSnapshot().asReversed()) {
            if (!isShortVideoHolderVisible(holder)) continue
            when (state) {
                1 -> return false
                2 -> return true
            }
            try {
                val playing = holder.javaClass.getMethod("isVideoPlaying").invoke(holder) as? Boolean
                if (playing == true) return false
            } catch (_: Throwable) {}
        }
        return null
    }

    private fun shouldForceShortVideoCleanMask(): Boolean {
        return gMasterOn && gControlOn && !(gRestoreControlsOnPause && gVideoPaused)
    }

    // 红果 7.3.1.32 的 dw4.t.z3(true) 是“清屏时移除播放器遮罩”的原生入口。
    // 这里不改 TextureView/Surface、alpha、屏幕亮度或颜色空间，只同步遮罩本身。
    private fun setOneShortVideoMaskClear(holder: Any, clear: Boolean) {
        try {
            val mask = findFieldValue(holder, "c3") as? View
            if (clear && mask != null && mask.visibility == View.INVISIBLE) return
            holder.javaClass.getMethod("z3", Boolean::class.java).invoke(holder, clear)
        } catch (e: Throwable) {
            LogUtil.warn("set short-video mask clear=$clear failed: ${holder.javaClass.name}: $e")
        }
    }

    private fun syncShortVideoMasks() {
        val holders = shortVideoHolderSnapshot().asReversed()
        if (shouldForceShortVideoCleanMask()) {
            var handledVisibleHolder = false
            for ((holder, _) in holders) {
                if (!isShortVideoHolderVisible(holder)) continue
                setOneShortVideoMaskClear(holder, true)
                handledVisibleHolder = true
            }
            // RecyclerView 刚绑定、尚未完成可见区域计算时，最近 Holder 作为一次性兜底。
            if (!handledVisibleHolder) holders.firstOrNull()?.first?.let {
                setOneShortVideoMaskClear(it, true)
            }
            return
        }

        // 关闭模块或“隐藏控件”后，把模块曾强制清掉的遮罩还给 App 的原生 w3 状态。
        // 暂停恢复分支由 za(false, true) 完整处理，不在这里重复覆盖。
        if (!gMasterOn || !gControlOn) {
            for ((holder, _) in holders) {
                val nativeClear = findFieldValue(holder, "w3") as? Boolean ?: continue
                setOneShortVideoMaskClear(holder, nativeClear)
            }
        }
    }

    private fun setOneShortVideoControlsVisible(holder: Any, visible: Boolean) {
        if (!visible) return
        var managerHandled = false
        try {
            val cleanScreenManager = findFieldValue(holder, "v3")
            if (cleanScreenManager != null) {
                cleanScreenManager.javaClass.getDeclaredMethod("b", Boolean::class.java).apply {
                    isAccessible = true
                }.invoke(cleanScreenManager, false)
                managerHandled = true
            }
        } catch (e: Throwable) {
            LogUtil.warn("exit short-video clean screen failed: $e")
        }
        // 再通知当前 Holder：Manager 已经是 false 时会提前返回，但模块可能刚把 View
        // 隐藏过，Holder 仍需重新分发一次“退出清屏”事件。
        try {
            holder.javaClass.getMethod("za", Boolean::class.java, Boolean::class.java)
                .invoke(holder, false, true)
        } catch (e: Throwable) {
            if (!managerHandled) {
                LogUtil.warn("show short-video controls failed: ${holder.javaClass.name}: $e")
            }
        }
    }

    private fun restoreShortVideoNativeControls() {
        val holders = shortVideoHolderSnapshot().asReversed()
        var restoredVisibleHolder = false
        for ((holder, _) in holders) {
            if (!isShortVideoHolderVisible(holder)) continue
            setOneShortVideoControlsVisible(holder, true)
            shortVideoHolderRoot(holder)?.requestLayout()
            restoredVisibleHolder = true
        }
        // 页面刚切换或 RecyclerView 正在布局时可见区域可能尚未建立，最近实例作为兜底。
        if (!restoredVisibleHolder) holders.firstOrNull()?.first?.let {
            setOneShortVideoControlsVisible(it, true)
        }
    }

    // 保存实际播放器工具栏 Layer。暂停时仅放行 show 调用并不会让已经隐藏的控件出现，必须主动 show。
    private fun registerVideoToolbarLayer(layer: Any?) {
        if (layer == null) return
        var added = false
        synchronized(gVideoToolbarLayers) {
            gVideoToolbarLayers.removeAll { it.get() == null }
            val old = gVideoToolbarLayers.firstOrNull { it.get() === layer }
            if (old != null) {
                // 最近有 show/hide 调用的 Layer 放到末尾，状态检测优先使用它，避免缓存页误判。
                gVideoToolbarLayers.remove(old)
            } else {
                added = true
            }
            gVideoToolbarLayers.add(java.lang.ref.WeakReference(layer))
        }
        if (added) LogUtil.info("video layer registered: ${layer.javaClass.name}")
        if (added && gMasterOn && gRestoreControlsOnPause && gVideoPaused) {
            mainHandler.post { setOneVideoToolbarVisible(layer, true) }
        }
    }
    private fun videoToolbarLayerSnapshot(): List<Any> = synchronized(gVideoToolbarLayers) {
        val result = mutableListOf<Any>()
        val iterator = gVideoToolbarLayers.iterator()
        while (iterator.hasNext()) {
            val layer = iterator.next().get()
            if (layer == null) iterator.remove() else result.add(layer)
        }
        result
    }

    private fun setOneVideoToolbarVisible(layer: Any, visible: Boolean) {
        try {
            when (layer.javaClass.name) {
                "com.dragon.read.pages.video.layers.toolbarlayer.ToolbarLayerFixed" ->
                    layer.javaClass.getDeclaredMethod("T", Boolean::class.java).apply { isAccessible = true }.invoke(layer, visible)
                "com.dragon.read.pages.video.customizelayers.CustomizeToolbarLayer" ->
                    layer.javaClass.getDeclaredMethod("Q", Boolean::class.java).apply { isAccessible = true }.invoke(layer, visible)
            }
        } catch (e: Throwable) {
            LogUtil.warn("set video toolbar visible=$visible failed: ${layer.javaClass.name}: $e")
        }
    }

    private fun setVideoToolbarsVisible(visible: Boolean) {
        mainHandler.post {
            for (layer in videoToolbarLayerSnapshot()) setOneVideoToolbarVisible(layer, visible)
        }
    }

    // 通过 Layer 持有的 VideoStateInquirer 对命令入口进行二次确认。
    private fun detectPausedFromVideoLayers(): Boolean? {
        // 优先检查最近活动的 Layer，避免 RecyclerView/页面缓存中的旧暂停实例覆盖当前播放状态。
        for (layer in videoToolbarLayerSnapshot().asReversed()) {
            try {
                val inquirer = layer.javaClass.getMethod("getVideoStateInquirer").invoke(layer) ?: continue
                val paused = inquirer.javaClass.getMethod("isPaused").invoke(inquirer) as? Boolean ?: false
                if (paused) return true
                val playing = inquirer.javaClass.getMethod("isPlaying").invoke(inquirer) as? Boolean ?: false
                if (playing) return false
            } catch (_: Throwable) {}
        }
        return null
    }
    private fun refreshVideoPauseState(reason: String, fallback: Boolean? = null) {
        mainHandler.postDelayed({
            val shortVideoState = detectPausedFromShortVideoHolders()
            val detected = shortVideoState ?: detectPausedFromVideoLayers() ?: fallback
            if (detected != null) {
                val source = if (shortVideoState != null) "short-holder" else "layer"
                setVideoPaused(detected, "$reason/$source")
            }
            else LogUtil.warn("video state undetermined: $reason")
        }, 60L)
    }

    private fun setVideoPaused(paused: Boolean, reason: String = "callback") {
        val changed = gVideoPaused != paused
        gVideoPaused = paused
        gLastVideoStateAt = android.os.SystemClock.uptimeMillis()
        gLastVideoStateReason = reason
        // 即使功能开关暂时关闭也持续记录真实状态；这样用户在“已经暂停”时打开开关，
        // 不必再播放/暂停一次才能生效。
        if (!gMasterOn || !gRestoreControlsOnPause) return
        if (paused) {
            restoreAllControls()
            setVideoToolbarsVisible(true)
            // 红果的清屏动画和 RecyclerView 绑定可能在同帧或下一帧重新隐藏，分两次校正。
            for (delay in longArrayOf(120L, 360L)) mainHandler.postDelayed({
                if (gMasterOn && gRestoreControlsOnPause && gVideoPaused) {
                    restoreAllControls()
                    setVideoToolbarsVisible(true)
                }
            }, delay)
            if (changed) LogUtil.info("video paused: restore controls, reason=$reason")
        } else {
            if (gPlayerOn) setVideoToolbarsVisible(false)
            mainHandler.post { scanAllWindows() }
            if (changed) LogUtil.info("video resumed: hide controls, reason=$reason")
        }
    }
    private fun scanTreeRestore(v: View?) {
        if (v == null) return
        if (quickMatch(v) && !isRedGuoAd(v)) restoreView(v)
        if (v is ViewGroup) for (i in 0 until v.childCount) scanTreeRestore(v.getChildAt(i))
    }

    // ===== 视频进度条 =====
    private fun isProgressBar(v: View?): Boolean {
        if (v == null) return false
        try { if (v.javaClass.name == "qg4.t0") return true } catch (_: Exception) {}
        return false
    }
    private fun scanTreeProgress(v: View?) {
        if (v == null || !gMasterOn || !gProgressOff) return
        if (isProgressBar(v)) { try { v.visibility = View.GONE; LogUtil.incr("progressHide") } catch (_: Exception) {}; return }
        if (v is ViewGroup) for (i in 0 until v.childCount) scanTreeProgress(v.getChildAt(i))
    }
    private fun scanTreeProgressRestore(v: View?) {
        if (v == null) return
        if (isProgressBar(v)) { try { v.visibility = View.VISIBLE } catch (_: Exception) {} }
        if (v is ViewGroup) for (i in 0 until v.childCount) scanTreeProgressRestore(v.getChildAt(i))
    }

    // ===== 全窗口扫描 =====
    private fun collectAllWindows(): MutableList<View> {
        val roots = mutableListOf<View>()
        try {
            val wmgClass = Class.forName("android.view.WindowManagerGlobal")
            val instance = wmgClass.getMethod("getInstance").invoke(null)
            val mViewsField = wmgClass.getDeclaredField("mViews")
            mViewsField.isAccessible = true
            val mViews = mViewsField.get(instance) as? ArrayList<View> ?: return roots
            roots.addAll(mViews)
        } catch (_: Exception) {}
        return roots
    }
    private fun scanAllWindows() {
        try {
            if (gCurrentActivity == null) return
            val decor = gCurrentActivity!!.window.decorView
            resolveEntryId("hh", decor)
            resolveEntryId("book_container", decor)
            val allRoots = collectAllWindows()
            if (allRoots.isEmpty() && decor != null) allRoots.add(decor)
            for (root in allRoots) {
                if (gMasterOn && gControlOn && !(gRestoreControlsOnPause && gVideoPaused)) scanTreeQuick(root)
                else scanTreeRestore(root)
            }
            if (gMasterOn && gProgressOff && !(gRestoreControlsOnPause && gVideoPaused)) for (root in allRoots) scanTreeProgress(root)
            syncShortVideoMasks()
        } catch (_: Exception) {}
    }

    // ===== 周期扫描 =====
    private var gScanRunnable: Runnable? = null
    private fun startPeriodicScan() {
        stopPeriodicScan()
        gScanRunnable = object : Runnable {
            override fun run() {
                try {
                    if (gMasterOn && gControlOn && !(gRestoreControlsOnPause && gVideoPaused)) {
                        val allRoots = collectAllWindows()
                        for (root in allRoots) scanTreeQuick(root)
                        syncShortVideoMasks()
                    }
                    if (gMasterOn && gProgressOff && !(gRestoreControlsOnPause && gVideoPaused)) {
                        val allRoots2 = collectAllWindows()
                        for (root in allRoots2) scanTreeProgress(root)
                    }
                } catch (_: Exception) {}
                mainHandler.postDelayed(this, 150)
            }
        }
        mainHandler.postDelayed(gScanRunnable!!, 150)
    }
    private fun stopPeriodicScan() {
        gScanRunnable?.let { mainHandler.removeCallbacks(it) }
        gScanRunnable = null
    }
    private fun applyToCurrent() { mainHandler.post { scanAllWindows() } }

    // ===== 状态栏 =====
    private fun isWindowedMode(act: Activity?): Boolean {
        if (act == null) return false
        try {
            if (Build.VERSION.SDK_INT >= 24 && act.isInMultiWindowMode) return true
            if (Build.VERSION.SDK_INT >= 26 && act.isInPictureInPictureMode) return true
            // 部分国产 ROM 的自由小窗没有及时更新 isInMultiWindowMode，窗口尺寸再兜底一次。
            if (Build.VERSION.SDK_INT >= 30) {
                val current = act.windowManager.currentWindowMetrics.bounds
                val maximum = act.windowManager.maximumWindowMetrics.bounds
                if (maximum.width() > 0 && maximum.height() > 0) {
                    val widthReduced = current.width().toLong() * 100L < maximum.width().toLong() * 88L
                    val heightReduced = current.height().toLong() * 100L < maximum.height().toLong() * 88L
                    if (widthReduced || heightReduced) return true
                }
            }
        } catch (_: Throwable) {}
        return false
    }

    // “隐藏状态栏”在全屏和自由小窗中都应生效。窗口模式只决定采用哪套实现，
    // 不能决定是否恢复状态栏高度；否则小窗会重新得到一整条顶部占位。
    private fun shouldHideStatusBar(): Boolean {
        return gMasterOn && gStatusOn
    }

    private fun updateSeriesMallTopMargin(act: Activity, statusHidden: Boolean) {
        try {
            // 红果 7.3.1.32：首页“漫画 / 推荐 / 搜索”容器的资源名是 is7。
            // App 会给它手动加 status_bar_height；隐藏状态栏时必须同步归零。
            val topBarId = act.resources.getIdentifier("is7", "id", "com.phoenix.read")
            if (topBarId == 0) return
            val topBar = act.findViewById<View>(topBarId) ?: return
            val params = topBar.layoutParams as? ViewGroup.MarginLayoutParams ?: return
            val wantedTop = if (statusHidden) {
                0
            } else {
                if (Build.VERSION.SDK_INT >= 30) {
                    act.window.decorView.rootWindowInsets
                        ?.getInsets(WindowInsets.Type.statusBars())
                        ?.top
                        ?: 0
                } else {
                    val statusId = act.resources.getIdentifier("status_bar_height", "dimen", "android")
                    if (statusId == 0) 0 else act.resources.getDimensionPixelSize(statusId)
                }
            }
            if (params.topMargin != wantedTop) {
                params.topMargin = wantedTop
                topBar.layoutParams = params
                topBar.requestLayout()
            }
        } catch (_: Exception) {}
    }

    private fun refreshOneShortVideoHolder(
        holder: Any,
        configuration: android.content.res.Configuration,
    ) {
        try {
            holder.javaClass.getMethod("j4", android.content.res.Configuration::class.java)
                .invoke(holder, configuration)
            // j4 记录新窗口边界；l4 是 App 自带的播放器尺寸复位方法。
            holder.javaClass.getMethod("l4").invoke(holder)
        } catch (_: Throwable) {}
        shortVideoHolderRoot(holder)?.requestLayout()
    }

    private fun refreshShortVideoWindowLayout(act: Activity) {
        val configuration = act.resources.configuration

        // “第 xx 集”联播页由 ShortSeriesSingleFragment 持有当前 Holder。自由窗只改变
        // screenWidthDp/screenHeightDp 时，它自己的 onConfigurationChanged 不会进入
        // orientation 分支，因此先从 Fragment 取出当前 Holder，并刷新外围容器。
        for (fragment in shortSeriesFragmentSnapshot().asReversed()) {
            if (activityFromFragment(fragment) !== act) continue
            for (fieldName in arrayOf("A3", "i", "j", "q", "l", "m", "r", "z3")) {
                val view = findFieldValue(fragment, fieldName) as? View ?: continue
                try { view.requestLayout() } catch (_: Throwable) {}
            }
            try {
                val pager = fragment.javaClass.getMethod("Mg").invoke(fragment) ?: continue
                val holder = pager.javaClass.getMethod("p2").invoke(pager) ?: continue
                registerShortVideoHolder(holder)
                refreshOneShortVideoHolder(holder, configuration)
            } catch (_: Throwable) {}
        }

        for ((holder, _) in shortVideoHolderSnapshot().asReversed()) {
            if (!isShortVideoHolderVisible(holder)) continue
            refreshOneShortVideoHolder(holder, configuration)
        }
    }

    // 自由小窗/分屏仍然需要隐藏顶部系统区域，但不能沿用全屏 FLAG、刘海穿透和旧式
    // 沉浸参数。状态栏、自由窗 captionBar，以及 Android 14+ 的多任务 affordance 是
    // 不同 Insets 类型，必须分别请求隐藏；ROM 不授予控制权时再使用透明标题背景兜底。
    private fun applyWindowedTop(act: Activity) {
        try {
            val window = act.window ?: return
            val decor = window.decorView

            fun applyOnce() {
                // 先撤销全屏专用参数，避免自由窗被系统当成不兼容的全屏窗口。
                window.clearFlags(
                    WindowManager.LayoutParams.FLAG_FULLSCREEN or
                        WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS
                )
                window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
                if (Build.VERSION.SDK_INT >= 28) {
                    val attrs = window.attributes
                    attrs.layoutInDisplayCutoutMode =
                        WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                    window.attributes = attrs
                }

                if (Build.VERSION.SDK_INT >= 30) {
                    // 清掉旧式标志后再调用 InsetsController；反过来执行会被清标志操作
                    // 重新 show 状态栏。
                    @Suppress("DEPRECATION")
                    decor.systemUiVisibility = decor.systemUiVisibility and
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN.inv() and
                        View.SYSTEM_UI_FLAG_FULLSCREEN.inv() and
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY.inv()
                    window.setDecorFitsSystemWindows(false)
                    window.statusBarColor = Color.TRANSPARENT
                    decor.windowInsetsController?.apply {
                        setSystemBarsAppearance(0, WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS)
                        if (Build.VERSION.SDK_INT >= 35) {
                            setSystemBarsAppearance(
                                WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND,
                                WindowInsetsController.APPEARANCE_TRANSPARENT_CAPTION_BAR_BACKGROUND,
                            )
                        }
                        var topTypes = WindowInsets.Type.statusBars() or WindowInsets.Type.captionBar()
                        if (Build.VERSION.SDK_INT >= 34) {
                            topTypes = topTypes or WindowInsets.Type.systemOverlays()
                        }
                        hide(topTypes)
                        systemBarsBehavior =
                            WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                    }
                } else {
                    // Android 10 没有 WindowInsetsController，只在该版本使用兼容标志。
                    @Suppress("DEPRECATION")
                    decor.systemUiVisibility = decor.systemUiVisibility or
                        View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    window.statusBarColor = Color.BLACK
                }
                decor.requestApplyInsets()
                updateSeriesMallTopMargin(act, true)
            }

            applyOnce()
            // 自由窗动画结束时 ROM 可能再次派发一次可见 Insets，延迟校正只处理状态栏，
            // 不重新添加任何全屏 Window flag。
            for (delay in longArrayOf(120L, 360L)) mainHandler.postDelayed({
                if (!act.isFinishing && isWindowedMode(act) && gMasterOn && gStatusOn) {
                    applyOnce()
                    refreshShortVideoWindowLayout(act)
                }
            }, delay)
        } catch (e: Throwable) {
            LogUtil.warn("apply windowed mode failed: $e")
        }
    }

    private fun reapplyAfterWindowModeChange(
        act: Activity?,
        reason: String,
        refreshLayout: Boolean = true,
    ) {
        if (act == null || !gMasterOn) return
        for (delay in longArrayOf(0L, 180L)) mainHandler.postDelayed({
            if (act.isFinishing) return@postDelayed
            val windowed = isWindowedMode(act)
            if (gLastWindowedMode != windowed) {
                gLastWindowedMode = windowed
                LogUtil.info("window mode -> ${if (windowed) "windowed" else "fullscreen"}, reason=$reason")
            }
            if (gStatusOn) applyCleanTop(act) else showStatusBar(act)
            if (gNavBarOff) applyNavBar(act) else showNavBar(act)
            if (refreshLayout) refreshShortVideoWindowLayout(act)
        }, delay)
    }

    private fun reapplySeriesPageState(
        owner: Any?,
        reason: String,
        refreshLayout: Boolean = true,
    ) {
        val act = owner as? Activity ?: activityFromFragment(owner) ?: return
        reapplyAfterWindowModeChange(act, reason, refreshLayout)
        mainHandler.postDelayed({
            if (act.isFinishing) return@postDelayed
            if (refreshLayout) refreshShortVideoWindowLayout(act)
            if (gRestoreControlsOnPause) {
                refreshVideoPauseState("series-window:$reason")
            }
            scanAllWindows()
        }, 80L)
    }

    private fun applyCleanTop(act: Activity?) {
        if (act == null || !gMasterOn || !gStatusOn) return
        if (isWindowedMode(act)) {
            applyWindowedTop(act)
            return
        }
        try {
            val window = act.window ?: return
            val decor = window.decorView

            // 让内容窗口真正覆盖状态栏区域，而不是只隐藏状态栏图标。
            // 不安装自定义 InsetsListener，否则会替换 DecorView 自己的 inset 处理。
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false)
            }
            if (Build.VERSION.SDK_INT >= 28) {
                val attrs = window.attributes
                attrs.layoutInDisplayCutoutMode = if (Build.VERSION.SDK_INT >= 30) {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                } else {
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
                }
                window.attributes = attrs
            }

            window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS)
            window.addFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN or
                    WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS
            )
            window.statusBarColor = Color.TRANSPARENT

            if (Build.VERSION.SDK_INT >= 30) {
                decor.windowInsetsController?.apply {
                    hide(WindowInsets.Type.statusBars())
                    systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            }

            @Suppress("DEPRECATION")
            decor.systemUiVisibility = decor.systemUiVisibility or
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                View.SYSTEM_UI_FLAG_FULLSCREEN or
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            decor.requestApplyInsets()
            updateSeriesMallTopMargin(act, true)
        } catch (_: Exception) {}
    }
    private fun showStatusBar(act: Activity?) {
        if (act == null) return
        try {
            val window = act.window ?: return
            val decor = window.decorView
            window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(true)
                var topTypes = WindowInsets.Type.statusBars() or WindowInsets.Type.captionBar()
                if (Build.VERSION.SDK_INT >= 34) {
                    topTypes = topTypes or WindowInsets.Type.systemOverlays()
                }
                decor.windowInsetsController?.show(topTypes)
            }
            if (Build.VERSION.SDK_INT >= 28) {
                val attrs = window.attributes
                attrs.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT
                window.attributes = attrs
            }
            @Suppress("DEPRECATION")
            decor.systemUiVisibility = decor.systemUiVisibility and
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN.inv() and
                View.SYSTEM_UI_FLAG_FULLSCREEN.inv() and
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY.inv()
            decor.requestApplyInsets()
            updateSeriesMallTopMargin(act, false)
        } catch (_: Exception) {}
    }

    // ===== 导航栏（小白条）=====
    private fun applyNavBar(act: Activity?) {
        if (act == null || !gMasterOn || !gNavBarOff) return
        try {
            val decor = act.window.decorView
            if (Build.VERSION.SDK_INT >= 30) {
                decor.windowInsetsController?.apply {
                    hide(WindowInsets.Type.navigationBars())
                    systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                }
            } else {
                @Suppress("DEPRECATION") decor.systemUiVisibility = decor.systemUiVisibility or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            }
        } catch (_: Exception) {}
    }
    private fun showNavBar(act: Activity?) {
        if (act == null) return
        try {
            val decor = act.window.decorView
            if (Build.VERSION.SDK_INT >= 30) decor.windowInsetsController?.show(WindowInsets.Type.navigationBars())
            else @Suppress("DEPRECATION") decor.systemUiVisibility = decor.systemUiVisibility and View.SYSTEM_UI_FLAG_HIDE_NAVIGATION.inv()
        } catch (_: Exception) {}
    }

    // ===== 通知 =====
    private fun createNotification(ctx: Context) {
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) nm.createNotificationChannel(NotificationChannel("lspilot_toggle", "KEJIYU", NotificationManager.IMPORTANCE_LOW).apply { setSound(null, null) })
            val flag = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE else PendingIntent.FLAG_UPDATE_CURRENT
            fun pi(code: Int, action: String) = PendingIntent.getBroadcast(ctx, code, Intent(action).apply { setPackage("com.phoenix.read"); addFlags(Intent.FLAG_RECEIVER_FOREGROUND) }, flag)
            val n = if (Build.VERSION.SDK_INT >= 26) android.app.Notification.Builder(ctx, "lspilot_toggle") else @Suppress("DEPRECATION") android.app.Notification.Builder(ctx)
            n.setContentTitle("KEJIYU 模块"); n.setContentText(if (gMasterOn) "已启用" else "已停用"); n.setSmallIcon(android.R.drawable.ic_menu_view); n.setOngoing(true)
            n.setContentIntent(pi(9999, "com.phoenix.read.LSPilot.TOGGLE"))
            n.addAction(android.R.drawable.ic_menu_view, if (gMasterOn) "隐藏" else "显示", pi(10001, "com.phoenix.read.LSPilot.TOGGLE"))
            n.addAction(android.R.drawable.ic_menu_edit, "面板", pi(10002, "com.phoenix.read.LSPilot.OPEN_PANEL"))
            n.addAction(android.R.drawable.ic_menu_close_clear_cancel, "重启", pi(10003, "com.phoenix.read.LSPilot.RESTART"))
            nm.notify(9999, n.build())
        } catch (e: Exception) { LogUtil.error("通知", e) }
    }
    private fun ensureReceiver(ctx: Context) {
        if (gReceiverRegistered) return
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    try { when (intent.action) { "com.phoenix.read.LSPilot.TOGGLE" -> doToggle(ctx); "com.phoenix.read.LSPilot.OPEN_PANEL" -> openPanel(); "com.phoenix.read.LSPilot.RESTART" -> restartApp(ctx) } } catch (e: Exception) { LogUtil.error("receiver", e) }
                }
            }
            val filter = IntentFilter("com.phoenix.read.LSPilot.TOGGLE").apply { addAction("com.phoenix.read.LSPilot.OPEN_PANEL"); addAction("com.phoenix.read.LSPilot.RESTART") }
            if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            else ctx.registerReceiver(receiver, filter)
            gReceiverRegistered = true
        } catch (e: Exception) { LogUtil.error("receiver", e) }
    }
    private fun doToggle(ctx: Context) { gMasterOn = !gMasterOn; savePref("master_on", gMasterOn); createNotification(ctx); applyToCurrent(); LogUtil.info("总开关 → $gMasterOn") }
    private fun restartApp(ctx: Context) { try { val i = ctx.packageManager.getLaunchIntentForPackage("com.phoenix.read"); if (i != null) { i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK); ctx.startActivity(i) }; mainHandler.postDelayed({ android.os.Process.killProcess(android.os.Process.myPid()) }, 1000) } catch (_: Exception) {} }

    // ===== 面板 =====
    private fun isNightTheme(ctx: Context): Boolean { val tv = android.util.TypedValue(); if (ctx.theme.resolveAttribute(android.R.attr.isLightTheme, tv, true)) return tv.data == 0; return ((ctx.resources.configuration.uiMode and 48) == 32) }
    private fun themeColor(ctx: Context, attr: Int, fallback: Int): Int { val a = ctx.theme.obtainStyledAttributes(intArrayOf(attr)); try { return a.getColor(0, fallback) } finally { a.recycle() } }
    private fun makeRow(ctx: Context, label: String, defVal: Boolean): Pair<LinearLayout, Switch> {
        val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(16, 14, 16, 14) }
        val tv = TextView(ctx).apply { text = label; textSize = 16f; setTextColor(themeColor(ctx, android.R.attr.textColorPrimary, if (gIsNight) 0xFFE0E0E0.toInt() else 0xFF202020.toInt())) }
        row.addView(tv, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)); val sw = Switch(ctx).apply { isChecked = defVal }; row.addView(sw); return Pair(row, sw)
    }
    private fun makeDivider(ctx: Context) = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1); setBackgroundColor(if (gIsNight) 0x33FFFFFF else 0x14000000) }
    private fun showPanel(ctx: Context?) {
        val act = ctx ?: gCurrentActivity ?: return
        try {
            gIsNight = isNightTheme(act); val bgColor = themeColor(act, android.R.attr.colorBackground, if (gIsNight) 0xFF2C2C2C.toInt() else 0xFFFFFFFF.toInt())
            val layout = LinearLayout(act).apply { orientation = LinearLayout.VERTICAL; setPadding(24, 4, 24, 8); try { background = GradientDrawable().apply { cornerRadius = 18f; setColor(bgColor) } } catch (_: Exception) {} }
            layout.addView(TextView(act).apply { text = "KEJIYU"; textSize = 20f; setTypeface(Typeface.DEFAULT, Typeface.BOLD); gravity = Gravity.CENTER; setPadding(0, 24, 0, 14); setTextColor(themeColor(act, android.R.attr.textColorPrimary, if (gIsNight) 0xFFE0E0E0.toInt() else 0xFF202020.toInt())) })
            fun addSwitch(label: String, getter: () -> Boolean, setter: (Boolean) -> Unit, saveKey: String) {
                val (row, sw) = makeRow(act, label, getter()); sw.setOnCheckedChangeListener { _, v -> setter(v); savePref(saveKey, v); if (saveKey != "player_bar" && saveKey != "ad_block") applyToCurrent() }; layout.addView(row); layout.addView(makeDivider(act))
            }
            addSwitch("模块总开关", { gMasterOn }, { gMasterOn = it }, "master_on"); addSwitch("隐藏状态栏", { gStatusOn }, { gStatusOn = it }, "status_bar")
            addSwitch("隐藏控件", { gControlOn }, { gControlOn = it }, "control_hide"); addSwitch("播放器工具栏", { gPlayerOn }, { gPlayerOn = it }, "player_bar")
            addSwitch("拦截广告/挂件", { gAdOn }, { gAdOn = it }, "ad_block"); addSwitch("禁用下拉刷新", { gRefreshOff }, { gRefreshOff = it }, "pull_refresh")
            addSwitch("顶部区域拦截下滑", { gTopZoneOn }, { gTopZoneOn = it }, "top_zone")
            addSwitch("解锁VIP", { gVipOn }, { gVipOn = it }, "vip_unlock")
            addSwitch("显示VIP图标", { gVipIconOn }, { gVipIconOn = it }, "vip_icon")
            addSwitch("隐藏底部小白条", { gNavBarOff }, { gNavBarOff = it }, "nav_bar_off")
            addSwitch("隐藏视频进度条", { gProgressOff }, { gProgressOff = it; if (!it) { val roots = collectAllWindows(); for (root in roots) scanTreeProgressRestore(root) } }, "progress_off")
            addSwitch("暂停后恢复所有控件", { gRestoreControlsOnPause }, {
                gRestoreControlsOnPause = it
                if (it) {
                    refreshVideoPauseState("switch-enabled", gVideoPaused)
                } else {
                    // 保留后台记录到的真实播放状态，只立即恢复用户配置的隐藏规则。
                    mainHandler.post { scanAllWindows() }
                }
            }, "restore_controls_pause")
            AlertDialog.Builder(act).setView(layout).setPositiveButton("关闭", null).show()
        } catch (e: Exception) { LogUtil.error("面板", e) }
    }
    private fun openPanel() { mainHandler.post { showPanel(gCurrentActivity) } }

    // ===== 顶部下滑 =====
    private fun handleTopZoneEvent(ev: android.view.MotionEvent?): Boolean {
        if (ev == null) return false
        try {
            when (ev.actionMasked) { 0 -> { val y = ev.rawY; if (y in 0f..TOP_ZONE_HEIGHT.toFloat()) { gTopZoneTracking = true; gTopZoneStartY = y; gTopZoneActive = false } else { gTopZoneTracking = false; gTopZoneActive = false }; return false } }
            if (gTopZoneTracking) { if (!gTopZoneActive && ev.actionMasked == 2) { if (ev.rawY - gTopZoneStartY > 60) gTopZoneActive = true }; if (gTopZoneActive) { if (ev.actionMasked == 1 || ev.actionMasked == 3) { gTopZoneTracking = false; gTopZoneActive = false }; return true }; if (ev.actionMasked == 1 || ev.actionMasked == 3) gTopZoneTracking = false }
        } catch (_: Exception) {}
        return false
    }

    // ===== 设置页按钮 =====
    private fun injectSettingsButton(act: Activity) {
        try {
            val content = act.findViewById<ViewGroup>(android.R.id.content) ?: return
            if ((0 until content.childCount).any { gKejiyuBtnTag == content.getChildAt(it).tag }) return
            val btn = Button(act).apply { text = "KEJIYU"; textSize = 16f; isAllCaps = false; tag = gKejiyuBtnTag; setOnClickListener { showPanel(gSettingsActivity ?: gCurrentActivity) } }
            val lp = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP); lp.topMargin = (60 * act.resources.displayMetrics.density).toInt(); content.addView(btn, lp)
            LogUtil.info("设置页按钮已绘制")
        } catch (e: Exception) { LogUtil.error("按钮", e) }
    }

    // ============================================================
    // ★ 安装 Hook
    // ============================================================
    private fun pollUpdatePrompt(a: Activity, attempt: Int) {
        if (a.isFinishing || attempt > 10) return
        mainHandler.postDelayed({
            try {
                if (UpdateChecker.hasUpdate || UpdateChecker.lastError != null) {
                    UpdateChecker.showUpdateDialogIfNeeded(a)
                } else if (!a.isFinishing) {
                    pollUpdatePrompt(a, attempt + 1)
                }
            } catch (_: Exception) {}
        }, 1000)
    }

    /** 有新版时，用当前可见的 Activity 弹更新框（作用域启动自动检测用） */
    fun showUpdateDialogIfAvailable() {
        val a = gCurrentActivity ?: return
        if (a.isFinishing) return
        mainHandler.post { UpdateChecker.showUpdateDialogIfNeeded(a) }
    }

    fun installBusinessHooks(module: MainHook, classLoader: ClassLoader) {
        LogUtil.info("── installBusinessHooks ──"); seedIds.forEach { gTargetIdSet.add(it) }

        // ===== 自动更新检测（无开关）：每次启动作用域自动获取一次 ===== 
        try {
            if (!UpdateChecker.checking && UpdateChecker.latestVersion == null) {
                LogUtil.info("作用域启动：自动触发更新检测")
                val ver = BuildConfig.VERSION_NAME.substringBefore(' ').substringBefore('(')
                UpdateChecker.checkUpdate(ver) { latest ->
                    if (latest != null) {
                        LogUtil.info("作用域启动发现新版本 $latest")
                        showUpdateDialogIfAvailable()
                    }
                }
            }
        } catch (e: Exception) { LogUtil.warn("作用域更新检测启动失败: $e") }

        fun ham(clazz: Class<*>, methodName: String, hookId: String, block: (XposedInterface.Chain) -> Any?) {
            clazz.declaredMethods.filter { it.name == methodName }.forEachIndexed { i, m -> try { module.hook(m).setId("${hookId}_$i").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain -> block(chain) }) } catch (_: Exception) {} }
        }
        fun hac(clazz: Class<*>, hookId: String, block: (XposedInterface.Chain) -> Any?) {
            clazz.declaredConstructors.forEachIndexed { i, c -> try { module.hook(c).setId("${hookId}_$i").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain -> block(chain) }) } catch (_: Exception) {} }
        }

        // 1. 不再 Hook Activity.attach：ActivityThread 会在 attach 返回后才应用
        // NoActionBar 主题，此时访问 decorView 会提前安装系统标题栏。

        // 2. App 自己处理完焦点变化后再恢复所需的系统栏状态。
        try {
            val c = Class.forName("android.app.Activity", false, classLoader)
            module.hook(c.getDeclaredMethod("onWindowFocusChanged", Boolean::class.java))
                .setId("wf")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(Hooker { chain ->
                    val focused = chain.getArg(0) as? Boolean == true
                    val result = chain.proceed()
                    if (focused) {
                        val a = chain.thisObject as? Activity
                        try {
                            if (gMasterOn && gStatusOn) applyCleanTop(a) else showStatusBar(a)
                            if (gMasterOn && gNavBarOff) applyNavBar(a) else showNavBar(a)
                        } catch (_: Exception) {}
                    }
                    result
                })
            LogUtil.info("  ✓ winFocus")
        } catch (e: Exception) { LogUtil.error("wf", e) }

        // 自由小窗、分屏与画中画会在同一个 Activity 内改变窗口边界，不能只靠 onResume。
        try {
            val c = Class.forName("android.app.Activity", false, classLoader)
            ham(c, "onMultiWindowModeChanged", "multiWindow") { chain ->
                val result = chain.proceed()
                reapplyAfterWindowModeChange(chain.thisObject as? Activity, "multi-window")
                result
            }
            ham(c, "onPictureInPictureModeChanged", "pictureInPicture") { chain ->
                val result = chain.proceed()
                reapplyAfterWindowModeChange(chain.thisObject as? Activity, "picture-in-picture")
                result
            }
            ham(c, "onConfigurationChanged", "windowConfiguration") { chain ->
                val result = chain.proceed()
                reapplyAfterWindowModeChange(chain.thisObject as? Activity, "configuration")
                result
            }
            LogUtil.info("  ✓ window mode callbacks")
        } catch (e: Throwable) { LogUtil.warn("  window mode callbacks missing: $e") }

        // 联播页会在 Activity.onCreate 末尾重新写 systemUiVisibility=0x500，
        // ShortSeriesSingleFragment.za() 还会在上下控制层变化时直接增删
        // FLAG_FULLSCREEN。它们都晚于通用 Activity Hook，必须在具体调用返回后校正。
        val seriesActivityClasses = listOf(
            "com.dragon.read.component.shortvideo.impl.ShortSeriesActivity",
            "com.dragon.read.component.shortvideo.impl.seriesdetail.ShortSeriesDetailActivity",
            "com.dragon.read.component.shortvideo.impl.albumdetail.VideoAlbumDetailActivity",
        )
        for ((classIndex, className) in seriesActivityClasses.withIndex()) {
            try {
                val c = Class.forName(className, false, classLoader)
                for (methodName in listOf(
                    "onCreate",
                    "onResume",
                    "onConfigurationChanged",
                    "onMultiWindowModeChanged",
                    "onPictureInPictureModeChanged",
                    "onWindowFocusChanged",
                )) {
                    ham(c, methodName, "seriesWindow_${classIndex}_$methodName") { chain ->
                        val result = chain.proceed()
                        reapplySeriesPageState(chain.thisObject, "$className#$methodName")
                        result
                    }
                }
                LogUtil.info("  ✓ series window tracker: $className")
            } catch (e: Throwable) {
                LogUtil.warn("  series window tracker missing: $className: $e")
            }
        }

        try {
            val className =
                "com.dragon.read.component.shortvideo.impl.v2.ShortSeriesSingleFragment"
            val c = Class.forName(className, false, classLoader)
            hac(c, "seriesFragmentCtor") { chain ->
                val result = chain.proceed()
                registerShortSeriesFragment(chain.thisObject)
                result
            }
            for (methodName in listOf(
                "onCreateContent",
                "onResume",
                "onConfigurationChanged",
                "za",
            )) {
                ham(c, methodName, "seriesFragment_$methodName") { chain ->
                    val result = chain.proceed()
                    registerShortSeriesFragment(chain.thisObject)
                    reapplySeriesPageState(
                        chain.thisObject,
                        "$className#$methodName",
                        refreshLayout = methodName != "za",
                    )
                    result
                }
            }
            LogUtil.info("  ✓ series fragment window tracker")
        } catch (e: Throwable) {
            LogUtil.warn("  series fragment window tracker missing: $e")
        }
        // 3. StatusBarUtil
        try {
            val c = Class.forName("com.dragon.read.base.ui.util.StatusBarUtil", false, classLoader)
            ham(c, "clearFullScreenFlag", "sbC") { chain ->
                val result = chain.proceed()
                if (shouldHideStatusBar()) {
                    val a = chain.getArg(0) as? Activity
                    mainHandler.post { applyCleanTop(a) }
                }
                result
            }
            ham(c, "hideStatusBar", "sbH") { chain ->
                if (!shouldHideStatusBar()) {
                    chain.proceed()
                } else try {
                    if (chain.args.size >= 2 && chain.getArg(1) is Boolean && !(chain.getArg(1) as Boolean)) {
                        val args = (chain.args as Array<Any?>).copyOf()
                        args[1] = true
                        chain.proceed(args)
                    } else chain.proceed()
                } catch (_: Exception) { chain.proceed() }
            }

            // 目标 App 用 ignoringVisibility(statusBars | displayCutout) 计算顶部 margin。
            // 状态栏隐藏后该值仍非 0，因此在模块启用时直接返回 0。
            ham(c, "getStatusHeight", "sbHeight") { chain ->
                if (shouldHideStatusBar()) 0 else chain.proceed()
            }
            LogUtil.info("  ✓ StatusBarUtil")
        } catch (e: Exception) { LogUtil.warn("  StatusBarUtil 未找到") }

        // 首页实际使用的是 ByteDance 这套工具，而不是上面的 StatusBarUtil。
        try {
            val c = Class.forName("com.bytedance.ies.uikit.statusbar.StatusBarUtils", false, classLoader)
            module.hook(c.getDeclaredMethod("getStatusBarHeight", Context::class.java))
                .setId("uikitStatusHeight")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(Hooker { chain ->
                    if (shouldHideStatusBar()) 0 else chain.proceed()
                })
            LogUtil.info("  ✓ UIKit StatusBarUtils")
        } catch (e: Exception) { LogUtil.warn("  UIKit StatusBarUtils 未找到: $e") }

        // 4. VideoShop 播放状态检测。
        // 实际链路：工具栏点击 -> command 207/208 -> VideoContext play/pause ->
        // TTVideoEngine playbackState(1=播放, 2=暂停) -> VideoController/ns7.b.onPlaybackStateChanged。
        fun hookPlaybackState(className: String, hookId: String) {
            try {
                val c = Class.forName(className, false, classLoader)
                ham(c, "onPlaybackStateChanged", hookId) { chain ->
                    val result = chain.proceed()
                    try {
                        val state = chain.args.lastOrNull { it is Int } as? Int
                        when (state) {
                            1 -> setVideoPaused(false, "$className#state=1")
                            2 -> setVideoPaused(true, "$className#state=2")
                            0, 3 -> if (gVideoPaused) refreshVideoPauseState("$className#state=$state", false)
                        }
                    } catch (e: Throwable) { LogUtil.warn("playback state parse failed: $className: $e") }
                    result
                }
                LogUtil.info("  ✓ playback detector: $className")
            } catch (e: Throwable) { LogUtil.warn("  playback detector missing: $className: $e") }
        }
        // 当前版本同时包含新旧两套 VideoShop controller，均需覆盖。
        hookPlaybackState("com.ss.android.videoshop.controller.VideoController", "vcState")
        hookPlaybackState("ns7.b", "nsState")

        // 首页“推荐”短剧不走上面的 VideoShop Controller，而是走 dw4 Holder 回调：
        // S1(player, 1)=播放，S1(player, 2)=暂停。V2 没接这条链路，所以开关看似存在却无效。
        // APK 中全部具体 Holder 的 S1 都先调用 dw4.i0.S1；只 Hook 这一层可避免一次
        // 状态变化被父类、子类重复处理多次。
        val shortVideoHolderClasses = listOf("dw4.i0")
        var shortPlaybackHookCount = 0
        shortVideoHolderClasses.forEachIndexed { classIndex, className ->
            try {
                val c = Class.forName(className, false, classLoader)
                ham(c, "S1", "shortState_${classIndex}") { chain ->
                    val result = chain.proceed()
                    try {
                        val holder = chain.thisObject
                        val state = chain.args.lastOrNull { it is Int } as? Int
                        registerShortVideoHolder(holder, state)
                        if (state == 1 || state == 2) {
                            mainHandler.post {
                                if (isShortVideoHolderVisible(holder)) {
                                    setVideoPaused(state == 2, "$className#S1=$state")
                                }
                            }
                        }
                    } catch (e: Throwable) {
                        LogUtil.warn("short-video playback parse failed: $className: $e")
                    }
                    result
                }
                shortPlaybackHookCount++
            } catch (_: Throwable) {}
        }
        try {
            val c = Class.forName("dw4.t", false, classLoader)
            hac(c, "shortHolderCtor") { chain ->
                val result = chain.proceed()
                registerShortVideoHolder(chain.thisObject)
                result
            }
            ham(c, "onBind", "shortHolderBind") { chain ->
                val result = chain.proceed()
                // RecyclerView Holder 可能复用给下一集，旧集的暂停状态不能沿用。
                registerShortVideoHolder(chain.thisObject, 0)
                if (gRestoreControlsOnPause) refreshVideoPauseState("short-holder-bind")
                result
            }

            // z3(false) 会重新显示 c3 播放器遮罩。模块正在隐藏控件时，把它改为
            // z3(true)，避免首页按钮虽已隐藏、视频却仍被半透明遮罩压暗。
            module.hook(c.getDeclaredMethod("z3", Boolean::class.java))
                .setId("shortMask")
                .setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE)
                .intercept(Hooker { chain ->
                    if (shouldForceShortVideoCleanMask() && chain.getArg(0) as? Boolean == false) {
                        val args = (chain.args as Array<Any?>).copyOf()
                        args[0] = true
                        chain.proceed(args)
                    } else {
                        chain.proceed()
                    }
                })
        } catch (_: Throwable) {}
        LogUtil.info("  ✓ short-video playback detectors: $shortPlaybackHookCount")

        // 早期命令检测；最终由 Layer 的 VideoStateInquirer 延迟复核。
        try {
            val c = Class.forName("com.ss.android.videoshop.mediaview.LayerHostMediaLayout", false, classLoader)
            ham(c, "execCommand", "videoCmd") { chain ->
                var command: Int? = null
                try {
                    val cmd = chain.getArg(0)
                    if (cmd != null) command = cmd.javaClass.getMethod("getCommand").invoke(cmd) as? Int
                } catch (_: Throwable) {}
                val result = chain.proceed()
                when (command) {
                    208 -> refreshVideoPauseState("command=208", true)
                    207, 214 -> refreshVideoPauseState("command=$command", false)
                }
                result
            }
            ham(c, "onVideoPause", "videoPauseDirect") { chain ->
                val result = chain.proceed()
                setVideoPaused(true, "LayerHostMediaLayout#onVideoPause")
                result
            }
            ham(c, "onVideoPlay", "videoPlayDirect") { chain ->
                val result = chain.proceed()
                setVideoPaused(false, "LayerHostMediaLayout#onVideoPlay")
                result
            }
            LogUtil.info("  ✓ video command detector")
        } catch (e: Throwable) { LogUtil.warn("  video command detector missing: $e") }

        // 保存当前页面的真实工具栏 Layer，供暂停后主动显示、恢复后主动隐藏。
        fun hookToolbarLayer(className: String, hookId: String) {
            try {
                val c = Class.forName(className, false, classLoader)
                hac(c, hookId) { chain ->
                    val result = chain.proceed()
                    registerVideoToolbarLayer(chain.thisObject)
                    refreshVideoPauseState("layer-created:$className")
                    result
                }
                LogUtil.info("  ✓ toolbar layer tracker: $className")
            } catch (e: Throwable) { LogUtil.warn("  toolbar layer tracker missing: $className: $e") }
        }
        hookToolbarLayer("com.dragon.read.pages.video.layers.toolbarlayer.ToolbarLayerFixed", "fixedLayer")
        hookToolbarLayer("com.dragon.read.pages.video.customizelayers.CustomizeToolbarLayer", "customLayer")

        // 启动时有些 Layer 可能早于本模块构造器 Hook 创建；首次使用 show/hide 时补登记。
        fun trackLayerFromCall(chain: XposedInterface.Chain) {
            try { registerVideoToolbarLayer(chain.thisObject) } catch (_: Throwable) {}
        }

        // 5. 播放器工具栏 — 所有 show 调用直接跳过（return null），不执行任何显示逻辑
        // 5a. ToolbarLayerFixed.T(boolean) — 底部工具栏
        try { val c = Class.forName("com.dragon.read.pages.video.layers.toolbarlayer.ToolbarLayerFixed", false, classLoader)
            ham(c, "T", "pb") { chain -> trackLayerFromCall(chain); if (!gMasterOn || !gPlayerOn || (gRestoreControlsOnPause && gVideoPaused)) chain.proceed() else try { if (chain.getArg(0) as? Boolean == true) { LogUtil.incr("btmBlock"); null } else chain.proceed() } catch (_: Exception) { chain.proceed() } }
            LogUtil.info("  ✓ playerBtm") } catch (e: Exception) { LogUtil.warn("  ToolbarLayerFixed 未找到") }
        // 5b. CustomizeToolbarLayer.Q(boolean) — 顶部工具栏入口
        try { val c = Class.forName("com.dragon.read.pages.video.customizelayers.CustomizeToolbarLayer", false, classLoader)
            ham(c, "Q", "pt") { chain -> trackLayerFromCall(chain); if (!gMasterOn || !gPlayerOn || (gRestoreControlsOnPause && gVideoPaused)) chain.proceed() else try { if (chain.getArg(0) as? Boolean == true) { LogUtil.incr("topBlock"); null } else chain.proceed() } catch (_: Exception) { chain.proceed() } }
            LogUtil.info("  ✓ playerTop") } catch (e: Exception) { LogUtil.warn("  CustomizeToolbarLayer 未找到") }
        // 5c. CustomizeToolbarLayer.S(boolean,boolean,boolean) — 真正的 show/hide 实现
        try { val c = Class.forName("com.dragon.read.pages.video.customizelayers.CustomizeToolbarLayer", false, classLoader)
            module.hook(c.getDeclaredMethod("S", Boolean::class.java, Boolean::class.java, Boolean::class.java)).setId("ctS").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain -> trackLayerFromCall(chain); if (!gMasterOn || !gPlayerOn || (gRestoreControlsOnPause && gVideoPaused)) chain.proceed() else try { if (chain.getArg(0) as? Boolean == true) { LogUtil.incr("topBlock"); null } else chain.proceed() } catch (_: Exception) { chain.proceed() } })
            LogUtil.info("  ✓ CustomizeToolbarLayer.S") } catch (e: Exception) { LogUtil.warn("  CustomizeToolbarLayer.S 未找到") }
        // 5d. gt7.c.a(boolean) — 所有工具栏基类 show/hide
        try { val c = Class.forName("gt7.c", false, classLoader)
            module.hook(c.getDeclaredMethod("a", Boolean::class.java)).setId("gt7c").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain -> if (!gMasterOn || !gPlayerOn || (gRestoreControlsOnPause && gVideoPaused)) chain.proceed() else try { if (chain.getArg(0) as? Boolean == true) { LogUtil.incr("toolbarBlock"); null } else chain.proceed() } catch (_: Exception) { chain.proceed() } })
            LogUtil.info("  ✓ gt7.c.a") } catch (e: Exception) { LogUtil.warn("  gt7.c 未找到") }

        // 6. 金宝箱/挂件
        try { val c = Class.forName("com.dragon.read.component.biz.impl.BsGoldBoxServiceImpl", false, classLoader); module.hook(c.getDeclaredMethod("tryAttach")).setId("gb").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain -> if (gMasterOn && gAdOn) null else chain.proceed() }); LogUtil.info("  ✓ goldBox") } catch (e: Exception) { LogUtil.warn("  BsGoldBoxServiceImpl 未找到") }
        try { val c = Class.forName("com.bytedance.ug.sdk.novel.pendant.PlanPendantServiceImpl", false, classLoader); ham(c, "triggerEvent", "pd") { chain -> if (gMasterOn && gAdOn) null else chain.proceed() }; LogUtil.info("  ✓ pendant") } catch (e: Exception) { LogUtil.warn("  PlanPendantServiceImpl 未找到") }

        // 7. 红包
        try { var rc: Class<*>? = null; try { rc = Class.forName("com.dragon.read.component.biz.impl.bookmall.holder.video.VideoRedPacketHolder", false, classLoader) } catch (_: Exception) {}; if (rc != null) { hac(rc, "rp") { chain -> if (!gMasterOn || !gAdOn) chain.proceed() else { val r = chain.proceed(); try { val iv = chain.thisObject.javaClass.getField("itemView").get(chain.thisObject) as? View; iv?.visibility = View.GONE; iv?.layoutParams = ViewGroup.LayoutParams(0, 0) } catch (_: Exception) {}; r } }; LogUtil.info("  ✓ redPack") } } catch (e: Exception) { LogUtil.warn("redPack: $e") }

        // 8. dw4.t.p4 仅负责横屏入口按钮，不再把它误当成“全部控件”恢复入口。
        try { val c = Class.forName("dw4.t", false, classLoader); module.hook(c.getDeclaredMethod("p4", Boolean::class.java)).setId("fb").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain -> if (!gMasterOn || !gControlOn || (gRestoreControlsOnPause && gVideoPaused)) chain.proceed() else try { if (chain.getArg(0) as? Boolean == true) { val na = (chain.args as Array<Any?>).copyOf(); na[0] = false; chain.proceed(na) } else chain.proceed() } catch (_: Exception) { chain.proceed() } }); LogUtil.info("  ✓ dw4.t.p4") } catch (e: Exception) { LogUtil.warn("  dw4.t 未找到") }

        // 9. inflate
        // is7 只存在于首页，是“菜单 / 漫画 / 推荐 / 搜索”整块顶部栏。按用户设置把它
        // 归入“隐藏控件”，但不再把它和状态栏开关联动；暂停恢复时也会一起恢复。
        try {
            val c = Class.forName("android.view.LayoutInflater", false, classLoader)
            ham(c, "inflate", "inf") { chain ->
                val result = chain.proceed()
                if (result is ViewGroup) {
                    LogUtil.incr("inflate")
                    resolveEntryId("h8s", result)
                    resolveEntryId("inx", result)
                    resolveEntryId("ac5", result)
                    resolveEntryId("is7", result)
                    resolveEntryId("hh", result)
                    resolveEntryId("book_container", result)
                    if (gMasterOn && gControlOn && !(gRestoreControlsOnPause && gVideoPaused)) {
                        scanTreeQuick(result)
                    }
                }
                result
            }
            LogUtil.info("  ✓ inflate")
        } catch (e: Exception) { LogUtil.error("inflate", e) }

        // 10. setVisibility / addView
        try { val c = Class.forName("android.view.View", false, classLoader); ham(c, "setVisibility", "sv") { chain -> if (!gMasterOn || !gControlOn || (gRestoreControlsOnPause && gVideoPaused)) chain.proceed() else try { LogUtil.incr("setVis"); val tv = chain.getArg(0) as? Int; if (tv == View.VISIBLE && quickMatch(chain.thisObject as? View)) { val na = (chain.args as Array<Any?>).copyOf(); na[0] = View.INVISIBLE; chain.proceed(na) } else chain.proceed() } catch (_: Exception) { chain.proceed() } }; LogUtil.info("  ✓ setVis") } catch (e: Exception) { LogUtil.error("setVis", e) }
        try { val c = Class.forName("android.view.ViewGroup", false, classLoader); ham(c, "addView", "av") { chain -> if (!gMasterOn || !gControlOn || (gRestoreControlsOnPause && gVideoPaused)) chain.proceed() else { try { LogUtil.incr("addView"); val v = chain.getArg(0) as? View; if (v != null && quickMatch(v)) blindView(v) } catch (_: Exception) {}; chain.proceed() } }; LogUtil.info("  ✓ addView") } catch (e: Exception) { LogUtil.error("addView", e) }

        // 11. RecyclerView 卡片
        try { val c = Class.forName("com.dragon.read.recyler.AbsRecyclerViewHolder", false, classLoader); hac(c, "cc") { chain -> if (gMasterOn) try { val v = chain.getArg(0) as? View; if (v != null) { if (gControlOn) scanTreeQuick(v) else scanTreeRestore(v) } } catch (_: Exception) {}; chain.proceed() }; try { module.hook(c.getDeclaredMethod("onBind", Object::class.java, Int::class.java)).setId("cb").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain -> if (gMasterOn) try { val iv = chain.thisObject.javaClass.getField("itemView").get(chain.thisObject) as? View; if (iv != null) { if (gControlOn) scanTreeQuick(iv) else scanTreeRestore(iv) } } catch (_: Exception) {}; chain.proceed() }) } catch (_: Exception) {}; LogUtil.info("  ✓ card") } catch (e: Exception) { LogUtil.warn("  AbsRecyclerViewHolder 未找到") }

        // 12. 下拉刷新
        try { val c = Class.forName("androidx.swiperefreshlayout.widget.SwipeRefreshLayout", false, classLoader); module.hook(c.getDeclaredMethod("onInterceptTouchEvent", android.view.MotionEvent::class.java)).setId("sw").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain -> if (gMasterOn && gRefreshOff) false else chain.proceed() }); LogUtil.info("  ✓ swipe") } catch (e: Exception) { LogUtil.warn("  SwipeRefreshLayout 未找到") }

        // 13. 顶部下滑
        try { val c = Class.forName("ev7.b", false, classLoader); module.hook(c.getDeclaredMethod("onTouchEvent", android.view.MotionEvent::class.java)).setId("tz1").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain -> if (gMasterOn && gTopZoneOn && handleTopZoneEvent(chain.getArg(0) as? android.view.MotionEvent)) true else chain.proceed() }); LogUtil.info("  ✓ ev7.b") } catch (e: Exception) { LogUtil.warn("  ev7.b 未找到") }
        try { val c = Class.forName("android.app.Activity", false, classLoader); module.hook(c.getDeclaredMethod("dispatchTouchEvent", android.view.MotionEvent::class.java)).setId("tz2").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain -> if (gMasterOn && gTopZoneOn && handleTopZoneEvent(chain.getArg(0) as? android.view.MotionEvent)) true else chain.proceed() }); LogUtil.info("  ✓ dispatchTouch") } catch (e: Exception) { LogUtil.error("tz2", e) }

        // 14. 生命周期
        try {
            val c = Class.forName("android.app.Activity", false, classLoader)
            module.hook(c.getDeclaredMethod("onCreate", android.os.Bundle::class.java)).setId("oc").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain ->
                LogUtil.incr("onCreate")
                val a = chain.thisObject as? Activity
                // 这里只读取设置，不访问 Window；主题和 AppCompat 的窗口特性先照常初始化。
                try {
                    if (a != null) {
                        // 仅保存引用，供 onCreate 期间的状态栏高度 Hook 判断是否为恢复中的自由小窗。
                        gCurrentActivity = a
                        initPrefs(a.applicationContext)
                        appCtx = a.applicationContext
                        ensureReceiver(a.applicationContext)
                    }
                } catch (_: Exception) {}
                val result = chain.proceed()
                try {
                    if (a != null) {
                        if (gMasterOn && gStatusOn) applyCleanTop(a) else showStatusBar(a)
                        if (gMasterOn && gNavBarOff) applyNavBar(a) else showNavBar(a)
                        createNotification(a)
                    }
                } catch (_: Exception) {}
                result
            })
            LogUtil.info("  ✓ onCreate")
            module.hook(c.getDeclaredMethod("onResume")).setId("or").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain ->
                LogUtil.incr("onResume")
                val a = chain.thisObject as? Activity
                gCurrentActivity = a
                // 更新提示：作用域启动后如有新版，在 Activity 可见时弹一次
                try {
                    if (a != null) {
                        UpdateChecker.showUpdateDialogIfNeeded(a)
                        pollUpdatePrompt(a, 0)
                    }
                } catch (_: Exception) {}
                try {
                    if (a != null) {
                        initPrefs(a.applicationContext)
                        appCtx = a.applicationContext
                        ensureReceiver(a.applicationContext)
                    }
                } catch (_: Exception) {}
                val result = chain.proceed()
                try {
                    if (a != null) {
                        if (gMasterOn && gStatusOn) applyCleanTop(a) else showStatusBar(a)
                        if (gMasterOn && gNavBarOff) applyNavBar(a) else showNavBar(a)
                        createNotification(a)
                    }
                } catch (_: Exception) {}
                mainHandler.postDelayed({
                    scanAllWindows()
                    if (gMasterOn && gStatusOn) applyCleanTop(a) else showStatusBar(a)
                    if (gMasterOn && gNavBarOff) applyNavBar(a) else showNavBar(a)
                }, 400)
                startPeriodicScan()
                LogUtil.diagDump(true)
                result
            })
            LogUtil.info("  ✓ onResume")
            module.hook(c.getDeclaredMethod("onPause")).setId("op").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain ->
                val a = chain.thisObject as? Activity
                stopPeriodicScan()
                val result = chain.proceed()
                mainHandler.postDelayed({
                    try {
                        // 页面跳转也会触发 Activity.onPause，不能直接当作视频暂停。
                        // 仅在当前 Activity 仍可见时查询真实播放器状态。
                        if (a != null && a.hasWindowFocus() && !a.isFinishing) refreshVideoPauseState("activity-onPause")
                    } catch (_: Throwable) {}
                }, 120L)
                result
            })
            LogUtil.info("  ✓ onPause")
        } catch (e: Exception) { LogUtil.error("lifecycle", e) }

        // 15. 设置页
        try { val c = Class.forName("com.dragon.read.component.biz.impl.mine.settings.SettingsActivity", false, classLoader); module.hook(c.getDeclaredMethod("onCreate", android.os.Bundle::class.java)).setId("st").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain -> val a = chain.thisObject as? Activity; gSettingsActivity = a; val r = chain.proceed(); if (a != null) mainHandler.postDelayed({ injectSettingsButton(a) }, 800); r }); LogUtil.info("  ✓ settings") } catch (e: Exception) { LogUtil.warn("  SettingsActivity 未找到") }

        // 16. 保留原有 OLED 防灼伤亮度拦截；它只影响物理屏幕亮度。
        // 截图中能被记录下来的首页发暗由上面的 c3 播放器遮罩负责处理。
        try {
            val hClass = Class.forName("l83.h", false, classLoader)
            val brightActionClass = Class.forName("n83.a", false, classLoader)
            module.hook(hClass.getDeclaredMethod("a", java.util.List::class.java)).setId("oled").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain ->
                try {
                    val list = chain.getArg(0) as? java.util.List<*>
                    if (list != null && list.isNotEmpty()) {
                        val filtered = list.filter { it == null || !brightActionClass.isInstance(it) }
                        if (filtered.size < list.size) {
                            LogUtil.incr("oledBrightBlock")
                            val na = (chain.args as Array<Any?>).copyOf()
                            na[0] = java.util.ArrayList(filtered)
                            return@Hooker chain.proceed(na)
                        }
                    }
                } catch (_: Exception) {}
                chain.proceed()
            })
            LogUtil.info("  ✓ oledBright")
        } catch (e: Exception) { LogUtil.warn("  l83.h OLED 未找到: $e") }




        // ============================================================
        // 13. 去广告：剧集暂停广告（两个视频中间）+ 片尾广告 + 角标广告
        // ============================================================
        // 13a. 暂停广告请求入口 j4.b (FullScreenViewInjectAgency 内部)
        try { val c = Class.forName("com.dragon.read.component.shortvideo.impl.inject.view.j4", false, classLoader)
            ham(c, "b", "pauseAdEntry") { chain -> if (gMasterOn && gAdOn) null else chain.proceed() }
            LogUtil.info("  ✓ 暂停广告入口 j4.b") } catch (e: Exception) { LogUtil.warn("  j4.b 未找到: $e") }
        // 13b. SeriesPauseAdImpl（暂停广告服务实现）
        try { val c = Class.forName("com.dragon.read.ad.onestop.seriespause.impl.SeriesPauseAdImpl", false, classLoader)
            ham(c, "canShowPauseAd", "spCan") { chain -> if (gMasterOn && gAdOn) false else chain.proceed() }
            ham(c, "enablePauseAd", "spEnable") { chain -> if (gMasterOn && gAdOn) false else chain.proceed() }
            ham(c, "requestAd", "spReq") { chain -> if (gMasterOn && gAdOn) null else chain.proceed() }
            ham(c, "onPauseAdShow", "spShow") { chain -> if (gMasterOn && gAdOn) null else chain.proceed() }
            ham(c, "enableCoinBox", "spCoin") { chain -> if (gMasterOn && gAdOn) false else chain.proceed() }
            LogUtil.info("  ✓ SeriesPauseAdImpl 拦截") } catch (e: Exception) { LogUtil.warn("  SeriesPauseAdImpl 未找到: $e") }
        // 13c. 片尾广告层 AdVideoEndLayer
        try { val c = Class.forName("com.dragon.read.pages.video.layers.advideoendlayer.AdVideoEndLayer", false, classLoader)
            ham(c, "handleVideoEvent", "veAd") { chain -> if (gMasterOn && gAdOn) { try { val ev = chain.getArg(0); val m = ev?.javaClass?.getMethod("getType"); if (m != null && (m.invoke(ev) as? Int) == 102) return@ham false } catch (_: Exception) {} }; chain.proceed() }
            ham(c, "I", "veAdI") { chain -> if (gMasterOn && gAdOn) null else chain.proceed() }
            LogUtil.info("  ✓ 片尾广告层") } catch (e: Exception) { LogUtil.warn("  AdVideoEndLayer 未找到: $e") }
        // 13d. 广告图标层 AdIconLayer
        try { val c = Class.forName("com.dragon.read.pages.video.layers.adiconlayer.AdIconLayer", false, classLoader)
            ham(c, "handleVideoEvent", "aiAd") { chain -> if (gMasterOn && gAdOn) false else chain.proceed() }
            LogUtil.info("  ✓ 广告图标层") } catch (e: Exception) { LogUtil.warn("  AdIconLayer 未找到: $e") }

        // ============================================================
        // 14. 解锁 VIP（不再强制显示书封 VIP 蒙层，避免首页视频封面变暗）
        // ============================================================
        try { val c = Class.forName("com.dragon.read.component.biz.impl.privilege.PrivilegeManager", false, classLoader)
            ham(c, "isVip", "vipIsVip") { chain -> if (gVipOn) true else chain.proceed() }
            ham(c, "isAnyVip", "vipAny") { chain -> if (gVipOn) true else chain.proceed() }
            ham(c, "canReadShortStory", "vipRead") { chain -> if (gVipOn) true else chain.proceed() }
            ham(c, "hasVipShortSeriesPrivilege", "vipSeries") { chain -> if (gVipOn) true else chain.proceed() }
            ham(c, "b", "vipSubType") { chain -> if (gVipOn) true else chain.proceed() }
            ham(c, "hasNoAdFollAllScene", "vipNoAd") { chain -> if (gVipOn) true else chain.proceed() }
            ham(c, "hasNoAdForShortSeries", "vipNoAdS") { chain -> if (gVipOn) true else chain.proceed() }
            // getVipInfo / getAllVipInfo 返回 isVip="1" 的模型，让会员中心显示已解锁
            try {
                val vim = Class.forName("com.dragon.read.user.model.VipInfoModel", false, classLoader)
                val vct = Class.forName("com.dragon.read.rpc.model.VipCommonSubType", false, classLoader)
                val vc = vim.getDeclaredConstructor(String::class.java, String::class.java, String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, vct)
                vc.isAccessible = true
                val de = vct.getEnumConstants()[0]
                val fakeModel = { vc.newInstance("2099-12-31 23:59:59", "1", "99999999", true, false, 0, true, de) }
                c.declaredMethods.filter { it.name == "getVipInfo" }.forEachIndexed { i, m -> try { module.hook(m).setId("vipGetInfo_$i").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain -> if (gVipOn) fakeModel() else chain.proceed() }) } catch (_: Exception) {} }
                module.hook(c.getDeclaredMethod("getAllVipInfo")).setId("vipGetAll").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain -> if (gVipOn) java.util.Collections.singletonList(fakeModel()) else chain.proceed() })
                LogUtil.info("  ✓ getVipInfo 模型")
            } catch (e2: Exception) { LogUtil.warn("  getVipInfo 模型 hook 失败: $e2") }
            LogUtil.info("  ✓ VIP 解锁 (PrivilegeManager)") } catch (e: Exception) { LogUtil.warn("  PrivilegeManager 未找到: $e") }
        try { val c = Class.forName("com.dragon.read.component.NsUserInfoDependImpl", false, classLoader)
            ham(c, "isVip", "nsVip") { chain -> if (gVipOn) true else chain.proceed() }
            try {
                val vim = Class.forName("com.dragon.read.user.model.VipInfoModel", false, classLoader)
                val vct = Class.forName("com.dragon.read.rpc.model.VipCommonSubType", false, classLoader)
                val vc = vim.getDeclaredConstructor(String::class.java, String::class.java, String::class.java, Boolean::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, Int::class.javaPrimitiveType, Boolean::class.javaPrimitiveType, vct)
                vc.isAccessible = true
                val de = vct.getEnumConstants()[0]
                module.hook(c.getDeclaredMethod("getVipInfoModel")).setId("vipGetModel").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain -> if (gVipOn) vc.newInstance("2099-12-31 23:59:59", "1", "99999999", true, false, 0, true, de) else chain.proceed() })
                LogUtil.info("  ✓ getVipInfoModel")
            } catch (e2: Exception) { LogUtil.warn("  getVipInfoModel hook 失败: $e2") }
            LogUtil.info("  ✓ NsUserInfoDependImpl.isVip") } catch (e: Exception) { LogUtil.warn("  NsUserInfoDependImpl 未找到: $e") }
        try { val c = Class.forName("com.dragon.read.component.NsComicAdDependImpl", false, classLoader)
            ham(c, "isVipUser", "comicVip") { chain -> if (gVipOn) true else chain.proceed() }
            LogUtil.info("  ✓ NsComicAdDependImpl.isVipUser") } catch (e: Exception) { LogUtil.warn("  NsComicAdDependImpl 未找到: $e") }
        // KMP 侧账号服务：会员中心若为 KMP 页面，走 ec3.h.getVipInfo() 返回 nn5/e
        try { val c = Class.forName("ec3.h", false, classLoader)
            val eCls = Class.forName("nn5.e", false, classLoader)
            val eCtor = eCls.getDeclaredConstructor(String::class.java, String::class.java, String::class.java, java.lang.Boolean::class.java, java.lang.Boolean::class.java, Integer::class.java, java.lang.Boolean::class.java, Integer::class.java, java.lang.Boolean::class.java, Integer::class.java, java.lang.Boolean::class.java)
            eCtor.isAccessible = true
            module.hook(c.getDeclaredMethod("getVipInfo")).setId("kmpVipInfo").setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).intercept(Hooker { chain ->
                if (gVipOn) { try { eCtor.newInstance("1", "2099-12-31 23:59:59", "99999999", true, true, 0, true, 0, true, 0, true) } catch (_: Exception) { chain.proceed() } } else chain.proceed()
            })
            LogUtil.info("  ✓ ec3.h.getVipInfo") } catch (e: Exception) { LogUtil.warn("  ec3.h 未找到: $e") }
        try { val c = Class.forName("com.dragon.read.component.biz.impl.NsVipImpl", false, classLoader)
            ham(c, "isVip", "nvVip") { chain -> if (gVipOn) true else chain.proceed() }
            ham(c, "isSpecificVipOrHigher", "nvSpec") { chain -> if (gVipOn) true else chain.proceed() }
            ham(c, "canShowVipCenter", "nvCenter") { chain -> if (gVipOn) true else chain.proceed() }
            LogUtil.info("  ✓ NsVipImpl") } catch (e: Exception) { LogUtil.warn("  NsVipImpl 未找到: $e") }

        LogUtil.info("installBusinessHooks done"); LogUtil.diagDump(true)
    }

    fun installDemoHooks(module: MainHook, classLoader: ClassLoader) {
        LogUtil.info("demo")
        try { val c = Class.forName("xyz.kejiyu.hongguo.MainActivity", false, classLoader); module.hook(c.getMethod("onCreate", android.os.Bundle::class.java)).setPriority(XposedInterface.PRIORITY_DEFAULT).setExceptionMode(XposedInterface.ExceptionMode.PROTECTIVE).setId("dm").intercept(Hooker { chain -> chain.proceed() }); LogUtil.info("demo ok") } catch (e: Exception) { LogUtil.error("demo", e) }
    }
}
