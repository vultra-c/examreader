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

/**
 * WearableManager — Android side of the interconnect link.
 *
 * Matches the official XMS Wearable demo flow:
 *   1. Finds connected nodes
 *    2. Requests DEVICE_MANAGER + NOTIFY permissions
 *    3. Launches the watch app via launchWearApp
 *    4. Registers a message listener
 *    5. Marks as connected
 *
 * Tag-based routing is kept for the file transfer protocol.
 */
public class WearableManager {

    private static final String TAG = "WearableManager";

    /** Connection timeout in milliseconds. */
    private static final long CONNECT_TIMEOUT = 15000L;

    /** File transfer tag used by interconnfile.js. */
    public static final String TAG_FILE = "file";

    /** Tree sync tag used for folder/node synchronization. */
    public static final String TAG_TREE = "tree";

    private final NodeApi nodeApi;
    private final AuthApi authApi;
    private final MessageApi messageApi;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private volatile Node currentNode;
    private volatile boolean connected = false;
    private volatile boolean destroyed = false;
    private volatile ConnectionState connectionState = ConnectionState.DISCONNECTED;
    private Runnable connectTimeoutRunnable;

    /** tag -> callback routing table. Thread-safe for concurrent access. */
    private final ConcurrentHashMap<String, MessageCallback> messageHandlers = new ConcurrentHashMap<>();

    private ConnectionListener connectionListener;

    public interface ConnectionListener {
        void onConnected(String deviceName);
        void onDisconnected();
        void onError(String error);
    }

    /** Connection state enumeration. */
    public enum ConnectionState {
        DISCONNECTED,
        CONNECTING,
        CONNECTED,
        ERROR
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

    /** Register a per-tag message handler. */
    public void addListener(String tag, MessageCallback callback) {
        messageHandlers.put(tag, callback);
    }

    public void removeListener(String tag) {
        messageHandlers.remove(tag);
    }

    public boolean isConnected() {
        return connected && currentNode != null;
    }

    /** Return the current connection state. */
    public ConnectionState getConnectionState() {
        return connectionState;
    }

    public Handler getHandler() {
        return handler;
    }

    /**
     * Tear down: null currentNode synchronously FIRST, then async remove
     * the listener. This prevents the race where a stale removeListener
     * callback nulls a newly-assigned currentNode.
     */
    public void destroy() {
        destroyed = true;
        connected = false;
        connectionState = ConnectionState.DISCONNECTED;
        cancelConnectTimeout();

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

    /**
     * Connect to the watch.
     *
     * Flow: find nodes → request permissions → launch watch app → register listener → connected
     *
     * Matches the official XMS Wearable demo, which calls launchWearApp
     * to ensure the watch quick app is running before communication.
     */
    public void connect() {
        destroyed = false;

        // Drop any previous link first.
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

        // Signal "connecting".
        connectionState = ConnectionState.CONNECTING;
        notifyError("连接中...");

        // Start the connection timeout — if we don't connect within
        // CONNECT_TIMEOUT ms, fire an error.
        startConnectTimeout();

        // Find connected nodes.
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

    /**
     * Launch the watch app — matches the official demo's launchWearApp call.
     * After launching, register the message listener.
     */
    private void launchWatchApp(final String deviceName) {
        if (destroyed || currentNode == null) {
            notifyError("设备未连接");
            return;
        }

        final String nodeId = currentNode.id;

        // Launch the watch app to its entry page.
        // The official demo uses nodeApi.launchWearApp(node.id, "/home").
        // Our app's entry page is "pages/index".
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
                        // Even if launchWearApp fails (app may already be running),
                        // proceed to register the message listener.
                        registerMessageListener(deviceName);
                    }
                });
    }

    /**
     * Request DEVICE_MANAGER and NOTIFY permissions.
     */
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
                        launchWatchApp(deviceName);
                    }
                });
    }

    /**
     * Register the message listener. Once registered, we are connected.
     */
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

    /**
     * Dispatch a message received from the watch.
     * Routes by "tag" field: "file" for transfer, etc.
     * Called on a background SDK thread — must be thread-safe.
     */
    private void handleIncomingMessage(String messageStr) {
        if (destroyed) return;
        try {
            JSONObject msg = new JSONObject(messageStr);
            String tag = msg.optString("tag", "");

            // Route by tag to the registered handler
            MessageCallback callback = messageHandlers.get(tag);
            if (callback != null) {
                callback.onMessage(messageStr);
            } else {
                Log.w(TAG, "No handler for tag: " + tag + " (message: " + messageStr + ")");
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse message: " + messageStr, e);
        }
    }

    private void notifyConnected(String deviceName) {
        cancelConnectTimeout();
        connectionState = ConnectionState.CONNECTED;
        handler.post(() -> {
            if (destroyed) return;
            ConnectionListener l = connectionListener;
            if (l != null) {
                l.onConnected(deviceName);
            }
        });
    }

    private void notifyError(String error) {
        // "连接中..." is a status signal, not a real error — keep CONNECTING state.
        // Real errors cancel the timeout and transition to ERROR state.
        boolean isConnectingSignal = error != null && error.contains("连接中");
        if (!isConnectingSignal) {
            cancelConnectTimeout();
            connectionState = ConnectionState.ERROR;
        }
        handler.post(() -> {
            if (destroyed) return;
            ConnectionListener l = connectionListener;
            if (l != null) {
                l.onError(error);
            }
        });
    }

    // ==================== Connection timeout ====================

    /**
     * Start the connection timeout. If the watch is not connected within
     * {@link #CONNECT_TIMEOUT} ms, {@link #notifyError(String)} is called
     * with a timeout message.
     */
    private void startConnectTimeout() {
        cancelConnectTimeout();
        connectTimeoutRunnable = () -> {
            if (connectionState == ConnectionState.CONNECTING && !connected) {
                Log.w(TAG, "Connection timeout after " + CONNECT_TIMEOUT + "ms");
                connectionState = ConnectionState.ERROR;
                notifyError("连接超时");
            }
        };
        handler.postDelayed(connectTimeoutRunnable, CONNECT_TIMEOUT);
    }

    private void cancelConnectTimeout() {
        if (connectTimeoutRunnable != null) {
            handler.removeCallbacks(connectTimeoutRunnable);
            connectTimeoutRunnable = null;
        }
    }

    /**
     * Send a raw JSON string to the watch.
     */
    public void sendRawMessageWithCallback(String message, SendCallback callback) {
        if (destroyed) {
            if (callback != null) {
                callback.onError("连接已关闭");
            }
            return;
        }
        Node node = currentNode;
        if (node == null) {
            if (callback != null) {
                callback.onError("设备未连接");
            }
            return;
        }

        byte[] data = message.getBytes(StandardCharsets.UTF_8);
        messageApi.sendMessage(node.id, data)
                .addOnSuccessListener(new OnSuccessListener<Void>() {
                    @Override
                    public void onSuccess(Void aVoid) {
                        Log.d(TAG, ">>> Sent: " + message);
                        if (callback != null) {
                            callback.onSuccess();
                        }
                    }
                })
                .addOnFailureListener(new OnFailureListener() {
                    @Override
                    public void onFailure(Exception e) {
                        Log.e(TAG, ">>> Send failed: " + e.getMessage());
                        if (callback != null) {
                            callback.onError(e.getMessage());
                        }
                    }
                });
    }
}
