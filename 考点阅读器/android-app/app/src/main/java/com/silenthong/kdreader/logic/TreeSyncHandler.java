package com.silenthong.kdreader.logic;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * TreeSyncHandler — handles directory tree synchronization between the Android
 * phone and the Xiaomi watch.
 *
 * <p>Registers with {@link WearableManager} for "tree" tag messages and exposes
 * a simple request/response API for fetching the directory tree, creating
 * folders, deleting nodes, and renaming nodes.
 *
 * <h3>Protocol</h3>
 * <ul>
 *   <li>Outgoing (Android → Watch):
 *     <ul>
 *       <li>{@code { tag:"tree", action:"getTree" }}
 *       <li>{@code { tag:"tree", action:"createFolder", name, parentId }}
 *       <li>{@code { tag:"tree", action:"deleteNode", nodeId }}
 *       <li>{@code { tag:"tree", action:"renameNode", nodeId, newName }}
 *     </ul>
 *   <li>Incoming (Watch → Android):
 *     <ul>
 *       <li>{@code { tag:"tree", response:"treeData", tree:[...] }}
 *       <li>{@code { tag:"tree", response:"folderCreated", folderId, success }}
 *       <li>{@code { tag:"tree", response:"nodeDeleted", success }}
 *       <li>{@code { tag:"tree", response:"nodeRenamed", success }}
 *     </ul>
 * </ul>
 *
 * <p>All listener callbacks are dispatched on the {@link WearableManager}'s
 * main-thread {@link Handler} so they are safe to touch the UI from.
 *
 * <p>All requests have a 10-second timeout. If the watch does not respond
 * within that window, the corresponding listener callback is invoked with
 * a timeout error.
 */
public class TreeSyncHandler {

    private static final String TAG = "TreeSync";

    /** Per-request response timeout (ms). */
    private static final long RESPONSE_TIMEOUT = 10000L;

    // Outgoing action values
    private static final String ACTION_GET_TREE      = "getTree";
    private static final String ACTION_CREATE_FOLDER = "createFolder";
    private static final String ACTION_DELETE_NODE   = "deleteNode";
    private static final String ACTION_RENAME_NODE   = "renameNode";

    // Incoming response values (watch responses)
    private static final String RESPONSE_TREE_DATA      = "treeData";
    private static final String RESPONSE_FOLDER_CREATED = "folderCreated";
    private static final String RESPONSE_NODE_DELETED   = "nodeDeleted";
    private static final String RESPONSE_NODE_RENAMED   = "nodeRenamed";

    // Pending operation types — used to track which response is expected
    private static final int OP_NONE          = 0;
    private static final int OP_GET_TREE      = 1;
    private static final int OP_CREATE_FOLDER = 2;
    private static final int OP_DELETE_NODE   = 3;
    private static final int OP_RENAME_NODE   = 4;

    private final WearableManager conn;
    private final Handler handler;

    private volatile TreeSyncListener listener;

    // Pending operation tracking for timeout management
    private volatile int pendingOp = OP_NONE;
    private Runnable responseTimeoutRunnable;

    /** Callback for tree sync events. */
    public interface TreeSyncListener {
        /** Called when the watch returns the current directory tree. */
        void onTreeReceived(JSONArray tree);

        /** Called after a createFolder request completes. */
        void onFolderCreated(String folderId, boolean success, String error);

        /** Called after a deleteNode request completes. */
        void onNodeDeleted(boolean success, String error);

        /** Called after a renameNode request completes. */
        void onNodeRenamed(boolean success, String error);
    }

    // ==================== Construction ====================

    public TreeSyncHandler(WearableManager conn) {
        this.conn = conn;
        this.handler = conn != null ? conn.getHandler() : new Handler(Looper.getMainLooper());

        if (conn == null) {
            Log.e(TAG, "WearableManager 为空，无法注册 tree 监听");
            return;
        }

        // Register for "tree" tag messages from the watch.
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

    // ==================== Listener ====================

    public void setListener(TreeSyncListener listener) {
        this.listener = listener;
    }

    // ==================== Public entry points ====================

    /**
     * Request the current directory tree from the watch.
     * The result is delivered via {@link TreeSyncListener#onTreeReceived(JSONArray)}.
     */
    public void requestTree() {
        JSONObject payload = new JSONObject();
        try {
            payload.put("tag", WearableManager.TAG_TREE);
            payload.put("action", ACTION_GET_TREE);
        } catch (Exception e) {
            Log.e(TAG, "构建 getTree 消息失败: " + e.getMessage());
            return;
        }

        Log.d(TAG, ">>> getTree");
        sendRaw(payload, "发送 getTree 失败", "同步文件树超时", OP_GET_TREE);
    }

    /**
     * Create a new folder under the given parent node.
     * The result is delivered via
     * {@link TreeSyncListener#onFolderCreated(String, boolean, String)}.
     *
     * @param name     folder name
     * @param parentId parent node id (root id for top-level folders)
     */
    public void createFolder(String name, String parentId) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("tag", WearableManager.TAG_TREE);
            payload.put("action", ACTION_CREATE_FOLDER);
            payload.put("name", name != null ? name : "");
            payload.put("parentId", parentId != null ? parentId : "");
        } catch (Exception e) {
            Log.e(TAG, "构建 createFolder 消息失败: " + e.getMessage());
            notifyFolderCreated("", false, "构建 createFolder 消息失败: " + e.getMessage());
            return;
        }

        Log.d(TAG, ">>> createFolder: name=" + name + " parentId=" + parentId);
        sendRaw(payload, "发送 createFolder 失败", "创建文件夹超时", OP_CREATE_FOLDER);
    }

    /**
     * Delete a node by id.
     * The result is delivered via
     * {@link TreeSyncListener#onNodeDeleted(boolean, String)}.
     *
     * @param nodeId node id to delete
     */
    public void deleteNode(String nodeId) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("tag", WearableManager.TAG_TREE);
            payload.put("action", ACTION_DELETE_NODE);
            payload.put("nodeId", nodeId != null ? nodeId : "");
        } catch (Exception e) {
            Log.e(TAG, "构建 deleteNode 消息失败: " + e.getMessage());
            notifyNodeDeleted(false, "构建 deleteNode 消息失败: " + e.getMessage());
            return;
        }

        Log.d(TAG, ">>> deleteNode: nodeId=" + nodeId);
        sendRaw(payload, "发送 deleteNode 失败", "删除节点超时", OP_DELETE_NODE);
    }

    /**
     * Rename a node by id.
     * The result is delivered via
     * {@link TreeSyncListener#onNodeRenamed(boolean, String)}.
     *
     * @param nodeId  node id to rename
     * @param newName new name for the node
     */
    public void renameNode(String nodeId, String newName) {
        JSONObject payload = new JSONObject();
        try {
            payload.put("tag", WearableManager.TAG_TREE);
            payload.put("action", ACTION_RENAME_NODE);
            payload.put("nodeId", nodeId != null ? nodeId : "");
            payload.put("newName", newName != null ? newName : "");
        } catch (Exception e) {
            Log.e(TAG, "构建 renameNode 消息失败: " + e.getMessage());
            notifyNodeRenamed(false, "构建 renameNode 消息失败: " + e.getMessage());
            return;
        }

        Log.d(TAG, ">>> renameNode: nodeId=" + nodeId + " newName=" + newName);
        sendRaw(payload, "发送 renameNode 失败", "重命名节点超时", OP_RENAME_NODE);
    }

    // ==================== Outgoing messages ====================

    /**
     * Send a raw JSON payload via the WearableManager and arm the per-request
     * timeout. The watch's response is handled asynchronously in
     * {@link #handleWatchMessage(String, JSONObject)}.
     */
    private void sendRaw(JSONObject payload, String sendErrorMsg,
                         String timeoutMsg, int opType) {
        if (conn == null) {
            Log.e(TAG, sendErrorMsg + ": WearableManager 为空");
            return;
        }
        // Set the pending operation before sending so the timeout fires
        // with the correct context.
        pendingOp = opType;
        final String finalTimeoutMsg = timeoutMsg;
        final int finalOpType = opType;

        conn.sendRawMessageWithCallback(payload.toString(), new WearableManager.SendCallback() {
            @Override
            public void onSuccess() {
                // Message sent — arm the timeout and wait for the watch's response.
                startResponseTimeout(finalTimeoutMsg, finalOpType);
            }

            @Override
            public void onError(String error) {
                Log.e(TAG, sendErrorMsg + ": " + error);
                // Clear the pending op since the send itself failed
                if (pendingOp == finalOpType) {
                    cancelResponseTimeout();
                    pendingOp = OP_NONE;
                }
            }
        });
    }

    // ==================== Timeout management ====================

    /**
     * Start a per-request timeout. If the watch does not respond within
     * {@link #RESPONSE_TIMEOUT} ms, the corresponding listener callback is
     * invoked with a timeout error.
     */
    private void startResponseTimeout(String timeoutMessage, int opType) {
        cancelResponseTimeout();
        final int expectedOp = opType;
        responseTimeoutRunnable = () -> {
            if (pendingOp == expectedOp) {
                Log.w(TAG, "Response timeout: " + timeoutMessage + " (op=" + expectedOp + ")");
                pendingOp = OP_NONE;
                switch (expectedOp) {
                    case OP_GET_TREE:
                        // For tree requests, just log — no error callback
                        // to avoid disrupting the UI on auto-refresh.
                        break;
                    case OP_CREATE_FOLDER:
                        notifyFolderCreated("", false, timeoutMessage);
                        break;
                    case OP_DELETE_NODE:
                        notifyNodeDeleted(false, timeoutMessage);
                        break;
                    case OP_RENAME_NODE:
                        notifyNodeRenamed(false, timeoutMessage);
                        break;
                    default:
                        break;
                }
            }
        };
        if (handler != null) {
            handler.postDelayed(responseTimeoutRunnable, RESPONSE_TIMEOUT);
        }
    }

    private void cancelResponseTimeout() {
        if (responseTimeoutRunnable != null && handler != null) {
            handler.removeCallbacks(responseTimeoutRunnable);
            responseTimeoutRunnable = null;
        }
    }

    // ==================== Incoming message handling ====================

    private void handleWatchMessage(String response, JSONObject msg) {
        switch (response) {
            case RESPONSE_TREE_DATA:
                handleTreeData(msg);
                break;
            case RESPONSE_FOLDER_CREATED:
                handleFolderCreated(msg);
                break;
            case RESPONSE_NODE_DELETED:
                handleNodeDeleted(msg);
                break;
            case RESPONSE_NODE_RENAMED:
                handleNodeRenamed(msg);
                break;
            default:
                Log.w(TAG, "未知的响应类型: " + response);
                break;
        }
    }

    /** treeData: the watch returned the current directory tree. */
    private void handleTreeData(JSONObject msg) {
        cancelResponseTimeout();
        pendingOp = OP_NONE;
        Log.d(TAG, "<<< treeData");
        JSONArray tree = msg.optJSONArray("tree");
        if (tree == null) {
            tree = new JSONArray();
        }
        notifyTreeReceived(tree);
    }

    /** folderCreated: the watch finished creating a folder. */
    private void handleFolderCreated(JSONObject msg) {
        cancelResponseTimeout();
        pendingOp = OP_NONE;
        String folderId = msg.optString("folderId", "");
        boolean success = msg.optBoolean("success", false);
        String error = success ? null : msg.optString("error", "创建文件夹失败");
        Log.d(TAG, "<<< folderCreated: folderId=" + folderId + " success=" + success);
        notifyFolderCreated(folderId, success, error);
    }

    /** nodeDeleted: the watch finished deleting a node. */
    private void handleNodeDeleted(JSONObject msg) {
        cancelResponseTimeout();
        pendingOp = OP_NONE;
        boolean success = msg.optBoolean("success", false);
        String error = success ? null : msg.optString("error", "删除节点失败");
        Log.d(TAG, "<<< nodeDeleted: success=" + success);
        notifyNodeDeleted(success, error);
    }

    /** nodeRenamed: the watch finished renaming a node. */
    private void handleNodeRenamed(JSONObject msg) {
        cancelResponseTimeout();
        pendingOp = OP_NONE;
        boolean success = msg.optBoolean("success", false);
        String error = success ? null : msg.optString("error", "重命名节点失败");
        Log.d(TAG, "<<< nodeRenamed: success=" + success);
        notifyNodeRenamed(success, error);
    }

    // ==================== Listener notifications (thread-safe) ====================

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

    private void notifyNodeRenamed(boolean success, String error) {
        TreeSyncListener l = listener;
        if (l == null || handler == null) return;
        handler.post(() -> l.onNodeRenamed(success, error));
    }
}
