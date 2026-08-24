/** 最大公约数（非负整数） */
export function gcd(a, b) {
  a = Math.abs(Math.floor(a))
  b = Math.abs(Math.floor(b))
  while (b) {
    const t = b
    b = a % b
    a = t
  }
  return a
}
