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

/**
 * TXT file transfer handler — matches the Vela app's interconnfile.js protocol.
 *
 * <p>All messages use the tag-based routing of handshake.js/interconn.js:
 * <ul>
 *   <li>Outgoing (Android → Watch): {@code { tag:"file", stat:"...", ...payload }}
 *   <li>Incoming (Watch → Android): {@code { tag:"file", type:"...", ...payload }}
 * </ul>
 *
 * <h3>Protocol (single chapter — the entire file is one chapter)</h3>
 * <pre>
 *   A→W: { tag:"file", stat:"startTransfer", filename, total:1, wordCount, startFrom:0 }
 *   W→A: { tag:"file", type:"ready", count:0, usage:0 }
 *   A→W: { tag:"file", stat:"d", count:0, data:"{index:0,name,content,wordCount,chunkNum,totalChunks}" }
 *   W→A: { tag:"file", type:"next_chunk" }                            // not last chunk
 *   ... repeat for each chunk ...
 *   W→A: { tag:"file", type:"chapter_chunk_complete" }                // last chunk
 *   A→W: { tag:"file", stat:"chapter_complete", count:0 }
 *   W→A: { tag:"file", type:"chapter_saved", count:1, syncedCount:1, totalCount:1, progress:100 }
 *   A→W: { tag:"file", stat:"transfer_complete" }
 *   W→A: { tag:"file", type:"transfer_finished" }                     // done!
 * </pre>
 */
public class TxtTransferHandler {

    private static final String TAG = "TxtTransfer";

    /** Characters per chunk — must match the watch side (interconnfile.js). */
    private static final int CHUNK_SIZE = 8000;

    /** Per-step response timeout (ms). */
    private static final long RESPONSE_TIMEOUT = 15000L;

    // Outgoing stat values
    private static final String STAT_START_TRANSFER  = "startTransfer";
    private static final String STAT_DATA            = "d";
    private static final String STAT_CHAPTER_COMPLETE = "chapter_complete";
    private static final String STAT_TRANSFER_COMPLETE = "transfer_complete";
    private static final String STAT_CANCEL           = "cancel";

    // Incoming type values (watch responses)
    private static final String TYPE_READY                 = "ready";
    private static final String TYPE_NEXT_CHUNK            = "next_chunk";
    private static final String TYPE_CHAPTER_CHUNK_COMPLETE = "chapter_chunk_complete";
    private static final String TYPE_CHAPTER_SAVED        = "chapter_saved";
    private static final String TYPE_TRANSFER_FINISHED    = "transfer_finished";
    private static final String TYPE_ERROR                = "error";
    private static final String TYPE_CANCEL               = "cancel";

    private final WearableManager conn;

    // Transfer context — accessed from both the SDK callback thread and the UI thread.
    // All reads/writes of progressListener and transferring are guarded by 'this'.
    private String pendingFileName;
    private int pendingWordCount;
    private int currentChunkIndex;   // 0-based chunk currently in flight
    private int totalChunks;
    private List<String> chunkList;
    private volatile TransferProgressListener progressListener;
    private Runnable responseTimeoutRunnable;
    private volatile boolean transferring = false;

    /** Target folder ID on the watch (e.g. "bt_root" or "bt_folder_xxx"). */
    private String targetFolder = "bt_root";

    /** Progress callback for the UI layer. */
    public interface TransferProgressListener {
        void onProgress(int sent, int total, String message);
        void onSuccess(String message);
        void onError(String error);
    }

    // ==================== Construction ====================

    public TxtTransferHandler(WearableManager conn) {
        this.conn = conn;

        // Register for "file" tag messages from the watch.
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

    // ==================== Public entry point ====================

    /**
     * Send a TXT file's content to the watch.
     *
     * @param fileName     file name without extension
     * @param content      full text content
     * @param targetFolder folder ID on the watch where the file should be saved
     *                     ("bt_root" for root, "bt_folder_xxx" for sub-folders)
     * @param listener     progress callback (may be null)
     */
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

        // Step 1: send startTransfer and wait for "ready".
        sendStartTransfer();
    }

    // ==================== Outgoing messages ====================

    /**
     * Send { tag:"file", stat:"startTransfer", ... } and wait for "ready".
     * Includes the target folder so the watch knows where to save the file.
     */
    private void sendStartTransfer() {
        JSONObject payload = new JSONObject();
        try {
            payload.put("tag", WearableManager.TAG_FILE);
            payload.put("stat", STAT_START_TRANSFER);
            payload.put("filename", pendingFileName);
            payload.put("total", 1);           // single chapter
            payload.put("wordCount", pendingWordCount);
            payload.put("startFrom", 0);
            payload.put("folder", targetFolder);  // target folder on the watch
        } catch (Exception e) {
            fail("构建 startTransfer 失败: " + e.getMessage());
            return;
        }

        Log.d(TAG, ">>> startTransfer: " + pendingFileName
                + " chunks=" + totalChunks + " words=" + pendingWordCount
                + " folder=" + targetFolder);
        sendRaw(payload, "发送 startTransfer 失败", "等待 ready 超时");
    }

    /**
     * Send a data chunk: { tag:"file", stat:"d", count:0, data:"{...}" }
     * The inner data is a JSON string with chunk details.
     */
    private void sendDataChunk(int chunkNum) {
        if (chunkNum >= totalChunks) {
            return;
        }
        this.currentChunkIndex = chunkNum;
        String chunkContent = chunkList.get(chunkNum);

        // Build the inner data JSON string
        JSONObject innerData = new JSONObject();
        try {
            innerData.put("index", 0);          // chapter index (always 0 for single chapter)
            innerData.put("name", pendingFileName);
            innerData.put("content", chunkContent);
            innerData.put("wordCount", pendingWordCount);
            innerData.put("chunkNum", chunkNum);
            innerData.put("totalChunks", totalChunks);
        } catch (Exception e) {
            fail("构建数据分片失败: " + e.getMessage());
            return;
        }

        // Build the outer message
        JSONObject payload = new JSONObject();
        try {
            payload.put("tag", WearableManager.TAG_FILE);
            payload.put("stat", STAT_DATA);
            payload.put("count", 0);             // chapter index (always 0)
            payload.put("data", innerData.toString());  // double-encoded JSON string
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

    /**
     * Send { tag:"file", stat:"chapter_complete", count:0 }
     * Called after the watch confirms all chunks of the chapter are received.
     */
    private void sendChapterComplete() {
        JSONObject payload = new JSONObject();
        try {
            payload.put("tag", WearableManager.TAG_FILE);
            payload.put("stat", STAT_CHAPTER_COMPLETE);
            payload.put("count", 0);    // chapter index
        } catch (Exception e) {
            fail("构建 chapter_complete 失败: " + e.getMessage());
            return;
        }

        Log.d(TAG, ">>> chapter_complete");
        TransferProgressListener l2 = progressListener;
        if (l2 != null) {
            l2.onProgress(totalChunks, totalChunks,
                    "等待手环保存...");
        }

        sendRaw(payload, "发送 chapter_complete 失败", "等待 chapter_saved 超时");
    }

    /**
     * Send { tag:"file", stat:"transfer_complete" }
     * Called after the watch confirms the chapter is saved.
     */
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

    /**
     * Send a raw JSON payload via the WearableManager and arm the per-step timeout.
     */
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

    // ==================== Incoming message handling ====================

    private void handleWatchMessage(String type, JSONObject msg) {
        if (!transferring) {
            return;
        }

        switch (type) {
            case TYPE_READY:
                handleReady(msg);
                break;
            case TYPE_NEXT_CHUNK:
                handleNextChunk(msg);
                break;
            case TYPE_CHAPTER_CHUNK_COMPLETE:
                handleChapterChunkComplete(msg);
                break;
            case TYPE_CHAPTER_SAVED:
                handleChapterSaved(msg);
                break;
            case TYPE_TRANSFER_FINISHED:
                handleTransferFinished(msg);
                break;
            case TYPE_ERROR:
                handleErrorMsg(msg);
                break;
            case TYPE_CANCEL:
                handleCancelledMsg(msg);
                break;
            default:
                Log.w(TAG, "Unknown watch message type: " + type);
                break;
        }
    }

    /** ready: the watch is ready; begin streaming chunks from index 0. */
    private void handleReady(JSONObject msg) {
        cancelResponseTimeout();
        Log.d(TAG, "<<< ready");
        sendDataChunk(0);
    }

    /** next_chunk: the watch wants the next data chunk. */
    private void handleNextChunk(JSONObject msg) {
        cancelResponseTimeout();
        Log.d(TAG, "<<< next_chunk");
        int next = currentChunkIndex + 1;
        if (next >= totalChunks) {
            // Shouldn't happen — watch should send chapter_chunk_complete for last chunk
            sendChapterComplete();
        } else {
            sendDataChunk(next);
        }
    }

    /** chapter_chunk_complete: the watch received the last chunk of the chapter. */
    private void handleChapterChunkComplete(JSONObject msg) {
        cancelResponseTimeout();
        Log.d(TAG, "<<< chapter_chunk_complete");
        // Tell the watch to save the chapter
        sendChapterComplete();
    }

    /** chapter_saved: the watch saved the chapter to storage. */
    private void handleChapterSaved(JSONObject msg) {
        cancelResponseTimeout();
        Log.d(TAG, "<<< chapter_saved");
        // Send transfer_complete to finalize
        sendTransferComplete();
    }

    /** transfer_finished: the watch confirmed the entire transfer is complete. */
    private void handleTransferFinished(JSONObject msg) {
        cancelResponseTimeout();
        transferring = false;
        Log.d(TAG, "<<< transfer_finished");
        TransferProgressListener l = progressListener;
        progressListener = null;
        if (l != null) {
            l.onSuccess("传输完成: " + pendingFileName);
        }
    }

    /** error: the watch reported an error. */
    private void handleErrorMsg(JSONObject msg) {
        cancelResponseTimeout();
        transferring = false;
        String message = msg.optString("message", "手环传输错误");
        Log.e(TAG, "<<< error: " + message);
        TransferProgressListener l = progressListener;
        progressListener = null;
        if (l != null) {
            l.onError("手环错误: " + message);
        }
    }

    /** cancel: the watch cancelled the transfer. */
    private void handleCancelledMsg(JSONObject msg) {
        cancelResponseTimeout();
        transferring = false;
        Log.d(TAG, "<<< cancel");
        TransferProgressListener l = progressListener;
        progressListener = null;
        if (l != null) {
            l.onError("手环取消了传输");
        }
    }

    // ==================== Timeout management ====================

    private void startResponseTimeout(String timeoutMessage) {
        cancelResponseTimeout();
        responseTimeoutRunnable = () -> {
            Log.w(TAG, "Response timeout: " + timeoutMessage);
            transferring = false;
            sendCancelQuietly();
            TransferProgressListener l = progressListener;
            progressListener = null;
            if (l != null) {
                l.onError(timeoutMessage);
            }
        };
        conn.getHandler().postDelayed(responseTimeoutRunnable, RESPONSE_TIMEOUT);
    }

    private void cancelResponseTimeout() {
        if (responseTimeoutRunnable != null) {
            conn.getHandler().removeCallbacks(responseTimeoutRunnable);
            responseTimeoutRunnable = null;
        }
    }

    /**
     * Send { tag:"file", stat:"cancel" } without waiting for a response.
     */
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
        if (l != null) {
            l.onError(error);
        }
    }

    // ==================== Utilities ====================

    /**
     * Split content into chunks of at most chunkSize characters.
     */
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
        if (chunks.isEmpty()) {
            chunks.add("");
        }
        return chunks;
    }

    /**
     * Read TXT file content from a Uri (UTF-8).
     */
    public static String readTxtFromUri(Context context, Uri uri) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = context.getContentResolver().openInputStream(uri);
             BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int len;
            while ((len = reader.read(buffer)) != -1) {
                sb.append(buffer, 0, len);
            }
        }
        return sb.toString();
    }

    /**
     * Strip the extension from a file name.
     */
    public static String getFileNameWithoutExtension(String fileName) {
        if (fileName == null) return "untitled";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            return fileName.substring(0, dot);
        }
        return fileName;
    }

    /**
     * Cancel the current transfer (user-initiated).
     */
    public void cancelTransfer() {
        if (!transferring) {
            return;
        }
        cancelResponseTimeout();
        transferring = false;
        sendCancelQuietly();
        TransferProgressListener l = progressListener;
        progressListener = null;
        if (l != null) {
            l.onError("传输已取消");
        }
    }
}
