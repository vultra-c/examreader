# 需要上传给 Grok 的文件清单

将以下文件连同提示词文档一起上传给 Grok。

## 必须上传（6 个文件）

| 序号 | 文件路径 | 说明 |
|------|----------|------|
| 1 | `grok_android_app_prompt.md` | 完整的 AI 提示词文档（本次生成的） |
| 2 | `android-app/app/libs/xms-wearable-lib_1.4_release.aar` | 小米穿戴 SDK 库文件（必须，编译依赖） |
| 3 | `src/common/utils/interconnfile.js` | 手环端文件传输模块（协议参考） |
| 4 | `src/common/utils/interconnTree.js` | 手环端文件树同步模块（协议参考） |
| 5 | `src/common/utils/interconn.js` | 手环端连接管理器（消息路由参考） |
| 6 | `android-app/app/src/main/AndroidManifest.xml` | 权限和组件声明 |

## 建议上传（4 个文件，帮助 Grok 理解现有实现）

| 序号 | 文件路径 | 说明 |
|------|----------|------|
| 7 | `android-app/app/src/main/java/com/silenthong/kdreader/logic/WearableManager.java` | 连接管理参考实现 |
| 8 | `android-app/app/src/main/java/com/silenthong/kdreader/logic/TxtTransferHandler.java` | 文件传输参考实现 |
| 9 | `android-app/app/src/main/java/com/silenthong/kdreader/logic/TreeSyncHandler.java` | 文件树同步参考实现 |
| 10 | `android-app/build_apk.sh` | 构建脚本（Grok 需要知道如何构建 APK） |

## 可选上传（1 个文件）

| 序号 | 文件路径 | 说明 |
|------|----------|------|
| 11 | `src/common/utils/dataManager.js` | 手环端数据管理（了解手环存储格式） |

---

## 文件来源路径

所有文件都在项目目录 `考点阅读器/` 下：

```
考点阅读器/
├── grok_android_app_prompt.md                                    ← 提示词（在 /workspace/ 下）
├── android-app/
│   ├── app/
│   │   ├── libs/
│   │   │   └── xms-wearable-lib_1.4_release.aar                ← 文件 2
│   │   └── src/main/
│   │       ├── java/com/silenthong/kdreader/logic/
│   │       │   ├── WearableManager.java                          ← 文件 7
│   │       │   ├── TxtTransferHandler.java                       ← 文件 8
│   │       │   └── TreeSyncHandler.java                          ← 文件 9
│   │       └── AndroidManifest.xml                                ← 文件 6
│   └── build_apk.sh                                               ← 文件 10
└── src/
    └── common/utils/
        ├── interconn.js                                           ← 文件 5
        ├── interconnfile.js                                        ← 文件 3
        ├── interconnTree.js                                        ← 文件 4
        └── dataManager.js                                         ← 文件 11（可选）
```

## 使用说明

1. 将 `grok_android_app_prompt.md` 的内容作为提示词发送给 Grok
2. 将上述文件作为附件上传给 Grok
3. Grok 会根据提示词中的协议文档和参考代码，开发出一个美观且功能完整的 Android 应用

## 提示词使用方式

将 `grok_android_app_prompt.md` 的全部内容复制粘贴到 Grok 的对话框中，然后上传上述文件。提示词已经包含了完整的：
- 项目需求
- 通信协议（文件传输 + 文件树同步）
- SDK 使用方法
- UI 设计要求
- 构建方式
- 代码结构

Grok 只需按照提示词开发即可，参考文件用于验证协议细节。
