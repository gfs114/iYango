# Third-Party Reference Attribution: XJAU-Helper (MIT)

In implementing iYango's **Credit Progress Tracking** (`学分进度`) feature, parts of the academic-system domain semantics and progress tracking design were informed by the open-source reference repository **XJAU-Helper**:

- **Repository**: <https://github.com/FengHDSL/XJAU-Helper>
- **Version / Package**: `1.4.1` (`com.fenghdsl.xjau_helper`)
- **Snapshot Verification (SHA-256)**: `17B6E5976C90B29686E4DC1E6031D9AA9EA648E2EE696A8226BF34CE1BED0658`
- **Usage Scope in iYango**:
  - Referenced conceptually for graduation credit requirement categorization, overall/category completion progress UX, and retake single-credit handling concepts.
  - All implementation code in iYango (`:core:model`, `:core:domain`, `:data`, `:feature:main`) was written from scratch in Kotlin cleanly adapted for **Yango University (阳光学院, YGU)** educational system semantics, `BigDecimal` arithmetic, encrypted local credential storage, and offline Room persistence. No upstream code was copied verbatim.

## MIT License Notice

```text
MIT License

Copyright (c) 2026 FengHDSL

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```
