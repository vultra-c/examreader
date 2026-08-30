/**
 * 考点阅读器 - 文件树同步模块
 *
 * 作为 interconnModule 注册到连接管理器，tag="tree"
 * 负责文件树的同步、文件夹创建/删除等操作
 *
 * 消息协议（tag="tree"）：
 * 接收方向（action 字段路由）：
 *   - getTree: 请求文件树 { }
 *   - createFolder: 创建文件夹 { name, parentId }
 *   - deleteNode: 删除节点 { nodeId }
 *   - renameNode: 重命名节点 { nodeId, newName }
 *   - moveNode: 调整同级显示顺序 { nodeId, direction: 'up'|'down' }
 *
 * 发送方向（response 字段）：
 *   - treeData: 文件树数据 { tree: [...] }
 *   - folderCreated: 文件夹创建结果 { folderId, success, error }
 *   - nodeDeleted: 节点删除结果 { success, error }
 *   - nodeRenamed: 节点重命名结果 { success, error }
 *   - nodeMoved: 顺序调整结果 { success, error }
 *
 * 注：createFolder / deleteNode / renameNode / moveNode 后不再自动全量推树，
 *     手机端收到成功响应后自行调用 requestTree 刷新。
 */
import { interconnModule } from './interconn.js';
import dataManager from './dataManager.js';

export default class interconnTree {
    static "__interconnModule__" = true;
    static name = 'tree';

    constructor({ addListener, send, setEventListener }) {
        this.send = send;

        const onmessage = async (data) => {
            const { action, ...payload } = data;
            try {
                switch (action) {
                    case 'getTree':
                        await this.handleGetTree();
                        break;
                    case 'createFolder':
                        await this.handleCreateFolder(payload);
                        break;
                    case 'deleteNode':
                        await this.handleDeleteNode(payload);
                        break;
                    case 'renameNode':
                        await this.handleRenameNode(payload);
                        break;
                    case 'moveNode':
                        await this.handleMoveNode(payload);
                        break;
                    default:
                        console.warn('[BT-Tree] Unknown action: ' + action);
                }
            } catch (e) {
                console.error('[BT-Tree] Error: ' + ((e && e.message) ? e.message : String(e || '未知错误')));
            }
        };

        addListener(onmessage);

        // When connection opens or reconnects, auto-send tree
        setEventListener((event) => {
            if (event === 'open') {
                // Auto-push tree on connection
                setTimeout(() => this.handleGetTree(), 500);
            }
        });
    }

    /**
     * 处理获取文件树请求
     */
    async handleGetTree() {
        console.log('[BT-Tree] getTree request');
        const tree = await dataManager.getFolderTreeForBluetooth();
        await this.send({
            response: 'treeData',
            tree: tree
        });
        console.log('[BT-Tree] Tree sent (' + tree.length + ' top-level nodes)');
    }

    /**
     * 处理创建文件夹请求
     */
    async handleCreateFolder({ name, parentId }) {
        console.log('[BT-Tree] createFolder: ' + name + ' parentId=' + (parentId || 'bt_root'));
        try {
            const folderId = await dataManager.createBluetoothFolder(name, parentId);
            await this.send({
                response: 'folderCreated',
                folderId: folderId,
                success: true
            });
            // 不再自动全量推树，手机端收到成功响应后自行 requestTree
        } catch (e) {
            await this.send({
                response: 'folderCreated',
                folderId: null,
                success: false,
                error: (e && e.message) ? e.message : String(e || '未知错误')
            });
        }
    }

    /**
     * 处理删除节点请求
     */
    async handleDeleteNode({ nodeId }) {
        console.log('[BT-Tree] deleteNode: ' + nodeId);
        try {
            await dataManager.deleteBluetoothNode(nodeId);
            await this.send({
                response: 'nodeDeleted',
                success: true
            });
            // 不再自动全量推树，手机端收到成功响应后自行 requestTree
        } catch (e) {
            await this.send({
                response: 'nodeDeleted',
                success: false,
                error: (e && e.message) ? e.message : String(e || '未知错误')
            });
        }
    }

    /**
     * 处理重命名节点请求
     * @param {string} nodeId 节点 ID
     * @param {string} newName 新名称
     */
    /**
     * 处理顺序调整请求
     * @param {string} nodeId 节点 ID
     * @param {string} direction 'up' 上移 | 'down' 下移
     */
    async handleMoveNode({ nodeId, direction }) {
        console.log('[BT-Tree] moveNode: ' + nodeId + ' -> ' + direction);
        try {
            const success = await dataManager.moveBluetoothNode(nodeId, direction === 'down' ? 'down' : 'up');
            await this.send({
                response: 'nodeMoved',
                success: !!success,
                error: success ? undefined : '已到顶/底或节点不存在'
            });
            // 不再自动全量推树，手机端收到成功响应后自行 requestTree
        } catch (e) {
            await this.send({
                response: 'nodeMoved',
                success: false,
                error: (e && e.message) ? e.message : String(e || '未知错误')
            });
        }
    }
}
