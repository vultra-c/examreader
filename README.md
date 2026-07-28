# 考点阅读器

基于小米手环（Vela 系统）和 Android 手机端的双端阅读应用，支持通过蓝牙将手机上的 TXT 文件传输到手环进行阅读。

本项目早期基于 [弦电子书](https://github.com/youshen2/com.bandbbs.ebook) 开源项目开发，根据其 AGPL v3 许可证进行使用和修改。

## 功能特性

### 手环端（Vela）

- 文件夹与文件浏览：支持多级文件夹管理
- 阅读器：支持字体大小调节、行距调节、翻页、页码跳转、百分比跳转
- 蓝牙传输：通过 `@system.interconnect` API 接收手机端推送的 TXT 文件
- 阅读进度同步：记录阅读位置，下次打开自动恢复
- 亮屏设置：支持保持屏幕常亮
- 删除管理：支持删除单个文件/文件夹/全部考点

### Android 端

- 文件管理：支持导入 PDF、EPUB、DOCX、MOBI、TXT 等多种格式
- 蓝牙传输：通过 XMS Wearable SDK 将文件推送到手环
- 阅读数据同步：支持阅读进度双向同步
- 书签管理：支持添加和管理书签
- 阅读统计：记录阅读时间和进度

## 技术栈

| 端 | 技术 |
|------|------|
| 手环端 | Vela JS (Quick App)、aiot-toolkit |
| Android 端 | Kotlin、Jetpack Compose、Room、XMS Wearable SDK |

## 项目结构

```
examreader/
├── 考点阅读器/                    # 手环端（Vela 应用）
│   ├── src/
│   │   ├── manifest.json          # 应用清单
│   │   ├── pages/                 # 页面组件
│   │   │   ├── index/             # 首页（文件夹列表）
│   │   │   ├── subfolder/         # 子文件夹页
│   │   │   ├── reader/            # 阅读器页
│   │   │   ├── readerSettings/    # 阅读设置页
│   │   │   ├── globalSettings/    # 全局设置页
│   │   │   ├── pageJump/          # 页码跳转页
│   │   │   ├── percentJump/       # 百分比跳转页
│   │   │   ├── deleteConfirm/     # 删除确认页
│   │   │   └── push/              # 蓝牙传输页
│   │   └── common/
│   │       ├── images/            # 图片资源
│   │       ├── utils/             # 工具类
│   │       │   ├── dataManager.js     # 数据管理
│   │       │   ├── handshake.js       # 握手协议
│   │       │   ├── interconnfile.js   # 文件传输
│   │       │   ├── timeUtil.js        # 时间工具
│   │       │   └── appState.js        # 应用状态
│   │       └── style.css           # 公共样式
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
npm install
npm run start      # 开发调试
npm run build      # 构建
npm run release    # 发布 RPK
```

### Android 端

```bash
cd android_src/com.bandbbs.ebook-android
./gradlew assembleDebug    # 构建 Debug APK
./gradlew assembleRelease  # 构建 Release APK
```

## 版本要求

| 端 | 版本号 | 版本名 |
|------|--------|--------|
| Android 端 | 126430 | V26.4.3 |
| 手环端 | 260520 | V26.4.4.BAND |

两端版本需匹配才能正常建立蓝牙连接。

## 许可证

本项目早期基于 AGPL v3 授权的开源代码开发。详见 [LICENSE.txt](android_src/com.bandbbs.ebook-android/LICENSE.txt)。

## 致谢

- [弦电子书](https://github.com/youshen2/com.bandbbs.ebook) — 手环端开源项目
- [喵喵电子书](https://github.com/youshen2/com.bandbbs.ebook) — 多端设计稿
