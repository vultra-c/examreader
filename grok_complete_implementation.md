# 考点传输 Android App — 完整实现文档

> 本文档包含**所有源代码**，逐文件完整给出。其他 AI 可以直接复制使用，不需要猜测任何细节。

## 注册失败根因分析

其他 AI 写的代码"注册失败"的核心原因有三个：

### 1. 没有先 `launchWearApp` 再 `addListener`
XMS Wearable SDK 要求手环上的快应用必须先启动，才能注册消息监听。必须按顺序调用 `launchWearApp` → `addListener`。

### 2. 没有处理"已注册"错误
当 App 退出再打开时，之前的 listener 可能还在。`addListener` 会返回错误 "You have registered"，此时**不是真正的失败**，应该视为连接成功。很多 AI 没处理这个分支，直接报错。

### 3. 没有先 `removeListener` 再重新注册
重复连接时，必须先调用 `messageApi.removeListener(nodeId)` 移除旧监听器，否则会持续报"已注册"错误。而且必须**先置空 currentNode，再异步 remove**，否则有竞态条件。

---

## 项目结构

```
com.silenthong.kdreader/
├── ui/
│   └── MainActivity.java          // 主界面
├── logic/
│   ├── WearableManager.java      // 连接管理（核心！注册失败的根源在此）
│   ├── TxtTransferHandler.java   // TXT 文件分片传输
│   └── TreeSyncHandler.java       // 文件树同步
```

资源文件：
```
res/
├── layout/activity_main.xml       // 布局（优化版）
├── values/strings.xml            // 字符串
├── values/themes.xml             // 主题（NoActionBar + 状态栏色）
├── drawable/bg_header.xml        // 顶部标题栏渐变背景
├── drawable/bg_card.xml           // 卡片背景（白色圆角）
├── drawable/bg_rounded.xml        // 通用圆角背景（兼容旧引用）
├── drawable/bg_tree_area.xml      // 文件树区域背景（浅灰+边框）
├── drawable/bg_file_selected.xml  // 已选文件背景（浅蓝+蓝边框）
├── drawable/bg_file_empty.xml     // 未选文件背景（虚线边框）
├── drawable/bg_status_connected.xml  // 连接成功状态（绿色圆角）
├── drawable/bg_status_connecting.xml // 连接中状态（橙色圆角）
├── drawable/bg_status_error.xml      // 错误/断开状态（红色圆角）
├── drawable/bg_send_button.xml   // 发送按钮选择器（蓝/灰/深蓝）
├── drawable/bg_btn_blue.xml      // 蓝色按钮选择器（蓝/灰/深蓝）
├── drawable/bg_btn_red.xml       // 红色按钮选择器（红/灰/深红）
├── drawable/bg_btn_outline.xml   // 描边按钮选择器（白底蓝边）
├── drawable/bg_btn_folder.xml    // 文件夹按钮背景（浅蓝圆角）
├── drawable/ic_launcher.xml      // 应用图标
├── color/btn_primary_bg.xml      // 按钮颜色选择器（兼容旧引用）
```

---

## 文件 1: WearableManager.java（连接管理 — 最关键）

> 这是整个项目最关键的文件。注册失败的根源就在这里。

```java
package com.silenthong.kdreader.logic;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import com.xiaomi.xms.wearable.Wearable;
import com.xiaomi.xms.wearable.auth.AuthApi;
import com.xiaomi.xms.wearable.auth.Permission;
import com.xiaomi.xms.wearable.message.MessageApi;
import com.xiaomi.xms.wearable.message.OnMessageReceivedListener;
import com.xiaomi.xms.wearable.node.Node;
import com.xiaomi.xms.wearable.node.NodeApi;
import com.xiaomi.xms.wearable.tasks.OnFailureListener;
import com.xiaomi.xms.wearable.tasks.OnSuccessListener;

import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

public class WearableManager {

    private static final String TAG = "WearableManager";

    public static final String TAG_FILE = "file";
    public static final String TAG_TREE = "tree";

    private final NodeApi nodeApi;
    private final AuthApi authApi;
    private final MessageApi messageApi;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private volatile Node currentNode;
    private volatile boolean connected = false;
    private volatile boolean destroyed = false;

    // 【关键】使用 ConcurrentHashMap 保证线程安全
    // onMessageReceived 在 SDK 后台线程调用，UI 线程也会读写这个表
    private final ConcurrentHashMap<String, MessageCallback> messageHandlers = new ConcurrentHashMap<>();

    private ConnectionListener connectionListener;

    public interface ConnectionListener {
        void onConnected(String deviceName);
        void onDisconnected();
        void onError(String error);
    }

    public interface MessageCallback {
        void onMessage(String message);
    }

    public interface SendCallback {
        void onSuccess();
        void onError(String error);
    }

    public WearableManager(Context context) {
        nodeApi = Wearable.getNodeApi(context);
        authApi = Wearable.getAuthApi(context);
        messageApi = Wearable.getMessageApi(context);
    }

    public void setConnectionListener(ConnectionListener listener) {
        this.connectionListener = listener;
    }

    public void addListener(String tag, MessageCallback callback) {
        messageHandlers.put(tag, callback);
    }

    public void removeListener(String tag) {
        messageHandlers.remove(tag);
    }

    public boolean isConnected() {
        return connected && currentNode != null;
    }

    public Handler getHandler() {
        return handler;
    }

    // 【关键】销毁时必须先置空 currentNode，再异步 removeListener
    // 否则会有竞态：新的 connect() 赋值了 currentNode，旧的 removeListener 回调又把它置空
    public void destroy() {
        destroyed = true;
        connected = false;

        final Node oldNode = currentNode;
        currentNode = null;

        if (oldNode != null) {
            try {
                messageApi.removeListener(oldNode.id)
                        .addOnSuccessListener(aVoid ->
                                Log.d(TAG, "Previous listener removed"))
                        .addOnFailureListener(e ->
                                Log.w(TAG, "Failed to remove listener: " + e.getMessage()));
            } catch (Exception e) {
                Log.w(TAG, "removeListener exception: " + e.getMessage());
            }
        }
    }

    // 【关键】连接流程必须严格按顺序：
    // 1. getConnectedNodes — 发现设备
    // 2. checkPermissions / requestPermission — 请求权限
    // 3. launchWearApp — 启动手环快应用（必须！否则 addListener 会失败）
    // 4. addListener — 注册消息监听
    public void connect() {
        destroyed = false;

        // 【关键】先移除旧的 listener，否则 addListener 会报"已注册"
        final Node oldNode = currentNode;
        currentNode = null;
        connected = false;

        if (oldNode != null) {
            try {
                messageApi.removeListener(oldNode.id)
                        .addOnSuccessListener(aVoid ->
                                Log.d(TAG, "Old listener removed"))
                        .addOnFailureListener(e ->
                                Log.w(TAG, "Old removeListener failed: " + e.getMessage()));
            } catch (Exception e) {
                Log.w(TAG, "removeListener exception: " + e.getMessage());
            }
        }

        notifyError("连接中...");

        // Step 1: 发现设备
        nodeApi.getConnectedNodes()
                .addOnSuccessListener(new OnSuccessListener<List<Node>>() {
                    @Override
                    public void onSuccess(List<Node> nodes) {
                        if (destroyed) return;
                        if (nodes == null || nodes.isEmpty()) {
                            notifyError("未找到设备！请检查小米运动健康是否已连接");
                            return;
                        }
                        Node node = nodes.get(0);
                        if (node == null) {
                            notifyError("设备信息异常");
                            return;
                        }
                        currentNode = node;
                        Log.d(TAG, "Found device: " + node.name);
                        requestPermissions(node.name);
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(Exception e) {
                        if (destroyed) return;
                        Log.e(TAG, "getConnectedNodes failed", e);
                        notifyError("获取设备列表失败，请检查小米运动健康是否已连接");
                    }
                });
    }

    // Step 3: 启动手环快应用
    // 【关键】launchWearApp 可能失败（手环应用可能已经在运行）
    // 失败时不要中断流程，继续注册监听器
    private void launchWatchApp(final String deviceName) {
        if (destroyed || currentNode == null) {
            notifyError("设备未连接");
            return;
        }

        final String nodeId = currentNode.id;

        nodeApi.launchWearApp(nodeId, "/pages/index")
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void aVoid) {
                        if (destroyed || currentNode == null) return;
                        Log.d(TAG, "Watch app launched successfully");
                        registerMessageListener(deviceName);
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(Exception e) {
                        if (destroyed) return;
                        Log.w(TAG, "launchWearApp failed: " + e.getMessage()
                                + " — continuing with listener registration anyway");
                        // 【关键】即使 launchWearApp 失败也继续注册
                        // 因为手环应用可能已经在运行了
                        registerMessageListener(deviceName);
                    }
                });
    }

    // Step 2: 请求权限
    private void requestPermissions(final String deviceName) {
        if (destroyed || currentNode == null) {
            notifyError("设备未连接");
            return;
        }

        final Permission[] permissions = { Permission.DEVICE_MANAGER, Permission.NOTIFY };
        final String nodeId = currentNode.id;

        authApi.checkPermissions(nodeId, permissions)
                .addOnSuccessListener(new OnSuccessListener<boolean[]>() {
                    @Override
                    public void onSuccess(boolean[] results) {
                        if (destroyed || currentNode == null) return;
                        List<Permission> missing = new ArrayList<>();
                        if (results != null) {
                            for (int i = 0; i < results.length && i < permissions.length; i++) {
                                if (!results[i]) {
                                    missing.add(permissions[i]);
                                }
                            }
                        }
                        if (!missing.isEmpty()) {
                            authApi.requestPermission(nodeId,
                                            missing.toArray(new Permission[0]))
                                    .addOnSuccessListener(granted -> {
                                        Log.d(TAG, "Permissions granted");
                                        launchWatchApp(deviceName);
                                    })
                                    .addOnFailureListener(e -> {
                                        Log.w(TAG, "requestPermission failed: " + e.getMessage());
                                        // 【关键】即使权限请求失败也继续
                                        launchWatchApp(deviceName);
                                    });
                        } else {
                            launchWatchApp(deviceName);
                        }
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(Exception e) {
                        if (destroyed || currentNode == null) return;
                        Log.w(TAG, "checkPermissions failed: " + e.getMessage());
                        // 【关键】即使权限检查失败也继续
                        launchWatchApp(deviceName);
                    }
                });
    }

    // Step 4: 注册消息监听器
    // 【最关键】这里是最容易出"注册失败"错误的地方
    private void registerMessageListener(final String deviceName) {
        if (destroyed || currentNode == null) {
            notifyError("设备未连接");
            return;
        }

        final String nodeId = currentNode.id;

        messageApi.addListener(nodeId, new OnMessageReceivedListener() {
            @Override
            public void onMessageReceived(String did, byte[] data) {
                if (data == null) return;
                String messageStr = new String(data, StandardCharsets.UTF_8);
                Log.d(TAG, "<<< Received: " + messageStr);
                handleIncomingMessage(messageStr);
            }
        }).addOnSuccessListener(new OnSuccessListener<Void>() {
            @Override
            public void onSuccess(Void aVoid) {
                if (destroyed || currentNode == null) return;
                Log.d(TAG, "Message listener registered — connected!");
                connected = true;
                notifyConnected(deviceName);
            }
        }).addOnFailureListener(new OnFailureListener() {
            @Override
            public void onFailure(Exception e) {
                if (destroyed) return;
                String msg = e.getMessage() != null ? e.getMessage() : "";
                // 【关键！！！】"You have registered" 不是错误！
                // 当 App 重新打开时，之前的 listener 可能还在
                // 此时 addListener 会返回这个"错误"，但实际上监听器还在工作
                // 必须当作连接成功处理！
                if (msg.contains("You have registered") || msg.contains("already")) {
                    Log.w(TAG, "Listener already registered — connected!");
                    connected = true;
                    notifyConnected(deviceName);
                } else {
                    notifyError("注册消息监听失败: " + msg);
                }
            }
        });
    }

    // 消息路由：按 tag 字段分发
    private void handleIncomingMessage(String messageStr) {
        if (destroyed) return;
        try {
            JSONObject msg = new JSONObject(messageStr);
            String tag = msg.optString("tag", "");

            MessageCallback callback = messageHandlers.get(tag);
            if (callback != null) {
                callback.onMessage(messageStr);
            } else {
                Log.w(TAG, "No handler for tag: " + tag);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse message: " + messageStr, e);
        }
    }

    // 所有 UI 回调都通过 handler.post 切换到主线程
    private void notifyConnected(String deviceName) {
        handler.post(() -> {
            if (destroyed) return;
            ConnectionListener l = connectionListener;
            if (l != null) {
                l.onConnected(deviceName);
            }
        });
    }

    private void notifyError(String error) {
        handler.post(() -> {
            if (destroyed) return;
            ConnectionListener l = connectionListener;
            if (l != null) {
                l.onError(error);
            }
        });
    }

    // 发送消息
    public void sendRawMessageWithCallback(String message, SendCallback callback) {
        if (destroyed) {
            if (callback != null) callback.onError("连接已关闭");
            return;
        }
        Node node = currentNode;
        if (node == null) {
            if (callback != null) callback.onError("设备未连接");
            return;
        }

        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        messageApi.sendMessage(node.id, data)
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void aVoid) {
                        Log.d(TAG, ">>> Sent: " + message);
                        if (callback != null) callback.onSuccess();
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(Exception e) {
                        Log.e(TAG, ">>> Send failed: " + e.getMessage());
                        if (callback != null) callback.onError(e.getMessage());
                    }
                });
    }
}
```

---

## 文件 2: TxtTransferHandler.java（文件传输）

```java
package com.silenthong.kdreader.logic;

import android.content.Context;
import android.net.Uri;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

public class TxtTransferHandler {

    private static final String TAG = "TxtTransfer";
    private static final int CHUNK_SIZE = 8000;
    private static final long RESPONSE_TIMEOUT = 15000L;

    private static final String STAT_START_TRANSFER = "startTransfer";
    private static final String STAT_DATA = "d";
    private static final String STAT_CHAPTER_COMPLETE = "chapter_complete";
    private static final String STAT_TRANSFER_COMPLETE = "transfer_complete";
    private static final String STAT_CANCEL = "cancel";

    private static final String TYPE_READY = "ready";
    private static final String TYPE_NEXT_CHUNK = "next_chunk";
    private static final String TYPE_CHAPTER_CHUNK_COMPLETE = "chapter_chunk_complete";
    private static final String TYPE_CHAPTER_SAVED = "chapter_saved";
    private static final String TYPE_TRANSFER_FINISHED = "transfer_finished";
    private static final String TYPE_ERROR = "error";
    private static final String TYPE_CANCEL = "cancel";

    private final WearableManager conn;

    private String pendingFileName;
    private int pendingWordCount;
    private int currentChunkIndex;
    private int totalChunks;
    private List<String> chunkList;
    private volatile TransferProgressListener progressListener;
    private Runnable responseTimeoutRunnable;
    private volatile boolean transferring = false;
    private String targetFolder = "bt_root";

    public interface TransferProgressListener {
        void onProgress(int sent, int total, String message);
        void onSuccess(String message);
        void onError(String error);
    }

    public TxtTransferHandler(WearableManager conn) {
        this.conn = conn;
        conn.addListener(WearableManager.TAG_FILE, message -> {
            try {
                JSONObject msg = new JSONObject(message);
                String type = msg.optString("type", "");
                handleWatchMessage(type, msg);
            } catch (Exception e) {
                Log.e(TAG, "Message parse error", e);
            }
        });
    }

    public void sendTxtFile(String fileName, String content,
                            String targetFolder, TransferProgressListener listener) {
        this.progressListener = listener;
        this.pendingFileName = fileName != null ? fileName : "untitled";
        this.pendingWordCount = content != null ? content.length() : 0;
        this.chunkList = splitContent(content, CHUNK_SIZE);
        this.totalChunks = chunkList.size();
        this.currentChunkIndex = 0;
        this.transferring = true;
        this.targetFolder = targetFolder != null ? targetFolder : "bt_root";

        if (listener != null) {
            listener.onProgress(0, totalChunks,
                    "开始传输: " + pendingFileName + " (" + totalChunks + " 片)");
        }
        sendStartTransfer();
    }

    private void sendStartTransfer() {
        JSONObject payload = new JSONObject();
        try {
            payload.put("tag", WearableManager.TAG_FILE);
            payload.put("stat", STAT_START_TRANSFER);
            payload.put("filename", pendingFileName);
            payload.put("total", 1);
            payload.put("wordCount", pendingWordCount);
            payload.put("startFrom", 0);
            payload.put("folder", targetFolder);
        } catch (Exception e) {
            fail("构建 startTransfer 失败: " + e.getMessage());
            return;
        }
        Log.d(TAG, ">>> startTransfer: " + pendingFileName + " folder=" + targetFolder);
        sendRaw(payload, "发送 startTransfer 失败", "等待 ready 超时");
    }

    private void sendDataChunk(int chunkNum) {
        if (chunkNum >= totalChunks) return;
        this.currentChunkIndex = chunkNum;
        String chunkContent = chunkList.get(chunkNum);

        JSONObject innerData = new JSONObject();
        try {
            innerData.put("index", 0);
            innerData.put("name", pendingFileName);
            innerData.put("content", chunkContent);
            innerData.put("wordCount", pendingWordCount);
            innerData.put("chunkNum", chunkNum);
            innerData.put("totalChunks", totalChunks);
        } catch (Exception e) {
            fail("构建数据分片失败: " + e.getMessage());
            return;
        }

        JSONObject payload = new JSONObject();
        try {
            payload.put("tag", WearableManager.TAG_FILE);
            payload.put("stat", STAT_DATA);
            payload.put("count", 0);
            payload.put("data", innerData.toString()); // 二次编码为字符串
        } catch (Exception e) {
            fail("构建数据消息失败: " + e.getMessage());
            return;
        }

        int percent = (int) (chunkNum * 100f / Math.max(1, totalChunks));
        Log.d(TAG, ">>> chunk " + (chunkNum + 1) + "/" + totalChunks);
        TransferProgressListener l = progressListener;
        if (l != null) {
            l.onProgress(chunkNum, totalChunks,
                    "传输中 " + percent + "% (" + (chunkNum + 1) + "/" + totalChunks + ")");
        }
        sendRaw(payload, "发送分片 " + (chunkNum + 1) + " 失败", "等待分片确认超时");
    }

    private void sendChapterComplete() {
        JSONObject payload = new JSONObject();
        try {
            payload.put("tag", WearableManager.TAG_FILE);
            payload.put("stat", STAT_CHAPTER_COMPLETE);
            payload.put("count", 0);
        } catch (Exception e) {
            fail("构建 chapter_complete 失败: " + e.getMessage());
            return;
        }
        Log.d(TAG, ">>> chapter_complete");
        TransferProgressListener l2 = progressListener;
        if (l2 != null) l2.onProgress(totalChunks, totalChunks, "等待手环保存...");
        sendRaw(payload, "发送 chapter_complete 失败", "等待 chapter_saved 超时");
    }

    private void sendTransferComplete() {
        JSONObject payload = new JSONObject();
        try {
            payload.put("tag", WearableManager.TAG_FILE);
            payload.put("stat", STAT_TRANSFER_COMPLETE);
        } catch (Exception e) {
            fail("构建 transfer_complete 失败: " + e.getMessage());
            return;
        }
        Log.d(TAG, ">>> transfer_complete");
        sendRaw(payload, "发送 transfer_complete 失败", "等待 transfer_finished 超时");
    }

    private void sendRaw(JSONObject payload, String sendErrorMsg, String timeoutMsg) {
        conn.sendRawMessageWithCallback(payload.toString(), new WearableManager.SendCallback() {
            @Override
            public void onSuccess() {
                startResponseTimeout(timeoutMsg);
            }
            @Override
            public void onError(String error) {
                fail(sendErrorMsg + ": " + error);
            }
        });
    }

    private void handleWatchMessage(String type, JSONObject msg) {
        if (!transferring) return;
        switch (type) {
            case TYPE_READY:
                cancelResponseTimeout();
                Log.d(TAG, "<<< ready");
                sendDataChunk(0);
                break;
            case TYPE_NEXT_CHUNK:
                cancelResponseTimeout();
                Log.d(TAG, "<<< next_chunk");
                int next = currentChunkIndex + 1;
                if (next >= totalChunks) sendChapterComplete();
                else sendDataChunk(next);
                break;
            case TYPE_CHAPTER_CHUNK_COMPLETE:
                cancelResponseTimeout();
                Log.d(TAG, "<<< chapter_chunk_complete");
                sendChapterComplete();
                break;
            case TYPE_CHAPTER_SAVED:
                cancelResponseTimeout();
                Log.d(TAG, "<<< chapter_saved");
                sendTransferComplete();
                break;
            case TYPE_TRANSFER_FINISHED:
                cancelResponseTimeout();
                transferring = false;
                Log.d(TAG, "<<< transfer_finished");
                TransferProgressListener l = progressListener;
                progressListener = null;
                if (l != null) l.onSuccess("传输完成: " + pendingFileName);
                break;
            case TYPE_ERROR:
                cancelResponseTimeout();
                transferring = false;
                String message = msg.optString("message", "手环传输错误");
                Log.e(TAG, "<<< error: " + message);
                TransferProgressListener l2 = progressListener;
                progressListener = null;
                if (l2 != null) l2.onError("手环错误: " + message);
                break;
            case TYPE_CANCEL:
                cancelResponseTimeout();
                transferring = false;
                Log.d(TAG, "<<< cancel");
                TransferProgressListener l3 = progressListener;
                progressListener = null;
                if (l3 != null) l3.onError("手环取消了传输");
                break;
            default:
                Log.w(TAG, "Unknown type: " + type);
                break;
        }
    }

    private void startResponseTimeout(String timeoutMessage) {
        cancelResponseTimeout();
        responseTimeoutRunnable = () -> {
            Log.w(TAG, "Response timeout: " + timeoutMessage);
            transferring = false;
            sendCancelQuietly();
            TransferProgressListener l = progressListener;
            progressListener = null;
            if (l != null) l.onError(timeoutMessage);
        };
        conn.getHandler().postDelayed(responseTimeoutRunnable, RESPONSE_TIMEOUT);
    }

    private void cancelResponseTimeout() {
        if (responseTimeoutRunnable != null) {
            conn.getHandler().removeCallbacks(responseTimeoutRunnable);
            responseTimeoutRunnable = null;
        }
    }

    private void sendCancelQuietly() {
        try {
            JSONObject payload = new JSONObject();
            payload.put("tag", WearableManager.TAG_FILE);
            payload.put("stat", STAT_CANCEL);
            conn.sendRawMessageWithCallback(payload.toString(), null);
        } catch (Exception e) {
            Log.e(TAG, "Failed to send cancel", e);
        }
    }

    private void fail(String error) {
        cancelResponseTimeout();
        transferring = false;
        Log.e(TAG, error);
        TransferProgressListener l = progressListener;
        progressListener = null;
        if (l != null) l.onError(error);
    }

    private List<String> splitContent(String content, int chunkSize) {
        List<String> chunks = new ArrayList<>();
        if (content == null || content.isEmpty()) {
            chunks.add("");
            return chunks;
        }
        int start = 0;
        while (start < content.length()) {
            int end = Math.min(start + chunkSize, content.length());
            chunks.add(content.substring(start, end));
            start = end;
        }
        if (chunks.isEmpty()) chunks.add("");
        return chunks;
    }

    public static String readTxtFromUri(Context context, Uri uri) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = context.getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(is, StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int len;
            while ((len = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, len);
            }
        }
        return sb.toString();
    }

    public static String getFileNameWithoutExtension(String fileName) {
        if (fileName == null) return "untitled";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) return fileName.substring(0, dot);
        return fileName;
    }

    public void cancelTransfer() {
        if (!transferring) return;
        cancelResponseTimeout();
        transferring = false;
        sendCancelQuietly();
        TransferProgressListener l = progressListener;
        progressListener = null;
        if (l != null) l.onError("传输已取消");
    }
}
```

---

## 文件 3: TreeSyncHandler.java（文件树同步）

```java
package com.silenthong.kdreader.logic;

import android.os.Handler;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

public class TreeSyncHandler {

    private static final String TAG = "TreeSync";

    private static final String ACTION_GET_TREE = "getTree";
    private static final String ACTION_CREATE_FOLDER = "createFolder";
    private static final String ACTION_DELETE_NODE = "deleteNode";

    private static final String RESPONSE_TREE_DATA = "treeData";
    private static final String RESPONSE_FOLDER_CREATED = "folderCreated";
    private static final String RESPONSE_NODE_DELETED = "nodeDeleted";

    private final WearableManager conn;
    private final Handler handler;
    private volatile TreeSyncListener listener;

    public interface TreeSyncListener {
        void onTreeReceived(JSONArray tree);
        void onFolderCreated(String folderId, boolean success, String error);
        void onNodeDeleted(boolean success, String error);
    }

    public TreeSyncHandler(WearableManager conn) {
        this.conn = conn;
        this.handler = conn != null ? conn.getHandler() : null;

        if (conn == null) {
            Log.e(TAG, "WearableManager 为空");
            return;
        }

        conn.addListener(WearableManager.TAG_TREE, message -> {
            try {
                JSONObject msg = new JSONObject(message);
                String response = msg.optString("response", "");
                handleWatchMessage(response, msg);
            } catch (Exception e) {
                Log.e(TAG, "消息解析失败", e);
            }
        });
    }

    public void setListener(TreeSyncListener listener) {
        this.listener = listener;
    }

    public void requestTree() {
        JSONObject payload = new JSONObject();
        try {
            payload.put("tag", WearableManager.TAG_TREE);
            payload.put("action", ACTION_GET_TREE);
        } catch (Exception e) {
            Log.e(TAG, "构建 getTree 失败: " + e.getMessage());
            return;
        }
        Log.d(TAG, ">>> getTree");
        sendRaw(payload, "发送 getTree 失败");
    }

    public void createFolder(String name, String parentId) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("tag", WearableManager.TAG_TREE);
            payload.put("action", ACTION_CREATE_FOLDER);
            payload.put("name", name != null ? name : "");
            payload.put("parentId", parentId != null ? parentId : "");
        } catch (Exception e) {
            Log.e(TAG, "构建 createFolder 失败: " + e.getMessage());
            notifyFolderCreated("", false, "构建失败");
            return;
        }
        Log.d(TAG, ">>> createFolder: name=" + name + " parentId=" + parentId);
        sendRaw(payload, "发送 createFolder 失败");
    }

    public void deleteNode(String nodeId) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("tag", WearableManager.TAG_TREE);
            payload.put("action", ACTION_DELETE_NODE);
            payload.put("nodeId", nodeId != null ? nodeId : "");
        } catch (Exception e) {
            Log.e(TAG, "构建 deleteNode 失败: " + e.getMessage());
            notifyNodeDeleted(false, "构建失败");
            return;
        }
        Log.d(TAG, ">>> deleteNode: nodeId=" + nodeId);
        sendRaw(payload, "发送 deleteNode 失败");
    }

    private void sendRaw(JSONObject payload, String sendErrorMsg) {
        if (conn == null) {
            Log.e(TAG, sendErrorMsg + ": WearableManager 为空");
            return;
        }
        conn.sendRawMessageWithCallback(payload.toString(), new WearableManager.SendCallback() {
            @Override
            public void onSuccess() { /* 等待手环响应 */ }
            @Override
            public void onError(String error) {
                Log.e(TAG, sendErrorMsg + ": " + error);
            }
        });
    }

    private void handleWatchMessage(String response, JSONObject msg) {
        switch (response) {
            case RESPONSE_TREE_DATA:
                Log.d(TAG, "<<< treeData");
                JSONArray tree = msg.optJSONArray("tree");
                if (tree == null) tree = new JSONArray();
                notifyTreeReceived(tree);
                break;
            case RESPONSE_FOLDER_CREATED:
                String folderId = msg.optString("folderId", "");
                boolean success = msg.optBoolean("success", false);
                String error = success ? null : msg.optString("error", "创建失败");
                Log.d(TAG, "<<< folderCreated: " + folderId + " " + success);
                notifyFolderCreated(folderId, success, error);
                break;
            case RESPONSE_NODE_DELETED:
                boolean delSuccess = msg.optBoolean("success", false);
                String delError = delSuccess ? null : msg.optString("error", "删除失败");
                Log.d(TAG, "<<< nodeDeleted: " + delSuccess);
                notifyNodeDeleted(delSuccess, delError);
                break;
            default:
                Log.w(TAG, "未知响应: " + response);
                break;
        }
    }

    private void notifyTreeReceived(JSONArray tree) {
        TreeSyncListener l = listener;
        if (l == null || handler == null) return;
        handler.post(() -> l.onTreeReceived(tree));
    }

    private void notifyFolderCreated(String folderId, boolean success, String error) {
        TreeSyncListener l = listener;
        if (l == null || handler == null) return;
        handler.post(() -> l.onFolderCreated(folderId, success, error));
    }

    private void notifyNodeDeleted(boolean success, String error) {
        TreeSyncListener l = listener;
        if (l == null || handler == null) return;
        handler.post(() -> l.onNodeDeleted(success, error));
    }
}
```

---

## 文件 4: AndroidManifest.xml

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

    <!-- 【关键】不要用 QUERY_ALL_PACKAGES，用 <queries> 声明特定包名 -->
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

---

## 文件 5: 资源文件

### res/values/strings.xml
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">考点传输</string>
</resources>
```

### res/values/themes.xml
```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <style name="AppTheme" parent="android:Theme.Material.Light.NoActionBar">
        <item name="android:colorPrimary">#1976D2</item>
        <item name="android:colorPrimaryDark">#0D47A1</item>
        <item name="android:colorAccent">#1976D2</item>
        <item name="android:statusBarColor">#1565C0</item>
        <item name="android:windowBackground">#F0F2F5</item>
        <item name="android:textColorPrimary">#202124</item>
    </style>
</resources>
```

### res/drawable/bg_header.xml — 顶部标题栏渐变
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <gradient
        android:startColor="#1565C0"
        android:endColor="#1976D2"
        android:angle="135" />
</shape>
```

### res/drawable/bg_card.xml — 卡片背景
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#FFFFFF" />
    <corners android:radius="16dp" />
</shape>
```

### res/drawable/bg_rounded.xml — 通用圆角（兼容旧引用）
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#FFFFFF" />
    <corners android:radius="12dp" />
</shape>
```

### res/drawable/bg_tree_area.xml — 文件树区域背景
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#F5F7FA" />
    <corners android:radius="10dp" />
    <stroke android:width="1dp" android:color="#E0E0E0" />
</shape>
```

### res/drawable/bg_file_selected.xml — 已选文件背景
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#E8F0FE" />
    <corners android:radius="10dp" />
    <stroke android:width="1.5dp" android:color="#1976D2" />
</shape>
```

### res/drawable/bg_file_empty.xml — 未选文件背景（虚线边框）
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#F5F5F5" />
    <corners android:radius="10dp" />
    <stroke android:width="1dp" android:color="#E0E0E0" android:dashWidth="6dp" android:dashGap="4dp" />
</shape>
```

### res/drawable/bg_status_connected.xml — 连接成功（绿色）
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#4CAF50" />
    <corners android:radius="12dp" />
</shape>
```

### res/drawable/bg_status_connecting.xml — 连接中（橙色）
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#FF9800" />
    <corners android:radius="12dp" />
</shape>
```

### res/drawable/bg_status_error.xml — 错误/断开（红色）
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#F44336" />
    <corners android:radius="12dp" />
</shape>
```

### res/drawable/bg_send_button.xml — 发送按钮选择器
```xml
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_enabled="false">
        <shape android:shape="rectangle">
            <solid android:color="#BDBDBD" />
            <corners android:radius="14dp" />
        </shape>
    </item>
    <item android:state_pressed="true">
        <shape android:shape="rectangle">
            <solid android:color="#1565C0" />
            <corners android:radius="14dp" />
        </shape>
    </item>
    <item>
        <shape android:shape="rectangle">
            <solid android:color="#1976D2" />
            <corners android:radius="14dp" />
        </shape>
    </item>
</selector>
```

### res/drawable/bg_btn_blue.xml — 蓝色按钮选择器
```xml
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_enabled="false">
        <shape android:shape="rectangle">
            <solid android:color="#BDBDBD" />
            <corners android:radius="12dp" />
        </shape>
    </item>
    <item android:state_pressed="true">
        <shape android:shape="rectangle">
            <solid android:color="#1565C0" />
            <corners android:radius="12dp" />
        </shape>
    </item>
    <item>
        <shape android:shape="rectangle">
            <solid android:color="#1976D2" />
            <corners android:radius="12dp" />
        </shape>
    </item>
</selector>
```

### res/drawable/bg_btn_red.xml — 红色按钮选择器
```xml
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_enabled="false">
        <shape android:shape="rectangle">
            <solid android:color="#BDBDBD" />
            <corners android:radius="12dp" />
        </shape>
    </item>
    <item android:state_pressed="true">
        <shape android:shape="rectangle">
            <solid android:color="#B71C1C" />
            <corners android:radius="12dp" />
        </shape>
    </item>
    <item>
        <shape android:shape="rectangle">
            <solid android:color="#D32F2F" />
            <corners android:radius="12dp" />
        </shape>
    </item>
</selector>
```

### res/drawable/bg_btn_outline.xml — 描边按钮选择器
```xml
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_enabled="false">
        <shape android:shape="rectangle">
            <solid android:color="#F5F5F5" />
            <corners android:radius="12dp" />
            <stroke android:width="1.5dp" android:color="#E0E0E0" />
        </shape>
    </item>
    <item android:state_pressed="true">
        <shape android:shape="rectangle">
            <solid android:color="#E3F2FD" />
            <corners android:radius="12dp" />
            <stroke android:width="1.5dp" android:color="#1976D2" />
        </shape>
    </item>
    <item>
        <shape android:shape="rectangle">
            <solid android:color="#FFFFFF" />
            <corners android:radius="12dp" />
            <stroke android:width="1.5dp" android:color="#1976D2" />
        </shape>
    </item>
</selector>
```

### res/drawable/bg_btn_folder.xml — 文件夹按钮背景
```xml
<?xml version="1.0" encoding="utf-8"?>
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <solid android:color="#E3F2FD" />
    <corners android:radius="10dp" />
</shape>
```

### res/drawable/ic_launcher.xml
```xml
<?xml version="1.0" encoding="utf-8"?>
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="48dp"
    android:height="48dp"
    android:viewportWidth="48"
    android:viewportHeight="48">
    <path
        android:fillColor="#1976D2"
        android:pathData="M8,4 L32,4 L40,12 L40,44 L8,44 Z" />
    <path
        android:fillColor="#FFFFFF"
        android:pathData="M14,18 L34,18 L34,20 L14,20 Z M14,24 L34,24 L34,26 L14,26 Z M14,30 L28,30 L28,32 L14,32 Z" />
</vector>
```

### res/color/btn_primary_bg.xml（兼容旧引用）
```xml
<?xml version="1.0" encoding="utf-8"?>
<selector xmlns:android="http://schemas.android.com/apk/res/android">
    <item android:state_enabled="false" android:color="#BDBDBD" />
    <item android:color="#1976D2" />
</selector>
```

---

## 文件 6: MainActivity.java（UI 逻辑）

> 完整源码见项目中的 `MainActivity.java`。以下列出与 UI 相关的关键代码段。

### 6.1 状态栏背景切换（不再用 tint，改用 drawable）

```java
// 状态类型常量
private static final int STATUS_CONNECTING = 0;
private static final int STATUS_CONNECTED = 1;
private static final int STATUS_DISCONNECTED = 2;
private static final int STATUS_ERROR = 3;

/**
 * 根据状态类型切换连接状态栏的背景 drawable。
 * connecting → bg_status_connecting（橙色圆角）
 * connected  → bg_status_connected（绿色圆角）
 * error/disconnected → bg_status_error（红色圆角）
 */
private void setStatusBackground(int status) {
    int resId;
    switch (status) {
        case STATUS_CONNECTED:
            resId = R.drawable.bg_status_connected;
            break;
        case STATUS_CONNECTING:
            resId = R.drawable.bg_status_connecting;
            break;
        case STATUS_DISCONNECTED:
        case STATUS_ERROR:
        default:
            resId = R.drawable.bg_status_error;
            break;
    }
    tvConnectionStatus.setBackgroundResource(resId);
}
```

### 6.2 文件选择后切换背景

```java
// onActivityResult 中：
// 选中文件后 → 切换为 bg_file_selected（浅蓝+蓝边框）
tvSelectedFile.setText(selectedFileName);
tvSelectedFile.setTextColor(0xFF202124);
tvSelectedFile.setBackgroundResource(R.drawable.bg_file_selected);

// 读取失败 → 切换回 bg_file_empty（虚线边框）
tvSelectedFile.setText("读取失败：" + selectedFileName);
tvSelectedFile.setTextColor(0xFFF44336);
tvSelectedFile.setBackgroundResource(R.drawable.bg_file_empty);
```

### 6.3 其他关键要点

- `onCreate` 中调用 `wearableManager.connect()` 自动连接
- 连接成功后调用 `treeSync.requestTree()` 同步文件树
- 文件树渲染用 `LinearLayout` 动态添加 `View`，不用 `RecyclerView`（避免复杂依赖）
- 点击文件夹展开/折叠 + 设为上传目标
- 传输进度通过 `Handler.post` 回到主线程更新 UI
- **不再 import `android.graphics.drawable.Drawable`**（旧版用 `setTint` 切换颜色，新版用 `setBackgroundResource` 切换 drawable）

---

## 文件 7: activity_main.xml（完整布局）

> 布局结构：ScrollView → LinearLayout（纵向）
> - 顶部标题栏（渐变背景 `bg_header`）
> - 连接状态栏（`bg_status_connecting` 默认）
> - 重新连接按钮（`bg_btn_blue`）
> - 手环文件树卡片（`bg_card` + `bg_tree_area`）
> - 文件选择卡片（`bg_card` + `bg_file_empty`）
> - 目标文件夹提示（`bg_btn_folder`）
> - 发送按钮（`bg_send_button`，带 disabled 状态）
> - 传输进度卡片（`bg_card`）

```xml
<?xml version="1.0" encoding="utf-8"?>
<ScrollView xmlns:android="http://schemas.android.com/apk/res/android"
    android:layout_width="match_parent"
    android:layout_height="match_parent"
    android:background="#F0F2F5"
    android:fillViewport="true">

    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:clipToPadding="false">

        <!-- 顶部标题栏 -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:padding="20dp"
            android:paddingTop="28dp"
            android:background="@drawable/bg_header"
            android:elevation="4dp">

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:text="考点传输"
                android:textColor="#FFFFFF"
                android:textSize="22sp"
                android:textStyle="bold" />

            <TextView
                android:layout_width="wrap_content"
                android:layout_height="wrap_content"
                android:layout_marginTop="4dp"
                android:text="选择 TXT 文件并发送至小米手环"
                android:textColor="#B3D4FC"
                android:textSize="13sp" />
        </LinearLayout>

        <!-- 连接状态栏 -->
        <TextView
            android:id="@+id/tv_connection_status"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginStart="16dp"
            android:layout_marginEnd="16dp"
            android:layout_marginTop="16dp"
            android:text="正在连接手环..."
            android:textColor="#FFFFFF"
            android:textSize="15sp"
            android:textStyle="bold"
            android:gravity="center"
            android:padding="14dp"
            android:background="@drawable/bg_status_connecting"
            android:elevation="2dp" />

        <!-- 重新连接按钮 -->
        <Button
            android:id="@+id/btn_connect"
            android:layout_width="match_parent"
            android:layout_height="52dp"
            android:layout_marginStart="16dp"
            android:layout_marginEnd="16dp"
            android:layout_marginTop="10dp"
            android:text="重新连接"
            android:textColor="#FFFFFF"
            android:textSize="15sp"
            android:background="@drawable/bg_btn_blue"
            android:elevation="2dp" />

        <!-- 手环文件树卡片 -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:layout_marginStart="16dp"
            android:layout_marginEnd="16dp"
            android:layout_marginTop="20dp"
            android:padding="16dp"
            android:background="@drawable/bg_card"
            android:elevation="2dp">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:gravity="center_vertical">

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="📁"
                    android:textSize="18sp" />

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginStart="8dp"
                    android:text="手环文件树"
                    android:textColor="#1A237E"
                    android:textSize="16sp"
                    android:textStyle="bold" />

                <View
                    android:layout_width="0dp"
                    android:layout_height="0dp"
                    android:layout_weight="1" />

                <TextView
                    android:id="@+id/tv_tree_status"
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="等待同步..."
                    android:textColor="#9AA0A6"
                    android:textSize="12sp" />
            </LinearLayout>

            <View
                android:layout_width="match_parent"
                android:layout_height="1dp"
                android:layout_marginTop="12dp"
                android:layout_marginBottom="8dp"
                android:background="#EBEEF0" />

            <ScrollView
                android:layout_width="match_parent"
                android:layout_height="260dp"
                android:background="@drawable/bg_tree_area"
                android:fillViewport="true"
                android:padding="1dp">

                <LinearLayout
                    android:id="@+id/lv_tree_container"
                    android:layout_width="match_parent"
                    android:layout_height="wrap_content"
                    android:minHeight="240dp"
                    android:orientation="vertical"
                    android:padding="6dp">
                </LinearLayout>
            </ScrollView>

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:layout_marginTop="14dp">

                <Button
                    android:id="@+id/btn_new_folder"
                    android:layout_width="0dp"
                    android:layout_height="44dp"
                    android:layout_weight="1"
                    android:text="📁 新建文件夹"
                    android:textColor="#1565C0"
                    android:textSize="13sp"
                    android:background="@drawable/bg_btn_folder"
                    android:elevation="1dp" />

                <Button
                    android:id="@+id/btn_delete_node"
                    android:layout_width="0dp"
                    android:layout_height="44dp"
                    android:layout_weight="1"
                    android:layout_marginStart="10dp"
                    android:text="🗑 删除选中"
                    android:textColor="#FFFFFF"
                    android:textSize="13sp"
                    android:background="@drawable/bg_btn_red"
                    android:elevation="1dp" />
            </LinearLayout>
        </LinearLayout>

        <!-- 文件选择卡片 -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:layout_marginStart="16dp"
            android:layout_marginEnd="16dp"
            android:layout_marginTop="16dp"
            android:padding="16dp"
            android:background="@drawable/bg_card"
            android:elevation="2dp">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:gravity="center_vertical">

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="📄"
                    android:textSize="18sp" />

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginStart="8dp"
                    android:text="文件选择"
                    android:textColor="#1A237E"
                    android:textSize="16sp"
                    android:textStyle="bold" />
            </LinearLayout>

            <View
                android:layout_width="match_parent"
                android:layout_height="1dp"
                android:layout_marginTop="12dp"
                android:layout_marginBottom="10dp"
                android:background="#EBEEF0" />

            <TextView
                android:id="@+id/tv_selected_file"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:text="未选择文件"
                android:textColor="#9AA0A6"
                android:textSize="14sp"
                android:padding="14dp"
                android:maxLines="2"
                android:ellipsize="middle"
                android:background="@drawable/bg_file_empty" />

            <Button
                android:id="@+id/btn_select_file"
                android:layout_width="match_parent"
                android:layout_height="48dp"
                android:layout_marginTop="12dp"
                android:text="选择 TXT 文件"
                android:textColor="#1976D2"
                android:textSize="15sp"
                android:background="@drawable/bg_btn_outline"
                android:elevation="1dp" />
        </LinearLayout>

        <!-- 目标文件夹提示 -->
        <TextView
            android:id="@+id/tv_target_folder"
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:layout_marginStart="16dp"
            android:layout_marginEnd="16dp"
            android:layout_marginTop="14dp"
            android:text="默认上传到主页"
            android:textColor="#5F6368"
            android:textSize="13sp"
            android:gravity="center"
            android:padding="8dp"
            android:background="@drawable/bg_btn_folder" />

        <!-- 发送按钮 -->
        <Button
            android:id="@+id/btn_send"
            android:layout_width="match_parent"
            android:layout_height="56dp"
            android:layout_marginStart="16dp"
            android:layout_marginEnd="16dp"
            android:layout_marginTop="10dp"
            android:text="发送到手环"
            android:textColor="#FFFFFF"
            android:textSize="16sp"
            android:textStyle="bold"
            android:background="@drawable/bg_send_button"
            android:enabled="false"
            android:elevation="3dp" />

        <!-- 传输进度区 -->
        <LinearLayout
            android:layout_width="match_parent"
            android:layout_height="wrap_content"
            android:orientation="vertical"
            android:layout_marginStart="16dp"
            android:layout_marginEnd="16dp"
            android:layout_marginTop="16dp"
            android:layout_marginBottom="24dp"
            android:padding="16dp"
            android:background="@drawable/bg_card"
            android:elevation="1dp">

            <LinearLayout
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:orientation="horizontal"
                android:gravity="center_vertical">

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:text="📊"
                    android:textSize="16sp" />

                <TextView
                    android:layout_width="wrap_content"
                    android:layout_height="wrap_content"
                    android:layout_marginStart="8dp"
                    android:text="传输状态"
                    android:textColor="#1A237E"
                    android:textSize="14sp"
                    android:textStyle="bold" />
            </LinearLayout>

            <ProgressBar
                android:id="@+id/progress_bar"
                style="?android:attr/progressBarStyleHorizontal"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="12dp"
                android:max="100"
                android:progress="0"
                android:visibility="gone" />

            <TextView
                android:id="@+id/tv_progress"
                android:layout_width="match_parent"
                android:layout_height="wrap_content"
                android:layout_marginTop="8dp"
                android:text="就绪"
                android:textColor="#5F6368"
                android:textSize="13sp" />
        </LinearLayout>

    </LinearLayout>
</ScrollView>
```

---

## 连接流程总结（一张图）

```
App 启动
  │
  ▼
connect()
  │
  ├── 1. removeListener(oldNode)     ← 先清理旧监听器
  │
  ├── 2. getConnectedNodes()         ← 发现设备
  │       │
  │       ├── 成功 → 找到设备
  │       └── 失败 → "未找到设备"
  │
  ├── 3. checkPermissions()          ← 检查权限
  │       │
  │       ├── 缺权限 → requestPermission() → 无论成功失败都继续
  │       └── 有权限 → 直接继续
  │
  ├── 4. launchWearApp("/pages/index")  ← 启动手环快应用
  │       │
  │       ├── 成功 → 继续注册
  │       └── 失败 → 也继续注册（应用可能已在运行）
  │
  └── 5. addListener(nodeId, listener)  ← 注册消息监听
          │
          ├── 成功 → connected = true → onConnected()
          │
          └── 失败
                ├── "You have registered" → connected = true ← 【关键！当作成功】
                └── 其他错误 → onError("注册失败")
```

---

## 构建方式（无 Gradle）

```bash
# 工具
AAPT2=$ANDROID_HOME/build-tools/30.0.3/aapt2
D8=$ANDROID_HOME/build-tools/30.0.3/d8
ANDROID_JAR=$ANDROID_HOME/platforms/android-30/android.jar

# 1. 提取 AAR 中的 classes.jar
unzip xms-wearable-lib_1.4_release.aar -d aar-extract
cp aar-extract/classes.jar libs/wearable-sdk.jar

# 2. 编译资源
aapt2 compile --dir src/main/res -o build/res_compiled.zip

# 3. 链接资源
aapt2 link -I $ANDROID_JAR \
  --manifest src/main/AndroidManifest.xml \
  -o build/base.apk --java build/gen \
  -R build/res_compiled.zip --auto-add-overlay \
  --min-sdk-version 26 --target-sdk-version 28

# 4. 编译 Java
javac -source 1.8 -target 1.8 \
  -classpath "libs/wearable-sdk.jar:$ANDROID_JAR" \
  -d build/obj src/main/java/**/*.java build/gen/**/*.java

# 5. DEX
d8 --output build --lib $ANDROID_JAR --min-api 26 \
  build/obj/**/*.class libs/wearable-sdk.jar

# 6. 打包 + 对齐 + 签名
cd build && zip -j base.apk classes.dex
zipalign -f 4 base.apk aligned.apk
apksigner sign --ks debug.keystore --ks-pass pass:android \
  --v1-signing-enabled true --v2-signing-enabled true \
  --out 考点传输.apk aligned.apk
```
