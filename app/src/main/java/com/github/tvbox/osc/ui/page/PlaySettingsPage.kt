package com.github.tvbox.osc.ui.page

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.tvbox.osc.R
import com.github.tvbox.osc.player.PlaybackService
import com.github.tvbox.osc.player.effect.anime4k.Anime4kTier
import com.github.tvbox.osc.ui.components.AVBoxAlertDialog
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.LocalSheetDismiss
import com.github.tvbox.osc.ui.components.LocalSheetDismissThen
import com.github.tvbox.osc.ui.components.SettingsCard
import com.github.tvbox.osc.ui.components.SettingsCardPosition
import com.github.tvbox.osc.ui.components.SettingsGroup
import com.github.tvbox.osc.ui.components.SettingsOptionMenuRow
import com.github.tvbox.osc.ui.components.SettingsRow
import com.github.tvbox.osc.ui.components.SettingsSliderRow
import com.github.tvbox.osc.ui.components.SettingsSwitchRow
import com.github.tvbox.osc.ui.components.TopBarActionBox
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.PlayerHelper
import kotlin.math.roundToInt
import xyz.doikki.videoplayer.player.VideoView

// KV 持久化值(exo_decode),不能翻;显示走 player_decode_* 资源
private const val DecodeHard = "硬解码" // i18n: keep
private const val DecodeSoft = "软解码" // i18n: keep

@Composable
fun PlaySettingsScreen(onNavigateBack: () -> Unit, vm: SettingsViewModel = viewModel()) {
    val state by vm.state
    val context = LocalContext.current
    var showPrewarmWarning by remember { mutableStateOf(false) }
    var sliderPreloadDuration by remember(state.preloadDuration) { mutableStateOf(state.preloadDuration) }
    var sliderSpeed by remember(state.longPressSpeed) { mutableStateOf(state.longPressSpeed) }
    var sliderBuffer by remember(state.bufferTimes) { mutableStateOf(state.bufferTimes) }

    val listState = rememberScrollState()
    AppTopBarScaffold(
        titleContent = {
            Text(
                text = stringResource(R.string.settings_play),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        navigationIcon = {
            TopBarActionBox(R.drawable.ic_arrow_left, stringResource(R.string.common_back), onClick = onNavigateBack)
        },
    ) { topPad, _ ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(listState)
                .padding(horizontal = 16.dp)
                .padding(bottom = 8.dp),
        ) {
            Spacer(Modifier.height(topPad + 8.dp))

            SettingsGroup(title = stringResource(R.string.settings_group_play_picture)) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    val playerTypes = PlayerHelper.getExistPlayerTypes().sortedDescending()
                    SettingsOptionMenuRow(
                        title = stringResource(R.string.settings_play_kernel),
                        leadingIconRes = R.drawable.ic_play_kernel,
                        valueText = PlayerHelper.getPlayerName(state.playType),
                        options = playerTypes.map { PlayerHelper.getPlayerName(it) },
                        selectedIndex = playerTypes.indexOf(state.playType).coerceAtLeast(0),
                        onSelect = { idx -> vm.put(HawkConfig.PLAY_TYPE, playerTypes[idx]) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsOptionMenuRow(
                        title = stringResource(R.string.settings_play_render),
                        leadingIconRes = R.drawable.ic_play_render,
                        valueText = PlayerHelper.getRenderName(state.playRender),
                        options = listOf("SurfaceView", "TextureView"),
                        selectedIndex = 1 - state.playRender,
                        onSelect = { idx ->
                            val render = 1 - idx
                            if (render == 0 && state.playTunnel) vm.put(HawkConfig.PLAY_TUNNEL, false)
                            vm.put(HawkConfig.PLAY_RENDER, render)
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    val scales = listOf(
                        VideoView.SCREEN_SCALE_DEFAULT to stringResource(R.string.common_default),
                        VideoView.SCREEN_SCALE_16_9 to "16:9",
                        VideoView.SCREEN_SCALE_4_3 to "4:3",
                        VideoView.SCREEN_SCALE_MATCH_PARENT to stringResource(R.string.player_scale_fill),
                        VideoView.SCREEN_SCALE_ORIGINAL to stringResource(R.string.player_scale_origin),
                        VideoView.SCREEN_SCALE_CENTER_CROP to stringResource(R.string.player_scale_crop),
                    )
                    SettingsOptionMenuRow(
                        title = stringResource(R.string.settings_play_scale),
                        leadingIconRes = R.drawable.ic_play_scale,
                        valueText = PlayerHelper.getScaleName(state.playScale),
                        options = scales.map { it.second },
                        selectedIndex = scales.indexOfFirst { it.first == state.playScale },
                        onSelect = { idx -> vm.put(HawkConfig.PLAY_SCALE, scales[idx].first) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    // 解码方式:软解 = 系统软件解码器 c2.android.*(仅视频渲染器);
                    // 内核选外部播放器时该设置不生效,行置灰
                    val codec = state.exoDecode
                    val decodeLabels = listOf(
                        stringResource(R.string.player_decode_hard),
                        stringResource(R.string.player_decode_soft),
                    )
                    SettingsOptionMenuRow(
                        title = stringResource(R.string.settings_play_decode),
                        leadingIconRes = R.drawable.ic_play_decode,
                        valueText = when (codec) {
                            DecodeSoft -> decodeLabels[1]
                            DecodeHard -> decodeLabels[0]
                            else -> codec
                        },
                        enabled = state.playType == 2,
                        options = decodeLabels,
                        selectedIndex = if (codec == DecodeSoft) 1 else 0,
                        onSelect = { idx -> vm.put(HawkConfig.EXO_DECODE, if (idx == 1) DecodeSoft else DecodeHard) },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    val tiers = Anime4kTier.entries
                    val labels = tiers.map { stringResource(it.labelRes) }
                    val selected = tiers.indexOf(state.anime4kTier).coerceAtLeast(0)
                    SettingsOptionMenuRow(
                        title = stringResource(R.string.settings_play_anime4k),
                        leadingIconRes = R.drawable.ic_play_anime4k,
                        subtitle = stringResource(R.string.settings_play_anime4k_subtitle),
                        valueText = labels[selected],
                        enabled = state.playType == 2,
                        options = labels,
                        selectedIndex = selected,
                        onSelect = { idx -> vm.put(HawkConfig.ANIME4K_TIER, tiers[idx].name) },
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            SettingsGroup(title = stringResource(R.string.settings_group_play_behavior)) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_play_kernel_prewarm),
                        leadingIconRes = R.drawable.ic_play_prewarm,
                        subtitle = stringResource(R.string.settings_play_kernel_prewarm_subtitle),
                        checked = state.kernelPrewarm,
                        onCheckedChange = { checked ->
                            if (checked) {
                                showPrewarmWarning = true
                            } else {
                                vm.put(HawkConfig.KERNEL_PREWARM, false)
                                PlaybackService.onPrewarmPreferenceChanged(context, false)
                            }
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_play_tunnel),
                        leadingIconRes = R.drawable.ic_play_tunnel,
                        checked = state.playTunnel,
                        onCheckedChange = { checked ->
                            if (checked && state.playRender != 1) vm.put(HawkConfig.PLAY_RENDER, 1)
                            vm.put(HawkConfig.PLAY_TUNNEL, checked)
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_play_prefer_aac),
                        leadingIconRes = R.drawable.ic_play_aac,
                        checked = state.preferAac,
                        onCheckedChange = { vm.put(HawkConfig.PLAY_PREFER_AAC, it) },
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            SettingsGroup(title = stringResource(R.string.settings_group_preload_cache)) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.preload_next_episode),
                        leadingIconRes = R.drawable.ic_preload_next,
                        subtitle = stringResource(R.string.preload_next_episode_subtitle),
                        checked = state.preloadNextEpisode,
                        onCheckedChange = { vm.put(HawkConfig.PRELOAD_NEXT_EPISODE, it) },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    SettingsSliderRow(
                        title = stringResource(R.string.preload_duration),
                        leadingIconRes = R.drawable.ic_preload_duration,
                        value = sliderPreloadDuration.toFloat(),
                        valueText = "${sliderPreloadDuration}s",
                        valueRange = 20f..120f,
                        steps = 9,
                        onValueChange = { sliderPreloadDuration = ((it - 20) / 10).roundToInt() * 10 + 20 },
                        onValueChangeFinished = {
                            if (sliderPreloadDuration != state.preloadDuration) {
                                vm.put(HawkConfig.PRELOAD_DURATION, sliderPreloadDuration)
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            SettingsGroup(title = stringResource(R.string.settings_group_play_search)) {
                SettingsCard(SettingsCardPosition.FIRST) {
                    SettingsSwitchRow(
                        title = stringResource(R.string.settings_auto_switch_line),
                        leadingIconRes = R.drawable.ic_pref_auto_switch_line,
                        checked = state.autoSwitchLine,
                        onCheckedChange = { vm.put(HawkConfig.AUTO_SWITCH_LINE, it) },
                    )
                }
                SettingsCard(SettingsCardPosition.MIDDLE) {
                    SettingsSliderRow(
                        title = stringResource(R.string.settings_long_press_speed),
                        leadingIconRes = R.drawable.ic_pref_long_press_speed,
                        value = sliderSpeed.toFloat(),
                        valueText = "${sliderSpeed}x",
                        valueRange = 2f..10f,
                        steps = 7,
                        onValueChange = { sliderSpeed = (it - 2).roundToInt() + 2 },
                        onValueChangeFinished = {
                            if (sliderSpeed != state.longPressSpeed) {
                                vm.put(HawkConfig.LONG_PRESS_SPEED, sliderSpeed)
                            }
                        },
                    )
                }
                SettingsCard(SettingsCardPosition.LAST) {
                    SettingsSliderRow(
                        title = stringResource(R.string.settings_buffer_time),
                        leadingIconRes = R.drawable.ic_pref_buffer_time,
                        value = sliderBuffer.toFloat(),
                        valueText = "${sliderBuffer}x",
                        valueRange = 1f..10f,
                        steps = 8,
                        onValueChange = { sliderBuffer = (it - 1).roundToInt() + 1 },
                        onValueChangeFinished = {
                            if (sliderBuffer != state.bufferTimes) {
                                vm.put(HawkConfig.BUFFER_TIMES, sliderBuffer)
                            }
                        },
                    )
                }
            }

            Spacer(Modifier.height(64.dp))
        }
    }

    if (showPrewarmWarning) {
        AVBoxAlertDialog(
            onDismissRequest = { showPrewarmWarning = false },
            title = { Text(stringResource(R.string.dialog_kernel_prewarm_title)) },
            text = { Text(stringResource(R.string.dialog_kernel_prewarm_message)) },
            dismissButton = {
                val dismiss = LocalSheetDismiss.current
                TextButton(onClick = { dismiss() }) {
                    Text(stringResource(R.string.dialog_kernel_prewarm_cancel))
                }
            },
            confirmButton = {
                val dismissThen = LocalSheetDismissThen.current
                TextButton(onClick = {
                    dismissThen {
                        vm.put(HawkConfig.KERNEL_PREWARM, true)
                        PlaybackService.onPrewarmPreferenceChanged(context, true)
                        showPrewarmWarning = false
                    }
                }) {
                    Text(stringResource(R.string.dialog_kernel_prewarm_confirm))
                }
            },
        )
    }
}
