package com.github.tvbox.osc.util

import android.app.Application
import android.content.Context

/**
 * 语言固定简体中文,所有入口已移除。
 */
object LanguageManager {

    /** 是否繁体中文(已禁用,固定 false) */
    fun isTraditional(): Boolean = false

    /** 简体中文下不做任何改造,原样返回上下文 */
    fun wrap(context: Context): Context = context

    fun localized(context: Context): Context = context

    fun resolve(context: Context) {}
}
