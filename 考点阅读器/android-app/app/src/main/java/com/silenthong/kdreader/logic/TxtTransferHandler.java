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
 * TXT 文件传输处理器 — 严格复刻弦电子书 "file" 协议。
 *
 * 消息路由 tag = "file"
 *
 * 手机 → 手环（使用 "stat" 字段标识动作）：
 *   1. startTransfer    : {stat:"startTransfer", filename, total, wordCount, startFrom}
 *   2. d (数据片)        : {stat:"d", count, data}
 *                          data 为 JSON 字符串：{index, name, content, wordCount, chunkNum, totalChunks}
 *   3. chapter_complete : {stat:"chapter_complete", count}
 *   4. transfer_complete: {stat:"transfer_complete"}
 *   5. cancel            : {stat:"cancel"}
 *
 * 手环 → 手机（使用 "type" 字段标识事件）：
 *   ready / next_chunk / chapter_chunk_complete / chapter_saved /
 *   transfer_finished / error / cancel
 *
 * 传输流程（单文件，total=1）：
 *   startTransfer → ready
 *   → [d × N 片, 由 next_chunk 驱动逐片发送]
 *   → chapter_chunk_complete → chapter_complete
 *   → chapter_saved → transfer_complete
 *   → transfer_finished
 */
public class TxtTransferHandler {

    private static final String TAG = "TxtTransfer";
    private static final String TAG_MSG = "file";            // 弦电子书协议 tag（非 "txt"）
    private static final int CHUNK_SIZE = 10 * 1024;         // 10KB，与弦电子书一致
    private static final long RESPONSE_TIMEOUT = 15000L;     // 单步响应超时 15 秒

    // 单文件传输固定只有 1 个章节
    private static final int CHAPTER_INDEX = 0;
    private static final int TOTAL_CHAPTERS = 1;

    // 手机 → 手环 stat
    private static final String STAT_START_TRANSFER = "startTransfer";
    private static final String STAT_DATA = "d";
    private static final String STAT_CHAPTER_COMPLETE = "chapter_complete";
    private static final String STAT_TRANSFER_COMPLETE = "transfer_complete";
    private static final String STAT_CANCEL = "cancel";

    // 手环 → 手机 type
    private static final String TYPE_READY = "ready";
    private static final String TYPE_NEXT_CHUNK = "next_chunk";
    private static final String TYPE_CHAPTER_CHUNK_COMPLETE = "chapter_chunk_complete";
    private static final String TYPE_CHAPTER_SAVED = "chapter_saved";
    private static final String TYPE_TRANSFER_FINISHED = "transfer_finished";
    private static final String TYPE_ERROR = "error";
    private static final String TYPE_CANCEL = "cancel";

    private final WearableManager conn;

    // 传输上下文
    private String pendingFileName;
    private int pendingWordCount;
    private int currentChunkIndex;       // 当前要发送的分片序号（0-based）
    private int totalChunks;             // 总分片数
    private List<String> chunkList;      // 分片缓存
    private TransferProgressListener progressListener;
    private Runnable responseTimeoutRunnable;
    private boolean transferring = false;

    public interface TransferProgressListener {
        void onProgress(int sent, int total, String message);
        void onSuccess(String message);
        void onError(String error);
    }

    public interface TreeReceivedCallback {
        void onTreeReceived(List<FolderNode> tree);
        void onError(String error);
    }

    // ==================== 数据模型 ====================

    public static class FolderNode {
        public String id;
        public String name;
        public String type; // "folder" or "content"
        public List<FolderNode> children = new ArrayList<>();

        public void collectFolders(List<FolderNode> out, String prefix) {
            if ("folder".equals(type)) {
                FolderNode copy = new FolderNode();
                copy.id = id;
                copy.name = prefix.isEmpty() ? name : prefix + " / " + name;
                copy.type = type;
                out.add(copy);
                for (FolderNode child : children) {
                    child.collectFolders(out, copy.name);
                }
            }
        }
    }

    // ==================== 构造与初始化 ====================

    public TxtTransferHandler(WearableManager conn) {
        this.conn = conn;

        // 注册消息处理器 — tag="file"，与弦电子书一致
        conn.addListener(TAG_MSG, message -> {
            try {
                JSONObject msg = new JSONObject(message);
                String type = msg.optString("type", "");
                handleBraceletMessage(type, msg);
            } catch (Exception e) {
                Log.e(TAG, "Message parse error", e);
            }
        });
    }

    // ==================== 文件夹树（简化） ====================

    /**
     * 弦电子书协议中不存在文件夹树请求；这里直接返回一个虚拟文件夹"蓝牙传输"（id=bt），
     * 不向手环发送任何消息。
     */
    public void requestFolderTree(TreeReceivedCallback callback) {
        List<FolderNode> tree = new ArrayList<>();
        FolderNode root = new FolderNode();
        root.id = "bt";
        root.name = "蓝牙传输";
        root.type = "folder";
        tree.add(root);

        if (callback != null) {
            callback.onTreeReceived(tree);
        }
    }

    // ==================== TXT 文件发送（弦电子书 file 协议） ====================

    /**
     * 发送 txt 文件内容到手环。
     *
     * 协议流程（单文件，total=1）：
     *   startTransfer → ready → [d × N] → chapter_chunk_complete
     *   → chapter_complete → chapter_saved → transfer_complete → transfer_finished
     *
     * @param fileName     文件名（不含 .txt 后缀）
     * @param content      文件正文
     * @param targetFolder 目标文件夹 ID（弦电子书协议未使用，保留以兼容调用方）
     * @param listener     进度回调
     */
    public void sendTxtFile(String fileName, String content, String targetFolder, TransferProgressListener listener) {
        this.progressListener = listener;
        this.pendingFileName = fileName;
        this.pendingWordCount = content != null ? content.length() : 0;
        this.chunkList = splitContent(content, CHUNK_SIZE);
        this.totalChunks = chunkList.size();
        this.currentChunkIndex = 0;
        this.transferring = true;

        if (listener != null) {
            listener.onProgress(0, totalChunks, "开始传输: " + fileName + "（共 " + totalChunks + " 片）");
        }

        // 1. 发送 startTransfer
        sendStartTransfer();
    }

    /**
     * 1. 发送 startTransfer：{stat:"startTransfer", filename, total, wordCount, startFrom}
     */
    private void sendStartTransfer() {
        JSONObject payload = new JSONObject();
        try {
            payload.put("stat", STAT_START_TRANSFER);
            payload.put("filename", pendingFileName);
            payload.put("total", TOTAL_CHAPTERS);
            payload.put("wordCount", pendingWordCount);
            payload.put("startFrom", CHAPTER_INDEX);
        } catch (Exception e) {
            fail("构建 startTransfer 失败: " + e.getMessage());
            return;
        }

        Log.d(TAG, ">>> startTransfer: " + pendingFileName + " wordCount=" + pendingWordCount);
        conn.sendMessage(TAG_MSG, payload, new WearableManager.SendCallback() {
            @Override
            public void onSuccess() {
                startResponseTimeout("等待手环 ready 响应超时");
            }

            @Override
            public void onError(String error) {
                fail("发送 startTransfer 失败: " + error);
            }
        });
    }

    /**
     * 2. 发送数据片：{stat:"d", count, data}
     *    data 为 JSON 字符串 {index, name, content, wordCount, chunkNum, totalChunks}
     */
    private void sendChunk(int chunkNum) {
        if (chunkNum >= totalChunks) {
            return;
        }
        this.currentChunkIndex = chunkNum;
        String chunkContent = chunkList.get(chunkNum);

        // 构造 data 字段（JSON 字符串）
        JSONObject dataObj = new JSONObject();
        try {
            dataObj.put("index", CHAPTER_INDEX);
            dataObj.put("name", pendingFileName);
            dataObj.put("content", chunkContent);
            dataObj.put("wordCount", pendingWordCount);
            dataObj.put("chunkNum", chunkNum);
            dataObj.put("totalChunks", totalChunks);
        } catch (Exception e) {
            fail("构建分片数据失败: " + e.getMessage());
            return;
        }

        JSONObject payload = new JSONObject();
        try {
            payload.put("stat", STAT_DATA);
            payload.put("count", CHAPTER_INDEX);
            payload.put("data", dataObj.toString());
        } catch (Exception e) {
            fail("构建 d 消息失败: " + e.getMessage());
            return;
        }

        Log.d(TAG, ">>> d chunk " + (chunkNum + 1) + "/" + totalChunks);
        if (progressListener != null) {
            int percent = (int) (chunkNum * 100f / Math.max(1, totalChunks));
            progressListener.onProgress(chunkNum, totalChunks,
                    "传输中 " + percent + "% (" + (chunkNum + 1) + "/" + totalChunks + ")");
        }

        conn.sendMessage(TAG_MSG, payload, new WearableManager.SendCallback() {
            @Override
            public void onSuccess() {
                startResponseTimeout("等待分片响应超时");
            }

            @Override
            public void onError(String error) {
                fail("发送分片 " + (chunkNum + 1) + " 失败: " + error);
            }
        });
    }

    /**
     * 3. 发送 chapter_complete：{stat:"chapter_complete", count}
     */
    private void sendChapterComplete() {
        JSONObject payload = new JSONObject();
        try {
            payload.put("stat", STAT_CHAPTER_COMPLETE);
            payload.put("count", CHAPTER_INDEX);
        } catch (Exception e) {
            fail("构建 chapter_complete 失败: " + e.getMessage());
            return;
        }

        Log.d(TAG, ">>> chapter_complete");
        if (progressListener != null) {
            progressListener.onProgress(totalChunks, totalChunks, "章节传输完成，等待手环保存...");
        }

        conn.sendMessage(TAG_MSG, payload, new WearableManager.SendCallback() {
            @Override
            public void onSuccess() {
                startResponseTimeout("等待 chapter_saved 响应超时");
            }

            @Override
            public void onError(String error) {
                fail("发送 chapter_complete 失败: " + error);
            }
        });
    }

    /**
     * 4. 发送 transfer_complete：{stat:"transfer_complete"}
     */
    private void sendTransferComplete() {
        JSONObject payload = new JSONObject();
        try {
            payload.put("stat", STAT_TRANSFER_COMPLETE);
        } catch (Exception e) {
            fail("构建 transfer_complete 失败: " + e.getMessage());
            return;
        }

        Log.d(TAG, ">>> transfer_complete");
        if (progressListener != null) {
            progressListener.onProgress(totalChunks, totalChunks, "传输完成，等待手环确认...");
        }

        conn.sendMessage(TAG_MSG, payload, new WearableManager.SendCallback() {
            @Override
            public void onSuccess() {
                startResponseTimeout("等待 transfer_finished 响应超时");
            }

            @Override
            public void onError(String error) {
                fail("发送 transfer_complete 失败: " + error);
            }
        });
    }

    // ==================== 手环消息处理（type 字段） ====================

    private void handleBraceletMessage(String type, JSONObject msg) {
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
                handleCancelMsg(msg);
                break;
            default:
                Log.w(TAG, "Unknown bracelet message type: " + type);
                break;
        }
    }

    /**
     * ready：手环就绪，开始发送指定章节
     */
    private void handleReady(JSONObject msg) {
        cancelResponseTimeout();
        int count = msg.optInt("count", CHAPTER_INDEX);
        Log.d(TAG, "<<< ready, count=" + count + " usage=" + msg.optString("usage", ""));
        // count 即待发送的章节序号；单文件仅 0
        sendChunk(0);
    }

    /**
     * next_chunk：发送当前章节的下一片
     */
    private void handleNextChunk(JSONObject msg) {
        cancelResponseTimeout();
        Log.d(TAG, "<<< next_chunk");
        int next = currentChunkIndex + 1;
        if (next >= totalChunks) {
            // 异常情况：已无更多分片，但手环仍要求下一片，直接收尾
            Log.w(TAG, "next_chunk received but no more chunks; sending chapter_complete");
            sendChapterComplete();
            return;
        }
        sendChunk(next);
    }

    /**
     * chapter_chunk_complete：当前章节所有分片接收完毕，发送 chapter_complete
     */
    private void handleChapterChunkComplete(JSONObject msg) {
        cancelResponseTimeout();
        Log.d(TAG, "<<< chapter_chunk_complete");
        sendChapterComplete();
    }

    /**
     * chapter_saved：章节已保存，发送 transfer_complete（单文件即结束）
     */
    private void handleChapterSaved(JSONObject msg) {
        cancelResponseTimeout();
        int count = msg.optInt("count", CHAPTER_INDEX);
        int syncedCount = msg.optInt("syncedCount", TOTAL_CHAPTERS);
        int totalCount = msg.optInt("totalCount", TOTAL_CHAPTERS);
        int progress = msg.optInt("progress", 100);
        Log.d(TAG, "<<< chapter_saved count=" + count + " synced=" + syncedCount
                + "/" + totalCount + " progress=" + progress);
        // 单文件只有 1 章，直接结束传输
        sendTransferComplete();
    }

    /**
     * transfer_finished：传输完成
     */
    private void handleTransferFinished(JSONObject msg) {
        cancelResponseTimeout();
        transferring = false;
        Log.d(TAG, "<<< transfer_finished");
        if (progressListener != null) {
            progressListener.onSuccess("传输完成: " + pendingFileName);
            progressListener = null;
        }
    }

    /**
     * error：手环报错
     */
    private void handleErrorMsg(JSONObject msg) {
        cancelResponseTimeout();
        transferring = false;
        String message = msg.optString("message", "手环传输错误");
        int count = msg.optInt("count", CHAPTER_INDEX);
        Log.e(TAG, "<<< error: " + message + " (count=" + count + ")");
        if (progressListener != null) {
            progressListener.onError("手环错误: " + message);
            progressListener = null;
        }
    }

    /**
     * cancel：手环取消传输
     */
    private void handleCancelMsg(JSONObject msg) {
        cancelResponseTimeout();
        transferring = false;
        Log.d(TAG, "<<< cancel");
        if (progressListener != null) {
            progressListener.onError("传输已被取消");
            progressListener = null;
        }
    }

    // ==================== 超时管理 ====================

    private void startResponseTimeout(String timeoutMessage) {
        cancelResponseTimeout();
        responseTimeoutRunnable = () -> {
            Log.w(TAG, "Response timeout: " + timeoutMessage);
            transferring = false;
            // 超时后通知手环取消本次传输
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
     * 静默发送 cancel，不等待响应
     */
    private void sendCancelQuietly() {
        try {
            JSONObject payload = new JSONObject();
            payload.put("stat", STAT_CANCEL);
            conn.sendMessage(TAG_MSG, payload, null);
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

    // ==================== 工具方法 ====================

    /**
     * 将内容按指定大小切片（按字符数切，不截断 UTF-8 字符）
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
     * 从 Uri 读取 txt 文件内容（UTF-8）
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
     * 从文件名中提取不含后缀的名称
     */
    public static String getFileNameWithoutExtension(String fileName) {
        if (fileName == null) return "未命名";
        int dot = fileName.lastIndexOf('.');
        if (dot > 0) {
            return fileName.substring(0, dot);
        }
        return fileName;
    }

    /**
     * 取消当前传输（主动取消）
     */
    public void cancelTransfer() {
        if (!transferring) {
            return;
        }
        cancelResponseTimeout();
        transferring = false;
        sendCancelQuietly();
        if (progressListener != null) {
            progressListener.onError("传输已取消");
            progressListener = null;
        }
    }
}
