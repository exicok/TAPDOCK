package com.lsp102.tapdock

import android.app.Application
import com.google.android.material.color.DynamicColors

class TapDockApp : Application() {

    override fun onCreate() {
        super.onCreate()
        // 跟随系统主题色（Material You，Android 12+ 生效）
        DynamicColors.applyToActivitiesIfAvailable(this)
    }
}
