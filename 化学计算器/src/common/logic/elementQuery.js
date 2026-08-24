/**
 * 化学计算器 - 元素查询模块
 * 输入一个或多个元素（如 Fe、Fe O、Na C O），本地检索含有这些元素的方程式
 * （输入几个元素，方程式就必须同时含有这几个元素）
 */
import { ELEMENT_BANK } from './elementBank.js'
import { ATOMIC_MASSES } from './elements.js'
import { parseFormula, molarMass } from './parser.js'

/** 已知元素符号集合（用于校验输入） */
export const ELEMENT_SYMBOLS = Object.keys(ATOMIC_MASSES)

/** 元素符号规范化：fe → Fe，h2o 里的输入不管；只处理单个符号 */
export function normalizeSymbol(s) {
  const t = String(s || '').trim()
  if (!t) return ''
  // 首字母大写、其余小写
  const sym = t.charAt(0).toUpperCase() + t.slice(1).toLowerCase()
  return ELEMENT_SYMBOLS.indexOf(sym) !== -1 ? sym : ''
}

/**
 * 解析元素查询输入
 * @param {string} raw 如 "Fe O" / "fe+o" / "Na,C,O"
 * @returns {{ok:true, elems:string[]}|{ok:false, error:string}}
 */
export function parseElementQuery(raw) {
  const s = String(raw || '').trim()
  if (!s) return { ok: false, error: '请输入元素，如 Fe 或 Fe O' }
  const tokens = s.split(/[\s+,，]+/).filter(Boolean)
  if (tokens.length === 0) return { ok: false, error: '请输入元素' }
  const elems = []
  for (const t of tokens) {
    const sym = normalizeSymbol(t)
    if (!sym) return { ok: false, error: '无法识别元素：' + t }
    if (elems.indexOf(sym) === -1) elems.push(sym)
  }
  return { ok: true, elems }
}

/**
 * 搜索含有全部指定元素的方程式
 * @param {string[]} elems 如 ['Fe','O']
 * @param {number} limit 返回条数上限
 * @returns {Array} 匹配的方程式条目 [{r,p,c,t,n,e, full}]
 */
export function searchEquations(elems, limit = 30) {
  if (!elems || elems.length === 0) return []
  const results = []
  for (const entry of ELEMENT_BANK) {
    const inSet = entry.e.split(' ')
    let all = true
    for (const e of elems) {
      if (inSet.indexOf(e) === -1) { all = false; break }
    }
    if (all) results.push(entry)
    if (results.length >= limit) break
  }
  return results
}

/** 把元素集合（空格分隔）转成逗号分隔显示，如 "Fe O" → "Fe、O" */
export function formatElems(elemStr) {
  return elemStr.split(' ').join('、')
}

/**
 * 由银行条目重建 reaction 对象（供结果页/质量页使用）
 * @returns {Object} {reactants, products, coefs, mr, segs, type, cond}
 */
export function buildReactionFromBank(entry) {
  const all = entry.r.concat(entry.p)
  const parsed = all.map(f => parseFormula(f))
  return {
    reactants: entry.r.slice(),
    products: entry.p.slice(),
    coefs: entry.c.slice(),
    mr: parsed.map(p => molarMass(p.counts)),
    segs: parsed.map(p => p.segs),
    type: entry.t,
    cond: entry.n
  }
}

/** 方程式渲染文本（系数 + 化学式，数字下标转 Unicode） */
const SUB = { '0':'₀','1':'₁','2':'₂','3':'₃','4':'₄','5':'₅','6':'₆','7':'₇','8':'₈','9':'₉' }
function subDigit(num) {
  return String(num).split('').map(c => SUB[c] || c).join('')
}
export function renderFormulaText(segs, coef) {
  let s = ''
  for (const seg of segs) {
    if (seg.t === 'el') s += seg.s
    else if (seg.t === 'num') s += subDigit(seg.s)
    else s += seg.s
  }
  return (coef > 1 ? coef : '') + s
}

/** 整条方程式渲染文本，如 "2Cu + O₂ = 2CuO" */
export function equationText(entry) {
  const all = entry.r.concat(entry.p)
  const segsList = all.map(f => {
    const pr = parseFormula(f)
    return pr.ok ? pr.segs : [{ t: 'el', s: f }]
  })
  const parts = entry.c.map((c, i) => renderFormulaText(segsList[i], c))
  const nR = entry.r.length
  let s = ''
  for (let i = 0; i < parts.length; i++) {
    if (i > 0) s += i === nR ? ' = ' : ' + '
    s += parts[i]
  }
  return s
}
