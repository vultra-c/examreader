package com.silenthong.kdreader.ui;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.silenthong.kdreader.R;
import com.silenthong.kdreader.logic.TxtTransferHandler;
import com.silenthong.kdreader.logic.WearableManager;

/**
 * 考点传输主界面。
 *
 * 与小米手环上的「考点阅读器」配对，将本地 TXT 文件传输到手环。
 *
 * 交互流程：
 *   1. 打开应用后自动连接手环
 *   2. 通过系统文件选择器选择 TXT 文件
 *   3. 点击「发送到手环」开始传输
 *   4. 传输过程中显示进度条与状态文本
 *   5. 传输结束弹出成功 / 失败提示
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_OPEN_FILE = 1001;

    /** 传输时使用的目标文件夹标识（弦电子书协议未使用，保留以兼容调用方）。 */
    private static final String TARGET_FOLDER_ID = "bt";

    // 连接状态背景色（Material Design 配色）
    private static final int COLOR_CONNECTED = 0xFF4CAF50;    // 绿 —— 已连接（成功）
    private static final int COLOR_DISCONNECTED = 0xFFF44336; // 红 —— 已断开（错误）
    private static final int COLOR_CONNECTING = 0xFFFF9800;   // 橙 —— 连接中
    private static final int COLOR_ERROR = 0xFFF44336;        // 红 —— 错误

    private WearableManager wearableManager;
    private TxtTransferHandler txtTransfer;

    // UI 控件
    private TextView tvConnectionStatus;
    private Button btnConnect;
    private TextView tvSelectedFile;
    private Button btnSelectFile;
    private Button btnSend;
    private ProgressBar progressBar;
    private TextView tvProgress;

    // 选中的文件
    private String selectedFileName;
    private String selectedFileContent;

    // 运行状态
    private volatile boolean transferring = false;
    private volatile boolean destroyed = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        initWearableManager();

        // 打开应用即自动连接手环
        showConnecting();
        wearableManager.connect();
    }

    private void initViews() {
        tvConnectionStatus = (TextView) findViewById(R.id.tv_connection_status);
        btnConnect = (Button) findViewById(R.id.btn_connect);
        tvSelectedFile = (TextView) findViewById(R.id.tv_selected_file);
        btnSelectFile = (Button) findViewById(R.id.btn_select_file);
        btnSend = (Button) findViewById(R.id.btn_send);
        progressBar = (ProgressBar) findViewById(R.id.progress_bar);
        tvProgress = (TextView) findViewById(R.id.tv_progress);

        btnConnect.setOnClickListener(v -> onConnectClick());
        btnSelectFile.setOnClickListener(v -> onSelectFileClick());
        btnSend.setOnClickListener(v -> onSendClick());

        updateSendButtonState();
    }

    private void initWearableManager() {
        wearableManager = new WearableManager(this);
        txtTransfer = new TxtTransferHandler(wearableManager);

        wearableManager.setConnectionListener(new WearableManager.ConnectionListener() {
            @Override
            public void onConnected(String deviceName) {
                mainHandler.post(() -> {
                    if (destroyed) return;
                    tvConnectionStatus.setText("已连接：" + deviceName);
                    setStatusColor(COLOR_CONNECTED);
                    btnConnect.setText("重新连接");
                    btnConnect.setEnabled(true);
                    updateSendButtonState();
                    Toast.makeText(MainActivity.this,
                            "连接成功：" + deviceName, Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onDisconnected() {
                mainHandler.post(() -> {
                    if (destroyed) return;
                    tvConnectionStatus.setText("连接已断开");
                    setStatusColor(COLOR_DISCONNECTED);
                    btnConnect.setText("重新连接");
                    btnConnect.setEnabled(true);
                    updateSendButtonState();
                    if (!transferring) {
                        Toast.makeText(MainActivity.this,
                                "连接已断开", Toast.LENGTH_SHORT).show();
                    }
                });
            }

            @Override
            public void onError(String error) {
                mainHandler.post(() -> {
                    if (destroyed) return;
                    if (error != null && error.contains("连接中")) {
                        // 连接进行中的提示，不弹 toast
                        tvConnectionStatus.setText(error);
                        setStatusColor(COLOR_CONNECTING);
                    } else {
                        tvConnectionStatus.setText("错误：" + error);
                        setStatusColor(COLOR_ERROR);
                        btnConnect.setText("重新连接");
                        btnConnect.setEnabled(true);
                        updateSendButtonState();
                        Toast.makeText(MainActivity.this,
                                error, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    /** 用户点击「重新连接」。 */
    private void onConnectClick() {
        btnConnect.setEnabled(false);
        btnConnect.setText("连接中...");
        showConnecting();
        wearableManager.connect();
        // 5 秒后兜底恢复按钮，避免一直禁用
        mainHandler.postDelayed(() -> {
            if (!destroyed) btnConnect.setEnabled(true);
        }, 5000);
    }

    /** 通过系统文件选择器选择 TXT 文件。 */
    private void onSelectFileClick() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        startActivityForResult(intent, REQUEST_OPEN_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQUEST_OPEN_FILE || resultCode != RESULT_OK) return;
        if (data == null || data.getData() == null) return;

        Uri uri = data.getData();
        selectedFileName = queryDisplayName(uri);
        if (selectedFileName == null || selectedFileName.trim().isEmpty()) {
            selectedFileName = "未命名.txt";
        }
        tvSelectedFile.setText(selectedFileName);

        // 读取文件内容
        try {
            selectedFileContent = TxtTransferHandler.readTxtFromUri(this, uri);
            tvProgress.setText("文件已加载：" + selectedFileContent.length() + " 字符");
        } catch (Exception e) {
            Log.w(TAG, "readTxtFromUri failed", e);
            selectedFileContent = null;
            tvSelectedFile.setText("读取失败：" + selectedFileName);
            tvProgress.setText("读取文件失败：" + safeMessage(e));
        }
        updateSendButtonState();
    }

    /** 通过 ContentResolver 查询文件显示名。 */
    private String queryDisplayName(Uri uri) {
        String name = uri.getLastPathSegment();
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    name = cursor.getString(idx);
                }
            }
        } catch (Exception e) {
            Log.w(TAG, "queryDisplayName failed", e);
        }
        return name;
    }

    /** 点击「发送到手环」。 */
    private void onSendClick() {
        if (wearableManager == null || !wearableManager.isConnected()) {
            Toast.makeText(this, "手环未连接，请先连接", Toast.LENGTH_SHORT).show();
            return;
        }
        if (selectedFileContent == null || selectedFileContent.isEmpty()) {
            Toast.makeText(this, "请先选择 TXT 文件", Toast.LENGTH_SHORT).show();
            return;
        }

        transferring = true;
        btnSend.setEnabled(false);
        btnSelectFile.setEnabled(false);
        btnConnect.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        tvProgress.setText("准备传输...");

        // 发送文件名（不含扩展名）与正文到手环
        String fileName = TxtTransferHandler.getFileNameWithoutExtension(selectedFileName);
        txtTransfer.sendTxtFile(fileName, selectedFileContent,
                new TxtTransferHandler.TransferProgressListener() {
                    @Override
                    public void onProgress(int sent, int total, String message) {
                        mainHandler.post(() -> {
                            if (destroyed) return;
                            progressBar.setMax(Math.max(total, 1));
                            progressBar.setProgress(sent);
                            tvProgress.setText(message);
                        });
                    }

                    @Override
                    public void onSuccess(String message) {
                        mainHandler.post(() -> {
                            if (destroyed) return;
                            transferring = false;
                            progressBar.setVisibility(View.GONE);
                            tvProgress.setText(message);
                            btnSelectFile.setEnabled(true);
                            btnConnect.setEnabled(true);
                            updateSendButtonState();
                            Toast.makeText(MainActivity.this,
                                    "传输成功！", Toast.LENGTH_LONG).show();
                        });
                    }

                    @Override
                    public void onError(String error) {
                        mainHandler.post(() -> {
                            if (destroyed) return;
                            transferring = false;
                            progressBar.setVisibility(View.GONE);
                            tvProgress.setText("错误：" + error);
                            btnSelectFile.setEnabled(true);
                            btnConnect.setEnabled(true);
                            updateSendButtonState();
                            Toast.makeText(MainActivity.this,
                                    "传输失败：" + error, Toast.LENGTH_LONG).show();
                        });
                    }
                });
    }

    /** 根据连接状态与文件加载状态刷新「发送到手环」按钮可用性。 */
    private void updateSendButtonState() {
        boolean ready = wearableManager != null
                && wearableManager.isConnected()
                && selectedFileContent != null
                && !selectedFileContent.isEmpty()
                && !transferring;
        btnSend.setEnabled(ready);
    }

    /** 显示「连接中」状态。 */
    private void showConnecting() {
        tvConnectionStatus.setText("正在连接手环...");
        setStatusColor(COLOR_CONNECTING);
    }

    /** 设置连接状态栏背景色（保留圆角形状）。 */
    private void setStatusColor(int color) {
        Drawable bg = tvConnectionStatus.getBackground();
        if (bg != null) {
            bg.mutate().setTint(color);
        }
    }

    private static String safeMessage(Throwable t) {
        if (t == null) return "";
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    @Override
    protected void onDestroy() {
        destroyed = true;
        mainHandler.removeCallbacksAndMessages(null);
        if (txtTransfer != null) {
            txtTransfer.cancelTransfer();
        }
        if (wearableManager != null) {
            wearableManager.destroy();
        }
        super.onDestroy();
    }
}
