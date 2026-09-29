# KindlePDF

将 `txt / md / docx / epub / html / pdf` 文档重新排版并转换为 PDF，针对 **6 英寸 Kindle 等墨水屏阅读器** 做了深度中文排版与图片自适应优化。  
基于 Kotlin + Jetpack Compose（Material 3）+ PdfBox-Android 实现的纯本地 Android 应用。

---

## 功能特性

- **多格式输入支持**：
  - 纯文本（`.txt`，自动识别 UTF-8 / UTF-16 / GBK 编码，兼容单换行与空行分段，自动去重段首全角空格）
  - Markdown（`.md` / `.markdown`，支持标题、列表、引用、代码块及图文混排）
  - Word 文档（`.docx`，零 POI 依赖直接解析 OOXML，支持标题层级、列表、表格文字及内嵌图片提取）
  - 电子书（`.epub`，支持 EPUB 2 NCX / EPUB 3 Nav 目录、相对路径消解、`<img/>` 与 `<svg><image/></svg>` 封面/插图提取）
  - 网页（`.html` / `.htm`）与现有 PDF 重排（`.pdf`，支持页眉页脚/页码清理与图片重提取）
- **专业中文排版引擎（符合 GB/T 15834 与 W3C CLREQ 标准）**：
  - **行首/行尾禁则（Kinsoku Shori）**：智能采用「优先悬挂/挤入（Pull-in）+ 退字法（Push-out）」双策略，杜绝句读点号、右引号、右括号出现在行首，杜绝左引号、左括号出现在行尾。
  - **视觉悬挂标点（Optical Hanging Punctuation）**：全角句读标点（`，。、；：！？`）墨迹位于左半角（`0.5 em`），右半角自带留白；在行末触发悬挂时自动扣除 `0.45 em` 右侧空白并锁定字距不负向压缩，保证右边界在视觉上笔直齐平。
  - **两端对齐（Justify）与拉丁断词**：非段末行自动微调字距（拉伸上限 `+0.25 em`、压缩下限 `-0.10 em`）；英文单词仅在连续拉丁字符内部按空格回退断行，绝不误伤前方汉字；纯汉字与拉丁字母/数字之间自动插入 `0.20 em` 视觉间距。
  - **孤行控制（Widow Control）**：自动避免段落末行仅剩 1 个字符落单。
- **全链路图片自适应排版**：
  - 本地（EPUB / DOCX / PDF）与远程（Markdown / HTML）图片自动探测真实像素尺寸与格式（PNG / JPEG / GIF / WebP），保持原始宽高比。
  - 严格限制单图最大高度不超过单页可用版心高度，超高长图自动按比例缩放并水平居中，杜绝图片跨页死循环或被静默丢弃。
- **参数预设与实时预览**：
  - 字体、页边距、行距、字号、首行缩进、段间距等参数可保存为预设，启动自动恢复。
  - 内置单页/多页排版实时预览与离线《故乡》中英混排示例预览。

---

## 目录结构与核心模块说明（维护指南）

```text
app/src/main/java/com/kindle/converter/
├── MainActivity.kt                  # 应用入口 Activity
├── data/
│   └── Models.kt                    # 核心数据模型（Document, Block, TextRun, PageLayout, TypesettingParams）
├── parser/                          # 多格式文档解析层（统一输出 Document 模型）
│   ├── DocumentParser.kt            # 格式路由分发器（按后缀名 + 文件头 Magic Bytes 嗅探）
│   ├── TxtParser.kt                 # TXT 解析：编码探测、CRLF 归一化、单/双换行分段、段首全角空格清理
│   ├── MarkdownParser.kt            # Markdown 解析：基于 CommonMark AST，保持段落内图文相对顺序
│   ├── HtmlParser.kt                # HTML 解析：轻量级块级切分、实体解码与 <img> 抽取
│   ├── EpubParser.kt                # EPUB 解析：OPF/Spine/TOC 解析、相对路径规范化（../ 消解）、<img> 与 <svg><image/> 提取
│   ├── DocxParser.kt                # DOCX 解析：document.xml 与 _rels/document.xml.rels 关系映射、DrawingML/VML 内嵌图片提取
│   ├── PdfParser.kt                 # PDF 重排解析：基于 Y 坐标容差聚合行、智能识别中英文间距、页眉页脚过滤与位图提取
│   └── ImageDownloader.kt           # 远程图片下载与通用位图尺寸探测（inJustDecodeBounds 元数据读取、文件头魔数识别）
├── typeset/                         # 排版引擎核心层
│   ├── ChineseTypography.kt         # 中文标点禁则表（避头/避尾/右半角留白标点）、CJK 字符分类与中西文间距判定
│   ├── TextMeasurer.kt              # 基于 PdfBox PDFont 的字形宽度精确测量与带缓存计算
│   └── TypesettingEngine.kt         # 核心排版管线：LineBreaker（断行/禁则/断词）→ Paginator（分页/两端对齐/悬挂标点/图片缩放）
├── pdf/                             # PDF 渲染与持久化层
│   ├── FontManager.kt               # 自定义 TTF/OTF 字体导入、管理与 PdfBox 字体加载
│   ├── PdfGenerator.kt              # 将 PageLayout 渲染为最终 PDF 文件（含大纲书签、字距矩阵绘制、图片绘制）
│   └── PresetRepository.kt          # 排版参数与预设持久化存储
├── ui/                              # Jetpack Compose 界面层
│   ├── ConverterScreen.kt           # 主界面（文件选择、字体管理、排版参数面板、预览弹窗）
│   ├── PagePreviewCanvas.kt         # Compose Canvas 实时排版预览渲染器
│   └── Theme.kt                     # Material 3 主题配色
└── viewmodel/
    └── ConverterViewModel.kt        # 状态管理、异步解析转换管线与同目录文件输出回退逻辑
```

---

## 核心技术细节与近期重要修复说明

为方便后续迭代维护，以下记录关键模块的算法设计与注意事项：

### 1. 中文排版引擎（`ChineseTypography.kt` & `TypesettingEngine.kt`）
- **字符分类边界（`ChineseTypography.isCJK`）**：
  中文通用标点（如弯引号 `“”‘’`、破折号 `—`、省略号 `…`、间隔号 `·`）位于 Unicode General Punctuation 区段（`\u2000..\u206F`）。必须在 `isCJK(c)` 中将其显式纳入，且在 `isLatinWordChar(c)` 中严格限定为 ASCII/Latin 字母数字与词内连字符，否则英文断词回退（Word-wrap rollback）会把跟在中文弯引号后的汉字误判为“超长英文单词”而在行中强行截断。
- **避头（`lineStartForbidden`）与避尾（`lineEndForbidden`）算法**：
  - **避头**：当下一个字符为行首禁用标点时，先检查后续连续避头标点序列（例如 `”，` 或 `。）`）的总宽度。若单标点越界量 `<= 1.05 em`，允许悬挂在当前行尾；若连续多个避头标点导致越界过宽，则触发**退字法（`tryPushOutForKinsoku`）**，将当前行末尾的 1 个汉字回退到下一行，让标点跟随该字安全落入下一行第 2 列之后。
  - **避尾**：当行末遗留前引号/左括号（如 `“（《`）时，调用 `rollbackTrailingLineEndForbidden` 将连续的避尾标点整体回退到下一行行首，禁止在行尾拉入后续正文字符导致溢出。
- **两端对齐（`JUSTIFY`）与视觉悬挂标点**：
  - 在 `Paginator.paginate` 中，若行末因避头标点悬挂导致 `rawContentWidth > avail`，且末字符属于 `rightBlankFullWidthPunctuation`（`0.45 em` 右侧留白），先从目标对齐宽度中扣除该留白宽度。
  - 凡是带有行尾悬挂标点的行（`endsWithHangingPunct == true`），禁止施加负向 `charSpacing` 压缩，避免行尾多出的半个标点把整行正常汉字挤扁。

### 2. 图片提取与分页管线（`ImageDownloader` / `EpubParser` / `DocxParser` / `PdfParser` / `Paginator`）
- **位图真实尺寸探测（`ImageDownloader.decodeDimensions`）**：
  使用 Android `BitmapFactory.Options().apply { inJustDecodeBounds = true }` 时，`BitmapFactory.decodeByteArray(...)` 按 Android 规范**必然返回 `null`**，真实宽高保存在 `opts.outWidth` 与 `opts.outHeight` 中。切勿判断返回值非空，否则会导致宽高回退为硬编码值造成图片拉伸变形。
- **EPUB 路径消解（`EpubParser.resolvePath`）**：
  EPUB 章节（如 `OEBPS/Text/ch1.xhtml`）经常通过 `../Images/fig1.jpg` 引用图片。解析器必须对路径按 `/` 栈消解 `.` 与 `..`，并执行 `URLDecoder.decode` 处理百分号编码文件名。
- **DOCX 关系映射（`DocxParser`）**：
  DOCX 正文中的图片通过 `<a:blip r:embed="rId..."/>` 或 `<v:imagedata r:id="rId..."/>` 引用 `word/_rels/document.xml.rels` 中的 `Relationship`，对应 `word/media/*` 下的二进制文件。
- **PDF 图片提取（`PdfParser.extractImagesAsJpeg`）**：
  `PDImageXObject.width/height` 的单位是**像素（px）**，而 `PDPage.mediaBox.width/height` 的单位是 **PDF 点（pt，1/72 英寸）**。切勿直接比较两者数值大小，否则高分辨率插图会被误判为“全页尺寸背景”而被丢弃。
- **图片高度上限与分页安全（`TypesettingEngine.computeImageFit` & `Paginator.paginate`）**：
  图片缩放后的最大高度必须严格受限于单页可用高度 `params.availableHeight - params.paragraphSpacing`。在 `Paginator` 中若剩余空间不足则先换新页，换页后再次执行 `coerceAtMost(maxPageImageHeight)` 兜底，防止超高图片无法放入任何页面而被丢弃。

---

## 构建要求与打包步骤

| 工具 | 版本 |
| --- | --- |
| JDK | 17 或 21 |
| Android SDK | `compileSdk 34`，`minSdk 24` |
| Gradle | 8.11.1（AGP 8.5.0） |
| Kotlin | 2.0.0 |
| 目标架构 | `arm64-v8a` |

### 命令行构建（Windows 示例）

```powershell
# 1. 设置环境变量
$env:JAVA_HOME = "C:\Program Files\Java\jdk-21.0.12"
$env:ANDROID_HOME = "C:\Android\Sdk"

# 2. 构建 arm64-v8a Release APK
.\gradlew.bat assembleRelease

# 产物路径：
# app/build/outputs/apk/release/app-arm64-v8a-release.apk
```

> **签名说明**：发布签名配置位于 `app/build.gradle.kts` 的 `signingConfigs.release`，自动读取项目根目录的 `keystore.properties`（已加入 `.gitignore`，包含 `storeFile`、`storePassword`、`keyAlias`、`keyPassword`）。

---

## 许可证

本项目仅供个人学习与使用。

