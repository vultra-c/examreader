/**
 * 化学计算器 - 反应规则引擎（离线规则库）
 * 覆盖：化合、分解、置换、复分解（含中和）、氧化还原、有机燃烧等常规反应
 */
import { parseFormula, molarMass } from './parser.js'
import { balance } from './balance.js'
import {
  METALS, activityIndex, CATION_CHARGE, DISPLACEMENT_CHARGE,
  ANIONS, RADICAL_SIGNATURES, isInsoluble, BLOCKED_METAL_ACID,
  REDUCIBLE_OXIDES, METAL_O2_PRODUCTS, NONMETAL_O2_PRODUCTS
} from './elements.js'

// ---------------- 分类 ----------------

const ORGANICS = ['CH4', 'C2H5OH', 'CH3OH', 'C2H2', 'C6H12O6', 'C12H22O11', 'CH3COOH']

const ACID_ANION = {
  HCl: 'Cl', H2SO4: 'SO4', HNO3: 'NO3',
  H2CO3: 'CO3', H2SO3: 'SO3', H3PO4: 'PO4'
}

/** 可与酸反应（成盐+水）的碱性氧化物白名单 */
const BASIC_OXIDES_FOR_ACID = {
  MgO: ['Mg', 2], CaO: ['Ca', 2], BaO: ['Ba', 2],
  Na2O: ['Na', 1], K2O: ['K', 1],
  CuO: ['Cu', 2], Fe2O3: ['Fe', 3], Al2O3: ['Al', 3], ZnO: ['Zn', 2], Ag2O: ['Ag', 1]
}

const AO_WATER = { CO2: 'H2CO3', SO2: 'H2SO3', SO3: 'H2SO4', P2O5: 'H3PO4' }
const AO_ANION = { CO2: 'CO3', SO2: 'SO3', SO3: 'SO4', P2O5: 'PO4' }
const MO_WATER = { CaO: 'Ca(OH)2', BaO: 'Ba(OH)2', Na2O: 'NaOH', K2O: 'KOH' }

/**
 * 物质分类
 */
export function classify(formula) {
  if (formula === 'H2O') return 'water'
  if (formula === 'CO') return 'co'
  if (formula === 'H2O2') return 'h2o2'
  if (formula === 'NO2') return 'no2'
  if (formula === 'NH3') return 'nh3'
  if (ORGANICS.indexOf(formula) !== -1) return 'organic'

  const r = parseFormula(formula)
  if (!r.ok) return null
  const keys = Object.keys(r.counts)
  if (keys.length === 1) {
    const sym = keys[0]
    return METALS.indexOf(sym) !== -1 ? 'metal' : 'nonmetal'
  }
  // 酸：H 开头且多元素
  if (formula[0] === 'H' && keys.length > 1 && ACID_ANION[formula]) return 'acid'
  // 碱：M(OH)n
  if (/^(K|Na|Ca|Ba|Mg|Al|Zn|Fe|Cu)\(OH\)\d$/.test(formula) || /^(K|Na)OH$/.test(formula)) return 'base'
  // 氧化物：两元素含 O
  if (keys.indexOf('O') !== -1 && keys.length === 2) {
    const other = keys.find(k => k !== 'O')
    if (METALS.indexOf(other) !== -1) return 'mo'
    return 'ao'
  }
  // 盐：含金属/铵根 + 酸根或卤素等
  if (/^(NH4)/.test(formula)) return 'salt'
  const hasMetal = keys.some(k => METALS.indexOf(k) !== -1)
  if (hasMetal) {
    if (getRadical(r.counts, keys)) return 'salt'
    // 二元盐：金属 + Cl/Br/I/S
    const nonCation = keys.find(k => METALS.indexOf(k) === -1 && k !== 'O')
    if (nonCation && ['Cl', 'Br', 'I', 'S'].indexOf(nonCation) !== -1) return 'salt'
  }
  return null
}

// ---------------- 离子解析 ----------------

/** 规范化签名：中心元素（非 O/H）按字母序在前，随后 H、O */
function makeSig(rest, rk) {
  let g = 0
  for (const k of rk) g = gcd2(g, rest[k])
  if (g === 0) g = 1
  const centers = rk.filter(k => k !== 'O' && k !== 'H').sort()
  const hs = rk.filter(k => k === 'H')
  const os = rk.filter(k => k === 'O')
  const ordered = centers.concat(hs).concat(os)
  return ordered.map(k => k + (rest[k] / g)).join('')
}

function getRadical(counts, keys) {
  // 排除阳离子元素后的组成签名（归一化，支持 Ca(HCO3)2 等）
  const rest = {}
  for (const k of keys) {
    if (!(k in CATION_CHARGE)) rest[k] = counts[k]
  }
  const rk = Object.keys(rest)
  if (rk.length < 2) return null
  const cands = RADICAL_SIGNATURES[makeSig(rest, rk)]
  return cands || null
}

/**
 * 解析盐的离子组成
 * @returns {{cation:{sym,count,q}, anion:{id,count,q}}|null}
 */
export function saltIons(formula) {
  const r = parseFormula(formula)
  if (!r.ok) return null
  const counts = r.counts
  const keys = Object.keys(counts)

  // 铵盐：NH4 为阳离子，其余组成识别阴离子
  if (/^NH4/.test(formula)) {
    const nCount = counts.N || 0
    if (nCount <= 0) return null
    const rest = Object.assign({}, counts)
    rest.N = (rest.N || 0) - nCount
    rest.H = (rest.H || 0) - nCount * 4
    const rk = Object.keys(rest).filter(k => rest[k] > 0)
    if (rk.length === 0) return null
    // 单一简单阴离子：NH4Cl / NH4Br / NH4I / NH4? S 类
    if (rk.length === 1 && ANIONS[rk[0]]) {
      const id = rk[0]
      return { cation: { sym: 'NH4', count: nCount, q: 1 }, anion: { id, count: rest[id], q: ANIONS[id].charge } }
    }
    const sig = makeSig(rest, rk)
    const cands = RADICAL_SIGNATURES[sig]
    if (!cands) return null
    const centerKey = rk.find(k => k !== 'O' && k !== 'H') || rk[0]
    const acount = rest[centerKey]
    for (const id of cands) {
      const aq = Math.abs(ANIONS[id].charge)
      if (nCount * 1 === acount * aq) {
        return { cation: { sym: 'NH4', count: nCount, q: 1 }, anion: { id, count: acount, q: ANIONS[id].charge } }
      }
    }
    return { cation: { sym: 'NH4', count: nCount, q: 1 }, anion: { id: cands[0], count: acount, q: ANIONS[cands[0]].charge } }
  }

  // 阳离子元素
  const cationSyms = keys.filter(k => k in CATION_CHARGE)
  if (cationSyms.length !== 1) return null
  const csym = cationSyms[0]
  const ccount = counts[csym]

  // 酸根候选
  const cands = getRadical(counts, keys)
  if (cands) {
    const rest = {}
    for (const k of keys) {
      if (!(k in CATION_CHARGE)) rest[k] = counts[k]
    }
    const rk = Object.keys(rest)
    // 中心原子（非 O/H 的组成元素）的原始个数即阴离子个数
    const centerKey = rk.find(k => k !== 'O' && k !== 'H') || rk[0]
    const acount = rest[centerKey]
    for (const id of cands) {
      const aq = ANIONS[id].charge
      if (ccount * CATION_CHARGE[csym] === acount * Math.abs(aq)) {
        return { cation: { sym: csym, count: ccount, q: CATION_CHARGE[csym] }, anion: { id, count: acount, q: aq } }
      }
    }
    // 无中性匹配时退回第一个
    return { cation: { sym: csym, count: ccount, q: CATION_CHARGE[csym] }, anion: { id: cands[0], count: acount, q: ANIONS[cands[0]].charge } }
  }

  // 二元盐：阴离子为非阳离子元素（Cl/Br/I/S）
  const nonCation = keys.find(k => k !== csym)
  if (nonCation && ['Cl', 'Br', 'I', 'S'].indexOf(nonCation) !== -1) {
    const acount = counts[nonCation]
    const totalQ = ccount * CATION_CHARGE[csym]
    const aq = -Math.round(totalQ / acount)
    return { cation: { sym: csym, count: ccount, q: CATION_CHARGE[csym] }, anion: { id: nonCation, count: acount, q: aq } }
  }
  return null
}

function countOf(obj, keys) {
  // 组成的"份数"= 中心原子个数（第一个键）
  return obj[keys[0]]
}

/** 碱 → {metal, count, q} */
export function baseIons(formula) {
  const r = parseFormula(formula)
  if (!r.ok) return null
  const counts = r.counts
  const metal = Object.keys(counts).find(k => METALS.indexOf(k) !== -1)
  if (!metal) return null
  const ohCount = counts.O
  const mCount = counts[metal]
  const q = Math.round(ohCount / mCount)
  return { metal, count: mCount, q, ohCount }
}

/** 由阳离子+阴离子构建盐化学式 */
export function buildSalt(cationSym, cQ, anionId) {
  if (!(cQ > 0)) return null
  const an = ANIONS[anionId]
  if (!an) return null
  const g = gcd2(cQ, Math.abs(an.charge))
  const cSub = Math.abs(an.charge) / g
  const aSub = cQ / g
  const catPoly = cationSym === 'NH4'
  let s = ''
  if (catPoly && cSub > 1) s += '(NH4)' + cSub
  else s += cationSym + (cSub > 1 ? cSub : '')
  const anPoly = an.poly
  if (aSub > 1 && anPoly) s += '(' + an.label + ')' + aSub
  else if (aSub > 1) s += an.label + aSub
  else s += an.label
  return s
}

function gcd2(a, b) {
  a = Math.abs(a); b = Math.abs(b)
  while (b) { const t = b; b = a % b; a = t }
  return a || 1
}

/** 由阳离子+氢氧根构建碱化学式 */
export function buildBase(metal, q) {
  if (q === 1) return metal + 'OH'
  return metal + '(OH)' + q
}

/** 由阴离子构建新酸（用于复分解产酸） */
export function buildAcid(anionId) {
  const map = {
    Cl: 'HCl', Br: 'HBr', I: 'HI', S: 'H2S',
    NO3: 'HNO3', SO4: 'H2SO4', SO3: 'H2SO3',
    CO3: 'H2CO3', PO4: 'H3PO4', ClO3: 'HClO3'
  }
  return map[anionId] || null
}

// ---------------- 特殊反应库 ----------------

const SPECIFIC = {}

function addSpec(formulas, products, type, cond) {
  const key = formulas.slice().sort().join('+')
  SPECIFIC[key] = { reactants: formulas.slice(), products, type, cond }
}

// 分解反应
addSpec(['CaCO3'], ['CaO', 'CO2'], '分解反应', '高温')
addSpec(['H2O'], ['H2', 'O2'], '分解反应', '通电')
addSpec(['H2O2'], ['H2O', 'O2'], '分解反应', 'MnO2 催化')
addSpec(['KMnO4'], ['K2MnO4', 'MnO2', 'O2'], '分解反应', '加热')
addSpec(['KClO3'], ['KCl', 'O2'], '分解反应', 'MnO2 催化、加热')
addSpec(['Cu2(OH)2CO3'], ['CuO', 'H2O', 'CO2'], '分解反应', '加热')
addSpec(['NH4HCO3'], ['NH3', 'H2O', 'CO2'], '分解反应', '加热')
addSpec(['NaHCO3'], ['Na2CO3', 'H2O', 'CO2'], '分解反应', '加热')
addSpec(['Ca(HCO3)2'], ['CaCO3', 'H2O', 'CO2'], '分解反应', '加热')
addSpec(['H2CO3'], ['H2O', 'CO2'], '分解反应', '（不稳定，自行分解）')
addSpec(['HgO'], ['Hg', 'O2'], '分解反应', '加热')
addSpec(['Al2O3'], ['Al', 'O2'], '分解反应', '通电电解（熔融）')
addSpec(['Fe(OH)3'], ['Fe2O3', 'H2O'], '分解反应', '加热')

// 化合 / 特殊氧化还原
addSpec(['C', 'CO2'], ['CO'], '化合反应', '高温')
addSpec(['C', 'H2O'], ['CO', 'H2'], '置换反应', '高温')
addSpec(['Mg', 'CO2'], ['MgO', 'C'], '置换反应', '点燃')
addSpec(['Mg', 'N2'], ['Mg3N2'], '化合反应', '点燃')
addSpec(['N2', 'H2'], ['NH3'], '化合反应', '高温高压、催化剂')
addSpec(['Fe', 'H2O'], ['Fe3O4', 'H2'], '置换反应', '高温（水蒸气）')
addSpec(['Na2O2', 'H2O'], ['NaOH', 'O2'], '氧化还原反应', '')
addSpec(['Na2O2', 'CO2'], ['Na2CO3', 'O2'], '氧化还原反应', '')

// 三元
addSpec(['CO2', 'H2O', 'CaCO3'], ['Ca(HCO3)2'], '化合反应', '')

// ---------------- 主入口 ----------------

/**
 * 推导并配平反应
 * @param {Array<string>} reactantFormulas 已解析的反应物化学式
 * @returns {{ok:true, reaction:Object}|{ok:false, error:string}}
 */
export function solveReaction(reactantFormulas) {
  // 去重保序
  const rs = []
  for (const f of reactantFormulas) {
    if (rs.indexOf(f) === -1) rs.push(f)
  }
  if (rs.length === 0) return { ok: false, error: '请先输入至少一种反应物' }

  // 1. 特殊库精确匹配
  const key = rs.slice().sort().join('+')
  const spec = SPECIFIC[key]
  if (spec) {
    return assemble(spec.reactants, spec.products, spec.type, spec.cond)
  }

  if (rs.length === 1) {
    return { ok: false, error: '未找到 "' + rs[0] + '" 的分解反应规则\n（该物质在默认条件下不易分解）' }
  }
  if (rs.length > 3) {
    return { ok: false, error: '暂不支持超过三种物质的组合反应' }
  }

  const cats = rs.map(f => classify(f))

  // 2. 有机物燃烧
  {
    const oi = cats.indexOf('organic')
    const oxi = rs.indexOf('O2')
    if (oi !== -1 && oxi !== -1 && oi !== oxi) {
      return assemble([rs[oi], 'O2'], ['CO2', 'H2O'], '氧化反应（燃烧）', '点燃')
    }
  }

  // 3. CO 燃烧
  if (rs.indexOf('CO') !== -1 && rs.indexOf('O2') !== -1) {
    return assemble(['CO', 'O2'], ['CO2'], '氧化反应（燃烧）', '点燃')
  }

  // 4. 还原剂 + 金属氧化物
  {
    const RED = { C: ['CO2', '高温'], H2: ['H2O', '加热'], CO: ['CO2', '高温'] }
    for (let i = 0; i < rs.length; i++) {
      if (RED[rs[i]]) {
        for (let j = 0; j < rs.length; j++) {
          if (j !== i && REDUCIBLE_OXIDES.indexOf(rs[j]) !== -1) {
            const pr = parseFormula(rs[j])
            const metal = Object.keys(pr.counts).find(k => k !== 'O')
            const [prod, cond] = RED[rs[i]]
            return assemble([rs[i], rs[j]], [metal, prod], '氧化还原反应', cond)
          }
        }
      }
    }
  }

  // 5. 单质 + O2 化合
  {
    const oxi = rs.indexOf('O2')
    if (oxi !== -1) {
      const other = rs[oxi === 0 ? 1 : 0]
      const oc = classify(other)
      if (oc === 'metal') {
        const p = METAL_O2_PRODUCTS[other]
        if (p) return assemble([other, 'O2'], [p], '化合反应', other === 'Cu' ? '加热' : '点燃')
        return { ok: false, error: other + ' 与氧气在常规条件下不发生明显反应' }
      }
      if (oc === 'nonmetal') {
        const p = NONMETAL_O2_PRODUCTS[other]
        if (p) {
          const condMap = { N2: '放电', Cu: '' }
          return assemble([other, 'O2'], [p], '化合反应', condMap[other] || '点燃')
        }
      }
    }
  }

  // 6. 活泼金属(K/Ca/Na) + 水
  {
    const wi = rs.indexOf('H2O')
    if (wi !== -1) {
      const other = rs[wi === 0 ? 1 : 0]
      if (['K', 'Ca', 'Na'].indexOf(other) !== -1) {
        return assemble([other, 'H2O'], [buildBase(other, 1), 'H2'], '置换反应', '')
      }
    }
  }

  // 7. 氧化物 + 水
  {
    const wi = rs.indexOf('H2O')
    if (wi !== -1) {
      const other = rs[wi === 0 ? 1 : 0]
      if (MO_WATER[other]) {
        return assemble([other, 'H2O'], [MO_WATER[other]], '化合反应', '')
      }
      if (AO_WATER[other]) {
        return assemble([other, 'H2O'], [AO_WATER[other]], '化合反应', '')
      }
      if (classify(other) === 'mo' || classify(other) === 'ao') {
        return { ok: false, error: other + ' 不与水发生反应' }
      }
    }
  }

  // 8. 双物质规则
  if (rs.length === 2) {
    const res2 = tryTwo(rs[0], rs[1], cats[0], cats[1])
    if (res2) return res2
    return {
      ok: false,
      error: '未找到可行的化学反应：\n' + rs.join(' + ') + '\n不满足任何内置反应条件'
    }
  }

  // 9. 三物质（未命中特殊库）
  return { ok: false, error: '该三物质组合未匹配到已知反应规则' }
}

function indexOfCat(cats, cat, filter) {
  for (let i = 0; i < cats.length; i++) {
    if (cats[i] === cat && (!filter || filter(arguments[2]))) return i
  }
  return -1
}

function tryTwo(a, b, ca, cb) {
  // 尝试两种顺序的类别规则
  const orders = [[a, b, ca, cb], [b, a, cb, ca]]
  for (const [x, y, cx, cy] of orders) {
    const r =
      ruleAoBase(x, y, cx, cy) ||
      ruleMoAcid(x, y, cx, cy) ||
      ruleAcidBase(x, y, cx, cy) ||
      ruleMetalAcid(x, y, cx, cy) ||
      ruleMetalSalt(x, y, cx, cy) ||
      ruleNh4Base(x, y, cx, cy) ||
      ruleAcidSalt(x, y, cx, cy) ||
      ruleBaseSalt(x, y, cx, cy) ||
      ruleSaltSalt(x, y, cx, cy)
    if (r) return r
  }
  return null
}

/** 酸性氧化物 + 碱 → 盐 + 水 */
function ruleAoBase(x, y, cx, cy) {
  if (cx === 'ao' && cy === 'base') {
    const bi = baseIons(y)
    const salt = buildSalt(bi.metal, bi.q, AO_ANION[x])
    return assemble([x, y], [salt, 'H2O'], '复分解反应', '')
  }
  return null
}

/** 金属氧化物 + 酸 → 盐 + 水 */
function ruleMoAcid(x, y, cx, cy) {
  if (cx === 'mo' && BASIC_OXIDES_FOR_ACID[x] && cy === 'acid') {
    const [m, q] = BASIC_OXIDES_FOR_ACID[x]
    const salt = buildSalt(m, q, ACID_ANION[y])
    return assemble([x, y], [salt, 'H2O'], '复分解反应', '')
  }
  return null
}

/** 酸 + 碱 → 盐 + 水（中和） */
function ruleAcidBase(x, y, cx, cy) {
  if (cx === 'acid' && cy === 'base') {
    const bi = baseIons(y)
    const salt = buildSalt(bi.metal, bi.q, ACID_ANION[x])
    return assemble([x, y], [salt, 'H2O'], '中和反应（复分解）', '')
  }
  return null
}

/** 金属 + 酸 → 盐 + H2 */
function ruleMetalAcid(x, y, cx, cy) {
  if (cx === 'metal' && cy === 'acid') {
    if (['HCl', 'H2SO4'].indexOf(y) === -1) {
      return { ok: false, error: '金属与酸置换请使用盐酸或稀硫酸' }
    }
    const idxM = activityIndex(x)
    const idxH = activityIndex('H')
    if (idxM === -1) return null
    if (idxM >= idxH) {
      return { ok: false, error: x + ' 的金属活动性排在氢之后，不能与稀酸发生置换反应' }
    }
    if (BLOCKED_METAL_ACID.indexOf(x + '+' + y) !== -1) {
      return { ok: false, error: x + ' 与 ' + y + ' 因生成微溶物覆盖表面，反应难以持续' }
    }
    const q = DISPLACEMENT_CHARGE[x]
    const salt = buildSalt(x, q, ACID_ANION[y])
    return assemble([x, y], [salt, 'H2'], '置换反应', '')
  }
  return null
}

/** 金属 + 盐溶液 → 新金属 + 新盐 */
function ruleMetalSalt(x, y, cx, cy) {
  if (cx === 'metal' && cy === 'salt') {
    if (['K', 'Ca', 'Na'].indexOf(x) !== -1) {
      return { ok: false, error: 'K/Ca/Na 太活泼，放入盐溶液会先与水反应' }
    }
    const ions = saltIons(y)
    if (!ions) return null
    const idxX = activityIndex(x)
    const idxB = activityIndex(ions.cation.sym)
    if (idxB === -1) return null
    if (isInsoluble(y)) {
      return { ok: false, error: y + ' 不溶于水，无法发生溶液中的置换反应' }
    }
    if (idxX >= idxB) {
      return { ok: false, error: x + ' 的活动性不强于 ' + ions.cation.sym + '，不能发生置换反应' }
    }
    const q = DISPLACEMENT_CHARGE[x] != null ? DISPLACEMENT_CHARGE[x] : CATION_CHARGE[x]
    const newSalt = buildSalt(x, q, ions.anion.id)
    return assemble([x, y], [newSalt, ions.cation.sym], '置换反应', '')
  }
  return null
}

/** 碱 + 铵盐 → NH3 + 水 + 新盐 */
function ruleNh4Base(x, y, cx, cy) {
  if (cx === 'base' && cy === 'salt') {
    const ions = saltIons(y)
    if (ions && ions.cation.sym === 'NH4') {
      const bi = baseIons(x)
      const newSalt = buildSalt(bi.metal, bi.q, ions.anion.id)
      return assemble([x, y], ['NH3', 'H2O', newSalt], '复分解反应', '加热')
    }
  }
  return null
}

/**
 * 通用离子交换（酸+盐 / 碱+盐 / 盐+盐）
 * 返回组装好的结果或 null（不满足条件）
 */
function exchange(x, y, kind) {
  let ionX, ionY
  if (kind === 'acid+salt') {
    const hx = parseFormula(x)
    ionX = { cations: [{ sym: 'H', count: hx.counts.H, q: 1 }], anId: ACID_ANION[x] }
  } else if (kind === 'base+salt') {
    const bi = baseIons(x)
    ionX = { cations: [{ sym: bi.metal, count: bi.count, q: bi.q }], anId: 'OH' }
  } else {
    const si = saltIons(x)
    if (!si) return null
    ionX = { cations: [{ sym: si.cation.sym, count: si.cation.count, q: si.cation.q }], anId: si.anion.id }
  }
  const siY = saltIons(y)
  if (!siY) return null
  ionY = { cations: [{ sym: siY.cation.sym, count: siY.cation.count, q: siY.cation.q }], anId: siY.anion.id }

  // 交换产物：P1 = X阳 + Y阴；P2 = Y阳 + X阴
  let p1 = buildSalt(ionX.cations[0].sym, ionX.cations[0].q, ionY.anId)
  let p2 = buildSalt(ionY.cations[0].sym, ionY.cations[0].q, ionX.anId)

  // 产物中出现不稳定酸 → 分解放气
  const extras = []
  const unstable = { H2CO3: ['H2O', 'CO2'], H2SO3: ['H2O', 'SO2'] }
  const fix = f => {
    if (unstable[f]) {
      extras.push(...unstable[f])
      return null
    }
    return f
  }
  p1 = fix(p1); p2 = fix(p2)
  const prods = []
  if (p1) prods.push(p1)
  if (p2) prods.push(p2)

  // 可行性判断
  const hasPpt = prods.some(isInsoluble)
  const hasGas = extras.some(e => e === 'CO2' || e === 'SO2')
  const hasWater = extras.indexOf('H2O') !== -1

  if (kind === 'acid+salt') {
    if (!hasPpt && !hasGas) return null
  } else {
    // 碱+盐、盐+盐：必须生成沉淀（或铵盐产气分支已单独处理）
    if (!hasPpt) return null
  }
  void hasWater
  return prods.concat(extras)
}

/** 酸 + 盐 */
function ruleAcidSalt(x, y, cx, cy) {
  if (cx === 'acid' && cy === 'salt') {
    if (['HCl', 'H2SO4', 'HNO3'].indexOf(x) === -1) {
      return { ok: false, error: '内置规则仅支持盐酸/硫酸/硝酸与盐的复分解' }
    }
    // 碳酸盐/碳酸氢盐 → 盐 + 水 + CO2（气体分支优先）
    const siY = saltIons(y)
    if (siY && (siY.anion.id === 'CO3' || siY.anion.id === 'HCO3')) {
      const newSalt = buildSalt(siY.cation.sym, siY.cation.q, ACID_ANION[x])
      return assemble([x, y], [newSalt, 'H2O', 'CO2'], '复分解反应', '')
    }
    const prods = exchange(x, y, 'acid+salt')
    if (!prods) {
      return { ok: false, error: x + ' + ' + y + '：不满足复分解条件\n（需生成沉淀或气体）' }
    }
    return assemble([x, y], prods, '复分解反应', '')
  }
  return null
}

/** 碱 + 盐 */
function ruleBaseSalt(x, y, cx, cy) {
  if (cx === 'base' && cy === 'salt') {
    const prods = exchange(x, y, 'base+salt')
    if (!prods) {
      return { ok: false, error: x + ' + ' + y + '：不满足复分解条件\n（碱与盐反应需生成沉淀）' }
    }
    return assemble([x, y], prods, '复分解反应', '')
  }
  return null
}

/** 盐 + 盐 */
function ruleSaltSalt(x, y, cx, cy) {
  if (cx === 'salt' && cy === 'salt') {
    const prods = exchange(x, y, 'salt+salt')
    if (!prods) {
      return { ok: false, error: x + ' + ' + y + '：不满足复分解条件\n（盐与盐反应需生成沉淀）' }
    }
    return assemble([x, y], prods, '复分解反应', '')
  }
  return null
}

// ---------------- 结果组装 ----------------

/**
 * 组装最终反应对象（含配平与相对分子质量）
 */
function assemble(reactants, products, type, cond) {
  const all = reactants.concat(products)
  const parsedAll = []
  for (const f of all) {
    const r = parseFormula(f)
    if (!r.ok) return { ok: false, error: '内部错误：产物解析失败 ' + f }
    parsedAll.push(r)
  }
  const coefs = balance(parsedAll.map(p => p.counts), reactants.length)
  if (!coefs) {
    return { ok: false, error: '配平失败：该反应组合无法构成质量守恒方程' }
  }
  return {
    ok: true,
    reaction: {
      reactants,
      products,
      coefs,
      mr: parsedAll.map(p => molarMass(p.counts)),
      segs: parsedAll.map(p => p.segs),
      type,
      cond
    }
  }
}
