# KindlePDF

将 `txt / md / docx / epub / html / pdf` 文档重新排版并转换为 PDF，针对 **6 英寸 Kindle 等墨水屏阅读器** 做了深度中文排版与图片自适应优化。  
基于 Kotlin + Jetpack Compose（Material 3）+ PdfBox-Android 实现的纯本地 Android 应用。

> **最新版本 v1.3.1 亮点**：
> - 🌐 **网页剪藏（Web Clipper）**：批量提取网页文章，内置三级反截断与反弹窗策略，支持双通道导出（Kindle 6" PDF / 标准 EPUB 3）。
> - 🔤 **系统默认字体零配置排版**：自动识别设备系统级中文字体，无需手动导入外部字体即可直接排版与生成 PDF。
> - 💾 **设置备份与迁移**：设置页面提供一键导入/导出 JSON 配置文件，支持跨设备无缝迁移排版预设与规则。
> - 🛠️ **稳定性加固**：彻底根治 `NetworkOnMainThreadException`，网页抓取全链路调度至后台 I/O 线程池。

---

<details>
<summary><h3>✨ 功能特性（点击展开）</h3></summary>

- **多格式输入支持**：
  - **网页剪藏（Web Clipper，深度整合 `web-to-epub` 原生能力）**：
    - **三层反截断与反弹窗策略**：攻克知乎、微信公众号、CSDN、掘金、简书等平台的强制下载 App、折叠截断与粘性遮罩弹窗——① 默认桌面端 User-Agent 伪装绕过移动端拦截；② 离线注入 38KB 原生移植版《骚扰拦截》用户脚本（支持自动静默更新与重置）；③ 隐蔽 WebView 离线渲染执行 JavaScript、自动点击「展开阅读全文」并导出完整 DOM。
    - **双通道导出（Kindle PDF / EPUB 3）**：
      - **Kindle 6 英寸 PDF 通道**：通过 `WebArticleParser` 无缝对接 KindlePDF 专业排版引擎，网页表格自动转换为真网格 `Block.TableBlock`，代码转换为 44 列等宽 `Block.CodeBlock`，多篇文章之间插入 `Block.PageBreak` 保证另起新页，每篇文章标题自动映射为 1 级大纲书签；
      - **标准 EPUB 3 通道**：纯 Kotlin 原生 `EpubBuilder` 流式打包，生成包含 NCX + Nav 双目录的合规电子书，图片自动去重并相对路径重映射。
    - **系统「分享到」一键剪藏**：已注册 `Intent.ACTION_SEND`，在系统浏览器（Chrome、Via、夸克等）或社交 App 点击「分享 -> KindlePDF」即可直接提取排版。
    - **批量链接提取与章节调序**：支持换行、空格及混合文本中多链接智能正则提取，列表支持上下调换顺序与正文快速纯净预览。
  - **纯文本**（`.txt`，自动识别 UTF-8 / UTF-16 / GBK 编码，兼容单换行与空行分段，自动去重段首全角空格，智能识别章节大纲标题）
  - **Markdown**（`.md` / `.markdown`，完整支持 **GFM 管道表格**、**ASCII / Unicode 流程图与框线图**、**多级嵌套列表**、**含列表/多段的复杂引用块（Callout）**、**水平分割线 `---`**、H1~H6 标题、代码块及图文混排）
  - **Word 文档**（`.docx`，零 POI 依赖直接解析 OOXML，支持标题层级、列表、**结构化表格 `<w:tbl>`** 及内嵌图片提取）
  - **电子书**（`.epub`，支持 EPUB 2 NCX / EPUB 3 Nav 目录、相对路径消解、**HTML `<table>` 表格**、`<img/>` 与 `<svg><image/></svg>` 封面/插图提取）
  - **网页与 PDF 重排**（`.html` / `.htm` / `.pdf`，支持页眉页脚/页码清理、大纲书签无缝继承与图片重提取）

- **开箱即用与系统默认字体回退（v1.3.1 新增）**：
  - **系统级 CJK 字体自动扫描**：自动检索 Android 系统内置中文字体路径（`/system/fonts/NotoSansSC-VF.ttf`、`NotoSansHans-Regular.otf`、`DroidSansFallback.ttf` 等）。
  - **零外部字体门槛**：未导入字体时默认启用「系统默认字体」，本地文件转换与网页剪藏导出 PDF 均可直接排版生成，彻底解决白屏或缺失字体无法使用的问题。
  - **自由切换管理**：字体选择列表中常驻提供「系统默认字体」选项，与用户导入的自定义 TTF/OTF 字体无缝切换。

- **全局配置备份与迁移（v1.3.1 新增）**：
  - **一键导出设置**：在「设置规则」页调用系统 SAF 存储框架导出为标准 JSON 配置文件（`kindle_pdf_settings.json`）。
  - **完整状态备份**：涵盖用户保存的全部排版参数命名预设（尺寸、边距、字号、行距、段距、缩进、对齐方式）、界面折叠偏好、网页抓取配置（UA 模式、正文图片下载嵌入、并发下载线程数）以及当前《骚扰拦截》用户脚本。
  - **一键导入与实时同步**：导入备份配置后，主界面排版预设列表自动热重载刷新，换机或重装时一键恢复所有工作环境。

- **结构化表格与 ASCII 图表墨水屏专项优化（v1.2.0）**：
  - **真网格表格排版引擎（`Block.TableBlock`）**：自动统计各列最大自然宽度，对紧凑列（如「营养素」「RNI」「状态」）优先保障单行零折行，剩余宽度按阻尼权重分配给长文本列；支持单元格内粗体/斜体折行、表头加粗、完整横竖网格线绘制，且**跨页时自动在新页顶部重复表头**、单行绝不跨页撕裂。
  - **ASCII / Unicode 框线流程图窄屏自适应重排与网格引擎（`Block.CodeBlock`）**：针对 Markdown 常见的决策树、全景接力图、证据金字塔、餐盘比例图（含 `┌─┐│└┘├┤┬┴┼╭╮╰╯▲▼◄►` 与中英混排）：
    - **单列框线图与树状图自适应重排（`optimizeDiagramLinesForKindle`）**：自动剥离整块冗余前导空格，将宽幅单列闭合方框（`┌───┐...└───┘`）、并列双栏树状表、横式双分式与并列双框重构为适合 6 英寸墨水屏阅读的 44 列宽度（字号达 **`9.3pt ~ 9.5pt`**，彻底告别原先缩小至 `5.0pt` 看不清的问题），自动智能折行框内/树枝长文本（保护英文单词与剂量单位如 `200㎡`、`pH 6.0～7.0` 不被劈断）并重新对齐左右竖线边框与中心连接线（`▲`/`│`/`┴`）；
    - **二维图形无损列压缩（`compact2DRows`）**：对真二维图形自动剔除冗余空白/横线列，确保最小字号不低于 `7.3pt ~ 8.8pt`；
    - **东亚半角/全角网格定位 + 宽拉丁字母防重叠**：框线与箭头符号严格锁定在 `0.5 em` / `1.0 em` 网格坐标，正文字符自动规避比例字体中宽大写字母（如 `M`、`W`、`T`）的字形重叠，且分页器自动保护闭合子方框（`┌...┐` 至 `└...┘`）不被跨页孤立切断。
  - **多级嵌套列表与悬挂缩进**：递归解析多级无序/有序列表，按层级采用 `•` / `◦` / `▪` 符号区分，并实现真正的**悬挂缩进（Hanging Indent）**——折行后的第 2、3 行与首行正文严格左对齐。
  - **增强型引用块（Callout）与分割线**：支持引用块内包含多个段落与列表，并在左侧自动绘制竖向装饰条（跨页自动分段）；支持 `---` / `<hr>` 水平分割线绘制。
  - **中文语境 `**加粗**` 100% 精准识别与 PDF 伪粗体（Synthetic Bold）**：预处理解决 CommonMark §6.2 侧翼定界符规则在中文标点（`“”（）《》【】%℃～`）与汉字紧邻时导致 `**...**` 失效或反向加粗的顽疾；当用户仅导入单字重（Regular）中文字体时，PDF 渲染器自动启用 `RenderingMode.FILL_STROKE` 笔画微调描边使标题、表头与行内 `**加粗**` 呈现鲜明黑体效果，兼顾清晰对比度与复杂汉字笔画通透度；当字体缺失 `╭╮╰╯►◄▲▼◦▪` 等特殊符号时自动降级为 `┌┐└┘→←↑↓·•`，杜绝符号丢失或错位。

- **专业中文排版引擎（符合 GB/T 15834 与 W3C CLREQ 标准）**：
  - **行首/行尾禁则（Kinsoku Shori）**：智能采用「优先悬挂/挤入（Pull-in）+ 退字法（Push-out）」双策略，杜绝句读点号、右引号、右括号出现在行首，杜绝左引号、左括号出现在行尾。
  - **视觉悬挂标点（Optical Hanging Punctuation）**：全角句读标点（`，。、；：！？`）墨迹位于左半角（`0.5 em`），右半角自带留白；在行末触发悬挂时自动扣除右侧空白并锁定字距不负向压缩，保证右边界在视觉上笔直齐平。
  - **两端对齐（Justify）与拉丁断词**：非段末行自动微调字距（拉伸上限 `+0.25 em`、压缩下限 `-0.15 em`）；英文单词仅在连续拉丁字符内部按空格回退断行，绝不误伤前方汉字；纯汉字与拉丁字母/数字之间自动插入 `0.20 em` 视觉间距。
  - **孤行/寡行控制（Orphan & Widow Control）**：自动避免段落首行孤立在页底或末行孤立在页顶。

- **全链路图片自适应排版**：
  - 本地（EPUB / DOCX / PDF）与远程（Markdown / HTML）图片自动探测真实像素尺寸与格式（PNG / JPEG / GIF / WebP），保持原始宽高比。
  - 严格限制单图最大高度不超过单页可用版心高度，超高长图自动按比例缩放并水平居中，杜绝图片跨页死循环或被静默丢弃。

- **参数预设与实时预览**：
  - 字体、页边距、行距、字号、首行缩进、段间距等参数可保存为预设，启动自动恢复。
  - 内置单页/多页排版实时预览与离线综合示例预览（含表格、引用块、多级列表、ASCII 流程图与中英混排正文）。

</details>

---

<details>
<summary><h3>📂 目录结构与核心模块说明（点击展开）</h3></summary>

```text
app/src/main/java/com/kindle/converter/
├── MainActivity.kt                  # 应用入口 Activity（支持 ACTION_SEND 网页链接接收）
├── data/
│   └── Models.kt                    # 核心数据模型（Document, Block[含 TableBlock/PageBreak], TextRun, PageLayout）
├── parser/                          # 多格式文档解析层（统一输出 Document 模型）
│   ├── DocumentParser.kt            # 格式路由分发器（按后缀名 + 文件头 Magic Bytes 嗅探）
│   ├── WebArticleParser.kt          # 网页文章 -> Document 桥接器（大纲书签、PageBreak 章节另起新页、表格/代码映射）
│   ├── TxtParser.kt                 # TXT 解析：编码探测、CRLF 归一化、单/双换行分段、段首全角空格清理与章节大纲提取
│   ├── MarkdownParser.kt            # Markdown 解析：GFM 管道表格 + 中文 **粗体** 定界符预处理 + CommonMark AST
│   ├── HtmlParser.kt                # HTML 解析：轻量级块级切分、实体解码、<hr> 与 <img> 抽取
│   ├── EpubParser.kt                # EPUB 解析：OPF/Spine/TOC 解析、<table> 结构化提取、相对路径规范化、<img> 与 <svg><image/> 提取
│   ├── DocxParser.kt                # DOCX 解析：document.xml 与 _rels 关系映射、<w:tbl> 结构化表格与 DrawingML/VML 内嵌图片提取
│   ├── PdfParser.kt                 # PDF 重排解析：基于 Y 坐标容差聚合行、智能识别中英文间距、页眉页脚过滤与位图提取
│   └── ImageDownloader.kt           # 远程图片下载与通用位图尺寸探测
├── web/                             # 网页抓取与反截断核心层（web-to-epub 整合）
│   ├── fetcher/WebFetcher.kt        # OkHttp 客户端，支持 Desktop/Mobile UA 伪装、编码嗅探、8MB 熔断与 Dispatchers.IO 调度
│   ├── extractor/                   # Readability4J 核心正文提取器 + OverlayCleaner 遮罩过滤
│   ├── script/ScriptRepository.kt   # 用户脚本持久化、ScriptCat 在线更新与内置资产重置
│   ├── settings/                    # DataStore 用户抓取偏好配置
│   └── webview/                     # 隐蔽 WebView 渲染器，支持用户脚本注入与 DOM 导出
├── settings/                        # 全局配置备份与迁移层（v1.3.1 新增）
│   └── SettingsBackupManager.kt     # JSON 格式一键导出/导入排版预设、上次排版参数、抓取设置与用户脚本
├── epub/                            # 标准 EPUB 3 电子书生成层
│   ├── EpubBuilder.kt               # 纯 Kotlin 原生 EPUB 3 + NCX/Nav 流式打包器
│   ├── XhtmlConverter.kt            # Jsoup Safelist XHTML 清洗与相对图片路径重映射
│   └── WebImageDownloader.kt        # 网页图片哈希去重下载器
├── typeset/                         # 排版引擎核心层
│   ├── ChineseTypography.kt         # 中文标点禁则表、CJK 字符分类、东亚半角/全角网格列宽计算与特殊符号降级映射表
│   └── TypesettingEngine.kt         # 核心排版管线：TextMeasurer + LineBreaker + Table/CodeBlock 自适应重排 + Paginator
├── pdf/                             # PDF 渲染与持久化层
│   ├── FontManager.kt               # 系统预装 CJK 字体扫描与自动回退、自定义 TTF/OTF 字体导入管理
│   ├── PdfGenerator.kt              # 将 PageLayout 渲染为最终 PDF（含 FILL_STROKE 伪粗体、横竖线绘制、符号降级、大纲书签）
│   └── PresetRepository.kt          # 排版参数与预设持久化存储
├── ui/                              # Jetpack Compose 界面层
│   ├── MainTabScreen.kt             # Material 3 底部导航容器（网页剪藏 / 本地文档 / 设置规则）
│   ├── ConverterScreen.kt           # 本地文档转 PDF 界面（含系统默认字体切换与参数预设卡片）
│   ├── web/                         # 网页剪藏界面（WebClipperScreen）与设置面板（WebSettingsScreen）
│   ├── PreviewCanvas.kt             # Compose Canvas 实时排版预览渲染器（支持横竖网格线与缩放拖拽）
│   └── Theme.kt                     # Material 3 主题配色
└── viewmodel/
    ├── ConverterViewModel.kt        # 本地文件转换状态管理（含字体状态、预设刷新与转换调度）
    └── WebClipperViewModel.kt       # 网页抓取状态机、并发调度与双通道导出调度器
```

</details>

---

<details>
<summary><h3>🔧 核心技术细节与近期重要修复说明（点击展开）</h3></summary>

为方便后续迭代维护，以下记录关键模块的算法设计与注意事项：

### 1. 系统默认字体发现与回退机制（v1.3.1 新增）
- **Android 系统字体扫描（`FontManager.getSystemFontFile`）**：
  Android 系统目录 `/system/fonts` 是只读的，但所有应用具有读取权限。`FontManager` 按优先级扫描候选列表中文字体（`NotoSansSC-VF.ttf`、`NotoSansHans-Regular.otf`、`DroidSansFallback.ttf` 等），并使用 PdfBox 的 `PDType0Font.load` 进行预载入验证。
- **全流程零配置回退**：
  若应用启动时检测到用户从未导入字体或外部字体已被删除，自动激活 `SYSTEM_FONT_ID`（"系统默认字体"）。无论是本地文档预览、本地转换还是网页剪藏导出 Kindle PDF，均会自动调用系统字体，彻底解决新用户首次使用时“未导入字体无法排版”的阻碍。

### 2. 全局设置备份与迁移（v1.3.1 新增）
- **统一 JSON 序列化（`SettingsBackupManager`）**：
  将 `PresetRepository`（排版预设数组、上次排版参数、折叠状态）、`WebSettingsRepository`（默认 UA、图片嵌入、并发数、自定义 UA）以及 `ScriptRepository`（《骚扰拦截》JS 正文）打包为标准 JSON 结构。
- **SAF (Storage Access Framework) 异步导入导出**：
  通过 `ActivityResultContracts.CreateDocument` 和 `OpenDocument` 触发系统原生文件选择器，在 `Dispatchers.IO` 下执行流读写；并在导入成功后通过 `ConverterViewModel.reloadPresets()` 实时通知界面重载预设列表。

### 3. 网络抓取协程调度与 `NetworkOnMainThreadException` 修复（v1.3.1 修复）
- **主线程网络隔离**：
  在 `WebFetcher.fetchHtml()` 中显式使用 `withContext(Dispatchers.IO)` 将 OkHttp 的同步调用隔离在后台 I/O 线程池中；在 `WebClipperViewModel` 中将 `refetch()` 和 `fetchOne()` 均明确调度至 `Dispatchers.IO`，严格遵守 Android `StrictMode` 规范。

### 4. 中文排版引擎（`ChineseTypography.kt` & `TypesettingEngine.kt`）
- **字符分类边界（`ChineseTypography.isCJK`）**：
  中文通用标点（如弯引号 `“”‘’`、破折号 `—`、省略号 `…`、间隔号 `·`）位于 Unicode General Punctuation 区段（`\u2000..\u206F`）。必须在 `isCJK(c)` 中将其显式纳入，且在 `isLatinWordChar(c)` 中严格限定为 ASCII/Latin 字母数字与词内连字符，否则英文断词回退（Word-wrap rollback）会把跟在中文弯引号后的汉字误判为“超长英文单词”而在行中强行截断。
- **避头（`lineStartForbidden`）与避尾（`lineEndForbidden`）算法**：
  - **避头**：当下一个字符为行首禁用标点时，先检查后续连续避头标点序列（例如 `”，` 或 `。）`）的总宽度。若单标点越界量 `<= 1.05 em`，允许悬挂在当前行尾；若连续多个避头标点导致越界过宽，则触发**退字法（`tryPushOutForKinsoku`）**，将当前行末尾的 1 个汉字回退到下一行，让标点跟随该字安全落入下一行第 2 列之后。
  - **避尾**：当行末遗留前引号/左括号（如 `“（《`）时，调用 `rollbackTrailingLineEndForbidden` 将连续的避尾标点整体回退到下一行行首，禁止在行尾拉入后续正文字符导致溢出。
- **两端对齐（`JUSTIFY`）与视觉悬挂标点**：
  - 在 `Paginator.paginate` 中，若行末因避头标点悬挂导致 `rawContentWidth > avail`，且末字符属于 `rightBlankFullWidthPunctuation`（右侧留白），先从目标对齐宽度中扣除该留白宽度。
  - 凡是带有行尾悬挂标点的行（`endsWithHangingPunct == true`），禁止施加负向 `charSpacing` 压缩，避免行尾多出的半个标点把整行正常汉字挤扁。

### 5. 结构化表格、中文加粗与 ASCII 图表排版管线
- **中文语境 `**...**` 强强调定界符预处理（`MarkdownParser.preprocessCjkStrongDelimiters`）**：
  CommonMark 0.22 规范 §6.2 规定左/右侧翼定界符（Left/Right-flanking delimiter run）在紧邻标点符号（`“”（）《》【】%℃～` 等）时，另一侧必须为空白或标点。在中文无空格排版中，诸如 `协同**“机械性消化（...）”**与**“化学性消化（...）”**` 会因第一个右 `**` 前为 `”`、后为汉字 `与` 而被判为“非右侧翼定界符”，不仅导致加粗失效、残留裸 `**`，还会与后方 `**` 错误配对把不该加粗的 `与` 加粗。`preprocessCjkStrongDelimiters` 在非代码围栏及非行内代码区间内，将成对的 `***...***` 与 `**...**` 转换为私有使用区哨兵字符（`\uE010`..`\uE013`），再由 `extractInlines` 状态机精确还原为 `bold = true` 的 `TextRun`。
- **GFM 管道表格解析（`MarkdownParser.splitSegmentsWithTables`）**：
  CommonMark 核心库默认不含 `commonmark-ext-gfm-tables`。`MarkdownParser` 在进入 AST 解析前先在非代码围栏（` ``` ` / `~~~`）区间内扫描表头行与分隔行（`:?-{1,}:?`），按未转义 `|` 切分单元格，并对每个单元格递归调用 `Parser.parse` 提取行内 `**粗体**`、`*斜体*` 与 `` `代码` ``，组装为 `Block.TableBlock`。
- **智能两段式列宽分配（`TypesettingEngine.layoutTableBlock`）**：
  在 6 英寸墨水屏窄版心（约 `222pt`）下，若简单按列均分或线性比例分配，会导致短列（「维生素B12」「推荐量」）被挤压折行。算法先锁定自然宽度 `<= 0.95 * 平均列宽` 的紧凑列使其零折行，再将剩余空间按 `naturalWidth^0.72` 阻尼权重分配给长描述列，并在 `Paginator` 跨页切分时自动在每页顶部重绘 `headerRow`。
- **代码块与 ASCII 框线图窄屏自适应重排与网格对齐（`optimizeDiagramLinesForKindle` & `layoutCodeBlock`）**：
  1. **窄屏自适应重排**：针对 6 英寸 Kindle 版心（目标 44 半角列，对应 `9.36pt` 大字号），自动剥离整块公共前导缩进，将宽幅单列闭合方框（`┌───┐...└───┘`）、双栏并列树状表、横式双分式方程、并列双框对照图重构为 44 列宽度，在框内按树枝前缀与单词/单位边界智能折行并重绘对齐右边框 `│` 与中心连接符 `▲`/`│`/`┴`；对真二维图形执行无损垂直冗余列剔除（`compact2DRows`），并对圆角框符号 `╭╮╰╯` 自动降级为全角等宽的 `┌┐└┘`。
  2. **网格锁定与宽拉丁字形防重叠**：框线与箭头符号严格锁定在 `padX + col * halfEm` 网格坐标，确保竖线像素级垂直对齐；正文字符采用 `maxOf(gridX, minNextX)` 动态防碰撞，避免比例字体中宽大写字母（如 `M`、`W`、`T`）超出 `0.5 em` 时与右侧字符发生重叠。
  3. **闭合子方框防孤立断页**：在 `Paginator.paginate` 中检测框线图内的闭合子方框（`┌...┐` 至 `└...┘` 及紧邻连接线），若当前页剩余高度不足以容纳整个子方框则整体推至下一页。
- **单字重字体的 PDF 伪粗体与笔画防糊优化（`PdfGenerator.drawPage`）**：
  当 `seg.bold == true` 且 `boldFont === regularFont` 时，通过 `contentStream.setRenderingMode(RenderingMode.FILL_STROKE)` 配合描边加粗。经墨水屏实测调优，将原先过粗的 `0.042 * fontSize` 优化为 `(seg.fontSize * 0.020f).coerceIn(0.18f, 0.30f)`：既在 300 PPI 墨水屏上保持对比鲜明，又确保笔画密集的复杂汉字（如「篇」「糖」「淀」）字腔内白清晰通透、彻底杜绝糊墨堵笔画现象；并在绘制后立即恢复 `RenderingMode.FILL`。
- **确定性书签映射与全格式目录大纲增强（v1.2.0）**：
  1. **EPUB 规范级目录与锚点精准消解（`EpubParser`）**：解析 EPUB 2 NCX（`<content src="..."/>`）与 EPUB 3 NAV（`<a href="...">`）中的相对路径与锚点 `#id`；递归收集所有 HTML 元素的 `id` 属性与 `<a name="...">` 锚点，构建映射表；每条目录项严格且唯一地绑定到正文物理块索引 `blockIndex`，根治全书书签锁定前几页目录或书末索引页的顽疾；在 EPUB Spine 章节项之间与 DOCX 分页符处插入原生 `Block.PageBreak` 保证另起新页。
  2. **TXT 经典章节智能探测（`TxtParser`）**：自动检测提取 `第[一二三四五六七八九十百千万\d]+[章节回卷折篇部集]`、`Chapter \d+`、`序言`、`前言`、`引子`、`后记`、`尾声`、`附录` 等段落为 `Block.Heading` 并构建大纲树。
  3. **PDF 重排目录大纲继承（`PdfParser`）**：遍历原始 PDF 的 `documentOutline`，精确获取书签标题与层级并继承至新版面。
  4. **书签单调递增匹配（`ConverterViewModel.createBookmarks`）**：匹配时强制从 `lastFoundPageIndex` 向后单调递增扫描，并自动过滤标点与空白符，杜绝逆向跳跃。

### 6. 图片提取与分页管线
- **位图真实尺寸探测（`ImageDownloader.decodeDimensions`）**：
  使用 Android `BitmapFactory.Options().apply { inJustDecodeBounds = true }` 时，`BitmapFactory.decodeByteArray(...)` 按 Android 规范必然返回 `null`，真实宽高保存在 `opts.outWidth` 与 `opts.outHeight` 中。
- **EPUB 路径消解（`EpubParser.resolvePath`）**：
  EPUB 章节通过 `../Images/fig1.jpg` 引用图片时，解析器必须对路径按 `/` 栈消解 `.` 与 `..`，并执行 `URLDecoder.decode` 处理百分号编码文件名。
- **图片高度上限与分页安全**：
  图片缩放后的最大高度严格受限于单页可用高度 `params.availableHeight - params.paragraphSpacing`，换页后再次执行 `coerceAtMost(maxPageImageHeight)` 兜底，防止超高长图死循环或被丢弃。

</details>

---

<details>
<summary><h3>📦 构建要求与打包步骤（点击展开）</h3></summary>

| 工具 | 版本 |
| --- | --- |
| JDK | 17 或 21 |
| Android SDK | `compileSdk 34`，`minSdk 24` |
| Gradle | 8.11.1（AGP 8.5.0） |
| Kotlin | 2.0.0 |
| 目标架构 | `arm64-v8a` |

#### 命令行构建（Windows 示例）

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

</details>

---

<details>
<summary><h3>📄 许可证（点击展开）</h3></summary>

本项目仅供个人学习与使用。

</details>
