/**
 * 化学计算器 - 物质名称库与分类目录
 * 中文名称 → 化学式；目录按类别分组展示
 */

/** 别名/俗名 → 标准化学式 */
export const NAME_MAP = {
  // 单质
  '氢气': 'H2', '氧气': 'O2', '氮气': 'N2', '氯气': 'Cl2',
  '碳': 'C', '木炭': 'C', '活性炭': 'C', '硫': 'S', '硫磺': 'S',
  '磷': 'P', '红磷': 'P', '白磷': 'P', '硅': 'Si',
  '钠': 'Na', '钾': 'K', '钙': 'Ca', '镁': 'Mg', '铝': 'Al',
  '锌': 'Zn', '铁': 'Fe', '铜': 'Cu', '银': 'Ag', '金': 'Au',
  '汞': 'Hg', '水银': 'Hg', '铅': 'Pb', '锡': 'Sn', '钡': 'Ba',

  // 氧化物
  '水': 'H2O', '氧化汞': 'HgO',
  '一氧化碳': 'CO', '二氧化碳': 'CO2', '干冰': 'CO2',
  '二氧化硫': 'SO2', '三氧化硫': 'SO3',
  '五氧化二磷': 'P2O5', '二氧化氮': 'NO2', '二氧化锰': 'MnO2',
  '二氧化硅': 'SiO2',
  '氧化镁': 'MgO', '氧化钙': 'CaO', '生石灰': 'CaO',
  '氧化钠': 'Na2O', '过氧化钠': 'Na2O2',
  '氧化铜': 'CuO', '氧化铁': 'Fe2O3', '三氧化二铁': 'Fe2O3',
  '铁锈主要成分': 'Fe2O3', '四氧化三铁': 'Fe3O4',
  '氧化铝': 'Al2O3', '氧化锌': 'ZnO', '氧化钡': 'BaO', '氧化银': 'Ag2O',

  // 酸
  '盐酸': 'HCl', '氢氯酸': 'HCl',
  '硫酸': 'H2SO4', '硝酸': 'HNO3',
  '碳酸': 'H2CO3', '亚硫酸': 'H2SO3', '磷酸': 'H3PO4',

  // 碱
  '氢氧化钠': 'NaOH', '烧碱': 'NaOH', '火碱': 'NaOH', '苛性钠': 'NaOH',
  '氢氧化钾': 'KOH', '氢氧化钙': 'Ca(OH)2', '熟石灰': 'Ca(OH)2', '消石灰': 'Ca(OH)2',
  '氢氧化钡': 'Ba(OH)2', '氢氧化镁': 'Mg(OH)2',
  '氢氧化铜': 'Cu(OH)2', '氢氧化铁': 'Fe(OH)3', '氢氧化亚铁': 'Fe(OH)2',
  '氢氧化铝': 'Al(OH)3', '氢氧化锌': 'Zn(OH)2',

  // 盐
  '氯化钠': 'NaCl', '食盐': 'NaCl', '食盐主要成分': 'NaCl',
  '氯化钾': 'KCl', '氯化钡': 'BaCl2', '氯化钙': 'CaCl2',
  '氯化银': 'AgCl', '氯化镁': 'MgCl2', '氯化铜': 'CuCl2', '氯化锌': 'ZnCl2', '氯化铝': 'AlCl3',
  '硫酸铜': 'CuSO4', '硫酸锌': 'ZnSO4', '硫酸亚铁': 'FeSO4', '硫酸铁': 'Fe2(SO4)3',
  '硫酸钠': 'Na2SO4', '硫酸钾': 'K2SO4', '硫酸钡': 'BaSO4', '硫酸镁': 'MgSO4', '硫酸铝': 'Al2(SO4)3',
  '硝酸钠': 'NaNO3', '硝酸钾': 'KNO3', '硝酸银': 'AgNO3', '硝酸钡': 'Ba(NO3)2',
  '硝酸铜': 'Cu(NO3)2', '硝酸钙': 'Ca(NO3)2', '硝酸镁': 'Mg(NO3)2', '硝酸铝': 'Al(NO3)3',
  '碳酸钙': 'CaCO3', '石灰石主要成分': 'CaCO3', '大理石主要成分': 'CaCO3',
  '石灰石': 'CaCO3', '大理石': 'CaCO3',
  '碳酸钠': 'Na2CO3', '纯碱': 'Na2CO3', '苏打': 'Na2CO3',
  '碳酸氢钠': 'NaHCO3', '小苏打': 'NaHCO3',
  '碳酸钾': 'K2CO3', '碳酸钡': 'BaCO3', '碳酸镁': 'MgCO3',
  '高锰酸钾': 'KMnO4', '锰酸钾': 'K2MnO4', '氯酸钾': 'KClO3',
  '碱式碳酸铜': 'Cu2(OH)2CO3', '铜绿': 'Cu2(OH)2CO3',
  '碳酸氢钙': 'Ca(HCO3)2',
  '氯化铵': 'NH4Cl', '硫酸铵': '(NH4)2SO4', '碳酸氢铵': 'NH4HCO3', '硝酸铵': 'NH4NO3',
  '氨气': 'NH3',

  // 有机物
  '甲烷': 'CH4', '天然气主要成分': 'CH4', '沼气主要成分': 'CH4',
  '乙醇': 'C2H5OH', '酒精': 'C2H5OH',
  '甲醇': 'CH3OH', '乙炔': 'C2H2',
  '葡萄糖': 'C6H12O6', '蔗糖': 'C12H22O11', '醋酸': 'CH3COOH'
}

/** 目录分组（名称选择页用） */
export const CATALOG = [
  {
    group: '金属单质',
    items: ['K', 'Ca', 'Na', 'Mg', 'Al', 'Zn', 'Fe', 'Sn', 'Pb', 'Cu', 'Hg', 'Ag', 'Au']
  },
  {
    group: '非金属单质',
    items: ['H2', 'O2', 'N2', 'Cl2', 'C', 'S', 'P', 'Si']
  },
  {
    group: '氧化物',
    items: ['H2O', 'CO', 'CO2', 'SO2', 'SO3', 'P2O5', 'NO2', 'MnO2', 'SiO2',
      'MgO', 'CaO', 'Na2O', 'Na2O2', 'CuO', 'Fe2O3', 'Fe3O4', 'Al2O3', 'ZnO', 'BaO', 'Ag2O', 'HgO']
  },
  {
    group: '酸',
    items: ['HCl', 'H2SO4', 'HNO3', 'H2CO3', 'H2SO3', 'H3PO4']
  },
  {
    group: '碱',
    items: ['NaOH', 'KOH', 'Ca(OH)2', 'Ba(OH)2', 'Mg(OH)2', 'Cu(OH)2', 'Fe(OH)2', 'Fe(OH)3', 'Al(OH)3', 'Zn(OH)2']
  },
  {
    group: '盐',
    items: ['NaCl', 'KCl', 'BaCl2', 'CaCl2', 'MgCl2', 'CuCl2', 'ZnCl2', 'AlCl3', 'AgCl',
      'CuSO4', 'ZnSO4', 'FeSO4', 'Fe2(SO4)3', 'Na2SO4', 'K2SO4', 'BaSO4', 'MgSO4',
      'NaNO3', 'KNO3', 'AgNO3', 'Ba(NO3)2', 'Cu(NO3)2',
      'CaCO3', 'Na2CO3', 'NaHCO3', 'K2CO3', 'BaCO3',
      'KMnO4', 'K2MnO4', 'KClO3', 'Cu2(OH)2CO3', 'Ca(HCO3)2',
      'NH4Cl', '(NH4)2SO4', 'NH4HCO3', 'NH4NO3', 'NH3']
  },
  {
    group: '有机物',
    items: ['CH4', 'C2H5OH', 'CH3OH', 'C2H2', 'C6H12O6', 'C12H22O11', 'CH3COOH']
  }
]

/** 化学式 → 常用名（展示用，取第一个匹配名） */
let formulaToName = null
export function formulaName(formula) {
  if (!formulaToName) {
    formulaToName = {}
    for (const n in NAME_MAP) {
      const f = NAME_MAP[n]
      if (!formulaToName[f]) formulaToName[f] = n
    }
  }
  return formulaToName[formula] || ''
}
