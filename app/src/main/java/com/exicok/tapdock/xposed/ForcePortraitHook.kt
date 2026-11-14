package com.lsp102.tapdock.xposed

import android.content.Context
import android.content.pm.ActivityInfo
import android.os.SystemClock
import com.lsp102.tapdock.DockConfigProvider
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 强制竖屏（名单制）：把名单内应用的一切横屏请求改写成竖屏。
 *
 * 挂在 system_server 的 ActivityRecord.setRequestedOrientation 上 —— 这是所有
 * 方向请求的总闸：应用代码里调 Activity.setRequestedOrientation、以及清单里声明的
 * screenOrientation 在记录创建时写入，全都过这里。改写参数而不是拦方法，
 * WindowManager 的方向执行逻辑照常走，只是值被换成了竖屏。
 *
 * 为什么挂 system_server 而不是逐应用挂：一个钩子覆盖所有应用，用户不用在
 * LSPosed 里给每个目标应用勾模块作用域，只需勾一次"Android 系统（系统框架）"。
 * system_server 里崩了会软重启，所以这里只做"读参数 → 换参数"，任何异常都吞掉。
 *
 * 配置不用广播（system_server 里注册接收器要绕 ActivityThread 拿系统上下文，
 * 得不偿失）：名单变化靠 3 秒缓存的 Provider 懒查询 —— 方向请求本身就是低频调用，
 * 绝大多数时候连查询都不发生；SystemUI 那边本来就每 3 秒轮询一次这个 Provider，
 * App 进程常驻，不会因为这里的查询被反复拉起。
 */
object ForcePortraitHook {

    private const val TAG = "[TapDock] force portrait"

    /** 配置轮询的最小间隔 */
    private const val REFRESH_MS = 3_000L

    /** 这些包永远不强制：模块自身、壳层、桌面（桌面上滑手势依赖方向状态） */
    private val NEVER_FORCE = setOf(
        "com.lsp102.tapdock",
        "com.android.systemui",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
        "android",
    )

    @Volatile
    private var enabled = false

    @Volatile
    private var apps: Set<String> = emptySet()

    @Volatile
    private var queriedAt = 0L

    private var loggedPkg = ""
    private var loggedConfigError = false

    fun hook(classLoader: ClassLoader) {
        try {
            val cls = XposedHelpers.findClassIfExists(
                "com.android.server.wm.ActivityRecord", classLoader
            )
            if (cls == null) {
                XposedBridge.log("$TAG ActivityRecord 未找到，放弃")
                return
            }
            XposedBridge.hookAllMethods(cls, "setRequestedOrientation", object : XC_MethodHook() {
                override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    try {
                        val pkg = packageName(param.thisObject) ?: return
                        if (!shouldForce(pkg)) return
                        if (param.args.isNotEmpty() && param.args[0] != ActivityInfo.SCREEN_ORIENTATION_PORTRAIT) {
                            param.args[0] = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
                            if (pkg != loggedPkg) {
                                loggedPkg = pkg
                                XposedBridge.log("$TAG $pkg 的方向请求改写为竖屏")
                            }
                        }
                    } catch (t: Throwable) {
                        // system_server 里绝不能抛：吞掉，最多次数多了点日志
                        XposedBridge.log("$TAG rewrite failed: $t")
                    }
                }
            })
            XposedBridge.log("$TAG installed（名单初值会在首次方向请求时从 Provider 拉取）")
        } catch (t: Throwable) {
            XposedBridge.log("$TAG init failed: $t")
        }
    }

    private fun packageName(record: Any?): String? = try {
        val pkg = XposedHelpers.getObjectField(record, "packageName")
        pkg as? String ?: (XposedHelpers.getObjectField(record, "info")?.let {
            XposedHelpers.getObjectField(it, "packageName")
        } as? String)
    } catch (_: Throwable) {
        null
    }

    private fun shouldForce(pkg: String): Boolean {
        if (pkg in NEVER_FORCE) return false
        refreshConfigIfNeeded()
        return enabled && pkg in apps
    }

    /** 3 秒缓存：方向请求是低频调用，这里几乎总是直接返回 */
    private fun refreshConfigIfNeeded() {
        val now = SystemClock.elapsedRealtime()
        if (now - queriedAt < REFRESH_MS) return
        queriedAt = now
        try {
            val ctx = systemContext() ?: return
            ctx.contentResolver
                .query(DockConfigProvider.CONTENT_URI, null, null, null, null)
                ?.use { c ->
                    if (!c.moveToFirst()) return@use
                    val ei = c.getColumnIndex("forceportrait")
                    val ai = c.getColumnIndex("forceportraitapps")
                    if (ei >= 0) enabled = c.getString(ei) == "1"
                    if (ai >= 0) {
                        apps = c.getString(ai)?.split('|')?.filter { it.isNotBlank() }?.toSet()
                            ?: emptySet()
                    }
                }
            loggedConfigError = false
        } catch (t: Throwable) {
            if (!loggedConfigError) {
                loggedConfigError = true
                XposedBridge.log("$TAG config query failed: $t")
            }
        }
    }

    /** system_server 的系统上下文（首次方向请求发生在 boot 完成后，currentActivityThread 一定就绪） */
    private fun systemContext(): Context? = try {
        val at = XposedHelpers.callStaticMethod(
            XposedHelpers.findClass("android.app.ActivityThread", null), "currentActivityThread"
        ) ?: return null
        XposedHelpers.callMethod(at, "getSystemContext") as? Context
    } catch (t: Throwable) {
        if (!loggedConfigError) {
            loggedConfigError = true
            XposedBridge.log("$TAG systemContext failed: $t")
        }
        null
    }
}
