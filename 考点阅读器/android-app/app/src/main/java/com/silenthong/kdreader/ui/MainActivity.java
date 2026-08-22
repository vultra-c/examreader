package com.silenthong.kdreader.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.silenthong.kdreader.R;
import com.silenthong.kdreader.logic.TxtTransferHandler;
import com.silenthong.kdreader.logic.TreeSyncHandler;
import com.silenthong.kdreader.logic.WearableManager;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 考点传输主界面。
 *
 * 功能：
 *   1. 自动连接手环
 *   2. 连接后同步手环文件树（仅文件夹名和文件名，不含内容）
 *   3. 用户可浏览手环文件树，创建文件夹，重命名节点，选择目标文件夹
 *   4. 选择 TXT 文件并发送到手环的指定文件夹
 *   5. 传输完成后记录历史，最多保留 10 条，显示最近 5 条
 *   6. 断线后自动重连一次（3 秒延迟）
 */
public class MainActivity extends Activity {

    private static final String TAG = "MainActivity";
    private static final int REQUEST_OPEN_FILE = 1001;

    /** Large file threshold (characters). */
    private static final int LARGE_FILE_THRESHOLD = 50000;

    /** Maximum number of history entries kept in memory. */
    private static final int MAX_HISTORY = 10;

    /** Maximum number of history entries displayed. */
    private static final int MAX_HISTORY_DISPLAY = 5;

    /** Auto-reconnect delay in milliseconds. */
    private static final long AUTO_RECONNECT_DELAY = 3000L;

    // Connection status types — each maps to a drawable background
    private static final int STATUS_CONNECTING = 0;
    private static final int STATUS_CONNECTED = 1;
    private static final int STATUS_DISCONNECTED = 2;
    private static final int STATUS_ERROR = 3;

    // Special folder IDs
    private static final String ROOT_BT = "bt_root";       // 蓝牙传输根目录（主页，不显示为树节点）

    private WearableManager wearableManager;
    private TxtTransferHandler txtTransfer;
    private TreeSyncHandler treeSync;

    // UI: Connection
    private View cardConnection;
    private View statusIndicator;
    private TextView tvConnectionStatus;
    private Button btnConnect;

    // UI: File tree
    private TextView tvTreeStatus;
    private LinearLayout lvTreeContainer;
    private Button btnRefreshTree;

    // UI: Folder actions
    private Button btnNewFolder;
    private Button btnRename;
    private Button btnDeleteNode;

    // UI: File selection
    private TextView tvSelectedFile;
    private Button btnSelectFile;

    // UI: Target folder
    private TextView tvTargetFolder;

    // UI: Send
    private Button btnSend;
    private ProgressBar progressBar;
    private TextView tvProgress;

    // UI: Transfer history
    private LinearLayout lvHistoryContainer;

    // State
    private String selectedFileName;
    private String selectedFileContent;
    /** True when the picked file is a knowledge-point JSON (.json). */
    private boolean selectedFileIsJson = false;
    private volatile boolean transferring = false;
    private volatile boolean destroyed = false;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Tree data
    private JSONArray currentTree = null;
    private TreeNode selectedNode = null;      // currently selected tree node
    private String targetFolderId = ROOT_BT;   // where files will be sent (default: root)
    private String targetFolderPath;           // display path

    // Expanded folder state: folderId -> expanded
    private final Map<String, Boolean> expandedFolders = new HashMap<>();

    // Transfer history
    private final List<TransferRecord> transferHistory = new ArrayList<>();

    // Auto-reconnect
    private final Handler autoReconnectHandler = new Handler(Looper.getMainLooper());
    private boolean autoReconnectAttempted = false;

    // ==================== Lifecycle ====================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        targetFolderPath = getString(R.string.default_upload);

        initViews();
        initWearableManager();

        showConnecting();
        wearableManager.connect();
    }

    // ==================== View initialization ====================

    private void initViews() {
        cardConnection = findViewById(R.id.card_connection);
        statusIndicator = findViewById(R.id.status_indicator);
        tvConnectionStatus = findViewById(R.id.tv_connection_status);
        btnConnect = findViewById(R.id.btn_connect);
        tvTreeStatus = findViewById(R.id.tv_tree_status);
        lvTreeContainer = findViewById(R.id.lv_tree_container);
        btnRefreshTree = findViewById(R.id.btn_refresh_tree);
        btnNewFolder = findViewById(R.id.btn_new_folder);
        btnRename = findViewById(R.id.btn_rename);
        btnDeleteNode = findViewById(R.id.btn_delete_node);
        tvSelectedFile = findViewById(R.id.tv_selected_file);
        btnSelectFile = findViewById(R.id.btn_select_file);
        tvTargetFolder = findViewById(R.id.tv_target_folder);
        btnSend = findViewById(R.id.btn_send);
        progressBar = findViewById(R.id.progress_bar);
        tvProgress = findViewById(R.id.tv_progress);
        lvHistoryContainer = findViewById(R.id.lv_history_container);

        btnConnect.setOnClickListener(v -> onConnectClick());
        btnSelectFile.setOnClickListener(v -> onSelectFileClick());
        btnSend.setOnClickListener(v -> onSendClick());
        btnNewFolder.setOnClickListener(v -> onNewFolderClick());
        btnRename.setOnClickListener(v -> onRenameClick());
        btnDeleteNode.setOnClickListener(v -> onDeleteNodeClick());
        btnRefreshTree.setOnClickListener(v -> onRefreshTreeClick());

        updateTargetFolderDisplay();
        updateSendButtonState();
        renderHistory();
    }

    private void initWearableManager() {
        wearableManager = new WearableManager(this);
        txtTransfer = new TxtTransferHandler(wearableManager);
        treeSync = new TreeSyncHandler(wearableManager);

        // Connection listener
        wearableManager.setConnectionListener(new WearableManager.ConnectionListener() {
            @Override
            public void onConnected(String deviceName) {
                mainHandler.post(() -> {
                    if (destroyed) return;
                    autoReconnectAttempted = false;
                    tvConnectionStatus.setText(getString(R.string.connected_prefix) + deviceName);
                    setStatusBackground(STATUS_CONNECTED);
                    btnConnect.setText(R.string.reconnect);
                    btnConnect.setEnabled(true);
                    updateSendButtonState();
                    Toast.makeText(MainActivity.this,
                            getString(R.string.connect_success) + deviceName,
                            Toast.LENGTH_SHORT).show();
                    // Request tree sync after connection
                    tvTreeStatus.setText(R.string.tree_syncing);
                    treeSync.requestTree();
                });
            }

            @Override
            public void onDisconnected() {
                mainHandler.post(() -> {
                    if (destroyed) return;
                    tvConnectionStatus.setText(R.string.disconnected);
                    setStatusBackground(STATUS_DISCONNECTED);
                    btnConnect.setText(R.string.reconnect);
                    btnConnect.setEnabled(true);
                    updateSendButtonState();
                    tvTreeStatus.setText(R.string.tree_not_connected);
                    if (!transferring) {
                        Toast.makeText(MainActivity.this,
                                R.string.disconnected, Toast.LENGTH_SHORT).show();
                    }
                    // Auto-reconnect once after a 3-second delay
                    attemptAutoReconnect();
                });
            }

            @Override
            public void onError(String error) {
                mainHandler.post(() -> {
                    if (destroyed) return;
                    if (error != null && error.contains(getString(R.string.connecting_status))) {
                        // "连接中..." status signal
                        tvConnectionStatus.setText(error);
                        setStatusBackground(STATUS_CONNECTING);
                    } else {
                        tvConnectionStatus.setText(getString(R.string.error_prefix) + error);
                        setStatusBackground(STATUS_ERROR);
                        btnConnect.setText(R.string.reconnect);
                        btnConnect.setEnabled(true);
                        updateSendButtonState();
                        Toast.makeText(MainActivity.this,
                                error, Toast.LENGTH_LONG).show();
                        // Attempt auto-reconnect for real errors (e.g. timeout)
                        attemptAutoReconnect();
                    }
                });
            }
        });

        // Tree sync listener
        treeSync.setListener(new TreeSyncHandler.TreeSyncListener() {
            @Override
            public void onTreeReceived(JSONArray tree) {
                if (destroyed) return;
                currentTree = tree;
                tvTreeStatus.setText(getString(R.string.tree_synced, tree.length()));
                // Default target is root (bt_root) — no need to select a node
                targetFolderId = ROOT_BT;
                targetFolderPath = getString(R.string.default_upload);
                updateTargetFolderDisplay();
                renderTree();
            }

            @Override
            public void onFolderCreated(String folderId, boolean success, String error) {
                if (destroyed) return;
                if (success) {
                    Toast.makeText(MainActivity.this,
                            R.string.folder_created, Toast.LENGTH_SHORT).show();
                    tvTreeStatus.setText(R.string.refreshing_tree);
                    treeSync.requestTree();
                } else {
                    Toast.makeText(MainActivity.this,
                            getString(R.string.create_failed) + error,
                            Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onNodeDeleted(boolean success, String error) {
                if (destroyed) return;
                if (success) {
                    Toast.makeText(MainActivity.this,
                            R.string.deleted, Toast.LENGTH_SHORT).show();
                    // Clear selection if deleted
                    selectedNode = null;
                    tvTreeStatus.setText(R.string.refreshing_tree);
                    treeSync.requestTree();
                } else {
                    Toast.makeText(MainActivity.this,
                            getString(R.string.delete_failed) + error,
                            Toast.LENGTH_LONG).show();
                }
            }

            @Override
            public void onNodeRenamed(boolean success, String error) {
                if (destroyed) return;
                if (success) {
                    Toast.makeText(MainActivity.this,
                            R.string.renamed, Toast.LENGTH_SHORT).show();
                    tvTreeStatus.setText(R.string.refreshing_tree);
                    treeSync.requestTree();
                } else {
                    Toast.makeText(MainActivity.this,
                            getString(R.string.rename_failed) + error,
                            Toast.LENGTH_LONG).show();
                }
            }
        });
    }

    // ==================== Tree rendering ====================

    /**
     * Render the file tree in the tree container.
     * Uses a flat list with indentation to represent hierarchy.
     */
    private void renderTree() {
        lvTreeContainer.removeAllViews();

        if (currentTree == null || currentTree.length() == 0) {
            // No items on the watch — show a hint
            TextView empty = new TextView(this);
            empty.setText(R.string.tree_empty);
            empty.setTextColor(getColor(R.color.text_hint));
            empty.setTextSize(TypedValueDimension(R.dimen.text_caption));
            int padSm = getDimen(R.dimen.margin_sm);
            int padMd = getDimen(R.dimen.margin_md);
            empty.setPadding(padMd, padSm, padSm, padSm);
            lvTreeContainer.addView(empty);
            return;
        }

        // Recursively add all tree nodes from the watch
        for (int i = 0; i < currentTree.length(); i++) {
            try {
                JSONObject node = currentTree.optJSONObject(i);
                if (node != null) {
                    renderNode(node, 0);
                }
            } catch (Exception e) {
                Log.w(TAG, "renderTree error at index " + i, e);
            }
        }
    }

    /**
     * Recursively render a tree node and its children.
     */
    private void renderNode(JSONObject node, int depth) {
        String id = node.optString("id", "");
        String name = node.optString("name", "");
        String type = node.optString("type", "");
        boolean isFolder = "folder".equals(type);

        // Default expand top-level folders (depth 0)
        if (depth == 0 && isFolder) {
            expandedFolders.putIfAbsent(id, true);
        }

        boolean expanded = isFolder && Boolean.TRUE.equals(expandedFolders.get(id));

        addTreeItem(id, name, type, depth, expanded);

        if (isFolder && expanded) {
            JSONArray children = node.optJSONArray("children");
            if (children != null) {
                for (int i = 0; i < children.length(); i++) {
                    JSONObject child = children.optJSONObject(i);
                    if (child != null) {
                        renderNode(child, depth + 1);
                    }
                }
            }
        }
    }

    /**
     * Create and add a single tree item (folder or file) to the container.
     * Uses ImageView with vector icons instead of emoji.
     * Uses ripple background for better touch feedback.
     */
    private void addTreeItem(String id, String name, String type, int depth, boolean expanded) {
        boolean isFolder = "folder".equals(type);
        boolean isSelected = selectedNode != null && id.equals(selectedNode.id);
        boolean isBtFolder = id.startsWith("bt_folder_");

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        int treeIndent = getDimen(R.dimen.tree_indent);
        int indent = depth * treeIndent;
        int padSm = getDimen(R.dimen.margin_sm);
        int padMd = getDimen(R.dimen.padding_md);
        row.setPadding(indent + padSm, padSm, padSm, padSm);
        row.setClickable(true);
        row.setFocusable(true);
        row.setFocusableInTouchMode(false);

        // Layout params with bottom margin for spacing between rows
        LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        rowParams.bottomMargin = getDimen(R.dimen.margin_xs);
        row.setLayoutParams(rowParams);

        // Selection highlight — use drawable backgrounds for rounded corners
        if (isSelected && isBtFolder) {
            row.setBackgroundResource(R.drawable.bg_tree_node_target);
        } else if (isSelected) {
            row.setBackgroundResource(R.drawable.bg_tree_node_selected);
        } else {
            // Use ripple background for non-selected rows
            row.setBackgroundResource(R.drawable.bg_ripple_tree_item);
        }

        // Click listener
        final String clickId = id;
        final String clickName = name;
        final String clickType = type;
        final int clickDepth = depth;
        row.setOnClickListener(v -> onTreeNodeClick(clickId, clickName, clickType, clickDepth));

        // Icon — use ImageView with vector drawable instead of emoji
        ImageView icon = new ImageView(this);
        int iconSize = dpToPx(20);
        LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(iconSize, iconSize);
        iconParams.setMarginEnd(dpToPx(10));
        icon.setLayoutParams(iconParams);
        if (isFolder) {
            icon.setImageResource(expanded ? R.drawable.ic_folder_open : R.drawable.ic_folder);
        } else {
            icon.setImageResource(R.drawable.ic_file);
        }
        icon.setClickable(false);
        icon.setFocusable(false);
        row.addView(icon);

        // Name
        TextView label = new TextView(this);
        label.setText(name);
        label.setTextSize(TypedValueDimension(R.dimen.text_body));
        label.setTextColor(isBtFolder ? getColor(R.color.tree_folder) : getColor(R.color.text_body));
        label.setMaxLines(1);
        label.setClickable(false);
        label.setFocusable(false);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        label.setLayoutParams(labelParams);
        row.addView(label);

        // If folder, show expand/collapse indicator + target marker
        if (isFolder) {
            // Show "传至此" marker if this is the selected transfer target
            if (isSelected && isBtFolder) {
                TextView targetMark = new TextView(this);
                targetMark.setText(R.string.transfer_here);
                targetMark.setTextSize(TypedValueDimension(R.dimen.text_caption));
                targetMark.setTextColor(getColor(R.color.success));
                targetMark.setPadding(dpToPx(8), 0, 0, 0);
                targetMark.setClickable(false);
                targetMark.setFocusable(false);
                row.addView(targetMark);
            }

            // Expand/collapse indicator
            TextView expand = new TextView(this);
            expand.setText(expanded ? "▾" : "▸");
            expand.setTextSize(TypedValueDimension(R.dimen.text_body));
            expand.setTextColor(getColor(R.color.text_secondary));
            expand.setPadding(dpToPx(8), 0, 0, 0);
            expand.setClickable(false);
            expand.setFocusable(false);
            row.addView(expand);
        }

        lvTreeContainer.addView(row);
    }

    /**
     * Handle click on a tree node.
     */
    private void onTreeNodeClick(String id, String name, String type, int depth) {
        boolean isFolder = "folder".equals(type);

        if (isFolder) {
            boolean wasExpanded = Boolean.TRUE.equals(expandedFolders.get(id));
            expandedFolders.put(id, !wasExpanded);
        }

        selectedNode = new TreeNode(id, name, type);

        if (isFolder && id.startsWith("bt_folder_")) {
            setTargetFolder(id, name);
        }

        renderTree();
        updateSendButtonState();
    }

    // ==================== Target folder management ====================

    private void setTargetFolder(String folderId, String folderName) {
        targetFolderId = folderId;
        targetFolderPath = folderName;
        updateTargetFolderDisplay();
    }

    private void updateTargetFolderDisplay() {
        if (ROOT_BT.equals(targetFolderId)) {
            tvTargetFolder.setText(R.string.upload_to_root);
        } else {
            tvTargetFolder.setText(getString(R.string.upload_to, targetFolderPath));
        }
    }

    // ==================== Button handlers ====================

    private void onConnectClick() {
        btnConnect.setEnabled(false);
        btnConnect.setText(R.string.connecting_btn);
        showConnecting();
        wearableManager.connect();
        mainHandler.postDelayed(() -> {
            if (!destroyed) btnConnect.setEnabled(true);
        }, 5000);
    }

    private void onRefreshTreeClick() {
        if (wearableManager == null || !wearableManager.isConnected()) {
            Toast.makeText(this, R.string.not_connected, Toast.LENGTH_SHORT).show();
            return;
        }
        tvTreeStatus.setText(R.string.tree_syncing);
        treeSync.requestTree();
    }

    private void onSelectFileClick() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        // Accept both TXT and knowledge-point JSON files
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES,
                new String[]{"text/plain", "application/json", "application/octet-stream"});
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
            selectedFileName = getString(R.string.unnamed_txt);
        }
        tvSelectedFile.setText(selectedFileName);
        tvSelectedFile.setTextColor(getColor(R.color.text_body));
        tvSelectedFile.setBackgroundResource(R.drawable.bg_file_selected);

        try {
            selectedFileContent = TxtTransferHandler.readTxtFromUri(this, uri);

            // Detect file type by extension: .json files are knowledge-point files
            // (Snapnotes structure) and keep the ".json" suffix in transfer name so
            // the band app can route them to its JSON reader.
            selectedFileIsJson = selectedFileName != null
                    && selectedFileName.toLowerCase().endsWith(".json");
            if (selectedFileIsJson && !validateKnowledgeJson(selectedFileContent)) {
                // Invalid JSON — reject before transfer instead of failing on the band
                selectedFileContent = null;
                selectedFileIsJson = false;
                tvSelectedFile.setText(getString(R.string.file_read_error) + selectedFileName);
                tvSelectedFile.setTextColor(getColor(R.color.error));
                tvSelectedFile.setBackgroundResource(R.drawable.bg_file_empty);
                tvProgress.setText(R.string.json_invalid);
                updateSendButtonState();
                return;
            }

            tvProgress.setText(getString(R.string.file_loaded, selectedFileContent.length()));
            if (selectedFileIsJson) {
                int subjectCount = countKnowledgeSubjects(selectedFileContent);
                if (subjectCount > 0) {
                    Toast.makeText(this,
                            getString(R.string.json_valid, subjectCount),
                            Toast.LENGTH_SHORT).show();
                }
            }

            // Large file warning
            if (selectedFileContent.length() > LARGE_FILE_THRESHOLD) {
                Toast.makeText(this,
                        getString(R.string.file_too_large, selectedFileContent.length()),
                        Toast.LENGTH_LONG).show();
            }
        } catch (Exception e) {
            Log.w(TAG, "readTxtFromUri failed", e);
            selectedFileContent = null;
            selectedFileIsJson = false;
            tvSelectedFile.setText(getString(R.string.file_read_error) + selectedFileName);
            tvSelectedFile.setTextColor(getColor(R.color.error));
            tvSelectedFile.setBackgroundResource(R.drawable.bg_file_empty);
            tvProgress.setText(getString(R.string.file_read_error) + safeMessage(e));
        }
        updateSendButtonState();
    }

    /**
     * Validate that a picked JSON file matches the knowledge-point structure:
     * an object whose values are arrays of point entries.
     * Returns true when it parses and contains at least one subject array.
     */
    private static boolean validateKnowledgeJson(String content) {
        try {
            JSONObject root = new JSONObject(content);
            JSONArray names = root.names();
            if (names == null || names.length() == 0) return false;
            for (int i = 0; i < names.length(); i++) {
                if (root.optJSONArray(names.optString(i)) != null) return true;
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }

    /** Count subjects (top-level arrays) inside a knowledge-point JSON. */
    private static int countKnowledgeSubjects(String content) {
        try {
            JSONObject root = new JSONObject(content);
            JSONArray names = root.names();
            int n = 0;
            if (names != null) {
                for (int i = 0; i < names.length(); i++) {
                    if (root.optJSONArray(names.optString(i)) != null) n++;
                }
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }

    private void onNewFolderClick() {
        if (wearableManager == null || !wearableManager.isConnected()) {
            Toast.makeText(this, R.string.not_connected, Toast.LENGTH_SHORT).show();
            return;
        }

        String parentId = ROOT_BT;
        String parentName = getString(R.string.default_upload);
        if (selectedNode != null && "folder".equals(selectedNode.type)) {
            if (selectedNode.id.startsWith("bt_folder_")) {
                parentId = selectedNode.id;
                parentName = selectedNode.name;
            }
        }

        final String finalParentId = parentId;
        final String finalParentName = parentName;
        EditText input = new EditText(this);
        input.setHint(R.string.folder_name_hint);
        int padMd = getDimen(R.dimen.padding_md);
        input.setPadding(padMd * 2, padMd, padMd * 2, padMd);

        new AlertDialog.Builder(this)
                .setTitle(R.string.new_folder)
                .setMessage(getString(R.string.create_in, finalParentName))
                .setView(input)
                .setPositiveButton(R.string.create, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        Toast.makeText(this, R.string.enter_folder_name, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    tvTreeStatus.setText(R.string.creating_folder);
                    treeSync.createFolder(name, finalParentId);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void onRenameClick() {
        if (selectedNode == null || !selectedNode.id.startsWith("bt_")) {
            Toast.makeText(this, R.string.rename_select_first, Toast.LENGTH_SHORT).show();
            return;
        }
        EditText input = new EditText(this);
        input.setText(selectedNode.name);
        input.setSelection(selectedNode.name.length());
        int padMd = getDimen(R.dimen.padding_md);
        input.setPadding(padMd * 2, padMd, padMd * 2, padMd);

        new AlertDialog.Builder(this)
                .setTitle(R.string.rename)
                .setView(input)
                .setPositiveButton(R.string.rename, (dialog, which) -> {
                    String newName = input.getText().toString().trim();
                    if (newName.isEmpty()) {
                        Toast.makeText(this, R.string.enter_name, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    tvTreeStatus.setText(R.string.renaming);
                    treeSync.renameNode(selectedNode.id, newName);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void onDeleteNodeClick() {
        if (selectedNode == null) {
            Toast.makeText(this, R.string.select_node_first, Toast.LENGTH_SHORT).show();
            return;
        }

        if (!selectedNode.id.startsWith("bt_")) {
            Toast.makeText(this, R.string.builtin_no_delete, Toast.LENGTH_SHORT).show();
            return;
        }

        String warning = "folder".equals(selectedNode.type)
                ? getString(R.string.folder_delete_warning) : "";
        new AlertDialog.Builder(this)
                .setTitle(R.string.confirm_delete)
                .setMessage(getString(R.string.delete_warning, selectedNode.name, warning))
                .setPositiveButton(R.string.delete, (dialog, which) -> {
                    tvTreeStatus.setText(R.string.deleting);
                    treeSync.deleteNode(selectedNode.id);
                })
                .setNegativeButton(R.string.cancel, null)
                .show();
    }

    private void onSendClick() {
        if (wearableManager == null || !wearableManager.isConnected()) {
            Toast.makeText(this, R.string.connect_first, Toast.LENGTH_SHORT).show();
            return;
        }
        if (selectedFileContent == null || selectedFileContent.isEmpty()) {
            Toast.makeText(this, R.string.select_txt_first, Toast.LENGTH_SHORT).show();
            return;
        }

        transferring = true;
        btnSend.setEnabled(false);
        btnSelectFile.setEnabled(false);
        btnConnect.setEnabled(false);
        btnNewFolder.setEnabled(false);
        btnRename.setEnabled(false);
        btnDeleteNode.setEnabled(false);
        btnRefreshTree.setEnabled(false);
        progressBar.setVisibility(View.VISIBLE);
        progressBar.setMax(100);
        progressBar.setProgress(0);
        tvProgress.setText(getString(R.string.preparing_transfer, targetFolderPath));

        final String fileName = selectedFileIsJson
                ? selectedFileName   // JSON：保留 .json 后缀，手环据此识别知识点文件
                : TxtTransferHandler.getFileNameWithoutExtension(selectedFileName);
        final int charCount = selectedFileContent.length();
        txtTransfer.sendTxtFile(fileName, selectedFileContent, targetFolderId,
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
                            restoreButtons();
                            updateSendButtonState();
                            Toast.makeText(MainActivity.this,
                                    R.string.transfer_success, Toast.LENGTH_LONG).show();
                            // Add to history
                            addHistoryEntry(new TransferRecord(
                                    fileName, charCount, true, null));
                            // Refresh tree to show the new file
                            tvTreeStatus.setText(R.string.refreshing_tree);
                            treeSync.requestTree();
                        });
                    }

                    @Override
                    public void onError(String error) {
                        mainHandler.post(() -> {
                            if (destroyed) return;
                            transferring = false;
                            progressBar.setVisibility(View.GONE);
                            tvProgress.setText(getString(R.string.error_prefix) + error);
                            restoreButtons();
                            updateSendButtonState();
                            Toast.makeText(MainActivity.this,
                                    getString(R.string.transfer_failed) + error,
                                    Toast.LENGTH_LONG).show();
                            // Add to history
                            addHistoryEntry(new TransferRecord(
                                    fileName, charCount, false, error));
                        });
                    }
                });
    }

    private void restoreButtons() {
        btnSelectFile.setEnabled(true);
        btnConnect.setEnabled(true);
        btnNewFolder.setEnabled(true);
        btnRename.setEnabled(true);
        btnDeleteNode.setEnabled(true);
        btnRefreshTree.setEnabled(true);
    }

    private void updateSendButtonState() {
        boolean ready = wearableManager != null
                && wearableManager.isConnected()
                && selectedFileContent != null
                && !selectedFileContent.isEmpty()
                && !transferring;
        btnSend.setEnabled(ready);
    }

    // ==================== Transfer history ====================

    /**
     * Add a transfer record to the history list (newest first).
     * Keeps at most MAX_HISTORY entries.
     */
    private void addHistoryEntry(TransferRecord record) {
        transferHistory.add(0, record);
        if (transferHistory.size() > MAX_HISTORY) {
            transferHistory.remove(transferHistory.size() - 1);
        }
        renderHistory();
    }

    /**
     * Render the transfer history list (up to MAX_HISTORY_DISPLAY entries).
     * Success entries show a green check icon + file name + char count + relative time.
     * Failure entries show a red X + file name + error message.
     * Empty state shows a hint text.
     */
    private void renderHistory() {
        if (lvHistoryContainer == null) return;
        lvHistoryContainer.removeAllViews();

        if (transferHistory.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.history_empty);
            empty.setTextColor(getColor(R.color.text_hint));
            empty.setTextSize(TypedValueDimension(R.dimen.text_caption));
            empty.setGravity(Gravity.CENTER);
            int padSm = getDimen(R.dimen.margin_sm);
            empty.setPadding(0, padSm, 0, padSm);
            lvHistoryContainer.addView(empty);
            return;
        }

        int count = Math.min(transferHistory.size(), MAX_HISTORY_DISPLAY);
        int padSm = getDimen(R.dimen.margin_sm);
        int padXs = getDimen(R.dimen.margin_xs);

        for (int i = 0; i < count; i++) {
            TransferRecord record = transferHistory.get(i);

            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, padXs, 0, padXs);

            LinearLayout.LayoutParams rowParams = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i < count - 1) {
                rowParams.bottomMargin = padXs;
            }
            row.setLayoutParams(rowParams);

            if (record.success) {
                // Green check icon
                ImageView checkIcon = new ImageView(this);
                int iconSize = dpToPx(16);
                LinearLayout.LayoutParams iconParams = new LinearLayout.LayoutParams(iconSize, iconSize);
                iconParams.setMarginEnd(padSm);
                checkIcon.setLayoutParams(iconParams);
                checkIcon.setImageResource(R.drawable.ic_check);
                checkIcon.setClickable(false);
                checkIcon.setFocusable(false);
                row.addView(checkIcon);

                // Text: file name + char count + relative time
                TextView text = new TextView(this);
                text.setText(getString(R.string.history_success, record.fileName, record.charCount)
                        + " — " + getRelativeTime(record.timestamp));
                text.setTextColor(getColor(R.color.text_body));
                text.setTextSize(TypedValueDimension(R.dimen.text_caption));
                text.setMaxLines(2);
                text.setEllipsize(android.text.TextUtils.TruncateAt.END);
                text.setClickable(false);
                text.setFocusable(false);
                LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                text.setLayoutParams(textParams);
                row.addView(text);
            } else {
                // Red X
                TextView xMark = new TextView(this);
                xMark.setText("✗");
                xMark.setTextColor(getColor(R.color.error));
                xMark.setTextSize(TypedValueDimension(R.dimen.text_body));
                xMark.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
                xMark.setPadding(0, 0, padSm, 0);
                xMark.setClickable(false);
                xMark.setFocusable(false);
                row.addView(xMark);

                // Text: file name — error
                TextView text = new TextView(this);
                String err = record.errorMsg != null ? record.errorMsg : "";
                text.setText(getString(R.string.history_failed, record.fileName, err));
                text.setTextColor(getColor(R.color.error));
                text.setTextSize(TypedValueDimension(R.dimen.text_caption));
                text.setMaxLines(2);
                text.setEllipsize(android.text.TextUtils.TruncateAt.END);
                text.setClickable(false);
                text.setFocusable(false);
                LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(
                        0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
                text.setLayoutParams(textParams);
                row.addView(text);
            }

            lvHistoryContainer.addView(row);
        }
    }

    /**
     * Compute a human-readable relative time string.
     */
    private String getRelativeTime(long timestamp) {
        long diff = System.currentTimeMillis() - timestamp;
        long seconds = diff / 1000;
        if (seconds < 60) {
            return getString(R.string.time_just_now);
        }
        long minutes = seconds / 60;
        if (minutes < 60) {
            return getString(R.string.time_minutes_ago, minutes);
        }
        long hours = minutes / 60;
        if (hours < 24) {
            return getString(R.string.time_hours_ago, hours);
        }
        long days = hours / 24;
        return getString(R.string.time_days_ago, days);
    }

    // ==================== Auto-reconnect ====================

    /**
     * Attempt to auto-reconnect once after a 3-second delay.
     * Only fires if not already attempted and not currently transferring.
     */
    private void attemptAutoReconnect() {
        if (!autoReconnectAttempted && !transferring) {
            autoReconnectAttempted = true;
            autoReconnectHandler.postDelayed(() -> {
                if (!destroyed && wearableManager != null
                        && !wearableManager.isConnected()) {
                    showConnecting();
                    wearableManager.connect();
                }
            }, AUTO_RECONNECT_DELAY);
        }
    }

    // ==================== Utilities ====================

    private void showConnecting() {
        tvConnectionStatus.setText(R.string.connecting);
        setStatusBackground(STATUS_CONNECTING);
    }

    /**
     * Switch the connection status card background and indicator dot
     * to the appropriate drawables for each state.
     */
    private void setStatusBackground(int status) {
        int cardBg;
        int dotBg;
        switch (status) {
            case STATUS_CONNECTED:
                cardBg = R.drawable.bg_status_connected;
                dotBg = R.drawable.bg_status_dot_connected;
                break;
            case STATUS_CONNECTING:
                cardBg = R.drawable.bg_status_connecting;
                dotBg = R.drawable.bg_status_dot_connecting;
                break;
            case STATUS_DISCONNECTED:
            case STATUS_ERROR:
            default:
                cardBg = R.drawable.bg_status_error;
                dotBg = R.drawable.bg_status_dot_error;
                break;
        }
        cardConnection.setBackgroundResource(cardBg);
        statusIndicator.setBackgroundResource(dotBg);
    }

    private static String safeMessage(Throwable t) {
        if (t == null) return "";
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

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

    /** Convert dp to pixels. */
    private int dpToPx(int dp) {
        return (int) (dp * getResources().getDisplayMetrics().density + 0.5f);
    }

    /** Get a dimension resource as pixels. */
    private int getDimen(int resId) {
        return getResources().getDimensionPixelSize(resId);
    }

    /** Get a dimension resource as a textSize value (in SP-equivalent complex unit). */
    private float TypedValueDimension(int resId) {
        return getResources().getDimension(resId);
    }

    // ==================== Inner classes ====================

    /** Simple tree node data holder. */
    private static class TreeNode {
        final String id;
        final String name;
        final String type;

        TreeNode(String id, String name, String type) {
            this.id = id;
            this.name = name;
            this.type = type;
        }
    }

    /** Transfer history record. */
    private static class TransferRecord {
        String fileName;
        int charCount;
        long timestamp;
        boolean success;
        String errorMsg;

        TransferRecord(String name, int count, boolean ok, String err) {
            fileName = name;
            charCount = count;
            timestamp = System.currentTimeMillis();
            success = ok;
            errorMsg = err;
        }
    }

    // ==================== Cleanup ====================

    @Override
    protected void onDestroy() {
        destroyed = true;
        if (txtTransfer != null) {
            txtTransfer.cancelTransfer();
        }
        if (wearableManager != null) {
            wearableManager.destroy();
        }
        mainHandler.removeCallbacksAndMessages(null);
        autoReconnectHandler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
