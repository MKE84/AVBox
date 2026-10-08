package com.github.tvbox.osc.util

import com.github.tvbox.osc.bean.SourceBean

/** 源爬虫种类。py/js 看 api 后缀(.py/.js 及 ?# 变体),否则看 jar 是否非空;API = 普通 json/xml 接口。 */
enum class SourceKind { PY, JS, JAR, API }

/** 源类型判定的唯一事实源。项目内统一从这里推断,避免各页面重复写同一条 when 规则。 */
object SourceType {

    /** 按 SourceBean 推断爬虫种类(利用 Java getter 的 safeString,api/jar 不会为 null) */
    @JvmStatic
    fun of(sb: SourceBean): SourceKind = of(sb.api.orEmpty(), sb.jar.orEmpty())

    /** 按 api 地址 + jar 推断(兼容以 String 传参的调用点,容忍 null) */
    @JvmStatic
    fun of(api: String?, jar: String?): SourceKind {
        val lower = (api ?: "").lowercase()
        return when {
            lower.endsWith(".py") || lower.contains(".py?") || lower.contains(".py#") -> SourceKind.PY
            lower.endsWith(".js") || lower.contains(".js?") || lower.contains(".js#") -> SourceKind.JS
            !jar.isNullOrEmpty() -> SourceKind.JAR
            else -> SourceKind.API
        }
    }
}