package com.lsp102.tapdock.xposed

import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers

/**
 * 自由窗口（长按图标弹出的「自由窗口」）：钉在最上层，并把开着的那些交给控制中心列出、关闭。
 *
 * 置顶走 SystemUI 里 WM Shell 自己的通道：ShellTaskOrganizer 每次上报任务时认出 freeform 任务，
 * 对它下发 `WindowContainerTransaction.setAlwaysOnTop(token, true)`。WM 给 root task 排图层时
 * 把 always-on-top 单独一组放到普通应用之上（TaskDisplayArea#assignRootTaskOrdering），层级由
 * 平台自己维持，后开的 App 盖不住小窗。
 * 没有选"每次切换都抢一次最前"的写法：那种做法要盯着每一帧过渡事务，和动画、和 WM 自己排的序
 * 都会打架。
 *
 * 列表和关闭也挂在同一个 organizer 上：token 只有任务自己那侧拿得到，而关窗口用的
 * `removeTask(token)` 正是小窗标题栏 ✕ 走的那个事务。
 */
object FreeformTopHook {

    private const val ORGANIZER = "com.android.wm.shell.ShellTaskOrganizer"
    private const val TRANSACTION = "android.window.WindowContainerTransaction"

    /** 一个开着的小窗口；控制中心按这份列表逐条画出来。w×h 是它当前的像素尺寸，0 = 没读到 */
    internal class Win(
        val taskId: Int,
        val pkg: String,
        val token: Any,
        val w: Int,
        val h: Int,
        val frame: Frame?,
    ) {
        /** "1240×800"，没读到尺寸就留空，卡片上不写 0×0 */
        val sizeText: String get() = if (w <= 0 || h <= 0) "" else "${w}×${h}"
    }

    /**
     * 窗口在屏幕上的绝对矩形（像素），给 App 侧截屏裁剪用。
     * 和 [Win] 的 w×h 不是一回事：那个是应用自己的画布大小（去掉装饰），这个是屏幕上占的那块。
     */
    internal data class Frame(val x: Int, val y: Int, val w: Int, val h: Int) {
        /** `左,上,宽,高`；面板按 `任务id:这段` 拼成裁剪请求 */
        val spec: String get() = "$x,$y,$w,$h"
    }

    @Volatile
    private var wins: List<Win> = emptyList()

    /** 已经请求过置顶的任务 id，同一任务反复上报不再下发事务 */
    private val pinned = hashSetOf<Int>()

    /** 回调传进来的就是进程内唯一的那个 ShellTaskOrganizer，面板关窗口时复用它的权限 */
    private var organizer: Any? = null

    /** WINDOWING_MODE_FREEFORM 是 @hide 常量，编译期的 android.jar 里没有，装 hook 时取一次 */
    private var freeformMode = -1

    fun hook(classLoader: ClassLoader) {
        val cls = XposedHelpers.findClassIfExists(ORGANIZER, classLoader)
        if (cls == null) {
            XposedBridge.log("[TapDock] 这个 SystemUI 里没有 $ORGANIZER，自由窗口置顶未启用")
            return
        }
        freeformMode = try {
            Class.forName("android.app.WindowConfiguration")
                .getField("WINDOWING_MODE_FREEFORM").getInt(null)
        } catch (t: Throwable) {
            XposedBridge.log("[TapDock] 取不到 WINDOWING_MODE_FREEFORM: $t")
            -1
        }
        val sync = object : XC_MethodHook() {
            override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                try {
                    applyTop(param.thisObject, param.args.getOrNull(0))
                } catch (t: Throwable) {
                    XposedBridge.log("[TapDock] 自由窗口处理失败: $t")
                }
            }
        }
        // changed 这条不能省：小窗菜单里"放大"会把任务切成全屏，切回去又要变 freeform，
        // 只盯 appeared 的话全屏窗口会一直被钉在最上层，列表里也会多出一条已经不存在的小窗
        XposedBridge.hookAllMethods(cls, "onTaskAppeared", sync)
        XposedBridge.hookAllMethods(cls, "onTaskInfoChanged", sync)
        XposedBridge.hookAllMethods(cls, "onTaskVanished", object : XC_MethodHook() {
            override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                val info = param.args.getOrNull(0) ?: return
                val id = info.taskId() ?: return
                drop(id)
                pinned.remove(id)
            }
        })
        XposedBridge.log("[TapDock] 自由窗口置顶已启用")
    }

    /** 当前开着的小窗口快照（面板主线程读，回调也在主线程，所以直接换整个列表即可） */
    internal fun windows(): List<Win> = wins

    /** 关掉某个小窗口，和标题栏 ✕ 同样等价：任务连同它的进程窗口一起撤掉 */
    internal fun close(taskId: Int) {
        val win = wins.firstOrNull { it.taskId == taskId } ?: return
        val org = organizer ?: return
        try {
            val wct = Class.forName(TRANSACTION).getConstructor().newInstance()
            wct.require("removeTask", win.token)
            org.require("applyTransaction", wct)
            drop(taskId)
            pinned.remove(taskId)
            XposedBridge.log("[TapDock] 小窗口 #${taskId} (${win.pkg}) 已关闭")
        } catch (t: Throwable) {
            XposedBridge.log("[TapDock] 关闭小窗口失败: $t")
        }
    }

    /** [raw] 是 android.app.ActivityManager.RunningTaskInfo，全程反射（模块编译期碰不到 @hide） */
    private fun applyTop(organizer: Any, raw: Any?) {
        val id = raw?.taskId() ?: return
        this.organizer = organizer
        val token = raw.token()
        val pkg = raw.pkg()
        val freeform = token != null && pkg != null && raw.isFreeform()
        if (!freeform) {
            drop(id)
        } else {
            val (w, h) = raw.size()
            put(Win(id, pkg!!, token!!, w, h, raw.screenFrame()))
        }
        if (freeform == (id in pinned)) return
        val wct = Class.forName(TRANSACTION).getConstructor().newInstance()
        wct.require("setAlwaysOnTop", token, freeform)
        // 已经存在的小窗（比如 SystemUI 重启前就开着的）位置在别人下面，补一次提到最前
        if (freeform) wct.call("reorder", token, true, true)
        organizer.require("applyTransaction", wct)
        if (freeform) pinned.add(id) else pinned.remove(id)
        XposedBridge.log("[TapDock] 自由窗口 #$id ${if (freeform) "钉到最上层" else "取消置顶"}")
    }

    /**
     * 记下这一帧：没有同 id 就追加，有就原地换掉。
     * 换而不是忽略，是因为拖动/改变大小只会走 onTaskInfoChanged，卡片上的分辨率要跟着变。
     */
    private fun put(win: Win) {
        val old = wins.firstOrNull { it.taskId == win.taskId }
        wins = if (old == null) {
            wins + win
        } else if (old.pkg == win.pkg && old.w == win.w && old.h == win.h &&
            old.frame == win.frame && old.token === win.token
        ) {
            wins
        } else {
            wins.map { if (it.taskId == win.taskId) win else it }
        }
    }

    private fun drop(taskId: Int) {
        if (wins.none { it.taskId == taskId }) return
        wins = wins.filter { it.taskId != taskId }
    }

    private fun Any.taskId(): Int? = try {
        XposedHelpers.getIntField(this, "taskId")
    } catch (_: Throwable) {
        call("getTaskId") as? Int
    }

    private fun Any.token(): Any? = try {
        XposedHelpers.getObjectField(this, "token")
    } catch (_: Throwable) {
        call("getTaskToken")
    }

    /** topActivity / baseActivity 都是 ComponentName，取包名给面板去查图标和名字 */
    private fun Any.pkg(): String? {
        val cn = getObject("topActivity") ?: getObject("baseActivity") ?: return null
        return try {
            cn.call("getPackageName") as? String
        } catch (_: Throwable) {
            try {
                XposedHelpers.getObjectField(cn, "mPackage") as? String
            } catch (_: Throwable) {
                null
            }
        }
    }

    private fun Any.isFreeform(): Boolean {
        (call("isFreeform") as? Boolean)?.let { return it }
        val mode = call("getWindowingMode") as? Int ?: return false
        return mode == freeformMode
    }

    /**
     * 小窗当前的像素尺寸，给卡片显示"分辨率"用。
     *
     * 先试 TaskInfo#parentAppBounds —— 那是去掉边框/标题装饰之后真正用来渲染的面积，
     * 也就是 App 看到的画布大小；旧版本没这个字段或它是空的，退回任务自身的
     * configuration.windowConfiguration.bounds。读不到就是 0×0。
     */
    private fun Any.size(): Pair<Int, Int> {
        (getObject("parentAppBounds")?.rect() ?: getObject("configuration")
            ?.getObject("windowConfiguration")?.call("getBounds")?.rect())?.let { return it }
        return 0 to 0
    }

    private fun Any.rect(): Pair<Int, Int>? {
        val w = call("width") as? Int ?: return null
        val h = call("height") as? Int ?: return null
        return if (w > 0 && h > 0) w to h else null
    }

    /**
     * 窗口在屏幕上的绝对矩形，给截屏裁剪用。
     *
     * 取的是任务自身的 `configuration.windowConfiguration.bounds`：顶层任务的这块坐标就是屏幕坐标。
     * 不用 [size] 那条链上的 parentAppBounds —— 那是相对父容器的应用画布，原点不是屏幕上的位置。
     */
    private fun Any.screenFrame(): Frame? = try {
        val rect = getObject("configuration")?.getObject("windowConfiguration")
            ?.call("getBounds")
        val l = rect?.intOf("left")
        val t = rect?.intOf("top")
        val r = rect?.intOf("right")
        val b = rect?.intOf("bottom")
        if (l != null && t != null && r != null && b != null && r > l && b > t) {
            Frame(l, t, r - l, b - t)
        } else {
            null
        }
    } catch (_: Throwable) {
        null
    }

    /** Rect 的 left/top/right/bottom：字段和方法两套名字都试一遍 */
    private fun Any.intOf(name: String): Int? = try {
        XposedHelpers.getIntField(this, name)
    } catch (_: Throwable) {
        call(name) as? Int
    }

    private fun Any.getObject(name: String): Any? = try {
        XposedHelpers.getObjectField(this, name)
    } catch (_: Throwable) {
        null
    }

    /** 找得到就调，找不到返回 null —— 供上层区分"没有这个方法"和"方法返回了 null" */
    private fun Any.call(name: String, vararg args: Any?): Any? =
        javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
            ?.invoke(this, *args)

    private fun Any.require(name: String, vararg args: Any?): Any? {
        val m = javaClass.methods.firstOrNull { it.name == name && it.parameterCount == args.size }
            ?: throw NoSuchMethodException("$name with ${args.size} args")
        return m.invoke(this, *args)
    }
}
