/**
 * 化学计算器 - 质量守恒计量计算
 * 已知一种物质的质量，按配平系数推算其余所有物质质量
 */
import { fmtNum } from './parser.js'

/**
 * @param {Object} reaction solveReaction 的 reaction 对象
 * @param {number} knownIndex 已知物质在 all 中的下标（reactants.length 为分界）
 * @param {number} knownMass 已知质量 g
 * @returns {Array<{mass:number, text:string}>} 各物质质量（与 reactants+products 对齐）
 */
export function computeMasses(reaction, knownIndex, knownMass) {
  const n = reaction.coefs.length
  const mr = reaction.mr[knownIndex]
  if (!(mr > 0) || !(knownMass >= 0)) return null
  const molKnown = knownMass / mr
  const out = []
  for (let i = 0; i < n; i++) {
    const molI = molKnown * reaction.coefs[i] / reaction.coefs[knownIndex]
    const mass = molI * reaction.mr[i]
    out.push({ mol: molI, mass, text: fmtNum(mass) })
  }
  return out
}
