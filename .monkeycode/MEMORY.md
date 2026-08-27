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
