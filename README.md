# 仓库总览

本仓库包含两个相互独立的小米 Vela 轻应用：

| 项目 | 目录 | 包名 | 说明 |
|------|------|------|------|
| 考点阅读器（闪念小抄） | [`考点阅读器/`](考点阅读器/) | `com.whyy.snapnotes` | TXT/JSON 双格式手环阅读器，蓝牙传输、无缝滚动阅读 |
| 化学反应计算器 | [`化学计算器/`](化学计算器/) | `com.whyy.chemcalc` | 纯离线化学反应推导 + 智能配平 + 质量计量计算 |

两个项目源码完全分开，各自独立构建与签名；视觉风格与交互习惯保持一致（纯黑底 + 深灰圆角卡片 + 蓝色强调，右滑退出）。

---

# 考点阅读器

基于小米手环（Vela 系统）和 Android 手机端的双端阅读应用，支持通过蓝牙将手机上的 TXT 与 JSON 文件传输到手环阅读。

本项目早期基于 [弦电子书](https://github.com/youshen2/com.bandbbs.ebook) 开源项目开发，根据其 AGPL v3 许可证进行使用和修改。

## 功能特性

### 手环端（Vela）

- 文件夹与文件浏览：支持多级文件夹管理
- 阅读器：支持字体大小调节、行距调节、翻页、页码跳转、百分比跳转
- 章节目录：长文本自动分章（参考 [com.bandbbs.ebook](https://github.com/youshen2/com.bandbbs.ebook) 分段加载模型，识别「第X章/卷/节/部/篇/回、番外、Chapter N」，无章节结构时按字数兕底切段），点章节直达对应位置
- 长文性能优化：无缝滚动模式**复刻弦电子书 detail.ux 两段式无缝阅读**——只渲染 page1+page2 两个段落块（每段约 400 字，滚动由引擎自然撑开），滚到底 `@scrollbottom` 加载下一段并整体前移、滚到顶 `@scrolltop` 回退上一段，换段时用 `getBoundingClientRect` 实测段高补偿 `scroll-top`；DOM 恒定几十个节点，长文不卡顿、不叠字、可一路滚到底
- 双格式解析：TXT 按原有章节格式解析；`.json` 文件自动按知识点结构解析（显示逻辑提取自 [闪念小抄 Snapnotes-band](https://github.com/WenHuaYiYang/Snapnotes-band)）
- 知识点阅读：科目列表 → 条目列表 → 详情（描述/原文分页/编号要点/公式），返回手势逐层回退；右上角设置页可调字号/行距/常亮/删除文件（与 TXT 阅读设置共用存储键）
- 蓝牙传输（双协议）：
  - **Snapnotes 协议**（适配 [Snapnotes-android](https://github.com/vultra-c/Snapnotes-android)）：`__hs__` 三方握手/心跳保活、`file` 链路 startTransfer/d/transferComplete 状态机、`get_storage_info` 存储查询、`startFormula` 公式图片接收（base64 落盘 internal://files/formulas/）；手机端拉起 `/pages/import` 直达传输页
  - **断点续传**：传输中断后重发 startTransfer，手环从第一个缺失分片继续（ready.nextChunkIndex），BandBurg 脚本失败自动重试 2 次
  - 旧版考点传输协议保留兼容（chapter 分片模式）
- 搜索分级：主页搜索=全部考点；子文件夹内搜索=仅该文件夹子树；考点设置内=单文件；JSON 结果直达知识点阅读器
- 输入法：内置 [Vela_input_method](https://github.com/NEORUAA/Vela_input_method) 全拼连打键盘（整词候选）
- 阅读进度同步：记录阅读位置，下次打开自动恢复
- 亮屏设置：支持保持屏幕常亮
- 删除管理：支持删除单个文件/文件夹/全部考点

### Android 端

- 文件管理：支持导入 TXT、JSON（知识点）等多种格式
- JSON 校验：选择 `.json` 文件时本地校验知识点结构，无效文件拒绝发送并提示
- 蓝牙传输：通过 XMS Wearable SDK 将文件推送到手环（JSON 保留 `.json` 后缀，手环据此路由到知识点阅读器）
- 阅读数据同步：支持阅读进度双向同步
- 书签管理：支持添加和管理书签
- 阅读统计：记录阅读时间和进度

## 知识点 JSON 格式

与 [闪念小抄 Snapnotes 手机端](https://github.com/WenHuaYiYang/Snapnotes-band) 同构，顶层为「科目名 → 条目数组」：

```json
{
  "数学": [
    {
      "id": 1,
      "title": "三角函数和差公式",
      "desc": "必背公式（可选）",
      "points": ["sin(a±b) = sinA·cosB ± cosA·sinB"],
      "formulas": ["tan(a+b)=(tanA+tanB)/(1-tanAtanB)"],
      "raw": "原文段落1\n原文段落2（可选，按每页320字分页）"
    }
  ],
  "物理": [{ "title": "牛顿第二定律", "points": ["F = ma"] }]
}
```

- 仅 `title` 必填；`desc`/`raw`/`points`/`formulas` 可选，非法条目自动过滤
- 示例文件见 [`考点阅读器/samples/knowledge-sample.json`](考点阅读器/samples/knowledge-sample.json)

## 签名说明

- 手环端 RPK 签名：`考点阅读器/sign/debug` 与 `sign/release` 使用同一套 RSA2048 密钥对（`certificate.pem` + `private.pem`，取自小米官方 interconnect 开发测试证书），两端签名一致
- Android 端 APK 签名：`android-app/keystore/keystore.jks`（密码见 `android-app/keystore.properties`），证书 DN 与手环端 RPK 证书完全相同（`CN=wearable, OU=xiaomi, O=xiaomi`），同源同身份
- 成品交付包统一放在 `release/` 目录：手环 RPK 在 `考点阅读器/release/`，APK 在 `考点阅读器/android-app/release/`
- 化学计算器使用独立生成的签名（`化学计算器/sign/`，RSA2048，有效期 30 年，证书已随源码推送仓库）

## 技术栈

| 端 | 技术 |
|------|------|
| 手环端 | Vela JS (Quick App)、aiot-toolkit |
| Android 端 | Java、XMS Wearable SDK |

## 项目结构

```
examreader/
├── 考点阅读器/                    # 手环端（Vela 应用）
│   ├── src/
│   │   ├── manifest.json          # 应用清单
│   │   ├── pages/                 # 页面组件
│   │   │   ├── index/             # 首页（文件夹列表）
│   │   │   ├── subfolder/         # 子文件夹页
│   │   │   ├── reader/            # 阅读器页（TXT）
│   │   │   ├── jsonReader/        # 知识点阅读器（JSON，Snapnotes 显示逻辑）
│   │   │   ├── readerSettings/    # 阅读设置页
│   │   │   ├── globalSettings/    # 全局设置页
│   │   │   ├── pageJump/          # 页码跳转页
│   │   │   ├── percentJump/       # 百分比跳转页
│   │   │   ├── deleteConfirm/     # 删除确认页
│   │   │   └── push/              # 蓝牙传输页
│   │   └── common/
│   │       ├── images/            # 图片资源
│   │       ├── utils/             # 工具类
│   │       │   ├── dataManager.js     # 数据管理（含 fmt 格式标记）
│   │       │   ├── interconnfile.js   # 文件传输（TXT/JSON 自动识别）
│   │       │   ├── jsonParser.js      # 知识点 JSON 解析校验
│   │       │   ├── timeUtil.js        # 时间工具
│   │       │   └── appState.js        # 应用状态
│   │       └── style.css           # 公共样式
│   ├── samples/                   # 示例文件（知识点 JSON）
│   ├── release/                   # 已签名 RPK 交付包
│   ├── sign/{debug,release}/      # RPK 签名密钥对（两端相同）
│   ├── scripts/patch-aiotpack.js  # 构建环境兼容补丁（rspack→webpack 回退）
│   ├── android-app/               # Android 手机端源码（考点传输）
│   │   ├── app/src/main/java/com/silenthong/kdreader/
│   │   │   ├── ui/MainActivity.java   # 主界面（支持选择 TXT/JSON）
│   │   │   └── logic/TxtTransferHandler.java
│   │   ├── keystore/keystore.jks      # APK 签名
│   │   └── build_apk.sh               # APK 构建脚本（需 Java17+Android SDK）
│   └── package.json
│
├── bandburg-kdreader.js           # BandBurg 传输脚本（TXT/JSON，免装 APK）
├── android_src/
│   └── com.bandbbs.ebook-android/  # Android 端
│       ├── app/
│       │   ├── src/main/java/com/bandbbs/ebook/
│       │   │   ├── App.kt              # 应用入口
│       │   │   ├── database/           # Room 数据库
│       │   │   ├── logic/              # 蓝牙通信逻辑
│       │   │   │   ├── InterHandshake.kt   # 握手协议
│       │   │   │   ├── Interconn.kt        # 连接管理
│       │   │   │   └── InterconnetFile.kt  # 文件传输
│       │   │   ├── notifications/      # 通知服务
│       │   │   ├── ui/                 # Compose UI
│       │   │   │   ├── activity/       # Activity
│       │   │   │   ├── components/    # 组件
│       │   │   │   ├── screens/        # 页面
│       │   │   │   ├── model/          # 数据模型
│       │   │   │   ├── theme/          # 主题
│       │   │   │   └── viewmodel/      # ViewModel
│       │   │   └── utils/             # 工具类
│       │   │       ├── manager/        # 管理器
│       │   │       └── parser/         # 文件解析器
│       │   ├── src/main/res/           # 资源文件
│       │   ├── libs/
│       │   │   └── xms-wearable-lib_1.4_release.aar  # XMS SDK
│       │   └── build.gradle.kts        # 构建配置
│       ├── settings.gradle.kts
│       ├── gradle/libs.versions.toml    # 依赖版本
│       └── LICENSE.txt                 # AGPL v3 许可证
│
├── .gitignore
└── README.md
```

## 快速上手

### 手环端

```bash
cd 考点阅读器
npm install          # postinstall 会自动应用构建兼容补丁
npm run start        # 开发调试
npm run build        # 构建
npm run release      # 发布签名 RPK 到 dist/
```

### Android 端

使用仓库内脚本构建（需自备 Java 17 与 Android SDK build-tools 30.0.3，按脚本头部路径配置）：

```bash
cd 考点阅读器/android-app
bash build_apk.sh    # 产出考点传输.apk 并完成签名对齐
```

### BandBurg 脚本传输（免装 APK）

在 [BandBurg](https://bandburg.com) 的「脚本」中导入根目录的 `bandburg-kdreader.js` 并运行，即可在浏览器里完成与手环端的全部交互：

- 自动启动手环端考点阅读器并同步文件树（新建文件夹 / 刷新）
- 选择 `.txt` 文件 → 按普通文本传输，进入原阅读器（彻底按扩展名分流，不再误判为 JSON）
- 选择 `.json` 文件 → 本地预校验 Snapnotes 结构（`{"科目":[{title,...}]}`），校验通过后保留 `.json` 后缀传输，手环自动路由到知识点阅读器，并在日志中显示科目及条目数；若 `.json` 后缀但内容为纯文本，自动嗅探降级按 TXT 传输，不会拒发
- 分片传输带逐片确认；手环返回错误（如 JSON 校验失败、存储不足）时立即中止并显示原因

可直接用 `考点阅读器/samples/knowledge-sample.json` 实测。

## 版本要求

| 端 | 版本号 | 版本名 |
|------|--------|--------|
| 手环端 | 2608160 | V26.8.16.BAND（包名 com.whyy.snapnotes；内容与 V26.8.4 一致并删除 10 个未引用图片资源（约 60KB），降低包体积与内存占用；无缝模式完整移植弦电子书 detail.ux 两段式） |
| 手环端 | 260840 | V26.8.4.BAND（无缝模式完整移植弦电子书 detail.ux 两段式：page1+page2 段落块 + scroll-top 绑定 + @scrollbottom/@scrolltop 换段 + getBoundingClientRect 测高补偿，DOM 恒定、长文不卡顿不叠字可滚到底；进度改存 起始字符偏移+段内滚动像素） |
| 手环端 | 260800 | V26.8.0.BAND（包名改为 com.whyy.snapnotes 与闪念小抄仓库一致；修复无缝模式滑不动——改为增量追加渲染；移除打开时的阅读进度闪现；脚本 TXT 传输彻底修复 + JSON 内容嗅探兜底） |

两端版本需匹配才能正常建立蓝牙连接。

## 许可证

本项目早期基于 AGPL v3 授权的开源代码开发。详见 [LICENSE.txt](android_src/com.bandbbs.ebook-android/LICENSE.txt)。

## 致谢

- [弦电子书](https://github.com/youshen2/com.bandbbs.ebook) — 手环端开源项目
- [喵喵电子书](https://github.com/youshen2/com.bandbbs.ebook) — 多端设计稿
