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
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * WearableManager is the Android side of the link between this phone app and the
 * "考点阅读器" Vela smartwatch app. It talks to the watch through the Xiaomi XMS
 * Wearable SDK interconnect API.
 *
 * The watch side is built on @system.interconnect, which manages the transport
 * automatically. Therefore the phone only needs a simple bring-up sequence:
 *
 *   find nodes -> request permissions -> launch the watch app at /pages/push
 *   -> register a message listener -> send {"type":"ping"}
 *   -> wait for {"type":"pong"} (10s timeout)
 *
 * Once {"type":"pong"} arrives the link is considered ready. Messages are routed
 * by their "tag" field to the registered callbacks. Any message sent before the
 * link is ready is queued and flushed automatically when "pong" is received.
 *
 * There is no custom handshake protocol, no __hs__ tag, no count cycling and no
 * keep-alive ping loop; the interconnect API handles connection liveness.
 */
public class WearableManager {

    private static final String TAG = "WearableManager";

    /** Watch page that hosts the interconnect peer. */
    private static final String WATCH_APP_PATH = "/pages/push";

    /** Wait up to 10s for the watch to reply with {"type":"pong"}. */
    private static final long READINESS_TIMEOUT = 10000L;

    private static final String TYPE_PING = "ping";
    private static final String TYPE_PONG = "pong";

    private final NodeApi nodeApi;
    private final AuthApi authApi;
    private final MessageApi messageApi;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private Node currentNode;

    /** True once {"type":"pong"} has been received from the watch. */
    private boolean connected = false;
    /** True while we are waiting for the pong response. */
    private boolean readinessPending = false;

    /** tag -> callback routing table. */
    private final Map<String, MessageCallback> messageHandlers = new HashMap<>();

    /** Messages sent before the link became ready; flushed on pong. */
    private final List<QueuedMessage> messageQueue = new ArrayList<>();

    private ConnectionListener connectionListener;
    private MessageReceivedListener messageReceivedListener;

    private Runnable readinessTimeoutRunnable;

    /** A message held until the link is ready. */
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

    /** Register a per-tag message handler. */
    public void addListener(String tag, MessageCallback callback) {
        messageHandlers.put(tag, callback);
    }

    public void removeListener(String tag) {
        messageHandlers.remove(tag);
    }

    /**
     * Tear down the current link: cancel the readiness timer, fail any queued
     * messages, and remove the message listener from the watch node.
     */
    public void destroy() {
        connected = false;
        readinessPending = false;

        if (readinessTimeoutRunnable != null) {
            handler.removeCallbacks(readinessTimeoutRunnable);
            readinessTimeoutRunnable = null;
        }

        for (QueuedMessage msg : messageQueue) {
            if (msg.callback != null) {
                msg.callback.onError("连接已断开");
            }
        }
        messageQueue.clear();

        // Remove the previous listener so a reconnect does not double-register.
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
     * Connect to the watch.
     *
     * Flow: find nodes -> request permissions -> launch /pages/push
     *      -> register listener -> ping -> wait for pong.
     */
    public void connect() {
        // 1. Drop any previous link first.
        destroy();

        // Signal "connecting" through the error channel (preserves the legacy
        // ConnectionListener contract used by the UI).
        notifyError("连接中...");

        // 2. Find connected nodes.
        nodeApi.getConnectedNodes()
                .addOnSuccessListener(new OnSuccessListener<List<Node>>() {
                    @Override
                    public void onSuccess(List<Node> nodes) {
                        if (nodes == null || nodes.isEmpty()) {
                            notifyError("未找到设备！请检查小米运动健康是否已连接");
                            return;
                        }
                        currentNode = nodes.get(0);
                        Log.d(TAG, "Found device: " + currentNode.name);
                        requestPermissions(currentNode.name);
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(Exception e) {
                        notifyError("获取设备列表失败，请检查小米运动健康是否已连接");
                    }
                });
    }

    /**
     * Request the DEVICE_MANAGER permission (fire-and-forget), then continue.
     * launchWearApp does not depend on the permission result, so we always
     * proceed to the next step regardless of the outcome.
     */
    private void requestPermissions(final String deviceName) {
        final Permission[] permissions = { Permission.DEVICE_MANAGER };

        authApi.checkPermissions(currentNode.id, permissions)
                .addOnSuccessListener(new OnSuccessListener<boolean[]>() {
                    @Override
                    public void onSuccess(boolean[] results) {
                        // Request any permissions that are not granted yet.
                        List<Permission> missing = new ArrayList<>();
                        if (results != null) {
                            for (int i = 0; i < results.length && i < permissions.length; i++) {
                                if (!results[i]) {
                                    missing.add(permissions[i]);
                                }
                            }
                        }
                        if (!missing.isEmpty()) {
                            authApi.requestPermission(currentNode.id,
                                            missing.toArray(new Permission[0]))
                                    .addOnFailureListener(e -> Log.w(TAG,
                                            "requestPermission failed (ignored): " + e.getMessage()));
                        }
                        launchWatchApp(deviceName);
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(Exception e) {
                        Log.w(TAG, "checkPermissions failed (continuing): " + e.getMessage());
                        launchWatchApp(deviceName);
                    }
                });
    }

    /** Launch the watch app at /pages/push. */
    private void launchWatchApp(final String deviceName) {
        nodeApi.launchWearApp(currentNode.id, WATCH_APP_PATH)
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void aVoid) {
                        Log.d(TAG, "Watch app launched at " + WATCH_APP_PATH);
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
     * Register the message listener, then start the ping/pong readiness check.
     */
    private void registerMessageListener(final String deviceName) {
        messageApi.addListener(currentNode.id, new OnMessageReceivedListener() {
            @Override
            public void onMessageReceived(String nodeId, byte[] data) {
                String messageStr = new String(data, java.nio.charset.StandardCharsets.UTF_8);
                Log.d(TAG, "<<< Received: " + messageStr);
                handleIncomingMessage(messageStr, deviceName);
            }
        }).addOnSuccessListener(new OnSuccessListener<Void>() {
            @Override
            public void onSuccess(Void aVoid) {
                Log.d(TAG, "Message listener registered");
                startReadinessCheck(deviceName);
            }
        }).addOnFailureListener(new OnFailureListener() {
            @Override
            public void onFailure(Exception e) {
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (msg.contains("You have registered")) {
                    Log.w(TAG, "Listener already registered, continuing");
                    startReadinessCheck(deviceName);
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

    // ==================== Readiness check (ping / pong) ====================

    /**
     * Send {"type":"ping"} and wait up to READINESS_TIMEOUT for
     * {"type":"pong"}. When pong arrives, the link becomes ready and any
     * queued messages are flushed. If pong does not arrive in time, the
     * pending messages are failed and an error is reported.
     */
    private void startReadinessCheck(final String deviceName) {
        readinessPending = true;

        try {
            JSONObject ping = new JSONObject();
            ping.put("type", TYPE_PING);
            sendRawMessage(ping.toString());
            Log.d(TAG, ">>> Readiness ping sent");
        } catch (Exception e) {
            Log.e(TAG, "Failed to build/send ping", e);
        }

        if (readinessTimeoutRunnable != null) {
            handler.removeCallbacks(readinessTimeoutRunnable);
        }
        readinessTimeoutRunnable = () -> {
            if (!readinessPending) {
                return;
            }
            readinessPending = false;
            Log.w(TAG, "Readiness timeout - no pong from watch");
            for (QueuedMessage msg : messageQueue) {
                if (msg.callback != null) {
                    msg.callback.onError("连接就绪超时");
                }
            }
            messageQueue.clear();
            handler.post(() -> {
                if (connectionListener != null) {
                    connectionListener.onError(
                            "连接超时，请确保手环上已打开考点阅读器");
                }
            });
        };
        handler.postDelayed(readinessTimeoutRunnable, READINESS_TIMEOUT);
    }

    /** Dispatch a message received from the watch. */
    private void handleIncomingMessage(String messageStr, String deviceName) {
        try {
            JSONObject msg = new JSONObject(messageStr);
            String type = msg.optString("type", "");

            // 1. Readiness response: {"type":"pong"}.
            if (TYPE_PONG.equals(type)) {
                onReadinessComplete(deviceName);
                return;
            }

            // 2. Route by tag to the registered handler.
            String tag = msg.optString("tag", "");
            MessageCallback cb = messageHandlers.get(tag);
            if (cb != null) {
                cb.onMessage(messageStr);
            }

            // 3. Notify the global listener.
            if (messageReceivedListener != null) {
                messageReceivedListener.onReceived(tag, messageStr);
            }
        } catch (Exception e) {
            Log.e(TAG, "Parse message error", e);
        }
    }

    /** {"type":"pong"} received - the link is ready. */
    private void onReadinessComplete(String deviceName) {
        if (!readinessPending) {
            // Duplicate or late pong after timeout; ignore.
            return;
        }
        readinessPending = false;
        connected = true;

        if (readinessTimeoutRunnable != null) {
            handler.removeCallbacks(readinessTimeoutRunnable);
            readinessTimeoutRunnable = null;
        }

        // Flush messages that were queued before the link came up.
        if (!messageQueue.isEmpty()) {
            Log.d(TAG, "Flushing " + messageQueue.size() + " queued messages");
            List<QueuedMessage> queued = new ArrayList<>(messageQueue);
            messageQueue.clear();
            for (QueuedMessage msg : queued) {
                sendMessage(msg.tag, msg.payload, msg.callback);
            }
        }

        final String name = (deviceName != null && !deviceName.isEmpty()) ? deviceName : "watch";
        handler.post(() -> {
            if (connectionListener != null) {
                connectionListener.onConnected(name);
            }
        });
    }

    // ==================== Sending messages ====================

    /**
     * Send a JSON message to the watch, wrapping the payload with its tag.
     *
     * If the link is not ready yet, the message is queued and delivered
     * automatically once {"type":"pong"} is received.
     */
    public void sendMessage(String tag, JSONObject payload, SendCallback callback) {
        try {
            if (!connected) {
                messageQueue.add(new QueuedMessage(tag, payload, callback));
                Log.d(TAG, "Queued message (waiting for pong): " + tag
                        + ", queue size=" + messageQueue.size());
                return;
            }
            JSONObject message = new JSONObject();
            Iterator<String> it = payload.keys();
            while (it.hasNext()) {
                String key = it.next();
                message.put(key, payload.get(key));
            }
            message.put("tag", tag);
            sendRawMessageWithCallback(message.toString(), callback);
        } catch (Exception e) {
            if (callback != null) {
                callback.onError(e.getMessage());
            }
        }
    }

    /** Send a raw string to the watch with a completion callback. */
    public void sendRawMessageWithCallback(String message, SendCallback callback) {
        Log.d(TAG, ">>> Send: " + message);
        if (currentNode == null) {
            if (callback != null) {
                callback.onError("设备未连接");
            }
            return;
        }
        messageApi.sendMessage(currentNode.id, message.getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void aVoid) {
                        if (callback != null) {
                            callback.onSuccess();
                        }
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(Exception e) {
                        if (callback != null) {
                            callback.onError(e.getMessage());
                        }
                    }
                });
    }

    /** Internal fire-and-forget raw send. */
    private void sendRawMessage(String message) {
        sendRawMessageWithCallback(message, null);
    }

    // ==================== Status ====================

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
