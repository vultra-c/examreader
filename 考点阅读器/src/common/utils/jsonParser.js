/**
 * 考点阅读器 - JSON 知识点解析器
 *
 * 显示逻辑提取自 闪念小抄 Snapnotes-band（github.com/WenHuaYiYang/Snapnotes-band）
 * 的 knowledgeStore.js / content.ux，适配到本项目存储结构。
 *
 * JSON 文件结构（与闪念小抄手机端同构）：
 *   {
 *     "<科目名>": [
 *       { "id": 1, "title": "标题", "desc": "描述(可选)", "points": ["要点1", ...],
 *         "raw": "原文(可选)", "formulas": ["公式(可选)"] },
 *       ...
 *     ],
 *     ...
 *   }
 */

// 清洗单个条目（与 Snapnotes mergeParsedInto 同规则）
function cleanItem(it, fallbackId) {
  if (!it || typeof it !== 'object') return null
  if (!it.title || typeof it.title !== 'string') return null
  return {
    id: (typeof it.id === 'number') ? it.id : fallbackId,
    title: it.title,
    desc: (it.desc && typeof it.desc === 'string') ? it.desc : '',
    raw: (it.raw && typeof it.raw === 'string') ? it.raw : '',
    points: Array.isArray(it.points) ? it.points.filter(p => typeof p === 'string') : [],
    formulas: Array.isArray(it.formulas) ? it.formulas.filter(f => typeof f === 'string') : []
  }
}

/**
 * 解析并校验知识点 JSON 字符串
 * @param {string} text JSON 文本
 * @returns {{ok:boolean, data?:Object, subjects?:Array, error?:string}}
 *   data 为清洗后的对象 { 科目: [条目] }；subjects 为 [{name,count}] 列表
 */
export function parseKnowledgeJson(text) {
  if (!text || typeof text !== 'string' || text.trim().length === 0) {
    return { ok: false, error: 'empty' }
  }
  let parsed
  try {
    parsed = JSON.parse(text)
  } catch (e) {
    return { ok: false, error: 'parse-fail' }
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) {
    return { ok: false, error: 'bad-structure' }
  }

  const data = {}
  const subjects = []
  const names = Object.keys(parsed)
  for (let i = 0; i < names.length; i++) {
    const name = names[i]
    const list = parsed[name]
    if (!Array.isArray(list)) continue
    const clean = []
    for (let j = 0; j < list.length; j++) {
      const item = cleanItem(list[j], j + 1)
      if (item) clean.push(item)
    }
    if (!clean.length) continue
    data[name] = clean
    subjects.push({ name: name, count: clean.length })
  }
  if (subjects.length === 0) {
    return { ok: false, error: 'no-valid-items' }
  }
  return { ok: true, data: data, subjects: subjects }
}

/**
 * 获取科目列表 [{name, count}]
 */
export function getSubjects(parsedData) {
  if (!parsedData || typeof parsedData !== 'object') return []
  const result = []
  const names = Object.keys(parsedData)
  for (let i = 0; i < names.length; i++) {
    const name = names[i]
    const list = parsedData[name]
    if (Array.isArray(list) && list.length > 0) {
      result.push({ name: name, count: list.length })
    }
  }
  return result
}

/**
 * 获取某科目的条目列表（已清洗）
 */
export function getItems(parsedData, subjectName) {
  if (!parsedData || typeof parsedData !== 'object') return []
  const list = parsedData[subjectName]
  if (!Array.isArray(list)) return []
  const clean = []
  for (let j = 0; j < list.length; j++) {
    const item = cleanItem(list[j], j + 1)
    if (item) clean.push(item)
  }
  return clean
}

/**
 * 判断文本是否可能是知识点 JSON（用于无扩展名场景的快速嗅探）
 */
export function looksLikeKnowledgeJson(text) {
  if (!text || typeof text !== 'string') return false
  const t = text.trim()
  if (t.charAt(0) !== '{') return false
  const r = parseKnowledgeJson(t)
  return r.ok
}
