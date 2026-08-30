# User Instruction Memory

This file records user instructions, preferences, and teachings for reference in future interactions.

## Format

### User Instruction Entry
User instruction entries should follow this format:

[User Instruction Summary]
- Date: [YYYY-MM-DD]
- Context: [Mentioned scenario or time]
- Instructions:
  - [Content of user teaching or instruction, described line by line]

### Project Knowledge Entry
Entries discovered by the Agent during task execution should follow this format:

[Project Knowledge Summary]
- Date: [YYYY-MM-DD]
- Context: Discovered by Agent while performing [specific task description]
- Category: [Operations & Deployment|Build Methods|Testing Methods|Troubleshooting & Debugging|Workflow & Collaboration|Environment Configuration]
- Instructions:
  - [Specific knowledge points, described line by line]

## Deduplication Strategy
- Before adding a new entry, check for similar or identical instructions.
- If a duplicate is found, skip the new entry or merge it with the existing one.
- When merging, update the context or date information.
- This helps avoid redundant entries and keeps the memory file tidy.

## Entries

[User Instruction Summary]
- Date: 2026-08-29
- Context: 完成考点阅读器搜索/跳转优化任务后用户补充的构建工作流
- Instructions:
  - 本仓库不需要本地构建手环 rpk：push 到 main 后 GitHub Action 自动构建
  - 用户临时提供 GitHub token 用于 `gh run list`/`gh run view` 查看 CI 日志；token 绝不写入任何仓库文件或 git 配置，只在命令环境变量中使用
  - CI 失败则修复后重新推送触发构建；CI 成功则不需要任何后续处理

[Project Knowledge Summary]
- Date: 2026-08-27
- Context: Discovered by Agent while splitting the monorepo into three repositories per user instruction
- Category: Operations & Deployment
- Instructions:
  - 项目已拆分为三个独立仓库，均由用户提供的 GitHub token 管理推送（token 只能用户临时提供，绝不写入任何仓库文件）：
    - vultra-c/examreader：手环端考点阅读器（本仓库，含 考点阅读器/、release/rpk、docs、bandburg-kdreader.js）
    - vultra-c/Snapnotes-android：Android 手机端（源码等同旧 android_src/snapnotes-android）
    - vultra-c/Chemical-calculator：化学工具箱（源码等同旧 化学计算器/）
  - 三仓均有 .github/workflows/，push main 自动构建安装包传 artifact：安卓 assembleRelease（JDK21+gradle）；两个 Vela 应用 setup-node 20 + npm install（postinstall 打 patch-aiotpack）+ npm run release，工件在 dist/（其 .gitignore 忽略）
  - 签名规范：手环 rpk 证书 考点阅读器/sign/release（CN=snapnotes）与化学 sign/release（CN=com.whyy.chemcalc）不可更换，换手环签名=旧用户无法覆盖升级；APK 用 Snapnotes-android/app/snapnotes.p12 共享签名（由手环私钥合成，有效期 2053），CI/本地构建签名统一，覆盖安装不冲突
  - 手环连接只校验包名 + __hs__ 握手版本号（手机端 MIN_BAND_VERSION_CODE=2），与 APK 签名无关
  - 手环端 BAND_VERSION_CODE（interconnHs.js）必须与 manifest.json versionCode 保持同步（手机端校验的是 manifest 版本，握手上报版本仅作展示与阈值判定）

[Project Knowledge Summary]
- Date: 2026-08-27
- Context: Discovered by Agent while fixing 化学工具箱 list row-collapse and optimizing both Vela apps
- Category: Troubleshooting & Debugging
- Instructions:
  - Vela 快应用 list 真机渲染可靠模式（考点阅读器真机验证可用，化学工具箱 flex 流内 list 行曾塌陷成细线）：
    - list 用 position:absolute 挂为页面顶层子节点，显式 width/height，不参与 flex 流测量
    - list-item 用静态 class（禁止动态 class 如 `class="{{$item.x ? 'a' : 'b'}}"`）+ 显式 height/padding/margin
    - list-item 内容统一用一层 div 包裹，不直接放 text
    - 同 type 的 list-item DOM 结构必须完全一致，for 用 $item，tid 唯一
  - 构建命令：各应用目录下 `npm install`（postinstall 会打 patch-aiotpack 补丁防 rspack SIGBUS）后 `npx aiot build` / `npx aiot release --enable-jsc`
  - 构建产物先落在 `<项目>/dist/`（日志里显示的 `.temp_*` 目录会被工具链自动迁移回项目 dist）
  - debug 包可直接解包检查编译产物（JS 可读），release 包经 jsc 字节码化
  - 化学工具箱冒烟测试：`cd 化学计算器 && node tests/smoke.mjs`（62 例，纯 Node 可跑，不依赖 Vela 环境）
  - dataManager.js 等 Vela 模块的语法检查：`node --experimental-vm-modules` 配合 vm.SourceTextModule（@system.* 模块在 Node 无法 resolve，但可 parse 校验语法）
