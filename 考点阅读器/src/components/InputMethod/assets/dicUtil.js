import { dict } from './dic.js'

let SimpleInputMethod = {
  dict: {}
}

SimpleInputMethod.initDict = function() {
  this.dict.py2hz = dict
  this.dict.py2hz2 = {}
  this.dict.py2hz2['i'] = 'i'

  for (let key in this.dict.py2hz) {
    let ch = key[0]
    if (!this.dict.py2hz2[ch]) {
      this.dict.py2hz2[ch] = this.dict.py2hz[key]
    }
  }
}

SimpleInputMethod.getSingleHanzi = function(pinyin, lang = 'cn') {
  if (lang === 'cn') {
    return this.dict.py2hz2[pinyin]
        || this.dict.py2hz[pinyin]
        || ''
  }
  return ''
}

SimpleInputMethod.getHanzi = function(pinyin, lang = 'cn') {
  let result = this.getSingleHanzi(pinyin, lang)
  if (result) {
    return [ result.split(''), pinyin ]
  }

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
