/**
 * base64 编解码与文件名消毒工具（公式图片传输用）。
 *
 * base64ToArrayBuffer 移植自参考工程 com.bandbbs.ebook/src/utils/interconnfile.js:581-611
 * （纯 JS 手写解码，无第三方依赖，Vela 快应用环境无 atob/btoa）。
 */

// base64 → Uint8Array.buffer。非法字符静默跳过；空串返回 0 长度 buffer。
function base64ToArrayBuffer(base64) {
  base64 = (base64 || '').replace(/[\s\r\n]/g, '')
  const len = base64.length
  if (len === 0) return new ArrayBuffer(0)
  const b64lookup = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
  let paddingCount = 0
  if (base64.charAt(len - 1) === '=') paddingCount++
  if (base64.charAt(len - 2) === '=') paddingCount++
  const bufferLength = (len * 3 / 4) - paddingCount
  const bytes = new Uint8Array(bufferLength)
  let p = 0
  for (let i = 0; i < len; i += 4) {
    const encoded1 = b64lookup.indexOf(base64[i])
    const encoded2 = b64lookup.indexOf(base64[i + 1])
    const encoded3 = b64lookup.indexOf(base64[i + 2])
    const encoded4 = b64lookup.indexOf(base64[i + 3])
    if (encoded1 < 0 || encoded2 < 0) continue
    bytes[p++] = (encoded1 << 2) | (encoded2 >> 4)
    if (encoded3 !== -1 && encoded3 !== 64 && p < bufferLength) bytes[p++] = ((encoded2 & 15) << 4) | (encoded3 >> 2)
    if (encoded4 !== -1 && encoded4 !== 64 && p < bufferLength) bytes[p++] = ((encoded3 & 3) << 6) | (encoded4 & 63)
  }
  return bytes.buffer
}

// 文件名消毒：仅保留安全字符，强制 .png 结尾，防路径穿越（手机端发来的文件名不可信）。
// 规范文件名应为 md5(subject#id) 前 12 位 + ".png"，此处兜底任何非法/恶意输入。
function sanitizeFileName(name) {
  const base = String(name || '').replace(/[^A-Za-z0-9._-]/g, '').slice(0, 64)
  if (!base) return 'unknown.png'
  return /\.png$/i.test(base) ? base : base + '.png'
}

export { base64ToArrayBuffer, sanitizeFileName }
export default { base64ToArrayBuffer, sanitizeFileName }
