# 闪念小抄与化学工具箱

本仓库包含两个小米 Vela 轻应用和一个配套 Android 手机端。源码、文档和安装包分区存放，当前可安装包统一在 [`release/`](release/) 目录。

## 当前安装包

| 应用 | 安装包 | 包名 | 版本 |
|------|--------|------|------|
| 考点阅读器（闪念小抄） | [`release/com.whyy.snapnotes.release.V26.8.28.BAND.rpk`](release/com.whyy.snapnotes.release.V26.8.28.BAND.rpk) | `com.whyy.snapnotes` | `V26.8.28.BAND` |
| 化学工具箱 | [`release/com.whyy.chemcalc.release.V26.8.37.CALC.rpk`](release/com.whyy.chemcalc.release.V26.8.37.CALC.rpk) | `com.whyy.chemcalc` | `V26.8.37.CALC` |
| Android 手机端 | [`release/com.whyy.snapnotes.android.v1.0.1-debug.apk`](release/com.whyy.snapnotes.android.v1.0.1-debug.apk) | `com.whyy.snapnotes` | `1.0.1 / 2` |

`.rpk` 是 Vela 安装包，`.apk` 是 Android Debug 安装包。安装包索引和安装说明见 [`release/README.md`](release/README.md)。

## 源码布局

```text
.
├── 考点阅读器/                  # Vela：TXT/JSON 考点阅读、搜索、无缝阅读、传输
│   ├── src/
│   ├── samples/                 # JSON 示例
│   ├── scripts/                 # 构建兼容脚本
│   ├── sign/                    # Vela 签名文件
│   └── package.json
├── 化学计算器/                  # Vela：离线反应推导、配平、元素检索、质量计量
│   ├── src/
│   ├── tests/                   # 62 项核心逻辑冒烟测试
│   ├── scripts/
│   ├── sign/
│   └── package.json
├── android_src/
│   └── snapnotes-android/       # Android：同步自 vultra-c/Snapnotes-android
│       ├── app/src/main/java/com/whyy/snapnotes/
│       ├── app/src/main/res/
│       └── app/build.gradle.kts
├── release/                     # 当前可安装 RPK/APK
├── bandburg-kdreader.js         # BandBurg 离线传输脚本
├── docs/                        # 开发文档与历史资料归档
└── .claude/skills/              # Vela 开发规范
```

旧的废弃 Android 示例工程（包名 `com.silenthong.kdreader`）已经移除，当前唯一 Android 工程是 `android_src/snapnotes-android`，避免多个工程混淆。

## 功能概览

### 考点阅读器

- TXT 按原有章节格式解析，JSON 按知识点结构解析。
- 支持文件夹、多级目录、搜索、分页、百分比跳转和无缝长文阅读。
- 支持通过 Android 手机端或 BandBurg 脚本传输 TXT/JSON。
- 内置中文、英文输入法（已移除日文），支持拼音连打和右滑返回。
- Android 端包名与 Vela 端统一为 `com.whyy.snapnotes`。

### 化学工具箱

- 纯离线识别化学式和中文物质名称。
- 自动推导生成物、配平常规化学方程式。
- 按任意已知物质质量反推或正向计算全方程式计量结果。
- 支持按 1～3 个元素检索本地预配平反应库，可输入中文名（铁 氧）或英文符号（Fe O）。
- 首页为功能列表，点击进入对应计算界面；使用考点阅读器同款中文/英文输入法（已移除日文），所有页面支持右滑返回。
- 跨页数据通过 Vela 全局 `$app.$def` 传递（不用 `$data`，本机运行时后者不可用）；结果列表与常用物质表均使用考点阅读器同款 `list/list-item` 组件。

## 本地构建

### Vela 应用

两个 Vela 项目使用相同的 aiot-toolkit 构建方式：

```bash
cd 考点阅读器
npm install
npm run release

cd ../化学计算器
npm install
npm run release
node tests/smoke.mjs
```

构建产物首先出现在各项目的 `dist/`（这些目录被忽略）。正式包放入根目录 `release/` 后提交，文件名应与当前版本对应。

### Android 手机端

```bash
cd android_src/snapnotes-android
ANDROID_HOME=/path/to/android-sdk \\
ANDROID_SDK_ROOT=/path/to/android-sdk \\
  bash ./gradlew :app:assembleDebug
```

APK 生成在 `app/build/outputs/apk/debug/app-debug.apk`。Android 构建目录默认忽略；需要交付时，将验证后的 APK 放入根目录 `release/`。

## 文档

- [`docs/vela/kd-dev-doc/`](docs/vela/kd-dev-doc/)：Vela 开发文档
- [`docs/vela/vela-all-docs.zip`](docs/vela/vela-all-docs.zip)：Vela 文档集合
- [`docs/自然语言实现指南.md`](docs/自然语言实现指南.md)：功能实现说明
- [`docs/archive/`](docs/archive/)：旧 Android 方案和历史部署资料，仅供查阅

## 签名与版本

- Vela 两个应用分别使用各自 `sign/release/` 下的签名文件，RPK 已在 `release/` 中提供。
- Android 当前仓库提供的是 Debug APK；Android Release 签名需要在本地配置 `keystore.properties`，该文件不会提交。
- 具体版本号以各应用的 `src/manifest.json`、Android `app/build.gradle.kts` 和 `release/README.md` 为准。

## 许可证

Android 上游工程保留其 [`AGPL v3 LICENSE`](android_src/snapnotes-android/LICENSE)。本仓库对上游代码的修改遵循相应开源许可证。
