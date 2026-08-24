package com.whyy.snapnotes.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Notes

/**
 * 可折叠的「公式教程」，公式板块从 JSON 教程里单独拎出来讲。
 * 介绍 formulas 字段的写法、渲染与推送流程、以及常见问题。
 */
@Composable
fun FormulaTutorial(modifier: Modifier = Modifier) {
    TutorialCard(
        title = "公式教程",
        subtitle = "formulas 怎么写、怎么自动渲染推送到手环",
        icon = MiuixIcons.Notes,
        modifier = modifier
    ) {
        MarkdownText(FORMULA_TUTORIAL_MD)
    }
}

private val FORMULA_TUTORIAL_MD = """
# 公式图推送与显示指南

## 一句话原理

在条目的 `formulas` 数组中写好公式后，**JSON 推送完成时，系统会自动将每条公式逐一渲染成独立的公式图**，并按顺序推送到手环。手环通过「科目名#id」的索引键关联图片，知识点详情页的公式区即可正常展示。

---

## JSON 中的公式写法

`formulas` 字段为字符串数组，每个字符串代表一条公式，直接写在条目中即可。

### 示例

```json
{
  "拓展物理": [
    {
      "id": 1,
      "title": "时间膨胀",
      "formulas": [
        "t = t0 / sqrt(1 - v^2/c^2)",
        "L = L0 * sqrt(1 - v^2/c^2)"
      ]
    }
  ]
}
```

### 书写规则

- **支持数学文本**
  如 `t = t0 / sqrt(1 - v^2/c^2)`，√、½、上标等符号均能正常转换。若命中内置的 168 条常用公式映射表，将直接采用手环内置渲染样式，效果更佳。

- **支持 LaTeX 语法**
  例如 `\frac{-b \pm \sqrt{b^2 - 4ac}}{2a}` 亦可识别。

- **多公式垂直排列**
  同一条目下的多条公式会纵向堆叠成一张图片，公式之间自动留有合理间距，规格与手环内置公式图一致。

---

## 使用注意事项

- **科目名与 id 须严格匹配**
  手环以「科目名#id」作为图片索引，任何字符（包括大小写、全半角）差异均可能导致图片无法显示。

- **公式长度控制**
  建议保持单条公式不换行。若宽度超出 336px，系统会自动等比缩放，但仍可能影响阅读体验。

- **重复推送是幂等操作**
  同一 JSON 文件多次推送时，系统会覆盖同名旧文件，不会产生重复数据。

- **删除同步清理**
  从手环端删除某个导入科目时，该科目下所有已推送的公式图会自动一并清理。

- **编辑预览与手环效果一致**
  手机编辑页中公式区的实时预览，与推送到手环后的显示效果采用**同一渲染引擎**，所见即所得。

---

## 排查要点（按现象分类）

### 公式区不显示

请按以下顺序逐一排查：

1. 检查科目名与条目中的 `id` 是否与 JSON 数据中的索引键完全一致（含大小写、空格、全半角）。
2. 确认该条目是否包含 `formulas` 字段，且不为空数组。
3. 查看结果页是否存在报错提示——可能该条在渲染或推送过程中被跳过。

### 图片被压缩变形

通常是因为手动指定的 `w`（宽度）或 `h`（高度）参数与 PNG 图片的实际像素不匹配。
正常推送流程中，手机端会自动读取图片真实尺寸，因此不会出现此问题；若自定义参数，则可能引起拉伸或压缩。

### 传输进度卡住

根本原因：发送端未等待手环回执即发送了后续数据。
由于 BLE 为单通道通信，必须具备流控机制——手机端按回执逐片确认。发送端应遵循 **“发一条 → 等回执 → 发下一条”** 的节奏，否则进度会停滞。
""".trimIndent()