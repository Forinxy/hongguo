package xyz.kejiyu.hongguo

import android.util.Log
import io.github.libxposed.api.XposedModule
import io.github.libxposed.api.XposedModuleInterface
import xyz.kejiyu.hongguo.hooks.Hooks
import io.github.libxposed.api.XposedInterface
import java.lang.reflect.Method

class MainHook : XposedModule() {

    companion object {
        private const val TAG = "ZongHe"
        private const val TARGET_PACKAGE = "com.phoenix.read"
        private const val DEMO_PACKAGE = "xyz.kejiyu.hongguo"
    }

    override fun onModuleLoaded(param: XposedModuleInterface.ModuleLoadedParam) {
        LogUtil.init()
        LogUtil.info("══════════ 模块加载 ══════════")
        LogUtil.info("进程=${param.processName} | systemServer=${param.isSystemServer}")
        LogUtil.info("框架=${frameworkName} v${frameworkVersion} | API=${apiVersion}")
        LogUtil.info("日志文件=${LogUtil.getFilePath()}")
        LogUtil.info("══════════════════════════════")
        log(Log.INFO, TAG, "模块已加载 | 进程=${param.processName} | API=${apiVersion}")

        // ===== 更新检查：App 或作用域每次启动时，对比 GitHub 最新版本号 =====
        try {
            val ver = BuildConfig.VERSION_NAME.substringBefore(' ').substringBefore('(')
            LogUtil.info("更新检查启动 | 当前版本=$ver")
            UpdateChecker.checkUpdate(ver) { latest ->
                if (latest != null) {
                    LogUtil.info("发现新版本 $latest（当前 $ver）")
                    Hooks.showUpdateDialogIfAvailable()
                } else if (UpdateChecker.lastError != null) {
                    LogUtil.warn("更新检查失败: ${UpdateChecker.lastError}")
                }
            }
        } catch (e: Exception) {
            LogUtil.error("更新检查启动失败", e)
        }
    }

    override fun onPackageLoaded(param: XposedModuleInterface.PackageLoadedParam) {
        val pkg = param.packageName
        if (pkg != TARGET_PACKAGE && pkg != DEMO_PACKAGE) return

        LogUtil.info("onPackageLoaded: $pkg | first=${param.isFirstPackage}")
        val cl = param.defaultClassLoader
        if (cl == null) { LogUtil.warn("defaultClassLoader=null"); return }

        if (pkg == DEMO_PACKAGE) {
            LogUtil.info("  → 安装演示 Hook")
            try { Hooks.installDemoHooks(this, cl); LogUtil.info("  ✓ 完成") }
            catch (e: Exception) { LogUtil.error("演示 Hook 失败", e) }
        }
        if (pkg == TARGET_PACKAGE) {
            LogUtil.info("  → TARGET_PACKAGE，等待 onPackageReady")
        }
    }

    override fun onPackageReady(param: XposedModuleInterface.PackageReadyParam) {
        val pkg = param.packageName
        if (pkg != TARGET_PACKAGE && pkg != DEMO_PACKAGE) return

        LogUtil.info("onPackageReady: $pkg")
        LogUtil.info("  classLoader=${param.classLoader}")
        LogUtil.info("  appComponentFactory=${param.appComponentFactory}")

        if (pkg == TARGET_PACKAGE) {
            LogUtil.info("  → 安装业务 Hook")
            try {
                Hooks.installBusinessHooks(this, param.classLoader)
                LogUtil.info("  ✓ 业务 Hook 安装完成")
            } catch (e: Exception) {
                LogUtil.error("业务 Hook 安装失败", e)
            }
        }
    }

    override fun onSystemServerStarting(param: XposedModuleInterface.SystemServerStartingParam) {
        LogUtil.info("system_server 启动")
    }

    override fun onHotReloading(param: XposedModuleInterface.HotReloadingParam): Boolean {
        LogUtil.info("热重载中...")
        return true
    }

    override fun onHotReloaded(param: XposedModuleInterface.HotReloadedParam) {
        LogUtil.info("热重载完成, 旧 hook=${param.oldHookHandles.size}")
        LogUtil.diagDump(true)
    }
}
