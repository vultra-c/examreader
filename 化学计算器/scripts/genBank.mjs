/**
 * 生成「元素 → 方程式」索引库
 * 用法: node scripts/genBank.mjs > src/common/logic/elementBank.js
 * 通过现有 solveReaction 引擎喂入精心挑选的反应物组合，收集全部成功配平的反应。
 */
import { solveReaction } from '../src/common/logic/reactions.js'
import { parseFormula, molarMass } from '../src/common/logic/parser.js'

const METALS = ['Na','K','Ca','Mg','Al','Zn','Fe','Cu','Ag','Ba','Hg','Pb','Sn','Li']
const ACIDS = ['HCl','H2SO4','HNO3','H3PO4','H2SO3','H2CO3']
const BASES = ['NaOH','KOH','Ca(OH)2','Ba(OH)2','Mg(OH)2','Al(OH)3','Fe(OH)3','Cu(OH)2','NH3·H2O']
const METAL_OXIDES = ['Na2O','K2O','CaO','BaO','MgO','Al2O3','ZnO','Fe2O3','Fe3O4','CuO','Ag2O']
const NONMETAL_OXIDES = ['CO2','SO2','SO3','P2O5','NO2']
const SALTS = [
  'NaCl','KCl','CaCl2','BaCl2','AgCl','CuCl2','ZnCl2','FeCl3','AlCl3','MgCl2',
  'Na2SO4','K2SO4','BaSO4','CuSO4','ZnSO4','FeSO4','Fe2(SO4)3','MgSO4','Al2(SO4)3',
  'NaNO3','KNO3','AgNO3','Ba(NO3)2','Cu(NO3)2','Ca(NO3)2','Mg(NO3)2',
  'Na2CO3','K2CO3','CaCO3','BaCO3','MgCO3','NaHCO3','KMnO4','KClO3','NH4Cl','(NH4)2SO4','NH4NO3','NH4HCO3'
]
const REDUCIBLE = ['CuO','Fe2O3','Fe3O4','ZnO','MgO','Al2O3','PbO']
const NONMETALS = ['C','S','P','H2','N2','O2','Cl2','Si']
const ORGANICS = ['CH4','C2H5OH','CH3OH','C2H2','C6H12O6','CH3COOH','C12H22O11']
const WATER = ['H2O']

const combos = []
const add = (arr) => combos.push(arr)

// 单质分解
for (const m of ['CaCO3','H2O','H2O2','KMnO4','KClO3','Cu2(OH)2CO3','NH4HCO3','NaHCO3','Ca(HCO3)2','H2CO3','HgO','Al2O3','Fe(OH)3','Mg(OH)2','Cu(OH)2','NH4Cl']) add([m])

// 金属 + 氧气
for (const m of METALS) add([m, 'O2'])
// 非金属 + 氧气
for (const nm of ['C','S','P','H2','N2','Si']) add([nm, 'O2'])
// 非金属 + 氯气/硫/氢气
for (const m of ['Na','Mg','Al','Fe','Cu']) add([m, 'Cl2'])
add(['Fe','S']); add(['Cu','S']); add(['H2','Cl2']); add(['N2','H2'])

// 金属氧化物 + 水 / 酸性氧化物 + 水
for (const mo of ['Na2O','K2O','CaO','BaO']) add([mo, 'H2O'])
for (const ao of ['CO2','SO2','SO3','P2O5']) add([ao, 'H2O'])

// 金属 + 酸（活动性顺序）
for (const m of ['Mg','Al','Zn','Fe']) for (const a of ['HCl','H2SO4']) add([m, a])

// 金属 + 盐（活动性）
add(['Fe','CuSO4']); add(['Fe','CuCl2']); add(['Zn','CuSO4']); add(['Zn','AgNO3'])
add(['Cu','AgNO3']); add(['Cu','Hg(NO3)2']); add(['Zn','FeSO4']); add(['Fe','AgNO3'])
add(['Mg','CuSO4']); add(['Al','CuSO4']); add(['Zn','CuCl2']); add(['Al','AgNO3'])

// 碱性氧化物 + 酸
for (const mo of ['CaO','CuO','Fe2O3','Al2O3','ZnO','MgO','Na2O']) for (const a of ['HCl','H2SO4']) add([mo, a])

// 酸性氧化物 + 碱
for (const ao of ['CO2','SO2','SO3']) for (const b of ['NaOH','Ca(OH)2','KOH']) add([ao, b])

// 酸 + 碱（中和）
for (const a of ['HCl','H2SO4','HNO3']) for (const b of ['NaOH','KOH','Ca(OH)2','Ba(OH)2','Al(OH)3','Fe(OH)3','Cu(OH)2','Mg(OH)2']) add([a, b])
// 氨水 + 酸
for (const a of ['HCl','H2SO4','HNO3']) add([a, 'NH3'])

// 酸 + 盐（生成气体/沉淀/水）
add(['HCl','Na2CO3']); add(['HCl','CaCO3']); add(['HCl','NaHCO3']); add(['HCl','AgNO3'])
add(['HCl','BaCO3']); add(['HCl','MgCO3'])
add(['H2SO4','Na2CO3']); add(['H2SO4','BaCl2']); add(['H2SO4','Ba(NO3)2']); add(['H2SO4','Na2SO3'])
add(['HNO3','Na2CO3']); add(['HNO3','CaCO3']); add(['HNO3','AgNO3'])

// 碱 + 盐（沉淀）
add(['NaOH','CuSO4']); add(['NaOH','FeCl3']); add(['NaOH','FeCl2']); add(['NaOH','MgSO4'])
add(['NaOH','AlCl3']); add(['NaOH','ZnSO4']); add(['NaOH','CuCl2'])
add(['Ca(OH)2','Na2CO3']); add(['Ba(OH)2','Na2SO4']); add(['Ba(OH)2','CuSO4'])
add(['KOH','FeCl3']); add(['Ca(OH)2','CuSO4']); add(['Ca(OH)2','MgCl2'])

// 盐 + 盐（沉淀）
add(['NaCl','AgNO3']); add(['BaCl2','Na2SO4']); add(['BaCl2','K2SO4']); add(['BaCl2','MgSO4'])
add(['Ba(NO3)2','Na2SO4']); add(['Ba(NO3)2','CuSO4']); add(['BaCl2','Na2CO3']); add(['BaCl2','AgNO3'])
add(['CaCl2','Na2CO3']); add(['CaCl2','AgNO3']); add(['KCl','AgNO3']); add(['MgCl2','AgNO3'])
add(['BaCl2','CuSO4']); add(['AgNO3','Na2CO3'])

// 还原性气体/碳 + 金属氧化物
for (const mo of ['CuO','Fe2O3','Fe3O4','ZnO']) add(['H2', mo])
for (const mo of ['CuO','Fe2O3','Fe3O4','ZnO','MgO','Al2O3','PbO']) add(['CO', mo])
for (const mo of ['CuO','Fe2O3','Fe3O4','ZnO','PbO']) add(['C', mo])
add(['CO','Fe2O3']); add(['C','CO2']); add(['C','H2O']); add(['Mg','CO2']); add(['Mg','N2'])
add(['Fe','H2O']); add(['Na2O2','H2O']); add(['Na2O2','CO2'])

// 有机物燃烧
for (const o of ORGANICS) add([o, 'O2'])

// 三元
add(['CO2','H2O','CaCO3']); add(['CO2','H2O','Ca(OH)2']); add(['CO2','NaOH','Ca(OH)2'])

// ---- 执行 ----
const seen = new Set()
const bank = []
for (const combo of combos) {
  const res = solveReaction(combo)
  if (!res.ok || !res.reaction) continue
  const r = res.reaction
  const key = r.reactants.slice().sort().join('+') + '=' + r.products.slice().sort().join('+')
  if (seen.has(key)) continue
  seen.add(key)
  // 元素集合
  const elemSet = new Set()
  for (const f of r.reactants.concat(r.products)) {
    const pr = parseFormula(f)
    if (pr.ok) for (const k of Object.keys(pr.counts)) elemSet.add(k)
  }
  const elems = Array.from(elemSet).sort().join(' ')
  bank.push({
    r: r.reactants,
    p: r.products,
    c: r.coefs,
    t: r.type,
    n: r.cond || '',
    e: elems
  })
}

// 输出
const out = `/** 元素索引反应库（由 scripts/genBank.mjs 自动生成） */
export const ELEMENT_BANK = ${JSON.stringify(bank, null, 0)}
`
process.stdout.write(out)
console.error(`[genBank] 共生成 ${bank.length} 条反应`, process.stderr)
