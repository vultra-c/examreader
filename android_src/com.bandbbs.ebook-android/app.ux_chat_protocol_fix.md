# 手环端 app.ux Amadeus 协议微调：reply_end 收齐判定

> 手机端 SSE 流式回包无法提前预知总片数，与现有 `received < totalChunks` hard check 冲突。
> 本文件只改这一个判定点，其余协议完全不动。

## 改前（app.ux 当前）

```javascript
case 'reply_end': {
    this._clearChatWatchdog()
    const st = this.chatReplyState
    this.chatReplyState = null
    if (!st || st.totalChunks <= 0 || st.received < st.totalChunks) {
      this._broadcastChat({ kind: 'reply_error', message: '回复不完整 ' + (st ? st.received + '/' + st.totalChunks : 'no-state') })
      return
    }
    // ... 拼 fullText 广播 reply_end
}
```

`totalChunks` 是硬性上限：`received` 必须严格等于 `totalChunks` 才算收齐。

## 改后

把 `reply_start` 的 `totalChunks` 语义从「精确总数」改为「最大片数上限」（手机端在流式场景下可以传一个足够大的保守值，或传实际已发片数——两种都对），收齐判据改为「至少收到一片 + 手机端显式结束」：

```javascript
case 'reply_end': {
    this._clearChatWatchdog()
    const st = this.chatReplyState
    this.chatReplyState = null
    if (!st || st.received <= 0) {
      // 一片都没收到（start 没到/全丢了）才算不完整
      this._broadcastChat({ kind: 'reply_error', message: '回复不完整 ' + (st ? st.received + '片' : 'no-state') })
      return
    }
    // 按 chunkIndex 升序拼完整文本（buffer 里可能不连续，跳过空洞）
    let full = ''
    for (let i = 0; i < (st.totalChunks || st.received); i++) {
      full += (st.buffer[i] != null) ? st.buffer[i] : ''
    }
    this._broadcastChat({ kind: 'reply_end', sessionId: st.sessionId, fullText: full })
    break
}
```

### 语义变化

| 维度 | 改前 | 改后 |
|---|---|---|
| `totalChunks` | 精确总片数 | 最大片数上限（手机端传 `received` 实际计数或任意 ≥received 的值均可） |
| 收齐判定 | `received < totalChunks` → error | `received <= 0` → error（只要收到至少一片且手机端主动 `reply_end`，即视为完整） |
| 拼装方式 | 严格 `0..totalChunks-1` 连续拼接 | 同样遍历，但允许空洞（跳过 undefined index，对流式中途异常中断后 resume 场景友好） |

### 非流式回包兼容性

手机端非流式（一次性 LLM 返回再切片）时，`reply_start` 的 `totalChunks` 仍传精确值（如 3），手环 `received === 3` 走新逻辑同样判 `received > 0` → 收齐，无回归。

### SSE 流式场景

手机端 SSE 边收边攒，每攒够 80 字符发一片 `reply_chunk`，同时递增 `chunkIndex`。`reply_start` 的 `totalChunks` 传一个保守上限（如 999）或不传（手环默认 0 时走 `received` 实际计数）。手环收到 `reply_end` 时不校验 `received vs totalChunks`，只校验「确实收到过内容」，然后按 `chunkIndex` 升序把所有 `buffer[i]` 拼起来——与 file 分片组装的思路一致。

### 与 file 分片的一致性

file 的 `_finishFileTransfer` 已经是「片齐才并入落盘」，但 file 是手机端控制 `totalChunks` 精确值。chat 是手环端控制——把 chat 的收齐逻辑从「必须精确等值」降级为「收到即完整」是合理的：手机端不会在发完 `reply_end` 后再补发历史 chunk，`reply_end` 本身就是结束信号。

## 位置

文件：`src/app.ux`
函数：`_processChatMessage(msg)` 的 `case 'reply_end':` 分支
行数：约第 330-350 行（以当前手环端代码为准）
