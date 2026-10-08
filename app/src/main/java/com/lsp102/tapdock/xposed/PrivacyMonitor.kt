package com.lsp102.tapdock.xposed

import android.content.Context
import android.os.Handler
import android.os.Looper
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.lang.reflect.Method
import java.lang.reflect.Proxy

/**
 * 摄像头 / 麦克风 / 定位使用状态的真实数据源：
 * 读取 SystemUI 隐私指示器的数据——com.android.systemui.privacy.PrivacyItemController。
 *
 * 本 ROM 上相关类已被 Kotlin 化并改过名，所以不硬编码回调接口名：
 * 回调接口从 `addCallback` 的形参反推，回调方法按名前缀匹配，
 * PrivacyItem 的类型优先读 public 字段（本 ROM 没有 getter）。
 * 任一步失败只记日志，Dock 退回轮询，不连带拖垮其它 hook。
 *
 * 只旁观系统的注册动作来拿单例，**绝不替换系统自己的回调**：
 * PrivacyItemController.removeCallback 会在回调列表上做 Kotlin `==`，
 * 空 Proxy 会让系统的回调对象去调 Proxy.equals，一旦返回 null 就是
 * 主线程 NPE —— QS 下拉必崩，SystemUI 直接进 rescueparty 重启循环。
 */
object PrivacyMonitor {

    @Volatile var camera = false; private set
    @Volatile var microphone = false; private set
    @Volatile var location = false; private set

    /** 正在占用摄像头 / 麦克风的应用包名（Dock 上画它们的图标） */
    @Volatile var cameraPackages: List<String> = emptyList(); private set
    @Volatile var microphonePackages: List<String> = emptyList(); private set

    /** 成功注册到 PrivacyItemController */
    @Volatile
    var available: Boolean = false
        private set

    private val CONTROLLER_NAMES = listOf(
        "com.android.systemui.privacy.PrivacyItemController",
        "com.android.systemui.privacy.PrivacyItemControllerImpl",
    )

    private var registered = false
    private var attaching = false
    private val main = Handler(Looper.getMainLooper())
    private var callbackInterface: Class<*>? = null
    private var ourCallback: Any? = null

    fun start(classLoader: ClassLoader, appCtx: Context, onChange: () -> Unit) {
        if (registered) return
        registered = true
        val controllerCls = CONTROLLER_NAMES
            .asSequence()
            .mapNotNull { XposedHelpers.findClassIfExists(it, classLoader) }
            .firstOrNull()
        if (controllerCls == null) {
            XposedBridge.log("[TapDock] PrivacyItemController not found on this ROM")
            return
        }
        val callbackCls = controllerCls.methods.firstOrNull {
            it.name == "addCallback" && it.parameterTypes.size == 1
        }?.parameterTypes?.get(0)
        if (callbackCls == null) {
            XposedBridge.log("[TapDock] PrivacyItemController.addCallback not found")
            return
        }
        callbackInterface = callbackCls
        ourCallback = callbackProxy(classLoader, callbackCls) { method, args ->
            val items = args?.firstOrNull() as? List<*>
            if (method.name.startsWith("onPrivacyItemsChanged") && items != null) {
                parseItems(items)
                main.post { onChange() }
            }
        }
        val grab = object : XC_MethodHook() {
            override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                attach(param.thisObject)
            }
        }
        try {
            // 本 ROM 把"定位隐私指示"编成了常量 false（PrivacyConfig 里连字段都被 R8 折叠掉），
            // getLocationAvailable() 直接 return 0，控制器于是丢掉所有 TYPE_LOCATION 条目。
            // 打开它之后定位占用才会正常上报（实测：不打开时 items 里永远没有 TYPE_LOCATION）。
            XposedBridge.hookAllMethods(controllerCls, "getLocationAvailable",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: XC_MethodHook.MethodHookParam) {
                        param.result = true
                    }
                })
            XposedBridge.hookAllMethods(controllerCls, "addCallback", grab)
            XposedBridge.hookAllConstructors(controllerCls, grab)
            XposedBridge.log("[TapDock] privacy source hooked on ${controllerCls.name}")
        } catch (t: Throwable) {
            XposedBridge.log("[TapDock] PrivacyItemController hook failed: $t")
        }
    }

    private fun attach(controller: Any) {
        if (available || attaching) return
        attaching = true
        main.post {
            try {
                val iface = callbackInterface ?: return@post
                val callback = ourCallback ?: return@post
                controller.javaClass.getMethod("addCallback", iface).invoke(controller, callback)
                available = true
                XposedBridge.log("[TapDock] privacy source attached")
            } catch (t: Throwable) {
                XposedBridge.log("[TapDock] privacy attach failed: $t")
            } finally {
                attaching = false
            }
        }
    }

    /**
     * Proxy 会把 equals/hashCode/toString 一并转给 handler，而这三个会被系统代码调用，
     * 必须返回非 null 的正确值；其余方法按返回类型给默认值（void 返回 null 才安全）。
     */
    private fun callbackProxy(
        classLoader: ClassLoader,
        iface: Class<*>,
        onCall: (Method, Array<Any?>?) -> Unit,
    ): Any = Proxy.newProxyInstance(classLoader, arrayOf(iface)) { receiver, method, args ->
        if (method.declaringClass == Any::class.java) {
            when (method.name) {
                "equals" -> receiver === args?.getOrNull(0)
                "hashCode" -> System.identityHashCode(receiver)
                else -> "TapDock.${iface.simpleName}"
            }
        } else {
            onCall(method, args)
            defaultValue(method)
        }
    }

    private fun defaultValue(method: Method): Any? = when (method.returnType.name) {
        "boolean" -> false
        "int" -> 0
        "long" -> 0L
        "float" -> 0f
        "double" -> 0.0
        "byte" -> 0.toByte()
        "short" -> 0.toShort()
        "char" -> '0'
        else -> null
    }

    /** 只取摄像头/麦克风/定位占用条目 */
    private fun parseItems(items: List<*>) {
        var cam = false
        var mic = false
        var loc = false
        val camPkgs = ArrayList<String>(2)
        val micPkgs = ArrayList<String>(2)
        for (item in items) {
            if (item == null) continue
            when (privacyTypeOf(item)) {
                "TYPE_CAMERA" -> {
                    cam = true
                    packagesOf(item).forEach { if (it !in camPkgs) camPkgs.add(it) }
                }
                "TYPE_MICROPHONE" -> {
                    mic = true
                    packagesOf(item).forEach { if (it !in micPkgs) micPkgs.add(it) }
                }
                "TYPE_LOCATION" -> loc = true
            }
        }
        camera = cam
        microphone = mic
        location = loc
        cameraPackages = camPkgs
        microphonePackages = micPkgs
    }

    /**
     * PrivacyItem 里报占用的包名。本 ROM 是 Android 12 的形状：`application: PrivacyApplication`
     * （里面是 packageName + userId）；更新的 AOSP 换成了 `packagesList: List<Pair<String, Int>>`，
     * 两种都认，认不出来就是不画图标位，不影响摄像头 / 麦克风那两颗符号。
     */
    private fun packagesOf(item: Any): List<String> {
        val out = ArrayList<String>(2)
        field(item, "application")?.let { app ->
            (field(app, "packageName") as? String)?.let { if (it.isNotEmpty()) out.add(it) }
        }
        (field(item, "packagesList") as? List<*>)?.forEach { entry ->
            val pkg = (entry as? Pair<*, *>)?.first as? String ?: entry as? String
            if (!pkg.isNullOrEmpty()) out.add(pkg)
        }
        return out
    }

    /** public 字段优先，其次同名 getter：本 ROM 的类被 R8 折叠过，两种都试 */
    private fun field(target: Any, name: String): Any? = try {
        target.javaClass.getField(name).get(target)
    } catch (_: Throwable) {
        try {
            target.javaClass.getMethod("get${name.replaceFirstChar { it.uppercase() }}")
                .invoke(target)
        } catch (_: Throwable) {
            null
        }
    }

    /** 本 ROM 的 PrivacyItem 是 Kotlin 类，类型只有 public 字段，没有 getter */
    private fun privacyTypeOf(item: Any): String? {
        val value = try {
            item.javaClass.getField("privacyType").get(item)
        } catch (_: Throwable) {
            try {
                item.javaClass.getMethod("getPrivacyType").invoke(item)
            } catch (_: Throwable) {
                null
            }
        }
        return (value as? Enum<*>)?.name
    }
}
