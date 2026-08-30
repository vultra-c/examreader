/**
 * 考点阅读器 - 数据管理器
 * 处理知识点树导航、分页、删除、阅读进度等功能
 *
 * 蓝牙传输内容采用「分 key 存储」：
 *   - KD_BT_META       : 元数据列表（不含正文）[{id,name,type,folder,parentId,created}]
 *   - KD_BT_FILE_{id} : 单个文件正文
 *   - KD_BT_CONTENT   : 旧版全量键（仅用于一次性迁移检测，迁移完成后删除）
 */
import { knowledgeTree } from './knowledgeData.js'
import { builtinExamples } from './builtinData.js'
import { parseContent, isSubjectSpecific, formatForDisplay } from './subjectParser.js'
import { parseKnowledgeJson } from './jsonParser.js'
import storage from '@system.storage'

const STORAGE_KEY_DELETED = 'KD_DATA_DELETED'
const STORAGE_KEY_INIT = 'KD_DATA_INIT'
const STORAGE_KEY_BT_CONTENT = 'KD_BT_CONTENT'              // 旧版全量键，保留用于迁移检测
const STORAGE_KEY_BT_META = 'KD_BT_META'
const STORAGE_KEY_BT_FILE_PREFIX = 'KD_BT_FILE_'            // + id（旧版单键，兼容读取）
const STORAGE_KEY_BT_CHUNK_PREFIX = 'KD_BT_C_'              // + id + '_' + chunkIndex（新版分块）
const STORAGE_KEY_READING_PROGRESS = 'KD_READING_PROGRESS'

// 分块存储参数
// 手环内存极小，单个 storage value 过大会导致 OOM 崩溃和存储系统损坏
// 每块最大 3000 字符，确保安全
const STORAGE_CHUNK_SIZE = 3000

// 分页参数（手环屏幕 336×480）
// 滚动区域 480px，上下内边距 20px → 440px 可用高度
// 文字宽 296px；默认字号 26px → floor(296/26)=11 字/行
// 行高 fontSize+4=30 → floor(440/30)=14 行/页
const DEFAULT_FONT_SIZE = 26
const SCREEN_TEXT_WIDTH = 296
const SCREEN_TEXT_HEIGHT = 440

// ---------------------------------------------------------------------------
// Pre-computed cache for built-in example pages.
// Built-in examples never change, so their formatted + paginated content is
// computed once at module load time instead of on every access.
// ---------------------------------------------------------------------------
const _builtinPagesCache = {}
const _builtinNameCache = {}
const _builtinFormattedCache = {}

function _precomputeBuiltinExamples() {
  for (let i = 0; i < builtinExamples.length; i++) {
    const item = builtinExamples[i]
    _builtinNameCache[item.id] = item.name
    let formatted = item.content
    if (isSubjectSpecific(item.content)) {
      const parsed = parseContent(item.content)
      formatted = formatForDisplay(parsed)
    }
    _builtinFormattedCache[item.id] = formatted
    _builtinPagesCache[item.id] = splitContentIntoPages(formatted)
  }
}

// ---------------------------------------------------------------------------
// getDeletedSet: read the deleted-ID set from storage once, then cache it
// in memory to avoid repeated storage reads on every list / search call.
// ---------------------------------------------------------------------------
let _deletedSetCache = null

// 获取已删除内容的ID集合（memory-cached after first read）
function getDeletedSet() {
  if (_deletedSetCache) {
    return Promise.resolve(_deletedSetCache)
  }
  return new Promise((resolve) => {
    storage.get({
      key: STORAGE_KEY_DELETED,
      success: (data) => {
        let result
        if (data) {
          try {
            result = new Set(JSON.parse(data))
          } catch (e) {
            result = new Set()
          }
        } else {
          result = new Set()
        }
        _deletedSetCache = result
        resolve(result)
      },
      fail: () => {
        _deletedSetCache = new Set()
        resolve(_deletedSetCache)
      }
    })
  })
}

// 保存已删除内容ID集合（updates cache）
function saveDeletedSet(deletedSet) {
  _deletedSetCache = deletedSet
  return new Promise((resolve) => {
    storage.set({
      key: STORAGE_KEY_DELETED,
      value: JSON.stringify(Array.from(deletedSet)),
      success: () => resolve(true),
      fail: () => resolve(false)
    })
  })
}

// ==================== 搜索辅助（条目缓存 + JSON 解析缓存） ====================

// 缓存 id → {text, lower}：搜索用展示文本及其小写形式。
// 避免重复 toLowerCase 全尺寸拷贝；按总字节数 FIFO 淘汰，
// 上限 300KB，大文件不会把缓存撑爆（手环内存极小）。
const _searchableTextCache = new Map()
let _searchCacheBytes = 0
const SEARCH_CACHE_MAX_BYTES = 300 * 1024

function _searchCacheSet(id, entry) {
  const bytes = (entry.text ? entry.text.length : 0) + (entry.lower ? entry.lower.length : 0)
  // 单条超过上限直接不缓存（避免反复淘汰全部条目仍装不下）
  if (bytes > SEARCH_CACHE_MAX_BYTES) return
  _searchCacheDelete(id)
  entry._bytes = bytes
  _searchableTextCache.set(id, entry)
  _searchCacheBytes += bytes
  while (_searchCacheBytes > SEARCH_CACHE_MAX_BYTES && _searchableTextCache.size > 1) {
    const oldestKey = _searchableTextCache.keys().next().value
    _searchCacheDelete(oldestKey)
  }
}

function _searchCacheGet(id) {
  const entry = _searchableTextCache.get(id)
  if (!entry) return null
  // touch：重插到末尾维持热度
  _searchableTextCache.delete(id)
  _searchableTextCache.set(id, entry)
  return entry
}

function _searchCacheDelete(id) {
  const entry = _searchableTextCache.get(id)
  if (entry) {
    _searchCacheBytes -= entry._bytes || 0
    if (_searchCacheBytes < 0) _searchCacheBytes = 0
    _searchableTextCache.delete(id)
  }
}

function _searchCacheClear() {
  _searchableTextCache.clear()
  _searchCacheBytes = 0
}

// JSON 知识点解析结果缓存（id → parseKnowledgeJson 结果）：
// JSON.parse 大文件代价高；阅读页与搜索共享同一份解析结果。
// FIFO 限 3 条，超出即淘汰，内存占用有界。
const _parsedJsonCache = new Map()
const PARSED_JSON_CACHE_MAX = 3

function _parsedJsonCacheSet(id, result) {
  if (_parsedJsonCache.has(id)) _parsedJsonCache.delete(id)
  _parsedJsonCache.set(id, result)
  while (_parsedJsonCache.size > PARSED_JSON_CACHE_MAX) {
    const oldestKey = _parsedJsonCache.keys().next().value
    _parsedJsonCache.delete(oldestKey)
  }
}

function _getParsedJson(id, content) {
  let result = _parsedJsonCache.get(id)
  if (result) {
    _parsedJsonCache.delete(id)
    _parsedJsonCache.set(id, result)
    return result
  }
  try {
    result = parseKnowledgeJson(content || '')
  } catch (e) {
    result = { ok: false, error: 'parse-fail' }
  }
  _parsedJsonCacheSet(id, result)
  return result
}

/**
 * 取某 TXT 文件的搜索条目 {text, lower}（带 id 键缓存）。
 * 重复搜索（逐字输入触发）时整本书只小写化一次，热点全在缓存命中。
 */
function _getSearchEntry(id, fmt, content) {
  const cached = _searchCacheGet(id)
  if (cached) return cached
  const text = content || ''
  const entry = { text: text, lower: text.toLowerCase() }
  _searchCacheSet(id, entry)
  return entry
}

// 关键词上下文片段
function _makeSnippet(text, pos, kwLen) {
  const start = Math.max(0, pos - 10)
  const end = Math.min(text.length, pos + kwLen + 20)
  return (start > 0 ? '...' : '') + text.substring(start, end) + (end < text.length ? '...' : '')
}

// 每个文件最多保留的命中数（同一文件重复命中分组展示，供任意选择跳转）
const MAX_MATCHES_PER_FILE = 12
// 搜索结果最多保留的文件数
const MAX_RESULT_FILES = 50

/**
 * TXT：在搜索条目上收集全部命中位置（截断到上限）
 * @returns {Array<{position:number, snippet:string}>}
 */
function _collectTxtMatches(entry, kw, max) {
  const matches = []
  let pos = 0
  const limit = max || MAX_MATCHES_PER_FILE
  const lower = entry.lower
  while (matches.length < limit) {
    pos = lower.indexOf(kw, pos)
    if (pos < 0) break
    matches.push({ position: pos, snippet: _makeSnippet(entry.text, pos, kw.length) })
    pos += kw.length
  }
  return matches
}

/**
 * JSON：在解析结果上按「科目→条目」定位命中（条目级跳转定位用）
 * @returns {Array<{position:-1, subject:string, entryIdx:number, entryTitle:string, snippet:string}>}
 */
function _collectJsonMatches(parsed, kw, max) {
  const matches = []
  const limit = max || MAX_MATCHES_PER_FILE
  if (!parsed || !parsed.ok || !parsed.data) return matches
  const data = parsed.data
  const subjects = Object.keys(data)
  for (let i = 0; i < subjects.length; i++) {
    const subject = subjects[i]
    const items = data[subject]
    if (!Array.isArray(items)) continue
    for (let j = 0; j < items.length; j++) {
      const it = items[j]
      const fields = [it.title || '', it.desc || '', it.raw || '']
      if (Array.isArray(it.points)) fields.push(it.points.join('\n'))
      if (Array.isArray(it.formulas)) fields.push(it.formulas.join('\n'))
      const haystack = fields.join('\n')
      const pos = haystack.toLowerCase().indexOf(kw)
      if (pos >= 0) {
        matches.push({
          position: -1,
          subject: subject,
          entryIdx: j,
          entryTitle: it.title || '',
          snippet: _makeSnippet(haystack, pos, kw.length)
        })
        if (matches.length >= limit) return matches
      }
    }
  }
  return matches
}

// 按 id 获取 JSON 解析结果（缓存命中零 I/O；未命中读正文并解析入缓存）
function _parsedJsonById(id) {
  return new Promise((resolve) => {
    const cached = _parsedJsonCache.get(id)
    if (cached) {
      _parsedJsonCache.delete(id)
      _parsedJsonCache.set(id, cached)
      resolve(cached)
      return
    }
    getBluetoothFileContent(id).then((content) => resolve(_getParsedJson(id, content)))
  })
}

/**
 * 对单个蓝牙文件执行搜索，返回分组结果 {name, path, fmt, type, matches}
 * TXT 命中带字符位置；JSON 命中带 科目/条目 定位；文件名为空数组。
 * 命中缓存时跳过正文读取（大文件重复搜索从「读全部块」降为「内存命中」）。
 */
function _searchOneBtFile(item, kw) {
  return new Promise((resolve) => {
    const finish = (matches) => resolve({
      name: item.name,
      path: item.id,
      fmt: item.fmt || '',
      type: 'bluetooth',
      matches: matches
    })
    if (item.fmt === 'json') {
      _parsedJsonById(item.id).then((parsed) => {
        finish(_collectJsonMatches(parsed, kw))
      })
      return
    }
    const cachedEntry = _searchCacheGet(item.id)
    if (cachedEntry) {
      finish(_collectTxtMatches(cachedEntry, kw))
      return
    }
    getBluetoothFileContent(item.id).then((content) => {
      finish(_collectTxtMatches(_getSearchEntry(item.id, '', content), kw))
    })
  })
}

// ==================== 蓝牙传输内容存储（分 key） ====================

// ==================== 阅读进度（内存镜像 + 防抖落盘） ====================

// 进度对象常驻内存镜像：get/save 不再每次读 storage；
// 落盘用 trailing 防抖合并写，翻页高频场景 I/O 降一到两个数量级。
let _progressMirror = null
let _progressFlushTimer = null

function _loadProgressMirror() {
  if (_progressMirror) return Promise.resolve(_progressMirror)
  return new Promise((resolve) => {
    storage.get({
      key: STORAGE_KEY_READING_PROGRESS,
      success: (data) => {
        let obj = {}
        if (data) {
          try { obj = JSON.parse(data) } catch (e) { obj = {} }
        }
        if (!obj || typeof obj !== 'object') obj = {}
        _progressMirror = obj
        resolve(_progressMirror)
      },
      fail: () => {
        _progressMirror = {}
        resolve(_progressMirror)
      }
    })
  })
}

function _scheduleProgressFlush() {
  if (_progressFlushTimer) return
  _progressFlushTimer = setTimeout(() => {
    _progressFlushTimer = null
    if (!_progressMirror) return
    storage.set({
      key: STORAGE_KEY_READING_PROGRESS,
      value: JSON.stringify(_progressMirror)
    })
  }, 800)
}


// 内存缓存：元数据列表（不含正文）
let _btMetaCache = null
// 内存缓存：小文件正文 LRU（id → content；仅 ≤60KB 文件入缓存，总上限 200KB）
// 大文件（>60KB）保持流式读取不入缓存，避免 OOM。
const _btFileCache = new Map()
let _btFileCacheBytes = 0
const BT_FILE_CACHE_ITEM_MAX = 60 * 1024
const BT_FILE_CACHE_TOTAL_MAX = 200 * 1024

function _btFileCacheSet(id, content) {
  if (!content || content.length > BT_FILE_CACHE_ITEM_MAX) return
  if (_btFileCache.has(id)) _btFileCache.delete(id)
  _btFileCache.set(id, content)
  _btFileCacheBytes += content.length
  while (_btFileCacheBytes > BT_FILE_CACHE_TOTAL_MAX && _btFileCache.size > 1) {
    const oldestKey = _btFileCache.keys().next().value
    const oldestVal = _btFileCache.get(oldestKey)
    _btFileCache.delete(oldestKey)
    _btFileCacheBytes -= oldestVal ? oldestVal.length : 0
  }
}

function _btFileCacheDelete(id) {
  const val = _btFileCache.get(id)
  if (_btFileCache.delete(id) && val) {
    _btFileCacheBytes -= val.length
    if (_btFileCacheBytes < 0) _btFileCacheBytes = 0
  }
}

function _btFileCacheClear() {
  _btFileCache.clear()
  _btFileCacheBytes = 0
}

// 章节目录缓存（参考 com.bandbbs.ebook 的分章模型：
// 只保存 {title,start} 轻量索引，正文按需读取）；FIFO 限容防长会话堆积
const _btChaptersCache = new Map()
const BT_CHAPTERS_CACHE_MAX = 8

function _btChaptersCacheSet(id, result) {
  if (_btChaptersCache.has(id)) _btChaptersCache.delete(id)
  _btChaptersCache.set(id, result)
  while (_btChaptersCache.size > BT_CHAPTERS_CACHE_MAX) {
    const oldestKey = _btChaptersCache.keys().next().value
    _btChaptersCache.delete(oldestKey)
  }
}
// 蓝牙内容分页缓存 Map<id_fontSize, pages>
// 分页数组≈整书文本体积，按总字节数 FIFO 淘汰（上限 320KB），
// 多本书 × 多字号时内存占用有界；同时保留「当前书」级别的缓存命中。
const _btPagesCache = new Map()
let _btPagesCacheBytes = 0
const BT_PAGES_CACHE_MAX_BYTES = 320 * 1024

function _btPagesCacheSet(cacheKey, pages) {
  let bytes = 0
  if (pages) {
    for (let i = 0; i < pages.length; i++) {
      bytes += pages[i] ? pages[i].length : 0
    }
  }
  // 单本书分页超过上限不缓存（大书每次打开重分页，避免撑爆内存）
  if (bytes > BT_PAGES_CACHE_MAX_BYTES) return
  _btPagesCacheDelete(cacheKey)
  _btPagesCache.set(cacheKey, { pages: pages, bytes: bytes })
  _btPagesCacheBytes += bytes
  while (_btPagesCacheBytes > BT_PAGES_CACHE_MAX_BYTES && _btPagesCache.size > 1) {
    const oldestKey = _btPagesCache.keys().next().value
    _btPagesCacheDelete(oldestKey)
  }
}

function _btPagesCacheGet(cacheKey) {
  const entry = _btPagesCache.get(cacheKey)
  if (!entry) return null
  // touch：重插到末尾维持热度
  _btPagesCache.delete(cacheKey)
  _btPagesCache.set(cacheKey, entry)
  return entry.pages
}

function _btPagesCacheDelete(cacheKey) {
  const entry = _btPagesCache.get(cacheKey)
  if (entry) {
    _btPagesCacheBytes -= entry.bytes || 0
    if (_btPagesCacheBytes < 0) _btPagesCacheBytes = 0
    _btPagesCache.delete(cacheKey)
  }
}

function _btPagesCacheClear() {
  _btPagesCache.clear()
  _btPagesCacheBytes = 0
}
// 迁移单例 Promise，保证只跑一次
let _btMigrationPromise = null

// 读取旧版全量数据（仅用于迁移）
function _readLegacyBtContent() {
  return new Promise((resolve) => {
    storage.get({
      key: STORAGE_KEY_BT_CONTENT,
      success: (data) => {
        let result = []
        if (data) {
          try { result = JSON.parse(data) } catch (e) { result = [] }
        }
        resolve(result)
      },
      fail: () => resolve([])
    })
  })
}

// 将旧版 KD_BT_CONTENT 拆分为 KD_BT_META + KD_BT_FILE_{id}
function _migrateLegacyToSplit(legacyList) {
  return new Promise((resolve) => {
    const metaList = legacyList.map(item => ({
      id: item.id,
      name: item.name,
      type: item.type || 'content',
      folder: item.folder,
      parentId: item.parentId,
      created: item.created
    }))
    let pending = legacyList.length
    const finish = () => {
      pending--
      if (pending <= 0) {
        // 所有正文写完，最后写 meta（作为迁移完成标志）
        _btMetaCache = metaList
        storage.set({
          key: STORAGE_KEY_BT_META,
          value: JSON.stringify(metaList),
          success: () => {
            // 迁移完成，删除旧版全量键释放空间
            storage.delete({ key: STORAGE_KEY_BT_CONTENT })
            resolve()
          },
          fail: () => resolve()
        })
      }
    }
    legacyList.forEach((item) => {
      const content = item.content || ''
      storage.set({
        key: STORAGE_KEY_BT_FILE_PREFIX + item.id,
        value: content,
        success: finish,
        fail: finish
      })
    })
  })
}

// 确保已完成旧版 → 分 key 迁移（只执行一次）
function ensureBtMigrated() {
  if (_btMigrationPromise) return _btMigrationPromise
  _btMigrationPromise = new Promise((resolve) => {
    storage.get({
      key: STORAGE_KEY_BT_META,
      success: (metaData) => {
        // KD_BT_META 已存在，无需迁移
        if (metaData) {
          resolve(false)
          return
        }
        // KD_BT_META 不存在，检查是否有旧版数据需要迁移
        _readLegacyBtContent().then((legacyList) => {
          if (!legacyList || legacyList.length === 0) {
            // 无旧数据：写入空 meta 占位，避免反复检测
            _btMetaCache = []
            storage.set({ key: STORAGE_KEY_BT_META, value: '[]' })
            resolve(false)
            return
          }
          _migrateLegacyToSplit(legacyList).then(() => resolve(true))
        })
      },
      fail: () => resolve(false)
    })
  })
  return _btMigrationPromise
}

// 永久过滤掉名为"蓝牙传输"/"Bluetooth Transfer"的旧包装文件夹
// 在读取层直接排除，确保该文件夹永远不会出现在任何返回数据中
function _filterOutLegacyWrapper(list) {
  if (!list || list.length === 0) return list
  return list.filter(item =>
    !(item.type === 'folder' &&
      (item.name === '蓝牙传输' || item.name === 'Bluetooth Transfer'))
  )
}

// 获取蓝牙元数据列表（不含正文，memory-cached）
// 读取时永久过滤掉"蓝牙传输"包装文件夹
function getBluetoothMeta() {
  if (_btMetaCache) {
    return Promise.resolve(_btMetaCache)
  }
  return new Promise((resolve) => {
    ensureBtMigrated().then(() => {
      if (_btMetaCache) {
        resolve(_btMetaCache)
        return
      }
      storage.get({
        key: STORAGE_KEY_BT_META,
        success: (data) => {
          let result = []
          if (data) {
            try { result = JSON.parse(data) } catch (e) { result = [] }
          }
          // 永久过滤掉"蓝牙传输"包装文件夹
          result = _filterOutLegacyWrapper(result)
          _btMetaCache = result
          resolve(result)
        },
        fail: () => {
          _btMetaCache = []
          resolve(_btMetaCache)
        }
      })
    })
  })
}

// 保存蓝牙元数据列表（updates cache）
// 写入前永久清除"蓝牙传输"包装文件夹，确保不会持久化到存储
function saveBluetoothMeta(list) {
  // 写入前永久清除"蓝牙传输"文件夹
  list = _filterOutLegacyWrapper(list)
  _btMetaCache = list
  return new Promise((resolve) => {
    storage.set({
      key: STORAGE_KEY_BT_META,
      value: JSON.stringify(list),
      success: () => resolve(true),
      fail: () => resolve(false)
    })
  })
}

// 获取单个文件正文（memory-cached）
// 支持新版分块存储和旧版单键存储
function getBluetoothFileContent(id) {
  if (_btFileCache.has(id)) {
    return Promise.resolve(_btFileCache.get(id))
  }
  return new Promise((resolve) => {
    ensureBtMigrated().then(() => {
      if (_btFileCache.has(id)) {
        resolve(_btFileCache.get(id))
        return
      }
      // 先尝试读取分块存储（新版）
      _readChunkedContent(id).then((content) => {
        if (content !== null) {
          // 小文件入 LRU 缓存（阅读/搜索/章节复用零 I/O）；大文件不入缓存
          _btFileCacheSet(id, content)
          resolve(content)
        } else {
          // 分块不存在，回退到旧版单键
          storage.get({
            key: STORAGE_KEY_BT_FILE_PREFIX + id,
            success: (data) => {
              const content = data || ''
              _btFileCacheSet(id, content)
              resolve(content)
            },
            fail: () => {
              resolve('')
            }
          })
        }
      })
    })
  })
}

// 读取分块存储的内容
// 返回 null 表示没有分块数据（需要回退到旧版）
// 逐块串行读取，避免大量并发 storage.get 导致 OOM
function _readChunkedContent(id) {
  return new Promise((resolve) => {
    // 先读取块数
    storage.get({
      key: STORAGE_KEY_BT_CHUNK_PREFIX + id + '_count',
      success: (countStr) => {
        const count = parseInt(countStr)
        if (!count || count <= 0 || isNaN(count)) {
          resolve(null) // 没有分块数据
          return
        }
        // 逐块串行读取并拼接，避免并发读取大量块导致 OOM
        const chunks = new Array(count)
        let readIndex = 0
        let failed = false

        function readNext() {
          if (failed) return
          if (readIndex >= count) {
            // 全部读取完成，拼接
            resolve(chunks.join(''))
            return
          }
          const chunkKey = STORAGE_KEY_BT_CHUNK_PREFIX + id + '_' + readIndex
          storage.get({
            key: chunkKey,
            success: (data) => {
              chunks[readIndex] = data || ''
              readIndex++
              readNext()
            },
            fail: () => {
              if (!failed) {
                failed = true
                resolve(null) // 读取失败，回退到旧版
              }
            }
          })
        }
        readNext()
      },
      fail: () => resolve(null) // 没有分块计数键，回退到旧版
    })
  })
}

// 将内容分块写入存储
// 每块最大 STORAGE_CHUNK_SIZE 字符，避免单个 value 过大导致 OOM
// 逐块串行写入，避免大量并发 storage.set 导致存储系统崩溃
function _writeChunkedContent(id, content) {
  return new Promise((resolve) => {
    if (!content || content.length === 0) {
      // 空内容：写入 count=0
      storage.set({
        key: STORAGE_KEY_BT_CHUNK_PREFIX + id + '_count',
        value: '0',
        success: () => resolve(true),
        fail: () => resolve(false)
      })
      return
    }
    // 将内容分成块
    const chunks = []
    for (let i = 0; i < content.length; i += STORAGE_CHUNK_SIZE) {
      chunks.push(content.substring(i, Math.min(i + STORAGE_CHUNK_SIZE, content.length)))
    }
    const totalChunks = chunks.length

    // 先写入块数，作为标志位
    storage.set({
      key: STORAGE_KEY_BT_CHUNK_PREFIX + id + '_count',
      value: String(totalChunks),
      success: () => {
        // 逐块串行写入，避免并发过多导致存储系统崩溃
        let writeIndex = 0
        let failed = false

        function writeNext() {
          if (failed) return
          if (writeIndex >= totalChunks) {
            // 全部写入成功
            resolve(true)
            return
          }
          const chunkKey = STORAGE_KEY_BT_CHUNK_PREFIX + id + '_' + writeIndex
          storage.set({
            key: chunkKey,
            value: chunks[writeIndex],
            success: () => {
              writeIndex++
              writeNext()
            },
            fail: () => {
              if (!failed) {
                failed = true
                // 清理已写入的分块
                for (let i = 0; i < totalChunks; i++) {
                  storage.delete({ key: STORAGE_KEY_BT_CHUNK_PREFIX + id + '_' + i })
                }
                storage.delete({ key: STORAGE_KEY_BT_CHUNK_PREFIX + id + '_count' })
                resolve(false)
              }
            }
          })
        }
        writeNext()
      },
      fail: () => resolve(false)
    })
  })
}

// 流式分页：逐块读取存储，边读边分页，避免将整个大文件加载到内存
// 逐块串行读取，每读完一块就尝试分页，保持跨块文本的行完整性
function _streamPaginate(id, chunkCount, fontSize) {
  return new Promise((resolve) => {
    const fs = fontSize || DEFAULT_FONT_SIZE
    const charsPerLine = Math.max(1, Math.floor(SCREEN_TEXT_WIDTH / fs))
    const maxLines = Math.max(1, Math.floor(SCREEN_TEXT_HEIGHT / (fs + 4)))

    const pages = []
    let currentPage = ''
    let lineCount = 0
    let pendingLine = '' // 跨块的未完成行
    let readIndex = 0
    let failed = false

    // 将文本分页（处理已有的行列表）
    function processLines(lines) {
      for (let i = 0; i < lines.length; i++) {
        let line = lines[i]
        // 第一行与上一块的 pendingLine 拼接
        if (i === 0 && pendingLine !== '') {
          line = pendingLine + line
          pendingLine = ''
        }
        // 最后一行如果不以换行结尾，暂存为 pendingLine
        if (i === lines.length - 1) {
          pendingLine = line
          break
        }

        // 处理空行
        if (line.length === 0) {
          if (lineCount >= maxLines && currentPage.length > 0) {
            pages.push(currentPage)
            currentPage = ''
            lineCount = 0
          }
          currentPage += '\n'
          lineCount++
          continue
        }

        // 将超长行拆分为多行
        let remaining = line
        const subLines = []
        while (remaining.length > charsPerLine) {
          subLines.push(remaining.substring(0, charsPerLine))
          remaining = remaining.substring(charsPerLine)
        }
        subLines.push(remaining)

        for (let j = 0; j < subLines.length; j++) {
          if (lineCount >= maxLines && currentPage.length > 0) {
            pages.push(currentPage)
            currentPage = ''
            lineCount = 0
          }
          currentPage += subLines[j] + '\n'
          lineCount++
        }
      }
    }

    function readNext() {
      if (failed) return
      if (readIndex >= chunkCount) {
        // 处理最后剩余的 pendingLine
        if (pendingLine !== '') {
          processLines([pendingLine, ''])
        }
        if (currentPage.length > 0) {
          pages.push(currentPage)
        }
        resolve(pages.length > 0 ? pages : ['无内容'])
        return
      }
      const chunkKey = STORAGE_KEY_BT_CHUNK_PREFIX + id + '_' + readIndex
      storage.get({
        key: chunkKey,
        success: (data) => {
          const chunk = data || ''
          if (chunk.length > 0) {
            const lines = chunk.split('\n')
            processLines(lines)
          }
          readIndex++
          readNext()
        },
        fail: () => {
          if (!failed) {
            failed = true
            // 读取失败，返回已有的页面
            if (currentPage.length > 0) {
              pages.push(currentPage)
            }
            resolve(pages.length > 0 ? pages : ['无内容'])
          }
        }
      })
    }
    readNext()
  })
}

// 将文本按行数分段（无缝模式 list 虚拟渲染使用）
function _splitIntoSegments(content, linesPerSegment) {
  if (!content || content.length === 0) return ['无内容']
  const lines = content.split('\n')
  const segments = []
  let current = []
  for (let i = 0; i < lines.length; i++) {
    current.push(lines[i])
    if (current.length >= linesPerSegment) {
      segments.push(current.join('\n'))
      current = []
    }
  }
  if (current.length > 0) {
    segments.push(current.join('\n'))
  }
  return segments.length > 0 ? segments : ['无内容']
}

// 流式分段：逐块读取存储，边读边分段，避免将整个大文件加载到内存
// 用于无缝模式的 list 虚拟渲染，每个分段作为 list-item
function _streamSegments(id, chunkCount, linesPerSegment) {
  return new Promise((resolve) => {
    const lps = linesPerSegment || 20
    const segments = []
    let currentLines = []
    let pendingLine = ''
    let readIndex = 0

    function processChunk(chunk) {
      const lines = chunk.split('\n')
      for (let i = 0; i < lines.length; i++) {
        let line = lines[i]
        if (i === 0 && pendingLine !== '') {
          line = pendingLine + line
          pendingLine = ''
        }
        if (i === lines.length - 1) {
          pendingLine = line
          break
        }
        currentLines.push(line)
        if (currentLines.length >= lps) {
          segments.push(currentLines.join('\n'))
          currentLines = []
        }
      }
    }

    function readNext() {
      if (readIndex >= chunkCount) {
        if (pendingLine !== '') {
          currentLines.push(pendingLine)
        }
        if (currentLines.length > 0) {
          segments.push(currentLines.join('\n'))
        }
        resolve(segments.length > 0 ? segments : ['无内容'])
        return
      }
      storage.get({
        key: STORAGE_KEY_BT_CHUNK_PREFIX + id + '_' + readIndex,
        success: (data) => {
          if (data && data.length > 0) {
            processChunk(data)
          }
          readIndex++
          readNext()
        },
        fail: () => {
          if (pendingLine !== '') {
            currentLines.push(pendingLine)
          }
          if (currentLines.length > 0) {
            segments.push(currentLines.join('\n'))
          }
          resolve(segments.length > 0 ? segments : ['无内容'])
        }
      })
    }
    readNext()
  })
}

// 按字符位置定位页码的精确口径：
// 翻页判定与 splitContentIntoPages 完全一致（每 subCount 个显示行翻页）；
// 偏移推进与原文逐字节一致：每个原始行推进 L+1（L 个字符 + 换行符），
// 折行只是显示层切分，不额外虚构换行 —— 旧实现按折行子行各 +1，
// 统计偏移随文本长度虚增，长文跳转误差可达数页。
// 目标字符落在某原始行内即返回该行所在页（floor(显示行号 / maxLines)）。
function _findPageInContent(content, charPosition, charsPerLine, maxLines) {
  let lineCount = 0
  let currentPage = 0
  let globalCharPos = 0 // 当前原始行在原文中的精确起始偏移
  if (!content) return 0
  const lines = content.split('\n')
  for (let i = 0; i < lines.length; i++) {
    const L = lines[i].length
    // 目标落在本原始行内：所在页即当前页
    if (charPosition >= globalCharPos &&
      (L === 0 ? charPosition === globalCharPos : charPosition < globalCharPos + L)) {
      return currentPage
    }
    const subCount = L === 0 ? 1 : Math.ceil(L / charsPerLine)
    for (let j = 0; j < subCount; j++) {
      if (lineCount >= maxLines) {
        currentPage++
        lineCount = 0
      }
      lineCount++
    }
    globalCharPos += L + 1 // L 个字符 + 换行符
  }
  return currentPage
}

// 流式按字符位置定位页码（与 _findPageInContent 同一口径）：
// 逐块读取并累计全局字符偏移，命中即提前结束，大文件跳转不把整本书读进内存。
function _streamFindPageByChar(id, chunkCount, charPosition, fontSize) {
  return new Promise((resolve) => {
    const fs = fontSize || DEFAULT_FONT_SIZE
    const charsPerLine = Math.max(1, Math.floor(SCREEN_TEXT_WIDTH / fs))
    const maxLines = Math.max(1, Math.floor(SCREEN_TEXT_HEIGHT / (fs + 4)))
    let lineCount = 0
    let currentPage = 0
    let globalCharPos = 0
    let pendingLine = ''
    let readIndex = 0
    let done = false

    function finish(page) {
      if (done) return
      done = true
      resolve(page)
    }

    // 喂入一个原始行（口径同 _findPageInContent）；返回 false 表示已定位可停止
    function feedLine(line) {
      const L = line.length
      if (charPosition >= globalCharPos &&
        (L === 0 ? charPosition === globalCharPos : charPosition < globalCharPos + L)) {
        finish(currentPage)
        return false
      }
      const subCount = L === 0 ? 1 : Math.ceil(L / charsPerLine)
      for (let j = 0; j < subCount; j++) {
        if (lineCount >= maxLines) {
          currentPage++
          lineCount = 0
        }
        lineCount++
      }
      globalCharPos += L + 1
      return true
    }

    function processChunkLines(lines) {
      for (let i = 0; i < lines.length; i++) {
        let line = lines[i]
        if (i === 0 && pendingLine !== '') {
          line = pendingLine + line
          pendingLine = ''
        }
        if (i === lines.length - 1) {
          pendingLine = line
          break
        }
        if (!feedLine(line)) return false
      }
      return true
    }

    function readNext() {
      if (done) return
      if (readIndex >= chunkCount) {
        if (pendingLine !== '') feedLine(pendingLine)
        finish(currentPage)
        return
      }
      storage.get({
        key: STORAGE_KEY_BT_CHUNK_PREFIX + id + '_' + readIndex,
        success: (data) => {
          if (data && data.length > 0) {
            if (!processChunkLines(data.split('\n'))) return
          }
          readIndex++
          readNext()
        },
        fail: () => finish(currentPage)
      })
    }
    readNext()
  })
}

// 删除单个文件正文键（同步清缓存）
// 同时删除新版分块键和旧版单键
function _deleteBtFileKey(id) {
  _btFileCacheDelete(id)
  _searchCacheDelete(id)
  _parsedJsonCache.delete(id)
  return new Promise((resolve) => {
    let pending = 1 // 旧版单键
    const done = () => {
      pending--
      if (pending <= 0) resolve(true)
    }
    // 删除旧版单键
    storage.delete({
      key: STORAGE_KEY_BT_FILE_PREFIX + id,
      success: done,
      fail: done
    })
    // 查找并删除新版分块键（需要先读 count）
    storage.get({
      key: STORAGE_KEY_BT_CHUNK_PREFIX + id + '_count',
      success: (countStr) => {
        const count = parseInt(countStr)
        if (!count || count <= 0 || isNaN(count)) return
        pending += count + 1 // count 个分块 + 1 个 count 键
        // 删除 count 键
        storage.delete({
          key: STORAGE_KEY_BT_CHUNK_PREFIX + id + '_count',
          success: done,
          fail: done
        })
        // 删除各分块
        for (let i = 0; i < count; i++) {
          storage.delete({
            key: STORAGE_KEY_BT_CHUNK_PREFIX + id + '_' + i,
            success: done,
            fail: done
          })
        }
      },
      fail: () => {} // 没有分块键，忽略
    })
  })
}

// 清除某 id 的所有分页缓存
function _clearBtPagesCacheForId(id) {
  const keysToDelete = []
  _btPagesCache.forEach((value, key) => {
    if (key.indexOf(id + '_') === 0) keysToDelete.push(key)
  })
  for (let i = 0; i < keysToDelete.length; i++) {
    _btPagesCacheDelete(keysToDelete[i])
  }
  // 章节目录缓存同步失效
  _btChaptersCache.delete(id)
}

// ==================== 章节分段（学习 com.bandbbs.ebook ChapterSplitter） ====================

// 章节标题识别规则（保守策略，避免把「1. xxx」这类普通考点行误判为章节）：
// 1. 第X章/卷/节/部/篇/回/本（含中文数字与阿拉伯数字）
// 2. 番外
// 3. 英文 Chapter N
const CHAPTER_TITLE_PATTERNS = [
  /^第\s{0,1}[一二三四五六七八九十百千万零〇\d]+\s{0,1}(章|卷|节|部|篇|回|本)(.{0,30})$/,
  /^番外\s{0,2}[一二三四五六七八九十百千万零〇\d]*.{0,30}$/,
  /^\s*(Chapter|CHAPTER)\s+\d+.*$/
]

// 无章节结构时的兜底分段大小（字符），约一屏半的内容
const CHAPTER_FALLBACK_CHARS = 3500

function isChapterHeading(line) {
  const t = line.trim()
  if (t.length === 0 || t.length > 40) return false
  for (let i = 0; i < CHAPTER_TITLE_PATTERNS.length; i++) {
    if (CHAPTER_TITLE_PATTERNS[i].test(t)) return true
  }
  return false
}

/**
 * 构建章节索引：只记录每章的起始偏移量，不复制正文，内存占用极小。
 * 有明确章节标题按标题切；检测不到章节结构时按固定字数在换行处兜底切段
 * （对应电子书「按字数分章」策略）。单章内容返回空数组表示无目录。
 */
function buildChapterIndex(content) {
  if (!content || content.length === 0) return []
  const lines = content.split('\n')
  const chapters = []
  let offset = 0
  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]
    if (isChapterHeading(line)) {
      chapters.push({ title: line.trim(), start: offset })
    }
    offset += line.length + 1
  }

  if (chapters.length >= 2) {
    // 前言：第一章之前若有实质内容，补一节
    if (chapters[0].start > 200) {
      chapters.unshift({ title: '前言', start: 0 })
    }
    return chapters
  }

  // 兜底：按固定字数在换行边界切段
  const total = content.length
  if (total <= CHAPTER_FALLBACK_CHARS) return []
  const fallback = []
  let start = 0
  let idx = 1
  while (start < total) {
    fallback.push({ title: '第 ' + idx + ' 节', start: start })
    idx++
    let next = start + CHAPTER_FALLBACK_CHARS
    if (next < total) {
      // 对齐到下一个换行处，避免切断句子
      const nl = content.indexOf('\n', next)
      if (nl >= 0 && nl < next + 300) next = nl + 1
    }
    start = next
  }
  return fallback.length > 1 ? fallback : []
}

// 根据路径数组获取节点（保留用于内置树路径解析；knowledgeTree 为空时返回 null）
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

// 分页内容：按行数切分，确保文字铺满全屏
// 超长行自动折行，每行最多 charsPerLine 字符
// fontSize 可选，默认 26（对应 11 字/行、15 行/页）
function splitContentIntoPages(content, fontSize) {
  if (!content) return ['无内容']
  const fs = fontSize || DEFAULT_FONT_SIZE
  const charsPerLine = Math.max(1, Math.floor(SCREEN_TEXT_WIDTH / fs))
  const maxLines = Math.max(1, Math.floor(SCREEN_TEXT_HEIGHT / (fs + 4)))
  const pages = []
  let current = ''
  let lineCount = 0
  const lines = content.split('\n')

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]

    // 处理空行：空行占一行
    if (line.length === 0) {
      if (lineCount >= maxLines && current.length > 0) {
        pages.push(current)
        current = ''
        lineCount = 0
      }
      current += '\n'
      lineCount++
      continue
    }

    // 将超长行拆分为多行（每行最多 charsPerLine 字符）
    let remaining = line
    const subLines = []
    while (remaining.length > charsPerLine) {
      subLines.push(remaining.substring(0, charsPerLine))
      remaining = remaining.substring(charsPerLine)
    }
    subLines.push(remaining)

    // 逐行添加，控制每页行数
    for (let j = 0; j < subLines.length; j++) {
      if (lineCount >= maxLines && current.length > 0) {
        pages.push(current)
        current = ''
        lineCount = 0
      }
      current += subLines[j] + '\n'
      lineCount++
    }
  }
  if (current.length > 0) {
    pages.push(current)
  }
  return pages.length > 0 ? pages : ['无内容']
}

// Pre-compute built-in example pages and names at module load time.
// This runs once when the module is first imported and caches the results
// so that subsequent reader / search calls return instantly.
_precomputeBuiltinExamples()

export default {
  /**
   * 初始化数据
   * "蓝牙传输"包装文件夹已在 getBluetoothMeta/saveBluetoothMeta 层永久过滤
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
    // 提前触发旧版数据迁移（fire-and-forget）
    ensureBtMigrated()
  },

  /**
   * 强制清除所有内存缓存（删除操作后调用，确保下次读取从 storage 重新加载）
   */
  invalidateCache() {
    _btMetaCache = null
    _btFileCacheClear()
    _searchCacheClear()
    _btPagesCacheClear()
    _parsedJsonCache.clear()
  },

  /**
   * 获取主页列表项
   * 主页直接显示根级文件夹和文件
   * "蓝牙传输"包装文件夹已在 getBluetoothMeta() 读取层永久过滤
   */
  getTopLevelFolders() {
    return new Promise((resolve) => {
      getBluetoothMeta().then((metaList) => {
        if (!metaList) metaList = []
        // 返回根级文件夹和文件
        const items = metaList
          .filter(item => {
            // 根级内容：folder 为 bt_root 或空
            if (item.type === 'content' && (item.folder === 'bt_root' || !item.folder)) return true
            // 根级文件夹：parentId 为 bt_root 或空
            if (item.type === 'folder' && (item.parentId === 'bt_root' || !item.parentId)) return true
            return false
          })
          .map(item => ({
            id: item.id,
            name: item.name,
            type: item.type || 'content',
            fmt: item.fmt || ''
          }))
        resolve(items)
      })
    })
  },

  /**
   * 获取某路径下可见子项
   * 仅支持 "bt_folder_*" 路径（子文件夹）
   * 主页（根目录）由 getTopLevelFolders() 提供
   */
  getVisibleChildren(pathStr) {
    return new Promise((resolve) => {
      // 子文件夹（bt_folder_* 路径）
      if (pathStr && pathStr.startsWith('bt_folder_')) {
        getBluetoothMeta().then((metaList) => {
          // 返回该文件夹下的子文件夹和文件
          const items = (metaList || [])
            .filter(item => item.folder === pathStr || (item.type === 'folder' && item.parentId === pathStr))
            .map(item => ({
              id: item.id,
              name: item.name,
              type: item.type || 'content',
              fmt: item.fmt || ''
            }))
          resolve(items)
        })
        return
      }

      // 内置示例文件夹
      if (pathStr === 'builtin') {
        const items = builtinExamples.map(item => ({
          id: item.id,
          name: item.name,
          type: 'content',
          content: item.content
        }))
        resolve(items)
        return
      }

      // knowledgeTree 已为空，内置文件夹分支已清理
      resolve([])
    })
  },

  /**
   * 根据路径字符串获取节点
   */
  getNodeByPathStr,

  /**
   * 获取节点名称
   * 支持 "bt_folder_*" 路径（子文件夹）
   */
  getNodeName(pathStr) {
    if (pathStr === 'builtin') return '内置示例'
    // bt_ 前缀节点（bt_ 文件 / bt_folder_ 文件夹）
    if (pathStr && pathStr.startsWith('bt_')) {
      // 从元数据缓存中查找名称（同步访问）
      if (_btMetaCache) {
        const item = _btMetaCache.find(it => it.id === pathStr)
        if (item) return item.name
      }
      if (pathStr.startsWith('bt_folder_')) return '文件夹'
      return ''
    }
    const node = getNodeByPathStr(pathStr)
    return node ? node.name : ''
  },

  /**
   * 获取内容节点的分页内容
   * 支持 bt_ 前缀的蓝牙传输内容
   * 对大文件采用逐块流式分页，避免将整个文件加载到内存导致 OOM
   * @param {string} pathStr 路径
   * @param {number} [fontSize] 字号，默认 26
   */
  getReaderPages(pathStr, fontSize) {
    return new Promise((resolve) => {
      const fs = fontSize || DEFAULT_FONT_SIZE
      // 蓝牙传输内容：ID 以 bt_ 开头
      if (pathStr && pathStr.startsWith('bt_')) {
        const cacheKey = pathStr + '_' + fs
        const cachedPages = _btPagesCacheGet(cacheKey)
        if (cachedPages) {
          resolve(cachedPages)
          return
        }
        // 先检查分块数量，决定是否使用流式分页
        storage.get({
          key: STORAGE_KEY_BT_CHUNK_PREFIX + pathStr + '_count',
          success: (countStr) => {
            const count = parseInt(countStr)
            // 超过 10 块（约 30000 字符）使用流式分页，避免 OOM
            if (count && count > 10) {
              _streamPaginate(pathStr, count, fs).then((pages) => {
                _btPagesCacheSet(cacheKey, pages)
                resolve(pages)
              })
            } else {
              // 小文件：直接读取全文后分页
              getBluetoothFileContent(pathStr).then((content) => {
                const pages = splitContentIntoPages(content, fs)
                _btPagesCacheSet(cacheKey, pages)
                resolve(pages)
              })
            }
          },
          fail: () => {
            // 没有分块计数键，回退到旧版单键读取
            getBluetoothFileContent(pathStr).then((content) => {
              const pages = splitContentIntoPages(content, fs)
              _btPagesCacheSet(cacheKey, pages)
              resolve(pages)
            })
          }
        })
        return
      }

      // 内置知识树内容（knowledgeTree 为空时返回 无内容）
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
        resolve(splitContentIntoPages(node.content, fs))
      })
    })
  },

  /**
   * 删除单个考点内容
   * 支持 bt_ 前缀的蓝牙传输内容
   */
  deleteContent(pathStr) {
    return new Promise((resolve) => {
      // Built-in examples cannot be deleted
      if (pathStr && pathStr.startsWith('builtin_')) {
        resolve(false)
        return
      }

      // 蓝牙传输内容：递归删除（文件无子项，等价于删除自身 + 正文键）
      if (pathStr && pathStr.startsWith('bt_')) {
        this.deleteBluetoothNode(pathStr).then(() => resolve(true))
        return
      }

      // knowledgeTree 已为空，内置分支已清理
      resolve(false)
    })
  },

  /**
   * 删除文件夹（递归删除其下所有子项）
   * 支持 "bt_folder_*" 路径
   */
  deleteFolder(pathStr) {
    return new Promise((resolve) => {
      // 子文件夹：递归删除该文件夹及其子项
      if (pathStr && pathStr.startsWith('bt_folder_')) {
        this.deleteBluetoothNode(pathStr).then(() => resolve(true))
        return
      }

      // 内置示例文件夹：不允许删除
      if (pathStr === 'builtin') {
        resolve(false)
        return
      }

      // knowledgeTree 已为空，内置分支已清理
      resolve(false)
    })
  },

  /**
   * 删除所有考点（包括蓝牙传输内容）
   */
  deleteAll() {
    return new Promise((resolve) => {
      // knowledgeTree 已为空，仅清空蓝牙传输内容
      clearAllBluetooth().then(() => resolve(true))
    })
  },

  /**
   * 统计所有考点数量（从 meta 计算，仅统计正文文件）
   */
  getTotalContentCount() {
    return new Promise((resolve) => {
      getBluetoothMeta().then((metaList) => {
        const count = (metaList || []).filter(m => m.type === 'content').length
        resolve(count)
      })
    })
  },

  /**
   * 获取考点占用的存储大小（字节，从 meta 计算）
   * 注：正文键为独立存储，此处按 meta + 各文件名长度估算；
   * 完整正文大小需遍历文件键，这里返回 meta 层可见大小。
   */
  getContentStorageSize() {
    return new Promise((resolve) => {
      getBluetoothMeta().then((metaList) => {
        let size = 0
        if (metaList) {
          metaList.forEach(item => {
            size += (item.name || '').length
            size += (item.id || '').length
          })
        }
        resolve(size)
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
   * @param {number} [fontSize] 字号，默认 26
   */
  getCharsPerPage(fontSize) {
    const fs = fontSize || DEFAULT_FONT_SIZE
    const charsPerLine = Math.max(1, Math.floor(SCREEN_TEXT_WIDTH / fs))
    const maxLines = Math.max(1, Math.floor(SCREEN_TEXT_HEIGHT / (fs + 4)))
    return maxLines * charsPerLine
  },

  /**
   * 获取分页参数
   * @param {number} [fontSize] 字号，默认 26
   */
  getPaginationInfo(fontSize) {
    const fs = fontSize || DEFAULT_FONT_SIZE
    return {
      maxLines: Math.max(1, Math.floor(SCREEN_TEXT_HEIGHT / (fs + 4))),
      charsPerLine: Math.max(1, Math.floor(SCREEN_TEXT_WIDTH / fs))
    }
  },

  // ==================== 蓝牙传输功能 ====================

  /**
   * 保存蓝牙传输的 txt 内容
   * 生成 id → 存 meta 到 KD_BT_META → 分块存正文到 KD_BT_C_{id}_{i}
   * 使用分块存储避免单个 value 过大导致 OOM 崩溃和存储系统损坏
   * @param {string} filename 文件名（不含后缀；JSON 文件保留 .json 后缀）
   * @param {string} content  正文内容
   * @param {string} targetFolder 目标文件夹 ID（'bt_root' 表示根目录）
   * @param {string} [fmt] 内容格式：'' = 纯文本（默认），'json' = 知识点 JSON（Snapnotes 结构）
   * @returns {Promise<string|null>} 新内容 ID（失败返回 null）
   */
  saveBluetoothContent(filename, content, targetFolder, fmt) {
    return new Promise((resolve) => {
      getBluetoothMeta().then((metaList) => {
        const id = 'bt_' + Date.now()
        const meta = {
          id: id,
          name: filename,
          type: 'content',
          folder: targetFolder || 'bt_root',
          created: Date.now()
        }
        if (fmt === 'json') meta.fmt = 'json'
        const updated = metaList.concat([meta])
        saveBluetoothMeta(updated).then((metaOk) => {
          if (!metaOk) {
            console.error('[DM] Failed to save bluetooth meta for ' + filename)
            resolve(null)
            return
          }
          // 分块写入正文
          _writeChunkedContent(id, content || '').then((ok) => {
            if (ok) {
              // 新保存的小文件直接进 LRU 缓存（随后阅读/搜索零 I/O）；
              // 大文件不入缓存，按需从 storage 流式读取
              _btFileCacheSet(id, content || '')
              _searchCacheDelete(id)
              _parsedJsonCache.delete(id)
              _clearBtPagesCacheForId(id)
              console.log('[DM] Bluetooth content saved (chunked): ' + filename +
                ' (' + (content || '').length + ' chars, ' +
                Math.ceil((content || '').length / STORAGE_CHUNK_SIZE) + ' chunks)')
              resolve(id)
            } else {
              // 分块写入失败，回滚 meta
              const rolled = updated.filter(m => m.id !== id)
              saveBluetoothMeta(rolled)
              console.error('[DM] Failed to save bluetooth content (chunked) for ' + id)
              resolve(null)
            }
          })
        })
      })
    })
  },

  /**
   * 创建蓝牙传输文件夹
   * @param {string} name 文件夹名称
   * @param {string} parentId 父文件夹 ID（'bt_root' 表示根目录）
   * @returns {Promise<string>} 新文件夹 ID
   */
  createBluetoothFolder(name, parentId) {
    return new Promise((resolve) => {
      getBluetoothMeta().then((metaList) => {
        const id = 'bt_folder_' + Date.now()
        const folder = {
          id: id,
          name: name,
          type: 'folder',
          parentId: parentId || 'bt_root',
          created: Date.now()
        }
        const updated = metaList.concat([folder])
        saveBluetoothMeta(updated).then(() => {
          console.log('[DM] Folder created: ' + name + ' parentId=' + (parentId || 'bt_root'))
          resolve(id)
        })
      })
    })
  },

  /**
   * 重命名蓝牙传输节点（仅更新 meta）
   * @param {string} nodeId 节点 ID
   * @param {string} newName 新名称
   * @returns {Promise<boolean>} 是否成功
   */
  renameBluetoothNode(nodeId, newName) {
    return new Promise((resolve) => {
      getBluetoothMeta().then((metaList) => {
        let found = false
        const updated = metaList.map(item => {
          if (item.id === nodeId) {
            found = true
            const next = {}
            for (const k in item) next[k] = item[k]
            next.name = newName
            return next
          }
          return item
        })
        if (!found) {
          resolve(false)
          return
        }
        saveBluetoothMeta(updated).then(() => {
          console.log('[DM] Node renamed: ' + nodeId + ' -> ' + newName)
          resolve(true)
        })
      })
    })
  },

  /**
   * 删除蓝牙传输节点（文件或文件夹）
   * 如果是文件夹，递归删除其下所有子项
   * 一次遍历建 parentId→children 索引，再递归收集，避免多轮扫描
   * @param {string} nodeId 节点 ID
   */
  deleteBluetoothNode(nodeId) {
    return new Promise((resolve) => {
      getBluetoothMeta().then((metaList) => {
        if (!metaList) metaList = []
        // 一次遍历建 parent → children 索引（folder 用 folder 字段，文件夹用 parentId）
        const childrenIndex = new Map()
        for (let i = 0; i < metaList.length; i++) {
          const item = metaList[i]
          const parent = item.type === 'folder'
            ? (item.parentId || 'bt_root')
            : (item.folder || 'bt_root')
          if (!childrenIndex.has(parent)) childrenIndex.set(parent, [])
          childrenIndex.get(parent).push(item)
        }
        // 递归收集要删除的 id（用栈避免深递归）
        const toDelete = new Set()
        const stack = [nodeId]
        while (stack.length > 0) {
          const cur = stack.pop()
          if (toDelete.has(cur)) continue
          toDelete.add(cur)
          const children = childrenIndex.get(cur)
          if (children) {
            for (let i = 0; i < children.length; i++) {
              if (!toDelete.has(children[i].id)) stack.push(children[i].id)
            }
          }
        }
        // 从 meta 中移除
        const filtered = metaList.filter(item => !toDelete.has(item.id))
        saveBluetoothMeta(filtered).then(() => {
          // 逐个删除正文键（仅 content 类型有正文键）+ 清分页缓存
          const deleteIds = []
          toDelete.forEach(id => {
            const meta = metaList.find(m => m.id === id)
            if (meta && meta.type === 'content') {
              deleteIds.push(id)
              _clearBtPagesCacheForId(id)
            }
          })
          if (deleteIds.length === 0) {
            console.log('[DM] Deleted node: ' + nodeId + ' (total removed: ' + toDelete.size + ')')
            resolve(true)
            return
          }
          let pending = deleteIds.length
          const done = () => {
            pending--
            if (pending <= 0) {
              console.log('[DM] Deleted node: ' + nodeId + ' (total removed: ' + toDelete.size + ')')
              resolve(true)
            }
          }
          deleteIds.forEach(id => _deleteBtFileKey(id).then(done))
        })
      })
    })
  },

  /**
   * 获取文件夹树（供手机端请求使用）
   * 从 meta 缓存构建，不读正文；用 parentId→children 索引一次建树
   * 返回根级文件夹和文件的树结构，不包含正文内容
   */
  getFolderTreeForBluetooth() {
    return new Promise((resolve) => {
      getBluetoothMeta().then((metaList) => {
        if (!metaList) metaList = []
        // "蓝牙传输"包装文件夹已在 getBluetoothMeta() 读取层永久过滤
        // 一次遍历建 parentId → children 索引
        const childrenIndex = new Map()
        for (let i = 0; i < metaList.length; i++) {
          const item = metaList[i]
          const parent = item.type === 'folder'
            ? (item.parentId || 'bt_root')
            : (item.folder || 'bt_root')
          if (!childrenIndex.has(parent)) childrenIndex.set(parent, [])
          childrenIndex.get(parent).push(item)
        }
        // 递归构建树
        function buildBtNode(node) {
          const result = {
            id: node.id,
            name: node.name,
            type: node.type
          }
          if (node.type === 'folder') {
            result.children = []
            const children = childrenIndex.get(node.id) || []
            for (let i = 0; i < children.length; i++) {
              result.children.push(buildBtNode(children[i]))
            }
          }
          return result
        }
        // 从根级构建
        const tree = []
        const rootChildren = childrenIndex.get('bt_root') || []
        for (let i = 0; i < rootChildren.length; i++) {
          tree.push(buildBtNode(rootChildren[i]))
        }
        resolve(tree)
      })
    })
  },

  /**
   * 判断某个蓝牙文件是否为知识点 JSON（Snapnotes 结构）
   * @param {string} id 文件 ID
   * @returns {boolean}
   */
  isBluetoothJson(id) {
    if (_btMetaCache) {
      const item = _btMetaCache.find(it => it.id === id)
      if (item && item.fmt === 'json') return true
    }
    return false
  },

  /**
   * 获取蓝牙元数据列表（不含正文）
   */
  getBluetoothMeta() {
    return getBluetoothMeta()
  },

  /**
   * 获取单个文件正文
   * @param {string} id 文件 ID
   */
  getBluetoothFileContent(id) {
    return getBluetoothFileContent(id)
  },

  /**
   * 保存蓝牙元数据列表（只存元数据）
   */
  saveBluetoothMeta(list) {
    return saveBluetoothMeta(list)
  },

  /**
   * 获取蓝牙传输内容列表（用于显示，返回元数据）
   */
  getBluetoothContentList() {
    return getBluetoothMeta()
  },

  /**
   * 删除指定蓝牙传输内容
   */
  deleteBluetoothContent(id) {
    return this.deleteBluetoothNode(id)
  },

  /**
   * 获取蓝牙传输正文文件数量
   */
  getBluetoothContentCount() {
    return new Promise((resolve) => {
      getBluetoothMeta().then((metaList) => {
        resolve((metaList || []).filter(m => m.type === 'content').length)
      })
    })
  },

  /**
   * 获取蓝牙 JSON 文件的解析结果（parseKnowledgeJson 返回结构）
   * 带 FIFO 缓存：阅读页与搜索共享同一份解析，避免大文件反复 JSON.parse
   */
  getParsedKnowledge(id) {
    return _parsedJsonById(id)
  },

  // ==================== 阅读进度 ====================

  /**
   * 读取某 path 的阅读进度页码
   * @param {string} path 内容路径
   * @returns {Promise<number>} 页码（无记录返回 0）
   */
  getReadingProgress(path) {
    return _loadProgressMirror().then((obj) => {
      const value = obj[path]
      // 兼容两种格式：数字（旧版页码/像素）、对象（无缝模式 offset+scroll）
      if (typeof value === 'number' && value >= 0) {
        return value
      } else if (value && typeof value === 'object') {
        return value
      }
      return 0
    })
  },

  /**
   * 保存某 path 的阅读进度页码
   * 内存镜像立即生效，落盘做 800ms trailing 防抖：
   * 快速连翻/无缝换段从「每页 2 次 storage IPC」降为「停顿后 1 次」，
   * 同时消除并发 read-modify-write 的后写覆盖问题。
   * @param {string} path 内容路径
   * @param {number} page 页码
   */
  saveReadingProgress(path, page) {
    return _loadProgressMirror().then((obj) => {
      obj[path] = page
      _scheduleProgressFlush()
      return true
    })
  },

  // ==================== Built-in Examples ====================

  /**
   * Get built-in example data entries
   */
  getBuiltinExamples() {
    return builtinExamples
  },

  /**
   * Get reader pages for a built-in example
   */
  getBuiltinReaderPages(id) {
    // Return pre-computed pages from cache (computed at module load)
    if (_builtinPagesCache[id]) {
      return _builtinPagesCache[id]
    }
    // Fallback: compute on demand (should not normally happen)
    const item = builtinExamples.find(e => e.id === id)
    if (item) {
      if (isSubjectSpecific(item.content)) {
        const parsed = parseContent(item.content)
        const formatted = formatForDisplay(parsed)
        const pages = splitContentIntoPages(formatted)
        _builtinPagesCache[id] = pages
        return pages
      }
      const pages = splitContentIntoPages(item.content)
      _builtinPagesCache[id] = pages
      return pages
    }
    return ['内容不存在']
  },

  /**
   * Get built-in example name by id
   */
  getBuiltinName(id) {
    if (_builtinNameCache[id]) {
      return _builtinNameCache[id]
    }
    const item = builtinExamples.find(e => e.id === id)
    return item ? item.name : ''
  },

  // ==================== Search ====================

  /**
   * Search all content (bluetooth + builtin examples)
   * knowledgeTree 搜索分支已清理（knowledgeTree 为空）
   * 按文件分组返回全部命中（上限 MAX_RESULT_FILES 个文件），
   * 同一文件内重复命中的关键词归在一起，供任意选择跳转。
   * @param {string} keyword - search keyword
   * @returns {Promise<Array>} array of {name, path, fmt, type, matches:[]}
   */
  searchContent(keyword) {
    return new Promise((resolve) => {
      if (!keyword || keyword.trim().length === 0) {
        resolve([])
        return
      }
      const kw = keyword.trim().toLowerCase()
      const results = []

      getBluetoothMeta().then((metaList) => {
        const contentItems = (metaList || []).filter(m => m.type === 'content')
        let idx = 0

        const searchBuiltinExamples = () => {
          builtinExamples.forEach(item => {
            if (results.length >= MAX_RESULT_FILES) return
            const formatted = _builtinFormattedCache[item.id] || item.content
            const entry = { text: formatted, lower: formatted.toLowerCase() }
            const matches = _collectTxtMatches(entry, kw)
            if (matches.length > 0 || (item.name || '').toLowerCase().indexOf(kw) >= 0) {
              results.push({
                name: item.name,
                path: item.id,
                fmt: '',
                type: 'example',
                matches: matches
              })
            }
          })
          resolve(results)
        }

        const searchNext = () => {
          if (idx >= contentItems.length || results.length >= MAX_RESULT_FILES) {
            searchBuiltinExamples()
            return
          }
          const item = contentItems[idx++]
          _searchOneBtFile(item, kw).then((group) => {
            if (results.length < MAX_RESULT_FILES &&
              (group.matches.length > 0 || (item.name || '').toLowerCase().indexOf(kw) >= 0)) {
              results.push(group)
            }
            searchNext()
          })
        }
        searchNext()
      })
    })
  },

  /**
   * 在指定文件夹子树内搜索考点内容
   * @param {string} folderId 文件夹 id（bt_root 表示主页全局）
   * @param {string} keyword 关键词
   * @returns {Promise<Array>} array of {name, path, fmt, type, matches:[]}
   */
  searchContentInFolder(folderId, keyword) {
    return new Promise((resolve) => {
      if (!keyword || keyword.trim().length === 0) {
        resolve([])
        return
      }
      const kw = keyword.trim().toLowerCase()
      getBluetoothMeta().then((metaList) => {
        const list = metaList || []
        // 建 id → item 索引，父链回溯改 Map 查表（避免 filter 内嵌套 find 的 O(N²×depth)）
        const byId = new Map()
        for (let i = 0; i < list.length; i++) byId.set(list[i].id, list[i])
        // 收集 folderId 子树内的所有内容项：沿 folder/parentId 向上回溯判定归属
        const inFolder = list.filter(m => {
          if (m.type !== 'content') return false
          let cur = m
          let depth = 0
          while (cur && depth < 20) {
            if (cur.id === folderId) return true
            if (folderId === 'bt_root') return true
            const pid = cur.folder || cur.parentId || 'bt_root'
            if (pid === 'bt_root') return false
            cur = byId.get(pid)
            depth++
          }
          return false
        })

        const results = []
        let idx = 0
        const searchNext = () => {
          if (idx >= inFolder.length || results.length >= MAX_RESULT_FILES) {
            resolve(results)
            return
          }
          const item = inFolder[idx++]
          _searchOneBtFile(item, kw).then((group) => {
            if (results.length < MAX_RESULT_FILES &&
              (group.matches.length > 0 || (item.name || '').toLowerCase().indexOf(kw) >= 0)) {
              results.push(group)
            }
            searchNext()
          })
        }

        searchNext()
      })
    })
  },

  /**
   * 在单个文件中搜索关键词，返回该文件的分组结果
   * @param {string} pathStr 文件路径
   * @param {string} keyword 搜索关键词
   * @returns {Promise<{name, path, fmt, type, matches:[]}>}
   *   TXT 命中为 {position, snippet}；JSON 命中为 {position:-1, subject, entryIdx, entryTitle, snippet}
   */
  searchContentInFile(pathStr, keyword) {
    return new Promise((resolve) => {
      const empty = { name: '', path: pathStr, fmt: '', type: 'bluetooth', matches: [] }
      if (!keyword || keyword.trim().length === 0) {
        resolve(empty)
        return
      }
      const kw = keyword.trim().toLowerCase()
      // 查找文件的 fmt（判断是否 JSON 知识点）
      let fileFmt = ''
      if (_btMetaCache && pathStr && pathStr.indexOf('bt_') === 0) {
        const meta = _btMetaCache.find(m => m.id === pathStr)
        if (meta) fileFmt = meta.fmt || ''
      }
      if (pathStr && pathStr.indexOf('bt_') === 0) {
        const item = { id: pathStr, name: this.getUnifiedNodeName(pathStr), fmt: fileFmt }
        _searchOneBtFile(item, kw).then(resolve)
        return
      }
      // 内置示例等非 bt_ 内容
      this.getReaderFullContent(pathStr).then((content) => {
        if (!content) {
          resolve(empty)
          return
        }
        const entry = { text: content, lower: content.toLowerCase() }
        resolve({
          name: this.getUnifiedNodeName(pathStr),
          path: pathStr,
          fmt: '',
          type: 'example',
          matches: _collectTxtMatches(entry, kw)
        })
      })
    })
  },

  /**
   * 根据字符位置找到对应的页码（分页模式）
   * 重新分页并跟踪每页首字符偏移，找到包含该位置的那一页
   * @param {string} pathStr 文件路径
   * @param {number} charPosition 字符位置
   * @param {number} fontSize 字号
   * @returns {Promise<number>} 页码（0索引）
   */
  findPageByCharPosition(pathStr, charPosition, fontSize) {
    return new Promise((resolve) => {
      const fs = fontSize || DEFAULT_FONT_SIZE
      const charsPerLine = Math.max(1, Math.floor(SCREEN_TEXT_WIDTH / fs))
      const maxLines = Math.max(1, Math.floor(SCREEN_TEXT_HEIGHT / (fs + 4)))

      // 精确口径定位（与 _streamFindPageByChar 共用 _findPageInContent）
      function findPageFromContent(content) {
        resolve(_findPageInContent(content, charPosition, charsPerLine, maxLines))
      }

      // 获取全文
      if (pathStr && pathStr.startsWith('builtin_')) {
        if (_builtinFormattedCache[pathStr]) {
          findPageFromContent(_builtinFormattedCache[pathStr])
          return
        }
        const item = builtinExamples.find(e => e.id === pathStr)
        if (item) {
          let formatted = item.content
          if (isSubjectSpecific(item.content)) {
            const parsed = parseContent(item.content)
            formatted = formatForDisplay(parsed)
          }
          _builtinFormattedCache[pathStr] = formatted
          findPageFromContent(formatted)
          return
        }
        resolve(0)
        return
      }
      if (pathStr && pathStr.startsWith('bt_')) {
        // 大文件（>10 块）流式定位，避免整书入内存；小文件走缓存读全量
        storage.get({
          key: STORAGE_KEY_BT_CHUNK_PREFIX + pathStr + '_count',
          success: (countStr) => {
            const count = parseInt(countStr)
            if (count && count > 10) {
              _streamFindPageByChar(pathStr, count, charPosition, fs).then(resolve)
              return
            }
            getBluetoothFileContent(pathStr).then((content) => {
              findPageFromContent(content)
            })
          },
          fail: () => {
            getBluetoothFileContent(pathStr).then((content) => {
              findPageFromContent(content)
            })
          }
        })
        return
      }
      resolve(0)
    })
  },

  /**
   * 获取统一阅读器分页内容
   * @param {string} pathStr 路径
   * @param {number} [fontSize] 字号（内置示例使用预计算缓存，忽略该参数）
   */
  getReaderPagesUnified(pathStr, fontSize) {
    return new Promise((resolve) => {
      // Built-in examples
      if (pathStr && pathStr.startsWith('builtin_')) {
        resolve(this.getBuiltinReaderPages(pathStr))
        return
      }
      // Bluetooth content & built-in knowledge tree
      this.getReaderPages(pathStr, fontSize).then(resolve)
    })
  },

  /**
   * 获取阅读器全文内容（无缝模式使用，跳过分页计算以提升性能）
   * 复用已有的内容缓存（内置示例格式化缓存 / 蓝牙文件内容缓存）
   * @param {string} pathStr 路径
   * @returns {Promise<string>} 全文内容
   */
  getReaderFullContent(pathStr) {
    return new Promise((resolve) => {
      // 内置示例：从预计算格式化缓存读取
      if (pathStr && pathStr.startsWith('builtin_')) {
        if (_builtinFormattedCache[pathStr]) {
          resolve(_builtinFormattedCache[pathStr])
          return
        }
        const item = builtinExamples.find(e => e.id === pathStr)
        if (item) {
          let formatted = item.content
          if (isSubjectSpecific(item.content)) {
            const parsed = parseContent(item.content)
            formatted = formatForDisplay(parsed)
          }
          _builtinFormattedCache[pathStr] = formatted
          resolve(formatted)
          return
        }
        resolve('无内容')
        return
      }
      // 蓝牙传输内容：复用 getBluetoothFileContent（已有分块读取+内存缓存）
      if (pathStr && pathStr.startsWith('bt_')) {
        getBluetoothFileContent(pathStr).then((content) => {
          resolve(content || '无内容')
        })
        return
      }
      resolve('无内容')
    })
  },

  /**
   * 获取分段内容（无缝模式 list 虚拟渲染使用，避免一次性渲染全文导致卡顿）
   * 对大文件采用流式分段，逐块读取存储并按行数分段
   * @param {string} pathStr 路径
   * @param {number} [linesPerSegment=20] 每段行数
   * @returns {Promise<Array<string>>} 分段内容数组
   */
  getReaderContentSegments(pathStr, linesPerSegment) {
    const lps = linesPerSegment || 20
    return new Promise((resolve) => {
      // 内置示例：从预计算格式化缓存读取
      if (pathStr && pathStr.startsWith('builtin_')) {
        let content = _builtinFormattedCache[pathStr]
        if (!content) {
          const item = builtinExamples.find(e => e.id === pathStr)
          if (item) {
            content = item.content
            if (isSubjectSpecific(item.content)) {
              const parsed = parseContent(item.content)
              content = formatForDisplay(parsed)
            }
            _builtinFormattedCache[pathStr] = content
          }
        }
        resolve(_splitIntoSegments(content || '无内容', lps))
        return
      }
      // 蓝牙传输内容：检查分块存储，大文件用流式分段
      if (pathStr && pathStr.startsWith('bt_')) {
        storage.get({
          key: STORAGE_KEY_BT_CHUNK_PREFIX + pathStr + '_count',
          success: (countStr) => {
            const count = parseInt(countStr)
            if (count && count > 0) {
              _streamSegments(pathStr, count, lps).then(resolve)
              return
            }
            // 回退到单键/缓存
            getBluetoothFileContent(pathStr).then((content) => {
              resolve(_splitIntoSegments(content || '无内容', lps))
            })
          },
          fail: () => {
            getBluetoothFileContent(pathStr).then((content) => {
              resolve(_splitIntoSegments(content || '无内容', lps))
            })
          }
        })
        return
      }
      resolve(['无内容'])
    })
  },

  /**
   * 获取章节目录（轻量索引，参考 com.bandbbs.ebook 分章加载模型）
   * 仅支持蓝牙传输的长文本；返回 { list: [{title,start}], totalLength }
   * list 为空表示无章节结构（或文件太短无需目录）
   */
  getReaderChapters(pathStr) {
    return new Promise((resolve) => {
      if (!pathStr || pathStr.indexOf('bt_') !== 0 || pathStr.indexOf('bt_folder_') === 0) {
        resolve({ list: [], totalLength: 0 })
        return
      }
      if (_btChaptersCache.has(pathStr)) {
        resolve(_btChaptersCache.get(pathStr))
        return
      }
      this.getReaderFullContent(pathStr).then((content) => {
        if (!this || !content || content.length === 0) {
          resolve({ list: [], totalLength: 0 })
          return
        }
        const result = { list: buildChapterIndex(content), totalLength: content.length }
        _btChaptersCacheSet(pathStr, result)
        resolve(result)
      })
    })
  },

  /**
   * Get node name by path (supports builtin_ prefix)
   */
  getUnifiedNodeName(pathStr) {
    if (pathStr && pathStr.startsWith('builtin_')) {
      return this.getBuiltinName(pathStr)
    }
    return this.getNodeName(pathStr)
  }
}

// ==================== 内部辅助：清空全部蓝牙内容 ====================

// 清空所有蓝牙内容（meta + 所有正文键 + 缓存）
function clearAllBluetooth() {
  return new Promise((resolve) => {
    getBluetoothMeta().then((metaList) => {
      const contentIds = (metaList || []).filter(m => m.type === 'content').map(m => m.id)
      saveBluetoothMeta([]).then(() => {
        // 清除所有分页缓存（含字节计数器）
        _btPagesCacheClear()
        _searchCacheClear()
        _parsedJsonCache.clear()
        if (contentIds.length === 0) {
          resolve(true)
          return
        }
        let pending = contentIds.length
        const done = () => {
          pending--
          if (pending <= 0) resolve(true)
        }
        contentIds.forEach(id => {
          _clearBtPagesCacheForId(id)
          _deleteBtFileKey(id).then(done)
        })
      })
    })
  })
}


