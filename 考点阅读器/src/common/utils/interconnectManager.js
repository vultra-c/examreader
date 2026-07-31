/**
 * Interconnect Manager for Vela side
 *
 * Uses @system.interconnect to communicate with the Android companion app.
 * Handles connection state, chunked text file reception, and saving to storage.
 *
 * Transfer protocol (JSON messages over interconnect):
 *
 * Android -> Watch:
 *   {"type":"start","name":"filename","totalChunks":N,"wordCount":N}
 *   {"type":"chunk","index":N,"content":"text_chunk"}
 *   {"type":"end"}
 *   {"type":"cancel"}
 *
 * Watch -> Android:
 *   {"type":"ready"}
 *   {"type":"ack","index":N}
 *   {"type":"saved","name":"filename"}
 *   {"type":"error","message":"..."}
 *   {"type":"cancelled"}
 */

import interconnect from '@system.interconnect'
import storage from '@system.storage'

const STORAGE_KEY_BT_CONTENT = 'KD_BT_CONTENT'

// Chunk size (characters) - must match Android side
const CHUNK_SIZE = 8000

// State constants
const STATE_WAIT = 'wait'         // Waiting for connection
const STATE_READY = 'ready'       // Connected, waiting for file
const STATE_RECEIVING = 'receiving' // Receiving file chunks
const STATE_SAVED = 'saved'       // File saved successfully
const STATE_ERROR = 'error'       // Error occurred

// Singleton connection instance
let conn = null

// Current transfer context
let transferCtx = {
  name: '',
  totalChunks: 0,
  wordCount: 0,
  receivedChunks: [],
  receivedCount: 0,
  isTransferring: false
}

// Callbacks for UI updates
let stateCallback = null
let progressCallback = null

// Connection state
let connectionState = STATE_WAIT

/**
 * Initialize the interconnect connection.
 * Registers onmessage, onopen, onclose, onerror handlers.
 * Should be called once when the push page initializes.
 */
function init() {
  if (conn) {
    return
  }

  conn = interconnect.instance()

  // Check current connection state
  conn.getReadyState({
    success: (data) => {
      if (data.status === 1) {
        connectionState = STATE_READY
      } else {
        connectionState = STATE_WAIT
      }
      notifyState()
    },
    fail: () => {
      connectionState = STATE_WAIT
      notifyState()
    }
  })

  // Connection opened
  conn.onopen = function (data) {
    connectionState = STATE_READY
    if (!transferCtx.isTransferring) {
      notifyState()
    }
  }

  // Connection closed
  conn.onclose = function (data) {
    connectionState = STATE_WAIT
    if (transferCtx.isTransferring) {
      transferCtx.isTransferring = false
      transferCtx.receivedChunks = []
      notifyProgress(0, 0, '连接断开')
    }
    notifyState()
  }

  // Connection error
  conn.onerror = function (data) {
    if (!transferCtx.isTransferring) {
      connectionState = STATE_WAIT
      notifyState()
    }
  }

  // Message received from Android
  conn.onmessage = function (data) {
    handleMessage(data.data || data)
  }
}

/**
 * Send a JSON message to the Android side.
 */
function sendMessage(obj) {
  if (!conn) return
  try {
    conn.send({
      data: obj,
      success: function () {},
      fail: function () {}
    })
  } catch (e) {
    // Silently fail
  }
}

/**
 * Handle incoming message from Android.
 */
function handleMessage(rawData) {
  let msg = null
  if (typeof rawData === 'string') {
    try {
      msg = JSON.parse(rawData)
    } catch (e) {
      return
    }
  } else {
    msg = rawData
  }

  if (!msg || !msg.type) return

  switch (msg.type) {
    case 'ping':
      // Readiness check from Android: reply with pong
      sendMessage({ type: 'pong' })
      break
    case 'start':
      handleStart(msg)
      break
    case 'chunk':
      handleChunk(msg)
      break
    case 'end':
      handleEnd(msg)
      break
    case 'cancel':
      handleCancel(msg)
      break
    default:
      break
  }
}

/**
 * Handle "start" message: prepare for receiving a new file.
 */
function handleStart(msg) {
  transferCtx.name = msg.name || '未命名'
  transferCtx.totalChunks = msg.totalChunks || 0
  transferCtx.wordCount = msg.wordCount || 0
  transferCtx.receivedChunks = []
  transferCtx.receivedCount = 0
  transferCtx.isTransferring = true

  connectionState = STATE_RECEIVING
  notifyState()
  notifyProgress(0, transferCtx.totalChunks, '开始接收: ' + transferCtx.name)

  // Tell Android we're ready for chunks
  sendMessage({ type: 'ready' })
}

/**
 * Handle "chunk" message: accumulate the chunk and ack.
 */
function handleChunk(msg) {
  if (!transferCtx.isTransferring) {
    sendMessage({ type: 'error', message: '未在传输状态' })
    return
  }

  var index = msg.index
  var content = msg.content || ''

  transferCtx.receivedChunks[index] = content
  transferCtx.receivedCount++

  var percent = Math.floor((transferCtx.receivedCount / transferCtx.totalChunks) * 100)
  notifyProgress(transferCtx.receivedCount, transferCtx.totalChunks, '接收中 ' + percent + '%')

  // Acknowledge the chunk
  sendMessage({ type: 'ack', index: index })
}

/**
 * Handle "end" message: assemble all chunks and save to storage.
 */
function handleEnd(msg) {
  if (!transferCtx.isTransferring) return

  // Verify all chunks received
  if (transferCtx.receivedCount < transferCtx.totalChunks) {
    transferCtx.isTransferring = false
    connectionState = STATE_ERROR
    notifyState()
    sendMessage({ type: 'error', message: '分片不完整' })
    return
  }

  // Assemble full content
  var fullContent = transferCtx.receivedChunks.join('')

  // Save to bluetooth content storage
  saveReceivedContent(transferCtx.name, fullContent)
}

/**
 * Save received content to the bluetooth content list in storage.
 */
function saveReceivedContent(name, content) {
  // Generate a unique ID
  var id = 'bt_' + Date.now()

  // Read existing list, append, and save
  storage.get({
    key: STORAGE_KEY_BT_CONTENT,
    success: function (data) {
      var list = []
      if (data) {
        try {
          list = JSON.parse(data) || []
        } catch (e) {
          list = []
        }
      }
      list.push({
        id: id,
        name: name,
        content: content
      })
      storage.set({
        key: STORAGE_KEY_BT_CONTENT,
        value: JSON.stringify(list),
        success: function () {
          // Transfer complete
          transferCtx.isTransferring = false
          connectionState = STATE_SAVED
          notifyState()
          notifyProgress(transferCtx.totalChunks, transferCtx.totalChunks, '接收成功: ' + name)

          // Tell Android the file was saved
          sendMessage({ type: 'saved', name: name })

          // Reset to ready state after a short delay
          setTimeout(function () {
            if (connectionState === STATE_SAVED) {
              connectionState = STATE_READY
              notifyState()
            }
          }, 3000)
        },
        fail: function () {
          transferCtx.isTransferring = false
          connectionState = STATE_ERROR
          notifyState()
          sendMessage({ type: 'error', message: '保存失败' })
        }
      })
    },
    fail: function () {
      // No existing data, create new list
      var list = [{
        id: id,
        name: name,
        content: content
      }]
      storage.set({
        key: STORAGE_KEY_BT_CONTENT,
        value: JSON.stringify(list),
        success: function () {
          transferCtx.isTransferring = false
          connectionState = STATE_SAVED
          notifyState()
          notifyProgress(transferCtx.totalChunks, transferCtx.totalChunks, '接收成功: ' + name)
          sendMessage({ type: 'saved', name: name })

          setTimeout(function () {
            if (connectionState === STATE_SAVED) {
              connectionState = STATE_READY
              notifyState()
            }
          }, 3000)
        },
        fail: function () {
          transferCtx.isTransferring = false
          connectionState = STATE_ERROR
          notifyState()
          sendMessage({ type: 'error', message: '保存失败' })
        }
      })
    }
  })
}

/**
 * Handle "cancel" message from Android.
 */
function handleCancel(msg) {
  transferCtx.isTransferring = false
  transferCtx.receivedChunks = []
  transferCtx.receivedCount = 0
  connectionState = STATE_READY
  notifyState()
  notifyProgress(0, 0, '传输已取消')
  sendMessage({ type: 'cancelled' })
}

/**
 * Notify state change to UI callback.
 */
function notifyState() {
  if (stateCallback) {
    stateCallback(connectionState)
  }
}

/**
 * Notify progress change to UI callback.
 */
function notifyProgress(received, total, message) {
  if (progressCallback) {
    var percent = total > 0 ? Math.floor((received / total) * 100) : 0
    progressCallback(percent, received, total, message, transferCtx.name)
  }
}

/**
 * Clean up the interconnect connection.
 * Called when the push page is destroyed.
 */
function destroy() {
  // Note: interconnect connection is managed by the system,
  // we only need to clear our callbacks.
  stateCallback = null
  progressCallback = null
  transferCtx.isTransferring = false
  transferCtx.receivedChunks = []
}

/**
 * Get current connection state.
 */
function getState() {
  return connectionState
}

/**
 * Check if currently transferring.
 */
function isTransferring() {
  return transferCtx.isTransferring
}

/**
 * Set callbacks for UI updates.
 */
function setCallbacks(onState, onProgress) {
  stateCallback = onState
  progressCallback = onProgress
}

export default {
  init,
  destroy,
  getState,
  isTransferring,
  setCallbacks,
  STATE_WAIT,
  STATE_READY,
  STATE_RECEIVING,
  STATE_SAVED,
  STATE_ERROR,
  CHUNK_SIZE
}
