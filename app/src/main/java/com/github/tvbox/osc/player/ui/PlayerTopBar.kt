package com.github.tvbox.osc.player.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.github.tvbox.osc.R
import com.github.tvbox.osc.player.state.PlayerActions
import com.github.tvbox.osc.player.state.PlayerUiState

private val TopBarLineHeight = 36.dp

private val PreviewTopPadding = 4.dp

@Composable
fun PlayerTopBar(state: PlayerUiState, actions: PlayerActions) {
    val rightVisible = state.topRightVisible && !state.previewMode
    val previewSizeVisible = state.previewMode && state.topLeftVisible
    val anyVisible = state.topLeftVisible || rightVisible
    // 左右边距按窗口宽度分档（竖屏预览 16dp / 横屏全屏与平板 48dp，见 playerEdgePadding）
    val edge = playerEdgePadding()
    val topPad = if (state.previewMode) PreviewTopPadding else 12.dp
    // 顶栏贴顶时补上未被自身覆盖的安全区差值（不贴顶/已被上层 padding 抬下去时为 0）。宽档（≥600dp：横屏
    // 全屏/平板）只取挖孔：safeDrawing 含状态栏，而系统栏在进应用/旋转/回前台会被短暂放出且带显隐动画，跟着它顶栏会弹一下
    val density = LocalDensity.current
    val topInset = if (LocalConfiguration.current.screenWidthDp >= 600) {
        WindowInsets.displayCutout
    } else {
        WindowInsets.safeDrawing
    }
    val topInsetPx = topInset.getTop(density)
    var barTopPx by remember { mutableStateOf(Float.NaN) }
    val extraTop = if (barTopPx.isNaN()) {
        0.dp
    } else {
        with(density) { (topInsetPx - barTopPx).coerceAtLeast(0f).toDp() }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .onGloballyPositioned { barTopPx = it.positionInWindow().y }
    ) {
        if (anyVisible) {
            // scrim 渐变（黑 55% → 透明），替代旧实现"无背景白字压画面"
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Black.copy(alpha = 0.55f), Color.Transparent)
                        )
                    )
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(
                    start = edge,
                    end = edge,
                    top = topPad + extraTop,
                    bottom = playerDim(R.dimen.vs_5),
                )
        ) {
                    // —— 左块：返回箭头 + 片名 ——
            if (state.topLeftVisible) {
                Row(Modifier.weight(3f), verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier
                            .size(TopBarLineHeight)
                            .semantics {
                                role = Role.Button
                                contentDescription = stringResource(R.string.common_back)
                            }
                            .pointerInput(Unit) {
                                detectTapGestures(onTap = { actions.onBackClicked() })
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Image(
                            painter = painterResource(R.drawable.player_ic_back),
                            contentDescription = null,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Text(
                        text = state.title,
                        color = Color.White,
                        fontSize = playerTextSize(R.dimen.ts_24),
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(
                            start = playerDim(R.dimen.vs_10),
                            top = playerDim(R.dimen.vs_5),
                        )
                    )
                }
            } else {
                Spacer(Modifier.weight(3f))
            }
            // —— 右块：网速/进度时间/系统时间 ——
            if (rightVisible) {
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.End) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.height(TopBarLineHeight),
                    ) {
                        if (state.sysTimeVisible && state.batteryPercent in 0..100) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                // 时间文字自带 top=vs_5，此处不补会与时间错位
                                modifier = Modifier.padding(
                                    end = playerDim(R.dimen.vs_10),
                                    top = playerDim(R.dimen.vs_5),
                                ),
                            ) {
                                Text(
                                    text = "${state.batteryPercent}%",
                                    color = Color.White,
                                    fontSize = playerTextSize(R.dimen.ts_20),
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(end = 4.dp),
                                )
                                Image(
                                    painter = painterResource(batteryIcon(state)),
                                    contentDescription = null,
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                        if (state.sysTimeVisible) {
                            TopBarText(state.sysTime)
                        }
                    }
                    if (state.netSpeedTopRightVisible) {
                        TopBarText(state.netSpeedTopRight)
                    }
                }
            } else if (previewSizeVisible) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.height(TopBarLineHeight),
                ) {
                    TopBarText(state.videoSize)
                }
            } else {
                Spacer(Modifier.weight(1f))
            }
        }
    }
}

/** 电池图标档位：充电/充满 → 闪电帧；否则按百分比映射 1~6 档（每档约 16.7%） */
private fun batteryIcon(state: PlayerUiState): Int = when {
    state.batteryCharging -> R.drawable.ic_battery_charging
    state.batteryPercent <= 16 -> R.drawable.ic_battery_1
    state.batteryPercent <= 33 -> R.drawable.ic_battery_2
    state.batteryPercent <= 50 -> R.drawable.ic_battery_3
    state.batteryPercent <= 66 -> R.drawable.ic_battery_4
    state.batteryPercent <= 83 -> R.drawable.ic_battery_5
    else -> R.drawable.ic_battery_6
}

@Composable
private fun TopBarText(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontSize = playerTextSize(R.dimen.ts_20),
        fontWeight = FontWeight.Medium,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(
            end = playerDim(R.dimen.vs_10),
            top = playerDim(R.dimen.vs_5),
        )
    )
}
