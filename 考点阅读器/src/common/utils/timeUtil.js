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
 * 按时间格式格式化时间
 * 支持两种调用方式（向后兼容）：
 *   getFormattedTime(timeFormat)              —— 格式化当前时间
 *   getFormattedTime(timeString, timeFormat)  —— 格式化指定 "HH:MM" 时间
 * timeFormat: '24h' 或 '12h'
 */
export function getFormattedTime(time, timeFormat) {
  let hours, minutes
  if (timeFormat === undefined) {
    // 兼容旧调用：getFormattedTime(timeFormat)
    timeFormat = time
    const date = new Date()
    hours = date.getHours()
    minutes = date.getMinutes()
  } else {
    // 新调用：getFormattedTime(timeString, timeFormat)，timeString 形如 "14:30"
    const parts = String(time).split(':')
    hours = parseInt(parts[0]) || 0
    minutes = parseInt(parts[1]) || 0
  }

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

/**
 * 读取时间格式并返回格式化后的当前时间
 * @param {string} timeFormat '24h' 或 '12h'
 */
export function getFormattedTimeNow(timeFormat) {
  return getFormattedTime(getNowTime(), timeFormat)
}
