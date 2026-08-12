# 红果模块

基于 libxposed API 102 的 Android 模块源码，包名为 `xyz.kejiyu.hongguo`。

## 当前适配

- 红果免费短剧 `com.phoenix.read` 7.3.3.18
- 红果免费短剧 `com.phoenix.read` 7.3.2.32
- 红果短剧 Play `com.phoenix.read.oversea.gp` 7.3.1.32

## 环境

- JDK 17
- Android SDK 36
- Gradle Wrapper 9.0
- Android Gradle Plugin 8.13.0
- Kotlin 2.1.0
- libxposed API 102

## 构建

Linux / Termux：

```bash
./gradlew assembleRelease
```

Windows：

```bat
gradlew.bat assembleRelease
```

没有配置私有签名时可以直接构建。需要使用自己的 release keystore 时，将 `keystore.properties.example` 复制为 `keystore.properties`，再填入自己的签名信息。`keystore.properties`、`*.jks` 和 `*.keystore` 已加入 `.gitignore`。

## 项目结构

```text
app/src/main/kotlin/xyz/kejiyu/hongguo/
├── MainActivity.kt
├── MainHook.kt
├── LogUtil.kt
├── UpdateChecker.kt
└── hooks/
    ├── Hooks.kt
    └── TargetNames.kt
```

`TargetNames.kt` 保存不同目标版本的兼容映射，`Hooks.kt` 包含主要 Hook 与模块功能逻辑。

## 说明

这是第三方项目，与红果官方无关。仓库不包含目标应用 APK、签名私钥或签名密码。

当前仓库未附带 LICENSE。如需以特定开源许可证发布，请在公开前自行选择并添加。
