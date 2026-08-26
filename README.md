# 闪念小抄与化学工具箱

本仓库包含两个小米 Vela 轻应用和一个配套 Android 手机端。源码、文档和安装包分区存放，当前可安装包统一在 [`release/`](release/) 目录。

## 当前安装包

| 应用 | 安装包 | 包名 | 版本 |
|------|--------|------|------|
| 考点阅读器（闪念小抄） | [`release/com.whyy.snapnotes.release.V26.8.30.BAND.rpk`](release/com.whyy.snapnotes.release.V26.8.30.BAND.rpk) | `com.whyy.snapnotes` | `V26.8.30.BAND` |
| 化学工具箱 | [`release/com.whyy.chemcalc.release.V26.8.39.CALC.rpk`](release/com.whyy.chemcalc.release.V26.8.39.CALC.rpk) | `com.whyy.chemcalc` | `V26.8.39.CALC` |
| Android 手机端 | [`release/com.whyy.snapnotes.android.v1.0.1-debug.apk`](release/com.whyy.snapnotes.android.v1.0.1-debug.apk) | `com.whyy.snapnotes` | `1.0.1 / 2` |

`.rpk` 是 Vela 安装包，`.apk` 是 Android Debug 安装包。安装包索引和安装说明见 [`release/README.md`](release/README.md)。

## 源码布局

```
.
├── 考点阅读器/                  # Vela：TXT/JSON 考点阅读、搜索、分页、传输
│   ├── src/                     # 源码（页面、组件、工具函数）
│   ├── samples/                 # JSON 示例文件
│   ├── scripts/                 # 构建辅助脚本
│   ├── sign/                    # Vela 签名文件
│   └── package.json
├── 化学计算器/                  # Vela：反应推导、配平、元素检索、质量计量
│   ├── src/                     # 源码
│   ├── tests/                   # 核心逻辑冒烟测试
│   ├── scripts/
│   ├── sign/
│   └── package.json
├── android_src/
│   └── snapnotes-android/       # Android：配套手机端应用
│       ├── app/src/main/java/com/whyy/snapnotes/
│       └── app/build.gradle.kts
├── release/                     # 当前可安装 RPK/APK
├── bandburg-kdreader.js         # BandBurg 离线传输脚本
├── docs/                        # 开发文档与历史资料归档
└── .claude/skills/              # Vela 开发规范
```

## 功能概览

### 考点阅读器

- TXT 按章节格式解析，JSON 按知识点结构解析（科目→条目→要点/原文/公式）。
- 支持文件夹多级目录、搜索（全局/文件夹/单文件）、分页、百分比跳转。
- 支持通过 Android 手机端或 BandBurg 脚本传输 TXT/JSON。
- 公式图显示 + 缩放控件。
- 内置中文/英文输入法，支持拼音连打和右滑返回。
- 大文件夹分块懒加载，优化打开性能。

### 化学工具箱

- 纯离线识别化学式和中文物质名称（如 Fe+O2 或 铁+氧气）。
- 自动推导生成物、配平常规化学方程式。
- 按任意已知物质质量反推或正向计算全方程式计量结果。
- 按 1~3 个元素检索本地预配平反应库。
- 常用物质表分块加载，点选自动填入反应计算输入框。
- 首页为功能列表，使用考点阅读器同款输入法。

### Android 手机端

- 小米 XMS Wearable SDK 连接手环，双向同步文件树。
- 分片传输 TXT/JSON 文件到手环。
- JSON 知识点文件支持公式图渲染。
- Amadeus AI 聊天集成（可选）。

## 本地构建

### Vela 应用

```bash
cd 考点阅读器 && npm install && npm run release
cd ../化学计算器 && npm install && npm run release
```

构建产物在各项目的 `dist/`，正式包放入 `release/` 后提交。

### Android 手机端

```bash
cd android_src/snapnotes-android
./gradlew :app:assembleDebug
```

## 文档

- [`docs/vela/kd-dev-doc/`](docs/vela/kd-dev-doc/)：Vela 开发文档
- [`docs/自然语言实现指南.md`](docs/自然语言实现指南.md)：手环与手机数据交换系统实现说明
- [`docs/archive/`](docs/archive/)：旧方案和历史资料，仅供查阅

## 签名与版本

- Vela 两个应用分别使用各自 `sign/release/` 下的签名文件。
- Android 当前为 Debug APK；Release 签名需本地配置 `keystore.properties`（不提交）。
- 版本号以各应用 `src/manifest.json`、Android `app/build.gradle.kts` 和 `release/README.md` 为准。
