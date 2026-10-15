package com.lsp102.tapdock

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity

/**
 * 引用库页：这个 APK 的直接依赖（Gradle 声明的那几项）和各自的许可证。
 *
 * 版本抄自 app/build.gradle 与 `:app:dependencies --configuration debugRuntimeClasspath` 的解析结果
 * ——kotlin-stdlib 没在 build.gradle 里写死，是 Kotlin 插件带进来的，升级 Kotlin 时这一行要跟着改。
 *
 * 六项都是 Apache License 2.0，所以正文只存一份字符串；真引入别的许可证再按 [Lib.license] 分派。
 */
class LibrariesActivity : AppCompatActivity() {

    private data class Lib(
        val name: String,
        val coord: String,
        val version: String,
        val author: String,
        val license: String,
        /** true = 随 APK 分发；false = 只编译期用，运行时由 LSPosed 注入 */
        val shipped: Boolean,
        val url: String,
    )

    private val libs = listOf(
        Lib(
            "AndroidX Core KTX", "androidx.core:core-ktx", "1.13.1",
            "Android Open Source Project", APACHE, true,
            "https://developer.android.com/jetpack/androidx/releases/core",
        ),
        Lib(
            "AndroidX AppCompat", "androidx.appcompat:appcompat", "1.7.0",
            "Android Open Source Project", APACHE, true,
            "https://developer.android.com/jetpack/androidx/releases/appcompat",
        ),
        Lib(
            "Material Components for Android", "com.google.android.material:material", "1.12.0",
            "Google", APACHE, true,
            "https://github.com/material-components/material-components-android",
        ),
        Lib(
            "AndroidX RecyclerView", "androidx.recyclerview:recyclerview", "1.3.2",
            "Android Open Source Project", APACHE, true,
            "https://developer.android.com/jetpack/androidx/releases/recyclerview",
        ),
        Lib(
            "Kotlin Standard Library", "org.jetbrains.kotlin:kotlin-stdlib", "2.0.21",
            "JetBrains", APACHE, true,
            "https://github.com/JetBrains/kotlin",
        ),
        Lib(
            "Xposed Framework API", "de.robv.android.xposed:api", "82",
            "rovo89", APACHE, false,
            "https://github.com/rovo89/XposedBridge/wiki/Using-the-Xposed-Framework-API",
        ),
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_libraries)
        val content = findViewById<LinearLayout>(R.id.ll_content)
        libs.forEach { content.addView(rowFor(it, content)) }
    }

    /** 行复用设置页那套 item_setting_row：标题 = 库名，副标题 = 许可证 · 作者，尾值 = 版本 */
    private fun rowFor(lib: Lib, parent: LinearLayout): View {
        val v = layoutInflater.inflate(R.layout.item_setting_row, parent, false)
        v.findViewById<ImageView>(R.id.iv_icon).setImageResource(R.drawable.ic_lib)
        v.findViewById<TextView>(R.id.tv_title).text = lib.name
        val sub = getString(
            if (lib.shipped) R.string.lib_sub_fmt else R.string.lib_sub_compileonly_fmt,
            lib.license,
            lib.author,
        )
        v.findViewById<TextView>(R.id.tv_sub).text = sub
        v.findViewById<TextView>(R.id.tv_trail).apply {
            visibility = View.VISIBLE
            text = lib.version
        }
        v.setOnClickListener { showLicense(lib) }
        return v
    }

    private fun showLicense(lib: Lib) {
        val body = getString(R.string.license_apache_body) + "\n\n" +
            getString(R.string.lib_dialog_meta, lib.name, lib.coord, lib.version, lib.url)
        AlertDialog.Builder(this)
            .setTitle(R.string.lib_dialog_title)
            .setMessage(body)
            .setPositiveButton(R.string.lib_open_source) { _, _ -> openUrl(lib.url) }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun openUrl(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, R.string.lib_no_browser, Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val APACHE = "Apache License 2.0"
    }
}
