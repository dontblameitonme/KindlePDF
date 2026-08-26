Kindle PDF — Font Setup
=================================

This app requires a CJK (Chinese-Japanese-Korean) font to render text in PDFs.

To set up the default font:

1. Download Noto Sans SC (思源黑体) from Google Fonts:
   https://fonts.google.com/noto/specimen/Noto+Sans+SC

2. Place the following files in this directory:
   - NotoSansSC-Regular.otf  (or .ttf)
   - NotoSansSC-Bold.otf     (or .ttf, optional but recommended)

   Alternative fonts that work well:
   - Noto Serif SC (思源宋体) — for a book-like reading experience
   - LXGW WenKai (霞鹜文楷) — open-source, free for commercial use

3. For smaller APK size, subset the font using Python fonttools:
   pip install fonttools
   pyftsubset NotoSansSC-Regular.otf --text-file=charset.txt --output-file=NotoSansSC-Regular-subset.otf

   Where charset.txt contains the characters you need (e.g. common Chinese + ASCII).

If no font is found in assets/fonts/, the app will prompt you to import a font
from device storage via the Settings panel.
