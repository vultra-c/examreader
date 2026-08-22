# 考点阅读器

基于小米手环（Vela 系统）和 Android 手机端的双端阅读应用，支持通过蓝牙将手机上的 TXT 与 JSON 文件传输到手环阅读。

本项目早期基于 [弦电子书](https://github.com/youshen2/com.bandbbs.ebook) 开源项目开发，根据其 AGPL v3 许可证进行使用和修改。

## 功能特性

### 手环端（Vela）

- 文件夹与文件浏览：支持多级文件夹管理
- 阅读器：支持字体大小调节、行距调节、翻页、页码跳转、百分比跳转
- 双格式解析：TXT 按原有章节格式解析；`.json` 文件自动按知识点结构解析（显示逻辑提取自 [闪念小抄 Snapnotes-band](https://github.com/WenHuaYiYang/Snapnotes-band)）
- 知识点阅读：科目列表 → 条目列表 → 详情（描述/原文分页/编号要点/公式），返回手势逐层回退
- 蓝牙传输：通过 `@system.interconnect` API 接收手机端推送的 TXT / JSON 文件
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

## 版本要求

| 端 | 版本号 | 版本名 |
|------|--------|--------|
| 手环端 | 260530 | V26.5.0.BAND（新增 JSON 知识点阅读） |

两端版本需匹配才能正常建立蓝牙连接。

## 许可证

本项目早期基于 AGPL v3 授权的开源代码开发。详见 [LICENSE.txt](android_src/com.bandbbs.ebook-android/LICENSE.txt)。

## 致谢

- [弦电子书](https://github.com/youshen2/com.bandbbs.ebook) — 手环端开源项目
- [喵喵电子书](https://github.com/youshen2/com.bandbbs.ebook) — 多端设计稿
