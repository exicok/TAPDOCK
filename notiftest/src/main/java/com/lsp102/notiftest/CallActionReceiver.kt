package com.lsp102.notiftest

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

/** 模拟来电的接听 / 挂断动作：撤掉测试通知，Dock 横条跟着退出 */
class CallActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra(EXTRA_ID, -1)
        val action = intent.getStringExtra(EXTRA_ACTION) ?: return
        if (id > 0) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.cancel(id)
        }
        Toast.makeText(
            context,
            if (action == "answer") "模拟接听 #$id" else "模拟挂断 #$id",
            Toast.LENGTH_SHORT,
        ).show()
    }

    companion object {
        const val EXTRA_ID = "call_id"
        const val EXTRA_ACTION = "call_action"
    }
}
