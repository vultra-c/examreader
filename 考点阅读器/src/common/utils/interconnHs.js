/**
 * 考点阅读器 - Snapnotes 握手/心跳模块（tag="__hs__"）
 *
 * 与 Snapnotes-android 的 InterHandshake.kt 完全对齐：
 *   手机发 count=0 → 手环回 count=1(带本端 versionCode) → 手机回 count=2 → 握手完成
 *   握手完成后手机每 5s 发 count=0 心跳；手环收到任何握手包都应答保活
 *
 * 手机端 MIN_BAND_VERSION_CODE = 2，低于该值会弹版本不兼容弹窗。
 * 这里上报与 src/manifest.json versionCode 一致的常量（改版本时两处同步）。
 */
import { interconnModule } from './interconn.js'

// 与 manifest.json 的 versionCode 保持一致
// 与 manifest.json versionCode 保持一致（V26.7.1 = 260710）
const BAND_VERSION_CODE = 260710

const HS_TIMEOUT_MS = 10000   // 发出 count=1 后等 count>=2 的超时
const HEARTBEAT_MS = 5000     // 心跳周期（与手机端 PING_INTERVAL 对齐）

export default class interconnHs {
  static "__interconnModule__" = true;
  static name = '__hs__';

  hsTimeoutTimer = null;
  hbTimer = null;

  constructor({ addListener, send, setEventListener }) {
    this.send = send

    addListener((msg) => {
      this._handleHsMessage(msg)
    })

    // 链路断开时清理定时器
    setEventListener((event) => {
      if (event !== 'open') {
        this._cleanup()
      }
    })
  }

  _handleHsMessage(msg) {
    if (!msg || typeof msg.count !== 'number') return
    // 收到任何握手包都说明链路活着，清握手超时
    if (this.hsTimeoutTimer) {
      clearTimeout(this.hsTimeoutTimer)
      this.hsTimeoutTimer = null
    }
    if (msg.count === 0) {
      // 手机端首发握手：回 count=1 带本端版本，并启动握手超时（等 count>=2）
      this.send({ count: 1, version: BAND_VERSION_CODE })
      this._startHsTimeout()
    } else if (msg.count === 1) {
      // 对端重发场景兜底：直接回 count=2 并视为握手完成
      this.send({ count: 2 })
      this._startHeartbeat()
    } else {
      // count>=2：握手已成的保活包，刷新心跳
      this._startHeartbeat()
    }
  }

  _startHsTimeout() {
    if (this.hsTimeoutTimer) clearTimeout(this.hsTimeoutTimer)
    this.hsTimeoutTimer = setTimeout(() => {
      console.log('[HS] handshake timeout: no count>=2')
      this._cleanup()
    }, HS_TIMEOUT_MS)
  }

  _startHeartbeat() {
    if (this.hbTimer) {
      clearInterval(this.hbTimer)
      this.hbTimer = null
    }
    // 心跳：周期回 count=0，手机端会回 count=1 再触发本模块刷新，双向保活
    this.hbTimer = setInterval(() => {
      this.send({ count: 0, version: BAND_VERSION_CODE })
    }, HEARTBEAT_MS)
  }

  _cleanup() {
    if (this.hsTimeoutTimer) {
      clearTimeout(this.hsTimeoutTimer)
      this.hsTimeoutTimer = null
    }
    if (this.hbTimer) {
      clearInterval(this.hbTimer)
      this.hbTimer = null
    }
  }
}
