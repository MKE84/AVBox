@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package com.github.tvbox.osc.player.ui

import androidx.annotation.DrawableRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.player.state.LockVisibility
import com.github.tvbox.osc.player.state.PlayerActions
import com.github.tvbox.osc.player.state.PlayerUiState

private val PillShape = RoundedCornerShape(50)

@Composable
private fun HintPill(modifier: Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier
            .shadow(4.dp, PillShape)
            .background(Color.Black.copy(alpha = OVERLAY_PILL_ALPHA), PillShape)
            // 垂直内距 vs_5：胶囊高度主要由内容撑(图标盒/文字行高)，内距只补一点呼吸感 ——
            // 胶囊高度与图标盒一起把 80mm 收到 60mm
            .padding(horizontal = playerDim(R.dimen.vs_20), vertical = playerDim(R.dimen.vs_5)),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

@Composable
private fun HintPillLayer(content: @Composable RowScope.() -> Unit) {
    Box(Modifier.fillMaxSize()) {
        HintPill(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .offset(y = playerDim(R.dimen.vs_60)),
            content = content,
        )
    }
}

@Composable
fun PlayerTipLayer(state: PlayerUiState) {
    if (!state.tipVisible) return
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (state.tipLoading) {
                ContainedLoadingIndicator(
                    containerColor = Color.White.copy(alpha = 0.2f),
                    indicatorColor = Color.White.copy(alpha = 0.75f),
                )
            } else {
                Icon(
                    painter = painterResource(R.drawable.icon_error),
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.75f),
                    modifier = Modifier.size(48.dp),
                )
            }
            if (state.tipMsg.isNotEmpty()) {
                Spacer(Modifier.height(12.dp))
                Text(
                    text = state.tipMsg,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.75f),
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp),
                )
            }
        }
    }
}

@Composable
fun PlayerPauseLayer(state: PlayerUiState, actions: PlayerActions) {
    if (!state.pauseOverlayVisible || state.tipVisible) return
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CenterControlIcon(
            icon = painterResource(R.drawable.player_ic_play),
            label = stringResource(R.string.common_play),
            onClick = actions::onPlayPauseClicked,
        )
    }
}

@Composable
fun PlayerSlideHint(state: PlayerUiState) {
    if (!state.slideHintVisible) return
    HintPillLayer {
        Image(
            painter = painterResource(
                if (state.slideHintBrightness) R.drawable.player_ic_brightness
                else R.drawable.player_ic_volume
            ),
            contentDescription = null,
            colorFilter = ColorFilter.tint(Color.White),
            modifier = Modifier.size(playerDim(R.dimen.vs_50)),
        )
        Spacer(Modifier.width(playerDim(R.dimen.vs_20)))
        Text(
            text = state.slideHintText,
            color = Color.White,
            fontSize = playerTextSize(R.dimen.ts_30),
        )
    }
}

@Composable
fun PlayerSeekHint(state: PlayerUiState) {
    if (!state.seekHintVisible) return
    HintPillLayer {
        Image(
            painter = painterResource(
                if (state.seekHintForward) R.drawable.player_ic_params_time_end
                else R.drawable.player_ic_params_time_start
            ),
            contentDescription = null,
            colorFilter = ColorFilter.tint(Color.White),
            modifier = Modifier.size(playerDim(R.dimen.vs_50)),
        )
        Spacer(Modifier.width(playerDim(R.dimen.vs_20)))
        Text(
            text = state.seekHintText,
            color = Color.White,
            fontSize = playerTextSize(R.dimen.ts_30),
        )
    }
}

@Composable
fun PlayerLoadingLayer(state: PlayerUiState) {
    if (!state.loadingVisible) return
    Box(Modifier.fillMaxSize()) {
        CircularProgressIndicator(
            modifier = Modifier
                .align(Alignment.Center)
                .size(playerDim(R.dimen.vs_50)),
            color = Color.White,
        )
        Text(
            text = state.netSpeedTopRight,
            color = Color.White,
            fontSize = playerTextSize(R.dimen.ts_20),
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = playerDim(R.dimen.vs_50) / 2 + playerDim(R.dimen.vs_10)),
        )
    }
}

@Composable
fun PlayerNetSpeedCenter(state: PlayerUiState) {
    if (!state.netSpeedCenterVisible || state.tipVisible) return
    Box(Modifier.fillMaxSize()) {
        Text(
            text = state.netSpeedCenter,
            color = Color.White,
            fontSize = playerTextSize(R.dimen.ts_20),
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = playerDim(R.dimen.vs_40)),
        )
    }
}

@Composable
fun PlayerSideButtons(state: PlayerUiState, actions: PlayerActions, iconBox: Dp) {
    if (state.lockState == LockVisibility.GONE) return
    val shown = state.lockState == LockVisibility.SHOWN
    // 边距跟随 window 分档（竖屏预览 16dp / 横屏全屏与平板 48dp，见 playerEdgePadding）
    val edge = playerEdgePadding()
    val iconSize = iconBox * ICON_TO_BOX_RATIO
    Box(Modifier.fillMaxSize()) {
        SideButton(
            iconRes = R.drawable.ic_player_rotate,
            contentDescription = stringResource(
                if (state.isPortrait) R.string.player_rotate_landscape else R.string.player_rotate_portrait
            ),
            startSide = true,
            edge = edge,
            iconSize = iconSize,
            // 锁定态隐藏（绕锁旋转无意义）
            visible = shown && !state.locked,
            onClick = actions::onRotateClicked,
        )
        SideButton(
            iconRes = if (state.locked) R.drawable.icon_lock else R.drawable.icon_unlock,
            contentDescription = stringResource(R.string.player_lock),
            startSide = false,
            edge = edge,
            iconSize = iconSize,
            visible = shown,
            onClick = actions::onLockClicked,
        )
    }
}

@Composable
private fun BoxScope.SideButton(
    @DrawableRes iconRes: Int,
    contentDescription: String,
    startSide: Boolean,
    edge: Dp,
    iconSize: Dp,
    visible: Boolean,
    onClick: () -> Unit,
) {
    Image(
        painter = painterResource(iconRes),
        contentDescription = null,
        alpha = if (visible) 1f else 0f,
        modifier = Modifier
            .align(if (startSide) Alignment.CenterStart else Alignment.CenterEnd)
            .padding(start = if (startSide) edge else 0.dp, end = if (startSide) 0.dp else edge)
            .size(iconSize)
            .then(
                if (visible) {
                    Modifier
                        .semantics {
                            role = Role.Button
                            this.contentDescription = contentDescription
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(onTap = { onClick() })
                        }
                } else {
                    // 隐藏态：同时移出语义树（不只有 alpha=0，读屏也读不到）
                    Modifier.clearAndSetSemantics {}
                }
            ),
    )
}

@Composable
fun PlayerSpeedBoostHint(state: PlayerUiState) {
    if (!state.speedBoostVisible || state.tipVisible) return
    HintPillLayer {
        Text(
            text = "%.1f X".format(state.speedBoostValue),
            color = Color.White,
            fontSize = playerTextSize(R.dimen.ts_26),
            fontWeight = FontWeight.Bold,
        )
    }
}
