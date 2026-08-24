---
name: vela-dev
description: Vela 开发综合指南：openvela 系统开发（架构/编译/应用/移植/调试）、小米 Vela JS 快应用开发（组件/接口/发布）、洛汐逆向参考（私有接口/Lua 表盘/系统文件）。开发、编译、调试、移植 openvela 或小米手环/手表快应用，或查询 Vela API 时使用。
---

# Vela 开发综合指南

本 skill 汇总三套文档：openvela 官方系统开发文档、小米 Vela JS 快应用官方文档、洛汐（docs.luoxe.cn）非官方逆向参考文档。

---

# 第一部分：openvela 系统开发

## 系统是什么

openvela 是基于 Apache NuttX RTOS 的 AIoT 操作系统，定位轻量、标准兼容、安全、高度可扩展。内核基于 NuttX（"Tiny Linux"），POSIX 兼容度约 89%，支持 ARMv7-M/A-R、RISC-V、Xtensa、MIPS 等架构，覆盖 32KB RAM 的 BLE 模组到 512MB RAM 的智能音箱。

三层架构：
- **内核层**：任务调度、IPC、文件系统、设备驱动、TCP/IP 协议栈、电源管理；支持同构/异构多核。
- **服务框架层**：连接、图形、多媒体、安全（TEE+Keystore）、XPC 跨核通信。
- **维测工具**：Logger、Debugger、Emulator（QEMU/Goldfish，支持 CPU 指令集仿真）。

## 代码仓库结构

```
openvela/
├── apps          # 系统应用
├── external      # 第三方库
├── frameworks    # 服务框架导出头文件（.a 库）
├── nuttx         # NuttX 内核、网络、文件系统
├── tests         # 测试集
├── prebuilts     # 工具链
├── vendor/       # 厂商代码（芯片原厂驱动和框架）
└── build.sh      # 编译脚本
```

关键概念：
- 应用是 **Flat Build**（平铺模式），与内核同一地址空间，性能最优但应用崩溃可能影响系统。
- 内存隔离主要靠 **MPU** 而非 MMU。
- 不要使用 `NX_` 前缀的内核内部函数，应使用标准 POSIX 接口。
- 协议栈运行在 AP 侧，外挂 WiFi/蓝牙模块通常仅作收发器。

## 环境搭建与快速上手

环境要求：Ubuntu 22.04（arm64/x86_64），至少 40GB 硬盘、16GB RAM。

```bash
# 依赖
sudo apt update
sudo apt install git curl cmake python3 libc++abi-dev build-essential

# Git LFS（必须，否则大文件拉取后损坏成指针文本）
curl -s https://packagecloud.io/install/repositories/github/git-lfs/script.deb.sh | sudo bash
sudo apt-get install git-lfs
git lfs install

# 同步代码（推荐 dev 分支）
mkdir openvela && cd openvela
repo init -u ssh://git@github.com/open-vela/manifests.git -b dev -m openvela.xml --repo-url=https://mirrors.tuna.tsinghua.edu.cn/git/git-repo/ --git-lfs
repo sync -c -j8

# 图形化配置
./build.sh vendor/openvela/boards/vela/configs/goldfish-arm64-v8a-ap/ --cmake menuconfig
# 编译固件
./build.sh vendor/openvela/boards/vela/configs/goldfish-arm64-v8a-ap/ --cmake -j$(nproc)
# 运行模拟器
./emulator.sh cmake_out/vela_goldfish-arm64-v8a-ap/
```

模拟器启动后出现 `goldfish-armv8a-ap>` / `openvela-ap>` 提示符，可在 NSH 命令行交互。

## 应用开发

### 添加原生应用

以 `apps/examples/hello/` 为例，结构为 `hello_main.c`（`int main(int argc, char *argv[])`，C++ 用 `extern "C"` 包裹）+ `Kconfig`（配置项如 `EXAMPLES_HELLO`、`EXAMPLES_HELLO_PROGNAME`、`EXAMPLES_HELLO_PRIORITY`、`EXAMPLES_HELLO_STACKSIZE`）+ `CMakeLists.txt`（调用 `nuttx_add_application()` 注册）。之后在 menuconfig 启用（`Application Configuration → Examples → [*] "Hello, World!" example`），编译后在 NSH 输入 PROGNAME 运行。

### 应用自启动

启动脚本在 `/etc`（以 romfs 与二进制链接）。需启用 `CONFIG_FS_ROMFS=y`、`CONFIG_ETC_ROMFS=y`、`CONFIG_ETC_ROMFSMOUNTPT="/etc"`、`CONFIG_NSH_SYSINITSCRIPT="init.d/rc.sysinit"`、`CONFIG_NSH_INITSCRIPT="init.d/rcS"`。默认脚本在 `vendor/openvela/boards/vela/src/etc/init.d/`，在 `rcS` 中加 `hello &` 即可自启动。

### 线程与资源

- 应用内推荐 `pthread_create()` 而非底层 `task_create()`。
- 主线程须等所有子线程安全退出后再结束，否则进程被回收、子线程被强制终止。
- 长期服务在 `rcS` 中用 `&` 后台运行。
- Wi-Fi ssid/psk 不要硬编码，用环境变量或配置文件。

### 典型示例（Kconfig 开关）

- 打地鼠：`LVX_USE_DEMO_WHACKMOLE=y`，难度用 `lv_timer_set_period()` 控制。
- 亲戚计算器：`LVX_USE_DEMO_RELATIVES_CALCULATOR=y`，`relation_transformation_t` 状态机。
- 音乐播放器：`LVX_USE_DEMO_MUSIC_PLAYER=y`，媒体仅支持 `*.wav`。
- 自行车码表：`LIB_PNG=y`、`LV_USE_LIBPNG=y`、`NETUTILS_CJSON=y`、`UIKIT=y`、`LVX_USE_DEMO_X_TRACK=y`。
- 智能手环：`LV_USE_FRAGMENT=y`、`LVX_USE_DEMO_BANDX=y`、`BANDX_BASE_PATH="/data"`。

## 设备开发与芯片移植

移植分芯片层与板级层，遵循「架构层 → 芯片层 → 板级层」三层。

### 芯片层（`vendor/<vendor>/chips/<chip>/`）

关键文件：`<vendor>_start.c`（`__start()` 复位入口：清 BSS、拷 .data/RAM 函数、初始化时钟串口、调 `nx_start()`）、`<vendor>_lowputc.c`（串口早期输出）、`<vendor>_irq.c`、`<vendor>_timeisr.c`、`chip.h`、`irq.h`、`Kconfig`、`Make.defs`。

内核中断 API：`up_irqinitialize()`、`up_enable_irq()`/`up_disable_irq()`、`up_prioritize_irq()`、`up_irq_save()`/`up_irq_restore()`、`up_trigger_irq()`（核间中断）、`irq_attach()`/`irq_attach_thread()`/`irq_attach_wqueue()`。

### 板级层（`vendor/<vendor>/boards/<chip>/<board>/`）

关键文件：`src/<vendor>_bringup.c`（`board_early_initialize()` 早期硬件、`board_late_initialize()` 常规驱动）、`src/<vendor>_appinit.c`（`board_app_initialize()`）、`src/<vendor>_boot.c`（`board_app_finalinitialize()`）、`configs/nsh/defconfig`、`scripts/ld.script`（`ENTRY` 为 `_vectors`）、`etc/init.d/rc.sysinit`、`etc/init.d/rcS`、`etc/group`/`etc/passwd`、`include/board.h`。

ETCROMFS 构建：在 Make.defs 用 `RCSRCS += etc/init.d/rc.sysinit etc/init.d/rcS`、`RCRAWS += etc/group etc/passwd etc/build.prop`。

编译产物：`libarch.a`、`libboards.a`、`vela_nuttx.bin`。命令：`./build.sh vendor/<vendor>/<board>/<chip>/configs/nsh --cmake -j8`。

## 调试与测试

### GDB

编译需带 `-g`/`-g3`，安装 `gdb-multiarch`。常用：`gdb ./nuttx`、`target remote <IP>:<Port>`、`bt` 看调用栈、`info threads`/`thread <id>` 查死锁、`watch <expr>` 观察变量、`layout src` 分屏、`disassemble <func>` 反汇编、`set var <name>=<value>` 改值。

### VSCode 调试 SIM

launch.json 配 `"type": "cppdbg"`、`"request": "launch"`、`"program": "${workspaceFolder}/nuttx/nuttx"`、`"MIMode": "gdb"`，在 NSH 输入命令触发断点。

### Cmocka 自测试

测试目录含 `Kconfig`（依赖 `TESTING_CMOCKA`）、`Makefile`（`PROGNAME` 以 `cmocka_` 开头）、`src/test_*.c`（测试函数以 `test_` 开头）、入口调 `cmocka_run_group_tests()`。执行：`./emulator.sh vela` 后 `cmocka -l` 列用例、`cmocka -t TestNuttxMm01` 跑指定用例。

### 常见坑

1. 必须 `git lfs install`，否则大文件损坏。
2. 改 Kconfig 后建议完整重编译（只改 .c/.h 可增量）。
3. Flat Build 下应用崩溃可能影响系统。
4. 模拟器数据不持久化，用 9PFS 映射宿主机文件夹。
5. goldfish arm64 基础配置未开网络桥接/外设，验证网络多媒体用产品形态配置。
6. Android 部署需 `sudo apt install android-tools-adb`。
7. 字体乱码需 `adb push` 字体资源；地图仅保留轨迹（版权）；电量随机跳动/轨迹重放属模拟器正常现象。
8. 修改 `music_player/res/`、`x_track/resource/`、`bandx/resource/` 后需重新 `adb push`。

---

# 第二部分：小米 Vela JS 快应用开发

## 快应用是什么

面向小米 IoT 设备（手表、手环等 Vela OS 设备）的轻量级应用框架，用 JS 开发，构建产物为 `.rpk` 包。

## 工程结构

- `manifest.json`：应用配置，含 `package`、`name`、`versionName`、`versionCode`、`icon`、`config.designWidth`。
- `app.ux`：入口文件，`<script>` 中的代码必须写入，否则不会执行。
- `pages/*.ux`：页面文件，`<template>` 根节点只能有一个。
- `src/`：源码目录，可含页面、样式、资源。

## 开发环境与工具链

| 工具 | 用途 |
|---|---|
| AIoT-IDE | 基于 VS Code 的 IDE，支持模板、构建、发布 |
| `npm create aiot` | 创建项目 |
| `aiot start` | 启动项目，首次创建模拟器 |
| `aiot build` | 构建 debug 包（.rpk） |
| `aiot release` | 构建 release 包 |
| `aiot getConnectedDevices` | 获取已连接设备 |
| `aiot crateVelaAvd` / `aiot deleteVelafangAvd` | 创建/删除 Vela 模拟器 |

AIoT-toolkit 2.0 迁移注意：`{{}}` 中不再嵌套 `{{}}`；动态路径改绝对路径（`../../common` → `/common/`）；`transform`、`background`、`filter`、`url` 等动态样式按 2.0 规范调整。

## 组件体系

- **基础组件**：`text`、`image`、`progress`、`chart`。`image` 的 `src` 不要用 `src="/common/{{type}}"` 拼接，直接用变量 `src="{{imgPath}}"`。
- **容器组件**：`div`、`list`、`scroll`、`swiper`。`div`/`list`/`scroll` 默认 Flex 布局；`swiper` 有 `swipestart`、`swipeend` 事件。
- **表单组件**：`input`、`switch`、`slider`。
- **通用能力**：`animation`、`class`、`style`、`opacity`、`transform`、`transition`、`border-radius`、`border`、`box-shadow` 等。

## 接口体系

- **基础**：`@system.app`、`@system.device`、`@system.router`、`@system.storage`、`@system.clipboard`。`@system.router.push({ uri, params })` 页面跳转；`@system.device.getInfo()` 获取设备信息（APILevel2 加 `deviceType`/`APILevel`，APILevel3 加 `screenDensity`/`screenShape`）。
- **数据**：`@system.storage`（`get`/`set`/`clear`/`delete`）；`@system.file`（`move`/`copy`/`list`/`get`/`delete`，URI 如 `internal://cache/`、`internal://files/`）。
- **网络**：`@system.fetch`（`fetch.fetch()`，支持 `method`/`header`/`responseType`，`success` 返回 `code`/`data`/`headers`）；`@system.interconnect`（与手机 App 通信，`interconnect.instance()`、`getReadyState()`）。
- **系统**：`@system.battery`（`getStatus()` 返回 `charging`/`level`）；`@system.bluetooth.ble`（`createScanner()`、`createGattClientDevice()`、`startBLEScan()`）；`@system.event`（APILevel4 新增）。
- **安全**：`@system.crypto`（`hashDigest()`/`hmacDigest()`/`sign()`/`verify()`/`encrypt()`，支持 MD5/SHA1/SHA256/SHA512、RSA/AES）。

## 设计规范与最佳实践

- **多屏设计**：圆屏 466x466、矩形屏 336x480、胶囊屏 192x490；圆形/胶囊屏注意安全区域，主体功能放安全区域内。
- **自适应**：Flex 布局 + `config.designWidth`，CSS 用 `px` 按设计稿基准等比缩放。
- **最佳实践**：
  - 避免 `setTimeout` 延迟跳转，异步用 Promise/await。
  - logo 页避免 HTTP 请求。
  - 大图加载前加 loading，下载后缓存本地；大图不超过屏幕尺寸、≤200KB。
  - 列表分页，每页 ≤20 个 item。
  - 网络请求加 loading，防按钮重复点击。
  - 减少长 `console` 日志，release 用 TerserPlugin 过滤 `console.debug`。

## 发布与版本

- `aiot build` 出 debug 包，`aiot release` 出 release 包；release 用于发布。
- 验收标准：首页 FMP ≤ 2000ms。
- APILevel 能力：L2 媒体查询/`barcode`/`qrcode`/`image-animator`/`scroll`/`getBoundingClientRect`；L3 `box-shadow`/`$canIUse`/`@system.uploadtask`/`app.canIUse()`/`dp` 单位；L4 `@system.event`。
- **证书**：涉及手表与手机通信时，release rpk 证书须与手机 App 证书一致，变更可能导致无法上架。

## 常见坑

1. `app.ux` 代码必须写入 `<script>`，否则不执行。
2. `*.ux` 的 `template` 根节点只能有一个。
3. `list-item` 中谨慎用 `if`/`else`/`show`，需保证结构一致。
4. `image` 的 `src` 不要变量拼接。
5. 网络/设备接口按设备/平台能力判断支持性。
6. 亮屏会重新触发 `onShow`，其中如有 `fetch` 请求需谨慎。
7. 列表更新闪烁可加 `tid`。
8. 通信签名错误时检查 rpk 与手机 App 证书是否配套。

---

# 第三部分：Vela 逆向参考（洛汐文档库）

> 来源：https://docs.luoxe.cn/docs/vela/ 。非官方逆向文档，基于公开源码、固件静态分析和设备内置资源整理。未公开接口可能随系统升级删除/改名/限制调用，使用后果由使用者自行承担。

## 使用前准备

- **manifest 声明**：在 `manifest.json` 的 `features` 数组声明，如 `{ "features": [{ "name": "system.zlib" }] }`。安装阶段提示 feature/权限不允许时应删除声明或改用公开 API，不要靠捕获 JS 异常绕过。
- **能力探测**：快应用用 `app.canIUse('@feature.method')`；Lua 用 `pcall(require, 'module')`。推荐同时探测 feature 与方法：`app.canIUse(name) && app.canIUse(\`${name}.${method}\`)`。`canIUse()` 只证明运行时导出了成员，不代表签名/权限/设备状态允许调用。
- **回调封装**：多数旧 feature 用 `success/fail/complete`，`fail` 通常是 `(message, code)` 两个位置参数；可用 Promise 封装统一处理。不要假定所有 `complete` 无参数（如 `system.exchange`、`system.internal.power` 的 complete 接收字符串）。
- **清理原则**：传感器、健康、微信事件、消息中心等订阅型接口，页面销毁/应用退出时必须调用对应取消方法，防句柄泄漏和后台耗电。

## Features 私有接口

| Feature | 关键接口/用途 |
|---|---|
| `service.health` | `getRecentSamples`/`subscribeSample`/`unsubscribeSample`；数据类型以设备实际 `health.DATA_TYPES` 为准 |
| `service.miaccount` | `getUserId`/`loginByScan`/`getServiceToken`/`refreshServiceToken`/`setEnv`/`encryptRequestParams`/`decryptResponseData` |
| `service.wechat` | `js_invoke_function`/`js_regist_task_callback`/`js_regist_event_callback` 等，微信任务桥（非登录/支付） |
| `jumpApp` | `jumpApp(uri, param)` 跳原生应用；`launchQuickApp(uri, param?)` 启动另一个快应用 |
| `system.cipher` | `rsa`/`sign`/`verify`/`digest`/`md5`/`aes`，支持 MD5/SHA1/SHA256/SHA512，默认 SHA256 |
| `system.mqttmessage` | 内部 MQTT 消息结构 `ts`/`id`/`carId`/`packageName`/`action`/`data`；`verify`/`encode`/`decode`，不建连不发布 |
| `system.settings` | 系统属性键值桥（不是 `system.storage`） |
| `system.zlib` | 仅同步 `decompressSync(data)` 返回 `Uint8Array`，依次尝试 zlib/raw DEFLATE/gzip；大数据会阻塞 JS/uvloop，应限制输入 |
| `locale` | `locale.get()` 同步返回 `{ language, countryOrRegion }` |
| `Error` | `Error.strerror(errnum)` 把 libuv/NuttX 整数错误码转文本 |

权限风险高（可能受签名/包名/白名单限制）：`service.wechat`、`system.internal.power`、`system.internal.activity`、`system.internal.messagecenter`、`system.exchange` 的 vendor/application scope、`service.health`。

## Extensions 补充成员

- `system.brightness`：`brightness.systembrightnessrecovery()` 恢复系统亮度/亮屏状态。
- `system.device`：`getId`/`getDeviceId`（同步）/`getInfo()` 额外字段 `IMEI`/`miProductId`/`deviceModel`/`miDeviceAlias`；`getDeviceId` 被系统黑名单拦截。
- `system.interconnect`：`connect.getApkStatus()` 返回 `CONNECTED`/`DISCONNECTED`/`UNINSTALLED`；另有 `getReadyState()`/`diagnosis()`/`send()`。
- `system.prompt`：`prompt.showDialog(options)` 同步返回 void，参数含 `title`/`message`/`autocancel`/`success`/`cancel`/`complete`。
- `system.request`：`download()` 遗漏字段 `share`（Boolean，默认 true）、`onDownLoadNotify`（进度回调，注意 L 大写）。
- `system.sensor`：专用订阅 `subscribeProximity`/`subscribeLight`/`subscribeStepCounter`/`subscribeHumidity`/`subscribeAmbientTemperature`；通用 `subscribe`/`getRecentData`/`checkAvailable`；`DATA_TYPES` 含 ACCELEROMETER/COMPASS/PROXIMITY/STEP_COUNTER/BAROMETER/HUMIDITY/AMBIENT_TEMPERATURE/WRIST_LIFT。
- `system.storage`：`storage.key(options)` 按索引取 key；目标固件未确认公开 `length`，不可无上限扫描。

## Lua 表盘开发

最小模块：`lvgl`、`dataman`、`topic`、`activity`、`animengine`、`navigator`、`vibrator`、`screen`。

- **生命周期**：`ui.init(style)` 创建表盘；`pageOnPause()` 暂停订阅/动画/Timer；`pageOnResume()` 恢复。页面退出后不要继续持有 LVGL userdata。
- **运行环境**：Lua 5.4.0；`package.loadlib()` 关闭（不能用 .so）；`io.popen()` 不可用；模块由表盘宿主创建，快应用 RPK 中不能直接 `require`。
- **dataman**：`dataman.subscribe(key, object, callback) -> token`；`pause(token)`/`resume(token)`。数据源覆盖时间日期（`timeHour`/`timeMinute`/`dateWeek`/`dateLunarYear`）、健康（`healthStepCount`/`healthHeartRate`/`healthCalorie`）、天气（`weatherCurrentTemperature`/`weatherCurrentHumidity`）、系统（`systemStatusBattery`/`systemStatusCharge`）。多数数值为 Q24.8 定点，用 `value // 256` 解码。
- **topic**：`topic.subscribe(name, callback) -> subscription` 订阅 uORB 主题。传感器主题 `sensor_accel`/`sensor_gyro`/`sensor_mag`/`sensor_baro`/`sensor_temp`/`sensor_humi`/`sensor_light`/`sensor_prox`/`sensor_hrate`；系统状态 `screen_status`/`battery_state`/`bt_stack_state`/`miwear_event`/`system_event`。`subscription.unsubscribe()` 取消，重复取消报错。
- **animengine**：`animengine.create(object, configString) -> animation`，JSON 描述动画；`start()`/`remove()`/`modify(json)`。属性含 `x`/`y`/`rotate`/`opacity`/`scale`/`img_zoom`/`start_angle`/`end_angle`/`custom_translate`/`custom_rotate`。
- **LVGL**：标准控件 `Image`/`Label`/`Led`/`List`/`Textarea`/`Calendar`/`Checkbox`/`Dropdown`/`Keyboard`/`Roller`；扩展控件 `Pointer`/`AnalogTime`/`ImageLabel`/`ImageBar`/`CurvedLabel`/`Thumbnail`/`ImageLineBar`/`imggroup`/`frameanim`/`xcanvas`。文件操作 `lvgl.fs.open_dir(path)`、`lvgl.fs.open_file(path, mode)`。
- **vibrator**：`vibrator.start(type [, repeat])`/`vibrator.cancel(type)`。类型含 `CROWN`/`KEY_BOARD`/`WATCH_FACE`/`SYSTEM_OPRATION`/`HEALTH_ALERT`/`SYSTEM_EVENT`/`TARGET_DONE`/`BREATHING_TRAINING`/`INCOMING_CALL`/`CLOCK_ALARM`/`SLEEP_ALARM`。

## 弦应用 Sine（弦电子书）

- 支持设备：小米手环 8 Pro/9/9 Pro/10/10 Pro、Watch S 系列、REDMI Watch 5/6。
- 安装传书：手机端装「弦电子书」同步器，手环端打开应用后手机端搜索连接、选书同步。
- 阅读：列表/封面书架、分类、进度；滚动/怀旧两种模式；字号 5-50px、透明度 10-100%、12 种字体色、13 种背景色、亮度跟随或自定义 20-255。
- 注意：手环 10 系列可能丢字库，需恢复出厂设置。

## 系统与工具

- **ROMFS /etc**：p67tc 含 AP/BL2/Factory/OTA/Recovery 五套独立 ROMFS；o63 可提取 AP 与 OTA。同名文件（如 `rcS`）不能跨套互换。AP 的 `/etc` 含 `build.prop`、`font_config.json`、`dbus-1/system.conf`、`miwear_product.json`、`init.d/rc.sysinit`、`init.d/rcS`。
- **启动脚本**：`rc.sysinit` 先建运行环境（挂载文件系统、属性/数据库守护、基础日志）；`rcS` 挂载完成后启动业务服务。BL2 `rcS` 根据复位原因/OTA 状态/恢复标志/工厂状态决定启动 OTA、Recovery、Factory 或 AP。
- **/data 目录**：p67tc 用 `/dev/nand_data`（YAFFS）；o63 用 `/dev/data`（先 `fsckexfat -y -v` 再挂）。主要目录：`/data/app/`（系统应用 db）、`/data/quickapp/`（快应用数据，`internal://cache/`、`internal://files/` 映射到 `cache/{package}/`、`file/{package}/`）、`/data/fitness/`（运动健康）、`/data/gps/`、`/data/misc/bt/`（`bt_storage.db`）、`/data/offlinelog/`、`/data/recording/`、`/data/ota.zip`、`/data/vela_ota.bin`、`/data/recovery/`。
- **截屏**：p67 用 `dd if=/dev/fb0 of=/data/snapshot/<名称>.bin bs=483840 count=1`（framebuffer 原始像素，非 PNG）；o63 可用 `miwear-snapshot`。读取 `/dev/fb0` 前用 `FBIOGET_VIDEOINFO`/`FBIOGET_PLANEINFO` 获取 xres/yres/bpp/fmt/stride/fblen。
- **/dev 节点**：分区块设备 `/dev/ap`、`/dev/bl2`、`/dev/resource`、`/dev/nand_data`、`/dev/data`；控制台 `/dev/console`、`/dev/ttyS0`、`/dev/kmsg`、`/dev/log`；显示输入 `/dev/fb0`、`/dev/input0`、`/dev/buttons`；传感器 `/dev/uorb/sensor_accel0` 等；通信 `/dev/ttyBT`、`/dev/ttyGNSS`、`/dev/tun`；电源振动 NFC `/dev/charge/batt_charger`、`/dev/lra0`、`/dev/nfc_sn100`、`/dev/rtc`、`/dev/watchdog0`。
- **安全提醒**：不要直接试写未知设备；对分区节点 `dd`/重定向/随机 ioctl 可能破坏系统；对 watchdog、充电、NFC、触控固件节点误操作可能造成重启、失去触控或硬件异常。

## 机型可用性

以 `Xiaomi Smart Band 10 Pro` 与 `Xiaomi Watch S4 41mm` 为基准，`✅` 可用、`❌` 不可用、`△` 部分成员可用或需系统授权。如 `system.exchange`（Band 10 Pro ✅ / Watch S4 ❌）、`system.mqtt`（❌ / △）、`system.settings`（❌ / △）。其他机型以官网 [Xiaomi Vela JS 应用接口机型支持](https://iot.mi.com/vela/quickapp/zh/features/) 为准。