/**
 * 兼容性补丁：@aiot-toolkit/aiotpack 2.0.x 的 JS 编译器默认使用 @rspack/core，
 * 其原生 binding（rspack.*.node）在部分环境（容器/沙箱/旧内核）下会触发
 * SIGBUS 导致 `aiot build/release` 直接崩溃（Bus error, core dumped）。
 *
 * SIGBUS 是致命信号，无法用 try/catch 兜住，因此本脚本直接把
 * @rspack/core 的入口替换为 webpack5 的转发 shim，并让编译器在
 * 「无 rspack API」时回退到 terser 压缩，构建产物与 rspack 一致。
 *
 * 通过 package.json 的 postinstall 钩子自动执行，可重复运行（幂等）。
 */
const fs = require('fs')
const path = require('path')

const pkgRoot = path.join(__dirname, '..', 'node_modules')

// ---------- 1) @rspack/core 入口 -> webpack 转发 shim ----------
let rspackCoreStubbed = false
try {
  const coreMain = path.join(pkgRoot, '@rspack', 'core', 'dist', 'index.js')
  if (fs.existsSync(coreMain)) {
    const src = fs.readFileSync(coreMain, 'utf8')
    if (src.indexOf('patch-aiotpack') === -1) {
      const shim = [
        '// patch-aiotpack: forward to webpack5; the native rspack binding',
        '// SIGBUSes in some sandboxes, so it must never be loaded here.',
        'module.exports = require("webpack");'
      ].join('\n')
      fs.writeFileSync(coreMain, shim)
      console.log('[patch-aiotpack] @rspack/core entry -> webpack5 shim')
    }
    rspackCoreStubbed = true
  }
} catch (e) {
  console.error('[patch-aiotpack] failed to stub @rspack/core:', e.message)
}

// ---------- 2) aiotpack 编译器适配 ----------
const targets = [
  path.join(pkgRoot, '@aiot-toolkit', 'aiotpack', 'lib', 'compiler', 'javascript', 'JavascriptCompiler.js'),
  path.join(pkgRoot, '@aiot-toolkit', 'aiotpack', 'lib', 'compiler', 'javascript', 'vela', 'plugin', 'WrapPlugin.js'),
  path.join(pkgRoot, '@aiot-toolkit', 'aiotpack', 'lib', 'compiler', 'javascript', 'android', 'plugin', 'WrapPlugin.js')
]

const requireOld = 'var _core = require("@rspack/core");'
const requireNew = [
  '// patch-aiotpack: prefer rspack, fall back to webpack5 when unavailable',
  'var _core;',
  'try {',
  '  _core = require("@rspack/core");',
  '  if (!_core || !_core.rspack) { throw new Error("rspack unavailable"); }',
  '} catch (e) {',
  '  var _w = require("webpack");',
  '  // webpack 导出被冻结，用可写包装函数拷贝其静态属性',
  '  _core = Object.assign(function () { return _w.apply(null, arguments); }, _w);',
  '  _core.rspack = _core; // webpack5 主导出即编译工厂，可直接当 rspack() 用',
  '  _core.__isWebpackFallback = true;',
  '}'
].join('\n')

// 锚点：在两个 const 箭头函数（translateDropConsole/createCompressValue）定义之后、
// 原生分支之前注入回退逻辑，避免 "Cannot access before initialization"。
const minimizerOld = '    if (mode === _CompileMode.default.DEVELOPMENT) {\n      return new _core.rspack.SwcJsMinimizerRspackPlugin({'
const minimizerNew = [
  '    // patch-aiotpack: webpack fallback uses terser instead of swc minimizer',
  '    if (_core.__isWebpackFallback || !_core.rspack) {',
  '      const TerserPlugin = require("terser-webpack-plugin");',
  '      const compressValue = createCompressValue(translateDropConsole());',
  '      const devMode = mode === _CompileMode.default.DEVELOPMENT;',
  '      if (devMode) {',
  '        return new TerserPlugin({ terserOptions: { module: true, mangle: false, format: { beautify: true, comments: true }, compress: Object.assign({ defaults: false }, compressValue) } });',
  '      }',
  '      return new TerserPlugin({ terserOptions: { module: true, compress: compressValue } });',
  '    }',
  '    if (mode === _CompileMode.default.DEVELOPMENT) {',
  '      return new _core.rspack.SwcJsMinimizerRspackPlugin({'
].join('\n')

if (!rspackCoreStubbed && !fs.existsSync(path.join(pkgRoot, '@rspack'))) {
  console.log('[patch-aiotpack] @rspack not installed yet; compiler patches still applied for safety.')
}

for (const target of targets) {
  if (!fs.existsSync(target)) continue
  let src = fs.readFileSync(target, 'utf8')

  // 幂等：已打补丁则跳过
  if (src.indexOf('patch-aiotpack') !== -1) {
    console.log('[patch-aiotpack] already applied:', path.basename(path.dirname(target)), '/', path.basename(target))
    continue
  }

  if (src.indexOf(requireOld) === -1) {
    console.error('[patch-aiotpack] unexpected layout in', target, '- skipping this file.')
    continue
  }
  src = src.replace(requireOld, requireNew)

  // 仅 JavascriptCompiler 含 minimizer 构造，需要换成 terser
  if (src.indexOf(minimizerOld) !== -1) {
    src = src.replace(minimizerOld, minimizerNew)
  }

  fs.writeFileSync(target, src)
  console.log('[patch-aiotpack] applied:', path.basename(target))
}

console.log('[patch-aiotpack] done.')
