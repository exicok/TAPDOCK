package com.lsp102.tapdock

/**
 * 卡片 id 的两种来源：模块自己实现的开关（[CardSpec] 的 id）和转发给系统的代理卡片
 * （`sys:<spec>`，spec 是 SystemUI 那边的磁贴 spec，第三方 TileService 用打平的组件名）。
 */
object CardIds {

    const val SYS_PREFIX = "sys:"

    fun sys(spec: String) = SYS_PREFIX + spec

    fun isSys(id: String) = id.startsWith(SYS_PREFIX)

    /** sys: 后面那段：内置磁贴是短名（dnd / rotation…），第三方是 com.foo/.BarTile */
    fun specOf(id: String) = id.removePrefix(SYS_PREFIX)
}

/**
 * SystemUI 枚举出来、再写回 App 的一个系统控制块。
 * spec 是执行时用的身份，label 只用于目录显示。
 */
data class SysEntry(
    val spec: String,
    val label: String,
    /** [SysCatalog.KIND_STOCK] / [SysCatalog.KIND_CUSTOM] / [SysCatalog.KIND_CONTROL] */
    val kind: Int,
) {
    val id: String get() = CardIds.sys(spec)
}

/**
 * 目录只有 SystemUI 能枚举（磁贴本体、TileService 绑定、Controls  provider 都归它管），
 * 它通过 DockConfigProvider.insert 把整份目录写回 App；App 勾选后以 `sys:<spec>`
 * 追加进 card_order，走原有的三级通道回传给面板。
 *
 * 存储格式：每条记录 `spec<TAB>label<TAB>kind`，记录之间换行。spec 和 label 里都不该有
 * 制表符 / 换行，出现就丢弃那条，不至于把整份目录解析歪。
 */
object SysCatalog {

    const val KIND_STOCK = 1
    const val KIND_CUSTOM = 2
    const val KIND_CONTROL = 3

    fun kindLabel(kind: Int): String = when (kind) {
        KIND_STOCK -> "系统磁贴"
        KIND_CUSTOM -> "第三方磁贴"
        KIND_CONTROL -> "设备控制"
        else -> "其它"
    }

    fun encode(entries: List<SysEntry>): String =
        entries.joinToString("\n") { "${it.spec}\t${it.label}\t${it.kind}" }

    fun decode(raw: String): List<SysEntry> =
        raw.split('\n').mapNotNull { line ->
            val parts = line.split('\t')
            if (parts.size != 3) return@mapNotNull null
            val kind = parts[2].toIntOrNull() ?: return@mapNotNull null
            if (parts[0].isEmpty()) return@mapNotNull null
            SysEntry(parts[0], parts[1], kind)
        }
}
