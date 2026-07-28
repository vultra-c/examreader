/**
 * 考点阅读器 - 数据管理器
 * 处理知识点树导航、分页、删除等功能
 */
import { knowledgeTree } from './knowledgeData.js'
import storage from '@system.storage'

const STORAGE_KEY_DELETED = 'KD_DATA_DELETED'
const STORAGE_KEY_INIT = 'KD_DATA_INIT'
const STORAGE_KEY_BT_CONTENT = 'KD_BT_CONTENT'  // 蓝牙传输内容存储键

// 每页大约的字符数（考虑手环屏幕336x480，字号26px，每行约11字）
// showSetting=true 时可见区域：102px(顶遮罩) ~ 378px(底遮罩) = 276px
// 30px行高 → 9行，296px文字宽 ÷ 26px ≈ 11字/行 → 9×11 = 99字
// 适当留余量，取 99
const CHARS_PER_PAGE = 99

// 获取已删除内容的ID集合
function getDeletedSet() {
  return new Promise((resolve) => {
    storage.get({
      key: STORAGE_KEY_DELETED,
      success: (data) => {
        if (data) {
          try {
            resolve(new Set(JSON.parse(data)))
          } catch (e) {
            resolve(new Set())
          }
        } else {
          resolve(new Set())
        }
      },
      fail: () => resolve(new Set())
    })
  })
}

// 保存已删除内容ID集合
function saveDeletedSet(deletedSet) {
  return new Promise((resolve) => {
    storage.set({
      key: STORAGE_KEY_DELETED,
      value: JSON.stringify(Array.from(deletedSet)),
      success: () => resolve(true),
      fail: () => resolve(false)
    })
  })
}

// ==================== 蓝牙传输内容存储 ====================

// 获取所有蓝牙传输内容
function getBluetoothContent() {
  return new Promise((resolve) => {
    storage.get({
      key: STORAGE_KEY_BT_CONTENT,
      success: (data) => {
        if (data) {
          try {
            resolve(JSON.parse(data))
          } catch (e) {
            resolve([])
          }
        } else {
          resolve([])
        }
      },
      fail: () => resolve([])
    })
  })
}

// 保存蓝牙传输内容列表
function saveBluetoothContentList(list) {
  return new Promise((resolve) => {
    storage.set({
      key: STORAGE_KEY_BT_CONTENT,
      value: JSON.stringify(list),
      success: () => resolve(true),
      fail: () => resolve(false)
    })
  })
}

// 在 knowledgeTree 中根据 ID 查找节点
function findNodeById(node, id) {
  if (node.id === id) return node
  if (node.children) {
    for (let i = 0; i < node.children.length; i++) {
      const found = findNodeById(node.children[i], id)
      if (found) return found
    }
  }
  return null
}

function findFolderById(id) {
  for (let i = 0; i < knowledgeTree.length; i++) {
    const node = findNodeById(knowledgeTree[i], id)
    if (node) return node
  }
  return null
}

// 根据路径数组获取节点
function getNodeByPath(path) {
  if (!path || path.length === 0) return null
  let node = knowledgeTree
  for (let i = 0; i < path.length; i++) {
    const idx = path[i]
    if (i === 0) {
      node = knowledgeTree[idx]
    } else {
      if (node && node.children && node.children[idx]) {
        node = node.children[idx]
      } else {
        return null
      }
    }
  }
  return node
}

// 根据路径字符串获取节点
function getNodeByPathStr(pathStr) {
  if (!pathStr) return null
  const path = pathStr.split(',').map(s => parseInt(s))
  return getNodeByPath(path)
}

// 获取某路径下文件夹的可见子项（过滤已删除的）
function getVisibleChildren(pathStr) {
  return new Promise((resolve) => {
    const node = getNodeByPathStr(pathStr)
    if (!node || node.type !== 'folder' || !node.children) {
      resolve([])
      return
    }
    getDeletedSet().then((deletedSet) => {
      const visible = node.children.filter(child => !deletedSet.has(child.id))
      resolve(visible)
    })
  })
}

// 分页内容：将长文本按字符数切分为多页
// 支持长行自动折行分页，避免单行超出页面容量
function splitContentIntoPages(content) {
  if (!content) return ['无内容']
  const pages = []
  let current = ''
  let currentLen = 0
  const lines = content.split('\n')

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]

    // 处理超长行：按 CHARS_PER_PAGE 拆分
    if (line.length > CHARS_PER_PAGE) {
      // 先把当前累积的内容推入 pages
      if (current.length > 0) {
        pages.push(current)
        current = ''
        currentLen = 0
      }
      // 拆分超长行
      let remaining = line
      while (remaining.length > CHARS_PER_PAGE) {
        pages.push(remaining.substring(0, CHARS_PER_PAGE))
        remaining = remaining.substring(CHARS_PER_PAGE)
      }
      current = remaining + '\n'
      currentLen = remaining.length + 1
      continue
    }

    if (currentLen + line.length + 1 > CHARS_PER_PAGE && current.length > 0) {
      pages.push(current)
      current = line + '\n'
      currentLen = line.length + 1
    } else {
      current += line + '\n'
      currentLen += line.length + 1
    }
  }
  if (current.length > 0) {
    pages.push(current)
  }
  return pages.length > 0 ? pages : ['无内容']
}

export default {
  /**
   * 初始化数据
   */
  initData() {
    storage.get({
      key: STORAGE_KEY_INIT,
      success: (data) => {
        if (!data) {
          storage.set({ key: STORAGE_KEY_INIT, value: 'true' })
          storage.set({ key: STORAGE_KEY_DELETED, value: '[]' })
        }
      },
      fail: () => {
        storage.set({ key: STORAGE_KEY_INIT, value: 'true' })
        storage.set({ key: STORAGE_KEY_DELETED, value: '[]' })
      }
    })
  },

  /**
   * 获取顶层文件夹列表
   * 包含内置文件夹 + 蓝牙传输文件夹（如果有蓝牙内容）
   */
  getTopLevelFolders() {
    return new Promise((resolve) => {
      getDeletedSet().then((deletedSet) => {
        const visible = knowledgeTree.filter(child => !deletedSet.has(child.id))

        // 检查是否有蓝牙传输内容
        getBluetoothContent().then((btList) => {
          if (btList && btList.length > 0) {
            visible.push({
              id: 'bt_folder',
              name: '蓝牙传输',
              type: 'folder',
              children: []
            })
          }
          resolve(visible)
        })
      })
    })
  },

  /**
   * 获取某路径下可见子项
   * 支持 "bt" 路径（蓝牙传输文件夹）
   */
  getVisibleChildren(pathStr) {
    return new Promise((resolve) => {
      // 蓝牙传输文件夹
      if (pathStr === 'bt') {
        getBluetoothContent().then((btList) => {
          const items = btList.map(item => ({
            id: item.id,
            name: item.name,
            type: 'content',
            content: item.content
          }))
          resolve(items)
        })
        return
      }

      // 内置文件夹
      const node = getNodeByPathStr(pathStr)
      if (!node || node.type !== 'folder' || !node.children) {
        resolve([])
        return
      }
      getDeletedSet().then((deletedSet) => {
        const visible = node.children.filter(child => !deletedSet.has(child.id))
        resolve(visible)
      })
    })
  },

  /**
   * 根据路径字符串获取节点
   */
  getNodeByPathStr,

  /**
   * 获取节点名称
   * 支持 "bt" 路径（蓝牙传输文件夹）
   */
  getNodeName(pathStr) {
    if (pathStr === 'bt') return '蓝牙传输'
    const node = getNodeByPathStr(pathStr)
    return node ? node.name : ''
  },

  /**
   * 获取内容节点的分页内容
   * 支持 bt_ 前缀的蓝牙传输内容
   */
  getReaderPages(pathStr) {
    return new Promise((resolve) => {
      // 蓝牙传输内容：ID 以 bt_ 开头
      if (pathStr && pathStr.startsWith('bt_')) {
        getBluetoothContent().then((btList) => {
          const item = btList.find(item => item.id === pathStr)
          if (item) {
            resolve(splitContentIntoPages(item.content))
          } else {
            resolve(['内容不存在'])
          }
        })
        return
      }

      // 内置内容
      const node = getNodeByPathStr(pathStr)
      if (!node || node.type !== 'content') {
        resolve(['无内容'])
        return
      }
      getDeletedSet().then((deletedSet) => {
        if (deletedSet.has(node.id)) {
          resolve(['该内容已被删除'])
          return
        }
        resolve(splitContentIntoPages(node.content))
      })
    })
  },

  /**
   * 删除单个考点内容
   * 支持 bt_ 前缀的蓝牙传输内容
   */
  deleteContent(pathStr) {
    return new Promise((resolve) => {
      // 蓝牙传输内容
      if (pathStr && pathStr.startsWith('bt_')) {
        getBluetoothContent().then((btList) => {
          const filtered = btList.filter(item => item.id !== pathStr)
          saveBluetoothContentList(filtered).then(() => resolve(true))
        })
        return
      }

      // 内置内容
      const node = getNodeByPathStr(pathStr)
      if (!node) {
        resolve(false)
        return
      }
      getDeletedSet().then((deletedSet) => {
        deletedSet.add(node.id)
        saveDeletedSet(deletedSet).then(() => resolve(true))
      })
    })
  },

  /**
   * 删除文件夹（将其ID加入已删除集合，同时递归删除所有子项）
   * 支持 "bt" 路径（清空所有蓝牙传输内容）
   */
  deleteFolder(pathStr) {
    return new Promise((resolve) => {
      // 蓝牙传输文件夹：清空所有蓝牙内容
      if (pathStr === 'bt') {
        saveBluetoothContentList([]).then(() => resolve(true))
        return
      }

      // 内置文件夹
      const node = getNodeByPathStr(pathStr)
      if (!node) {
        resolve(false)
        return
      }
      getDeletedSet().then((deletedSet) => {
        // 递归收集文件夹下所有节点的ID
        function collectIds(n) {
          deletedSet.add(n.id)
          if (n.children) {
            n.children.forEach(child => collectIds(child))
          }
        }
        collectIds(node)
        saveDeletedSet(deletedSet).then(() => resolve(true))
      })
    })
  },

  /**
   * 删除所有考点（包括蓝牙传输内容）
   */
  deleteAll() {
    return new Promise((resolve) => {
      const allIds = []
      function collect(node) {
        if (node.type === 'content') {
          allIds.push(node.id)
        } else if (node.type === 'folder' && node.children) {
          node.children.forEach(child => collect(child))
        }
      }
      knowledgeTree.forEach(node => collect(node))

      getDeletedSet().then((deletedSet) => {
        allIds.forEach(id => deletedSet.add(id))
        saveDeletedSet(deletedSet).then(() => {
          // 同时清空蓝牙传输内容
          saveBluetoothContentList([]).then(() => resolve(true))
        })
      })
    })
  },

  /**
   * 统计所有考点数量（包括蓝牙传输内容）
   */
  getTotalContentCount() {
    return new Promise((resolve) => {
      getDeletedSet().then((deletedSet) => {
        let count = 0
        function countNodes(node) {
          if (node.type === 'content' && !deletedSet.has(node.id)) {
            count++
          } else if (node.type === 'folder' && node.children) {
            node.children.forEach(child => countNodes(child))
          }
        }
        knowledgeTree.forEach(node => countNodes(node))

        // 加上蓝牙传输内容数量
        getBluetoothContent().then((btList) => {
          count += btList ? btList.length : 0
          resolve(count)
        })
      })
    })
  },

  /**
   * 获取考点占用的存储大小（字节，包括蓝牙传输内容）
   */
  getContentStorageSize() {
    return new Promise((resolve) => {
      getDeletedSet().then((deletedSet) => {
        let size = 0
        function calcSize(node) {
          if (node.type === 'content' && !deletedSet.has(node.id)) {
            size += (node.content || '').length
            size += (node.name || '').length
          } else if (node.type === 'folder' && node.children) {
            node.children.forEach(child => calcSize(child))
          }
        }
        knowledgeTree.forEach(node => calcSize(node))

        // 加上蓝牙传输内容大小
        getBluetoothContent().then((btList) => {
          if (btList) {
            btList.forEach(item => {
              size += (item.content || '').length
              size += (item.name || '').length
            })
          }
          resolve(size)
        })
      })
    })
  },

  /**
   * 格式化文件大小
   */
  formatFileSize(bytes) {
    if (!bytes || bytes === 0) return '0 B'
    const k = 1024
    const sizes = ['B', 'KB', 'MB', 'GB']
    const i = Math.floor(Math.log(bytes) / Math.log(k))
    return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i]
  },

  /**
   * 获取每页字符数
   */
  getCharsPerPage() {
    return CHARS_PER_PAGE
  },

  // ==================== 蓝牙传输功能 ====================

  /**
   * 保存蓝牙传输的 txt 内容
   * @param {string} filename 文件名（不含后缀）
   * @param {string} content  正文内容
   * @param {string} targetFolder 目标文件夹 ID（内置文件夹 ID 或 'bt_root'）
   */
  saveBluetoothContent(filename, content, targetFolder) {
    return new Promise((resolve) => {
      getBluetoothContent().then((btList) => {
        const id = 'bt_' + Date.now()
        const item = {
          id: id,
          name: filename,
          type: 'content',
          content: content,
          folder: targetFolder || 'bt_root',
          created: Date.now()
        }
        btList.push(item)
        saveBluetoothContentList(btList).then(() => {
          console.log('[DM] Bluetooth content saved: ' + filename + ' (' + content.length + ' chars)')
          resolve(id)
        })
      })
    })
  },

  /**
   * 获取文件夹树（供手机端请求使用）
   * 返回内置文件夹结构 + 蓝牙传输文件夹
   * 不包含正文内容，只包含 id/name/type/children
   */
  getFolderTreeForBluetooth() {
    return new Promise((resolve) => {
      getDeletedSet().then((deletedSet) => {
        // 构建内置文件夹树（不含正文）
        function buildTreeNode(node) {
          if (deletedSet.has(node.id)) return null
          const result = {
            id: node.id,
            name: node.name,
            type: node.type
          }
          if (node.type === 'folder' && node.children) {
            result.children = node.children
              .map(child => buildTreeNode(child))
              .filter(child => child !== null)
          }
          return result
        }

        const tree = knowledgeTree
          .map(node => buildTreeNode(node))
          .filter(node => node !== null)

        // 添加蓝牙传输文件夹
        getBluetoothContent().then((btList) => {
          if (btList && btList.length > 0) {
            const btNode = {
              id: 'bt_folder',
              name: '蓝牙传输',
              type: 'folder',
              children: btList.map(item => ({
                id: item.id,
                name: item.name,
                type: 'content'
              }))
            }
            tree.push(btNode)
          }
          resolve(tree)
        })
      })
    })
  },

  /**
   * 获取蓝牙传输内容列表（用于显示）
   */
  getBluetoothContentList() {
    return getBluetoothContent()
  },

  /**
   * 删除指定蓝牙传输内容
   */
  deleteBluetoothContent(id) {
    return new Promise((resolve) => {
      getBluetoothContent().then((btList) => {
        const filtered = btList.filter(item => item.id !== id)
        saveBluetoothContentList(filtered).then(() => resolve(true))
      })
    })
  },

  /**
   * 获取蓝牙传输内容数量
   */
  getBluetoothContentCount() {
    return new Promise((resolve) => {
      getBluetoothContent().then((btList) => {
        resolve(btList ? btList.length : 0)
      })
    })
  }
}
