package com.lsp102.tapdock.xposed

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.lsp102.tapdock.CardIds
import com.lsp102.tapdock.DockConfigProvider
import com.lsp102.tapdock.SysCatalog
import com.lsp102.tapdock.SysEntry
import com.lsp102.tapdock.SysTilesLocal
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import java.util.concurrent.Executors

/**
 * 系统控制块（QS 磁贴）代理。磁贴对象只存在于 SystemUI 进程，面板却可能挂在桌面进程，
 * 所以引擎侧（SystemUI）建磁贴 / 点击 / 读状态并广播快照，宿主侧只缓存快照、把点击转成广播。
 *
 * 反射名取自这颗 ROM 的 SystemUI dex（LineageOS 23.2 / Android 16 BP4A）。
 */
object SystemTiles {

    const val ACTION_CLICK = "com.lsp102.tapdock.SYS_CLICK"
    const val ACTION_REFRESH = "com.lsp102.tapdock.SYS_REFRESH"
    const val ACTION_SNAPSHOT = "com.lsp102.tapdock.SYS_SNAPSHOT"
    const val EXTRA_SPEC = "spec"
    const val EXTRA_LONG = "long_click"

    private const val FACTORY_CLS = "com.android.systemui.qs.tileimpl.QSFactoryImpl"
    private const val CATALOG_URI = "content://com.lsp102.tapdock.config/state"
    private const val STATE_ON = 0
    private const val STATE_UNAVAILABLE = 2

    /** 目录取标签时，等新造的磁贴异步初始化好的时间（毫秒） */
    private const val SETTLE_MS = 80L

    /** 一个磁贴的可显示信息；宿主侧靠它画卡片 */
    data class Snapshot(
        val spec: String,
        val label: String,
        val sub: String,
        val on: Boolean,
        val available: Boolean,
    )

    @Volatile private var factory: Any? = null

    /** 只有 SystemUI 进程是引擎：能建磁贴、能执行点击 */
    @Volatile private var engine = false
    private var main: Handler? = null
    private var appCtx: Context? = null
    private val worker = Executors.newSingleThreadExecutor()

    /** [call] 的“无参”哨兵：null 本身是合法实参（click 要传空 origin） */
    private val NONE = Any()

    /** spec -> 面板正在使用的磁贴对象 */
    private val live = LinkedHashMap<String, Any>()

    /** spec -> 最近一次快照，引擎和宿主共用同一份读接口 */
    private val snapshots = LinkedHashMap<String, Snapshot>()

    /**
     * 每个宿主进程都调一次。isEngine = 当前进程是 SystemUI，只有它能碰磁贴本体。
     */
    fun attach(context: Context, isEngine: Boolean) {
        appCtx = context.applicationContext
        main = Handler(Looper.getMainLooper())
        engine = isEngine
        if (isEngine) hookFactory(context.classLoader)
    }

    /** 抓 QSFactoryImpl 的构造：不自己 new，它要 Dagger 注入的 QSHost / CustomTileFactory */
    private fun hookFactory(loader: ClassLoader) {
        try {
            val cls = XposedHelpers.findClass(FACTORY_CLS, loader)
            XposedBridge.hookAllConstructors(cls, object : XC_MethodHook() {
                override fun afterHookedMethod(param: XC_MethodHook.MethodHookParam) {
                    factory = param.thisObject
                    XposedBridge.log("[TapDock] QS factory captured")
                }
            })
        } catch (t: Throwable) {
            XposedBridge.log("[TapDock] factory hook failed: $t")
        }
    }

    /** 按名字找公开方法再调用：不同安卓版本参数类型会变，名字比签名稳 */
    private fun call(obj: Any, name: String, arg: Any? = null): Any? = try {
        val m = obj.javaClass.methods.firstOrNull {
            it.name == name &&
                if (arg === NONE) it.parameterCount == 0 else it.parameterCount == 1
        } ?: return null
        if (arg === NONE) m.invoke(obj) else m.invoke(obj, arg)
    } catch (t: Throwable) {
        XposedBridge.log("[TapDock] $name() failed: $t")
        null
    }

    private fun field(obj: Any, name: String): Any? = try {
        obj.javaClass.getField(name).get(obj)
    } catch (_: Throwable) {
        null
    }

    /** QSFactoryImpl.createTile(String)：内置 spec 走 mTileMap，带 '/' 的组件 spec 走 CustomTileFactory */
    private fun createTile(spec: String): Any? = try {
        val f = factory ?: return null
        f.javaClass.methods.firstOrNull {
            it.name == "createTile" && it.parameterCount == 1 && it.parameterTypes[0] == String::class.java
        }?.invoke(f, spec)
    } catch (t: Throwable) {
        XposedBridge.log("[TapDock] createTile($spec) failed: $t")
        null
    }

    /** 面板在用的磁贴只建一次，之后反复读状态 */
    private fun tileFor(spec: String): Any? {
        live[spec]?.let { return it }
        val tile = createTile(spec) ?: return null
        live[spec] = tile
        call(tile, "refreshState")
        return tile
    }

    /** QSTile.getState() -> QSTile$State 的几个公开字段 */
    private fun readSnapshot(tile: Any, spec: String): Snapshot? {
        val state = call(tile, "getState") ?: return null
        val text = field(state, "label") as? CharSequence
        val label = (text?.toString().orEmpty()).ifEmpty {
            (call(tile, "getTileLabel") as? CharSequence)?.toString().orEmpty()
        }.ifEmpty { spec }
        val sub = ((field(state, "stateDescription") as? CharSequence)?.toString()
            ?: (field(state, "secondaryLabel") as? CharSequence)?.toString()).orEmpty()
        val raw = field(state, "state") as? Int
        val on = (field(state, "value") as? Boolean) ?: (raw == STATE_ON)
        return Snapshot(spec, label, sub, on, raw != STATE_UNAVAILABLE)
    }

    /** mTileMap 的键集就是这颗 ROM 全部内置磁贴 spec，不用自己抄一份表 */
    private fun stockSpecs(): List<String> = try {
        val f = factory ?: return emptyList()
        val fld = f.javaClass.declaredFields.firstOrNull { it.name == "mTileMap" } ?: return emptyList()
        fld.isAccessible = true
        (fld.get(f) as? Map<*, *>)?.keys?.filterIsInstance<String>()?.sorted() ?: emptyList()
    } catch (t: Throwable) {
        XposedBridge.log("[TapDock] stock specs failed: $t")
        emptyList()
    }

    /** 宿主侧读一张系统卡片要显示的内容；没快照时给占位，卡片仍然可点 */
    fun snapshot(id: String): Snapshot {
        val spec = CardIds.specOf(id)
        return snapshots[spec] ?: Snapshot(spec, spec, "", false, true)
    }

    /** 面板点击 / 长按系统卡片：宿主发广播，引擎就地执行 */
    fun requestClick(context: Context, id: String, long: Boolean) {
        val spec = CardIds.specOf(id)
        if (!engine) {
            try {
                context.sendBroadcast(
                    Intent(ACTION_CLICK)
                        .setPackage("com.android.systemui")
                        .putExtra(EXTRA_SPEC, spec)
                        .putExtra(EXTRA_LONG, long)
                )
            } catch (t: Throwable) {
                XposedBridge.log("[TapDock] click relay failed: $t")
            }
        } else {
            onClick(spec, long)
        }
    }

    /** HookDock 的接收器把三种广播转进来 */
    fun handleIntent(intent: Intent, context: Context) {
        when (intent.action) {
            ACTION_CLICK -> if (engine) {
                intent.getStringExtra(EXTRA_SPEC)
                    ?.let { onClick(it, intent.getBooleanExtra(EXTRA_LONG, false)) }
            }

            ACTION_REFRESH -> if (engine) harvestCatalog()

            ACTION_SNAPSHOT -> {
                absorb(intent)
                main?.post {
                    HookDock.applyContent()
                    ControlPanel.refreshCards()
                }
            }
        }
    }

    /** 引擎：点完立刻回读状态，别让开关等下一秒的 tick */
    private fun onClick(spec: String, long: Boolean) {
        worker.execute {
            val tile = tileFor(spec) ?: return@execute
            call(tile, if (long) "longClick" else "click", null)
            if (!long) call(tile, "refreshState")
            val snap = readSnapshot(tile, spec)
            main?.post {
                if (snap != null) snapshots[spec] = snap
                publishAll()
            }
        }
    }

    /** 引擎每 tick 刷一遍面板在用的磁贴，并把快照广播给两个宿主进程 */
    fun pushStates(usedIds: List<String>) {
        if (!engine) return
        val specs = usedIds.filter { CardIds.isSys(it) }.map { CardIds.specOf(it) }
        if (specs.isEmpty()) return
        worker.execute {
            val fresh = LinkedHashMap<String, Snapshot>()
            for (spec in specs) {
                val tile = tileFor(spec) ?: continue
                call(tile, "refreshState")
                readSnapshot(tile, spec)?.let { fresh[spec] = it }
            }
            if (fresh.isEmpty()) return@execute
            main?.post {
                val changed = fresh.any { (spec, snap) -> snapshots[spec] != snap }
                snapshots.putAll(fresh)
                if (changed) publishAll()
            }
        }
    }

    /** 引擎把整份快照广播给两个宿主进程；布尔用 "1"/"0" 混在 String[] 里，省掉自造分隔符 */
    private fun publishAll() {
        val ctx = appCtx ?: return
        val list = snapshots.values.toList()
        if (list.isEmpty()) return
        val specs = list.map { it.spec }.toTypedArray()
        val labels = list.map { it.label }.toTypedArray()
        val subs = list.map { it.sub }.toTypedArray()
        val ons = list.map { if (it.on) "1" else "0" }.toTypedArray()
        val avails = list.map { if (it.available) "1" else "0" }.toTypedArray()
        for (pkg in HOSTS) {
            try {
                ctx.sendBroadcast(
                    Intent(ACTION_SNAPSHOT)
                        .setPackage(pkg)
                        .putExtra(KEY_SPECS, specs)
                        .putExtra(KEY_LABELS, labels)
                        .putExtra(KEY_SUBS, subs)
                        .putExtra(KEY_ONS, ons)
                        .putExtra(KEY_AVAILS, avails)
                )
            } catch (t: Throwable) {
                XposedBridge.log("[TapDock] snapshot broadcast to $pkg failed: $t")
            }
        }
    }

    private fun absorb(intent: Intent) {
        val specs = intent.getStringArrayExtra(KEY_SPECS) ?: return
        val labels = intent.getStringArrayExtra(KEY_LABELS) ?: return
        val subs = intent.getStringArrayExtra(KEY_SUBS) ?: return
        val ons = intent.getStringArrayExtra(KEY_ONS) ?: return
        val avails = intent.getStringArrayExtra(KEY_AVAILS) ?: return
        for (i in specs.indices) {
            if (i >= labels.size) break
            snapshots[specs[i]] = Snapshot(
                specs[i], labels[i], subs.getOrElse(i) { "" },
                ons.getOrElse(i) { "0" } == "1", avails.getOrElse(i) { "1" } == "1",
            )
        }
    }

    /** 引擎：枚举内置 spec + 第三方 TileService，逐个建磁贴取标签，再写回 App 的 Provider */
    private fun harvestCatalog() {
        worker.execute {
            val ctx = appCtx ?: return@execute
            val entries = ArrayList<SysEntry>()
            for (spec in stockSpecs()) {
                val tile = createTile(spec) ?: continue
                call(tile, "refreshState")
                try {
                    Thread.sleep(SETTLE_MS)
                } catch (_: InterruptedException) {
                }
                entries.add(SysEntry(spec, readSnapshot(tile, spec)?.label ?: spec, SysCatalog.KIND_STOCK))
                call(tile, "destroy")
            }
            entries.addAll(SysTilesLocal.customEntries(ctx.packageManager))
            try {
                val values = ContentValues()
                values.put(DockConfigProvider.COLUMN_CATALOG, SysCatalog.encode(entries))
                ctx.contentResolver.insert(Uri.parse(CATALOG_URI), values)
                XposedBridge.log("[TapDock] catalog pushed: ${entries.size}")
            } catch (t: Throwable) {
                XposedBridge.log("[TapDock] catalog write failed: $t")
            }
        }
    }

    private const val KEY_SPECS = "specs"
    private const val KEY_LABELS = "labels"
    private const val KEY_SUBS = "subs"
    private const val KEY_ONS = "ons"
    private const val KEY_AVAILS = "avails"

    private val HOSTS = listOf(
        "com.android.systemui",
        "com.android.launcher3",
        "com.google.android.apps.nexuslauncher",
    )
}
