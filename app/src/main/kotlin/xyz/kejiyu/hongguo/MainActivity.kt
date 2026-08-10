package xyz.kejiyu.hongguo

import android.app.Activity
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var tvVersion: TextView
    private lateinit var tvStatus: TextView
    private var currentVersion: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // 状态栏/导航栏与内容区同一颜色，只有深浅两套
        applyThemeColors()

        tvVersion = findViewById(R.id.tv_version)
        tvStatus = findViewById(R.id.tv_update_status)

        // 当前版本：BuildConfig.VERSION_NAME = "1.0.0 (20260810)"
        currentVersion = BuildConfig.VERSION_NAME.substringBefore(' ').substringBefore('(')
        tvVersion.text = "当前版本：${BuildConfig.VERSION_NAME}"

        findViewById<Button>(R.id.btn_check_update).setOnClickListener { manualCheck() }
        findViewById<Button>(R.id.btn_github).setOnClickListener {
            UpdateChecker.openUrl(this, UpdateChecker.REPO_URL)
        }
        findViewById<Button>(R.id.btn_telegram).setOnClickListener {
            UpdateChecker.openUrl(this, UpdateChecker.TG_CHANNEL_URL)
        }

        // 每次打开设置页都可以重新提示一次（静默检查，有更新才弹）
        UpdateChecker.resetShown()
        silentCheck()
    }

    override fun onResume() {
        super.onResume()
        // 静默检查完成后回到界面也能弹；本进程已弹过则不会再弹
        UpdateChecker.showUpdateDialogIfNeeded(this)
    }

    /** 静默检查：无更新时什么都不显示 */
    private fun silentCheck() {
        if (!UpdateChecker.checking && UpdateChecker.latestVersion != null) {
            // MainHook 启动时已经查过了，直接用结果
            UpdateChecker.showUpdateDialogIfNeeded(this)
            return
        }
        UpdateChecker.checkUpdate(currentVersion) { latest ->
            if (latest != null) UpdateChecker.showUpdateDialogIfNeeded(this)
        }
    }

    /** 手动检查：显示状态和结果 */
    private fun manualCheck() {
        tvStatus.text = "正在检查更新…"
        UpdateChecker.checkUpdate(currentVersion) { latest ->
            tvStatus.text = when {
                latest != null -> {
                    UpdateChecker.showUpdateDialogIfNeeded(this)
                    "发现新版本：$latest"
                }
                UpdateChecker.lastError != null -> "检查更新失败：${UpdateChecker.lastError}"
                else -> "已是最新版本"
            }
        }
    }

    /** 通知栏/导航栏与内容区同一颜色：浅色=白，深色=深灰，跟随系统自动切换 */
    private fun applyThemeColors() {
        val dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        val bg = if (dark) 0xFF1E1E1E.toInt() else 0xFFFFFFFF.toInt()
        window.statusBarColor = bg
        window.navigationBarColor = bg
        window.decorView.setBackgroundColor(bg)
        findViewById<View>(R.id.root)?.setBackgroundColor(bg)
        if (Build.VERSION.SDK_INT >= 30) {
            val lightBars = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            window.insetsController?.setSystemBarsAppearance(
                if (dark) 0 else lightBars,
                lightBars
            )
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                if (dark) 0 else (View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR)
        }
    }
}
