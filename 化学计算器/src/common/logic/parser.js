/**
 * 化学计算器 - 化学式解析器
 * 支持：标准化学式（含括号嵌套）、中文名称查询
 * 输出：元素计数、渲染分段（下标用）
 */
import { ATOMIC_MASSES } from './elements.js'

/**
 * 解析化学式
 * @param {string} f 化学式，如 Cu2(OH)2CO3 / Fe3O4 / H2O
 * @returns {{ok:boolean, error?:string, counts?:Object, segs?:Array}}
 *   counts: { 元素符号: 个数 }
 *   segs:   渲染分段 [{t:'el'|'ch', s:'Cu'}] / [{t:'num', s:'2'}]
 */
export function parseFormula(f) {
  const s = String(f || '').trim()
  if (!s) return { ok: false, error: '化学式为空' }

  const counts = {}
  const segs = []
  // 栈：每层记录 {counts, startSegIndex}
  const stack = [{ counts: {}, segs: [] }]
  let i = 0
  const n = s.length

  function commitUnit(unitCounts, unitSegs, mult) {
    const top = stack[stack.length - 1]
    for (const k in unitCounts) {
      top.counts[k] = (top.counts[k] || 0) + unitCounts[k] * mult
    }
    if (mult === 1) {
      for (const sg of unitSegs) top.segs.push(sg)
    } else {
      top.segs.push({ t: 'ch', s: '(' })
      for (const sg of unitSegs) top.segs.push(sg)
      top.segs.push({ t: 'ch', s: ')' })
      top.segs.push({ t: 'num', s: String(mult) })
    }
  }

  while (i < n) {
    const c = s[i]
    if (c >= 'A' && c <= 'Z') {
      // 元素符号：大写开头 + 可选小写
      let sym = c
      i++
      if (i < n && s[i] >= 'a' && s[i] <= 'z') {
        sym += s[i]
        i++
      }
      if (!(sym in ATOMIC_MASSES)) {
        return { ok: false, error: '未知元素符号：' + sym + '（' + s + '）' }
      }
      // 数字
      let numStr = ''
      while (i < n && s[i] >= '0' && s[i] <= '9') {
        numStr += s[i]
        i++
      }
      const cnt = numStr ? parseInt(numStr) : 1
      const top = stack[stack.length - 1]
      top.counts[sym] = (top.counts[sym] || 0) + cnt
      top.segs.push({ t: 'el', s: sym })
      if (cnt > 1) top.segs.push({ t: 'num', s: numStr })
    } else if (c === '(' || c === '[') {
      stack.push({ counts: {}, segs: [] })
      stack[stack.length - 1]._openCh = '('
      i++
    } else if (c === ')' || c === ']') {
      if (stack.length <= 1) {
        return { ok: false, error: '括号不匹配（' + s + '）' }
      }
      const done = stack.pop()
      let numStr = ''
      while (i + 1 < n && s[i + 1] >= '0' && s[i + 1] <= '9') {
        numStr += s[i + 1]
        i++
        void numStr.length
      }
      // 注意上面循环从 i+1 开始，需要同步 i
      const mult = numStr ? parseInt(numStr) : 1
      commitUnit(done.counts, done.segs, mult)
      i++
    } else if (c === '·' || c === '.') {
      return { ok: false, error: '暂不支持结晶水合物（' + s + '）' }
    } else if (c === '+' || c === '=' || c === ' ') {
      return { ok: false, error: '化学式内不能含有 ' + c + '，请分开输入' }
    } else {
      return { ok: false, error: '非法字符：' + c + '（' + s + '）' }
    }
  }

  if (stack.length > 1) {
    return { ok: false, error: '括号不匹配（' + s + '）' }
  }

  const final = stack[0]
  if (Object.keys(final.counts).length === 0) {
    return { ok: false, error: '化学式为空' }
  }
  return { ok: true, counts: final.counts, segs: final.segs }
}

/** 相对分子质量 */
export function molarMass(counts) {
  let m = 0
  for (const k in counts) m += ATOMIC_MASSES[k] * counts[k]
  return m
}

/** 数值格式化：最多保留 3 位小数，去尾零 */
export function fmtNum(v) {
  if (!isFinite(v)) return '--'
  let r = Math.round(v * 1000) / 1000
  let str = String(r)
  return str
}

/**
 * 把输入串拆成单个物质 token：
 * 支持全角加号、中文顿号等分隔；返回 token 数组
 */
export function splitInputTokens(input) {
  return String(input || '')
    .replace(/＋/g, '+')
    .split('+')
    .map(t => t.trim())
    .filter(t => t.length > 0)
}

/**
 * 解析一个 token：先按化学式，失败后查中文名称表
 * @returns {{ok:boolean, formula?:string, error?:string}}
 */
export function resolveToken(token, nameMap) {
  const t = token.trim()
  // 化学式形态：字母数字括号
  if (/^[A-Za-z0-9()\[\]]+$/.test(t)) {
    const r = parseFormula(t.replace(/[\[\]]/g, m => (m === '[' ? '(' : ')')))
    if (r.ok) return { ok: true, formula: normalizeFormula(t, r) }
    return r
  }
  // 中文名称
  if (nameMap && nameMap[t]) {
    return { ok: true, formula: nameMap[t], byName: true }
  }
  return { ok: false, error: '无法识别 "' + t + '"：不是合法化学式，也不在名称库中' }
}

/**
 * 规范化化学式书写：Co 与 CO 区分依赖用户大小写。
 * 这里仅做括号统一（[]→()），其余保持原样。
 */
function normalizeFormula(raw, parsed) {
  void parsed
  return raw.replace(/[\[\]]/g, m => (m === '[' ? '(' : ')'))
}
