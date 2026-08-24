/**
 * 化学计算器 - 化学方程式配平（整数化高斯消元求零空间）
 */
import { gcd } from './mathUtil.js'

/** 简易分数运算 */
function F(n, d = 1) {
  if (d === 0) throw new Error('div0')
  if (d < 0) { n = -n; d = -d }
  const g = gcd(Math.abs(n), d)
  return { n: n / g, d: d / g }
}
function fAdd(a, b) { return F(a.n * b.d + b.n * a.d, a.d * b.d) }
function fSub(a, b) { return F(a.n * b.d - b.n * a.d, a.d * b.d) }
function fMul(a, b) { return F(a.n * b.n, a.d * b.d) }
function fDiv(a, b) { return F(a.n * b.d, a.d * b.n) }
function fIsZero(a) { return a.n === 0 }

function lcm(a, b) {
  return Math.abs(a * b) / gcd(a, b)
}

/**
 * 配平
 * 反应物按正计数、生成物按负计数入矩阵（质量守恒：左和 = 右和）
 * @param {Array<Object>} speciesCounts 每种物质的元素计数
 * @param {number} productStartIndex 从该下标起视为生成物（矩阵内取负）
 * @returns {Array<number>|null} 最小正整数系数数组；无解返回 null
 */
export function balance(speciesCounts, productStartIndex) {
  const cols = speciesCounts.length
  const elemSet = {}
  speciesCounts.forEach(c => { for (const k in c) elemSet[k] = true })
  const elems = Object.keys(elemSet)
  const rows = elems.length
  const negFrom = productStartIndex == null ? cols : productStartIndex

  // 构建矩阵 A[elem][species]，生成物列取负
  const A = []
  for (let r = 0; r < rows; r++) {
    A.push([])
    for (let c = 0; c < cols; c++) {
      const v = speciesCounts[c][elems[r]] || 0
      A[r].push(F(c >= negFrom ? -v : v))
    }
  }

  // 高斯-约当消元
  const pivotCols = []
  let r = 0
  for (let c = 0; c < cols && r < rows; c++) {
    let pr = -1
    for (let rr = r; rr < rows; rr++) {
      if (!fIsZero(A[rr][c])) { pr = rr; break }
    }
    if (pr === -1) continue
    const tmp = A[r]; A[r] = A[pr]; A[pr] = tmp
    const piv = A[r][c]
    for (let cc = 0; cc < cols; cc++) A[r][cc] = fDiv(A[r][cc], piv)
    for (let rr = 0; rr < rows; rr++) {
      if (rr !== r && !fIsZero(A[rr][c])) {
        const factor = A[rr][c]
        for (let cc = 0; cc < cols; cc++) {
          A[rr][cc] = fSub(A[rr][cc], fMul(factor, A[r][cc]))
        }
      }
    }
    pivotCols.push(c)
    r++
  }

  // 自由变量：期望恰好 1 个；多于 1 个说明体系欠定，拒绝
  const freeCols = []
  for (let c = 0; c < cols; c++) {
    if (pivotCols.indexOf(c) === -1) freeCols.push(c)
  }
  if (freeCols.length !== 1) return null
  const freeCol = freeCols[0]

  // 回代：自由变量取 1
  const x = new Array(cols)
  x[freeCol] = F(1)
  pivotCols.forEach((pc, idx) => {
    void idx
    // 第 idx 个主元行（RREF 后主元行顺序与 pivotCols 一致）
    const row = A[idx]
    let val = F(0)
    for (const fc of freeCols) {
      val = fAdd(val, fMul(row[fc], x[fc]))
    }
    x[pc] = fSub(F(0), val)
  })

  // 通分取整
  let L = 1
  for (const v of x) L = lcm(L, v.d)
  const ints = x.map(v => v.n * (L / v.d))

  // 归一化符号与最大公约数
  const allGcd = ints.reduce((g, v) => gcd(g, Math.abs(v)), 0) || 1
  let out = ints.map(v => v / allGcd)
  if (out.every(v => v <= 0)) out = out.map(v => -v)
  if (!out.every(v => v > 0)) return null
  return out
}
