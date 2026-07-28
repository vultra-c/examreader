/**
 * 考点阅读器 - 跨页面共享状态
 * 替代 globalThis，在 Vela JS 框架中通过模块缓存实现页面间数据传递
 */

const state = {
  // 跳转目标页码（pageJump / percentJump -> reader）
  jumpToPage: null,

  // 是否需要自动返回到 reader（pageJump / percentJump / deleteConfirm -> readerSettings -> reader）
  needBackToReader: false,

  // 是否需要刷新 reader（deleteConfirm 删除后 -> reader 自动返回上级）
  refreshReader: false,

  // 当前阅读路径（reader -> readerSettings / pageJump / percentJump）
  readerPath: '',

  // 当前阅读页码（reader -> pageJump / percentJump）
  readerPage: 0,

  // 当前内容名称（reader -> readerSettings）
  contentName: ''
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
  state.needBackToReader = false
}

export default state
