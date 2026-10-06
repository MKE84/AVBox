package com.github.tvbox.osc.ui.activity

import android.content.Context
import android.content.Intent
import androidx.compose.ui.platform.ComposeView
import com.github.tvbox.osc.R
import com.github.tvbox.osc.base.BaseActivity
import com.github.tvbox.osc.ui.components.SheetHostScaffold
import com.github.tvbox.osc.ui.page.ApiLogScreen
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.github.tvbox.osc.ui.theme.enableTransparentEdgeToEdge

/** 接口日志查看页(py/js/jar/普通接口的调用记录) */
class ApiLogActivity : BaseActivity() {

    companion object {
        fun start(context: Context) {
            context.startActivity(Intent(context, ApiLogActivity::class.java))
        }
    }

    override fun getLayoutResID(): Int = R.layout.activity_main

    override fun shouldRefreshAutoSize(): Boolean = true

    override fun hideSysBar() {
    }

    override fun init() {
        enableTransparentEdgeToEdge()
        findViewById<ComposeView>(R.id.compose_view).setContent {
            AVBoxTheme {
                SheetHostScaffold {
                    ApiLogScreen(onNavigateBack = { finish() })
                }
            }
        }
    }
}