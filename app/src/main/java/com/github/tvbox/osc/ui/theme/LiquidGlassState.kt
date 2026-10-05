package com.github.tvbox.osc.ui.theme

/**
 * 悬浮导航栏外观固定值(原「应用效果」可配置项已移除)。
 *
 * 保留悬浮胶囊形态,但关闭模糊/扭曲/色散 —— 即"固定的底部悬浮选择卡":
 * 实色不透明卡片 + 滑动选中动画,不再提供用户可调参数。
 */
object LiquidGlassState {

    private val Fixed = LiquidGlassConfig(
        navbarEnabled = true,   // 保留悬浮胶囊形态
        controlsEnabled = true,
        blurDp = 0f,            // 不模糊 → 实色卡片
        distortionDp = 0f,      // 不扭曲
        translucency = 0f,      // 完全不通透 → 不透明底色
        dispersion = false,     // 无色散
    )

    val config: LiquidGlassConfig get() = Fixed
}
