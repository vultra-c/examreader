/**
 * 考点阅读器 - 文件传输模块
 *
 * 完全复刻弦电子书 interconnfile.js 的消息协议
 * 作为 interconnModule 注册到连接管理器
 *
 * 消息协议（tag="file"）：
 * 接收方向（stat 字段路由）：
 *   - startTransfer: 开始传输 { filename, total, wordCount, startFrom }
 *   - d: 数据分片 { count, data } 其中 data 是 JSON 字符串 { index, name, content, wordCount, chunkNum, totalChunks }
 *   - chapter_complete: 章节完成 { count }
 *   - transfer_complete: 传输完成
 *   - cancel: 取消传输
 *
 * 发送方向（type 字段）：
 *   - ready: 手环就绪 { count, usage }
 *   - chapter_chunk_complete: 章节分片完成
 *   - next_chunk: 请求下一片
 *   - chapter_saved: 章节已保存 { count, syncedCount, totalCount, progress }
 *   - transfer_finished: 传输完成
 *   - error: 错误 { message, count }
 *   - cancel: 取消
 */
import { interconnModule } from './interconn.js';
import dataManager from './dataManager.js';
import { parseKnowledgeJson } from './jsonParser.js';

export default class interconnfile {
    static "__interconnModule__" = true;
    static name = 'file';

    currentBookName = "";
    totalChapters = 0;
    receivedChapters = 0;
    currentChapterMeta = null;
    currentChapterContent = "";
    targetFolder = 'bt_root';

    chapterWriteState = new Map();
    // UI 进度回调节流：上次触发时间戳，最多每 200ms 触发一次
    _lastProgressTime = 0;

    constructor({ addListener, send, setEventListener }) {
        this.send = send;

        const onmessage = async (data) => {
            const { stat, ...payload } = data;
            try {
                switch (stat) {
                    case "startTransfer":
                        await this.startTransfer(payload);
                        break;
                    case "d":
                        await this.saveChapter(payload);
                        break;
                    case "chapter_complete":
                        await this.completeChapterTransfer(payload);
                        break;
                    case "transfer_complete":
                        await this.handleTransferComplete();
                        break;
                    case "cancel":
                        await this.handleCancel();
                        break;
                    default:
                        console.warn('[BT-File] Unknown stat: ' + stat);
                }
            } catch (e) {
                this.handleError(e, "Message processing error");
            }
        };

        addListener(onmessage);

        setEventListener((event) => {
            if (event !== 'open') {
                this.resetState();
                this.callback({ msg: "error", error: event, filename: this.currentBookName });
            }
        });
    }

    resetState() {
        this.currentBookName = "";
        this.currentChapterContent = "";
        this.currentChapterMeta = null;
        this.receivedChapters = 0;
        this.totalChapters = 0;
        this.targetFolder = 'bt_root';
        this.chapterWriteState.clear();
        this._lastProgressTime = 0;
    }

    /**
     * 节流的 UI 进度回调
     * 最多每 200ms 触发一次；force=true、progress>=1（100%）时强制触发
     * 确保最后一个分片（isLastChunk）与 100% 进度一定会回调到 UI
     * @param {number} progress 0~1 的整体进度
     * @param {boolean} force 是否强制触发（如最后一个分片）
     */
    _emitProgress(progress, force) {
        const now = Date.now();
        if (force || progress >= 1 || (now - this._lastProgressTime) >= 200) {
            this._lastProgressTime = now;
            this.callback({ msg: "next", progress: progress, filename: this.currentBookName });
        }
    }

    async startTransfer({ filename, total, wordCount, startFrom = 0, folder }) {
        console.log('[BT-File] startTransfer: ' + filename + ', total=' + total + ', startFrom=' + startFrom + ', folder=' + (folder || 'bt_root'));

        this.currentBookName = filename;
        this.totalChapters = total;
        this.receivedChapters = startFrom;
        this.currentChapterContent = "";
        this.currentChapterMeta = null;
        this.chapterWriteState.clear();
        // 确保 folder 参数不为空
        this.targetFolder = (folder && folder !== '') ? folder : 'bt_root';
        console.log('[BT-File] targetFolder set to: ' + this.targetFolder);

        // 通知 UI 开始传输
        this.callback({ msg: "start", total, filename });

        // 回复 ready，表示手环已准备好接收
        await this.send({ type: "ready", count: startFrom, usage: 0 });
    }

    async saveChapter(payload) {
        const { count, data } = payload;

        const chapterData = JSON.parse(data);
        const { index, name, content, wordCount, chunkNum, totalChunks } = chapterData;

        console.log('[BT-File] Chunk ' + (chunkNum + 1) + '/' + totalChunks + ' for chapter ' + index);

        const state = this.chapterWriteState.get(index) || {
            started: false,
            completed: false,
            lastChunkNum: -1,
            totalChunks: 0,
            contentParts: [],
            content: ""
        };

        // 已完成的章节，直接回复
        if (state.completed && chunkNum !== 0) {
            const overallProgress = (count + ((chunkNum + 1) / totalChunks)) / this.totalChapters;
            this._emitProgress(overallProgress, false);

            if (chunkNum === totalChunks - 1) {
                await this.send({ type: "chapter_chunk_complete" });
            } else {
                await this.send({ type: "next_chunk" });
            }
            return;
        }

        const isFirstChunk = chunkNum === 0;
        const isLastChunk = chunkNum === totalChunks - 1;

        if (isFirstChunk) {
            state.started = true;
            state.completed = false;
            state.lastChunkNum = -1;
            state.totalChunks = totalChunks;
            state.contentParts = [];
            state.content = "";
        }

        // 用数组收集分片，避免反复字符串拼接带来的 O(n^2) 开销
        state.contentParts.push(content || "");
        state.lastChunkNum = chunkNum;
        this.chapterWriteState.set(index, state);

        // 通知 UI 进度（节流，最后一个分片强制触发）
        const overallProgress = (count + ((chunkNum + 1) / totalChunks)) / this.totalChapters;
        this._emitProgress(overallProgress, isLastChunk);

        if (isLastChunk) {
            state.completed = true;
            // 最后一次性 join 出完整正文
            state.content = state.contentParts.join("");
            // 立即释放 contentParts 数组，减少内存占用
            state.contentParts = null;
            this.chapterWriteState.set(index, state);

            // 保存章节元信息，等待 chapter_complete 时一起写入
            this.currentChapterMeta = {
                index: index,
                name: name,
                wordCount: wordCount,
                content: state.content
            };

            await this.send({ type: "chapter_chunk_complete" });
        } else {
            await this.send({ type: "next_chunk" });
        }
    }

    async completeChapterTransfer({ count }) {
        console.log('[BT-File] Chapter complete: ' + count);

        if (this.currentChapterMeta) {
            // 保存章节内容到手环存储
            try {
                // 多章节时使用 "文件名 - 第N章" 作为显示名
                let displayName = this.totalChapters > 1
                    ? this.currentBookName + ' - 第' + (this.currentChapterMeta.index + 1) + '章'
                    : this.currentBookName;

                // JSON 知识点文件检测：文件名以 .json 结尾时按闪念小抄（Snapnotes）结构解析保存
                let fmt = '';
                if (displayName && displayName.toLowerCase().indexOf('.json') === displayName.length - 5) {
                    const check = parseKnowledgeJson(this.currentChapterMeta.content);
                    if (!check.ok) {
                        const errText = check.error === 'parse-fail'
                            ? 'JSON 解析失败，请检查文件格式'
                            : 'JSON 结构无效（需 {科目:[{title,points,...}]} 结构）';
                        console.error('[BT-File] JSON validation failed: ' + check.error);
                        this.send({ type: "error", message: errText, count: 0 });
                        return;
                    }
                    fmt = 'json';
                }
                console.log('[BT-File] Saving to folder: ' + this.targetFolder + ', name: ' + displayName + ', fmt: ' + (fmt || 'txt'));
                const savedId = await dataManager.saveBluetoothContent(
                    displayName,
                    this.currentChapterMeta.content,
                    this.targetFolder,
                    fmt
                );
                if (!savedId) {
                    throw new Error('存储写入失败（可能存储空间不足）');
                }
                this.receivedChapters++;
            } catch (e) {
                const errMsg = (e && e.message) ? e.message : String(e || '未知错误');
                console.error('[BT-File] Save chapter failed: ' + errMsg);
                this.send({ type: "error", message: '保存失败: ' + errMsg, count: 0 });
                return;
            }
            // 立即释放正文内存，防止 OOM
            this.currentChapterMeta = null;
        }

        // 清理 chapterWriteState，释放内存
        this.chapterWriteState.clear();

        await this.send({
            type: "chapter_saved",
            count: this.receivedChapters,
            syncedCount: this.receivedChapters,
            totalCount: this.totalChapters,
            progress: (this.receivedChapters / this.totalChapters) * 100
        });
    }

    async handleTransferComplete() {
        console.log('[BT-File] Transfer complete');
        this.resetState();
        await this.send({ type: "transfer_finished" });
        this.callback({ msg: "success" });
    }

    async handleCancel() {
        console.log('[BT-File] Transfer cancelled');
        this.resetState();
        await this.send({ type: "cancel" });
        this.callback({ msg: "cancel" });
    }

    handleError(error, context) {
        const errorMsg = (error && error.message) ? error.message : String(error || '未知错误');
        const displayMsg = context + ': ' + errorMsg;
        console.error('[BT-File] ' + displayMsg);
        this.send({ type: "error", message: displayMsg, count: 0 });
        this.callback({ msg: "error", error: displayMsg });
    }

    setCallback(callback) {
        this.callback = callback;
    }

    callback() {}
}
