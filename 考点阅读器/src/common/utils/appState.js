/**
 * 考点阅读器 - 跨页面共享状态
 * 替代 globalThis，在 Vela JS 框架中通过模块缓存实现页面间数据传递
 */

const state = {
  // 跳转目标页码（pageJump / percentJump -> reader）
  jumpToPage: null,

  // 跳转目标百分比（无缝模式 percentJump -> reader）
  jumpToPercent: null,

  // 当前阅读模式（'paginated' 分页 / 'seamless' 无缝）
  readingMode: 'paginated',

  // 是否需要自动返回到 reader（pageJump / percentJump / deleteConfirm -> readerSettings -> reader）
  needBackToReader: false,

  // 是否需要刷新 reader（deleteConfirm 删除后 -> reader 自动返回上级）
  refreshReader: false,

  // 是否需要刷新列表（deleteConfirm 删除后 -> index / subfolder 重新加载）
  needRefreshList: false,

  // 当前阅读路径（reader -> readerSettings / pageJump / percentJump）
  readerPath: '',

  // 当前阅读页码（reader -> pageJump / percentJump）
  readerPage: 0,

  // 当前内容名称（reader -> readerSettings）
  contentName: '',

  // 当前阅读总页数（reader -> pageJump / percentJump）
  totalPages: 0,

  // 当前阅读字号（缓存 storage 读取，reader -> readerSettings）
  fontSize: 26,

  // 当前阅读行间距（缓存 storage 读取，reader -> readerSettings）
  lineHeight: 30,

  // JSON 阅读器一维进度（jsonReader -> percentJump 显示当前百分比）
  jsonIndex: -1,
  jsonTotal: 0
}

export function getState() {
  return state
}

export function setJumpToPage(page) {
  state.jumpToPage = page
}

export function setNeedBackToReader(val) {
  state.needBackToReader = val
}

export function setRefreshReader(val) {
  state.refreshReader = val
}

export function setReaderPath(path) {
  state.readerPath = path
}

export function setReaderPage(page) {
  state.readerPage = page
}

export function setContentName(name) {
  state.contentName = name
}

export function resetJumpState() {
  state.jumpToPage = null
  state.jumpToPercent = null
  state.needBackToReader = false
}

export default state
