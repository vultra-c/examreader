/**
 * 化学工具箱 · 展示格式化工具（公共）
 * 方程式/化学式文本渲染统一在此，避免各页面重复实现（DRY）。
 * 纯函数，无副作用；带轻量缓存，滚动分块渲染时零重复计算。
 */
import { parseFormula } from './parser.js'

/** 数字 → Unicode 下标映射 */
const SUB = { '0': '₀', '1': '₁', '2': '₂', '3': '₃', '4': '₄', '5': '₅', '6': '₆', '7': '₇', '8': '₈', '9': '₉' }

export function subDigit(num) {
  return String(num).split('').map(c => SUB[c] || c).join('')
}

/** 系数 + 分段 → 展示文本，如 2CuO（数字转 Unicode 下标） */
export function segsToText(segs, coef) {
  let s = ''
  for (let i = 0; i < segs.length; i++) {
    const seg = segs[i]
    if (seg.t === 'num') s += subDigit(seg.s)
    else s += seg.s
  }
  return (coef > 1 ? coef : '') + s
}

/** 单个化学式字符串 → 展示文本（带下标；解析失败时原样返回） */
export function formulaToText(formula, coef) {
  const pr = parseFormula(formula)
  const segs = pr.ok ? pr.segs : [{ t: 'el', s: formula }]
  return segsToText(segs, coef)
}

/**
 * 元素银行条目 → 整条方程式展示文本，如 "2Cu + O₂ = 2CuO"（带缓存）
 * 缓存直接挂在 entry._text 上：entry 是模块级常量数据，
 * 同一 entry 重复渲染（滚动翻出/翻回、搜索结果复用）不再重新解析化学式。
 */
export function equationText(entry) {
  if (entry._text) return entry._text
  const all = entry.r.concat(entry.p)
  const nR = entry.r.length
  let s = ''
  for (let i = 0; i < all.length; i++) {
    if (i > 0) s += i === nR ? ' = ' : ' + '
    s += formulaToText(all[i], entry.c[i])
  }
  try { entry._text = s } catch (err) { /* 数据被冻结时退化为无缓存 */ }
  return s
}
