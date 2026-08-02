/**
 * WASM 客户端 — 封装 AstroBox-NG WASM 模块
 * 提供设备连接、第三方应用消息收发等能力
 *
 * 基于 BandBurg 项目的 wasm-client.js 改编
 * WASM 模块来自 AstroBox-NG (https://github.com/AstralSightStudios/AstroBox-NG)
 *
 * 事件接收机制（双重保障）：
 * 1. register_event_sink 注册回调，WASM 模块收到设备消息时调用
 *    回调格式: (event: string, payload: any)
 * 2. console.log 拦截作为后备方案，捕获 WASM 模块的日志输出
 *    匹配 "[WASM] Received third-party app message from" 格式来提取消息内容
 */

class WasmClient {
  constructor() {
    this.wasmModule = null;
    this.isInitialized = false;
    this.eventCallbacks = new Map();
    this._eventSinkSetup = false;
    this._consoleCaptureSetup = false;
    this._rawEventLog = [];
  }

  async init() {
    if (this.isInitialized) return true;

    try {
      // 动态导入 WASM 模块
      const wasmModule = await import('./wasm/astrobox_ng_wasm.js');

      // 使用 locateFile 显式指定 .wasm 文件路径（与 BandBurg 一致）
      await wasmModule.default({
        locateFile: (path) => {
          if (path.endsWith('.wasm')) {
            return './wasm/astrobox_ng_wasm_bg.wasm';
          }
          return path;
        }
      });

      this.wasmModule = wasmModule;
      this.isInitialized = true;
      console.log('[WASM] 模块初始化成功');
      return true;
    } catch (error) {
      console.error('[WASM] 模块初始化失败:', error);
      return false;
    }
  }

  on(event, callback) {
    if (!this.eventCallbacks.has(event)) {
      this.eventCallbacks.set(event, []);
    }
    this.eventCallbacks.get(event).push(callback);
    this._setupEventSink();
  }

  off(event, callback) {
    const callbacks = this.eventCallbacks.get(event);
    if (callbacks) {
      const idx = callbacks.indexOf(callback);
      if (idx >= 0) callbacks.splice(idx, 1);
    }
  }

  /**
   * 触发事件
   * 注意：data 可能是 null、undefined、字符串或对象
   * 对通配符事件做安全处理
   */
  emit(event, data) {
    // 触发特定事件
    const callbacks = this.eventCallbacks.get(event);
    if (callbacks) {
      callbacks.forEach(cb => {
        try { cb(data); } catch (e) { console.error('[WASM] 事件回调错误:', e); }
      });
    }

    // 触发通配符事件
    const wildcard = this.eventCallbacks.get('*');
    if (wildcard) {
      // 安全构造通配符数据，避免 ...null / ...string 的 spread 错误
      let wildcardData;
      if (data && typeof data === 'object' && !Array.isArray(data)) {
        wildcardData = { event, ...data };
      } else {
        wildcardData = { event, data };
      }
      wildcard.forEach(cb => {
        try { cb(wildcardData); } catch (e) { console.error('[WASM] 通配回调错误:', e); }
      });
    }
  }

  /**
   * 设置事件接收器（与 BandBurg 实现一致）
   * register_event_sink 回调格式: (event: string, payload: any)
   */
  _setupEventSink() {
    if (this._eventSinkSetup) return;

    if (this.wasmModule && this.wasmModule.register_event_sink) {
      try {
        // 注册事件接收回调
        // WASM 模块以 (event, payload) 两参数格式调用此回调
        this.wasmModule.register_event_sink((event, payload) => {
          // 记录原始事件用于调试
          const logEntry = {
            time: new Date().toISOString(),
            event: String(event),
            payload: this._safeStringify(payload, 500)
          };
          this._rawEventLog.push(logEntry);
          if (this._rawEventLog.length > 100) this._rawEventLog.shift();

          console.log('[WASM] Event sink:', event, payload);

          // 直接发射事件
          this.emit(event, payload);

          // 额外检查：如果事件名不是 thirdpartyapp_message，
          // 但 payload 中包含 tag 字段，也尝试提取为第三方应用消息
          if (String(event) !== 'thirdpartyapp_message') {
            this._tryExtractThirdPartyFromEvent(payload);
          }
        });
        console.log('[WASM] 事件接收器已注册 (register_event_sink)');
      } catch (error) {
        console.error('[WASM] 注册事件接收器失败:', error);
      }
    }

    this._setupConsoleCapture();
    this._eventSinkSetup = true;
  }

  /**
   * 尝试从事件 payload 中提取第三方应用消息
   * 处理多种可能的数据格式
   */
  _tryExtractThirdPartyFromEvent(payload) {
    if (!payload) return;

    let parsedData = null;
    let packageName = '';

    // 情况 1: payload 是对象
    if (typeof payload === 'object' && !Array.isArray(payload)) {
      // 直接包含 tag
      if (payload.tag) {
        parsedData = payload;
      }
      // {data: "...", package_name: "..."}
      else if (payload.data) {
        if (typeof payload.data === 'string') {
          try { parsedData = JSON.parse(payload.data); } catch (_) {}
        } else if (typeof payload.data === 'object') {
          parsedData = payload.data;
        }
        packageName = payload.package_name || payload.packageName || '';
      }
      // {message: "..."}
      else if (payload.message) {
        if (typeof payload.message === 'string') {
          try { parsedData = JSON.parse(payload.message); } catch (_) {}
        } else {
          parsedData = payload.message;
        }
      }
    }
    // 情况 2: payload 是字符串
    else if (typeof payload === 'string') {
      try {
        const parsed = JSON.parse(payload);
        if (parsed && typeof parsed === 'object') {
          if (parsed.tag) {
            parsedData = parsed;
          } else if (parsed.data) {
            if (typeof parsed.data === 'string') {
              try { parsedData = JSON.parse(parsed.data); } catch (_) { parsedData = parsed; }
            } else {
              parsedData = parsed.data;
            }
          }
        }
      } catch (_) {
        // 可能是 base64
        if (payload.length > 10 && /^[A-Za-z0-9+/=]+$/.test(payload)) {
          try {
            const decoded = atob(payload);
            try { parsedData = JSON.parse(decoded); } catch (_) {}
          } catch (_) {}
        }
      }
    }

    if (parsedData && parsedData.tag) {
      console.log('[WASM] 从事件中提取到第三方应用消息:', parsedData.tag);
      this.emit('thirdpartyapp_message', {
        data: parsedData,
        package_name: packageName,
        rawEvent: 'event_sink',
        timestamp: Date.now()
      });
    }
  }

  _safeStringify(obj, maxLen) {
    if (typeof obj === 'string') return obj.substring(0, maxLen);
    if (obj && typeof obj === 'object') {
      try { return JSON.stringify(obj).substring(0, maxLen); } catch (_) { return String(obj); }
    }
    return String(obj);
  }

  /**
   * 设置控制台日志捕获（与 BandBurg 实现一致）
   * 拦截 console.log/console.info 来捕获 WASM 模块输出的事件消息
   */
  _setupConsoleCapture() {
    if (this._consoleCaptureSetup) return;

    const self = this;
    const origLog = console.log;
    const origInfo = console.info;

    console.log = function (...args) {
      origLog.apply(console, args);
      self._processWasmLog(args);
    };
    console.info = function (...args) {
      origInfo.apply(console, args);
      self._processWasmLog(args);
    };

    this._consoleCaptureSetup = true;
    console.log('[WASM] 控制台日志捕获已启用');
  }

  _processWasmLog(args) {
    if (!args || args.length === 0) return;

    const msg = args.map(a => {
      if (typeof a === 'string') return a;
      if (a && typeof a === 'object') {
        try { return JSON.stringify(a); } catch (_) { return String(a); }
      }
      return String(a);
    }).join(' ');

    // 跳过我们自己的日志
    if (msg.startsWith('[WASM]')) return;

    // 模式 1: "[WASM] Received third-party app message from <pkg>: <data>"
    // 这是 BandBurg 使用的核心模式
    if (msg.includes('Received third-party app message from')) {
      try {
        // 尝试多种分割方式
        let packageName = '';
        let messageContent = '';

        // 格式: "... Received third-party app message from <pkg>: <data>"
        const marker = 'Received third-party app message from ';
        const idx = msg.indexOf(marker);
        if (idx >= 0) {
          const rest = msg.substring(idx + marker.length);
          // 分割包名和消息内容：第一个 ": " 之后的内容是消息
          const colonIdx = rest.indexOf(': ');
          if (colonIdx >= 0) {
            packageName = rest.substring(0, colonIdx);
            messageContent = rest.substring(colonIdx + 2);
          } else {
            // 没有冒号，整个就是消息
            messageContent = rest;
          }
        }

        if (messageContent) {
          this._dispatchCapturedMessage(packageName, messageContent);
          return;
        }
      } catch (e) {
        console.warn('[WASM] 解析第三方应用消息失败:', e);
      }
    }

    // 模式 2: 包含 "interconnect" 和 JSON 的日志
    if (msg.toLowerCase().includes('interconnect')) {
      const jsonMatch = msg.match(/\{[^{}]*"tag"[^{}]*\}/);
      if (jsonMatch) {
        this._dispatchCapturedMessage('', jsonMatch[0]);
        return;
      }
    }

    // 模式 3: 日志中直接包含带 tag 的 JSON
    const jsonWithTag = msg.match(/\{[^{}]*"tag"\s*:\s*"([^"]+)"[^{}]*\}/);
    if (jsonWithTag) {
      this._dispatchCapturedMessage('', jsonWithTag[0]);
      return;
    }

    // 模式 4: 设备连接/断开事件
    if (msg.includes('Device connected') || msg.includes('设备已连接') || msg.includes('Connected to')) {
      this.emit('device_connected', { message: msg, timestamp: Date.now() });
    }
    if (msg.includes('Device disconnected') || msg.includes('设备已断开') || msg.includes('Disconnected')) {
      this.emit('device_disconnected', { message: msg, timestamp: Date.now() });
    }

    // 模式 5: 通用消息接收日志（包含 "received" 和 "message"）
    if (msg.toLowerCase().includes('received') && msg.toLowerCase().includes('message')) {
      const jsonMatch = msg.match(/\{.+\}/);
      if (jsonMatch) {
        try {
          const parsed = JSON.parse(jsonMatch[0]);
          if (parsed && (parsed.tag || parsed.data)) {
            this._dispatchCapturedMessage('', jsonMatch[0]);
            return;
          }
        } catch (_) {}
      }
    }

    // 模式 6: 数据包日志（与 BandBurg 一致）
    if (msg.includes('on_pb_packet:')) {
      try {
        const parts = msg.split('on_pb_packet: ');
        if (parts.length > 1) {
          const packetData = JSON.parse(parts[1]);
          this.emit('pb_packet', { packet: packetData, rawMessage: msg, timestamp: Date.now() });
        }
      } catch (_) {}
    }
  }

  /**
   * 将捕获到的消息分发为 thirdpartyapp_message 事件
   * 与 BandBurg 实现对齐
   */
  _dispatchCapturedMessage(packageName, messageContent) {
    let parsedData = null;

    if (typeof messageContent === 'object') {
      parsedData = messageContent;
    } else if (typeof messageContent === 'string') {
      try {
        parsedData = JSON.parse(messageContent);
      } catch (e) {
        console.warn('[WASM] 无法解析消息内容:', messageContent.substring(0, 200));
        return;
      }
    }

    if (parsedData && typeof parsedData === 'object') {
      // 如果消息包含 data 字段且 data 是字符串，尝试进一步解析
      if (parsedData.data && typeof parsedData.data === 'string') {
        try {
          parsedData.data = JSON.parse(parsedData.data);
        } catch (_) { /* 保持原样 */ }
      }

      // 确保有 tag 字段
      const tag = parsedData.tag || (parsedData.data && parsedData.data.tag);
      if (tag) {
        console.log('[WASM] 捕获到消息:', tag);
        // data 字段包含完整的消息对象（包含 tag）
        const dataToSend = parsedData.data || parsedData;
        this.emit('thirdpartyapp_message', {
          data: dataToSend,
          package_name: packageName || parsedData.package_name || '',
          timestamp: Date.now()
        });
      } else {
        // 即使没有 tag，也尝试作为通用消息处理
        console.log('[WASM] 收到无 tag 的消息:', JSON.stringify(parsedData).substring(0, 200));
      }
    }
  }

  /**
   * 获取原始事件日志（用于调试）
   */
  getRawEventLog() {
    return this._rawEventLog;
  }

  async call(command, args = {}) {
    if (!this.isInitialized) {
      const ok = await this.init();
      if (!ok) throw new Error('WASM 模块未初始化');
    }
    if (!this.wasmModule) throw new Error('WASM 模块不可用');

    console.log(`[WASM] 调用: ${command}`, args);

    let result;
    switch (command) {
      case 'miwear_connect':
        result = await this.wasmModule.miwear_connect(
          args.name || '',
          args.addr || '',
          args.authkey || '',
          args.sar_version || args.sarVersion || 2,
          args.connect_type || args.connectType || 'SPP'
        );
        break;

      case 'miwear_disconnect':
        result = await this.wasmModule.miwear_disconnect(args.addr || '');
        break;

      case 'miwear_get_connected_devices':
        result = await this.wasmModule.miwear_get_connected_devices();
        break;

      case 'miwear_get_data':
        result = await this.wasmModule.miwear_get_data(
          args.addr || '',
          args.type || args.data_type || 'info'
        );
        break;

      case 'thirdpartyapp_get_list':
        result = await this.wasmModule.thirdpartyapp_get_list(args.addr || '');
        break;

      case 'thirdpartyapp_launch':
        result = await this.wasmModule.thirdpartyapp_launch(
          args.addr || '',
          args.package_name || args.packageName || '',
          args.page || ''
        );
        break;

      case 'thirdpartyapp_send_message':
        result = await this.wasmModule.thirdpartyapp_send_message(
          args.addr || '',
          args.package_name || args.packageName || '',
          args.data || ''
        );
        break;

      case 'thirdpartyapp_uninstall':
        result = await this.wasmModule.thirdpartyapp_uninstall(
          args.addr || '',
          args.package_name || args.packageName || ''
        );
        break;

      default:
        throw new Error(`不支持的命令: ${command}`);
    }

    console.log(`[WASM] ${command} 返回:`, result);
    return result;
  }
}

export default new WasmClient();
