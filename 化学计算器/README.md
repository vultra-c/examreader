# 化学反应计算器（小米 Vela 轻应用）

纯离线运行的化学反应计算工具，适配小米 Vela 穿戴设备。所有计算、元素数据、反应规则库均本地内置，无需联网。

- 包名：`com.whyy.chemcalc`
- 当前版本：`V26.8.29.CALC`（versionCode 2608290）
- 设计尺寸：336 × 480（designWidth 336，与考点阅读器一致）

## 功能

1. **反应物输入识别**：支持标准化学式（`HCl+NaOH`、`Cu2(OH)2CO3`）与物质中文名称（`盐酸+烧碱`），两种方式可混用，`+` 分隔。自动过滤非法字符并给出明确错误提示。
2. **全自动生成物推导**：内置反应规则引擎，覆盖化合、分解、置换、复分解（含中和）、氧化还原、有机燃烧等常规反应；无法反应时给出原因提示，不乱生成。
3. **智能配平**：分数制高斯消元求质量守恒零空间，输出最小正整数计量系数。
4. **双向质量计量**：任选方程式中一种物质输入已知质量，按配平比例正向/反向推算其余所有物质的质量与物质的量。
5. **离线元素数据库**：36 种常用元素的 standard 相对原子质量 + 金属活动性顺序 + 常见离子化合价 + 溶解性表。
6. **一键重置**：清空按钮随时重新输入，无限次计算。

## 页面结构

| 路由 | 说明 |
|------|------|
| `pages/index` | 输入页：考点阅读器同款输入法（中文/日文/英文）与物质名称目录双模式 |
| `pages/result` | 方程式页：带下标渲染的配平方程式、反应类型、条件、各物质相对分子质量 |
| `pages/mass` | 质量计算页：选择已知物质 → 数字键盘输入质量 → 批量推算结果 |

所有页面支持右滑退出（`@swipe right → router.back()`），操作逻辑与考点阅读器一致。

## 目录

```
化学计算器/
├── package.json            # aiot-toolkit 构建配置
├── src/components/InputMethod/  # 考点阅读器同款中文/日文/英文输入法
├── scripts/patch-aiotpack.js
├── sign/
│   ├── release/            # 发布签名 private.pem + certificate.pem（已推送仓库）
│   └── debug/
├── src/
│   ├── manifest.json
│   ├── app.ux
│   ├── common/
│   │   ├── style.css       # 主题（纯黑底 + 深灰圆角卡片 + #0D6EFF 蓝强调）
│   │   ├── images/icon.png # 应用图标（锥形瓶）
│   │   └── logic/          # 核心算法（纯 JS，可独立测试）
│   │       ├── elements.js     # 元素/活动性/离子/溶解性数据
│   │       ├── parser.js       # 化学式解析与名称解析
│   │       ├── balance.js      # 配平算法
│   │       ├── reactions.js    # 反应规则引擎
│   │       └── stoich.js       # 计量计算
│   ├── components/FormulaText/  # 下标渲染组件
│   └── pages/{index,result,mass}/
├── tests/smoke.mjs         # Node 冒烟测试（62 项断言）
└── ../release/             # 根目录统一存放的签名发布包 *.rpk
```

## 构建与签名

```bash
# 安装依赖（如独立环境）
npm install

# 构建 + 使用 sign/release 签名，产物在 dist/
npm run release          # 即 aiot release --enable-jsc

# 运行核心逻辑测试
node tests/smoke.mjs
```

签名证书由本仓库自带生成（RSA 2048，CN=com.whyy.chemcalc，有效期 30 年）。若需更换：

```bash
openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out sign/release/private.pem
openssl req -new -x509 -key sign/release/private.pem -out sign/release/certificate.pem \
  -days 10950 -subj "/CN=com.whyy.chemcalc/O=WenHuaYiYang/C=CN"
```

## 与考点阅读器的关系

两个项目源码完全独立分目录存放（`考点阅读器/` 与 `化学计算器/`），互不影响；仅共用同一套视觉风格与交互习惯。构建工具链相同（aiot-toolkit），开发环境可通过软链共享 `node_modules`。
