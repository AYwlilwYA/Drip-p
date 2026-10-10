// StatusNotificationController：常驻通知的独立触发通道（进程级，UI 完全解耦）。
//
// 背景：原实现把「何时发通知」散在 injectManagerBinder / MainActivity / SettingsScreen
// 四处直投，且注入恒早于 Application 创建（appContext==null）→ 无 UI 场景（开机静默自愈）
// 通知永远发不出，只有打开 UI 才被 MainActivity 带出来（见 doc/spec/drip-m8-fix-boot-notification.md）。
// 本控制器改为事件驱动状态机：
//
//   事件 A onInjected()          —— 注入链路事件（binder 注入成功，通常先于 Application）
//   事件 B onAppReady(context)   —— 进程 bind 事件（DripManagerApp.onCreate，无 UI 必达）
//   事件 C onToggleChanged()     —— 开关变化（SettingsScreen 转交，UI 不直投）
//   事件 D onPermissionGranted() —— 授权完成（权限弹窗是 Android 13+ 系统硬约束，必须由
//                                   Activity 发起；结果转交本控制器决策）
//   事件 E onDaemonBinderDied()  —— daemon 断连（会话复位，允许重注入后再投）
//
//   A 与 B 到达顺序不定（重注入可发生在 App 存活期），齐备即投递；同进程同一次注入
//   会话只投一次（宿主被重拉 = 新进程 = 状态自然重置）。
package drip.manager.data

import android.content.Context
import android.util.Log

object StatusNotificationController {

    private const val TAG = "DripStatusNotif"

    /** 事件 A 已到达：注入通道建立（与 ManagerServiceClient.connected 同刻置位）。 */
    @Volatile
    private var injected = false

    /** 事件 B 已到达：进程 Application 就绪（Context 可用，投递前提）。 */
    @Volatile
    private var appContext: Context? = null

    /** 会话去重：同进程同一次注入会话只投一次。 */
    @Volatile
    private var postedThisSession = false

    /** 事件 A：注入完成（ManagerServiceClient.injectManagerBinder 成功路径末尾调用）。 */
    fun onInjected() {
        injected = true
        Log.i(TAG, "event: injected")
        evaluate("injected")
    }

    /** 事件 B：进程 bind 就绪（DripManagerApp.onCreate 调用；无 UI 场景的触发点）。 */
    fun onAppReady(context: Context) {
        appContext = context.applicationContext
        Log.i(TAG, "event: appReady")
        evaluate("appReady")
    }

    /** 事件 C：开关变化（SettingsScreen 转交；UI 只改开关，不碰投递原语）。 */
    fun onToggleChanged(enabled: Boolean, context: Context) {
        appContext = context.applicationContext
        if (enabled) {
            postedThisSession = false   // 允许重新投递（开关重开 = 新意图）
            evaluate("toggle-on")
        } else {
            cancelStatusNotification(context)
            Log.i(TAG, "event: toggle-off, notification cancelled")
        }
    }

    /** 事件 D：授权完成（Activity 权限回调转交；授权后若条件齐自动补投）。 */
    fun onPermissionGranted(context: Context) {
        appContext = context.applicationContext
        Log.i(TAG, "event: permissionGranted")
        evaluate("permissionGranted")
    }

    /** 事件 E：daemon binder 死亡（ManagerServiceClient.deathRecipient 调用）。 */
    fun onDaemonBinderDied() {
        injected = false
        Log.i(TAG, "event: daemonBinderDied, session reset (awaiting re-inject)")
    }

    /**
     * 统一决策 + 投递（会话幂等，锁内 check-and-set 防并发双投）。
     * 全条件齐才投：注入 ✓ + Context ✓ + 连接 ✓ + 开关 ✓（daemon 远端查询，无记录默认开）
     * + 权限 ✓（Android 13+；缺权限不发，等待 UI 引导授权后事件 D 补投）。
     * 任一条件不满足静默返回——对应事件到达时 evaluate 会再次触发，无需轮询。
     *
     * **2026-09-16 起本控制器不再投递常驻通知**：常驻通知已改由 daemon 直发
     * （`drip.daemon.util.StatusNotifier`，id 1001 / channel `drip_status` 同一条，daemon 启动
     * 与开关变更时投递或撤销）。原因：manager 寄生宿主 `com.android.shell` 是后台 cached 进程、
     * 会被系统冻结 ⇒ 投递/撤销排队延迟；daemon 是 root 常驻、且以系统身份投递还免掉了
     * POST_NOTIFICATIONS 权限门。此处只记事件（保留调用点，方便排查注入会话时序），不投递。
     */
    private fun evaluate(reason: String) {
        Log.i(TAG, "evaluate(trigger=$reason): posting delegated to daemon StatusNotifier" +
            " (injected=$injected, connected=${ManagerServiceClient.connected})")
    }
}
