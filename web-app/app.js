/**
 * 考点传输 Web 版 — 主应用逻辑
 *
 * 功能与安卓端一致：
 *  - 连接小米手环（通过 WASM + Web Bluetooth）
 *  - 启动手环上的考点阅读器应用
 *  - 同步文件树（获取/新建文件夹/删除/重命名）
 *  - 传输 TXT 考点文件（分片传输协议）
 *  - 传输记录
 *
 * 通信协议：JSON 消息 + tag 路由
 *   发送: thirdpartyapp_send_message(addr, pkg, JSON.stringify({tag, ...payload}))
 *   接收: register_event_sink → thirdpartyapp_message 事件
 */

import wasmClient from './wasm-client.js';

// ==================== 常量 ====================

const WATCH_PACKAGE = 'com.silenthong.kdreader';
const CHUNK_SIZE = 8000;        // 每片最大字符数（与手环端一致）
const RESPONSE_TIMEOUT = 15000; // 单步响应超时 (ms)
const TREE_TIMEOUT = 10000;      // 树操作超时 (ms)

// 安全提取错误信息：WASM 可能抛出非 Error 对象
function errMsg(e) {
  if (!e) return '未知错误';
  if (e.message) return e.message;
  if (typeof e === 'string') return e;
  try { return JSON.stringify(e); } catch (_) { return String(e); }
}

// localStorage 键
const LS_DEVICES = 'kd-web-devices';
const LS_HISTORY = 'kd-web-history';

// ==================== 状态 ====================

const state = {
  connectionStatus: 'disconnected',  // disconnected | connecting | connected | error
  currentDevice: null,               // {id, name, addr, authkey, sarVersion, connectType}
  connected: false,
  fileTree: [],
  selectedNode: null,                // 当前选中的树节点
  targetFolder: 'bt_root',          // 文件传输目标文件夹
  targetFolderName: '主页',
  selectedFile: null,                // {name, content, size}
  transferring: false,
  transferProgress: 0,
  logs: [],
  history: [],
};

// 标签路由回调表
const tagHandlers = {};

// 等待响应的 Promise resolve/reject
const pendingResponses = new Map(); // key → {resolve, reject, timer}

// 防止重复注册消息处理器
let messageHandlerRegistered = false;

// ==================== DOM 工具 ====================

const $ = (id) => document.getElementById(id);
const el = (tag, cls, text) => {
  const e = document.createElement(tag);
  if (cls) e.className = cls;
  if (text !== undefined) e.textContent = text;
  return e;
};

// ==================== 日志 ====================

function addLog(message, type = 'info') {
  const entry = { message, type, timestamp: new Date().toLocaleTimeString() };
  state.logs.unshift(entry);
  if (state.logs.length > 200) state.logs.pop();
  renderLogs();
}

function renderLogs() {
  const container = $('logContainer');
  if (!container) return;
  container.innerHTML = '';
  state.logs.forEach(entry => {
    const line = el('div', `log-line log-${entry.type}`, `[${entry.timestamp}] ${entry.message}`);
    container.appendChild(line);
  });
}

// ==================== Toast ====================

function showToast(message, type = 'info') {
  const toast = el('div', `toast toast-${type}`, message);
  $('toastContainer').appendChild(toast);
  setTimeout(() => {
    toast.classList.add('toast-fade');
    setTimeout(() => toast.remove(), 300);
  }, 3000);
}

// ==================== 设备管理 ====================

function loadDevices() {
  try {
    const saved = localStorage.getItem(LS_DEVICES);
    return saved ? JSON.parse(saved) : [];
  } catch { return []; }
}

function saveDevices(devices) {
  localStorage.setItem(LS_DEVICES, JSON.stringify(devices));
}

function loadHistory() {
  try {
    const saved = localStorage.getItem(LS_HISTORY);
    return saved ? JSON.parse(saved) : [];
  } catch { return []; }
}

function saveHistory(history) {
  localStorage.setItem(LS_HISTORY, JSON.stringify(history));
}

function addHistory(name, charCount, success, error) {
  state.history.unshift({
    name, charCount, success, error,
    timestamp: new Date().toLocaleString()
  });
  if (state.history.length > 50) state.history.pop();
  saveHistory(state.history);
  renderHistory();
}

function renderHistory() {
  const container = $('historyList');
  if (!container) return;
  container.innerHTML = '';

  if (state.history.length === 0) {
    container.appendChild(el('div', 'empty-hint', '暂无传输记录'));
    return;
  }

  state.history.forEach(item => {
    const row = el('div', 'history-item');
    const icon = item.success ? '✓' : '✗';
    const iconCls = item.success ? 'history-icon-success' : 'history-icon-fail';
    row.appendChild(el('span', `history-icon ${iconCls}`, icon));
    row.appendChild(el('span', 'history-name', item.name));
    row.appendChild(el('span', 'history-detail',
      item.success ? `${item.charCount} 字符` : item.error));
    row.appendChild(el('span', 'history-time', item.timestamp));
    container.appendChild(row);
  });
}

// ==================== 设备列表渲染 ====================

function renderDeviceList() {
  const container = $('deviceList');
  if (!container) return;
  container.innerHTML = '';

  const devices = loadDevices();
  if (devices.length === 0) {
    container.appendChild(el('div', 'empty-hint', '尚未添加设备'));
    return;
  }

  devices.forEach(device => {
    const card = el('div', 'device-card');
    if (state.currentDevice && state.currentDevice.id === device.id) {
      card.classList.add('device-card-active');
    }

    const info = el('div', 'device-info');
    info.appendChild(el('div', 'device-name', device.name));

    const meta = el('div', 'device-meta');
    meta.appendChild(el('span', '', device.addr || '未设置地址'));
    meta.appendChild(el('span', 'device-sep', '·'));
    meta.appendChild(el('span', '', `SAR v${device.sarVersion || 2}`));
    meta.appendChild(el('span', 'device-sep', '·'));
    meta.appendChild(el('span', '', device.connectType || 'SPP'));
    info.appendChild(meta);
    card.appendChild(info);

    const actions = el('div', 'device-actions');
    const connectBtn = el('button', 'btn-icon btn-connect',
      state.currentDevice?.id === device.id && state.connected ? '已连接' : '连接');
    connectBtn.onclick = () => connectDevice(device);
    actions.appendChild(connectBtn);

    const deleteBtn = el('button', 'btn-icon btn-danger', '删除');
    deleteBtn.onclick = () => {
      if (confirm(`确定删除设备「${device.name}」？`)) {
        const updated = devices.filter(d => d.id !== device.id);
        saveDevices(updated);
        renderDeviceList();
        addLog(`设备 ${device.name} 已删除`, 'success');
      }
    };
    actions.appendChild(deleteBtn);
    card.appendChild(actions);

    container.appendChild(card);
  });
}

// ==================== 连接管理 ====================

async function connectDevice(device) {
  state.connectionStatus = 'connecting';
  state.currentDevice = device;
  updateConnectionUI();
  addLog(`正在连接设备 ${device.name}...`);
  addDebug(`开始连接设备: ${JSON.stringify(device)}`);

  try {
    // 初始化 WASM
    if (!wasmClient.isInitialized) {
      addDebug('正在初始化 WASM 模块...');
      const ok = await wasmClient.init();
      if (!ok) throw new Error('WASM 模块初始化失败');
      addDebug('WASM 模块初始化成功');
    }

    // 注册消息接收回调（只注册一次）
    if (!messageHandlerRegistered) {
      wasmClient.on('thirdpartyapp_message', handleIncomingMessage);
      wasmClient.on('device_disconnected', () => {
        state.connected = false;
        state.connectionStatus = 'disconnected';
        state.fileTree = [];
        state.selectedNode = null;
        updateConnectionUI();
        renderTree();
        addLog('设备已断开连接', 'warning');
        addDebug('设备断开连接事件');
      });
      wasmClient.on('device_connected', () => {
        addDebug('设备连接事件');
      });
      messageHandlerRegistered = true;
      addDebug('消息处理器已注册');
    }

    // 连接设备
    addDebug(`调用 miwear_connect: addr=${device.addr}, sarVersion=${device.sarVersion || 2}, connectType=${device.connectType || 'SPP'}`);
    await wasmClient.call('miwear_connect', {
      name: device.name,
      addr: device.addr,
      authkey: device.authkey,
      sar_version: device.sarVersion || 2,
      connect_type: device.connectType || 'SPP'
    });
    addDebug('miwear_connect 调用成功');

    addLog(`设备 ${device.name} 连接成功`, 'success');

    // 启动手环应用（尝试多种方式）
    addLog('正在启动手环应用...');
    let appLaunched = false;

    // 方式 1: 不指定 page（与 BandBurg 一致）
    try {
      addDebug('尝试启动: 不指定 page');
      await wasmClient.call('thirdpartyapp_launch', {
        addr: device.addr,
        package_name: WATCH_PACKAGE,
        page: ''
      });
      addLog('手环应用已启动', 'success');
      addDebug('thirdpartyapp_launch 成功 (空 page)');
      appLaunched = true;
    } catch (e) {
      addDebug(`方式1失败: ${errMsg(e)}`);
    }

    // 方式 2: 指定 /pages/index
    if (!appLaunched) {
      try {
        addDebug('尝试启动: /pages/index');
        await wasmClient.call('thirdpartyapp_launch', {
          addr: device.addr,
          package_name: WATCH_PACKAGE,
          page: '/pages/index'
        });
        addLog('手环应用已启动', 'success');
        addDebug('thirdpartyapp_launch 成功 (/pages/index)');
        appLaunched = true;
      } catch (e) {
        addDebug(`方式2失败: ${errMsg(e)}`);
      }
    }

    // 方式 3: 指定 pages/index（无前导斜杠）
    if (!appLaunched) {
      try {
        addDebug('尝试启动: pages/index');
        await wasmClient.call('thirdpartyapp_launch', {
          addr: device.addr,
          package_name: WATCH_PACKAGE,
          page: 'pages/index'
        });
        addLog('手环应用已启动', 'success');
        addDebug('thirdpartyapp_launch 成功 (pages/index)');
        appLaunched = true;
      } catch (e) {
        addDebug(`方式3失败: ${errMsg(e)}`);
      }
    }

    if (!appLaunched) {
      addLog('手环应用可能已在运行，继续尝试同步...', 'warning');
      addDebug('所有启动方式均失败，应用可能已在运行');
    }

    state.connected = true;
    state.connectionStatus = 'connected';
    updateConnectionUI();

    // 自动同步文件树（等待手环应用完全初始化）
    addDebug('2000ms 后自动同步文件树');
    setTimeout(() => requestTree(), 2000);

  } catch (error) {
    state.connectionStatus = 'error';
    state.connected = false;
    updateConnectionUI();
    const msg = errMsg(error);
    addLog(`连接失败: ${msg}`, 'error');
    addDebug(`连接失败: ${msg}\n${error.stack || ''}`);
    showToast(`连接失败: ${msg}`, 'error');
  }
}

async function disconnectDevice() {
  if (!state.currentDevice) return;
  addLog('正在断开连接...');

  // 清理所有 pending responses
  pendingResponses.forEach((pending, key) => {
    clearTimeout(pending.timer);
  });
  pendingResponses.clear();

  try {
    if (wasmClient.isInitialized) {
      await wasmClient.call('miwear_disconnect', { addr: state.currentDevice.addr });
    }
  } catch (e) {
    addLog(`断开时出错: ${errMsg(e)}`, 'warning');
  }

  state.connected = false;
  state.connectionStatus = 'disconnected';
  state.fileTree = [];
  state.selectedNode = null;
  updateConnectionUI();
  renderTree();
  addLog('设备已断开', 'success');
}

function updateConnectionUI() {
  const statusEl = $('connectionStatus');
  const dotEl = $('statusDot');
  const connectBtn = $('btnConnect');
  const treeCard = $('treeCard');
  const transferCard = $('transferCard');

  if (!statusEl) return;

  const labels = {
    disconnected: '未连接',
    connecting: '连接中...',
    connected: state.currentDevice ? `已连接: ${state.currentDevice.name}` : '已连接',
    error: '连接失败'
  };
  const dotClasses = {
    disconnected: 'status-dot-disconnected',
    connecting: 'status-dot-connecting',
    connected: 'status-dot-connected',
    error: 'status-dot-error'
  };

  statusEl.textContent = labels[state.connectionStatus] || '未知';
  dotEl.className = `status-dot ${dotClasses[state.connectionStatus] || ''}`;
  connectBtn.textContent = state.connected ? '断开连接' : '连接设备';
  connectBtn.disabled = state.connectionStatus === 'connecting';

  // 连接后才启用操作区域
  if (treeCard) treeCard.classList.toggle('card-disabled', !state.connected);
  if (transferCard) transferCard.classList.toggle('card-disabled', !state.connected);
}

// ==================== 消息路由 ====================

function handleIncomingMessage(eventData) {
  addDebug(`[收到消息] eventData=${JSON.stringify(eventData).substring(0, 300)}`);

  // 处理多种可能的 eventData 格式：
  // 1. { data: { tag, ...payload } }  — 包装格式（来自 _dispatchCapturedMessage）
  // 2. { tag, ...payload }            — 原始格式（来自 register_event_sink 直接发射）
  // 3. { data: "json_string" }        — 字符串 data
  // 4. "json_string"                  — 纯字符串
  let data = null;

  if (eventData && typeof eventData === 'object') {
    if (eventData.data) {
      // 格式 1 或 3
      if (typeof eventData.data === 'string') {
        try { data = JSON.parse(eventData.data); } catch (e) {
          addLog(`收到非 JSON 消息: ${eventData.data.substring(0, 100)}`, 'warning');
          return;
        }
      } else if (typeof eventData.data === 'object') {
        data = eventData.data;
      }
    } else if (eventData.tag) {
      // 格式 2：eventData 本身就是消息
      data = eventData;
    }
  } else if (typeof eventData === 'string') {
    // 格式 4
    try { data = JSON.parse(eventData); } catch (e) {
      addLog(`收到非 JSON 字符串: ${eventData.substring(0, 100)}`, 'warning');
      return;
    }
  }

  if (!data || typeof data !== 'object') {
    addLog(`无法解析消息: ${JSON.stringify(eventData).substring(0, 100)}`, 'warning');
    addDebug(`[警告] 无法解析消息: ${JSON.stringify(eventData).substring(0, 200)}`);
    return;
  }

  const { tag, ...payload } = data;
  if (!tag) {
    addDebug(`[警告] 消息没有 tag 字段: ${JSON.stringify(data).substring(0, 200)}`);
    return;
  }

  addLog(`<<< ${tag}: ${JSON.stringify(payload).substring(0, 120)}`, 'receive');
  addDebug(`[消息路由] tag=${tag}, payload=${JSON.stringify(payload).substring(0, 200)}`);

  const handler = tagHandlers[tag];
  if (handler) {
    handler(payload);
  } else {
    addLog(`未处理的 tag: ${tag}`, 'warning');
    addDebug(`[警告] 未处理的 tag: ${tag}`);
  }
}

function registerTagHandler(tag, handler) {
  tagHandlers[tag] = handler;
}

function unregisterTagHandler(tag) {
  delete tagHandlers[tag];
}

// ==================== 消息发送 ====================

async function sendMessage(tag, payload) {
  if (!state.connected || !state.currentDevice) {
    throw new Error('设备未连接');
  }
  const message = JSON.stringify({ tag, ...payload });
  addLog(`>>> ${tag}: ${JSON.stringify(payload).substring(0, 120)}`, 'send');
  addDebug(`[发送消息] tag=${tag}, data=${message.substring(0, 200)}`);
  await wasmClient.call('thirdpartyapp_send_message', {
    addr: state.currentDevice.addr,
    package_name: WATCH_PACKAGE,
    data: message
  });
  addDebug(`[发送完成] tag=${tag}`);
}

// ==================== 响应等待机制 ====================

function waitForResponse(tag, key, timeout = RESPONSE_TIMEOUT) {
  return new Promise((resolve, reject) => {
    // Clear any existing pending response with the same key to prevent
    // the old timer from deleting/rejecting the new one
    const existing = pendingResponses.get(key);
    if (existing) {
      clearTimeout(existing.timer);
    }

    const timer = setTimeout(() => {
      // Only delete and reject if this is still the current pending response
      const current = pendingResponses.get(key);
      if (current && current.resolve === resolve) {
        pendingResponses.delete(key);
        reject(new Error(`等待 ${tag} 响应超时`));
      }
    }, timeout);

    pendingResponses.set(key, { resolve, reject, timer });
  });
}

function cancelPendingResponse(key) {
  const pending = pendingResponses.get(key);
  if (pending) {
    clearTimeout(pending.timer);
    pendingResponses.delete(key);
  }
}

function resolveResponse(key, data) {
  const pending = pendingResponses.get(key);
  if (pending) {
    clearTimeout(pending.timer);
    pendingResponses.delete(key);
    pending.resolve(data);
  }
}

// ==================== 文件树同步 ====================

function registerTreeHandler() {
  registerTagHandler('tree', (payload) => {
    const response = payload.response;

    if (response === 'treeData') {
      resolveResponse('tree', payload.tree || []);
    } else if (response === 'folderCreated') {
      resolveResponse('createFolder', payload);
    } else if (response === 'nodeDeleted') {
      resolveResponse('deleteNode', payload);
    } else if (response === 'nodeRenamed') {
      resolveResponse('renameNode', payload);
    }
  });
}

async function requestTree() {
  if (!state.connected) return;

  addLog('正在同步文件树...');
  $('treeStatus').textContent = '同步中...';

  // 重试机制：最多尝试 3 次
  const maxRetries = 3;
  for (let attempt = 1; attempt <= maxRetries; attempt++) {
    try {
      const treePromise = waitForResponse('tree', 'tree', TREE_TIMEOUT);
      await sendMessage('tree', { action: 'getTree' });
      const tree = await treePromise;

      state.fileTree = tree || [];
      renderTree();
      const count = countNodes(tree);
      $('treeStatus').textContent = `已同步 (${count} 个项目)`;
      addLog(`文件树同步成功: ${count} 个项目`, 'success');
      return; // 成功，退出重试循环
    } catch (error) {
      // 清理可能残留的 pending response
      cancelPendingResponse('tree');
      const msg = errMsg(error);
      if (attempt < maxRetries) {
        addLog(`文件树同步第 ${attempt} 次失败: ${msg}，重试中...`, 'warning');
        // 等待一段时间后重试
        await new Promise(r => setTimeout(r, 1000 * attempt));
      } else {
        $('treeStatus').textContent = '同步失败';
        addLog(`文件树同步失败: ${msg}`, 'error');
        addDebug(`[错误] 文件树同步失败 (${attempt} 次): ${msg}`);
      }
    }
  }
}

function countNodes(nodes) {
  if (!nodes) return 0;
  let count = 0;
  for (const node of nodes) {
    count++;
    if (node.type === 'folder' && node.children) {
      count += countNodes(node.children);
    }
  }
  return count;
}

async function createFolder(name, parentId) {
  if (!state.connected) return;
  addLog(`正在创建文件夹: ${name}`);

  try {
    const promise = waitForResponse('tree', 'createFolder', TREE_TIMEOUT);
    await sendMessage('tree', { action: 'createFolder', name, parentId: parentId || 'bt_root' });
    const result = await promise;

    if (result.success) {
      addLog(`文件夹创建成功: ${result.folderId}`, 'success');
      showToast('文件夹创建成功', 'success');
      await requestTree();
    } else {
      throw new Error(result.error || '创建失败');
    }
  } catch (error) {
    cancelPendingResponse('createFolder');
    const msg = errMsg(error);
    addLog(`创建文件夹失败: ${msg}`, 'error');
    showToast(`创建失败: ${msg}`, 'error');
  }
}

async function deleteNode(nodeId) {
  if (!state.connected) return;
  addLog(`正在删除节点: ${nodeId}`);

  try {
    const promise = waitForResponse('tree', 'deleteNode', TREE_TIMEOUT);
    await sendMessage('tree', { action: 'deleteNode', nodeId });
    const result = await promise;

    if (result.success) {
      addLog('删除成功', 'success');
      showToast('删除成功', 'success');
      state.selectedNode = null;
      await requestTree();
    } else {
      throw new Error(result.error || '删除失败');
    }
  } catch (error) {
    cancelPendingResponse('deleteNode');
    const msg = errMsg(error);
    addLog(`删除失败: ${msg}`, 'error');
    showToast(`删除失败: ${msg}`, 'error');
  }
}

async function renameNode(nodeId, newName) {
  if (!state.connected) return;
  addLog(`正在重命名: ${nodeId} → ${newName}`);

  try {
    const promise = waitForResponse('tree', 'renameNode', TREE_TIMEOUT);
    await sendMessage('tree', { action: 'renameNode', nodeId, newName });
    const result = await promise;

    if (result.success) {
      addLog('重命名成功', 'success');
      showToast('重命名成功', 'success');
      await requestTree();
    } else {
      throw new Error(result.error || '重命名失败');
    }
  } catch (error) {
    cancelPendingResponse('renameNode');
    const msg = errMsg(error);
    addLog(`重命名失败: ${msg}`, 'error');
    showToast(`重命名失败: ${msg}`, 'error');
  }
}

// ==================== 文件树渲染 ====================

function renderTree() {
  const container = $('treeContainer');
  if (!container) return;
  container.innerHTML = '';

  if (!state.fileTree || state.fileTree.length === 0) {
    container.appendChild(el('div', 'empty-hint', '手环上暂无文件，可直接上传'));
    return;
  }

  state.fileTree.forEach(node => {
    container.appendChild(renderTreeNode(node, 0));
  });
}

function renderTreeNode(node, depth) {
  const row = el('div', 'tree-node');
  row.style.paddingLeft = `${depth * 20 + 12}px`;

  const isFolder = node.type === 'folder';
  const isSelected = state.selectedNode?.id === node.id;
  const isTarget = state.targetFolder === node.id;

  if (isSelected) row.classList.add('tree-node-selected');
  if (isTarget) row.classList.add('tree-node-target');

  // 图标
  const icon = el('span', isFolder ? 'tree-icon-folder' : 'tree-icon-file',
    isFolder ? '📁' : '📄');
  row.appendChild(icon);

  // 名称
  const nameEl = el('span', 'tree-name', node.name);
  row.appendChild(nameEl);

  // 目标标记
  if (isTarget) {
    row.appendChild(el('span', 'tree-target-badge', '传至此'));
  }

  // 点击选择
  row.onclick = (e) => {
    e.stopPropagation();
    selectNode(node);
  };

  // 双击文件夹展开/折叠
  if (isFolder) {
    row.ondblclick = (e) => {
      e.stopPropagation();
      toggleFolder(row, node, depth);
    };
  }

  container.appendChild(row);

  // 渲染子节点（文件夹默认展开）
  if (isFolder && node.children && node.children.length > 0) {
    const childContainer = el('div', 'tree-children');
    node.children.forEach(child => {
      childContainer.appendChild(renderTreeNode(child, depth + 1));
    });
    container.appendChild(childContainer);
  }

  return row;
}

function toggleFolder(row, node, depth) {
  const childContainer = row.nextElementSibling;
  if (childContainer && childContainer.classList.contains('tree-children')) {
    childContainer.style.display = childContainer.style.display === 'none' ? '' : 'none';
  }
}

function selectNode(node) {
  state.selectedNode = node;
  renderTree();
  updateSelectionUI();
}

function updateSelectionUI() {
  const node = state.selectedNode;
  const deleteBtn = $('btnDeleteNode');
  const renameBtn = $('btnRename');
  const setTargetBtn = $('btnSetTarget');

  if (node) {
    deleteBtn.disabled = false;
    renameBtn.disabled = false;
    if (node.type === 'folder') {
      setTargetBtn.disabled = false;
      setTargetBtn.textContent = `设为传输目标`;
    } else {
      setTargetBtn.disabled = true;
      setTargetBtn.textContent = '仅文件夹可设为目标';
    }
  } else {
    deleteBtn.disabled = true;
    renameBtn.disabled = true;
    setTargetBtn.disabled = true;
    setTargetBtn.textContent = '设为传输目标';
  }
}

// ==================== 文件传输协议 ====================

function registerFileHandler() {
  registerTagHandler('file', (payload) => {
    const type = payload.type;

    switch (type) {
      case 'ready':
        resolveResponse('file_ready', payload);
        break;
      case 'next_chunk':
        resolveResponse('file_next_chunk', payload);
        break;
      case 'chapter_chunk_complete':
        resolveResponse('file_chunk_complete', payload);
        break;
      case 'chapter_saved':
        resolveResponse('file_chapter_saved', payload);
        break;
      case 'transfer_finished':
        resolveResponse('file_finished', payload);
        break;
      case 'error':
        resolveResponse('file_error', payload);
        break;
      case 'cancel':
        resolveResponse('file_cancel', payload);
        break;
      default:
        addLog(`未知文件消息类型: ${type}`, 'warning');
    }
  });
}

async function transferFile(fileName, content, targetFolder, onProgress) {
  if (!state.connected) throw new Error('设备未连接');
  if (state.transferring) throw new Error('正在传输中');

  state.transferring = true;
  state.transferProgress = 0;

  const wordCount = content.length;
  const chunks = splitContent(content, CHUNK_SIZE);
  const totalChunks = chunks.length;

  try {
    onProgress?.(0, totalChunks, `开始传输: ${fileName}`);

    // 步骤 1: startTransfer → 等待 ready
    const readyPromise = waitForResponse('file', 'file_ready', RESPONSE_TIMEOUT);
    await sendMessage('file', {
      stat: 'startTransfer',
      filename: fileName,
      total: 1,
      wordCount,
      startFrom: 0,
      folder: targetFolder
    });
    await readyPromise;
    addLog('手环已就绪，开始传输数据...', 'success');

    // 步骤 2: 逐片传输
    for (let i = 0; i < totalChunks; i++) {
      const isLast = i === totalChunks - 1;

      const innerData = JSON.stringify({
        index: 0,
        name: fileName,
        content: chunks[i],
        wordCount,
        chunkNum: i,
        totalChunks
      });

      const waitKey = isLast ? 'file_chunk_complete' : 'file_next_chunk';
      const chunkPromise = waitForResponse('file', waitKey, RESPONSE_TIMEOUT);

      await sendMessage('file', {
        stat: 'd',
        count: 0,
        data: innerData
      });

      await chunkPromise;

      const percent = Math.round((i + 1) * 100 / totalChunks);
      state.transferProgress = percent;
      onProgress?.(i + 1, totalChunks, `传输中 ${percent}% (${i + 1}/${totalChunks})`);
    }

    // 步骤 3: chapter_complete → 等待 chapter_saved
    onProgress?.(totalChunks, totalChunks, '等待手环保存...');
    const savedPromise = waitForResponse('file', 'file_chapter_saved', RESPONSE_TIMEOUT);
    await sendMessage('file', { stat: 'chapter_complete', count: 0 });
    await savedPromise;

    // 步骤 4: transfer_complete → 等待 transfer_finished
    const finishedPromise = waitForResponse('file', 'file_finished', RESPONSE_TIMEOUT);
    await sendMessage('file', { stat: 'transfer_complete' });
    await finishedPromise;

    onProgress?.(totalChunks, totalChunks, '传输完成!');
    addLog(`文件传输完成: ${fileName} (${wordCount} 字符)`, 'success');
    addHistory(fileName, wordCount, true, null);

    // 传输完成后刷新文件树
    setTimeout(() => requestTree(), 500);

  } catch (error) {
    // 清理所有可能的 pending response
    cancelPendingResponse('file_ready');
    cancelPendingResponse('file_next_chunk');
    cancelPendingResponse('file_chunk_complete');
    cancelPendingResponse('file_chapter_saved');
    cancelPendingResponse('file_finished');

    // 发送取消
    try {
      await sendMessage('file', { stat: 'cancel' });
    } catch (_) { /* 忽略取消发送的失败 */ }

    const msg = errMsg(error);
    addLog(`传输失败: ${msg}`, 'error');
    addHistory(fileName, wordCount, false, msg);
    throw error;
  } finally {
    state.transferring = false;
    state.transferProgress = 0;
  }
}

function splitContent(content, chunkSize) {
  if (!content || content.length === 0) return [''];
  const chunks = [];
  for (let i = 0; i < content.length; i += chunkSize) {
    chunks.push(content.substring(i, Math.min(i + chunkSize, content.length)));
  }
  return chunks.length > 0 ? chunks : [''];
}

// ==================== 文件选择 ====================

async function onSelectFile() {
  const input = $('fileInput');
  input.click();
}

async function onFileSelected(event) {
  const file = event.target.files[0];
  if (!file) return;

  // 检查文件类型
  if (!file.name.toLowerCase().endsWith('.txt')) {
    showToast('请选择 TXT 文件', 'warning');
    return;
  }

  addLog(`正在读取文件: ${file.name} (${formatSize(file.size)})`);

  try {
    const text = await file.text();
    const fileName = file.name.replace(/\.txt$/i, '');

    state.selectedFile = {
      name: fileName,
      content: text,
      size: text.length
    };

    $('selectedFileName').textContent = `${fileName}.txt`;
    $('selectedFileDetail').textContent = `${text.length} 字符`;

    if (text.length > 50000) {
      addLog(`文件较大 (${text.length} 字符)，传输可能需要较长时间`, 'warning');
    }

    updateSendButton();
    addLog(`文件加载完成: ${text.length} 字符`, 'success');
  } catch (error) {
    const msg = errMsg(error);
    addLog(`读取文件失败: ${msg}`, 'error');
    showToast('文件读取失败', 'error');
  }

  // 清空 input 以便重复选择同一文件
  event.target.value = '';
}

function formatSize(bytes) {
  if (bytes < 1024) return `${bytes} B`;
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`;
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`;
}

function updateSendButton() {
  const btn = $('btnSend');
  const canSend = state.connected && state.selectedFile && !state.transferring;
  btn.disabled = !canSend;
}

// ==================== 传输执行 ====================

async function onSendClick() {
  if (!state.selectedFile || !state.connected || state.transferring) return;

  const { name, content } = state.selectedFile;
  const progressBar = $('progressBar');
  const progressText = $('progressText');
  const btnSend = $('btnSend');

  btnSend.disabled = true;
  progressBar.style.display = 'block';
  progressBar.value = 0;
  progressText.textContent = '准备传输...';

  try {
    await transferFile(name, content, state.targetFolder, (sent, total, msg) => {
      progressBar.value = (sent / total) * 100;
      progressText.textContent = msg;
    });

    progressBar.value = 100;
    progressText.textContent = '传输完成!';
    showToast('传输成功!', 'success');

  } catch (error) {
    const msg = errMsg(error);
    progressText.textContent = `传输失败: ${msg}`;
    showToast(`传输失败: ${msg}`, 'error');
  } finally {
    btnSend.disabled = false;
    updateSendButton();
    setTimeout(() => {
      progressBar.style.display = 'none';
    }, 3000);
  }
}

// ==================== 设备表单 ====================

function onShowDeviceForm() {
  $('deviceFormOverlay').classList.add('overlay-visible');
  // 尝试扫描设备
  scanBluetoothDevice();
}

function onHideDeviceForm() {
  $('deviceFormOverlay').classList.remove('overlay-visible');
}

async function scanBluetoothDevice() {
  const scanStatus = $('scanStatus');
  scanStatus.textContent = '正在扫描蓝牙设备...';
  scanStatus.className = 'scan-status scan-status-active';

  if (!navigator.bluetooth) {
    scanStatus.textContent = '当前浏览器不支持 Web Bluetooth API，请使用 Chrome/Edge';
    scanStatus.className = 'scan-status scan-status-error';
    return;
  }

  try {
    const device = await navigator.bluetooth.requestDevice({
      acceptAllDevices: true,
      optionalServices: ['battery_service', 'device_information', '0000fe95-0000-1000-8000-00805f9b34fb']
    });

    if (device) {
      // 尝试解码设备地址
      let addr = device.id;
      try {
        if (/^[A-Za-z0-9+/=]+$/.test(device.id) && device.id.length % 4 === 0) {
          const bin = atob(device.id);
          const bytes = new Uint8Array(bin.length);
          for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
          if (bytes.length === 6) {
            addr = Array.from(bytes).map(b => b.toString(16).padStart(2, '0')).join(':').toUpperCase();
          }
        }
      } catch (_) { /* 使用原始 ID */ }

      $('formName').value = device.name || '';
      $('formAddr').value = addr;
      scanStatus.textContent = `找到设备: ${device.name || '未知'}`;
      scanStatus.className = 'scan-status scan-status-success';
      addLog(`扫描到设备: ${device.name || '未知'} (${addr})`, 'success');
    }
  } catch (error) {
    if (error.name === 'NotFoundError') {
      scanStatus.textContent = '未选择设备，可手动填写信息';
      scanStatus.className = 'scan-status';
    } else {
      scanStatus.textContent = `扫描失败: ${errMsg(error)}`;
      scanStatus.className = 'scan-status scan-status-error';
    }
  }
}

function onSaveDevice() {
  const name = $('formName').value.trim();
  const addr = $('formAddr').value.trim();
  const authkey = $('formAuthkey').value.trim();
  const sarVersion = parseInt($('formSarVersion').value) || 2;
  const connectType = $('formConnectType').value;

  if (!name) { showToast('请输入设备名称', 'warning'); return; }
  if (!authkey) { showToast('请输入认证密钥 (Auth Key)', 'warning'); return; }

  const device = {
    id: Date.now().toString(),
    name, addr, authkey, sarVersion, connectType
  };

  const devices = loadDevices();
  devices.push(device);
  saveDevices(devices);

  renderDeviceList();
  onHideDeviceForm();

  // 清空表单
  $('formName').value = '';
  $('formAddr').value = '';
  $('formAuthkey').value = '';

  addLog(`设备 ${name} 已保存`, 'success');
  showToast('设备已保存', 'success');
}

// ==================== 事件绑定 ====================

function bindEvents() {
  // 设备管理
  $('btnAddDevice').onclick = onShowDeviceForm;
  $('btnScanDevice').onclick = scanBluetoothDevice;
  $('btnSaveDevice').onclick = onSaveDevice;
  $('btnCancelDevice').onclick = onHideDeviceForm;
  $('deviceFormOverlay').onclick = (e) => {
    if (e.target === $('deviceFormOverlay')) onHideDeviceForm();
  };

  // 连接
  $('btnConnect').onclick = () => {
    if (state.connected) {
      disconnectDevice();
    } else {
      const devices = loadDevices();
      if (devices.length === 0) {
        showToast('请先添加设备', 'warning');
        onShowDeviceForm();
      } else {
        connectDevice(devices[0]);
      }
    }
  };

  // 文件树操作
  $('btnRefreshTree').onclick = requestTree;
  $('btnNewFolder').onclick = showNewFolderDialog;
  $('btnDeleteNode').onclick = () => {
    if (state.selectedNode) {
      const node = state.selectedNode;
      const warning = node.type === 'folder'
        ? `\n文件夹下的所有内容也将被删除。`
        : '';
      if (confirm(`确定要删除「${node.name}」吗？${warning}`)) {
        deleteNode(node.id);
      }
    }
  };
  $('btnRename').onclick = showRenameDialog;
  $('btnSetTarget').onclick = () => {
    if (state.selectedNode && state.selectedNode.type === 'folder') {
      state.targetFolder = state.selectedNode.id;
      state.targetFolderName = state.selectedNode.name;
      $('targetFolderDisplay').textContent = `上传到: ${state.selectedNode.name}`;
      renderTree();
      showToast(`已设为传输目标: ${state.selectedNode.name}`, 'success');
    }
  };

  // 文件选择
  $('btnSelectFile').onclick = onSelectFile;
  $('fileInput').onchange = onFileSelected;
  $('btnSend').onclick = onSendClick;

  // 日志折叠
  $('btnToggleLog').onclick = () => {
    const panel = $('logPanel');
    panel.classList.toggle('collapsed');
    $('btnToggleLog').textContent = panel.classList.contains('collapsed') ? '展开日志' : '收起日志';
  };

  // 清空日志
  $('btnClearLog').onclick = () => {
    state.logs = [];
    renderLogs();
  };
}

function showNewFolderDialog() {
  const parentId = state.selectedNode && state.selectedNode.type === 'folder'
    ? state.selectedNode.id
    : 'bt_root';
  const parentName = state.selectedNode && state.selectedNode.type === 'folder'
    ? state.selectedNode.name
    : '主页';

  const name = prompt(`在「${parentName}」下创建文件夹：\n请输入文件夹名称`);
  if (name && name.trim()) {
    createFolder(name.trim(), parentId);
  }
}

function showRenameDialog() {
  if (!state.selectedNode) return;
  const newName = prompt('请输入新名称：', state.selectedNode.name);
  if (newName && newName.trim() && newName !== state.selectedNode.name) {
    renameNode(state.selectedNode.id, newName.trim());
  }
}

// ==================== 调试面板 ====================

const debugEntries = [];

function addDebug(message) {
  const entry = `[${new Date().toLocaleTimeString()}] ${message}`;
  debugEntries.unshift(entry);
  if (debugEntries.length > 200) debugEntries.pop();
  renderDebug();
}

function renderDebug() {
  const container = $('debugContainer');
  if (!container) return;
  container.innerHTML = '';
  debugEntries.forEach(entry => {
    const line = el('div', '', entry);
    container.appendChild(line);
  });
}

function getDebugSummary() {
  const lines = [];
  lines.push('=== 调试信息摘要 ===');
  lines.push(`时间: ${new Date().toISOString()}`);
  lines.push(`浏览器: ${navigator.userAgent}`);
  lines.push(`Web Serial 支持: ${'serial' in navigator ? '是' : '否'}`);
  lines.push(`Web Bluetooth 支持: ${'bluetooth' in navigator ? '是' : '否'}`);
  lines.push(`连接状态: ${state.connectionStatus}`);
  lines.push(`当前设备: ${state.currentDevice ? JSON.stringify(state.currentDevice) : '无'}`);
  lines.push(`WASM 初始化: ${wasmClient.isInitialized ? '是' : '否'}`);
  lines.push(`文件树: ${JSON.stringify(state.fileTree)}`);
  lines.push('');

  // WASM 原始事件日志
  const rawEvents = wasmClient.getRawEventLog ? wasmClient.getRawEventLog() : [];
  lines.push(`=== WASM 原始事件 (${rawEvents.length} 条) ===`);
  rawEvents.forEach((entry, i) => {
    lines.push(`[${i}] ${JSON.stringify(entry)}`);
  });
  lines.push('');

  // 调试日志
  lines.push(`=== 调试日志 (${debugEntries.length} 条) ===`);
  debugEntries.forEach(entry => lines.push(entry));

  return lines.join('\n');
}

function bindDebugEvents() {
  $('btnToggleDebug').onclick = () => {
    const panel = $('debugPanel');
    const isHidden = panel.style.display === 'none';
    panel.style.display = isHidden ? 'block' : 'none';
    $('btnToggleDebug').textContent = isHidden ? '收起调试' : '展开调试';
  };

  $('btnCopyDebug').onclick = () => {
    const summary = getDebugSummary();
    navigator.clipboard.writeText(summary).then(() => {
      showToast('调试信息已复制到剪贴板', 'success');
    }).catch(() => {
      // 降级：创建 textarea
      const textarea = document.createElement('textarea');
      textarea.value = summary;
      document.body.appendChild(textarea);
      textarea.select();
      document.execCommand('copy');
      document.body.removeChild(textarea);
      showToast('调试信息已复制', 'success');
    });
  };
}

// ==================== 初始化 ====================

async function init() {
  addLog('欢迎使用考点传输 Web 版');
  addLog('参考项目: BandBurg + AstroBox-NG');

  // 加载历史记录
  state.history = loadHistory();
  renderHistory();

  // 渲染设备列表
  renderDeviceList();

  // 注册消息处理器
  registerTreeHandler();
  registerFileHandler();

  // 注册通配符事件处理器来捕获所有 WASM 事件（用于调试）
  wasmClient.on('*', (eventData) => {
    const eventName = eventData.event || 'unknown';
    addDebug(`[WASM事件] ${eventName}: ${JSON.stringify(eventData).substring(0, 300)}`);
  });

  // 绑定事件
  bindEvents();
  bindDebugEvents();

  // 更新 UI 状态
  updateConnectionUI();
  updateSelectionUI();
  updateSendButton();

  // 检查浏览器兼容性
  if (!navigator.bluetooth) {
    addLog('当前浏览器不支持 Web Bluetooth API，建议使用 Chrome 89+ 或 Edge 89+', 'warning');
  }

  // 检查 Web Serial API 支持（WASM 模块可能需要）
  if (!('serial' in navigator)) {
    addLog('当前浏览器不支持 Web Serial API，WASM 模块可能无法正常工作', 'warning');
    addDebug('警告: Web Serial API 不可用，WASM 模块需要此 API 来与设备通信');
  }

  addDebug('应用初始化完成');
  addLog('初始化完成，请添加设备并连接');
}

// 启动应用
init();
