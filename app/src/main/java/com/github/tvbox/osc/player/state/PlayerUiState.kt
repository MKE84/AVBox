package com.github.tvbox.osc.player.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.dlna.CastVideo
import com.github.tvbox.osc.player.effect.PictureEffectUnavailableReason
import com.github.tvbox.osc.player.effect.PicturePreset
import com.github.tvbox.osc.player.effect.PictureProfile
import xyz.doikki.videoplayer.player.VideoView

/**
 * 播放器控制层集中状态容器。
 *
 * dkplayer 侧只读状态由事件桥写入；控制层自身 UI 状态由 ComposeVideoController 的
 * 意图方法写入。Compose 通过 mutableStateOf 直接观察，替代旧实现中的
 * Handler(msg 100/1000-1004) + myHandle 两套异步与 30+ 个分散字段。
 */
class PlayerUiState {

    // —— 来自 dkplayer（只读，事件桥写入） ——
    var playState: Int by mutableStateOf(VideoView.STATE_IDLE)
    var playerState: Int by mutableStateOf(VideoView.PLAYER_NORMAL)
    var duration: Int by mutableStateOf(0)
    var position: Int by mutableStateOf(0)
    var bufferedPercent: Int by mutableStateOf(0)
    var locked: Boolean by mutableStateOf(false)
    /** dkplayer 侧 show/hide（锁定状态下不下发） */
    var showing: Boolean by mutableStateOf(false)

    // —— 控制层自身 UI 状态 ——

    /** 底部菜单可见（替代 mBottomRoot 显隐 + msg 1002/1003） */
    var controlsVisible: Boolean by mutableStateOf(false)
    /** SeekBar 拖拽/按键步进中（替代 mIsDragging，拖拽中不回写进度） */
    var dragging: Boolean by mutableStateOf(false)
    /** 拖拽预览位置 ms（仅 dragging 时用于当前时间展示） */
    var seekPreviewPositionMs: Long by mutableStateOf(0L)

    /** seek 提示（替代 msg 1000/1001）：方向与文本 */
    var seekHintVisible: Boolean by mutableStateOf(false)
    var seekHintForward: Boolean by mutableStateOf(true)
    var seekHintText: String by mutableStateOf("")

    /** 亮度/音量提示（替代 BaseController msg 100/101）；文本只有百分比，靠图标区分是哪一项 */
    var slideHintVisible: Boolean by mutableStateOf(false)
    var slideHintText: String by mutableStateOf("")
    var slideHintBrightness: Boolean by mutableStateOf(true)

    /** 长按倍速浮层（替代 play_speed_3_container / fromLongPress） */
    var speedBoostVisible: Boolean by mutableStateOf(false)
    /** 浮层显示的倍率值(设置页可调 2x~10x,长按触发时写入) */
    var speedBoostValue: Float by mutableStateOf(3.0f)

    // —— 1 秒轮询（替代 myRunnable2） ——
    var title: String by mutableStateOf("")
    var videoSize: String by mutableStateOf(VideoSizeGate.UNKNOWN)
    var sysTime: String by mutableStateOf("")
    /** 电量百分比（0~100；读不到为 -1 不显示），随 1s 轮询刷新 */
    var batteryPercent: Int by mutableStateOf(-1)
    /** 充电中/已充满：电池图标用闪电帧 */
    var batteryCharging: Boolean by mutableStateOf(false)
    var netSpeedTopRight: String by mutableStateOf("")
    var netSpeedCenter: String by mutableStateOf("")

    // —— 顶部栏元素可见性（与 msg 1002/1003 的规则一致） ——
    /** mTopRoot1：片名 + 分辨率 */
    var topLeftVisible: Boolean by mutableStateOf(false)
    /** mTopRoot2 容器：init 后一旦显示过就保持可见（旧实现如此） */
    var topRightVisible: Boolean by mutableStateOf(false)
    /** tv_sys_time */
    var sysTimeVisible: Boolean by mutableStateOf(false)
    /** tv_play_load_net_speed_right_top */
    var netSpeedTopRightVisible: Boolean by mutableStateOf(false)

    /** 返回键：true = VISIBLE（仅横屏非 TV），false = INVISIBLE（占位不显示） */
    var backVisible: Boolean by mutableStateOf(false)
    /** 锁屏按钮可见性（照搬 showLockView 的三态） */
    var lockState: LockVisibility by mutableStateOf(LockVisibility.GONE)

    // —— 底部菜单（照搬 updatePortraitMenu / updatePlayerCfgView / hideLiveAboutBtn） ——
    var infoOsdVisible: Boolean by mutableStateOf(false)
    var infoOsdLeft: List<String> by mutableStateOf(emptyList())
    var infoOsdRight: List<String> by mutableStateOf(emptyList())
    var infoOsdFooter: String by mutableStateOf("")
    var showParseRow: Boolean by mutableStateOf(false)
    var isPortrait: Boolean by mutableStateOf(true)
    var playerType: Int by mutableStateOf(2)
    /** 直播源（duration==0）时隐藏倍速与片头尾按钮 */
    var liveButtonsVisible: Boolean by mutableStateOf(true)
    /** 当前播放会话的影片数据（页面在会话建立/接管时写入）；选集入口可见性由它派生，缓存成标志会在同片接管时漏写 */
    var sessionVod: VodInfo? by mutableStateOf(null)
    /** 详情页竖屏预览态（setPreviewMode 写入）：呼出控件栏时只显示进度行，不显示菜单行 */
    var previewMode: Boolean by mutableStateOf(false)
    /** 片头/片尾已设值(mm:ss);未设置为空串(参数面板显示为「未设置」) */
    var timeStartText: String by mutableStateOf("")
    var timeEndText: String by mutableStateOf("")
    /** 解析列表版本号：setDefaultParse 后自增以驱动重绘 */
    var parseListVersion: Int by mutableStateOf(0)

    /** 尺寸/倍速/播放器选择弹窗（阶段 7：替代 View 版 SelectDialog），null = 不显示 */
    var selectDialog: SelectDialogState? by mutableStateOf(null)

    /** 播放参数抽屉（倍速/解码/片头尾/内核/比例/搜弹幕的统一入口），null = 不显示 */
    var paramsSheet: ParamsSheetState? by mutableStateOf(null)

    /** 「更多」面板停留的分页；页面级记忆：关抽屉再开保留，退出播放页/换片重进回默认（不落 KV） */
    var paramsTab: ParamsTab by mutableStateOf(ParamsTab.Playback)

    // —— 加载/错误遮罩（由 PlayerTipBridge 经页面桥入，见 PlayContainer.onTipStateChanged） ——
    var tipMsg: String by mutableStateOf("")
    var tipLoading: Boolean by mutableStateOf(false)
    var tipErr: Boolean by mutableStateOf(false)

    /** 遮罩是否在屏：盖住视频面（含上一部/上一集的残留画面），但**不**盖顶栏与底栏 */
    val tipVisible: Boolean get() = tipLoading || tipErr

    fun applyTip(msg: String, loading: Boolean, err: Boolean) {
        tipMsg = msg
        tipLoading = loading
        tipErr = err
    }

    // —— Step 6 对话框 sheet 化（替代 View 版 Cast/Episode Dialog） ——
    /** 投屏设备面板 */
    var castSheet: CastSheetState? by mutableStateOf(null)
    /** 详情页选集面板在屏（面板状态归 DetailViewModel，这里只收一个投影） */
    var episodeSheetOpen: Boolean by mutableStateOf(false)

    /** 有覆盖层面板在屏：面板期间冻结底栏的 10s 自动收起（见 ComposeVideoController.idleHideRunnable） */
    val overlayPanelOpen: Boolean
        get() = selectDialog != null || paramsSheet != null ||
                castSheet != null || episodeSheetOpen

    // —— 衍生可见性（照搬 updatePortraitMenu 的逐按钮规则；与方向无关，预览态由菜单行/解析行的 previewMode 守卫） —

    /** 选集入口可见:当前线路剧集数 >1(面板只列剧集,单集时点开没有可选项);数据未就绪按不可见处理 */
    val episodeBtnVisible: Boolean
        get() {
            val vod = sessionVod ?: return false
            val flag = vod.playFlag ?: return false
            return (vod.seriesMap?.get(flag)?.size ?: 0) > 1
        }

    /** 退后台暂停标记:语义 = 回前台会续播(见 PlayerControlApi.setLifecyclePaused),此时不画暂停浮层 */
    var lifecyclePaused: Boolean by mutableStateOf(false)

    /** 控制层按“播放中”渲染(中央键/预览态键):BUFFERED 不回 PLAYING(dkplayer),只判 PLAYING 会图标反显;生命周期暂停回前台必续播 */
    val playbackActive: Boolean
        get() = lifecyclePaused ||
                playState == VideoView.STATE_PLAYING ||
                playState == VideoView.STATE_BUFFERING ||
                playState == VideoView.STATE_BUFFERED

    /** 暂停浮层可见:暂停中且底栏已收起;生命周期暂停不算(避免任务快照拍到"已暂停"假象) */
    val pauseOverlayVisible: Boolean
        get() = playState == VideoView.STATE_PAUSED && !controlsVisible && !lifecyclePaused

    /** 当前时间行文本位置：拖拽/按键步进中显示预览位置，否则显示真实播放位置 */
    val seekPreviewOrPosition: Int
        get() = if (dragging && duration > 0) seekPreviewPositionMs.toInt().coerceIn(0, duration) else position

    /** loading 可见（照搬 BaseController.onPlayStateChanged） */
    val loadingVisible: Boolean
        get() = playState == VideoView.STATE_PREPARING || playState == VideoView.STATE_BUFFERING

    val centerControlsVisible: Boolean
        get() = controlsVisible && !loadingVisible && !tipVisible && !locked

    /** 中央网速文本可见（旧实现仅 IDLE 阶段可见） */
    val netSpeedCenterVisible: Boolean
        get() = playState == VideoView.STATE_IDLE
}

/** 锁屏按钮三态（照搬旧实现 GONE/INVISIBLE/VISIBLE 的区别） */
enum class LockVisibility { GONE, HIDDEN, SHOWN }

/** 「更多」面板分页：播放参数 / 画质参数 */
enum class ParamsTab { Playback, Picture }

/**
 * 选择弹窗状态（照搬 SelectDialog.setAdapter 的四参数：tip/数据/默认选中/回调）。
 * onSelected(index) 由控制器提供：应用选择后由 UI 侧自动收起弹窗。
 */
class SelectDialogState(
    val tip: String,
    val items: List<String>,
    val defaultIndex: Int,
    val onSelected: (Int) -> Unit,
)

/** 参数面板里的一组档位（档位文案 + 当前下标 + 选择回调）；渲染成 chips 还是滑块由面板决定 */
class ParamsChoice(
    val options: List<String>,
    val selected: Int,
    val onSelect: (Int) -> Unit,
)

/**
 * 播放参数抽屉状态（替代原底栏那批「文字即状态值」的按钮）。
 * 选项与当前下标由控制器在打开与每次选择后重建，保证选中态实时刷新。
 */
class ParamsSheetState(
    val speed: ParamsChoice,
    val decode: ParamsChoice,
    val player: ParamsChoice,
    val scale: ParamsChoice,
    val picture: PictureParamsState,
    /** 已设片头/片尾(mm:ss);空串 = 未设置 */
    val timeStartText: String,
    val timeEndText: String,
    val onSetTimeStart: () -> Unit,
    val onSetTimeEnd: () -> Unit,
    val onResetTime: () -> Unit,
)

/** 画质参数面板状态：值取自 KV，改动即时落库并下发内核；面板只保留拖动中的副本（拖动不重建本对象） */
class PictureParamsState(
    val preset: PicturePreset,
    /** 「自定义」预置下的 8 项滑条值；其余预置不展开滑条 */
    val tuning: PictureProfile,
    /** 当前不可调色的原因（面板显示一行说明；None = 可用） */
    val unavailableReason: PictureEffectUnavailableReason,
    val anime4kTierText: String,
    val anime4kEnabled: Boolean,
    /** 本机链构建失败（编译不过 / 资产读不到）：Anime4K 组下补一行"本次已跳过" */
    val anime4kUnavailable: Boolean,
    /** 链末锐化强度（0~1，滑条，改动即时生效、不必重播） */
    val anime4kSharpen: Float,
    /** 链内去模糊（Deblur_DoG）；改它要重播本集（链的 pass 组成变了） */
    val anime4kDeblur: Boolean,
    val onAnime4kToggled: (Boolean) -> Unit,
    val onAnime4kSharpenChanged: (Float) -> Unit,
    val onAnime4kDeblurToggled: (Boolean) -> Unit,
    val onPresetSelected: (PicturePreset) -> Unit,
    val onTuningChanged: (PictureProfile) -> Unit,
    /** 滑条拖动结束(松开):补一次"本集若没挂链则自动重播"判定;拖动中只即时调参不重播 */
    val onTuningChangeFinished: () -> Unit,
    val onReset: () -> Unit,
    /** 按住对比：true = 临时按恒等参数出画（松手复原） */
    val onCompareChanged: (Boolean) -> Unit,
)

/** 投屏设备面板状态（替代 View 版 CastDeviceDialog） */
class CastSheetState(
    val video: CastVideo,
    val onCastSuccess: () -> Unit,
)

/**
 * 控制层意图集（UI → 控制器）。由 ComposeVideoController 实现。
 * 命名与播放器按钮清单一一对应。
 */
interface PlayerActions {
    // 底栏显隐
    fun toggleControls()
    fun keepControlsAlive()

    // 播控按钮（onXxxLongClicked 为遥控器确认键/触摸长按）
    fun onNextClicked()
    fun onPreClicked()
    /** 播放/暂停切换（底栏「播放」按钮 + 中央控制组中间按钮） */
    fun onPlayPauseClicked()
    fun onRefreshClicked()
    fun onScaleClicked()
    fun onScaleLongClicked()
    fun onSpeedClicked()
    fun onSpeedLongClicked()
    fun onPlayerClicked()
    fun onPlayerLongClicked()
    fun onTimeStartClicked()
    fun onTimeStartLongClicked()
    fun onTimeEndClicked()
    fun onTimeEndLongClicked()
    fun onTimeResetClicked()
    /** 打开页面的选集面板（详情页弹层，横屏全屏下的选集入口） */
    fun onEpisodeClicked()
    fun onCastClicked()
    fun onAudioTrackClicked()
    fun onVideoTrackClicked()
    fun onRotateClicked()
    /** 打开播放参数抽屉（右侧竖排入口） */
    fun onParamsClicked()
    fun onInfoOsdClicked()
    fun onBackClicked()
    fun onLockClicked()

    // 解析
    fun onParseSelected(position: Int)

    // 进度条（拖拽中不回写；滚轮/方向键步进）
    fun onSeekStarted()
    fun onSeekPreview(progress: Int)
    fun onSeekFinished(progress: Int)
    /** 手势被系统取消（等效旧 ACTION_CANCEL：不提交 seek） */
    fun onSeekCancelled()
    fun onSeekStep(dir: Int)

    // 1s 轮询（替代 myRunnable2）
    fun refreshSystemInfo()

    // 提示浮层自动隐藏（替代 msg 1001/101）
    fun hideSeekHint()
    fun hideSlideHint()
}
