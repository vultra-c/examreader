/**
 * 化学计算器 - 元素与化学基础数据（全部离线内置）
 */

/** 标准相对原子质量 */
export const ATOMIC_MASSES = {
  H: 1.008, He: 4.003, Li: 6.941, Be: 9.012, B: 10.811,
  C: 12.011, N: 14.007, O: 15.999, F: 18.998, Ne: 20.180,
  Na: 22.990, Mg: 24.305, Al: 26.982, Si: 28.086, P: 30.974,
  S: 32.065, Cl: 35.453, Ar: 39.948, K: 39.098, Ca: 40.078,
  Ti: 47.867, Cr: 51.996, Mn: 54.938, Fe: 55.845, Ni: 58.693,
  Cu: 63.546, Zn: 65.409, As: 74.922, Br: 79.904, Ag: 107.868,
  I: 126.904, Ba: 137.327, Pt: 195.084, Au: 196.967, Hg: 200.591,
  Pb: 207.2, Sn: 118.71, W: 183.84
}

/** 金属活动性顺序（由强到弱） */
export const ACTIVITY = ['K', 'Ca', 'Na', 'Mg', 'Al', 'Zn', 'Fe', 'Sn', 'Pb', 'H', 'Cu', 'Hg', 'Ag', 'Pt', 'Au']

export function activityIndex(sym) {
  const i = ACTIVITY.indexOf(sym)
  return i
}

/** 常见金属单质 */
export const METALS = ['K', 'Ca', 'Na', 'Mg', 'Al', 'Zn', 'Fe', 'Sn', 'Pb', 'Cu', 'Hg', 'Ag', 'Pt', 'Au', 'Ba']

/** 常见非金属单质（化学式形式） */
export const NONMETALS = ['H2', 'O2', 'N2', 'Cl2', 'F2', 'C', 'S', 'P', 'Si']

/** 阳离子 → 常见化合价（盐的组成用） */
export const CATION_CHARGE = {
  K: 1, Na: 1, Ag: 1, NH4: 1,
  Ca: 2, Ba: 2, Mg: 2, Cu: 2, Zn: 2, Fe: 3, Sn: 2, Pb: 2, Hg: 2,
  Al: 3
}

/** 置换反应/金属与酸反应中，变价金属按低价成盐 */
export const DISPLACEMENT_CHARGE = {
  K: 1, Ca: 2, Na: 1, Mg: 2, Al: 3, Zn: 2, Fe: 2, Sn: 2, Pb: 2,
  Cu: 2, Hg: 2, Ag: 1
}

/** 常见酸根/阴离子：id → {label, charge, poly} */
export const ANIONS = {
  Cl: { label: 'Cl', charge: 1, poly: false },
  Br: { label: 'Br', charge: 1, poly: false },
  I: { label: 'I', charge: 1, poly: false },
  S: { label: 'S', charge: 2, poly: false },
  OH: { label: 'OH', charge: 1, poly: true },
  NO3: { label: 'NO3', charge: 1, poly: true },
  SO4: { label: 'SO4', charge: 2, poly: true },
  SO3: { label: 'SO3', charge: 2, poly: true },
  CO3: { label: 'CO3', charge: 2, poly: true },
  HCO3: { label: 'HCO3', charge: 1, poly: true },
  MnO4: { label: 'MnO4', charge: 1, poly: true },
  MnO4b: { label: 'MnO4', charge: 2, poly: true },
  ClO3: { label: 'ClO3', charge: 1, poly: true },
  PO4: { label: 'PO4', charge: 3, poly: true }
}

/** 由阴离子组成识别酸根：组成签名 → 候选阴离子 id 列表（按常见优先） */
export const RADICAL_SIGNATURES = {
  'N1O3': ['NO3'],
  'S1O4': ['SO4'],
  'S1O3': ['SO3'],
  'C1O3': ['CO3'],
  'C1H1O3': ['HCO3'],
  'Mn1O4': ['MnO4', 'MnO4b'],
  'Cl1O3': ['ClO3'],
  'P1O4': ['PO4']
}

/** 不溶性（沉淀）产物集合：用于复分解反应可行性判断 */
export const INSOLUBLE = [
  'AgCl', 'BaSO4', 'PbSO4',
  'CaCO3', 'BaCO3', 'Ag2CO3',
  'MgCO3', 'ZnCO3',
  'Cu(OH)2', 'Fe(OH)2', 'Fe(OH)3', 'Mg(OH)2', 'Al(OH)3', 'Zn(OH)2',
  'AgBr', 'AgI', 'CaSO3', 'BaSO3'
]

export function isInsoluble(formula) {
  return INSOLUBLE.indexOf(formula) !== -1
}

/** 微溶导致反应难以发生的组合（金属+稀硫酸） */
export const BLOCKED_METAL_ACID = [
  'Pb+H2SO4', 'Ca+H2SO4'
]

/** 可被 C/H2/CO 还原的金属氧化物 */
export const REDUCIBLE_OXIDES = ['CuO', 'Fe2O3', 'Fe3O4', 'ZnO', 'PbO', 'Ag2O']

/** 金属氧化物 + 氧气化合的产物表 */
export const METAL_O2_PRODUCTS = {
  Mg: 'MgO', Al: 'Al2O3', Fe: 'Fe3O4', Cu: 'CuO',
  Na: 'Na2O', K: 'K2O', Ca: 'CaO', Zn: 'ZnO', Ba: 'BaO'
}

/** 非金属单质 + 氧气化合的产物表 */
export const NONMETAL_O2_PRODUCTS = {
  C: 'CO2', S: 'SO2', P: 'P2O5', Si: 'SiO2', H2: 'H2O', N2: 'NO'
}
