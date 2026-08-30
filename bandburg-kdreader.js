/**
 * 考点传输 — BandBurg 脚本版
 *
 * 功能：
 *  1. 启动手环上的闪念小抄（考点阅读器）应用
 *  2. 同步文件树（获取/新建文件夹/删除/重命名）
 *  3. 传输考点文件（分片传输协议）：
 *     - .txt  → 普通文本阅读器（传输时去掉后缀，与旧行为一致）
 *     - .json → 知识点阅读器（Snapnotes 结构，保留 .json 后缀，
 *       手环端 interconnfile.js 据此后缀路由；发送前本地校验结构）
 *  4. 批量导入：选择本地文件夹内多个 TXT/JSON 文件，一次传输到手环
 *     （可指定手环端新文件夹名，自动创建后导入）。
 *     入口三选一：多选 / 直接选择文件夹（部分手机适用）/
 *     单选逐个加入队列（仅支持单选的手机适用）
 *  5. 编辑手环端文件顺序：选中当前文件夹内条目上移/下移（含嵌套文件夹），
 *     手环列表按元数据数组顺序展示，即调即变
 *
 * 通信协议：JSON 消息 + tag 路由
 *   发送: thirdpartyapp_send_message(addr, pkg, JSON.stringify({tag, ...payload}))
 *   接收: register_event_sink → thirdpartyapp_message 事件 → event.data
 */

// ==================== 常量 ====================

// 手环端包名：V26.8.0 起与闪念小抄仓库/安卓端对齐为 com.whyy.snapnotes（旧包名 com.silenthong.kdreader 已废弃）
const WATCH_PACKAGE = 'com.whyy.snapnotes';
// Snapnotes 协议分片上限：10KB（UTF-8 字节，与 JsonFilePusher.CHUNK_SIZE 一致）
const SN_CHUNK_BYTES = 10 * 1024;
const RESPONSE_TIMEOUT = 15000;
const TREE_TIMEOUT = 10000;

// ==================== 状态 ====================

const state = {
  connected: false,
  addr: null,          // ensureDeviceConnection 校准后的设备地址
  fileTree: [],
  selectedNode: null,
  targetFolder: 'bt_root',
  targetFolderName: '主页',
  transferring: false,
  pendingResponses: new Map(),
  selectedFile: null,  // file:change 事件返回的文件对象
  batchFiles: [],      // 批量导入选中的文件列表（单个文件时会归一成数组）
  orderNodeId: null,   // 排序编辑当前选中的手环条目 ID
  launchMethod: null,  // 探测出的可用启动签名 {id, route}，缓存复用
  appListDumped: false, // 是否已打印过应用列表原始字段（仅一次，避免刷屏）
};

// tag 路由回调表
const tagHandlers = {};

// ==================== 工具函数 ====================

function errMsg(e) {
  if (!e) return '未知错误';
  if (e.message) return e.message;
  if (typeof e === 'string') return e;
  try { return JSON.stringify(e); } catch (_) { return String(e); }
}

function getDeviceAddr() {
  // 优先使用 ensureDeviceConnection 缓存的地址（避免每次解析结果不一致）
  if (state.addr) return state.addr;
  if (sandbox.currentDevice && sandbox.currentDevice.addr) {
    return sandbox.currentDevice.addr;
  }
  if (sandbox.devices && sandbox.devices.length > 0 && sandbox.devices[0].addr) {
    return sandbox.devices[0].addr;
  }
  return null;
}

function sleep(ms) {
  return new Promise(function (r) { setTimeout(r, ms); });
}

/**
 * 获取手环第三方应用列表。
 * 不同版本 BandBurg 对 thirdpartyapp_get_list 的参数要求不一，
 * 文档标注无参但实测部分版本需传设备地址；依次尝试
 * (addr) / (无参) / (addr, pkg)，返回首个成功结果，全部失败返回 null。
 */
async function getAppList(verbose) {
  var addr = getDeviceAddr();
  var variants = [
    ['(addr)', function () { return sandbox.wasm.thirdpartyapp_get_list(addr); }],
    ['()', function () { return sandbox.wasm.thirdpartyapp_get_list(); }],
    ['(addr, pkg)', function () { return sandbox.wasm.thirdpartyapp_get_list(addr, WATCH_PACKAGE); }]
  ];
  for (var i = 0; i < variants.length; i++) {
    try {
      var r = await variants[i][1]();
      if (verbose) sandbox.log('[诊断] thirdpartyapp_get_list' + variants[i][0] + ' ✓');
      // 首次拉取时打印原始字段，揭示 BandBurg 返回的真实字段名（package/id/appId 等）
      if (verbose && r && r.length !== undefined && r.length > 0 && !state.appListDumped) {
        state.appListDumped = true;
        try {
          sandbox.log('[诊断] 应用列表首条原始字段: ' + JSON.stringify(r[0]));
        } catch (e) {}
      }
      return r;
    } catch (e) {
      if (verbose) sandbox.log('[诊断] get_list' + variants[i][0] + ' 失败: ' + errMsg(e));
    }
  }
  return null;
}

// 在应用列表中查找目标包名对应的条目（兼容多种字段名）
function findWatchAppEntry(apps) {
  if (!apps || apps.length === undefined) return null;
  for (var i = 0; i < apps.length; i++) {
    var a = apps[i] || {};
    var pkg = a.packageName || a.package_name || a.package || a.pkg || '';
    if (pkg === WATCH_PACKAGE) return a;
  }
  return null;
}

/**
 * 确保底层互连链路可用。
 * 关键：重复运行脚本时（第一次正常、第二次失败的场景），WASM 底层连接
 * 可能已失效或设备地址未刷新，必须显式调用 miwear_connect() 重连，
 * 并从 miwear_get_connected_devices() 重新校准设备地址。
 */
async function ensureDeviceConnection() {
  // 1) 显式连接（参数签名未知，依次尝试带地址/不带地址；
  //    实测部分版本无参会抛 reading 'length' 内部错误）
  var connected = false;
  if (typeof sandbox.wasm.miwear_connect === 'function') {
    var cVariants = [
      ['(addr)', function () { return sandbox.wasm.miwear_connect(getDeviceAddr()); }],
      ['()', function () { return sandbox.wasm.miwear_connect(); }]
    ];
    for (var ci = 0; ci < cVariants.length && !connected; ci++) {
      try {
        await cVariants[ci][1]();
        connected = true;
        sandbox.log('[连接] miwear_connect' + cVariants[ci][0] + ' 成功');
      } catch (e) {
        var em = errMsg(e);
        // reading 'length' 类内部错误说明该签名不对，静默换下一个
        if (em.indexOf("reading '") < 0) {
          sandbox.log('[连接] miwear_connect' + cVariants[ci][0] + ': ' + em);
        }
      }
    }
    if (!connected) sandbox.log('[连接] miwear_connect 各签名均不适用（可能本就无需调用，继续后续流程）');
  }
  // 2) 从已连设备列表校准地址
  try {
    if (typeof sandbox.wasm.miwear_get_connected_devices === 'function') {
      var devs = await sandbox.wasm.miwear_get_connected_devices();
      if (Array.isArray(devs) && devs.length > 0) {
        var d0 = devs[0];
        var listAddr = (d0 && typeof d0 === 'object') ? (d0.addr || d0.address) : d0;
        if (listAddr) {
          if (state.addr && state.addr !== listAddr) {
            sandbox.log('[连接] 设备地址变更: ' + state.addr + ' → ' + listAddr);
          }
          state.addr = listAddr;
          state.connected = true;
        }
        sandbox.log('[连接] 已连设备: ' + JSON.stringify(devs).substring(0, 150));
      } else {
        sandbox.log('[连接] 警告: 已连设备列表为空，请在 BandBurg 中重新连接设备');
      }
    }
  } catch (e) {
    sandbox.log('[连接] 获取已连设备失败: ' + errMsg(e));
  }
  return state.addr || getDeviceAddr();
}

function getDeviceName() {
  if (sandbox.currentDevice && sandbox.currentDevice.name) {
    return sandbox.currentDevice.name;
  }
  if (sandbox.devices && sandbox.devices.length > 0 && sandbox.devices[0].name) {
    return sandbox.devices[0].name;
  }
  return '未知设备';
}

// ==================== 文件读取 ====================

/**
 * 检测字符串是否为 base64 编码
 * base64 特征：只包含 A-Z a-z 0-9 + / = ，且长度为 4 的倍数
 * 如果解码后包含合法 UTF-8 中文字符，则判定为 base64
 */
function isBase64(str) {
  if (!str || typeof str !== 'string') return false;
  var trimmed = str.replace(/\s/g, '');
  if (trimmed.length < 4 || trimmed.length % 4 !== 0) return false;
  // 只允许 base64 字符
  if (!/^[A-Za-z0-9+/]+={0,2}$/.test(trimmed)) return false;
  // 尝试解码，看是否包含可打印中文字符
  try {
    var decoded = atob(trimmed);
    // 检查是否包含 UTF-8 编码的中文字符（常见范围）
    // 中文 UTF-8 编码：3 字节，范围 E4-E9, B8-BF, 80-BF 等
    for (var i = 0; i < decoded.length; i++) {
      var c = decoded.charCodeAt(i);
      if (c >= 0xE4 && c <= 0xE9) {
        // 看起来像 UTF-8 中文首字节
        return true;
      }
    }
    // 如果全是 ASCII 可打印字符，也可能是 base64 编码的纯英文
    // 但如果原始内容就是 base64 字符串，这种判断会误判
    // 所以只对包含中文的字串做 base64 解码
    return false;
  } catch (e) {
    return false;
  }
}

/**
 * 将 base64 字符串解码为 UTF-8 文本
 */
function decodeBase64ToText(b64) {
  try {
    var binary = atob(b64);
    // 将二进制字符串转换为 UTF-8 文本
    var bytes = new Uint8Array(binary.length);
    for (var i = 0; i < binary.length; i++) {
      bytes[i] = binary.charCodeAt(i);
    }
    if (typeof TextDecoder !== 'undefined') {
      return new TextDecoder('utf-8').decode(bytes);
    }
    // 回退：手动解码 UTF-8
    var result = '';
    var j = 0;
    while (j < bytes.length) {
      var b1 = bytes[j++];
      if (b1 < 0x80) {
        result += String.fromCharCode(b1);
      } else if (b1 < 0xC0) {
        // 无效的 UTF-8 首字节，跳过
        result += '?';
      } else if (b1 < 0xE0) {
        var b2 = bytes[j++];
        result += String.fromCharCode(((b1 & 0x1F) << 6) | (b2 & 0x3F));
      } else if (b1 < 0xF0) {
        var b2 = bytes[j++];
        var b3 = bytes[j++];
        result += String.fromCharCode(((b1 & 0x0F) << 12) | ((b2 & 0x3F) << 6) | (b3 & 0x3F));
      } else {
        // 4 字节 UTF-8（emoji 等）
        var b2 = bytes[j++];
        var b3 = bytes[j++];
        var b4 = bytes[j++];
        var code = ((b1 & 0x07) << 18) | ((b2 & 0x3F) << 12) | ((b3 & 0x3F) << 6) | (b4 & 0x3F);
        // 转为 UTF-16 代理对
        code -= 0x10000;
        result += String.fromCharCode(0xD800 + (code >> 10), 0xDC00 + (code & 0x3FF));
      }
    }
    return result;
  } catch (e) {
    sandbox.log('[警告] base64 解码失败: ' + errMsg(e));
    return b64; // 返回原始字符串
  }
}

/**
 * 读取文件内容为文本
 * BandBurg 的 file:change 事件返回的 file 对象格式不确定，
 * 因此依次尝试多种读取方式：
 *   1. file.text()           — 标准 File API（浏览器原生）
 *   2. file.content / file.data — 直接包含内容的属性（可能为 base64）
 *   3. sandbox.fs.read(path) — BandBurg 文件系统 API
 *   4. FileReader API（如果可用）
 *   5. arrayBuffer + TextDecoder
 *
 * 重要：BandBurg 沙箱可能返回 base64 编码的内容，需要检测并解码
 */
async function readFileAsText(file) {
  if (!file) throw new Error('文件对象为空');

  // 调试：打印 file 对象的所有属性和类型
  try {
    var keys = Object.keys(file);
    sandbox.log('[调试] file 对象属性: ' + keys.join(', '));
    for (var i = 0; i < keys.length; i++) {
      var k = keys[i];
      var v = file[k];
      var typeStr = typeof v;
      var preview = '';
      if (typeStr === 'string') {
        preview = v.substring(0, 50);
      } else if (v === null) {
        preview = 'null';
      } else if (v === undefined) {
        preview = 'undefined';
      } else {
        preview = String(v).substring(0, 50);
      }
      sandbox.log('[调试]   .' + k + ' (' + typeStr + '): ' + preview);
    }
  } catch (e) {}

  // 方式 1: 标准 File.text()
  if (typeof file.text === 'function') {
    var text1 = await file.text();
    sandbox.log('[调试] file.text() 返回: ' + (text1 || '').substring(0, 80));
    return text1;
  }

  // 方式 2: file.content / file.data / file.text（字符串属性）
  // 注意：这些可能包含 base64 编码的内容
  var contentProps = ['content', 'data', 'text', 'base64', 'fileContent'];
  for (var pi = 0; pi < contentProps.length; pi++) {
    var prop = contentProps[pi];
    if (typeof file[prop] === 'string' && file[prop].length > 0) {
      var rawContent = file[prop];
      sandbox.log('[调试] file.' + prop + ' 原始值: ' + rawContent.substring(0, 80));
      // 检测是否为 base64 编码
      if (isBase64(rawContent)) {
        sandbox.log('[调试] 检测到 base64 编码内容，正在解码...');
        var decoded = decodeBase64ToText(rawContent);
        sandbox.log('[调试] 解码结果: ' + decoded.substring(0, 80));
        return decoded;
      }
      return rawContent;
    }
  }

  // 方式 3: 通过 sandbox.fs 读取
  var filePath = file.path || file.filePath || file.uri;
  if (filePath && sandbox.fs) {
    if (typeof sandbox.fs.read === 'function') {
      var fsContent = await sandbox.fs.read(filePath);
      if (typeof fsContent === 'string' && isBase64(fsContent)) {
        return decodeBase64ToText(fsContent);
      }
      return fsContent;
    }
    if (typeof sandbox.fs.readFile === 'function') {
      var fsContent2 = await sandbox.fs.readFile(filePath, 'utf-8');
      if (typeof fsContent2 === 'string' && isBase64(fsContent2)) {
        return decodeBase64ToText(fsContent2);
      }
      return fsContent2;
    }
    if (typeof sandbox.fs.readFileSync === 'function') {
      var fsContent3 = sandbox.fs.readFileSync(filePath, 'utf-8');
      if (typeof fsContent3 === 'string' && isBase64(fsContent3)) {
        return decodeBase64ToText(fsContent3);
      }
      return fsContent3;
    }
  }

  // 方式 4: FileReader
  if (typeof FileReader !== 'undefined' && file instanceof Blob) {
    return await new Promise(function (resolve, reject) {
      var reader = new FileReader();
      reader.onload = function () { resolve(reader.result); };
      reader.onerror = function () { reject(new Error('FileReader 读取失败')); };
      reader.readAsText(file);
    });
  }

  // 方式 5: arrayBuffer + TextDecoder
  if (typeof file.arrayBuffer === 'function') {
    var buffer = await file.arrayBuffer();
    if (typeof TextDecoder !== 'undefined') {
      return new TextDecoder('utf-8').decode(buffer);
    }
  }

  throw new Error('无法读取文件内容（不支持的方法）。file 类型: ' + typeof file + ', keys: ' +
    (file ? Object.keys(file).join(', ') : 'null'));
}

// ==================== 消息系统 ====================

function registerTagHandler(tag, handler) {
  tagHandlers[tag] = handler;
}

function unregisterTagHandler(tag) {
  delete tagHandlers[tag];
}

function handleIncomingMessage(data) {
  if (!data || typeof data !== 'object') {
    sandbox.log('[警告] 无法解析消息: ' + JSON.stringify(data).substring(0, 100));
    return;
  }

  // event.data 可能直接是消息对象，也可能需要进一步解析
  let msg = data;
  if (data.data) {
    if (typeof data.data === 'string') {
      try { msg = JSON.parse(data.data); } catch (e) {
        sandbox.log('[警告] 非 JSON 消息: ' + data.data.substring(0, 100));
        return;
      }
    } else if (typeof data.data === 'object') {
      msg = data.data;
    }
  }

  if (!msg || !msg.tag) {
    sandbox.log('[调试] 消息无 tag: ' + JSON.stringify(data).substring(0, 150));
    return;
  }

  const tag = msg.tag;
  const payload = {};
  for (const k in msg) {
    if (k !== 'tag') payload[k] = msg[k];
  }

  sandbox.log('<<< ' + tag + ': ' + JSON.stringify(payload).substring(0, 120));

  const handler = tagHandlers[tag];
  if (handler) {
    try {
      handler(payload);
    } catch (e) {
      sandbox.log('[错误] 消息处理错误 (' + tag + '): ' + errMsg(e));
    }
  } else {
    sandbox.log('[警告] 未处理的 tag: ' + tag);
  }
}

async function sendMessage(tag, payload) {
  const addr = getDeviceAddr();
  if (!addr) throw new Error('未连接设备');

  const message = JSON.stringify(Object.assign({ tag: tag }, payload));
  sandbox.log('>>> ' + tag + ': ' + JSON.stringify(payload).substring(0, 120));

  try {
    await sandbox.wasm.thirdpartyapp_send_message(addr, WATCH_PACKAGE, message);
  } catch (e) {
    var msg = errMsg(e);
    // 如果是 AppInfo not found，说明手表应用已崩溃，尝试重新启动
    if (msg.indexOf('AppInfo not found') >= 0 || msg.indexOf('not found') >= 0) {
      sandbox.log('[警告] 手环应用未找到，尝试重新启动...');
      var launched = await launchWatchApp();
      if (launched) {
        sandbox.log('手环应用已重新启动，等待 2 秒后重试发送...');
        await new Promise(function (r) { setTimeout(r, 2000); });
        await sandbox.wasm.thirdpartyapp_send_message(addr, WATCH_PACKAGE, message);
        return;
      }
    }
    throw e;
  }
}

// 单次启动：自动探测可用签名——从 get_list 取回应用条目，
// 依次尝试 (id/包名, 路由) 与两参形式，命中即缓存复用。
async function launchWatchAppOnce() {
  var addr = getDeviceAddr();
  if (!addr) return false;

  // 复用上次探测出的可用参数，跳过重新探测
  if (state.launchMethod) {
    try {
      await sandbox.wasm.thirdpartyapp_launch(addr, state.launchMethod.id, state.launchMethod.route);
      sandbox.log('手环应用已启动（复用 id=' + state.launchMethod.id + (state.launchMethod.route ? ' route=' + state.launchMethod.route : '') + '）');
      return true;
    } catch (e) {
      state.launchMethod = null;
    }
  }

  // 从应用列表里拿目标应用条目，收集可用的身份字段
  var idCandidates = [WATCH_PACKAGE];
  var entry = null;
  var apps = null;
  try {
    apps = await getAppList(false);
  } catch (e) {}
  entry = findWatchAppEntry(apps);
  if (entry) {
    var eid = entry.id || entry.appId || entry.app_id;
    var epkg = entry.packageName || entry.package_name || entry.package;
    if (eid && idCandidates.indexOf(eid) < 0) idCandidates.push(eid);
    if (epkg && idCandidates.indexOf(epkg) < 0) idCandidates.push(epkg);
  } else if (apps && apps.length !== undefined && apps.length > 0) {
    // 列表里没有目标包名：打印原始条目，便于核对字段名/是否安装
    try {
      sandbox.log('[诊断] 应用列表未见 com.whyy.snapnotes，前 ' + Math.min(2, apps.length) + ' 条原始字段：');
      for (var di = 0; di < Math.min(2, apps.length); di++) {
        sandbox.log('   ' + JSON.stringify(apps[di]));
      }
    } catch (e) {}
  }

  var routes = ['', '/pages/import', '/pages/index', 'pages/import', 'pages/index'];
  var lastErr = '';
  for (var k = 0; k < idCandidates.length; k++) {
    for (var j = 0; j < routes.length; j++) {
      try {
        await sandbox.wasm.thirdpartyapp_launch(addr, idCandidates[k], routes[j]);
        sandbox.log('手环应用已启动（id=' + idCandidates[k] + (routes[j] ? ' → ' + routes[j] : '') + '）');
        state.launchMethod = { id: idCandidates[k], route: routes[j] };
        return true;
      } catch (e) {
        lastErr = errMsg(e);
      }
    }
  }
  // 两参形式（部分版本不接受 route）
  for (var k2 = 0; k2 < idCandidates.length; k2++) {
    try {
      await sandbox.wasm.thirdpartyapp_launch(addr, idCandidates[k2]);
      sandbox.log('手环应用已启动（两参: ' + idCandidates[k2] + '）');
      state.launchMethod = { id: idCandidates[k2], route: '' };
      return true;
    } catch (e2) {
      lastErr = errMsg(e2);
    }
  }
  sandbox.log('手环应用启动失败（最后错误: ' + lastErr + '）');
  if (entry) {
    sandbox.log('   应用已在列表中但启动失败：多为互连服务状态残留，请重启手环后重试');
  } else {
    sandbox.log('   请先在手环上手动打开一次《闪念小抄》（触发互连注册），再重新运行脚本；若已安装并打开过仍不行，请重启手环');
  }
  return false;
}

// 启动手环应用：每轮先重连底层链路，最多 3 轮（解决“第一次能用第二次不行”）
async function launchWatchApp() {
  for (var round = 1; round <= 3; round++) {
    await ensureDeviceConnection();
    var ok = await launchWatchAppOnce();
    if (ok) return true;
    if (round < 3) {
      sandbox.log('第 ' + round + '/3 轮启动失败，2 秒后自动重试...');
      await sleep(2000);
    }
  }
  await diagnoseAppNotFound();
  return false;
}

/**
 * 手环上报 AppInfo not found 时的诊断：
 * 1. 列出手环上所有可互连的第三方应用，确认闪念小抄是否在列
 * 2. 给出安装指引
 */
async function diagnoseAppNotFound() {
  sandbox.log('━━━ 诊断：手环上找不到 ' + WATCH_PACKAGE + ' ━━━');
  var apps = null;
  try {
    apps = await getAppList(true);
  } catch (e) {}
  if (apps && apps.length !== undefined) {
    sandbox.log('[诊断] 手环已装第三方应用共 ' + apps.length + ' 个：');
    var entry = findWatchAppEntry(apps);
    for (var i = 0; i < apps.length; i++) {
      var pkg = apps[i].packageName || apps[i].package_name || apps[i].package || apps[i].pkg || '';
      var name = apps[i].appName || apps[i].name || '';
      sandbox.log('  · ' + (name || '(无名)') + ' (' + pkg + ')');
    }
    if (entry) {
      try {
        sandbox.log('[诊断] 已找到条目原始字段: ' + JSON.stringify(entry));
      } catch (e) {}
      sandbox.log('✅ 应用已在列表但启动失败：多为互连注册/状态残留，请重启手环后重试；或在 BandBurg 断开并重新连接设备再跑');
    } else {
      sandbox.log('❌ 列表中没有 com.whyy.snapnotes——可能原因：');
      sandbox.log('   ① 安装的 RPK 不是最新版，或从未打开过应用（未触发互连注册）');
      sandbox.log('   ② 手环未开开发者/互连调试模式');
      sandbox.log('   ③ 安装的包未声明 system.interconnect 特性');
    }
  } else {
    sandbox.log('[诊断] 无法获取应用列表——多为底层互连状态残留：请在 BandBurg 断开并重新连接设备（或重启手环），再重新运行本脚本');
  }
  sandbox.log('安装方法：卸载旧包后，通过开发者调试推送/Vela 工具安装 release/com.whyy.snapnotes.release.V26.8.21.BAND.rpk，安装后在手环上手动打开一次《闪念小抄》再运行本脚本');
}

// 检查手环已装应用（GUI 按钮）
async function checkInstalledApps() {
  sandbox.log('正在获取手环已装第三方应用列表...');
  await ensureDeviceConnection();
  var apps = await getAppList(true);
  if (!apps || apps.length === undefined) {
    sandbox.log('返回格式异常: ' + JSON.stringify(apps).substring(0, 200));
    return;
  }
  sandbox.log('共 ' + apps.length + ' 个应用：');
  var entry = findWatchAppEntry(apps);
  for (var i = 0; i < apps.length; i++) {
    var pkg = apps[i].packageName || apps[i].package_name || apps[i].package || apps[i].pkg || '';
    var name = apps[i].appName || apps[i].name || '';
    var mark = pkg === WATCH_PACKAGE ? ' ← 闪念小抄' : '';
    sandbox.log('  · ' + (name || '(无名)') + ' (' + pkg + ')' + mark);
  }
  if (entry) {
    sandbox.log('✅ 闪念小抄已安装（包名 com.whyy.snapnotes）');
    try {
      sandbox.log('[诊断] 条目原始字段: ' + JSON.stringify(entry));
    } catch (e) {}
  } else {
    sandbox.log('❌ 列表中没有 com.whyy.snapnotes。若你确认已安装：请在手环上手动打开一次《闪念小抄》再刷新（触发互连注册）；仍无则检查手环开发者/互连调试模式并重装 RPK');
  }
}

// ==================== 响应等待机制 ====================

function waitForResponse(tag, key, timeout) {
  timeout = timeout || RESPONSE_TIMEOUT;
  return new Promise(function (resolve, reject) {
    // 清理同 key 的旧 pending
    var existing = state.pendingResponses.get(key);
    if (existing) clearTimeout(existing.timer);

    var timer = setTimeout(function () {
      var current = state.pendingResponses.get(key);
      if (current && current.resolve === resolve) {
        state.pendingResponses.delete(key);
        reject(new Error('等待 ' + tag + ' 响应超时'));
      }
    }, timeout);

    state.pendingResponses.set(key, { resolve: resolve, reject: reject, timer: timer });
  });
}

function resolveResponse(key, data) {
  var pending = state.pendingResponses.get(key);
  if (pending) {
    clearTimeout(pending.timer);
    state.pendingResponses.delete(key);
    pending.resolve(data);
  }
}

function cancelPendingResponse(key) {
  var pending = state.pendingResponses.get(key);
  if (pending) {
    clearTimeout(pending.timer);
    state.pendingResponses.delete(key);
  }
}

function cancelAllPending() {
  state.pendingResponses.forEach(function (pending, key) {
    clearTimeout(pending.timer);
  });
  state.pendingResponses.clear();
}

/**
 * 本地预校验知识点 JSON（与手环端 jsonParser.js 同规则）：
 *   { "<科目>": [ { title 必填, desc/raw/points/formulas 可选 }, ... ] }
 * @returns {{ok:boolean, subjects?:Array<{name,count}>, error?:string}}
 */
function validateKnowledgeJson(text) {
  if (!text || typeof text !== 'string' || text.trim().length === 0) {
    return { ok: false, error: '文件内容为空' };
  }
  var parsed;
  try {
    parsed = JSON.parse(text);
  } catch (e) {
    return { ok: false, error: 'JSON 解析失败，请检查文件格式' };
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return { ok: false, error: 'JSON 结构无效：顶层必须是对象 {"科目": [...]}' };
  }
  var subjects = [];
  var names = Object.keys(parsed);
  for (var i = 0; i < names.length; i++) {
    var list = parsed[names[i]];
    if (!Array.isArray(list)) continue;
    var count = 0;
    for (var j = 0; j < list.length; j++) {
      // 与 cleanItem 同规则：必须为对象且含非空字符串 title
      if (list[j] && typeof list[j] === 'object' && list[j].title && typeof list[j].title === 'string') count++;
    }
    if (count > 0) subjects.push({ name: names[i], count: count });
  }
  if (subjects.length === 0) {
    return { ok: false, error: '没有有效的知识点条目（每个条目需含字符串 title 字段）' };
  }
  return { ok: true, subjects: subjects };
}

/**
 * 内容预处理（文件名 + 文本）：识别 TXT/JSON、校验 JSON 结构。
 * 供单文件导入、批量导入与 zip 解压上传共用。返回 { fileName, text }；校验失败返回 null。
 */
async function prepareTransferContent(rawName, text) {
  var isJson = /\.json$/i.test(rawName);
  // TXT 去掉后缀传输（旧格式）；JSON 必须保留 .json 后缀，手环据此路由到知识点阅读器
  var fileName = isJson ? rawName : rawName.replace(/\.txt$/i, '');
  // 如果文件名也是 base64 编码，解码它
  if (!isJson && isBase64(fileName) && fileName.length >= 8) {
    var decodedName = decodeBase64ToText(fileName);
    if (decodedName && decodedName.length > 0) fileName = decodedName;
  }
  // 内容嗅探：后缀是 .json 但实际是纯文本（不以 { 开头）时自动按 TXT 传输
  var jsonLike = /^\s*\{/.test(text);
  if (isJson && !jsonLike) {
    sandbox.log('⚠️ ' + rawName + ' 后缀为 .json 但内容为纯文本，已自动按 TXT 文本传输');
    isJson = false;
    fileName = rawName.replace(/\.json$/i, '');
  }
  if (isJson) {
    var check = validateKnowledgeJson(text);
    if (!check.ok) {
      sandbox.log('❌ ' + rawName + ' JSON 校验未通过：' + check.error);
      sandbox.log('   参考 samples/knowledge-sample.json 的结构');
      return null;
    }
    var subjectDesc = [];
    for (var si = 0; si < check.subjects.length; si++) {
      subjectDesc.push(check.subjects[si].name + '(' + check.subjects[si].count + '条)');
    }
    sandbox.log('✅ ' + rawName + ' JSON 校验通过，共 ' + check.subjects.length + ' 个科目：' + subjectDesc.join('、'));
  } else {
    // TXT 传输前压缩：去 \r\n → \n、删除 3+ 连续空行、去首尾空白——
    // 手环端不显示这些差异，但可显著减小大文件的存储/读取分块数。
    var original = text.length;
    text = text
      .replace(/\r\n/g, '\n')
      .replace(/\r/g, '\n')
      .replace(/\n{3,}/g, '\n\n')
      .replace(/[ \t]+\n/g, '\n')
      .trim();
    if (text.length < original) {
      var ratio = ((1 - text.length / original) * 100).toFixed(1);
      sandbox.log('🗜 ' + rawName + ' 已压缩空白: ' + original + ' → ' + text.length + ' chars (-' + ratio + '%)');
    }
  }
  return { fileName: fileName, text: text };
}

/**
 * 读取并预处理一个待传文件：读文本、识别 TXT/JSON、校验 JSON 结构。
 * 供单文件导入与批量导入共用。返回 { fileName, text }；校验失败返回 null。
 */
async function prepareTransfer(file) {
  var text = await readFileAsText(file);
  var rawName = file.name || 'untitled';
  return prepareTransferContent(rawName, text);
}

// ==================== ZIP 考点包（本地解压 + 按路径建目录上传） ====================

// 读取文件为 ArrayBuffer（与 readFileAsText 同级的多方式兼容）
async function readFileAsArrayBuffer(file) {
  if (!file) throw new Error('文件对象为空');
  if (typeof file.arrayBuffer === 'function') {
    var buf = await file.arrayBuffer();
    if (buf && buf.byteLength !== undefined) return buf;
  }
  var filePath = file.path || file.filePath || file.uri;
  if (filePath && sandbox.fs) {
    if (typeof sandbox.fs.read === 'function') {
      var c = await sandbox.fs.read(filePath);
      if (typeof c === 'string') return base64ToArrayBuffer(c);
      if (c && c.byteLength !== undefined) return c;
    }
    if (typeof sandbox.fs.readFile === 'function') {
      var c2 = await sandbox.fs.readFile(filePath);
      if (typeof c2 === 'string') return base64ToArrayBuffer(c2);
      if (c2 && c2.byteLength !== undefined) return c2;
    }
  }
  // FileReader 兜底（readAsArrayBuffer）
  if (typeof FileReader !== 'undefined' && typeof Blob !== 'undefined' && (file instanceof Blob || (file && file.size !== undefined))) {
    return await new Promise(function (resolve, reject) {
      var reader = new FileReader();
      reader.onload = function () { resolve(reader.result); };
      reader.onerror = function () { reject(new Error('FileReader 读取 ArrayBuffer 失败')); };
      reader.readAsArrayBuffer(file);
    });
  }
  throw new Error('无法以二进制方式读取文件，无法解析 zip');
}

function base64ToArrayBuffer(b64) {
  var trimmed = ('' + b64).replace(/\s/g, '');
  var binary = atob(trimmed);
  var bytes = new Uint8Array(binary.length);
  for (var i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes.buffer;
}

// 解析 ZIP 中央目录，返回条目列表 {name, method, compSize, dataStart}
function parseZipEntries(buffer) {
  var view = new DataView(buffer);
  var u8 = new Uint8Array(buffer);
  var minEocd = Math.max(0, buffer.byteLength - 22 - 65536);
  var eocd = -1;
  for (var i = buffer.byteLength - 22; i >= minEocd; i--) {
    if (view.getUint32(i, true) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('zip 无效：找不到中央目录结束标记');
  var entryCount = view.getUint16(eocd + 10, true);
  var cdOffset = view.getUint32(eocd + 16, true);
  var dec = new TextDecoder('utf-8');
  var entries = [];
  var p = cdOffset;
  for (var e = 0; e < entryCount; e++) {
    if (view.getUint32(p, true) !== 0x02014b50) throw new Error('zip 无效：中央目录损坏');
    var method = view.getUint16(p + 10, true);
    var compSize = view.getUint32(p + 20, true);
    var nameLen = view.getUint16(p + 28, true);
    var extraLen = view.getUint16(p + 30, true);
    var commentLen = view.getUint16(p + 32, true);
    var localOffset = view.getUint32(p + 42, true);
    var name = dec.decode(u8.subarray(p + 46, p + 46 + nameLen));
    var lfnLen = view.getUint16(localOffset + 26, true);
    var lExtraLen = view.getUint16(localOffset + 28, true);
    entries.push({ name: name, method: method, compSize: compSize, dataStart: localOffset + 30 + lfnLen + lExtraLen });
    p += 46 + nameLen + extraLen + commentLen;
  }
  return entries;
}

// deflate(方法8) 解压：用浏览器原生 DecompressionStream；不支持时给出明确提示
async function inflateRaw(data) {
  if (typeof DecompressionStream === 'undefined') {
    throw new Error('当前环境不支持解压 deflate，请用「仅存储」方式压缩 zip（或换用 Chrome 内核浏览器）');
  }
  var ds = new DecompressionStream('deflate-raw');
  var stream = new Blob([data]).stream().pipeThrough(ds);
  var buf = await new Response(stream).arrayBuffer();
  return new Uint8Array(buf);
}

// 解压 zip 文件 → [{path, dir, bytes}]（保留原内部路径）
async function unzipToEntries(file) {
  var buffer = await readFileAsArrayBuffer(file);
  var u8 = new Uint8Array(buffer);
  var entries = parseZipEntries(buffer);
  var out = [];
  var skipped = 0;
  for (var i = 0; i < entries.length; i++) {
    var en = entries[i];
    var isDir = /\/$/.test(en.name);
    if (isDir) {
      out.push({ path: en.name, dir: true, bytes: null });
      continue;
    }
    var raw = u8.subarray(en.dataStart, en.dataStart + en.compSize);
    var data;
    if (en.method === 0) {
      data = raw;
    } else if (en.method === 8) {
      data = await inflateRaw(raw);
    } else {
      sandbox.log('⏭️ 跳过不支持压缩方式(' + en.method + ')的条目: ' + en.name);
      skipped++;
      continue;
    }
    out.push({ path: en.name, dir: false, bytes: data });
  }
  if (out.length === 0) throw new Error('zip 中没有可用的文件条目' + (skipped > 0 ? '（' + skipped + ' 个条目不支持）' : ''));
  return out;
}

function normalizeZipPath(p) {
  return ('' + p).replace(/\\/g, '/').replace(/^\/+/, '').replace(/\/+$/, '');
}

// 规范化条目路径：把 zip 内的路径映射到手环路径（保留文件夹层级）
function splitZipPath(path) {
  var parts = normalizeZipPath(path).split('/').filter(function (s) { return s.length > 0; });
  return parts;
}

// 在手环上按路径逐级创建文件夹，返回最终文件夹 ID（全部已存在则直接返回）
async function ensureFolderPath(parts, rootFolderId) {
  var cur = rootFolderId || 'bt_root';
  if (!parts || parts.length === 0) return cur;
  for (var i = 0; i < parts.length; i++) {
    var name = parts[i];
    // 先查文件树里是否已存在同名子文件夹（避免重复创建）
    var existing = findChildFolderId(state.fileTree, cur, name);
    if (existing) {
      cur = existing;
      continue;
    }
    var id = await createFolderRaw(name, cur);
    if (!id) {
      sandbox.log('❌ 创建文件夹失败: ' + name + '（在 ' + cur + ' 下）');
      throw new Error('创建文件夹失败: ' + name);
    }
    cur = id;
    await new Promise(function (r) { setTimeout(r, 120); }); // 轻量防抖，避免连发过快
  }
  return cur;
}

// 在文件树中查找指定父文件夹下的同名子文件夹 ID
function findChildFolderId(nodes, parentId, name) {
  if (!nodes) return null;
  for (var i = 0; i < nodes.length; i++) {
    var n = nodes[i];
    if (n.id === parentId && n.children) {
      for (var j = 0; j < n.children.length; j++) {
        var c = n.children[j];
        if (c.type === 'folder' && c.name === name) return c.id;
      }
      return null;
    }
    if (n.children) {
      var found = findChildFolderId(n.children, parentId, name);
      if (found) return found;
    }
  }
  return null;
}

// zip 内条目文本读取（兼容中文、UTF-8 无 BOM）
function bytesToText(bytes) {
  if (typeof TextDecoder !== 'undefined') {
    return new TextDecoder('utf-8').decode(bytes);
  }
  var bin = '';
  for (var i = 0; i < bytes.length; i++) bin += String.fromCharCode(bytes[i]);
  try { return decodeURIComponent(escape(bin)); } catch (e) { return bin; }
}

/**
 * 导入一个 zip 考点包：解压 → 按内部路径在手环建目录 → 逐文件上传（txt/json 均支持）
 */
async function importZipPackage(file, targetFolder) {
  if (state.transferring) throw new Error('正在传输中');
  sandbox.log('正在解析 zip 考点包: ' + (file.name || '未知') + ' (' + (file.size || 0) + ' 字节)...');
  var entries = await unzipToEntries(file);

  // 过滤出可上传文件（txt/json），其它类型（png 等）提示跳过
  var files = [];
  var skippedOther = 0;
  for (var i = 0; i < entries.length; i++) {
    var en = entries[i];
    if (en.dir) continue;
    var n = normalizeZipPath(en.path);
    if (!/\.(txt|json)$/i.test(n)) { skippedOther++; continue; }
    files.push({ path: en.path, bytes: en.bytes });
  }
  if (files.length === 0) {
    throw new Error('zip 中没有 .txt / .json 考点文件' + (skippedOther > 0 ? '（另有 ' + skippedOther + ' 个其它类型文件已跳过）' : ''));
  }
  if (skippedOther > 0) {
    sandbox.log('⏭️ 已跳过 ' + skippedOther + ' 个非 txt/json 文件（如公式图，需单独导入手环 formulas 目录）');
  }

  sandbox.log('zip 解压完成: ' + files.length + ' 个 txt/json 文件，按原路径上传至「' + (state.targetFolderName || '主页') + '」');

  var okCount = 0;
  for (var i = 0; i < files.length; i++) {
    var item = files[i];
    var parts = splitZipPath(item.path);
    var fileName = parts.pop();
    try {
      var dest = await ensureFolderPath(parts, targetFolder);
      var text = bytesToText(item.bytes);
      // 复用单文件的预处理（TXT 压缩 / JSON 校验）
      var prepared = await prepareTransferContent(fileName, text);
      if (!prepared) { sandbox.log('  ❌ 跳过: ' + item.path); continue; }
      sandbox.log('上传 (' + (i + 1) + '/' + files.length + '): ' + item.path);
      await transferFile(prepared.fileName, prepared.text, dest);
      okCount++;
    } catch (error) {
      sandbox.log('  ❌ ' + item.path + ' 上传失败: ' + errMsg(error));
    }
  }
  return okCount;
}

// ==================== 文件树同步 ====================

function registerTreeHandler() {
  registerTagHandler('tree', function (payload) {
    var response = payload.response;
    if (response === 'treeData') {
      resolveResponse('tree', payload.tree || []);
    } else if (response === 'folderCreated') {
      resolveResponse('createFolder', payload);
    } else if (response === 'nodeDeleted') {
      resolveResponse('deleteNode', payload);
    } else if (response === 'nodeRenamed') {
      resolveResponse('renameNode', payload);
    } else if (response === 'nodeMoved') {
      resolveResponse('moveNode', payload);
    }
  });
}

async function requestTree() {
  if (!state.connected) return;

  sandbox.log('正在同步文件树...');

  var maxRetries = 3;
  for (var attempt = 1; attempt <= maxRetries; attempt++) {
    try {
      var treePromise = waitForResponse('tree', 'tree', TREE_TIMEOUT);
      await sendMessage('tree', { action: 'getTree' });
      var tree = await treePromise;

      state.fileTree = tree || [];
      var count = countNodes(tree);
      var folderCount = buildFolderOptions(state.fileTree).length - 1;
      sandbox.log('文件树同步成功: ' + count + ' 个项目, ' + folderCount + ' 个文件夹');
      // 打印文件夹列表用于调试
      var opts = buildFolderOptions(state.fileTree);
      for (var oi = 0; oi < opts.length; oi++) {
        sandbox.log('  文件夹选项: ' + opts[oi].label + ' (value=' + opts[oi].value + ')');
      }
      recreateGui();
      return;
    } catch (error) {
      cancelPendingResponse('tree');
      var msg = errMsg(error);
      if (attempt < maxRetries) {
        var waitTime = 2000 * attempt;
        sandbox.log('同步第 ' + attempt + ' 次失败: ' + msg + '，' + (waitTime / 1000) + '秒后重试...');
        await new Promise(function (r) { setTimeout(r, waitTime); });
      } else {
        sandbox.log('文件树同步失败: ' + msg);
        sandbox.log('提示: 若为 AppInfo not found / 超时：① 请确认手环已安装最新 RPK（com.whyy.snapnotes, V26.8.0）并在手环上手动打开一次《闪念小抄》；② 重启手环后再试；③ 若手环应用反复崩溃请重启手环');
      }
    }
  }
}

function countNodes(nodes) {
  if (!nodes) return 0;
  var count = 0;
  for (var i = 0; i < nodes.length; i++) {
    count++;
    if (nodes[i].type === 'folder' && nodes[i].children) {
      count += countNodes(nodes[i].children);
    }
  }
  return count;
}

async function createFolder(name, parentId) {
  sandbox.log('正在创建文件夹: ' + name);
  try {
    var promise = waitForResponse('tree', 'createFolder', TREE_TIMEOUT);
    await sendMessage('tree', { action: 'createFolder', name: name, parentId: parentId || 'bt_root' });
    var result = await promise;
    if (result.success) {
      sandbox.log('文件夹创建成功: ' + result.folderId);
      await requestTree();
    } else {
      sandbox.log('创建失败: ' + (result.error || '未知错误'));
    }
  } catch (error) {
    cancelPendingResponse('createFolder');
    sandbox.log('创建文件夹失败: ' + errMsg(error));
  }
}

// 批量导入专用：只建文件夹并返回 folderId，不刷新文件树（失败返回 null）
async function createFolderRaw(name, parentId) {
  try {
    var promise = waitForResponse('tree', 'createFolder', TREE_TIMEOUT);
    await sendMessage('tree', { action: 'createFolder', name: name, parentId: parentId || 'bt_root' });
    var result = await promise;
    return result && result.success ? result.folderId : null;
  } catch (error) {
    cancelPendingResponse('createFolder');
    sandbox.log('创建文件夹失败: ' + errMsg(error));
    return null;
  }
}

/**
 * 调整手环端条目顺序（上移/下移一位）
 * @param {string} nodeId 节点 ID
 * @param {string} direction 'up' | 'down'
 * @returns {Promise<boolean>} 是否成功
 */
async function moveNode(nodeId, direction) {
  try {
    var promise = waitForResponse('tree', 'moveNode', TREE_TIMEOUT);
    await sendMessage('tree', { action: 'moveNode', nodeId: nodeId, direction: direction });
    var result = await promise;
    if (result && result.success) return true;
    sandbox.log('顺序调整失败: ' + ((result && result.error) || '未知错误'));
    return false;
  } catch (error) {
    cancelPendingResponse('moveNode');
    sandbox.log('顺序调整失败: ' + errMsg(error));
    return false;
  }
}

async function deleteNode(nodeId) {
  sandbox.log('正在删除: ' + nodeId);
  try {
    var promise = waitForResponse('tree', 'deleteNode', TREE_TIMEOUT);
    await sendMessage('tree', { action: 'deleteNode', nodeId: nodeId });
    var result = await promise;
    if (result.success) {
      sandbox.log('删除成功');
      state.selectedNode = null;
      await requestTree();
    } else {
      sandbox.log('删除失败: ' + (result.error || '未知错误'));
    }
  } catch (error) {
    cancelPendingResponse('deleteNode');
    sandbox.log('删除失败: ' + errMsg(error));
  }
}

async function renameNode(nodeId, newName) {
  sandbox.log('正在重命名: ' + nodeId + ' → ' + newName);
  try {
    var promise = waitForResponse('tree', 'renameNode', TREE_TIMEOUT);
    await sendMessage('tree', { action: 'renameNode', nodeId: nodeId, newName: newName });
    var result = await promise;
    if (result.success) {
      sandbox.log('重命名成功');
      await requestTree();
    } else {
      sandbox.log('重命名失败: ' + (result.error || '未知错误'));
    }
  } catch (error) {
    cancelPendingResponse('renameNode');
    sandbox.log('重命名失败: ' + errMsg(error));
  }
}

// ==================== 文件传输协议 ====================

function registerFileHandler() {
  registerTagHandler('file', function (payload) {
    var type = payload.type;
    switch (type) {
      case 'ready':             resolveResponse('file_ready', payload); break;
      case 'next_chunk':        resolveResponse('file_next_chunk', payload); break;
      case 'transfer_finished': resolveResponse('file_finished', payload); break;
      case 'storage_info':
        // 手环存储信息回包（Snapnotes 协议）
        sandbox.log('[存储] 总:' + Math.round((payload.totalStorage || 0) / 1048576) + 'MB 可用:' + Math.round((payload.actualAvailable || 0) / 1048576) + 'MB');
        break;
      case 'error':
        // 手环报错（如 JSON 校验失败、存储不足）：立即终止当前等待，抛出具体原因
        failAllFilePending(payload.message || '手环返回错误');
        break;
      case 'cancel':            resolveResponse('file_cancel', payload); break;
      default: sandbox.log('[警告] 未知文件消息: ' + type);
    }
  });
}

// 手环返回 error 时快速失败：拒绝所有正在挂起的 file 协议响应
// （否则要等满 15 秒超时才能看到失败原因）
function failAllFilePending(message) {
  var keys = ['file_ready', 'file_next_chunk', 'file_chunk_complete', 'file_chapter_saved', 'file_finished'];
  for (var i = 0; i < keys.length; i++) {
    var pending = state.pendingResponses.get(keys[i]);
    if (pending) {
      clearTimeout(pending.timer);
      state.pendingResponses.delete(keys[i]);
      pending.reject(new Error(message));
    }
  }
}

// ==================== Snapnotes 文件协议（断点续传） ====================
// 与手环端 interconnfile.js / Snapnotes-android JsonFilePusher 对齐：
//   发(stat): startTransfer{filename,totalChunks,totalBytes,folderId} / d{chunkIndex,totalChunks,data} /
//             transferComplete / cancel
//   收(type): ready{nextChunkIndex ← 断点续传起点} / next_chunk / transfer_finished / error
// 分片按 UTF-8 字节上限 10KB 切，不切断多字节字符。

function utf8ByteLength(str) {
  var bytes = 0;
  for (var i = 0; i < str.length; i++) {
    var c = str.charCodeAt(i);
    if (c >= 0xd800 && c <= 0xdbff && i + 1 < str.length) {
      var c2 = str.charCodeAt(i + 1);
      if (c2 >= 0xdc00 && c2 <= 0xdfff) { bytes += 4; i++; continue; }
    }
    if (c < 0x80) bytes += 1;
    else if (c < 0x800) bytes += 2;
    else bytes += 3;
  }
  return bytes;
}

// 按字节数上限切片（逐码点累加，与 JsonFilePusher.chunkUtf8Text 同规则）
function splitContentUtf8(content, maxBytes) {
  if (!content || content.length === 0) return [''];
  var chunks = [];
  var buf = '';
  var bufBytes = 0;
  for (var i = 0; i < content.length; ) {
    var codePoint = content.codePointAt(i);
    var ch = String.fromCodePoint(codePoint);
    var cb = codePoint > 0xffff ? 4 : (codePoint >= 0x800 ? 3 : (codePoint >= 0x80 ? 2 : 1));
    if (buf.length > 0 && bufBytes + cb > maxBytes) {
      chunks.push(buf);
      buf = '';
      bufBytes = 0;
    }
    buf += ch;
    bufBytes += cb;
    i += ch.length;
  }
  if (buf.length > 0) chunks.push(buf);
  return chunks.length > 0 ? chunks : [''];
}

async function transferFile(fileName, content, targetFolder) {
  if (state.transferring) throw new Error('正在传输中');
  state.transferring = true;

  try {
    // 自动断点续传：失败后最多重试 2 次，手环端从缺失分片继续，不重传已收内容
    await runSnTransfer(fileName, content, targetFolder, 0);
    setTimeout(function () { requestTree(); }, 500);
  } finally {
    state.transferring = false;
  }
}

async function runSnTransfer(fileName, content, targetFolder, attempt) {
  var chunks = splitContentUtf8(content, SN_CHUNK_BYTES);
  var totalChunks = chunks.length;
  var totalBytes = utf8ByteLength(content);

  try {
    sandbox.log('开始传输: ' + fileName + ' (' + totalBytes + ' 字节, ' + totalChunks + ' 片)' + (attempt > 0 ? ' [断点续传 #' + attempt + ']' : ''));

    // 步骤 1: startTransfer → 等待 ready（nextChunkIndex 即断点续传起点）
    var readyPromise = waitForResponse('file', 'file_ready', RESPONSE_TIMEOUT);
    await sendMessage('file', {
      stat: 'startTransfer',
      filename: fileName,
      totalChunks: totalChunks,
      totalBytes: totalBytes,
      folderId: targetFolder
    });
    var ready = await readyPromise;
    var startIndex = (ready && typeof ready.nextChunkIndex === 'number') ? ready.nextChunkIndex : 0;
    if (startIndex > 0 && startIndex < totalChunks) {
      sandbox.log('⚡ 断点续传：从第 ' + (startIndex + 1) + '/' + totalChunks + ' 片继续（前 ' + startIndex + ' 片已收）');
    }

    // 步骤 2: 从续传起点逐片传输，每片等 next_chunk 流控
    for (var i = startIndex; i < totalChunks; i++) {
      var chunkPromise = waitForResponse('file', 'file_next_chunk', RESPONSE_TIMEOUT);
      await sendMessage('file', {
        stat: 'd',
        chunkIndex: i,
        totalChunks: totalChunks,
        data: chunks[i]
      });
      await chunkPromise;
      if ((i - startIndex + 1) % 5 === 0 || i === totalChunks - 1) {
        sandbox.log('传输进度: ' + Math.round((i + 1) * 100 / totalChunks) + '% (' + (i + 1) + '/' + totalChunks + ')');
      }
    }

    // 步骤 3: transferComplete → 等待 transfer_finished（手环组装+校验+落盘）
    sandbox.log('等待手环保存...');
    var finishedPromise = waitForResponse('file', 'file_finished', RESPONSE_TIMEOUT);
    await sendMessage('file', { stat: 'transferComplete' });
    await finishedPromise;

    sandbox.log('✅ 传输完成: ' + fileName);
  } catch (error) {
    cancelPendingResponse('file_ready');
    cancelPendingResponse('file_next_chunk');
    cancelPendingResponse('file_finished');

    // 瞬时失败自动断点续传（不发 cancel，保留手环端已收分片）
    if (attempt < 2) {
      sandbox.log('⚠️ 传输中断，自动断点续传… (' + errMsg(error) + ')');
      await new Promise(function (r) { setTimeout(r, 1000); });
      return runSnTransfer(fileName, content, targetFolder, attempt + 1);
    }

    // 重试用尽：放弃并发送 cancel 让手环清理状态
    try { await sendMessage('file', { stat: 'cancel' }); } catch (_) {}
    sandbox.log('❌ 传输失败: ' + errMsg(error));
    throw error;
  }
}

// ==================== GUI 界面 ====================

var mainGui = null;

// 将文件树扁平化为文件夹列表（用于下拉选择框，带树形缩进符号）
function buildFolderOptions(nodes) {
  var options = [{ value: 'bt_root', label: '📁 主页', selected: true }];
  function walk(list, prefix) {
    if (!list) return;
    // 先筛选出文件夹节点
    var folders = [];
    for (var i = 0; i < list.length; i++) {
      if (list[i].type === 'folder') folders.push(list[i]);
    }
    for (var i = 0; i < folders.length; i++) {
      var node = folders[i];
      var isLast = (i === folders.length - 1);
      var treeChar = isLast ? '└─ ' : '├─ ';
      var childPrefix = prefix + (isLast ? '   ' : '│  ');
      options.push({ value: node.id, label: prefix + treeChar + '📁 ' + node.name });
      if (node.children && node.children.length > 0) {
        walk(node.children, childPrefix);
      }
    }
  }
  walk(nodes, '');
  return options;
}

// 从 ID 查找文件夹名
function findFolderName(nodes, targetId) {
  if (targetId === 'bt_root') return '主页';
  function walk(list) {
    if (!list) return null;
    for (var i = 0; i < list.length; i++) {
      if (list[i].id === targetId && list[i].type === 'folder') return list[i].name;
      if (list[i].children) {
        var found = walk(list[i].children);
        if (found) return found;
      }
    }
    return null;
  }
  return walk(nodes) || targetId;
}

// 销毁并重建 GUI（因为 setValue 无法更新 select 的 options，必须重建）
function recreateGui() {
  // 保存当前输入值
  var savedFolderName = '';
  var savedFile = state.selectedFile;
  if (mainGui) {
    try { savedFolderName = mainGui.getValue('newFolderName') || ''; } catch (e) {}
    try { mainGui.hide(); } catch (e) {}
  }
  mainGui = null;
  showMainGui();
  // 恢复输入值
  if (savedFolderName) {
    try { mainGui.setValue('newFolderName', savedFolderName); } catch (e) {}
  }
  state.selectedFile = savedFile;
}

// 从 select:change 或 getValue 返回的值解析出文件夹 ID 和名称
// 处理所有可能的返回格式：文件夹ID字符串、标签文本、数字索引、对象等
function resolveFolderFromValue(value) {
  if (value === null || value === undefined) return null;

  var options = buildFolderOptions(state.fileTree);

  // 情况 1: value 是字符串且是文件夹 ID（以 bt_ 开头）
  if (typeof value === 'string' && value.indexOf('bt_') === 0) {
    var name = findFolderName(state.fileTree, value);
    return { id: value, name: name };
  }

  // 情况 2: value 是字符串，尝试匹配 label 或 value
  if (typeof value === 'string') {
    for (var i = 0; i < options.length; i++) {
      if (options[i].label === value || options[i].value === value) {
        return { id: options[i].value, name: findFolderName(state.fileTree, options[i].value) };
      }
    }
    // 尝试去掉树形符号后匹配文件夹名
    var cleanName = value.replace(/^[├└│─\s]+/, '').replace(/^📁\s*/, '').trim();
    if (cleanName) {
      for (var j = 0; j < options.length; j++) {
        var optClean = options[j].label.replace(/^[├└│─\s]+/, '').replace(/^📁\s*/, '').trim();
        if (optClean === cleanName) {
          return { id: options[j].value, name: findFolderName(state.fileTree, options[j].value) };
        }
      }
    }
    // 尝试作为数字索引
    var idx = parseInt(value);
    if (!isNaN(idx) && idx >= 0 && idx < options.length) {
      return { id: options[idx].value, name: findFolderName(state.fileTree, options[idx].value) };
    }
  }

  // 情况 3: value 是数字（索引）
  if (typeof value === 'number') {
    var idx2 = Math.floor(value);
    if (idx2 >= 0 && idx2 < options.length) {
      return { id: options[idx2].value, name: findFolderName(state.fileTree, options[idx2].value) };
    }
  }

  // 情况 4: value 是对象，尝试读取 .value 或 .id 属性
  if (typeof value === 'object') {
    var objId = value.value || value.id || value.v;
    if (typeof objId === 'string' && objId.indexOf('bt_') === 0) {
      return { id: objId, name: findFolderName(state.fileTree, objId) };
    }
    var objLabel = value.label || value.text || value.name;
    if (objLabel) {
      return resolveFolderFromValue(objLabel);
    }
  }

  return null;
}

// ==================== 排序/批量 辅助函数 ====================

// 把「直接选择文件夹」的返回值归一化为文件数组：
// BandBurg 各版本可能返回 文件数组 / {files:[...]} / {children:[...]} / 单文件对象
function normalizeDirPick(file) {
  if (!file) return [];
  if (Array.isArray(file)) return file;
  if (Array.isArray(file.files)) return file.files;
  if (Array.isArray(file.children)) return file.children;
  return [file];
}

// 从文件的绝对路径推断其父目录名（用于自动填充手环新文件夹名）
function guessParentFolderName(file) {
  var p = file && (file.path || file.uri || file.fullPath);
  if (!p || typeof p !== 'string') return '';
  var parts = p.replace(/\\/g, '/').split('/');
  if (parts.length >= 2) {
    var cand = parts[parts.length - 2];
    if (cand && cand !== '') return cand;
  }
  return '';
}

// 在文件树中按 ID 查找节点
function findTreeNode(nodes, id) {
  if (!nodes) return null;
  for (var i = 0; i < nodes.length; i++) {
    if (!nodes[i]) continue;
    if (nodes[i].id === id) return nodes[i];
    if (nodes[i].children) {
      var r = findTreeNode(nodes[i].children, id);
      if (r) return r;
    }
  }
  return null;
}

// 生成「当前文件夹内条目」选项列表（文件+文件夹，按手环展示顺序）
function buildChildOptions(tree, parentId) {
  parentId = parentId || 'bt_root';
  var list;
  if (parentId === 'bt_root') {
    list = tree || [];
  } else {
    var node = findTreeNode(tree, parentId);
    list = (node && node.children) ? node.children : [];
  }
  var opts = [];
  for (var i = 0; i < list.length; i++) {
    var c = list[i];
    opts.push({
      label: (c.type === 'folder' ? '📁 ' : '📄 ') + c.name,
      value: c.id,
      selected: c.id === state.orderNodeId
    });
  }
  if (opts.length === 0) opts.push({ label: '（空文件夹）', value: '__empty__' });
  return opts;
}

// 从 select:change / getValue 返回解析出条目 ID（容错同 resolveFolderFromValue）
function resolveOrderNodeId(value) {
  if (value === null || value === undefined) return null;
  var opts = buildChildOptions(state.fileTree, state.targetFolder);
  if (typeof value === 'string' && value.indexOf('bt_') === 0) return value;
  if (typeof value === 'string') {
    for (var i = 0; i < opts.length; i++) {
      if (opts[i].label === value || opts[i].value === value) return opts[i].value;
    }
    var idx = parseInt(value);
    if (!isNaN(idx) && idx >= 0 && idx < opts.length) return opts[idx].value;
  }
  if (typeof value === 'number') {
    var idx2 = Math.floor(value);
    if (idx2 >= 0 && idx2 < opts.length) return opts[idx2].value;
  }
  if (typeof value === 'object') {
    var objId = value.value || value.id || value.v;
    if (typeof objId === 'string' && objId.indexOf('bt_') === 0) return objId;
    var objLabel = value.label || value.text || value.name;
    if (objLabel) return resolveOrderNodeId(objLabel);
  }
  return null;
}

// 从 GUI 读取当前选中的目标文件夹并同步到 state（btnNewFolder/btnImport/btnBatchImport 共用）
function refreshTargetFolderFromGui() {
  try {
    var guiValue = mainGui.getValue('targetFolder');
    if (guiValue !== null && guiValue !== undefined) {
      var resolved = resolveFolderFromValue(guiValue);
      if (resolved) {
        state.targetFolder = resolved.id;
        state.targetFolderName = resolved.name;
      }
    }
  } catch (e) {}
}

function showMainGui() {
  if (mainGui) {
    mainGui.show();
    return;
  }

  // 构建文件树选项（带树形缩进符号）
  var folderOptions = buildFolderOptions(state.fileTree);
  // 保持当前选择
  for (var i = 0; i < folderOptions.length; i++) {
    if (folderOptions[i].value === state.targetFolder) {
      folderOptions[i].selected = true;
      break;
    }
  }
  // 构建「当前文件夹内条目」排序选项
  var childOptions = buildChildOptions(state.fileTree, state.targetFolder);

  mainGui = sandbox.gui({
    title: '考点传输',
    elements: [
      // ── 第一步：选择目标位置 ──
      { type: 'label', text: '—— ① 选择手环目标文件夹 ——' },
      {
        type: 'select',
        id: 'targetFolder',
        label: '手环当前文件夹',
        options: folderOptions
      },
      { type: 'button', id: 'btnRefresh', text: '刷新文件树' },
      { type: 'input', id: 'newFolderName', placeholder: '新文件夹名（留空跳过）', value: '' },
      { type: 'button', id: 'btnNewFolder', text: '在当前位置新建文件夹' },

      // ── 第二步：导入 ──（选完文件自动传输，无需再点按钮）
      { type: 'label', text: '—— ② 导入考点（选完自动传到目标文件夹）——' },
      { type: 'file', id: 'fileInput', label: '导入单个 TXT / JSON 文件', accept: '.txt,.json' },
      { type: 'label', text: '批量导入（同一批文件会放进一个新文件夹）：' },
      { type: 'file', id: 'batchFiles', label: '多选文件批量导入', accept: '.txt,.json', multiple: true },
      { type: 'file', id: 'dirFiles', label: '整目录导入（部分手机适用）', accept: '.txt,.json', multiple: true, directory: true },
      { type: 'label', text: 'zip 考点包（解压后按原路径+文件夹上传）：' },
      { type: 'file', id: 'zipInput', label: '导入 zip 考点包', accept: '.zip' },
      { type: 'label', text: 'zip 考点包（解压后按原路径+文件夹上传）：' },
      { type: 'file', id: 'zipInput', label: '导入 zip 考点包', accept: '.zip' },
      { type: 'label', text: 'zip 考点包（解压后按原路径+文件夹上传）：' },
      { type: 'file', id: 'zipInput', label: '导入 zip 考点包', accept: '.zip' },
      { type: 'button', id: 'btnAddToQueue', text: '单选手机适用：逐个加入批量队列' },
      { type: 'input', id: 'batchFolderName', placeholder: '手环新文件夹名（留空=传到当前位置）', value: '' },

      // ── 第三步：调整顺序（可选）──
      { type: 'label', text: '—— ③ 调整手环内顺序（可选）——' },
      { type: 'select', id: 'orderItem', label: '当前文件夹内条目', options: childOptions },
      { type: 'button', id: 'btnMoveUp', text: '上移选中条目' },
      { type: 'button', id: 'btnMoveDown', text: '下移选中条目' },

      // ── 诊断工具 ──
      { type: 'button', id: 'btnCheckApps', text: '检测手环已装应用' },
      { type: 'button', id: 'btnRelaunch', text: '重新启动手环应用' }
    ]
  });
  // 打印简化操作指引
  sandbox.log('━━━━━━━━ 操作流程 ━━━━━━━━');
  sandbox.log('1. 上方选择手环目标文件夹（默认主页）');
  sandbox.log('2. 点「导入单个 TXT / JSON 文件」→ 选手环文件 → 自动传输');
  sandbox.log('   批量：用「多选/整目录/队列」选好后再填文件夹名即传');
  sandbox.log('3. 需要调整顺序时在「③ 调整手环内顺序」里点上移/下移');
  sandbox.log('━━━━━━━━━━━━━━━━━━━━━━━━━━');

  // 导入考点 — 选择文件后自动开始传输（简化流程：选完即传，无需再点导入）
  mainGui.on('file:change', 'fileInput', async function (file) {
    if (!file) {
      state.selectedFile = null;
      return;
    }
    state.selectedFile = file;
    var fileName = file.name || '未知文件';
    var fileSize = file.size || 0;
    sandbox.log('已选择文件: ' + fileName + ' (' + fileSize + ' 字节)');
    sandbox.log('→ 自动开始传输到「' + state.targetFolderName + '」...');
    await startImport();
  });

  // 目标文件夹切换
  mainGui.on('select:change', 'targetFolder', function (value) {
    sandbox.log('[调试] select:change 返回值: ' + JSON.stringify(value) + ' (类型: ' + typeof value + ')');
    var resolved = resolveFolderFromValue(value);
    if (resolved) {
      state.targetFolder = resolved.id;
      state.targetFolderName = resolved.name;
      sandbox.log('当前文件夹: ' + state.targetFolderName + ' (ID: ' + state.targetFolder + ')');
    } else {
      sandbox.log('[警告] 无法匹配文件夹，保持当前选择: ' + state.targetFolder + ' (' + state.targetFolderName + ')');
    }
  });

  // 新建文件夹 — 在当前选中的文件夹下创建子文件夹
  mainGui.on('button:click', 'btnNewFolder', function () {
    var name = mainGui.getValue('newFolderName');
    if (!name || !name.trim()) {
      sandbox.log('请输入文件夹名称');
      return;
    }
    // 再次从 GUI 读取当前选中的文件夹
    try {
      var guiValue = mainGui.getValue('targetFolder');
      if (guiValue !== null && guiValue !== undefined) {
        var resolved = resolveFolderFromValue(guiValue);
        if (resolved) {
          state.targetFolder = resolved.id;
          state.targetFolderName = resolved.name;
        }
      }
    } catch (e) {}
    sandbox.log('在「' + state.targetFolderName + '」(ID: ' + state.targetFolder + ') 下创建文件夹: ' + name.trim());
    createFolder(name.trim(), state.targetFolder).then(function () {
      mainGui.setValue('newFolderName', '');
    });
  });

  // 刷新
  mainGui.on('button:click', 'btnRefresh', function () {
    requestTree();
  });

  // 检测手环已装应用（诊断 AppInfo not found）
  mainGui.on('button:click', 'btnCheckApps', async function () {
    await checkInstalledApps();
  });

  // 重新启动手环应用（当应用崩溃或无法同步时使用）：先重连底层链路再启动
  mainGui.on('button:click', 'btnRelaunch', async function () {
    sandbox.log('正在重新连接并启动手环应用...');
    await ensureDeviceConnection();
    var launched = await launchWatchApp();
    if (launched) {
      sandbox.log('手环应用已启动，等待 3 秒后同步文件树...');
      setTimeout(function () { requestTree(); }, 3000);
    } else {
      sandbox.log('启动失败，请尝试重启手环');
    }
  });

  // 保留手动导入按钮（文件已选时可直接重传）
  mainGui.on('button:click', 'btnImport', async function () {
    await startImport();
  });

  /**
   * 统一导入入口：读取已选文件 → 校验 → 传输到当前文件夹。
   * 简化后的流程：选完文件自动调用；按钮仅用于重传。
   */
  async function startImport() {
    if (!state.selectedFile) {
      sandbox.log('请先选择 TXT 或 JSON 考点文件');
      return;
    }
    if (state.transferring) {
      sandbox.log('正在传输中，请等待...');
      return;
    }

    // 在传输前再次从 GUI 读取当前选中的文件夹，确保 select:change 事件不遗漏
    try {
      var guiValue = mainGui.getValue('targetFolder');
      if (guiValue !== null && guiValue !== undefined) {
        var resolved = resolveFolderFromValue(guiValue);
        if (resolved) {
          state.targetFolder = resolved.id;
          state.targetFolderName = resolved.name;
        }
      }
    } catch (e) {}

    try {
      // 使用统一的文件读取函数，自动适配多种 file 对象格式
      var prepared = await prepareTransfer(state.selectedFile);
      if (!prepared) return;

      sandbox.log('开始传输: ' + prepared.fileName + ' (' + prepared.text.length + ' 字符) → ' + state.targetFolderName + ' (folder ID: ' + state.targetFolder + ')');
      if (prepared.text.length > 50000) {
        sandbox.log('文件较大，传输可能需要较长时间');
      }

      await transferFile(prepared.fileName, prepared.text, state.targetFolder);
    } catch (error) {
      sandbox.log('传输失败: ' + errMsg(error));
    }
  }

  // 批量导入文件选择（多选时 BandBurg 可能返回数组，归一化处理）
  // 批量多选：选完自动开始传输（需先选手环目标文件夹）
  mainGui.on('file:change', 'batchFiles', async function (file) {
    var list = [];
    if (Array.isArray(file)) list = file;
    else if (file) list = [file];
    state.batchFiles = list;
    if (list.length > 0) {
      var names = [];
      for (var i = 0; i < list.length; i++) names.push(list[i].name || '未知文件');
      sandbox.log('已选择 ' + list.length + ' 个文件: ' + names.join(', '));
      sandbox.log('→ 自动开始批量导入...');
      // 沿用 btnBatchImport 的处理逻辑
      await doBatchImport();
    } else {
      sandbox.log('已清空批量选择');
    }
  });

  /** 批量导入实现：startBatchImport 和 btnBatchImport 共用 */
  async function doBatchImport() {
    var files = state.batchFiles || [];
    if (files.length === 0) {
      sandbox.log('批量队列为空');
      return;
    }
    if (state.transferring) {
      sandbox.log('正在传输中，请等待...');
      return;
    }
    refreshTargetFolderFromGui();
    var destFolder = state.targetFolder;
    var destName = state.targetFolderName;
    var folderName = '';
    try {
      folderName = ('' + (mainGui.getValue('batchFolderName') || '')).trim();
    } catch (e) {}
    if (folderName) {
      var folderId = await createFolderRaw(folderName, destFolder);
      if (!folderId) {
        sandbox.log('❌ 手环文件夹创建失败，批量导入中止');
        return;
      }
      destFolder = folderId;
      destName = folderName;
      sandbox.log('已创建手环文件夹: ' + folderName);
    }

    var okCount = 0;
    for (var i = 0; i < files.length; i++) {
      var f = files[i];
      sandbox.log('批量导入 (' + (i + 1) + '/' + files.length + '): ' + (f.name || '未知文件'));
      try {
        var prepared = await prepareTransfer(f);
        if (!prepared) continue;
        await transferFile(prepared.fileName, prepared.text, destFolder);
        okCount++;
      } catch (error) {
        sandbox.log('  ❌ 传输失败: ' + errMsg(error));
      }
    }
    sandbox.log('批量导入完成: 成功 ' + okCount + '/' + files.length + ' → ' + destName);
    state.batchFiles = [];
  }

  // 直接选择文件夹（部分手机适用）：BandBurg 可能返回文件数组 /
  // 带 files/children 的容器对象，归一化为文件列表；同时尝试从
  // 文件路径推断文件夹名，自动填到手环新文件夹名。
  mainGui.on('file:change', 'dirFiles', async function (file) {
    var list = normalizeDirPick(file);
    // 调试：打印对象键，排查不同 BandBurg 版本返回格式
    try {
      if (file && typeof file === 'object' && !Array.isArray(file)) {
        sandbox.log('[调试] 目录选择对象键: ' + Object.keys(file).join(', '));
      } else if (Array.isArray(file) && file.length > 0 && file[0]) {
        sandbox.log('[调试] 目录选择首项键: ' + Object.keys(file[0]).join(', '));
      }
    } catch (e) {}
    if (list.length > 0) {
      state.batchFiles = list;
      var names = [];
      for (var i = 0; i < list.length; i++) names.push(list[i].name || '未知文件');
      sandbox.log('已从目录选择 ' + list.length + ' 个文件: ' + names.join(', '));
      // 自动推断父文件夹名（仅当手环新文件夹名留空时）
      try {
        var cur = mainGui.getValue('batchFolderName');
        if (!cur || ('' + cur).trim() === '') {
          var guessed = guessParentFolderName(list[0]);
          if (guessed) {
            mainGui.setValue('batchFolderName', guessed);
            sandbox.log('已自动填入手环新文件夹名: ' + guessed + '（可在输入框修改/清空）');
          }
        }
      } catch (e) {}
      // 自动开始批量传输
      sandbox.log('→ 自动开始批量导入...');
      await doBatchImport();
    } else {
      sandbox.log('[提示] 目录选择未得到文件，请改用多选或队列方式');
    }
  });

  // zip 考点包：选择后自动解压并按原路径传输
  mainGui.on('file:change', 'zipInput', async function (file) {
    if (!file) return;
    if (state.transferring) {
      sandbox.log('正在传输中，请等待...');
      return;
    }
    refreshTargetFolderFromGui();
    try {
      var ok = await importZipPackage(file, state.targetFolder);
      if (ok > 0) {
        sandbox.log('✅ zip 导入完成: 成功 ' + ok + ' 个文件');
        await requestTree();
      }
    } catch (error) {
      sandbox.log('❌ zip 导入失败: ' + errMsg(error));
    }
  });

  // zip 考点包：选择后自动解压并按原路径传输
  mainGui.on('file:change', 'zipInput', async function (file) {
    if (!file) return;
    if (state.transferring) {
      sandbox.log('正在传输中，请等待...');
      return;
    }
    refreshTargetFolderFromGui();
    try {
      var ok = await importZipPackage(file, state.targetFolder);
      if (ok > 0) {
        sandbox.log('✅ zip 导入完成: 成功 ' + ok + ' 个文件');
        await requestTree();
      }
    } catch (error) {
      sandbox.log('❌ zip 导入失败: ' + errMsg(error));
    }
  });

  // 单选手机兜底：把「选择TXT/JSON考点文件」里选中的文件逐个加入批量队列
  mainGui.on('button:click', 'btnAddToQueue', function () {
    if (!state.selectedFile) {
      sandbox.log('请先用「选择TXT/JSON考点文件」选择一个文件');
      return;
    }
    var q = state.batchFiles || [];
    for (var i = 0; i < q.length; i++) {
      if (q[i].name === state.selectedFile.name && q[i].size === state.selectedFile.size) {
        sandbox.log('该文件已在队列中，跳过');
        return;
      }
    }
    q.push(state.selectedFile);
    state.batchFiles = q;
    var names = [];
    for (var j = 0; j < q.length; j++) names.push(q[j].name || '未知文件');
    sandbox.log('队列现有 ' + q.length + ' 个文件: ' + names.join(', '));
    sandbox.log('  队列模式需要手动传输：请点击「多选文件批量导入」→ 任选文件后队列自动传入');
    sandbox.log('  （或改用「② 导入单个 TXT / JSON 文件」逐个直传）');
  });

  // 排序条目选择
  mainGui.on('select:change', 'orderItem', function (value) {
    var resolved = resolveOrderNodeId(value);
    if (resolved && resolved !== '__empty__') state.orderNodeId = resolved;
  });

  // 上移选中条目
  mainGui.on('button:click', 'btnMoveUp', async function () {
    await onMoveOrder('up');
  });

  // 下移选中条目
  mainGui.on('button:click', 'btnMoveDown', async function () {
    await onMoveOrder('down');
  });

  sandbox.log('GUI 界面已创建');
}

// 上移/下移选中条目：成功后刷新文件树（recreateGui 会重建选项并恢复选中）
async function onMoveOrder(direction) {
  refreshTargetFolderFromGui();
  var nodeId = state.orderNodeId;
  try {
    var guiValue = mainGui.getValue('orderItem');
    if (guiValue !== null && guiValue !== undefined) {
      var resolved = resolveOrderNodeId(guiValue);
      if (resolved) nodeId = resolved;
    }
  } catch (e) {}
  if (!nodeId || nodeId === '__empty__') {
    sandbox.log('请先选中要移动的条目');
    return;
  }
  if (!state.connected) {
    sandbox.log('尚未连接手环');
    return;
  }
  if (await moveNode(nodeId, direction)) {
    state.orderNodeId = nodeId;
    await requestTree();
  }
}

// ==================== 主入口 ====================

async function main() {
  sandbox.log('=== 考点传输 BandBurg 脚本 ===');
  sandbox.log('参考: BandBurg + AstroBox-NG');

  // 确保底层连接（重复运行脚本时必须显式重连）
  var addr = await ensureDeviceConnection();
  if (!addr) {
    sandbox.log('错误: 未找到已连接设备，请先在 BandBurg 中连接设备');
    return;
  }
  sandbox.log('设备: ' + getDeviceName() + ' (' + addr + ')');
  state.connected = true;

  // 注册事件监听器
  sandbox.wasm.register_event_sink(function (event) {
    if (event.type === 'thirdpartyapp_message') {
      handleIncomingMessage(event.data || event);
    } else if (event.type === 'device_connected') {
      sandbox.log('设备已连接');
      state.connected = true;
    } else if (event.type === 'device_disconnected') {
      sandbox.log('设备已断开');
      state.connected = false;
      cancelAllPending();
    } else if (event.type === 'pb_packet') {
      // pb_packet 可能包含第三方应用消息
      if (event.packet && event.packet.thirdpartyApp && event.packet.thirdpartyApp.messageContent) {
        var content = event.packet.thirdpartyApp.messageContent.content;
        if (content) {
          // 尝试 base64 解码（使用 UTF-8 安全的解码方式）
          try {
            var decoded = decodeBase64ToText(content);
            try {
              var parsed = JSON.parse(decoded);
              if (parsed && parsed.tag) {
                handleIncomingMessage(parsed);
              }
            } catch (e) {
              // 不是 JSON，忽略
            }
          } catch (e) {
            // 不是 base64，尝试直接解析
            try {
              var parsed2 = JSON.parse(content);
              if (parsed2 && parsed2.tag) {
                handleIncomingMessage(parsed2);
              }
            } catch (e2) {
              // 无法解析，忽略
            }
          }
        }
      }
    }
  });
  sandbox.log('事件监听器已注册');

  // 注册消息处理器
  registerTreeHandler();
  registerFileHandler();

  // 启动手环应用
  sandbox.log('正在启动手环应用...');
  var appLaunched = await launchWatchApp();

  if (!appLaunched) {
    sandbox.log('手环应用可能已在运行，继续尝试同步...');
  }

  // 显示 GUI
  showMainGui();

  // 等待手环应用初始化后自动同步
  sandbox.log('3 秒后自动同步文件树...');
  setTimeout(function () {
    requestTree();
  }, 3000);
}

// 运行主函数
main().catch(function (e) {
  sandbox.log('启动失败: ' + errMsg(e));
});
