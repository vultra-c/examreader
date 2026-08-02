import interconnect from "@system.interconnect";

export default class interconn{
    callbacks = {};
    eventListeners = [];
    connected = false;
    constructor() {
        this.conn = interconnect.instance();

        this.conn.onmessage = ({ data }) => {
            // 日志：记录收到的原始数据
            console.log('[interconn] onmessage: type=' + typeof data + ', value=' + (typeof data === 'string' ? data.substring(0, 200) : JSON.stringify(data).substring(0, 200)));

            let parsed;
            // 处理 string 和 object 两种可能的 data 格式
            if (typeof data === 'string') {
                try {
                    parsed = JSON.parse(data);
                } catch (e) {
                    console.error('[interconn] onmessage JSON.parse failed: ' + (e && e.message));
                    return;
                }
            } else if (typeof data === 'object' && data !== null) {
                // data 已经是对象，无需 parse
                parsed = data;
            } else {
                console.warn('[interconn] onmessage received unexpected data type: ' + typeof data);
                return;
            }

            const { tag, ...playload } = parsed;
            console.log('[interconn] message tag=' + tag + ', payload keys=' + Object.keys(playload).join(','));

            this.connected = true;
            if (this.callbacks[tag]) {
                try {
                    this.callbacks[tag](playload);
                } catch (e) {
                    console.error('[interconn] callback error for tag=' + tag + ': ' + (e && e.message));
                }
            } else {
                // 记录未处理的 tag，帮助调试
                console.warn('[interconn] no handler for tag=' + tag + ', available tags=' + Object.keys(this.callbacks).join(','));
            }
        }
        this.conn.onclose = () => {
            console.log('[interconn] onclose');
            this.connected = false;
            this.eventListeners.forEach((callback) => {
                if (callback) callback("close");
            })
        }
        this.conn.onerror = (e) => {
            console.error('[interconn] onerror: ' + (e && JSON.stringify(e)));
            this.connected = false;
            this.eventListeners.forEach((callback) => {
                if (callback) callback("error");
            })
        }
        this.conn.onopen = () => {
            console.log('[interconn] onopen');
            this.connected = true;
            this.eventListeners.forEach((callback) => {
                if (callback) callback("open");
            })
        }

    }
    /**
     * @param {string} tag
     * @param {Function} callback
     */
    addListener(tag, callback) {
        console.log('[interconn] addListener: tag=' + tag);
        this.callbacks[tag] = callback;
    }
    removeListener(tag) {
        console.log('[interconn] removeListener: tag=' + tag);
        delete this.callbacks[tag];
    }
    addEventListener(callback) {
        // 复用已置为 null 的槽位，避免数组索引错位
        for (let i = 0; i < this.eventListeners.length; i++) {
            if (this.eventListeners[i] === null) {
                this.eventListeners[i] = callback;
                return i;
            }
        }
        return this.eventListeners.push(callback) - 1
    }
    removeEventListener(index) {
        // 置 null 而非 splice，保持其它监听器的索引稳定
        if (index >= 0 && index < this.eventListeners.length) {
            this.eventListeners[index] = null;
        }
    }
    /**
     * @param {string} tag
     * @param {any} playload
     */
    send(tag, playload) {
        const data = typeof playload === 'object' ? { ...playload, tag } : { msg: playload, tag }
        console.log('[interconn] send: tag=' + tag + ', data=' + JSON.stringify(data).substring(0, 200));
        return new Promise((resolve, reject) => {
            this.conn.send({
                data, success: () => {
                    console.log('[interconn] send success: tag=' + tag);
                    resolve();
                }, fail: (e) => {
                    console.error('[interconn] send fail: tag=' + tag + ', error=' + JSON.stringify(e));
                    reject(e);
                }
            });
        })
    }
    get state() {
        return this.conn.getApkStatus()
    }
    register(module) {
        if (typeof module !== 'function') throw new Error('module must be a function');
        if (!module.__interconnModule__) throw new Error('module must be a interconnModule');
        console.log('[interconn] register module: name=' + module.name);
        return new module({
            send: (playload) => this.send(module.name, playload),
            addListener: (callback) => this.addListener(module.name, callback),
            conn: this.conn,
            removeListener: () => this.removeListener(module.name),
            setEventListener: (listener)=>this.addEventListener(listener),
        });
    }
}
export class interconnModule{
    static "__interconnModule__" = true;
}
