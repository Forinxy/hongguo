# hongguo（红果综合模块）

红果免费短剧增强模块 —— 基于 LSPosed API 102 的 Xposed 模块，用于增强「红果免费短剧」App（包名 `com.phoenix.read`）。

## 功能

- 隐藏状态栏 / 顶部控件（可独立开关）
- 隐藏播放器工具栏、底部小白条、视频进度条
- 拦截广告 / 挂件 / 片尾广告 / 暂停广告
- 禁用下拉刷新
- 顶部区域拦截下滑
- 解锁 VIP、显示 VIP 图标
- 自由小窗 / 分屏 / 画中画适配
- 暂停后恢复控件
- OLED 防烧屏亮度拦截
- 自动更新检测（启动时静默对比 GitHub Releases 最新版本，有新版才弹窗提示）

## 环境要求

- Android 10+（minSdk 29）
- LSPosed 框架（API 102）
- 目标 App：红果免费短剧（`com.phoenix.read`）

## 构建

### 前置

- JDK 17
- Android SDK（compileSdk 36）

### 命令行

```bash
./gradlew assembleDebug
```

> 如果提示 `Permission denied`（GitHub 网页上传可能丢失可执行权限），先执行 `chmod +x gradlew`，或者直接用 `sh gradlew assembleDebug`。

产物：`app/build/outputs/apk/debug/app-debug.apk`

> 说明：debug 变体已开启 R8 压缩 + 资源收缩（`debuggable false`），直接打出小体积可安装包，无需额外配置。

### 签名（可选）

默认 debug 构建使用本机 debug 签名，可直接安装。如需用自己的正式签名，复制 `keystore.properties.example` 为 `keystore.properties` 并填写：

```properties
storeFile=/你的绝对路径/你的keystore.jks
storePassword=你的仓库密码
keyAlias=你的别名
keyPassword=你的密钥密码
```

配置后 debug 和 release 都会使用该签名。

## 更新检测

模块启动时（模块 App 或作用域 App）自动对比 GitHub Releases 最新版本号，**有新版本才弹窗提示，无更新完全静默**。

- 仓库：<https://github.com/KEJIYUNB/hongguo>
- 发布新版本时打 tag 即可（如 `v1.0.1`、`1.0.1`）

## 下载

- GitHub Releases：<https://github.com/KEJIYUNB/hongguo/releases>
- Telegram 频道：<https://t.me/Kmodify>

## 免责声明

本项目仅供学习交流使用，请勿用于商业用途。使用本项目产生的任何后果由使用者自行承担；若侵犯了您的权益，请联系删除。
