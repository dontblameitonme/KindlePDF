# KindlePDF

将 `txt / md / docx / epub` 文档转换为 PDF，针对 **6 英寸 Kindle 阅读** 做了排版与字号优化。
基于 Kotlin + Jetpack Compose（Material 3）+ PdfBox-Android 实现的 Android 应用。

## 功能特性

- **多格式输入**：纯文本（`.txt`）、Markdown（`.md`）、Word（`.docx`）、EPUB（`.epub`），以及带图文 HTML（`.html`）。
- **远程图片**：Markdown / HTML 中的 `![alt](url)` 远程图片会自动下载并嵌入。
- **排版对齐**：内置「强制对齐」开关，按页宽拉伸/压缩字距，使左右边界整齐（避免中英混排凹凸不平）。
- **参数预设**：字体、页边距、行距、字号等参数可保存为预设，下次启动自动回选上次选项。
- **实时预览**：内置排版预览（单页大图 / 多页模式），离线也有示例兜底，避免空白。
- **自适应图标 + 深色冷启动**：采用 Material Symbols 风格图标，冷启动为深色闪屏，无白闪。
- **通用架构 APK**：同时兼容 32 位与 64 位设备。

## 目录结构

```
app/src/main/java/com/kindle/converter/
├── ui/                # Compose 界面（ConverterScreen 等）
├── domain/            # 数据模型、排版参数、解析逻辑
├── data/              # 仓库（预设持久化等）
└── ...
app/src/main/res/      # 资源（图标、主题、字符串）
```

## 构建要求

| 工具 | 版本 |
| --- | --- |
| JDK | 21 |
| Android SDK | compileSdk 34，minSdk 24 |
| Gradle | 8.11.1（AGP 8.5.0） |
| Kotlin | 2.0.0 |

PDF 后端依赖：`com.tom-roush:pdfbox-android:2.0.27.0`

## 构建步骤

```bash
# 1. 设置环境变量（Windows 示例）
set JAVA_HOME=C:\Program Files\Java\jdk-21.0.12
set ANDROID_HOME=C:\Android\Sdk

# 2. 清理并构建发布版 APK
gradlew clean assembleRelease

# 产物位于：app/build/outputs/apk/release/app-release.apk
```

> 签名配置在 `app/build.gradle.kts` 的 `signingConfigs.release`，
> 密钥与密码来自项目根目录的 `keystore.properties`（**不纳入版本控制**）。
> 自行发布时请替换为自己的密钥。

## 6 英寸 Kindle 适配要点

- **页边距收紧**：默认左右页边距较小，最大化 6 英寸屏的有效文字宽度。
- **字号与行距**：默认正文字号针对 167 ppi 的 6 英寸屏校准，保证单页可读行数。
- **强制对齐**：中英混排时通过字距微调（拉伸上限 0.25em / 压缩 0.15em）让边界整齐，避免传统换行造成的参差。
- **图片自适应**：远程图片按页宽等比缩放，防止溢出。

## 许可证

本项目仅供个人学习与使用。
