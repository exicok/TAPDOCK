package com.lsp102.tapdock.xposed

import de.robv.android.xposed.XposedBridge

/** 游戏实际用的图形接口；OTHER 留给以后真认出来的第四条路（比如自研 GL 后端） */
enum class GraphicsApiType { VULKAN, OPENGL_ES, ANGLE, OTHER, UNKNOWN }

/** 结论的"底气"从哪一档来：只有 SESSION_CONFIRMED 才代表当前在用 */
enum class ApiDetectionStatus {
    /** 当前运行会话里实测到（进程映射里有对应的库本体） */
    SESSION_CONFIRMED,

    /** 采到了但认不出来，或者根本没数据 */
    UNDETERMINED,
}

/** 结论出自哪条路 */
enum class ApiDetectionSource {
    /** root 读 /proc/<pid>/maps 认库本体（见 GameInfoCollector） */
    RUNTIME_PROCESS_MAPS,

    NONE,
}

/** 厂商 Vulkan 驱动 / ANGLE 本体命中 = HIGH；只有 GLES 加载桩 = MEDIUM（它是推断，不是证据） */
enum class ApiConfidence { HIGH, MEDIUM, NONE }

/**
 * 统一数据对象：检测模块只往外发这个，Dock 和面板各自决定怎么画。
 *
 * [raw] 是采集侧的原始写法（如 `VK(软件)`）：apiType 为了图标把它归成 Vulkan，
 * 但详情行还得把"其实跑的是 swiftshader"这层意思留住。
 */
data class GraphicsApiInfo(
    val packageName: String,
    val processId: Int,
    val apiType: GraphicsApiType,
    val status: ApiDetectionStatus,
    val source: ApiDetectionSource,
    val confidence: ApiConfidence,
    val lastDetectedTime: Long,
    val raw: String,
) {
    val label: String
        get() = when {
            apiType == GraphicsApiType.VULKAN && raw.contains("软件") -> "Vulkan（软件渲染）"
            apiType == GraphicsApiType.VULKAN -> "Vulkan"
            apiType == GraphicsApiType.OPENGL_ES -> "OpenGL ES"
            apiType == GraphicsApiType.ANGLE -> "ANGLE"
            apiType == GraphicsApiType.OTHER -> "其他"
            else -> "未知"
        }
}

/** 检测器的对外形状：Dock 只认这三个动作，不知道数据是怎么采上来的 */
interface IGraphicsApiDetector {
    /** 当前前台游戏的 API；没在跑 / 已经过期 / 模块被关掉都返回 null */
    fun currentGraphicsApi(): GraphicsApiInfo?

    /** 喂一帧采集结果进来（不阻塞、不做 IO，绘制路径上随时可问） */
    fun observeGraphicsApi(frame: String)

    /** 停用：丢掉当前会话状态 */
    fun stopObserving()
}

/**
 * 图形 API 状态源。
 *
 * 它自己不采任何东西：真正的识别在模块 App 里用 root 读 `/proc/<pid>/maps`（见 GameInfoCollector），
 * 结果随 DockConfigProvider 的 query 每 3 秒回来一次。本对象只做解析和状态划分，
 * 所以能在设置里整块停用，也不和 Dock 的视图代码发生关系。
 *
 * 一条硬规矩：过期即失效。一帧超过 [SESSION_MS] 没更新就说明进程没了/被杀了/手机断开了，
 * 这一局的结论直接作废，不会有"上次 …"那种残留。
 */
object GraphicsApiSource : IGraphicsApiDetector {

    /** 和 GameInfoSource 收起那一行用的是同一条时间线：25 秒内没新帧就是这一局结束了 */
    private const val SESSION_MS = 25_000L

    @Volatile
    private var session: GraphicsApiInfo? = null

    /**
     * 检测模块总开关（App 里那颗"图形 API 标识"驱动它）。
     *
     * 关掉时调用方紧接着还要调一次 [stopObserving]：本对象只存引用，不做副作用，
     * 免得一个属性写入悄悄清了状态。
     */
    @Volatile
    var enabled: Boolean = true

    override fun currentGraphicsApi(): GraphicsApiInfo? = if (enabled) session else null

    override fun stopObserving() {
        if (session != null) XposedBridge.log("[TapDock] graphics api: 停用，清除当前会话")
        session = null
    }

    override fun observeGraphicsApi(frame: String) {
        if (!enabled) {
            session = null
            return
        }
        val fresh = parseFrame(frame)
        val old = session
        // 包名一样但进程号变了 = 重启过的一局：上一局的结论不算这一局的，日志留一行给排查用
        if (fresh != null && old != null && old.packageName == fresh.packageName &&
            old.processId > 0 && fresh.processId > 0 && old.processId != fresh.processId
        ) {
            XposedBridge.log("[TapDock] graphics api: ${fresh.packageName} 换了进程，重新确认")
        }
        session = fresh
    }

    /** 采集侧的 api 字段 → 类型 + 置信；认不出来一律 UNKNOWN，不猜 */
    private fun classify(raw: String): Pair<GraphicsApiType, ApiConfidence> = when (raw) {
        "ANGLE" -> GraphicsApiType.ANGLE to ApiConfidence.HIGH
        "VULKAN" -> GraphicsApiType.VULKAN to ApiConfidence.HIGH
        "VK(软件)" -> GraphicsApiType.VULKAN to ApiConfidence.HIGH
        "GLES" -> GraphicsApiType.OPENGL_ES to ApiConfidence.MEDIUM
        else -> GraphicsApiType.UNKNOWN to ApiConfidence.NONE
    }

    /** `pkg|api|宽x高|帧率|时间戳|进程号`；太旧或形状不对就没有当前会话 */
    private fun parseFrame(raw: String): GraphicsApiInfo? {
        if (raw.isBlank()) return null
        val p = raw.split('|')
        if (p.size < 5) return null
        val at = p[4].toLongOrNull() ?: return null
        if (System.currentTimeMillis() - at > SESSION_MS) return null
        val (type, confidence) = classify(p[1])
        return GraphicsApiInfo(
            packageName = p[0],
            processId = p.getOrNull(5)?.toIntOrNull() ?: 0,
            apiType = type,
            status = if (type == GraphicsApiType.UNKNOWN) {
                ApiDetectionStatus.UNDETERMINED
            } else {
                ApiDetectionStatus.SESSION_CONFIRMED
            },
            source = if (type == GraphicsApiType.UNKNOWN) {
                ApiDetectionSource.NONE
            } else {
                ApiDetectionSource.RUNTIME_PROCESS_MAPS
            },
            confidence = confidence,
            lastDetectedTime = at,
            raw = p[1],
        )
    }
}
