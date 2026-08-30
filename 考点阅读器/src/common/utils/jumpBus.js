/**
 * 跨页跳转请求邮箱（globalThis 载体）。
 * aiot-toolkit 会把公共模块内联进每个页面 bundle，模块级变量跨页不共享；
 * globalThis 已在真机验证跨页共享（app.ux 互连接口同机制），
 * 因此 pageJump / percentJump / chapterList / search 发起的跳转请求
 * 统一经此邮箱传递，由 reader / jsonReader 在 onShow / 初始化时消费。
 */

const JUMP_KEY = '__kdJumpReq'
const REFRESH_KEY = '__kdRefreshReader'
const JSONPOS_KEY = '__kdJsonPos'

/**
 * 写入一条跳转请求（单槽覆盖）。
 * @param {{type: 'page'|'percent'|'char'|'json', value?: number, path: string, subject?: string, index?: number}} req
 */
function requestJump(req) {
  globalThis[JUMP_KEY] = req
}

/** 查看当前请求（不消费） */
function peekJump() {
  return globalThis[JUMP_KEY] || null
}

/** 取出并清除当前请求 */
function consumeJump() {
  const req = globalThis[JUMP_KEY]
  globalThis[JUMP_KEY] = null
  return req || null
}

/** 请求阅读器刷新（删除当前内容后回退） */
function requestRefresh() {
  globalThis[REFRESH_KEY] = true
}

/** 取出并清除刷新请求 */
function consumeRefresh() {
  const v = globalThis[REFRESH_KEY]
  globalThis[REFRESH_KEY] = false
  return !!v
}

/**
 * 记录 JSON 知识点一维阅读位置（percentJump 显示当前进度用）
 * @param {{index: number, total: number}} pos
 */
function setJsonPos(pos) {
  globalThis[JSONPOS_KEY] = pos
}

/** 读取 JSON 知识点一维阅读位置 */
function getJsonPos() {
  return globalThis[JSONPOS_KEY] || null
}

export default {
  requestJump,
  peekJump,
  consumeJump,
  requestRefresh,
  consumeRefresh,
  setJsonPos,
  getJsonPos
}
