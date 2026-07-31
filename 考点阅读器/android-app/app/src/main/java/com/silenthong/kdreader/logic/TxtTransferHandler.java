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
 * TXT file transfer handler - a straightforward chunked transfer protocol that
 * matches the watch-side implementation.
 *
 * <p>All messages are JSON objects identified by their {@code "type"} field.
 *
 * <h3>Happy path</h3>
 * <pre>
 *   Android -&gt; Watch : {"type":"start","name":"filename","totalChunks":N,"wordCount":N}
 *   Watch    -&gt; Android : {"type":"ready"}
 *   Android -&gt; Watch : {"type":"chunk","index":0,"content":"text_chunk"}
 *   Watch    -&gt; Android : {"type":"ack","index":0}
 *   ... repeat for each chunk ...
 *   Android -&gt; Watch : {"type":"end"}
 *   Watch    -&gt; Android : {"type":"saved","name":"filename"}
 * </pre>
 *
 * <h3>Error / cancel</h3>
 * <pre>
 *   Android -&gt; Watch : {"type":"cancel"}
 *   Watch    -&gt; Android : {"type":"error","message":"..."}
 *   Watch    -&gt; Android : {"type":"cancelled"}
 * </pre>
 *
 * <h3>Message routing</h3>
 * The {@link WearableManager} routes incoming messages by their {@code "tag"} field
 * (see {@code msg.optString("tag", "")}). Watch-side messages use {@code "type"} and
 * carry no {@code "tag"}, so they fall through to the handler registered with the empty
 * tag {@code ""}. Outgoing messages are sent with {@link WearableManager#sendRawMessageWithCallback}
 * so the exact protocol payload is delivered without an injected {@code tag} field.
 */
public class TxtTransferHandler {

    private static final String TAG = "TxtTransfer";

    /** Characters per chunk - must match the watch side. */
    private static final int CHUNK_SIZE = 8000;

    /** Per-step response timeout (start/chunk/end). */
    private static final long RESPONSE_TIMEOUT = 15000L;

    // Message types (shared with the watch)
    private static final String TYPE_START     = "start";
    private static final String TYPE_READY     = "ready";
    private static final String TYPE_CHUNK     = "chunk";
    private static final String TYPE_ACK       = "ack";
    private static final String TYPE_END       = "end";
    private static final String TYPE_SAVED     = "saved";
    private static final String TYPE_CANCEL    = "cancel";
    private static final String TYPE_ERROR     = "error";
    private static final String TYPE_CANCELLED = "cancelled";

    private final WearableManager conn;

    // Transfer context
    private String pendingFileName;
    private int pendingWordCount;
    private int currentChunkIndex;   // 0-based index of the chunk currently in flight
    private int totalChunks;
    private List<String> chunkList;
    private TransferProgressListener progressListener;
    private Runnable responseTimeoutRunnable;
    private boolean transferring = false;

    /** Progress callback for the UI layer. */
    public interface TransferProgressListener {
        void onProgress(int sent, int total, String message);
        void onSuccess(String message);
        void onError(String error);
    }

    // ==================== Construction ====================

    public TxtTransferHandler(WearableManager conn) {
        this.conn = conn;

        // Watch messages use "type" (no "tag"), so they are routed to the "" handler.
        conn.addListener("", message -> {
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
     * Send a TXT file's content to the watch using the chunked transfer protocol.
     *
     * @param fileName file name without extension
     * @param content  full text content
     * @param listener progress callback (may be null)
     */
    public void sendTxtFile(String fileName, String content, TransferProgressListener listener) {
        this.progressListener = listener;
        this.pendingFileName = fileName != null ? fileName : "untitled";
        this.pendingWordCount = content != null ? content.length() : 0;
        this.chunkList = splitContent(content, CHUNK_SIZE);
        this.totalChunks = chunkList.size();
        this.currentChunkIndex = 0;
        this.transferring = true;

        if (listener != null) {
            listener.onProgress(0, totalChunks,
                    "Starting transfer: " + pendingFileName + " (" + totalChunks + " chunks)");
        }

        // Step 1: send "start" and wait for "ready".
        sendStart();
    }

    // ==================== Outgoing messages ====================

    /**
     * Send {"type":"start",...} and wait for the watch's "ready" response.
     */
    private void sendStart() {
        JSONObject payload = new JSONObject();
        try {
            payload.put("type", TYPE_START);
            payload.put("name", pendingFileName);
            payload.put("totalChunks", totalChunks);
            payload.put("wordCount", pendingWordCount);
        } catch (Exception e) {
            fail("Failed to build start message: " + e.getMessage());
            return;
        }

        Log.d(TAG, ">>> start: " + pendingFileName
                + " chunks=" + totalChunks + " words=" + pendingWordCount);
        sendRaw(payload, "Failed to send start message", "Timed out waiting for ready");
    }

    /**
     * Send {"type":"chunk","index":index,"content":...} and wait for the matching "ack".
     */
    private void sendChunk(int index) {
        if (index >= totalChunks) {
            return;
        }
        this.currentChunkIndex = index;
        String chunkContent = chunkList.get(index);

        JSONObject payload = new JSONObject();
        try {
            payload.put("type", TYPE_CHUNK);
            payload.put("index", index);
            payload.put("content", chunkContent);
        } catch (Exception e) {
            fail("Failed to build chunk message: " + e.getMessage());
            return;
        }

        int percent = (int) (index * 100f / Math.max(1, totalChunks));
        Log.d(TAG, ">>> chunk " + (index + 1) + "/" + totalChunks);
        if (progressListener != null) {
            progressListener.onProgress(index, totalChunks,
                    "Transferring " + percent + "% (" + (index + 1) + "/" + totalChunks + ")");
        }

        sendRaw(payload, "Failed to send chunk " + (index + 1), "Timed out waiting for ack");
    }

    /**
     * Send {"type":"end"} and wait for the watch's "saved" response.
     */
    private void sendEnd() {
        JSONObject payload = new JSONObject();
        try {
            payload.put("type", TYPE_END);
        } catch (Exception e) {
            fail("Failed to build end message: " + e.getMessage());
            return;
        }

        Log.d(TAG, ">>> end");
        if (progressListener != null) {
            progressListener.onProgress(totalChunks, totalChunks,
                    "Transfer complete, waiting for watch to save...");
        }

        sendRaw(payload, "Failed to send end message", "Timed out waiting for saved");
    }

    /**
     * Send a raw JSON payload via the WearableManager and arm the per-step timeout
     * once the SDK confirms the bytes were handed off.
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
            case TYPE_ACK:
                handleAck(msg);
                break;
            case TYPE_SAVED:
                handleSaved(msg);
                break;
            case TYPE_ERROR:
                handleErrorMsg(msg);
                break;
            case TYPE_CANCELLED:
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
        sendChunk(0);
    }

    /** ack: the watch acknowledged a chunk; send the next one or finish. */
    private void handleAck(JSONObject msg) {
        cancelResponseTimeout();
        int index = msg.optInt("index", currentChunkIndex);
        Log.d(TAG, "<<< ack index=" + index);
        int next = currentChunkIndex + 1;
        if (next >= totalChunks) {
            // All chunks acknowledged - finalize the transfer.
            sendEnd();
        } else {
            sendChunk(next);
        }
    }

    /** saved: the watch confirmed the file was persisted. */
    private void handleSaved(JSONObject msg) {
        cancelResponseTimeout();
        transferring = false;
        String name = msg.optString("name", pendingFileName);
        Log.d(TAG, "<<< saved name=" + name);
        if (progressListener != null) {
            progressListener.onSuccess("Transfer complete: " + name);
            progressListener = null;
        }
    }

    /** error: the watch reported an error. */
    private void handleErrorMsg(JSONObject msg) {
        cancelResponseTimeout();
        transferring = false;
        String message = msg.optString("message", "Watch transfer error");
        Log.e(TAG, "<<< error: " + message);
        if (progressListener != null) {
            progressListener.onError("Watch error: " + message);
            progressListener = null;
        }
    }

    /** cancelled: the watch cancelled the transfer. */
    private void handleCancelledMsg(JSONObject msg) {
        cancelResponseTimeout();
        transferring = false;
        Log.d(TAG, "<<< cancelled");
        if (progressListener != null) {
            progressListener.onError("Transfer cancelled by watch");
            progressListener = null;
        }
    }

    // ==================== Timeout management ====================

    private void startResponseTimeout(String timeoutMessage) {
        cancelResponseTimeout();
        responseTimeoutRunnable = () -> {
            Log.w(TAG, "Response timeout: " + timeoutMessage);
            transferring = false;
            // Notify the watch so it can tear down its half of the transfer.
            sendCancelQuietly();
            if (progressListener != null) {
                progressListener.onError(timeoutMessage);
                progressListener = null;
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
     * Send {"type":"cancel"} without waiting for a response.
     */
    private void sendCancelQuietly() {
        try {
            JSONObject payload = new JSONObject();
            payload.put("type", TYPE_CANCEL);
            conn.sendRawMessageWithCallback(payload.toString(), null);
        } catch (Exception e) {
            Log.e(TAG, "Failed to send cancel", e);
        }
    }

    private void fail(String error) {
        cancelResponseTimeout();
        transferring = false;
        Log.e(TAG, error);
        if (progressListener != null) {
            progressListener.onError(error);
            progressListener = null;
        }
    }

    // ==================== Utilities ====================

    /**
     * Split content into chunks of at most {@code chunkSize} characters. Splits on
     * UTF-16 code units (same unit the watch side counts), so the original string is
     * restored exactly when chunks are concatenated in order.
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
     * Strip the extension from a file name. Returns "untitled" when the input is null.
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
     * Cancel the current transfer (user-initiated). Sends {"type":"cancel"} to the watch.
     */
    public void cancelTransfer() {
        if (!transferring) {
            return;
        }
        cancelResponseTimeout();
        transferring = false;
        sendCancelQuietly();
        if (progressListener != null) {
            progressListener.onError("Transfer cancelled");
            progressListener = null;
        }
    }
}
