package com.silenthong.kdreader.ui;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import com.silenthong.kdreader.R;
import com.silenthong.kdreader.logic.TxtTransferHandler;
import com.silenthong.kdreader.logic.WearableManager;

import java.util.ArrayList;
import java.util.List;

/**
 * 考点传输主界面
 *
 * 交互流程：连接手环 → 获取文件夹树 → 选择目标文件夹 → 选择 txt 文件 → 发送
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_OPEN_FILE = 1001;

    private WearableManager wearableManager;
    private TxtTransferHandler txtTransfer;

    // UI 控件
    private TextView tvConnectionStatus;
    private Button btnConnect;
    private Spinner spinnerFolder;
    private Button btnRequestTree;
    private TextView tvSelectedFile;
    private Button btnSelectFile;
    private Button btnSend;
    private ProgressBar progressBar;
    private TextView tvProgress;

    // 状态
    private Uri selectedFileUri;
    private String selectedFileName;
    private String selectedFileContent;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        initWearableManager();
    }

    @SuppressWarnings("unchecked")
    private void initViews() {
        tvConnectionStatus = findViewById(R.id.tv_connection_status);
        btnConnect = findViewById(R.id.btn_connect);
        spinnerFolder = findViewById(R.id.spinner_folder);
        btnRequestTree = findViewById(R.id.btn_request_tree);
        tvSelectedFile = findViewById(R.id.tv_selected_file);
        btnSelectFile = findViewById(R.id.btn_select_file);
        btnSend = findViewById(R.id.btn_send);
        progressBar = findViewById(R.id.progress_bar);
        tvProgress = findViewById(R.id.tv_progress);

        btnConnect.setOnClickListener(v -> onConnectClick());
        btnRequestTree.setOnClickListener(v -> onRequestTreeClick());
        btnSelectFile.setOnClickListener(v -> onSelectFileClick());
        btnSend.setOnClickListener(v -> onSendClick());
    }

    private void initWearableManager() {
        wearableManager = new WearableManager(this);
        txtTransfer = new TxtTransferHandler(wearableManager);

        wearableManager.setConnectionListener(new WearableManager.ConnectionListener() {
            @Override
            public void onConnected(String deviceName) {
                mainHandler.post(() -> {
                    tvConnectionStatus.setText("已连接: " + deviceName);
                    tvConnectionStatus.setBackgroundColor(0xFF4CAF50);
                    btnConnect.setText("重新连接");
                    btnRequestTree.setEnabled(true);
                    btnSelectFile.setEnabled(true);
                    Toast.makeText(MainActivity.this, "连接成功: " + deviceName, Toast.LENGTH_SHORT).show();

                    // 自动请求文件夹树
                    onRequestTreeClick();
                });
            }

            @Override
            public void onDisconnected() {
                mainHandler.post(() -> {
                    tvConnectionStatus.setText("连接已断开");
                    tvConnectionStatus.setBackgroundColor(0xFFF44336);
                    btnConnect.setText("连接手环");
                    btnRequestTree.setEnabled(false);
                    btnSelectFile.setEnabled(false);
                    btnSend.setEnabled(false);
                    Toast.makeText(MainActivity.this, "连接已断开", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String error) {
                mainHandler.post(() -> {
                    if (error.contains("连接中")) {
                        tvConnectionStatus.setText(error);
                        tvConnectionStatus.setBackgroundColor(0xFFFF9800);
                    } else {
                        tvConnectionStatus.setText("错误: " + error);
                        tvConnectionStatus.setBackgroundColor(0xFFF44336);
                        Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void onConnectClick() {
        btnConnect.setEnabled(false);
        btnConnect.setText("连接中...");
        wearableManager.connect();

        // 5 秒后恢复按钮
        mainHandler.postDelayed(() -> btnConnect.setEnabled(true), 5000);
    }

    private void onRequestTreeClick() {
        if (!wearableManager.isConnected()) {
            Toast.makeText(this, "请先连接手环", Toast.LENGTH_SHORT).show();
            return;
        }

        tvProgress.setText("正在获取文件夹树...");
        btnRequestTree.setEnabled(false);

        txtTransfer.requestFolderTree(new TxtTransferHandler.TreeReceivedCallback() {
            @Override
            public void onTreeReceived(List<TxtTransferHandler.FolderNode> tree) {
                mainHandler.post(() -> {
                    btnRequestTree.setEnabled(true);

                    // 扁平化文件夹列表
                    List<TxtTransferHandler.FolderNode> flatList = new ArrayList<>();
                    for (TxtTransferHandler.FolderNode node : tree) {
                        node.collectFolders(flatList, "");
                    }

                    if (flatList.isEmpty()) {
                        Toast.makeText(MainActivity.this, "没有可用的文件夹", Toast.LENGTH_SHORT).show();
                        tvProgress.setText("没有可用的文件夹");
                        return;
                    }

                    // 填充 Spinner
                    String[] folderNames = new String[flatList.size()];
                    for (int i = 0; i < flatList.size(); i++) {
                        folderNames[i] = flatList.get(i).name;
                    }

                    ArrayAdapter<String> adapter = new ArrayAdapter<>(
                            MainActivity.this, android.R.layout.simple_spinner_item, folderNames);
                    adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
                    spinnerFolder.setAdapter(adapter);

                    // 保存 flatList 供发送时使用
                    spinnerFolder.setTag(flatList);

                    tvProgress.setText("获取到 " + flatList.size() + " 个文件夹");
                    Toast.makeText(MainActivity.this, "文件夹列表已更新", Toast.LENGTH_SHORT).show();
                });
            }

            @Override
            public void onError(String error) {
                mainHandler.post(() -> {
                    btnRequestTree.setEnabled(true);
                    tvProgress.setText("获取失败: " + error);
                    Toast.makeText(MainActivity.this, error, Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void onSelectFileClick() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("text/plain");
        startActivityForResult(intent, REQUEST_OPEN_FILE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_OPEN_FILE && resultCode == RESULT_OK) {
            if (data == null || data.getData() == null) return;

            Uri uri = data.getData();
            selectedFileUri = uri;

            // 获取文件名
            String displayName = uri.getLastPathSegment();
            if (displayName != null && displayName.contains("/")) {
                displayName = displayName.substring(displayName.lastIndexOf('/') + 1);
            }
            // 尝试从 cursor 获取真实文件名
            try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
                if (cursor != null && cursor.moveToFirst()) {
                    int nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                    if (nameIndex >= 0) {
                        displayName = cursor.getString(nameIndex);
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "Failed to get display name", e);
            }

            selectedFileName = displayName != null ? displayName : "未命名.txt";
            tvSelectedFile.setText(selectedFileName);

            // 读取文件内容
            try {
                selectedFileContent = TxtTransferHandler.readTxtFromUri(this, uri);
                tvProgress.setText("文件已加载: " + selectedFileContent.length() + " 字符");
                btnSend.setEnabled(true);
            } catch (Exception e) {
                tvProgress.setText("读取文件失败: " + e.getMessage());
                selectedFileContent = null;
                btnSend.setEnabled(false);
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void onSendClick() {
        if (!wearableManager.isConnected()) {
            Toast.makeText(this, "手环未连接", Toast.LENGTH_SHORT).show();
            return;
        }

        if (selectedFileContent == null || selectedFileContent.isEmpty()) {
            Toast.makeText(this, "请先选择文件", Toast.LENGTH_SHORT).show();
            return;
        }

        // 获取选中的目标文件夹
        List<TxtTransferHandler.FolderNode> flatList = (List<TxtTransferHandler.FolderNode>) spinnerFolder.getTag();
        if (flatList == null || flatList.isEmpty()) {
            Toast.makeText(this, "请先获取文件夹列表", Toast.LENGTH_SHORT).show();
            return;
        }

        int selectedPos = spinnerFolder.getSelectedItemPosition();
        if (selectedPos < 0 || selectedPos >= flatList.size()) {
            Toast.makeText(this, "请选择目标文件夹", Toast.LENGTH_SHORT).show();
            return;
        }

        TxtTransferHandler.FolderNode targetFolder = flatList.get(selectedPos);
        String fileName = TxtTransferHandler.getFileNameWithoutExtension(selectedFileName);

        // 禁用按钮，显示进度
        btnSend.setEnabled(false);
        btnSelectFile.setEnabled(false);
        btnRequestTree.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        tvProgress.setText("准备传输...");

        // 发送
        txtTransfer.sendTxtFile(fileName, selectedFileContent, targetFolder.id,
                new TxtTransferHandler.TransferProgressListener() {
                    @Override
                    public void onProgress(int sent, int total, String message) {
                        mainHandler.post(() -> {
                            progressBar.setMax(total);
                            progressBar.setProgress(sent);
                            tvProgress.setText(message);
                        });
                    }

                    @Override
                    public void onSuccess(String message) {
                        mainHandler.post(() -> {
                            progressBar.setVisibility(View.GONE);
                            tvProgress.setText(message);
                            btnSend.setEnabled(true);
                            btnSelectFile.setEnabled(true);
                            btnRequestTree.setEnabled(true);
                            Toast.makeText(MainActivity.this, "传输成功!", Toast.LENGTH_LONG).show();
                        });
                    }

                    @Override
                    public void onError(String error) {
                        mainHandler.post(() -> {
                            progressBar.setVisibility(View.GONE);
                            tvProgress.setText("错误: " + error);
                            btnSend.setEnabled(true);
                            btnSelectFile.setEnabled(true);
                            btnRequestTree.setEnabled(true);
                            Toast.makeText(MainActivity.this, "传输失败: " + error, Toast.LENGTH_LONG).show();
                        });
                    }
                });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (wearableManager != null) {
            wearableManager.destroy();
        }
    }
}
