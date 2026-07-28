/**
 * 考点阅读器 - 公共工具函数
 */

/**
 * 获取当前时间字符串（24小时制）
 */
export function getNowTime() {
  const date = new Date()
  let hours = date.getHours()
  let minutes = date.getMinutes()
  hours = hours < 10 ? '0' + hours : hours
  minutes = minutes < 10 ? '0' + minutes : minutes
  return hours + ':' + minutes
}

/**
 * 获取当前时间字符串（支持12/24小时制）
 */
export function getFormattedTime(timeFormat) {
  const date = new Date()
  let hours = date.getHours()
  let minutes = date.getMinutes()
  if (timeFormat === '12h') {
    let ampm = hours >= 12 ? '下午' : '上午'
    hours = hours % 12
    hours = hours ? hours : 12
    minutes = minutes < 10 ? '0' + minutes : minutes
    return ampm + ' ' + hours + ':' + minutes
  } else {
    hours = hours < 10 ? '0' + hours : hours
    minutes = minutes < 10 ? '0' + minutes : minutes
    return hours + ':' + minutes
  }
}
