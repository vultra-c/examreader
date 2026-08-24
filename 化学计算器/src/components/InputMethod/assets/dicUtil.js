import { dict } from './dic.js'

let SimpleInputMethod = {
  dict: {}
}

SimpleInputMethod.initDict = function() {
  this.dict.py2hz = dict
  this.dict.py2hz2 = {}
  this.dict.py2hz2['i'] = 'i'  // 特殊处理

  for (let key in this.dict.py2hz) {
    let ch = key[0]
    if (!this.dict.py2hz2[ch]) {
      this.dict.py2hz2[ch] = this.dict.py2hz[key]
    }
  }
}

SimpleInputMethod.getSingleHanzi = function(pinyin, lang = 'cn') {
  // 根据 lang 决定走哪张表
  if (lang === 'cn') {
    return this.dict.py2hz2[pinyin]
        || this.dict.py2hz[pinyin]
        || ''
  }
  // en 模式不查候选
  return ''
}

SimpleInputMethod.getHanzi = function(pinyin, lang = 'cn') {
  let result = this.getSingleHanzi(pinyin, lang)
  if (result) {
    return [ result.split(''), pinyin ]
  }

  // 多字截断时，同样指定 lang
  let max = Math.min(pinyin.length, 6)
  for (let len = max; len >= 1; len--) {
    let head = pinyin.substr(0, len)
    let rs = this.getSingleHanzi(head, lang)
    if (rs) {
      return [ rs.split(''), head ]
    }
  }

  return [ [], '' ]
}

SimpleInputMethod.initDict()

export { SimpleInputMethod }
