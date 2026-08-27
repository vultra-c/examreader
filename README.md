# 闪念小抄（考点阅读器 · 手环端）

本仓库包含小米 Vela 轻应用「考点阅读器（闪念小抄）」手环端源码、文档和安装包。
配套项目已拆分为三个仓库，分别维护：

| 仓库 | 内容 | 安装包 |
|------|------|--------|
| 本仓库（examreader） | 手环端考点阅读器 | `release/com.whyy.snapnotes.release.V26.8.30.BAND.rpk` |
| [vultra-c/Snapnotes-android](https://github.com/vultra-c/Snapnotes-android) | Android 手机端配套应用 | Actions artifact（每次提交自动构建） |
| [vultra-c/Chemical-calculator](https://github.com/vultra-c/Chemical-calculator) | Vela 化学工具箱 | Actions artifact（每次提交自动构建） |

三个仓库均配置了 GitHub Action：每次 push 到 main 自动构建发行版安装包并上传 artifact。

## 源码布局

```
.
├── 考点阅读器/                  # Vela：TXT/JSON 考点阅读、搜索、分页、传输
│   ├── src/                     # 源码（页面、组件、工具函数）
│   ├── samples/                 # JSON 示例文件
│   ├── scripts/                 # 构建辅助脚本
│   ├── sign/                    # Vela 签名文件（release 用，勿换）
│   └── package.json
├── release/                     # 当前手环端 RPK
├── bandburg-kdreader.js         # BandBurg 离线传输脚本
├── docs/                        # 开发文档与历史资料归档
└── .claude/skills/              # Vela 开发规范
```

## 功能概览

- TXT 按章节格式解析，JSON 按知识点结构解析（科目→条目→要点/原文/公式）。
- 支持文件夹多级目录、搜索（全局/文件夹/单文件）、分页、百分比跳转。
- 支持通过 Android 手机端（Snapnotes-android 仓库）或 BandBurg 脚本传输 TXT/JSON。
- 公式图显示 + 缩放控件。
- 内置中文/英文输入法，支持拼音连打和右滑返回。
- 大文件夹分块懒加载，优化打开性能。

## 本地构建

```bash
cd 考点阅读器 && npm install && npm run release
```

构建产物在 `考点阅读器/dist/`，正式包放入 `release/` 后提交。

## 签名与连接

- 手环端 rpk 使用 `考点阅读器/sign/release/` 下的证书（CN=snapnotes），**与上传到仓库的根证书一致，不可更换**（换手环端签名会导致已安装用户无法覆盖升级）。
- 手环连接只校验「包名 `com.whyy.snapnotes` + `__hs__` 握手版本号」，与 APK 签名无关；
  Android 端使用 Snapnotes-android 仓库入库的共享签名 `app/snapnotes.p12`（同源私钥合成）。

## 文档

- [`docs/vela/kd-dev-doc/`](docs/vela/kd-dev-doc/)：Vela 开发文档
- [`docs/自然语言实现指南.md`](docs/自然语言实现指南.md)：手环与手机数据交换系统实现说明
- [`docs/archive/`](docs/archive/)：旧方案和历史资料，仅供查阅

## 版本

版本号以 `考点阅读器/src/manifest.json` 与 `release/README.md` 为准。
