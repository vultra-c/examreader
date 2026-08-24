# 考点传输 Android App — Grok 开发提示词

## 项目概述

开发一个 Android 手机端应用，配合小米手环上的「考点阅读器」快应用使用。手机端通过小米 XMS Wearable SDK 与手环建立蓝牙通信，实现以下功能：

1. **自动连接手环**：打开 App 即自动搜索并连接已配对的小米手环
2. **文件树浏览**：实时同步手环上的文件夹和考点文件结构（仅名称，不含内容）
3. **上传 TXT 文件**：将手机上的 TXT 文件分片传输到手环，默认上传到手环主页（根目录）
4. **文件夹管理**：可以在主页创建文件夹、删除文件或文件夹
5. **可选目标**：可以选择某个子文件夹作为上传目标（默认上传到主页，无需选择）

**关键设计原则**：
- 没有虚拟的"蓝牙传输"或"主页"根节点，文件和文件夹直接显示在列表顶层
- 上传默认到手环主页（根目录），用户无需选择目标即可直接上传
- 用户可以创建文件夹来组织考点，也可以直接上传到主页

---

## 技术栈

- **语言**：Java（Android）
- **SDK**：小米 XMS Wearable SDK（`xms-wearable-lib_1.4_release.aar`）
- **最低 SDK**：26（Android 8.0）
- **目标 SDK**：28（Android 9）
- **构建方式**：不使用 Gradle，使用 aapt2 + javac + d8 + zipalign + apksigner 命令行构建
- **包名**：`com.silenthong.kdreader`

---

## SDK 依赖

### AAR 库

文件：`xms-wearable-lib_1.4_release.aar`

这是一个 AAR 格式的库文件，包含小米穿戴设备 SDK。构建时需要从中提取 `classes.jar` 用于编译。

### 核心 API

```java
import com.xiaomi.xms.wearable.Wearable;
import com.xiaomi.xms.wearable.auth.AuthApi;
import com.xiaomi.xms.wearable.auth.Permission;
import com.xiaomi.xms.wearable.message.MessageApi;
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener;
import com.xiaomi.xms.wearable.node.Node;
import com.xiaomi.xms.wearable.node.NodeApi;
import com.xiaomi.xms.wearable.tasks.OnSuccessListener;
import com.xiaomi.xms.wearable.tasks.OnFailureListener;
```

### 关键 API 说明

| API | 用途 |
|-----|------|
| `Wearable.getNodeApi(context)` | 获取节点 API，用于发现设备和启动手环应用 |
| `Wearable.getAuthApi(context)` | 获取认证 API，用于请求权限 |
| `Wearable.getMessageApi(context)` | 获取消息 API，用于收发消息 |
| `NodeApi.getConnectedNodes()` | 获取已连接的设备列表 |
| `NodeApi.launchWearApp(nodeId, path)` | 启动手环上的快应用 |
| `AuthApi.checkPermissions(nodeId, permissions)` | 检查权限是否已授权 |
| `AuthApi.requestPermission(nodeId, permissions)` | 请求权限 |
| `MessageApi.addListener(nodeId, listener)` | 注册消息监听器 |
| `MessageApi.sendMessage(nodeId, data)` | 发送消息（byte[] 格式） |

---

## 连接流程

连接手环的完整流程（与官方 demo 一致）：

```
1. getConnectedNodes() → 获取已连接设备列表
2. checkPermissions() → 检查 DEVICE_MANAGER 和 NOTIFY 权限
3. requestPermission() → 如果权限缺失，请求授权
4. launchWearApp(nodeId, "/pages/index") → 启动手环快应用
5. addListener(nodeId, listener) → 注册消息监听器
6. 标记为已连接
```

### 连接代码要点

```java
// 1. 获取节点
NodeApi nodeApi = Wearable.getNodeApi(context);
nodeApi.getConnectedNodes()
    .addOnSuccessListener(nodes -> {
        if (nodes == null || nodes.isEmpty()) {
            // 未找到设备
            return;
        }
        Node node = nodes.get(0);
        // 2. 检查权限
        requestPermissions(node);
    })
    .addOnFailureListener(e -> {
        // 获取设备失败
    });

// 2. 检查/请求权限
AuthApi authApi = Wearable.getAuthApi(context);
Permission[] permissions = { Permission.DEVICE_MANAGER, Permission.NOTIFY };
authApi.checkPermissions(nodeId, permissions)
    .addOnSuccessListener(results -> {
        // 收集缺失的权限
        List<Permission> missing = new ArrayList<>();
        for (int i = 0; i < results.length; i++) {
            if (!results[i]) missing.add(permissions[i]);
        }
        if (!missing.isEmpty()) {
            authApi.requestPermission(nodeId, missing.toArray(new Permission[0]))
                .addOnSuccessListener(granted -> launchWatchApp())
                .addOnFailureListener(e -> launchWatchApp()); // 即使失败也继续
        } else {
            launchWatchApp();
        }
    });

// 3. 启动手环应用
nodeApi.launchWearApp(nodeId, "/pages/index")
    .addOnSuccessListener(v -> registerMessageListener())
    .addOnFailureListener(e -> registerMessageListener()); // 即使失败也继续

// 4. 注册消息监听器
MessageApi messageApi = Wearable.getMessageApi(context);
messageApi.addListener(nodeId, new OnMessageReceivedListener() {
    @Override
    public void onMessageReceived(String did, byte[] data) {
        String message = new String(data, StandardCharsets.UTF_8);
        // 解析 JSON，按 tag 路由
        JSONObject msg = new JSONObject(message);
        String tag = msg.optString("tag", "");
        // 路由到对应的 handler
    }
}).addOnSuccessListener(v -> {
    // 连接成功
    connected = true;
});
```

### 重要注意事项

- `launchWearApp` 可能失败（手环应用可能已经在运行），失败时不要中断流程，继续注册监听器
- `addListener` 可能返回"已注册"错误，此时应视为连接成功
- 消息监听器的 `onMessageReceived` 在 SDK 后台线程调用，操作 UI 时需切换到主线程
- 断开连接时需要先置空 `currentNode`，再异步调用 `removeListener`
- 使用 `ConcurrentHashMap` 存储 tag → callback 路由表，确保线程安全

---

## 消息协议

所有通信使用 JSON 字符串，通过 `MessageApi.sendMessage()` 发送（UTF-8 编码的 byte[]）。

### 消息路由

每条消息包含 `tag` 字段，用于路由到对应的处理器：

| Tag | 用途 |
|-----|------|
| `"file"` | 文件传输 |
| `"tree"` | 文件树同步（获取树、创建文件夹、删除节点） |

### 接收消息格式

```
手环 → 手机：{ tag: "file"|"tree", type|response: "...", ...payload }
```

### 发送消息格式

```
手机 → 手环：{ tag: "file"|"tree", stat|action: "...", ...payload }
```

---

## 文件传输协议（tag="file"）

将 TXT 文件内容分片传输到手环。整个文件作为单个"章节"传输。

### 传输流程

```
步骤1: 手机 → 手环  startTransfer
步骤2: 手环 → 手机  ready
步骤3: 手机 → 手环  d (数据分片)
步骤4: 手环 → 手机  next_chunk 或 chapter_chunk_complete
      （重复步骤3-4直到所有分片发完）
步骤5: 手机 → 手环  chapter_complete
步骤6: 手环 → 手机  chapter_saved
步骤7: 手机 → 手环  transfer_complete
步骤8: 手环 → 手机  transfer_finished
```

### 消息详情

#### 1. startTransfer（手机 → 手环）

```json
{
  "tag": "file",
  "stat": "startTransfer",
  "filename": "考点名称",
  "total": 1,
  "wordCount": 12345,
  "startFrom": 0,
  "folder": "bt_root"
}
```

- `filename`：文件名（不含扩展名）
- `total`：章节数，固定为 1（整个文件作为一个章节）
- `wordCount`：文件总字符数
- `startFrom`：起始章节索引，固定为 0
- `folder`：目标文件夹 ID，`"bt_root"` 表示主页（根目录），`"bt_folder_xxx"` 表示子文件夹

#### 2. ready（手环 → 手机）

```json
{
  "tag": "file",
  "type": "ready",
  "count": 0,
  "usage": 0
}
```

手环确认已准备好接收数据。

#### 3. d — 数据分片（手机 → 手环）

```json
{
  "tag": "file",
  "stat": "d",
  "count": 0,
  "data": "{\"index\":0,\"name\":\"考点名称\",\"content\":\"分片内容...\",\"wordCount\":12345,\"chunkNum\":0,\"totalChunks\":3}"
}
```

- `count`：章节索引，固定为 0
- `data`：JSON 字符串（需要二次编码），包含：
  - `index`：章节索引，固定为 0
  - `name`：文件名
  - `content`：本分片的文本内容（最多 8000 字符）
  - `wordCount`：文件总字符数
  - `chunkNum`：当前分片索引（从 0 开始）
  - `totalChunks`：总分片数

**分片大小**：每片最多 8000 字符。

#### 4. next_chunk（手环 → 手机，非最后一片）

```json
{
  "tag": "file",
  "type": "next_chunk"
}
```

#### 4b. chapter_chunk_complete（手环 → 手机，最后一片）

```json
{
  "tag": "file",
  "type": "chapter_chunk_complete"
}
```

#### 5. chapter_complete（手机 → 手环）

```json
{
  "tag": "file",
  "stat": "chapter_complete",
  "count": 0
}
```

告诉手环所有分片已发完，请保存。

#### 6. chapter_saved（手环 → 手机）

```json
{
  "tag": "file",
  "type": "chapter_saved",
  "count": 1,
  "syncedCount": 1,
  "totalCount": 1,
  "progress": 100
}
```

手环已保存内容到存储。

#### 7. transfer_complete（手机 → 手环）

```json
{
  "tag": "file",
  "stat": "transfer_complete"
}
```

#### 8. transfer_finished（手环 → 手机）

```json
{
  "tag": "file",
  "type": "transfer_finished"
}
```

传输完成。

#### 错误消息（手环 → 手机）

```json
{
  "tag": "file",
  "type": "error",
  "message": "错误描述",
  "count": 0
}
```

#### 取消消息（双向）

```json
{ "tag": "file", "stat": "cancel" }      // 手机 → 手环
{ "tag": "file", "type": "cancel" }      // 手环 → 手机
```

### 超时处理

每个步骤等待手环响应的超时时间为 **15 秒**。超时后应报错并终止传输。

### 分片算法

```java
private List<String> splitContent(String content, int chunkSize) {
    List<String> chunks = new ArrayList<>();
    if (content == null || content.isEmpty()) {
        chunks.add("");
        return chunks;
    }
    for (int i = 0; i < content.length(); i += chunkSize) {
        int end = Math.min(i + chunkSize, content.length());
        chunks.add(content.substring(i, end));
    }
    return chunks;
}
```

---

## 文件树同步协议（tag="tree"）

用于在手机端浏览手环上的文件和文件夹结构。

### 重要设计

- **没有虚拟根节点**：手环返回的树直接是根级文件夹和文件的数组，没有"蓝牙传输"或"主页"包装层
- **默认上传到主页**：`folder` 字段为 `"bt_root"` 时表示上传到根目录（主页），这是默认值
- **文件和文件夹直接显示**：用户打开 App 就能看到手环上的所有文件和文件夹

### 消息详情

#### 1. getTree（手机 → 手环）

```json
{
  "tag": "tree",
  "action": "getTree"
}
```

请求手环返回当前文件树。

#### 2. treeData（手环 → 手机）

```json
{
  "tag": "tree",
  "response": "treeData",
  "tree": [
    {
      "id": "bt_folder_1700000000000",
      "name": "语文",
      "type": "folder",
      "children": [
        {
          "id": "bt_1700000000001",
          "name": "古诗鉴赏",
          "type": "content"
        },
        {
          "id": "bt_folder_1700000000002",
          "name": "文言文",
          "type": "folder",
          "children": [
            {
              "id": "bt_1700000000003",
              "name": "岳阳楼记",
              "type": "content"
            }
          ]
        }
      ]
    },
    {
      "id": "bt_1700000000004",
      "name": "英语单词",
      "type": "content"
    }
  ]
}
```

树结构说明：
- 顶层是根级文件夹和文件的数组（没有包装层）
- `type` 为 `"folder"` 的节点有 `children` 数组
- `type` 为 `"content"` 的节点是叶子节点（文件）
- ID 规则：
  - 文件夹：`bt_folder_` + 时间戳
  - 文件：`bt_` + 时间戳
  - 根目录标识：`bt_root`（不出现在树中，仅用于 folder/parentId 字段）

#### 3. createFolder（手机 → 手环）

```json
{
  "tag": "tree",
  "action": "createFolder",
  "name": "新建文件夹名",
  "parentId": "bt_root"
}
```

- `name`：文件夹名称
- `parentId`：父文件夹 ID，`"bt_root"` 表示在主页创建，`"bt_folder_xxx"` 表示在子文件夹下创建

#### 4. folderCreated（手环 → 手机）

```json
{
  "tag": "tree",
  "response": "folderCreated",
  "folderId": "bt_folder_1700000000005",
  "success": true
}
```

创建成功后，手环会自动推送新的 `treeData`。

#### 5. deleteNode（手机 → 手环）

```json
{
  "tag": "tree",
  "action": "deleteNode",
  "nodeId": "bt_1700000000001"
}
```

删除文件或文件夹。如果是文件夹，手环会递归删除其下所有内容。

#### 6. nodeDeleted（手环 → 手机）

```json
{
  "tag": "tree",
  "response": "nodeDeleted",
  "success": true
}
```

删除成功后，手环会自动推送新的 `treeData`。

### 自动推送

手环在蓝牙连接建立时（`onopen` 事件）会自动推送一次 `treeData`，无需手机主动请求。

---

## UI 设计要求

### 设计风格

- **Material Design 3** 风格
- 简洁现代，卡片式布局
- 主色调：蓝色系（如 `#1976D2`）
- 背景：浅灰 `#FAFAFA`
- 卡片：白色 `#FFFFFF`，圆角 16dp，轻微阴影
- 文字：标题 `#202124`，副文字 `#5F6368`

### 界面布局（单页面）

```
┌─────────────────────────────────┐
│         考点传输                 │  ← 标题栏
├─────────────────────────────────┤
│  ● 已连接：小米手环              │  ← 连接状态（绿色圆点+设备名）
│  [重新连接]                      │
├─────────────────────────────────┤
│  📁 语文                    ▾   │  ← 文件树（直接显示，无根节点）
│    📄 古诗鉴赏                   │
│    📁 文言文                 ▾   │
│      📄 岳阳楼记                 │
│  📄 英语单词                     │
│                                  │
│  [新建文件夹]  [删除选中]        │
├─────────────────────────────────┤
│  📄 已选文件：考点.txt           │  ← 文件选择区
│  [选择 TXT 文件]                 │
├─────────────────────────────────┤
│  默认上传到主页                  │  ← 目标提示（简洁）
│  (点击文件夹可选择上传目标)      │
├─────────────────────────────────┤
│  [    发送到手环    ]            │  ← 发送按钮（大号，醒目）
│  ━━━━━━━━━━━━━ 60%               │  ← 进度条
│  传输中 60% (3/5)                │  ← 进度文字
└─────────────────────────────────┘
```

### 交互设计

1. **连接**：
   - 打开 App 自动连接
   - 连接状态用颜色区分：绿色（已连接）、橙色（连接中）、红色（错误）
   - 连接成功后自动同步文件树

2. **文件树**：
   - 文件和文件夹直接显示在顶层，没有虚拟根节点
   - 点击文件夹：展开/折叠子项
   - 点击文件夹：同时将其设为上传目标（显示"✓ 传至此"标记）
   - 长按或选中文件/文件夹：可删除
   - 空状态：显示"暂无文件，可直接上传"

3. **文件选择**：
   - 点击"选择 TXT 文件"按钮，打开系统文件选择器
   - 只接受 `text/plain` 类型
   - 选中后显示文件名和字符数

4. **上传目标**：
   - 默认上传到主页（根目录），无需选择
   - 点击树中的文件夹可切换上传目标
   - 切换后显示"上传到：文件夹名"
   - 不显示大的目标文件夹卡片，用一行文字提示即可

5. **新建文件夹**：
   - 点击"新建文件夹"按钮
   - 弹出输入对话框
   - 默认在主页创建（如果选中了子文件夹，则在该文件夹下创建）
   - 创建成功后自动刷新文件树

6. **删除**：
   - 选中文件或文件夹后点击"删除选中"
   - 弹出确认对话框
   - 删除文件夹会同时删除其下所有内容
   - 删除成功后自动刷新文件树

7. **传输**：
   - 显示进度条和百分比
   - 传输中禁用所有其他按钮
   - 传输完成后自动刷新文件树
   - 传输失败显示错误信息

### 美观要求

- 使用 CardView 或带圆角和阴影的 LinearLayout 作为卡片容器
- 按钮使用 Material Button 样式，圆角
- 文件夹图标使用 📁/📂，文件图标使用 📄（或使用 Material Icons）
- 选中状态使用浅蓝色背景 `#E3F2FD` 或 `#BBDEFB`
- 进度条使用 Material 样式，带颜色
- 整体布局使用 ScrollView，确保小屏幕也能滚动查看
- 间距统一：卡片间距 16dp，内边距 16dp
- 字体大小：标题 16sp，正文 14sp，辅助文字 13sp

---

## AndroidManifest.xml

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools"
    package="com.silenthong.kdreader">

    <uses-permission android:name="android.permission.WAKE_LOCK" />
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />
    <uses-permission
        android:name="android.permission.READ_EXTERNAL_STORAGE"
        android:maxSdkVersion="32" />

    <!-- XMS Wearable SDK 需要通过 IPC 与小米穿戴/小米运动健康通信 -->
    <queries>
        <package android:name="com.xiaomi.wearable" />
        <package android:name="com.mi.health" />
    </queries>

    <application
        android:allowBackup="true"
        android:icon="@drawable/ic_launcher"
        android:label="@string/app_name"
        android:supportsRtl="true"
        android:theme="@style/AppTheme">
        <activity
            android:name=".ui.MainActivity"
            android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

**重要**：不要使用 `QUERY_ALL_PACKAGES` 权限，使用 `<queries>` 声明特定包名。

---

## 构建方式（无 Gradle）

由于环境限制，不使用 Gradle 构建。使用 Android SDK 命令行工具：

```bash
# 工具路径
AAPT2=$ANDROID_HOME/build-tools/30.0.3/aapt2
D8=$ANDROID_HOME/build-tools/30.0.3/d8
ZIPALIGN=$ANDROID_HOME/build-tools/30.0.3/zipalign
APKSIGNER=$ANDROID_HOME/build-tools/30.0.3/apksigner
ANDROID_JAR=$ANDROID_HOME/platforms/android-30/android.jar

# 1. 从 AAR 提取 classes.jar
unzip xms-wearable-lib_1.4_release.aar -d aar-extract
cp aar-extract/classes.jar libs/wearable-sdk.jar

# 2. 编译资源
aapt2 compile --dir src/main/res -o build/res_compiled.zip

# 3. 链接资源
aapt2 link \
  -I $ANDROID_JAR \
  --manifest src/main/AndroidManifest.xml \
  -o build/base.apk \
  --java build/gen \
  -R build/res_compiled.zip \
  --auto-add-overlay \
  --min-sdk-version 26 \
  --target-sdk-version 28

# 4. 编译 Java
javac -source 1.8 -target 1.8 \
  -classpath "libs/wearable-sdk.jar:$ANDROID_JAR" \
  -d build/obj \
  src/main/java/**/*.java build/gen/**/*.java

# 5. 转换为 DEX
d8 --output build --lib $ANDROID_JAR --min-api 26 \
  build/obj/**/*.class libs/wearable-sdk.jar

# 6. 打包 APK
cd build
zip -j base.apk classes.dex

# 7. 对齐
zipalign -f 4 base.apk aligned.apk

# 8. 签名
apksigner sign \
  --ks debug.keystore \
  --ks-pass pass:android \
  --key-pass pass:android \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --out 考点传输.apk \
  aligned.apk
```

### 生成调试密钥

```bash
keytool -genkey -v -keystore debug.keystore \
  -alias androiddebugkey \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass android -keypass android \
  -dname "CN=Android Debug,O=Android,C=US"
```

---

## 代码结构

```
com.silenthong.kdreader/
├── ui/
│   └── MainActivity.java          // 主界面，所有 UI 逻辑
├── logic/
│   ├── WearableManager.java      // 手环连接管理、消息收发
│   ├── TxtTransferHandler.java   // TXT 文件分片传输
│   └── TreeSyncHandler.java       // 文件树同步（获取/创建/删除）
```

### WearableManager.java 职责

- 管理 XMS Wearable SDK 的 NodeApi、AuthApi、MessageApi
- 连接流程：发现设备 → 请求权限 → 启动手环应用 → 注册监听器
- 消息路由：按 `tag` 字段分发到注册的回调
- 线程安全：使用 `ConcurrentHashMap` 存储路由表
- 消息发送：`sendRawMessageWithCallback(message, callback)`
- 生命周期管理：`connect()`、`destroy()`

### TxtTransferHandler.java 职责

- 实现 tag="file" 的传输协议
- 接收文件名、内容、目标文件夹 ID
- 将内容分片（每片 8000 字符）
- 按协议步骤发送：startTransfer → d(分片) → chapter_complete → transfer_complete
- 处理手环响应：ready → next_chunk/chapter_chunk_complete → chapter_saved → transfer_finished
- 超时处理：每步 15 秒超时
- 进度回调：`onProgress(sent, total, message)`、`onSuccess(message)`、`onError(error)`

### TreeSyncHandler.java 职责

- 实现 tag="tree" 的同步协议
- `requestTree()`：请求文件树
- `createFolder(name, parentId)`：创建文件夹
- `deleteNode(nodeId)`：删除节点
- 处理手环响应：treeData、folderCreated、nodeDeleted
- 回调接口：`onTreeReceived(tree)`、`onFolderCreated(folderId, success, error)`、`onNodeDeleted(success, error)`

---

## 关键数据结构

### 文件树节点

```json
{
  "id": "bt_folder_1700000000000",
  "name": "文件夹名",
  "type": "folder",
  "children": [ ... ]
}
```

```json
{
  "id": "bt_1700000000001",
  "name": "文件名",
  "type": "content"
}
```

### ID 规则

| 类型 | 格式 | 说明 |
|------|------|------|
| 根目录 | `bt_root` | 不出现在树中，仅用于 folder/parentId 字段 |
| 文件夹 | `bt_folder_` + 时间戳 | 可作为 parentId 和 folder |
| 文件 | `bt_` + 时间戳 | 叶子节点 |

### 存储格式（手环端）

手环端使用 `@system.storage` 存储，key 为 `KD_BT_CONTENT`，值为 JSON 数组：

```json
[
  {
    "id": "bt_folder_1700000000000",
    "name": "语文",
    "type": "folder",
    "parentId": "bt_root",
    "created": 1700000000000
  },
  {
    "id": "bt_1700000000001",
    "name": "古诗鉴赏",
    "type": "content",
    "content": "文件正文内容...",
    "folder": "bt_root",
    "created": 1700000000001
  }
]
```

---

## 完整功能清单

### 必须实现

- [x] 自动连接手环（打开 App 即连接）
- [x] 连接状态显示（已连接/连接中/错误）
- [x] 手动重新连接按钮
- [x] 文件树同步和显示（无虚拟根节点）
- [x] 文件夹展开/折叠
- [x] 选择 TXT 文件（系统文件选择器）
- [x] 上传文件到手环（默认上传到主页）
- [x] 传输进度显示（进度条 + 百分比）
- [x] 新建文件夹（默认在主页创建）
- [x] 删除文件/文件夹（带确认对话框）
- [x] 传输完成后自动刷新文件树
- [x] 传输超时处理（15秒/步）
- [x] 错误提示（Toast）

### 可选功能

- [ ] 点击文件夹切换上传目标
- [ ] 长按文件/文件夹快速删除
- [ ] 传输取消功能
- [ ] 暗色主题支持
- [ ] 文件预览（传输前预览内容）
- [ ] 批量文件选择

---

## 参考文件

以下是当前实现的源代码文件，作为协议参考：

| 文件 | 说明 |
|------|------|
| `WearableManager.java` | 连接管理和消息路由的参考实现 |
| `TxtTransferHandler.java` | 文件传输协议的参考实现 |
| `TreeSyncHandler.java` | 文件树同步协议的参考实现 |
| `interconn.js` | 手环端连接管理器 |
| `interconnfile.js` | 手环端文件传输模块 |
| `interconnTree.js` | 手环端文件树同步模块 |
| `AndroidManifest.xml` | 权限和组件声明 |
| `build_apk.sh` | 构建脚本参考 |
| `xms-wearable-lib_1.4_release.aar` | SDK 库文件 |

---

## 总结

开发一个 Android 应用，核心是：

1. 使用 XMS Wearable SDK 连接小米手环
2. 通过蓝牙消息协议传输 TXT 文件（分片传输，每片 8000 字符）
3. 同步手环文件树，支持创建文件夹和删除操作
4. **没有虚拟根节点**，文件和文件夹直接显示
5. **默认上传到主页**，用户无需选择目标即可直接上传
6. UI 要美观，Material Design 风格，卡片式布局
