# Drip

> Xposed 兼容注入框架 · 把「不被检测」放在第一位

**Drip 不是又一个 LSPosed 分支。** LSPosed 类框架解决的是「模块能不能跑起来」；Drip 解决的是「跑起来了，目标应用依然看不见」。

在 LSPlant + ReZygisk 之上，Drip 把工程重心全部压在对检测的对抗上：内核级执行断点、framework dex 混淆、安装期随机化、无可反射探测的稳定类面、寄生式 Manager、每模块独立日志。API 面兼容 libxposed 100 / 101 / 102 与 legacy 82 / 93，现有模块基本无需改动即可迁移。

> 设计优先级：**① 极致隐藏 ＞ ② 稳定兼容 ＞ ③ 官方 API 一致**。
> 与隐藏冲突时，Drip 会为隐藏让路——例如刻意不向目标进程暴露稳定的、可被 `Class.forName` 探测的 Xposed 类面。

## 和 LSPosed 类框架的差异

| | LSPosed 类框架 | Drip |
|---|---|---|
| **Hook 位置** | 用户态，ART 内联 hook | **内核模式**：内核执行断点，hook 逻辑运行在内核态；内核不支持时自动回退用户态 |
| **进程内痕迹** | 框架类名 / 包名固定，可反射探测 | framework dex 的类名 / 方法名经自研混淆引擎重写，**没有稳定可探测的符号面** |
| **日志 tag** | 固定 tag | 安装即随机化，检测方按固定字符串 grep 全部落空 |
| **Manager** | 独立 APK，常驻桌面 | **寄生式**：无需单独安装，通知栏入口，被系统回收自动拉起 |
| **模块日志** | 混在全局 logcat | 每个模块独立落盘，Manager 里按模块分开查看 |
| **native hook** | 无框架级 API，需模块自带 native 库 | 框架直接提供：hook 已加载 `.so` 的导出符号、Java `native` 方法 |
| **ROM 模块检测** | 无 | 识别 ROM 自带的内核模块检测器（如 OPPO `inte.ko`），可在验证页选择处置 |

### 反检测是怎么落地的

- **无稳定 API 面**：应用无法用 `Class.forName("de.robv…")` 判断 Drip 是否激活，模块判活方式与 LSPosed 不同。
- **混淆与随机化**：framework dex 的类名 / 方法名由自研引擎重写；框架日志 tag、SELinux 策略类型名、dex2oat wrapper 的 socket 名在安装时随机生成。
- **模块侧配合**：「方法名随机化」默认开启；个别与混淆冲突的模块可单独开「兼容性增强」豁免（隐藏性略降，换兼容）。
- **静默**：完全静默模式可关掉一切日志输出；框架无联网、无遥测。

## 内核模式与安全阀

| 模式 | Hook 实现 | 隐藏性 |
|---|---|---|
| user（回退模式） | 用户态 LSPlant / Dobby | 功能可用，但**不保证隐藏**（存在 libart / jit-zygote-cache 等指纹） |
| kernel | 内核执行断点 | 用户态几乎不可见，隐藏性最佳 |

- 首次使用在 Manager 中做一次全屏验证：打包 → 校验 → 回填 → 比对 → 加载 → 测试，全部通过才允许切到内核模式。
- 内核不支持时**拒绝加载并自动回退**用户模式，不阻塞开机。
- 连续异常自动进入**安全模式**；退出安全模式只写标记，重启后生效。

内核模式支持矩阵：

| 内核版本 | 状态 |
|---|---|
| 5.15 | 已验证 |
| 6.6 | 已验证 |
| 6.12 | 模拟器全绿 |
| 4.19 | 适配中 |
| 6.18 及以上 | 暂不支持（上游移除了断点注册接口），自动回退用户模式 |

## 功能

**兼容与开发**

- libxposed API 100 / 101 / 102，legacy 82 / 93；同一 APK 可同时声明两代入口
- 按应用作用域，支持多用户与应用分身
- 动态作用域：模块运行时申请把新应用加入作用域，Manager 通知中批准
- 热重载：API 102 模块更新后即时换代生效，目标进程无需重启
- remote preferences / remote files：模块 App 与 hook 进程经框架代管共享数据
- native hook：hook 已加载动态库的导出符号与 Java `native` 方法（契约见 [api/README.md](api/README.md)）
- 每模块「兼容性增强」：单独豁免混淆，换兼容

**管理与观测**

- 寄生式 Manager：开机静默自愈，通知栏常驻入口，宿主被回收后自动恢复
- 每模块独立日志（采集 `XposedBridge.log` / `XposedInterface.log`，直接 `Log.x` 不采集）
- CLI `drip_cli`：模块启停、作用域、热重载、日志 tag、系统日志缓存、内核模式与安全模式
- 注入与 hook 链路可观测（转储）

## 环境要求

- Android 9 及以上（minSdk 27）
- 任意 root 管理器（APatch / Magisk / KernelSU）+ 已启用任意 Zygisk 实现

## 安装

1. 在 [Release](https://github.com/AYwlilwYA/Drip-p/releases) 下载 `drip-module.zip`
2. 在任意 root 管理器中刷入
3. 用同目录的 `.sha256` 校验产物
4. 重启设备
5. 从通知栏打开 Manager，按需完成内核模式验证（建议做）

## 帮助

- 提 issue 请附带转储日志；只有一句描述的 issue 无法处理。

## 模块开发

- [Native Hook API 契约](api/README.md)
- Manager 源码在 [`manager/`](manager/)；模块 API 面与 LSPosed 写法的差异，以当前版本行为为准。

Manager 是独立 Gradle 工程：

```bash
cd manager
./gradlew :app:assembleDebug
# 输出：app/build/outputs/apk/debug/
```

> 可用的完整模块 ZIP 在本仓库的 `Release` 中提供。

## 开源与可审计

Drip 采用部分开源策略，在透明与保护核心技术之间取得平衡：

- **Manager 源码完全公开**：界面、模块管理逻辑、配置持久化均可在本仓库查看。
- **核心注入实现为预编译闭源**：注入器、native hook 引擎、内核模块等以二进制提供。闭源是为了防止注入思路与反检测策略被快速逆向利用，保持对目标应用的不可见性。
- **行为层可审计**：AIDL 契约、权限声明、文件访问范围均公开；框架运行时无联网行为，数据仅存储于设备本地，不含遥测、统计或上传功能。
- **安全承诺**：Drip 不会收集、上传任何用户数据，所有处理均在设备本地完成。

### 怎么确认官方产物没被篡改？

每个 release 附带 `drip-module.zip.sha256`：

```sh
sha256sum -c drip-module.zip.sha256
```

务必在刷入前校验，只从官方 Release 下载。

## 交流

- Telegram 群组：<https://t.me/drip666nb>

## 致谢

- LSPlant — LSPosed 团队
- Dobby — jmpews
- libxposed API — LSPosed
- ReZygisk — 注入框架

## 许可证

本仓库（manager 源码及公开部分）以 **Apache License 2.0** 协议开源，详见 [LICENSE](LICENSE)。

预编译核心产物不适用本协议，其使用受限于官方发布说明。
