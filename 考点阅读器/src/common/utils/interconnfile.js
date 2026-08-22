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
import device from '@system.device';
import file from '@system.file';
import { base64ToArrayBuffer, sanitizeFileName } from '../base64.js';
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

    // Snapnotes 协议状态（闪念小抄 JsonFilePusher 对齐，含断点续传）
    snState = null;

    constructor({ addListener, send, setEventListener }) {
        this.send = send;

        const onmessage = async (data) => {
            const { stat, ...payload } = data;
            try {
                switch (stat) {
                    case "startTransfer":
                        // 新版（Snapnotes）首包带 totalChunks；旧版考点传输带 total/wordCount
                        if (typeof payload.totalChunks === 'number' && payload.totalChunks > 0) {
                            this.snStartTransfer(payload);
                        } else {
                            await this.startTransfer(payload);
                        }
                        break;
                    case "d":
                        // 新版分片是扁平的 chunkIndex/data；旧版的 data 是内嵌 JSON 字符串
                        if (typeof payload.chunkIndex === 'number') {
                            this.snChunk(payload);
                        } else {
                            await this.saveChapter(payload);
                        }
                        break;
                    case "transferComplete":
                        // 新版完成包（camelCase）；旧版是下划线 transfer_complete
                        this.snFinish();
                        break;
                    case "startFormula":
                        this.snStartFormula(payload);
                        break;
                    case "get_storage_info":
                        this.snStorageInfo();
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
        this.snState = null;
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

    // ==================== Snapnotes 协议（闪念小抄 JsonFilePusher 对齐） ====================
    // 收(stat): startTransfer/startFormula/d/transferComplete/cancel/get_storage_info
    // 发(type): ready{nextChunkIndex}/next_chunk/transfer_finished/error/storage_info
    // 断点续传：同名同分片数的传输中断后重发 startTransfer，回 ready.nextChunkIndex 指向
    // 第一个缺失分片，手机端从该处继续，无需重传已收内容。

    _snNewState(mode) {
        return {
            mode: mode || 'json',   // 'json' | 'formula'
            fileName: '',
            totalChunks: 0,
            totalBytes: 0,
            folderId: 'bt_root',
            buffer: {},             // chunkIndex → 内容（对象当 Map 用）
            receivedChunks: 0,
            isReceiving: false,
            formulaSubject: '',
            formulaId: 0,
            formulaW: 0,
            formulaH: 0
        };
    }

    snStartTransfer(payload) {
        const { filename, totalChunks, totalBytes, folderId } = payload;
        console.log('[SN-File] startTransfer: ' + filename + ', chunks=' + totalChunks + ', bytes=' + totalBytes);

        // 断点续传判定：同名同分片数且仍在接收中 → 从第一个缺失分片继续
        const st = this.snState;
        if (st && st.isReceiving && st.fileName === filename && st.totalChunks === totalChunks) {
            let next = 0;
            while (next < st.totalChunks && st.buffer[next] !== undefined) next++;
            if (next < st.totalChunks) {
                console.log('[SN-File] resume from chunk ' + next + '/' + st.totalChunks);
                this.send({ type: "ready", nextChunkIndex: next });
                this.callback({ msg: "start", total: st.totalChunks, filename: filename });
                this.callback({ msg: "next", progress: next / st.totalChunks, filename: filename });
                return;
            }
        }

        // 全新传输
        this.snState = this._snNewState('json');
        this.snState.fileName = (typeof filename === 'string' && filename) ? filename : 'knowledge.json';
        this.snState.totalChunks = totalChunks;
        this.snState.totalBytes = (typeof totalBytes === 'number') ? totalBytes : 0;
        // 手机端 folderId 仅在能对上本机 bt_folder_ 时使用，否则落到主页
        this.snState.folderId = (typeof folderId === 'string' && folderId.indexOf('bt_folder_') === 0)
            ? folderId : 'bt_root';
        this.snState.isReceiving = true;

        this.callback({ msg: "start", total: totalChunks, filename: this.snState.fileName });
        this.send({ type: "ready", nextChunkIndex: 0 });
    }

    snStartFormula(payload) {
        const { subject, id, filename, w, h, totalChunks, totalBytes } = payload;
        console.log('[SN-Formula] start: ' + subject + '#' + id + ' -> ' + filename);
        this.snState = this._snNewState('formula');
        this.snState.formulaSubject = (typeof subject === 'string') ? subject : '';
        this.snState.formulaId = (typeof id === 'number') ? id : 0;
        this.snState.formulaW = (typeof w === 'number') ? w : 0;
        this.snState.formulaH = (typeof h === 'number') ? h : 0;
        this.snState.fileName = sanitizeFileName((typeof filename === 'string' && filename) ? filename : 'formula.png');
        this.snState.totalChunks = (typeof totalChunks === 'number' && totalChunks > 0) ? totalChunks : 0;
        this.snState.totalBytes = (typeof totalBytes === 'number') ? totalBytes : 0;
        this.snState.isReceiving = true;

        this.callback({ msg: "start", total: totalChunks, filename: this.snState.fileName });
        this.send({ type: "ready", nextChunkIndex: 0 });
    }

    snChunk(payload) {
        const st = this.snState;
        if (!st || !st.isReceiving) {
            this.send({ type: "error", message: 'not receiving' });
            return;
        }
        const idx = payload.chunkIndex;
        const data = payload.data;
        if (typeof idx !== 'number' || typeof data !== 'string') {
            this.send({ type: "error", message: 'bad chunk fields' });
            return;
        }
        if (st.buffer[idx] === undefined) {
            st.buffer[idx] = data;
            st.receivedChunks++;
        } else {
            // 重发片：覆盖即可，不重复计数
            st.buffer[idx] = data;
        }
        // UI 进度（节流）
        this._emitProgress(st.receivedChunks / st.totalChunks, false);
        // 每片都回 next_chunk 流控（含重发片，手机端在等它）
        this.send({ type: "next_chunk" });
    }

    snFinish() {
        const st = this.snState;
        if (!st || !st.isReceiving) {
            this.send({ type: "error", message: 'finish without state' });
            return;
        }
        if (st.totalChunks <= 0 || st.receivedChunks < st.totalChunks) {
            this.send({ type: "error", message: 'chunks incomplete ' + st.receivedChunks + '/' + st.totalChunks });
            return;
        }
        let full = '';
        for (let i = 0; i < st.totalChunks; i++) {
            full += st.buffer[i] != null ? st.buffer[i] : '';
        }
        this.snState = null;

        if (st.mode === 'formula') {
            this._finishSnFormula(st, full);
        } else {
            this._finishSnJson(st, full);
        }
    }

    async _finishSnJson(st, fullJson) {
        let ok = false;
        let reason = '';
        try {
            // 按文件名后缀分流：.json 按闪念小抄结构预校验；其余按普通文本（TXT）直接落盘
            const isJson = /\.json$/i.test(st.fileName || '');
            let fmt = '';
            let displayName = st.fileName;
            if (isJson) {
                const check = parseKnowledgeJson(fullJson);
                if (!check.ok) {
                    reason = check.error === 'parse-fail'
                        ? 'JSON 解析失败，请检查文件格式'
                        : 'JSON 结构无效（需 {科目:[{title,points,...}]} 结构）';
                    this.send({ type: "error", message: reason });
                    this.callback({ msg: "error", error: reason });
                    return;
                }
                fmt = 'json';
                displayName = st.fileName.replace(/\.json$/i, '');
            }
            const savedId = await dataManager.saveBluetoothContent(
                displayName,
                fullJson,
                st.folderId,
                fmt
            );
            ok = !!savedId;
            if (!ok) reason = '存储写入失败（可能存储空间不足）';
        } catch (e) {
            reason = '保存失败: ' + ((e && e.message) || e);
        }
        if (ok) {
            console.log('[SN-File] saved: ' + st.fileName + ' (' + fullJson.length + 'B)');
            this.send({ type: "transfer_finished" });
            this.callback({ msg: "success" });
        } else {
            this.send({ type: "error", message: reason || 'save fail' });
            this.callback({ msg: "error", error: reason });
        }
    }

    _finishSnFormula(st, fullB64) {
        if (!st.formulaSubject || !st.formulaId) {
            this.send({ type: "error", message: 'formula meta missing' });
            return;
        }
        const bytes = base64ToArrayBuffer(fullB64);
        if (!bytes || bytes.byteLength <= 0) {
            this.send({ type: "error", message: 'formula empty bytes' });
            return;
        }
        const fileUri = 'internal://files/formulas/' + st.fileName;
        file.writeArrayBuffer({
            uri: fileUri,
            buffer: new Uint8Array(bytes),
            append: false,
            success: () => {
                console.log('[SN-Formula] saved: ' + fileUri + ' ' + bytes.byteLength + 'B');
                this.send({ type: "transfer_finished" });
                this.callback({ msg: "success" });
            },
            fail: (er) => {
                const msg = 'formula write fail code=' + (er && er.code);
                this.send({ type: "error", message: msg });
                this.callback({ msg: "error", error: msg });
            }
        });
    }

    /**
     * 存储查询应答：串行取 device 信息/总空间/可用空间，2.5s 硬超时兜底回 0。
     * 照搬 Snapnotes-band app.ux _reportStorageInfoToDevice（源自弦电子书）。
     */
    snStorageInfo() {
        let done = false;
        const finish = (info) => {
            if (done) return;
            done = true;
            this.send(Object.assign({ type: "storage_info" }, info));
        };
        const timer = setTimeout(() => {
            finish({ product: null, totalStorage: 0, availableStorage: 0, reservedStorage: 0, usedStorage: 0, actualAvailable: 0 });
        }, 2500);

        const promiseVela = (fn, params) => new Promise((resolve, reject) => {
            try {
                if (typeof fn !== 'function') { reject(new Error('no api')); return; }
                fn(Object.assign({}, params || {}, { success: resolve, fail: (d, code) => reject(new Error('code=' + code)) }));
            } catch (e) { reject(e); }
        });

        ;(async () => {
            let product = null;
            let totalStorage = 0;
            let availableStorage = 0;
            try { const d = await promiseVela(device.getInfo); product = (d && (d.product || d.model)) || null; } catch (e) {}
            try { const d = await promiseVela(device.getTotalStorage); totalStorage = Number(d && d.totalStorage) || 0; } catch (e) {}
            try { const d = await promiseVela(device.getAvailableStorage); availableStorage = Number(d && d.availableStorage) || 0; } catch (e) {}
            clearTimeout(timer);
            const reserved = this._reservedStorageByProduct(product);
            const usedStorage = Math.max(0, totalStorage - availableStorage);
            const actualAvailable = Math.max(0, totalStorage - reserved - usedStorage);
            finish({ product, totalStorage, availableStorage, reservedStorage: reserved, usedStorage, actualAvailable });
        })();
    }

    _reservedStorageByProduct(product) {
        if (!product) return 0;
        if (product === 'REDMI Watch 6') return 120 * 1024 * 1024;
        if (product === 'REDMI Watch 5') return 120 * 1024 * 1024;
        if (product === 'Xiaomi Smart Band 9') return 64 * 1024 * 1024;
        if (product === 'Xiaomi Smart Band 9 Pro') return 64 * 1024 * 1024;
        if (product === 'Xiaomi Smart Band 8 Pro') return 84 * 1024 * 1024;
        if (product === 'o65m') return 1024 * 1024 * 1024;
        if (product.indexOf('Xiaomi Smart Band 10') >= 0) return 90 * 1024 * 1024;
        return 0;
    }

    setCallback(callback) {
        this.callback = callback;
    }

    callback() {}
}
