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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 手环通信管理器 — 严格复刻弦电子书 Interconn.kt + InterHandshake.kt
 *
 * 连接流程（与弦电子书 ConnectionHandler.kt 一致）：
 *   destroy() → connect() → auth() → getAppState() → openApp() → registerListener() → handshake
 */
public class WearableManager {

    private static final String TAG = "WearableManager";

    // 握手协议常量（与弦电子书 InterHandshake.kt 一致）
    private static final String HANDSHAKE_TAG = "__hs__";
    private static final long HANDSHAKE_TIMEOUT = 10000L;
    private static final long PING_INTERVAL = 5000L;
    private static final int PHONE_VERSION_CODE = 126430;

    private final NodeApi nodeApi;
    private final AuthApi authApi;
    private final MessageApi messageApi;
    private Node currentNode;
    private final Handler handler = new Handler(Looper.getMainLooper());

    // 连接状态
    private boolean connected = false;
    private boolean isHandshaking = false;

    // 消息路由表 — tag → 回调
    private final Map<String, MessageCallback> messageHandlers = new HashMap<>();

    // 消息队列 — 握手完成前暂存待发送消息（复刻弦电子书 InterHandshake.kt 的 promise?.await()）
    private final List<QueuedMessage> messageQueue = new ArrayList<>();

    // 事件回调
    private ConnectionListener connectionListener;
    private MessageReceivedListener messageReceivedListener;
    private Runnable timeoutRunnable;
    private Runnable pingRunnable;
    private Runnable handshakeTimeoutRunnable;

    /** 排队的消息（等待握手完成后发送） */
    private static class QueuedMessage {
        final String tag;
        final JSONObject payload;
        final SendCallback callback;
        QueuedMessage(String tag, JSONObject payload, SendCallback callback) {
            this.tag = tag;
            this.payload = payload;
            this.callback = callback;
        }
    }

    public interface ConnectionListener {
        void onConnected(String deviceName);
        void onDisconnected();
        void onError(String error);
    }

    public interface MessageCallback {
        void onMessage(String message);
    }

    public interface MessageReceivedListener {
        void onReceived(String tag, String message);
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

    public void setMessageReceivedListener(MessageReceivedListener listener) {
        this.messageReceivedListener = listener;
    }

    /**
     * 注册消息处理器（按 tag 分发）
     */
    public void addListener(String tag, MessageCallback callback) {
        messageHandlers.put(tag, callback);
    }

    public void removeListener(String tag) {
        messageHandlers.remove(tag);
    }

    /**
     * 销毁当前连接（复刻弦电子书 Interconn.kt destroy()）
     * 清理旧的 message listener，防止重复注册
     */
    public void destroy() {
        connected = false;
        isHandshaking = false;
        if (timeoutRunnable != null) {
            handler.removeCallbacks(timeoutRunnable);
            timeoutRunnable = null;
        }
        if (pingRunnable != null) {
            handler.removeCallbacks(pingRunnable);
            pingRunnable = null;
        }
        if (handshakeTimeoutRunnable != null) {
            handler.removeCallbacks(handshakeTimeoutRunnable);
            handshakeTimeoutRunnable = null;
        }
        // 清空消息队列
        for (QueuedMessage msg : messageQueue) {
            if (msg.callback != null) msg.callback.onError("连接已断开");
        }
        messageQueue.clear();
        // 移除旧的 listener
        if (currentNode != null) {
            messageApi.removeListener(currentNode.id)
                    .addOnSuccessListener(aVoid -> {
                        Log.d(TAG, "Previous listener removed");
                        currentNode = null;
                    })
                    .addOnFailureListener(e -> {
                        Log.w(TAG, "Failed to remove previous listener: " + e.getMessage());
                        currentNode = null;
                    });
        }
    }

    /**
     * 连接流程（复刻弦电子书 ConnectionHandler.kt reconnect()）：
     * destroy() → connect() → auth() → getAppState() → openApp() → registerListener() → handshake
     */
    public void connect() {
        // 1. 先销毁旧连接（复刻弦电子书 connection.destroy().await()）
        destroy();

        handler.post(() -> {
            if (connectionListener != null) {
                connectionListener.onError("连接中...");
            }
        });

        // 2. 查找设备（复刻弦电子书 connection.connect().await()）
        nodeApi.getConnectedNodes()
                .addOnSuccessListener(new OnSuccessListener<List<Node>>() {
                    @Override
                    public void onSuccess(List<Node> nodes) {
                        if (nodes == null || nodes.isEmpty()) {
                            notifyError("未找到设备！请检查小米运动健康是否已连接");
                            return;
                        }
                        currentNode = nodes.get(0);
                        String deviceName = currentNode.name;
                        Log.d(TAG, "Found device: " + deviceName);
                        // 3. 认证
                        authAndConnect(deviceName);
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(Exception e) {
                        notifyError("获取设备列表失败，请检查小米运动健康是否已连接");
                    }
                });
    }

    private void authAndConnect(String deviceName) {
        // 复刻弦电子书 connection.auth().await()
        // 弦电子书 auth() 的关键行为：
        //   checkPermissions onSuccess → 即使权限为 false 也只 requestPermission（fire-and-forget），然后立即 complete(Unit) 继续
        //   checkPermissions onFailure → completeExceptionally，但 ConnectionHandler 会捕获并继续
        //   即：无论权限检查结果如何，都必须继续 openApp
        Permission[] permissions = {Permission.DEVICE_MANAGER};

        authApi.checkPermissions(currentNode.id, permissions)
                .addOnSuccessListener(new OnSuccessListener<boolean[]>() {
                    @Override
                    public void onSuccess(boolean[] results) {
                        // 尝试请求缺失的权限（fire-and-forget，不等待结果）
                        for (int i = 0; i < results.length; i++) {
                            if (!results[i]) {
                                authApi.requestPermission(currentNode.id, permissions[i])
                                        .addOnFailureListener(e -> Log.w(TAG, "requestPermission failed (ignored): " + e.getMessage()));
                            }
                        }
                        // 无论权限是否获取成功，都继续 openApp
                        // 弦电子书的行为：complete(Unit) 后立即 openApp().await()
                        openWatchApp(deviceName);
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(Exception e) {
                        // 弦电子书此处 completeExceptionally，但 ConnectionHandler 会 catch 并继续
                        // 我们直接继续 openApp，因为 launchWearApp 不依赖 auth 权限
                        Log.w(TAG, "checkPermissions failed (continuing to openApp): " + e.getMessage());
                        openWatchApp(deviceName);
                    }
                });
    }

    /**
     * 打开手环应用（复刻弦电子书 connection.openApp().await()）
     * launchWearApp 直接启动到 /pages/push 页面
     */
    private void openWatchApp(String deviceName) {
        nodeApi.launchWearApp(currentNode.id, "/pages/push")
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void aVoid) {
                        Log.d(TAG, "Watch app launched at /pages/push");
                        // 6. 注册消息监听
                        registerMessageListener(deviceName);
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(Exception e) {
                        notifyError("打开手环应用失败: " + e.getMessage());
                    }
                });
    }

    /**
     * 注册消息监听（复刻弦电子书 connection.registerListener().await()）
     */
    private void registerMessageListener(String deviceName) {
        messageApi.addListener(currentNode.id, new OnMessageReceivedListener() {
            @Override
            public void onMessageReceived(String nodeId, byte[] data) {
                String messageStr = new String(data);
                Log.d(TAG, "<<< Received: " + messageStr);
                resetTimeout();

                try {
                    JSONObject msg = new JSONObject(messageStr);
                    String tag = msg.optString("tag", "");

                    // 处理握手消息
                    if (HANDSHAKE_TAG.equals(tag)) {
                        handleHandshake(messageStr);
                        return;
                    }

                    // 分发到注册的处理器
                    MessageCallback cb = messageHandlers.get(tag);
                    if (cb != null) {
                        cb.onMessage(messageStr);
                    }

                    // 通知全局监听器
                    if (messageReceivedListener != null) {
                        messageReceivedListener.onReceived(tag, messageStr);
                    }
                } catch (Exception e) {
                    Log.e(TAG, "Parse message error", e);
                }
            }
        }).addOnSuccessListener(new OnSuccessListener<Void>() {
            @Override
            public void onSuccess(Void aVoid) {
                Log.d(TAG, "Listener registered");
                // 7. 开始握手
                startHandshake(deviceName);
            }
        }).addOnFailureListener(new OnFailureListener() {
            @Override
            public void onFailure(Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (msg.contains("You have registered")) {
                    Log.w(TAG, "Listener already registered, continue");
                    startHandshake(deviceName);
                } else {
                    notifyError("注册消息监听失败: " + e.getMessage());
                }
            }
        });
    }

    private void notifyError(String error) {
        handler.post(() -> {
            if (connectionListener != null) {
                connectionListener.onError(error);
            }
        });
    }

    // ==================== 握手协议（复刻弦电子书 InterHandshake.kt） ====================

    /**
     * 开始握手（复刻弦电子书 InterHandshake.kt init block）
     * 发送初始握手包 {tag:"__hs__", count:0, version:PHONE_VERSION_CODE}
     */
    private void startHandshake(String deviceName) {
        Log.d(TAG, "Starting handshake with " + deviceName);
        isHandshaking = true;

        // 发送初始握手包
        sendRawMessage("{\"tag\":\"" + HANDSHAKE_TAG + "\",\"count\":0,\"version\":" + PHONE_VERSION_CODE + "}");

        // 设置握手超时（复刻弦电子书 InterHandshake.kt timeoutCb）
        if (handshakeTimeoutRunnable != null) handler.removeCallbacks(handshakeTimeoutRunnable);
        handshakeTimeoutRunnable = () -> {
            if (!connected) {
                isHandshaking = false;
                Log.w(TAG, "Handshake timeout - device did not respond");
                handler.post(() -> {
                    if (connectionListener != null) {
                        connectionListener.onError("握手超时，请确保手环上已打开考点阅读器");
                    }
                });
            }
        };
        handler.postDelayed(handshakeTimeoutRunnable, HANDSHAKE_TIMEOUT);
    }

    /**
     * 处理握手消息（复刻弦电子书 InterHandshake.kt addListener(TYPE)）
     */
    private void handleHandshake(String payload) {
        try {
            JSONObject data = new JSONObject(payload);
            int count = data.optInt("count", 0);

            // 只有 count > 0 才表示对端收到了我们的握手包
            if (count > 0 && !connected) {
                onHandshakeComplete(currentNode != null ? currentNode.name : "手环");
            }

            // 回复握手（最多到 count=3，与弦电子书一致）
            if (count < 3) {
                sendRawMessage("{\"tag\":\"" + HANDSHAKE_TAG + "\",\"count\":" + (count + 1) + ",\"version\":" + PHONE_VERSION_CODE + "}");
            }
        } catch (Exception e) {
            Log.e(TAG, "Handshake parse error", e);
        }
    }

    /**
     * 握手完成（复刻弦电子书 InterHandshake.kt onConnected）
     */
    private void onHandshakeComplete(String deviceName) {
        connected = true;
        isHandshaking = false;

        // 取消握手超时
        if (handshakeTimeoutRunnable != null) {
            handler.removeCallbacks(handshakeTimeoutRunnable);
            handshakeTimeoutRunnable = null;
        }

        // 启动 ping 保活
        startPing();

        // 启动连接超时检测
        resetTimeout();

        // 处理握手期间排队的消息（复刻弦电子书 promise resolve 后继续发送的逻辑）
        if (!messageQueue.isEmpty()) {
            Log.d(TAG, "Processing " + messageQueue.size() + " queued messages");
            List<QueuedMessage> queued = new ArrayList<>(messageQueue);
            messageQueue.clear();
            for (QueuedMessage msg : queued) {
                sendMessage(msg.tag, msg.payload, msg.callback);
            }
        }

        handler.post(() -> {
            if (connectionListener != null) {
                connectionListener.onConnected(deviceName);
            }
        });
    }

    /**
     * 启动 ping 保活（复刻弦电子书 InterHandshake.kt startPing()）
     */
    private void startPing() {
        if (pingRunnable != null) handler.removeCallbacks(pingRunnable);
        pingRunnable = new Runnable() {
            @Override
            public void run() {
                if (connected) {
                    sendRawMessage("{\"tag\":\"" + HANDSHAKE_TAG + "\",\"count\":0,\"version\":" + PHONE_VERSION_CODE + "}");
                    handler.postDelayed(this, PING_INTERVAL);
                }
            }
        };
        handler.postDelayed(pingRunnable, PING_INTERVAL);
    }

    /**
     * 重置连接超时（复刻弦电子书 InterHandshake.kt resetTimeout()）
     */
    private void resetTimeout() {
        if (timeoutRunnable != null) handler.removeCallbacks(timeoutRunnable);
        timeoutRunnable = () -> {
            Log.w(TAG, "Connection timeout");
            connected = false;
            // 清空消息队列并通知错误
            for (QueuedMessage msg : messageQueue) {
                if (msg.callback != null) msg.callback.onError("连接超时");
            }
            messageQueue.clear();
            handler.post(() -> {
                if (connectionListener != null) {
                    connectionListener.onDisconnected();
                }
            });
        };
        handler.postDelayed(timeoutRunnable, HANDSHAKE_TIMEOUT);
    }

    // ==================== 消息发送（复刻弦电子书 InterHandshake.kt sendMessage） ====================

    /**
     * 发送 JSON 消息到手环（自动包裹 tag）
     * 若握手未完成，将消息入队等待（复刻弦电子书 InterHandshake.kt 的 promise?.await()）
     */
    public void sendMessage(String tag, JSONObject payload, SendCallback callback) {
        try {
            if (!connected) {
                // 握手未完成，将消息入队等待
                messageQueue.add(new QueuedMessage(tag, payload, callback));
                Log.d(TAG, "Queued message (waiting for handshake): " + tag + ", queue size=" + messageQueue.size());
                return;
            }
            JSONObject message = new JSONObject();
            // 合并 payload 到 message
            for (java.util.Iterator<String> it = payload.keys(); it.hasNext(); ) {
                String key = it.next();
                message.put(key, payload.get(key));
            }
            message.put("tag", tag);
            sendRawMessageWithCallback(message.toString(), callback);
        } catch (Exception e) {
            if (callback != null) callback.onError(e.getMessage());
        }
    }

    /**
     * 直接发送原始字符串消息
     */
    public void sendRawMessageWithCallback(String message, SendCallback callback) {
        Log.d(TAG, ">>> Send: " + message);
        if (currentNode == null) {
            if (callback != null) callback.onError("设备未连接");
            return;
        }
        messageApi.sendMessage(currentNode.id, message.getBytes())
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void aVoid) {
                        if (callback != null) callback.onSuccess();
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(Exception e) {
                        if (callback != null) callback.onError(e.getMessage());
                    }
                });
    }

    /**
     * 发送原始字符串消息（内部用，无回调）
     */
    private void sendRawMessage(String message) {
        sendRawMessageWithCallback(message, null);
    }

    // ==================== 状态查询 ====================

    public Handler getHandler() {
        return handler;
    }

    public boolean isConnected() {
        return connected;
    }

    public String getDeviceName() {
        return currentNode != null ? currentNode.name : null;
    }
}
