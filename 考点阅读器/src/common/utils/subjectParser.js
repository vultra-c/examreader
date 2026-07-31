/**
 * Exam Point Reader - Subject-specific TXT format parser
 *
 * Parses structured TXT files that use "@" tags to describe different
 * subjects. Supported formats:
 *
 *   poem    Chinese poetry / classical text (original, translation, knowledge)
 *   history historical events (date, event)
 *   plain   unstructured text without any @type tag (returned as-is)
 *
 * Tag conventions used inside the TXT content:
 *
 *   @type:<value>      inline tag  (e.g. @type:poem)
 *   @title:<value>     inline tag
 *   @author:<value>    inline tag
 *   @dynasty:<value>   inline tag
 *   @date:<value>      inline tag
 *   @original          block tag  (lines until @end)
 *   @translation       block tag  (lines until @end)
 *   @knowledge         block tag  (lines until @end)
 *   @event             block tag  (lines until @end, optional inline value)
 *   @end               closes the currently open block
 *
 * A block tag may optionally carry its content inline on the same line,
 * e.g. "@event:some text" followed by @end. This keeps both the multi-line
 * style (used by poems) and the single-line style (used by history) working.
 */

// Tags whose value is provided inline on the same line as the tag.
const INLINE_TAGS = ['title', 'author', 'dynasty', 'date']

// Tags whose value spans one or more lines until a closing @end marker.
const BLOCK_TAGS = ['original', 'translation', 'knowledge', 'event']

// Matches a single tag line, e.g. "@title:关雎" (group 2 = "关雎") or
// "@original" (group 2 = undefined).
const TAG_RE = /^@([a-zA-Z]+)(?::(.*))?$/

/**
 * Detect whether a piece of text uses the subject-specific (tagged) format.
 * The format is identified by the presence of an "@type:" tag.
 *
 * @param {string} text raw file content
 * @returns {boolean} true when an @type tag is present
 */
function isSubjectSpecific(text) {
  if (!text) return false
  return /@type\s*:/.test(text)
}

/**
 * Build an empty result object containing every possible field.
 * This guarantees a stable shape for consumers of parseContent.
 *
 * @returns {object} empty parsed result
 */
function emptyResult() {
  return {
    type: 'plain',
    title: '',
    author: '',
    dynasty: '',
    original: '',
    translation: '',
    knowledge: '',
    date: '',
    event: '',
    content: ''
  }
}

/**
 * Parse a subject-specific TXT file into a structured object.
 *
 * The returned `content` field always holds the full (normalized) source
 * text so that callers can perform free-text search across every field.
 *
 * @param {string} text raw file content
 * @returns {object} parsed result with all fields populated; for plain text
 *   only `type` ("plain") and `content` are meaningful.
 */
function parseContent(text) {
  const result = emptyResult()

  if (text == null) return result

  // Normalize CRLF / CR line endings to LF so splitting on "\n" is reliable.
  const normalized = String(text).replace(/\r\n/g, '\n').replace(/\r/g, '\n')
  // Keep the full source text for searching purposes.
  result.content = normalized

  // Plain text: no @type tag, return the content untouched.
  if (!isSubjectSpecific(normalized)) {
    result.type = 'plain'
    return result
  }

  const lines = normalized.split('\n')
  let i = 0
  while (i < lines.length) {
    const line = lines[i].trim()

    // Only lines starting with "@" are interpreted as tags.
    if (line.charAt(0) !== '@') {
      i++
      continue
    }

    const match = TAG_RE.exec(line)
    if (!match) {
      i++
      continue
    }

    const tagName = match[1].toLowerCase()
    // inlineValue is undefined when the tag has no colon (block start).
    const inlineValue = match[2]

    // A stray @end without an open block is simply ignored.
    if (tagName === 'end') {
      i++
      continue
    }

    // The @type tag is always inline and normalised to lower case.
    if (tagName === 'type') {
      const typeValue = inlineValue != null ? inlineValue.trim().toLowerCase() : ''
      result.type = typeValue || 'plain'
      i++
      continue
    }

    // Inline tags: the value lives on the same line, after the colon.
    if (INLINE_TAGS.indexOf(tagName) !== -1) {
      result[tagName] = inlineValue != null ? inlineValue.trim() : ''
      i++
      continue
    }

    // Block tags: collect every following line until an @end marker.
    if (BLOCK_TAGS.indexOf(tagName) !== -1) {
      const blockLines = []
      // Some block tags carry their first (and sometimes only) line inline,
      // e.g. "@event:周平王东迁洛邑，东周开始".
      if (inlineValue != null && inlineValue !== '') {
        blockLines.push(inlineValue)
      }
      i++
      while (i < lines.length) {
        if (lines[i].trim() === '@end') {
          i++
          break
        }
        blockLines.push(lines[i])
        i++
      }
      result[tagName] = blockLines.join('\n').trim()
      continue
    }

    // Unknown tag: skip the line but keep scanning.
    i++
  }

  return result
}

/**
 * Format a parsed poem into a readable display string.
 *
 * @param {object} parsed result produced by parseContent
 * @returns {string} formatted text for the reader
 */
function formatPoem(parsed) {
  const lines = []

  // Header line: 《title》 [dynasty] author
  const header = []
  if (parsed.title) header.push('《' + parsed.title + '》')
  if (parsed.dynasty) header.push('[' + parsed.dynasty + ']')
  if (parsed.author) header.push(parsed.author)
  if (header.length > 0) lines.push(header.join(' '))

  // Each present section is separated by a blank line for readability.
  if (parsed.original) {
    if (lines.length > 0) lines.push('')
    lines.push('【原文】')
    lines.push(parsed.original)
  }
  if (parsed.translation) {
    if (lines.length > 0) lines.push('')
    lines.push('【译文】')
    lines.push(parsed.translation)
  }
  if (parsed.knowledge) {
    if (lines.length > 0) lines.push('')
    lines.push('【考点】')
    lines.push(parsed.knowledge)
  }

  return lines.join('\n').trim()
}

/**
 * Format a parsed history entry into a readable display string.
 *
 * @param {object} parsed result produced by parseContent
 * @returns {string} formatted text for the reader
 */
function formatHistory(parsed) {
  const lines = []
  if (parsed.date) lines.push('【' + parsed.date + '】')
  if (parsed.event) lines.push(parsed.event)
  return lines.join('\n').trim()
}

/**
 * Build a human-readable display string from a parsed result.
 *
 * The output is a plain string suitable for feeding into the reader's
 * pagination logic (see dataManager.splitContentIntoPages).
 *
 * @param {object} parsed output of parseContent
 * @returns {string} formatted text; empty string when given no input
 */
function formatForDisplay(parsed) {
  if (!parsed) return ''
  if (parsed.type === 'poem') return formatPoem(parsed)
  if (parsed.type === 'history') return formatHistory(parsed)
  // plain text or unknown type: return the raw content untouched.
  return parsed.content || ''
}

export { parseContent, isSubjectSpecific, formatForDisplay }

export default {
  parseContent,
  isSubjectSpecific,
  formatForDisplay
}
