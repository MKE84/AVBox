package com.github.tvbox.osc.player.controller

import com.github.tvbox.osc.util.LOG
import android.content.Context
import android.content.pm.ActivityInfo
import android.os.BatteryManager
import android.content.res.Configuration
import android.os.Handler
import android.os.Looper
import android.util.AttributeSet
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.animation.Animation
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.ComposeView
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.upstream.DefaultBandwidthMeter
import androidx.media3.ui.SubtitleView
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.ParseBean
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.player.ExoPlayer
import com.github.tvbox.osc.player.MyVideoView
import com.github.tvbox.osc.player.state.LockVisibility
import com.github.tvbox.osc.player.state.ParamsChoice
import com.github.tvbox.osc.player.state.ParamsSheetState
import com.github.tvbox.osc.player.effect.PictureEffects
import com.github.tvbox.osc.player.effect.anime4k.Anime4kSettings
import com.github.tvbox.osc.player.effect.anime4k.Anime4kTier
import com.github.tvbox.osc.player.state.PictureParamsState
import com.github.tvbox.osc.player.state.PlayerActions
import com.github.tvbox.osc.player.state.PlayerUiState
import com.github.tvbox.osc.player.state.VideoSizeGate
import com.github.tvbox.osc.player.state.SelectDialogState
import com.github.tvbox.osc.player.ui.PlayerOverlay
import com.github.tvbox.osc.player.usecase.M3u8PurifyUseCase
import com.github.tvbox.osc.player.usecase.PlayerSwitchUseCase
import com.github.tvbox.osc.player.usecase.WebParseUseCase
import com.github.tvbox.osc.subtitle.widget.SimpleSubtitleView
import com.github.tvbox.osc.ui.theme.AVBoxTheme
import com.github.tvbox.osc.util.DanmuHelper
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.PlayerHelper
import com.github.tvbox.osc.util.SubtitleHelper
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.PlaybackProgress
import org.greenrobot.eventbus.EventBus
import org.json.JSONException
import org.json.JSONObject
import xyz.doikki.videoplayer.controller.BaseVideoController
import xyz.doikki.videoplayer.controller.ControlWrapper
import xyz.doikki.videoplayer.player.VideoView
import xyz.doikki.videoplayer.util.PlayerUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.HashMap
import java.util.Locale

@Suppress("MemberVisibilityCanBePrivate")
class ComposeVideoController @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : BaseVideoController(context, attrs, defStyleAttr), PlayerControlApi, PlayerActions {

    companion object {

        /** 锁屏图标 3s 后隐藏 */
        private const val LOCK_HIDE_DELAY_MS = 3000L
        /** BugReview #32:倍速应用重试上限(100ms×30 = 3s),防长期不进播放态时主线程空转 */
        private const val SPEED_RETRY_MAX = 30
        /** SeekBar max 照搬旧布局 android:max="1000" */
        private const val SEEK_MAX = 1000
    }


    internal lateinit var state: PlayerUiState

    private var kernelSource: PlayerControlApi.KernelProvider? = null

    override fun setKernelProvider(provider: PlayerControlApi.KernelProvider?) {
        kernelSource = provider
    }

    // initView 由父类构造函数虚调用:那时属性初始化器还没跑 ⇒ 委托必须在 initView 里建(同 ComposeLiveController)
    internal lateinit var gestures: GestureController

    /** mControlWrapper 是父类 protected 字段,手势委托经这里取用(dkplayer 的类型) */
    internal val wrapper: ControlWrapper?
        get() = mControlWrapper

    // —— 原生字幕视图（PlayContainer 直接操作，保留 View 引用） ——
    private lateinit var mSubtitleView: SimpleSubtitleView
    private lateinit var mLyricView: SimpleSubtitleView
    private lateinit var mExoSubtitleView: SubtitleView

    internal var curPlayState = 0
    private val videoSizeGate = VideoSizeGate()

    // —— 控制层行为字段（照抄 VodController） ——
    internal var previewMode = false
    internal var speedOld = 1.0f
    /** BugReview #32:倍速应用重试计数 */
    private var speedRetryCount = 0
    private var skipEnd = true
    private var isClickBackBtn = false
    private var showParseFlag = false
    internal var playerConfig: JSONObject? = null
    private var listener: VodControlListener? = null

    // 方向键/滚轮步进 seek 的累计进度与提交去抖
    private var keySeekProgress = 0

    // 底栏闲置隐藏（旧 myHandleSeconds：注释写6秒实为10秒，照搬）
    private val idleHideMillis = 10000L

    private val uiHandler by lazy { Handler(Looper.getMainLooper()) }
    private val idleHideRunnable by lazy {
        Runnable {
            // 面板在屏时续期而不是收起:面板会吃掉点击(玩家不会收到 onSingleTapConfirmed),不续期就会
            // 在用户盯着面板时把底栏收掉,与"面板期间不收底栏"矛盾
            if (state.overlayPanelOpen) keepControlsAlive() else hideBottom()
        }
    }
    private val lockHideRunnable by lazy { Runnable { state.lockState = LockVisibility.HIDDEN } }
    private val keySeekCommitRunnable by lazy { Runnable { commitKeySeek() } }
    private val speedRetryRunnable by lazy { Runnable { applySpeedWhenReady() } }

    private val m3u8PurifyUseCase by lazy {
        M3u8PurifyUseCase(context, object : M3u8PurifyUseCase.Callback {
            override fun startPlayUrl(url: String?, headers: HashMap<String, String>?) {
                listener?.startPlayUrl(url ?: return, headers)
            }

            override fun onM3u8ProxyUrl(proxyUrl: String?, sourceUrl: String?) {
                listener?.onM3u8ProxyUrl(proxyUrl ?: return, sourceUrl ?: return)
            }
        })
    }
    private val webParseUseCase by lazy { WebParseUseCase() }

    // FastClickCheckUtil 等价：同一动作 500ms 内只生效一次
    private val fastClickMap = HashMap<String, Long>()

    private fun fastClickAllowed(key: String): Boolean {
        val now = System.currentTimeMillis()
        val last = fastClickMap[key] ?: 0L
        if (now - last < 500) return false
        fastClickMap[key] = now
        return true
    }

    // ============================================================
    // 生命周期 / 初始化
    // ============================================================

    override fun initView() {
        super.initView()
        state = PlayerUiState()

        gestures = GestureController(this)
        gestures.attach()

        initNativeSubtitleViews()
        initComposeLayer()

        // —— 初始状态（对齐旧 initView 屏显初始化） ——
        state.sysTimeVisible = false
        state.isPortrait =
            resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        updateDanmuBtnState()
        updateDanmuSearchBtnState()
        initSubtitleInfo()
    }

    override fun getLayoutId(): Int = 0

    /** 原生字幕视图（z-order：Compose 层之下，与旧布局一致） */
    private fun initNativeSubtitleViews() {
        val vs5 = resources.getDimensionPixelSize(R.dimen.vs_5)
        val vs15 = resources.getDimensionPixelSize(R.dimen.vs_15)
        val vs20 = resources.getDimensionPixelSize(R.dimen.vs_20)

        mSubtitleView = SimpleSubtitleView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(vs20, vs15, vs20, vs15)
            visibility = View.VISIBLE
        }
        addView(
            mSubtitleView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT, Gravity.BOTTOM),
        )

        mExoSubtitleView = SubtitleView(context).apply { visibility = View.GONE }
        addView(mExoSubtitleView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))

        mLyricView = SimpleSubtitleView(context).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFF00FF00.toInt())
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            textScaleX = 1.1f
            setPadding(vs5, vs20, vs5, vs20)
            visibility = View.GONE
        }
        addView(mLyricView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT, Gravity.CENTER))
    }

    private fun initComposeLayer() {
        val composeView = ComposeView(context).apply {
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
            // 全屏 reparent(DecorView) 后 detach 时 composition 会释放并重建，
            // 状态全部保存在 PlayerUiState（控制器持有），重建无感
            setContent {
                // 视频覆盖层挂在纯黑播放页:状态栏图标外观仍由宿主 Activity 断言,主题不接管
                AVBoxTheme(manageStatusBarIcons = false) {
                    PlayerOverlay(state, this@ComposeVideoController)
                }
            }
        }
        addView(composeView)
    }

    private fun initSubtitleInfo() {
        mSubtitleView.setTextSize(SubtitleHelper.getTextSize(mActivity).toFloat())
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        uiHandler.removeCallbacks(idleHideRunnable)
        uiHandler.removeCallbacks(lockHideRunnable)
        uiHandler.removeCallbacks(keySeekCommitRunnable)
        uiHandler.removeCallbacks(speedRetryRunnable)
    }

    // ============================================================
    // dkplayer 事件钩子 → PlayerUiState
    // ============================================================

    override fun setPlayState(playState: Int) {
        super.setPlayState(playState)
        curPlayState = playState
    }

    override fun setPlayerState(playerState: Int) {
        super.setPlayerState(playerState)
        state.playerState = playerState
        gestures.onPlayerState(playerState)
    }

    override fun onPlayStateChanged(playState: Int) {
        super.onPlayStateChanged(playState)
        curPlayState = playState
        state.playState = playState
        // 时长可信的状态才对齐:同片接管/页面重挂只回灌当前状态(PAUSED/PLAYING),不会再有 PREPARED;
        // PREPARING 要排除 —— 此时时长读作 0,会把点播误判成直播源而隐藏倍速/片头尾
        if (playState != VideoView.STATE_IDLE && playState != VideoView.STATE_ERROR &&
            playState != VideoView.STATE_PREPARING
        ) {
            updateLiveButtonsState()
        }
        when (playState) {
            VideoView.STATE_IDLE -> {
                savePlaybackProgress(notifyHistory = true)
                state.locked = false
                state.duration = 0
                state.position = 0
            }
            VideoView.STATE_PLAYING -> {
                initOrientationState()
                startProgress()
            }
            VideoView.STATE_PAUSED -> {
                // 生命周期暂停保留界面(退后台那一帧进任务快照):不收菜单、不清顶栏
                if (!state.lifecyclePaused) {
                    state.topLeftVisible = false
                    state.netSpeedTopRightVisible = false
                    if (state.controlsVisible) hideBottom()
                }
                savePlaybackProgress(notifyHistory = true)
            }
            VideoView.STATE_ERROR -> listener?.errReplay()
            VideoView.STATE_PREPARED -> listener?.prepared()
            VideoView.STATE_PLAYBACK_COMPLETED -> {
                PlaybackProgress.markFinished()
                listener?.playNext(true)
            }
        }
    }

    override fun onLockStateChanged(isLocked: Boolean) {
        super.onLockStateChanged(isLocked)
        state.locked = isLocked
    }

    /** 内核上报尺寸（换内容必然先回落 0）：角标当帧刷新，不等轮询 */
    override fun onVideoSizeChanged(width: Int, height: Int) {
        super.onVideoSizeChanged(width, height)
        state.videoSize = videoSizeGate.textFor(width, height)
    }

    /** 内核换了内容（setUrl）：解除闸门过滤 */
    override fun onVideoSizeCleared() {
        super.onVideoSizeCleared()
        videoSizeGate.onKernelContentReplaced()
    }

    override fun onVisibilityChanged(isVisible: Boolean, anim: Animation?) {
        super.onVisibilityChanged(isVisible, anim)
        state.showing = isVisible
    }

    override fun setProgress(duration: Int, position: Int) {
        if (state.dragging) return
        super.setProgress(duration, position)
        state.duration = duration
        state.position = position
        PlaybackProgress.onProgress(position, duration)
        // 片尾自动跳下一集（skipEnd 防重，照搬）
        if (skipEnd && position != 0 && duration != 0) {
            val et = playerConfig?.optInt("et", 0) ?: 0
            if (et > 0 && position + et * 1000 >= duration) {
                skipEnd = false
                listener?.playNext(true)
            }
        }
        state.bufferedPercent = runCatching { mControlWrapper?.bufferedPercentage ?: 0 }.getOrDefault(0)
    }

    /** [seekTargetMs] 只在 seek 提交时给:此刻读 currentPosition 还是拖动前的老位置,暂停态也等不到下一拍更正 */
    private fun savePlaybackProgress(notifyHistory: Boolean, seekTargetMs: Int = -1) {
        val wrapperDuration = runCatching { mControlWrapper?.duration ?: 0L }.getOrDefault(0L).toInt()
        val wrapperPosition = runCatching { mControlWrapper?.currentPosition ?: 0L }.getOrDefault(0L).toInt()
        val duration = if (wrapperDuration > 0) wrapperDuration else state.duration
        val position = when {
            seekTargetMs >= 0 -> seekTargetMs
            wrapperDuration > 0 -> wrapperPosition
            else -> state.position
        }
        if (duration <= 0) return
        PlaybackProgress.flush(position, duration)
        // 值没变也必须通知:周期写入早已落盘,历史页手里的可能是进播放前的旧快照
        if (notifyHistory) EventBus.getDefault().post(RefreshEvent(RefreshEvent.TYPE_HISTORY_REFRESH))
    }

    /** seek 提示（替代旧 updateSeekUI + msg 1000/1001，UI 侧 1s 自动隐藏）。
     *  只显示目标时间 —— 总时长在底栏时间胶囊里已有，提示里再带一份是冗余。 */
    internal fun updateSeekUiHint(curr: Int, seekTo: Int) {
        state.seekHintForward = seekTo > curr
        state.seekHintText = PlayerUtils.stringForTime(seekTo)
        state.seekHintVisible = true
    }

    internal fun isInPlaybackState(): Boolean {
        return mControlWrapper != null &&
                curPlayState != VideoView.STATE_ERROR &&
                curPlayState != VideoView.STATE_IDLE &&
                curPlayState != VideoView.STATE_PREPARING &&
                curPlayState != VideoView.STATE_PREPARED &&
                curPlayState != VideoView.STATE_START_ABORT &&
                curPlayState != VideoView.STATE_PLAYBACK_COMPLETED
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestures.onTouchEvent(event)
        return super.onTouchEvent(event)
    }

    // ============================================================
    // 底栏显隐（替代 msg 1002/1003 与 myHandle 闲置计时）
    // ============================================================

    override fun toggleControls() {
        if (!state.controlsVisible) showBottom() else hideBottom()
    }

    /** 详情页预览态点击视频区（PlayerControlApi 契约），与全屏单击同一切换逻辑 */
    override fun toggleControlBar() {
        toggleControls()
    }

    private fun showBottom() {
        applyShowBottom()
    }

    /** 可见性规则与 msg 1002 等价 */
    private fun applyShowBottom() {
        updateDanmuSearchBtnState()
        state.controlsVisible = true
        state.topLeftVisible = true
        state.topRightVisible = true
        state.netSpeedTopRightVisible = true
        state.sysTimeVisible = true
        state.backVisible = !state.isPortrait
        showLockView()
        keepControlsAlive()
    }

    /** 等价旧 msg 1003 */
    fun hideBottom() {
        uiHandler.removeCallbacks(idleHideRunnable)
        // 拖拽/按键步进中把底栏移出组合会吞掉 onDragCancel（Compose 手势随组合销毁），
        // dragging 卡死会让 setProgress 永久早退、进度显示冻结，先复位
        if (state.dragging) onSeekCancelled()
        state.controlsVisible = false
        state.topLeftVisible = false
        state.netSpeedTopRightVisible = false
        state.sysTimeVisible = false
        state.backVisible = false
        uiHandler.removeCallbacks(lockHideRunnable)
        if (state.lockState != LockVisibility.GONE) {
            state.lockState = LockVisibility.HIDDEN
        }
    }

    override fun keepControlsAlive() {
        if (state.controlsVisible) {
            uiHandler.removeCallbacks(idleHideRunnable)
            uiHandler.postDelayed(idleHideRunnable, idleHideMillis)
        }
    }

    internal fun showLockView() {
        if (previewMode) {
            setLocked(false)
            uiHandler.removeCallbacks(lockHideRunnable)
            state.lockState = LockVisibility.GONE
            return
        }
        state.lockState = LockVisibility.SHOWN
        uiHandler.removeCallbacks(lockHideRunnable)
        if (isLocked()) {
            uiHandler.postDelayed(lockHideRunnable, LOCK_HIDE_DELAY_MS)
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        initOrientationState()
    }

    private fun initOrientationState() {
        val isPortrait = resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        state.isPortrait = isPortrait
        if (isPortrait) {
            state.backVisible = false
        }
    }

    private fun updatePlayerCfgState() {
        val cfg = playerConfig ?: return
        try {
            val playerType = cfg.getInt("pl")
            state.playerType = playerType
            val start = cfg.getInt("st")
            val end = cfg.getInt("et")
            // 未设置留空串：参数面板据此显示「未设置」，而不是把「片头」当值显示一遍
            state.timeStartText = if (start == 0) "" else PlayerUtils.stringForTime(start * 1000)
            state.timeEndText = if (end == 0) "" else PlayerUtils.stringForTime(end * 1000)
            // 配置一变(含换集/换源)就同步参数面板，否则面板会停在旧值
            refreshParamsSheet()
        } catch (e: JSONException) {
            LOG.e("ComposeVideoController", e)
        }
    }

    // —— 播放参数面板（底栏状态类控件的统一入口） ——

    /** 倍速档位（参数面板与倍速弹窗共用同一份，避免两处漂移） */
    private val speedOptions = floatArrayOf(0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f, 3.0f)

    /** 倍速档位下标；配置值不在档位表里时兜底到 1.0x（参数面板与倍速弹窗共用同一口径） */
    private fun speedIndex(value: Float): Int {
        val idx = speedOptions.indexOfFirst { it == value }
        return if (idx >= 0) idx else speedOptions.indexOfFirst { it == 1.0f }
    }

    /** 参数面板里播放器的展示顺序:exo 在左,外部播放器保持原顺序跟在其后 */
    private fun sheetPlayerOrder(types: List<Int>): List<Int> {
        val head = listOf(2)
        return head.filter { types.contains(it) } + types.filter { it !in head }
    }

    /** 面板打开时按当前配置现算；选项或选中值变化后重算，保证 chips 选中态实时刷新 */
    private fun buildParamsSheet(): ParamsSheetState? {
        val cfg = playerConfig ?: return null
        val speed = cfg.optDouble("sp", 1.0).toFloat()
        val playerType = cfg.optInt("pl", 2)
        val players = sheetPlayerOrder(PlayerHelper.getExistPlayerTypes())
        val scaleType = cfg.optInt("sc", 0)
        return ParamsSheetState(
            speed = ParamsChoice(
                options = speedOptions.map { "${it}x" },
                selected = speedIndex(speed),
                onSelect = { applySpeed(speedOptions[it]) },
            ),
            decode = decodeChoice(cfg),
            player = ParamsChoice(
                options = players.map { PlayerHelper.getPlayerName(it) },
                selected = players.indexOf(playerType).coerceAtLeast(0),
                onSelect = { applyPlayer(players[it]) },
            ),
            scale = ParamsChoice(
                options = (0..5).map { PlayerHelper.getScaleName(it) },
                selected = scaleType.coerceIn(0, 5),
                onSelect = { applyScale(it) },
            ),
            picture = PictureParamsState(
                preset = PictureEffects.preset(),
                tuning = PictureEffects.custom(),
                unavailableReason = PictureEffects.unavailableReason(),
                anime4kTierText = context.getString(Anime4kTier.current().labelRes),
                anime4kEnabled = Anime4kSettings.enabled(),
                anime4kUnavailable = PictureEffects.anime4kUnavailable(),
                anime4kSharpen = Anime4kSettings.sharpen(),
                anime4kDeblur = Anime4kSettings.deblur(),
                onAnime4kToggled = {
                    Anime4kSettings.setEnabled(it)
                    restartForPictureIfNeeded()
                    refreshParamsSheet()
                },
                onAnime4kSharpenChanged = { PictureEffects.setAnime4kSharpen(it) },
                onAnime4kDeblurToggled = {
                    Anime4kSettings.setDeblur(it)
                    restartForPictureIfNeeded()
                    refreshParamsSheet()
                },
                onPresetSelected = {
                    PictureEffects.selectPreset(it)
                    restartForPictureIfNeeded()
                    refreshParamsSheet()
                },
                onTuningChanged = {
                    // 拖动中只即时调参(链已挂时下一帧生效);不触发重播,避免拖动过程反复重播
                    PictureEffects.setCustom(it)
                },
                onTuningChangeFinished = { restartForPictureIfNeeded() },
                onReset = {
                    PictureEffects.reset()
                    restartForPictureIfNeeded()
                    refreshParamsSheet()
                },
                onCompareChanged = {
                    PictureEffects.compare(it)
                    restartForPictureIfNeeded()
                },
            ),
            timeStartText = state.timeStartText,
            timeEndText = state.timeEndText,
            onSetTimeStart = { markTimeStart() },
            onSetTimeEnd = { markTimeEnd() },
            onResetTime = { onTimeResetClicked() },
            onSearchDanmu = if (state.danmuSearchAvailable) {
                { onDanmuSearchClicked() }
            } else {
                null
            },
        )
    }

    /** 面板未打开时是 no-op（别把 null 写回去，那等于"打开面板"） */
    private fun refreshParamsSheet() {
        if (state.paramsSheet == null) return
        state.paramsSheet = buildParamsSheet()
    }

    /** 开/关画质都要重播一次本集才生效(开=挂链,关=回 Surface 直通;media3 只在渲染器 enable 时建/不建 sink) */
    private fun restartForPictureIfNeeded() {
        if (PictureEffects.consumeRestartNeeded()) listener?.replay(false)
    }

    /** 解码选项：只有硬/软两档(软解 = media3 的视频解码选择器优先系统软件解码器) */
    private fun decodeChoice(cfg: JSONObject): ParamsChoice {
        val isSoft = cfg.optString("exo", "硬解码") == "软解码" // i18n: keep
        return ParamsChoice(
            options = listOf(
                context.getString(R.string.player_decode_hard),
                context.getString(R.string.player_decode_soft),
            ),
            selected = if (isSoft) 1 else 0,
            onSelect = { applyDecode(if (it == 1) "软解码" else "硬解码") }, // i18n: keep
        )
    }

    private fun applySpeed(value: Float) {
        keepControlsAlive()
        try {
            val cfg = playerConfig ?: return
            cfg.put("sp", value.toDouble())
            updatePlayerCfgState()
            listener?.updatePlayerCfg()
            speedOld = value
            mControlWrapper?.setSpeed(value)
        } catch (e: JSONException) {
            LOG.e("ComposeVideoController", e)
        }
    }

    private fun applyScale(index: Int) {
        keepControlsAlive()
        try {
            val cfg = playerConfig ?: return
            cfg.put("sc", index)
            updatePlayerCfgState()
            listener?.updatePlayerCfg()
            mControlWrapper?.setScreenScaleType(index)
        } catch (e: JSONException) {
            LOG.e("ComposeVideoController", e)
        }
    }

    private fun applyPlayer(playerType: Int) {
        keepControlsAlive()
        try {
            val cfg = playerConfig ?: return
            if (playerType == cfg.optInt("pl", 2)) return
            cfg.put("pl", playerType)
            // ⚠️ 必须先于 updatePlayerCfg():它会让"自动切内核"态作废,否则本次落库会被回填成自动切换前的内核
            listener?.setAllowSwitchPlayer(false)
            updatePlayerCfgState()
            listener?.updatePlayerCfg()
            listener?.replay(false)
        } catch (e: JSONException) {
            LOG.e("ComposeVideoController", e)
        }
    }

    private fun applyDecode(value: String) {
        keepControlsAlive()
        try {
            val cfg = playerConfig ?: return
            // 值没变(重复点当前档)只刷新显式选择标记,不重建内核:重建会中断播放并清掉已试线路
            val unchanged = cfg.optString("exo") == value
            cfg.put("exo", value) // i18n: keep
            // 记一个显式选择标记:否则设置页的新值会被播放记录里的旧值压住
            cfg.put("exoSet", 1)
            // 用户显式选过解码:本次播放不再自动回退软解
            listener?.setAllowDecodeFallback(false)
            updatePlayerCfgState()
            listener?.updatePlayerCfg()
            if (!unchanged) listener?.replay(false)
        } catch (e: JSONException) {
            LOG.e("ComposeVideoController", e)
        }
    }

    private fun updateDanmuBtnState() {
        state.danmuOpen = DanmuHelper.isOpen()
    }

    private fun updateDanmuSearchBtnState() {
        state.danmuSearchAvailable = ApiConfig.get().hasDanmuSearchUi()
    }

    /** 直播源(duration==0)隐藏倍速/片头尾按钮;取不到时长按可显示处理 */
    private fun updateLiveButtonsState() {
        state.liveButtonsVisible = runCatching { mControlWrapper?.duration ?: 0L != 0L }.getOrDefault(true)
    }

    private fun applySpeedWhenReady() {
        if (isInPlaybackState()) {
            speedRetryCount = 0
            try {
                playerConfig?.let { mControlWrapper?.setSpeed(it.getDouble("sp").toFloat()) }
            } catch (e: JSONException) {
                LOG.e("ComposeVideoController", e)
            }
        } else if (speedRetryCount < SPEED_RETRY_MAX) {
            // BugReview #32:重试带上限,播放长期不进 playback 态时不再主线程空转;
            // 后续播放态就绪的事件(resetSpeed/切集)会重新触发应用
            speedRetryCount++
            uiHandler.removeCallbacks(speedRetryRunnable)
            uiHandler.postDelayed(speedRetryRunnable, 100)
        }
    }

    override fun getUiState(): PlayerUiState = state

    override fun getSubtitleView(): SimpleSubtitleView = mSubtitleView

    override fun getLyricView(): SimpleSubtitleView = mLyricView

    override fun getExoSubtitleView(): SubtitleView = mExoSubtitleView

    override fun setListener(l: VodControlListener?) {
        listener = l
    }

    override fun setPlayerConfig(playerCfg: JSONObject) {
        playerConfig = playerCfg
        updatePlayerCfgState()
    }

    override fun showParse(userJxList: Boolean) {
        showParseFlag = userJxList
        state.showParseRow = userJxList
    }

    override fun setPreviewMode(previewMode: Boolean) {
        this.previewMode = previewMode
        state.previewMode = previewMode
        // 退出全屏回预览态时顺带收起全屏残留菜单，避免预览窗仍挂着底栏/中央三键
        if (previewMode && state.controlsVisible) hideBottom()
        uiHandler.removeCallbacks(lockHideRunnable)
        state.lockState = LockVisibility.GONE
    }

    override fun setTitle(playTitleInfo: String) {
        state.title = playTitleInfo
    }

    /** 暂停浮层已删,保留接口兼容 */
    override fun setUrlTitle(playTitleInfo: String) = Unit

    override fun setHasDanmu(hasDanmu: Boolean) {
        updateDanmuBtnState()
    }

    override fun setCanChangePosition(canChangePosition: Boolean) {
        gestures.setCanChangePosition(canChangePosition)
    }

    override fun setEnableInNormal(enableInNormal: Boolean) {
        gestures.setEnableInNormal(enableInNormal)
    }

    override fun setGestureEnabled(gestureEnabled: Boolean) {
        gestures.setGestureEnabled(gestureEnabled)
    }

    /** 旧暂停浮层根已并入 Compose 层,View 版无需隐藏 */
    override fun hidePauseRoot() = Unit

    override fun onNewPlayStarted() {
        val size = runCatching { mControlWrapper?.videoSize }.getOrNull() ?: intArrayOf(0, 0)
        state.videoSize = videoSizeGate.onNewSession(size[0], size[1])
    }

    override fun setLifecyclePaused(paused: Boolean) {
        state.lifecyclePaused = paused
        // 退后台保留控件(任务快照 = 离开时的样子):冻结自动收起,否则计时会在后台把控件收掉
        if (paused) uiHandler.removeCallbacks(idleHideRunnable) else keepControlsAlive()
    }

    override fun resetSpeed() {
        skipEnd = true
        applySpeedWhenReady()
    }

    override fun onBackPressed(): Boolean {
        if (isClickBackBtn) {
            isClickBackBtn = false
            if (state.controlsVisible) hideBottom()
            return false
        }
        // 侧边返回手势(YouTube 式)：菜单唤出时先收菜单并消费本次返回，
        // 菜单收起后再滑才交还宿主退出全屏回竖屏详情页
        if (state.controlsVisible) {
            hideBottom()
            return true
        }
        return super.onBackPressed()
    }

    /**
     * 自动重试的"换内核"阶梯(仅由 PlayContainer.autoRetry 调用):内核只剩 EXO,恒为"跳过"。
     * 手动换播放器([onPlayerClicked]/[onPlayerLongClicked])不受影响,仍按剧记忆持久化。
     */
    override fun switchPlayer(): Boolean = PlayerSwitchUseCase.switchPlayer()

    override fun stopOther() {
        PlayerSwitchUseCase.stopOther()
    }

    override fun playM3u8(url: String?, headers: HashMap<String, String>?) {
        m3u8PurifyUseCase.playM3u8(url ?: return, headers)
    }

    override fun encodeUrl(url: String?): String = PlayerSwitchUseCase.encodeUrl(url)

    override fun firstUrlByArray(url: String?): String = PlayerSwitchUseCase.firstUrlByArray(url)

    override fun evaluateScript(sourceBean: SourceBean?, url: String?, view: WebView?) {
        webParseUseCase.evaluateScript(sourceBean, url, view)
    }

    override fun getWebPlayUrlIfNeeded(webPlayUrl: String?): String {
        return webParseUseCase.getWebPlayUrlIfNeeded(webPlayUrl)
    }

    // ============================================================
    // PlayerActions 实现（按钮清单）
    // ============================================================

    override fun onNextClicked() {
        listener?.playNext(false)
        hideBottom()
    }

    override fun onPreClicked() {
        listener?.playPre()
        hideBottom()
    }

    override fun onPlayPauseClicked() {
        // 与其余按钮一致 500ms 防抖：触摸误双击＝两次 togglePlay 净零
        if (!fastClickAllowed("play_pause")) return
        // 遮罩在屏且不在播放态时短路:内核里可能还挂着上一次会话的地址,start() 会按旧地址起播
        // (错误态同样是 IDLE,故判遮罩而非只判 loading)
        if (state.tipVisible && !isInPlaybackState()) return
        mControlWrapper?.togglePlay()
        keepControlsAlive()
    }

    override fun onRefreshClicked() {
        listener?.replay(false)
        hideBottom()
    }

    override fun onScaleClicked() {
        keepControlsAlive()
        showScaleDialog()
    }

    override fun onScaleLongClicked() {
        keepControlsAlive()
        if (!fastClickAllowed("scale_long")) return
        applyScale(0)
    }

    override fun onSpeedClicked() {
        keepControlsAlive()
        showSpeedDialog()
    }

    override fun onSpeedLongClicked() {
        keepControlsAlive()
        if (!fastClickAllowed("speed_long")) return
        applySpeed(1.0f)
    }

    override fun onPlayerClicked() {
        keepControlsAlive()
        val cfg = playerConfig ?: return
        val existPlayerTypes = PlayerHelper.getExistPlayerTypes()
        if (existPlayerTypes.isEmpty()) return
        val current = cfg.optInt("pl", 2)
        var nextIdx = 0
        for (i in existPlayerTypes.indices) {
            if (current == existPlayerTypes[i]) {
                nextIdx = if (i == existPlayerTypes.size - 1) 0 else i + 1
            }
        }
        applyPlayer(existPlayerTypes[nextIdx])
        hideBottom()
    }

    override fun onPlayerLongClicked() {
        keepControlsAlive()
        if (!fastClickAllowed("player_long")) return
        try {
            val cfg = playerConfig ?: return
            val playerType = cfg.getInt("pl")
            var defaultPos = 0
            val players = PlayerHelper.getExistPlayerTypes()
            val names = ArrayList<String>()
            for (p in players.indices) {
                names.add(PlayerHelper.getPlayerName(players[p]))
                if (players[p] == playerType) {
                    defaultPos = p
                }
            }
            state.selectDialog = SelectDialogState(
                tip = context.getString(R.string.player_select_player),
                items = names,
                defaultIndex = defaultPos,
                onSelected = { pos ->
                    // 选中的就是当前内核时什么都不做(含不收底栏):与 applyPlayer 的同值早退同一口径
                    if (players[pos] != playerType) {
                        applyPlayer(players[pos])
                        hideBottom()
                    }
                },
            )
        } catch (e: JSONException) {
            LOG.e("ComposeVideoController", e)
        }
    }

    override fun onTimeStartClicked() {
        keepControlsAlive()
        markTimeStart()
    }

    override fun onTimeStartLongClicked() {
        setTimeMark("st", 0)
    }

    override fun onTimeEndClicked() {
        keepControlsAlive()
        markTimeEnd()
    }

    override fun onTimeEndLongClicked() {
        setTimeMark("et", 0)
    }

    override fun onTimeResetClicked() {
        keepControlsAlive()
        try {
            val cfg = playerConfig ?: return
            cfg.put("st", 0)
            cfg.put("et", 0)
            updatePlayerCfgState()
            listener?.updatePlayerCfg()
        } catch (e: JSONException) {
            LOG.e("ComposeVideoController", e)
        }
    }

    /** 把当前位置设为片头;位置已过半程时不设(那时它更可能是片尾) */
    private fun markTimeStart() {
        val wrapper = mControlWrapper ?: return
        val current = PlayerUtils.safeTimeMs(wrapper.currentPosition)
        if (current > PlayerUtils.safeTimeMs(wrapper.duration) / 2) return
        setTimeMark("st", current / 1000)
    }

    /** 把当前位置到结尾的时长设为片尾;位置未过半程时不设 */
    private fun markTimeEnd() {
        val wrapper = mControlWrapper ?: return
        val current = PlayerUtils.safeTimeMs(wrapper.currentPosition)
        val duration = PlayerUtils.safeTimeMs(wrapper.duration)
        if (current < duration / 2) return
        setTimeMark("et", (duration - current) / 1000)
    }

    /** 写 st/et 并落库(0 = 清除) */
    private fun setTimeMark(key: String, seconds: Int) {
        keepControlsAlive()
        try {
            val cfg = playerConfig ?: return
            cfg.put(key, seconds)
            updatePlayerCfgState()
            listener?.updatePlayerCfg()
        } catch (e: JSONException) {
            LOG.e("ComposeVideoController", e)
        }
    }

    override fun onEpisodeClicked() {
        if (!fastClickAllowed("episode")) return
        listener?.showEpisodes()
        // 面板在屏时不收底栏(与播放参数/弹幕面板一致),只续期自动收起计时
        keepControlsAlive()
    }

    override fun onCastClicked() {
        listener?.clickCast()
    }

    override fun onSubtitleClicked() {
        if (!fastClickAllowed("zimu")) return
        listener?.selectSubtitle()
        keepControlsAlive()
    }

    override fun onSubtitleLongClicked() {
        if (!fastClickAllowed("zimu_long")) return
        // 关闭字幕归播放层:要落"这个片不要字幕"的记忆并让它跨集生效
        listener?.closeSubtitles()
        hideBottom()
        Toast.makeText(context, context.getString(R.string.player_subtitle_closed), Toast.LENGTH_SHORT).show()
    }

    override fun onAudioTrackClicked() {
        if (!fastClickAllowed("audio")) return
        listener?.selectAudioTrack()
        keepControlsAlive()
    }

    override fun onVideoTrackClicked() {
        if (!fastClickAllowed("video")) return
        listener?.selectVideoTrack()
        keepControlsAlive()
    }

    override fun onDanmuSettingClicked() {
        if (!fastClickAllowed("danmu")) return
        listener?.showDanmuSetting()
    }

    override fun onDanmuSettingLongClicked() {
        if (!fastClickAllowed("danmu_long")) return
        val opened = listener?.toggleDanmu() ?: false
        hideBottom()
        Toast.makeText(context, context.getString(if (opened) R.string.player_danmu_opened else R.string.player_danmu_temp_closed), Toast.LENGTH_SHORT).show()
    }

    override fun onDanmuSearchClicked() {
        listener?.searchDanmuUi(false)
        hideBottom()
    }

    override fun onDanmuSearchLongClicked() {
        listener?.searchDanmuUi(true)
        hideBottom()
    }

    override fun onRotateClicked() {
        if (isLocked()) return
        if (!fastClickAllowed("rotate")) return
        val toPortrait =
            resources.configuration.orientation != Configuration.ORIENTATION_PORTRAIT
        mActivity?.requestedOrientation =
            if (toPortrait) ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        hideBottom()
    }

    override fun onParamsClicked() {
        keepControlsAlive()
        state.paramsSheet = buildParamsSheet()
    }

    override fun onInfoOsdClicked() {
        state.infoOsdVisible = !state.infoOsdVisible
        val exo = kernelSource?.get()?.mediaPlayer as? ExoPlayer
        if (state.infoOsdVisible) {
            exo?.setFrameRateTracking(true)
            refreshInfoOsd(runCatching { wrapper?.tcpSpeed ?: 0L }.getOrDefault(0L))
        } else {
            exo?.setFrameRateTracking(false)
        }
        if (state.overlayPanelOpen) keepControlsAlive() else hideBottom()
    }

    override fun onBackClicked() {
        isClickBackBtn = state.controlsVisible && !previewMode
        // 走 dispatcher(宿主一律是 BaseActivity):Activity.onBackPressed() 已废弃
        (mActivity as? ComponentActivity)?.onBackPressedDispatcher?.onBackPressed()
    }

    override fun onLockClicked() {
        val newLocked = !isLocked()
        setLocked(newLocked)
        if (newLocked) hideBottom()
        showLockView()
    }

    override fun onParseSelected(position: Int) {
        val parseBeanList = ApiConfig.get().parseBeanList
        if (position < 0 || position >= parseBeanList.size) return
        val parseBean: ParseBean = parseBeanList[position]
        ApiConfig.get().setDefaultParse(parseBean)
        state.parseListVersion++
        listener?.changeParse(parseBean)
        hideBottom()
    }

    override fun onSeekStarted() {
        if (!state.controlsVisible) applyShowBottom()
        if (state.dragging) return
        state.dragging = true
        mControlWrapper?.stopProgress()
        mControlWrapper?.stopFadeOut()
        keepControlsAlive()
    }

    override fun onSeekPreview(progress: Int) {
        val wrapper = mControlWrapper ?: return
        val duration = PlayerUtils.safeTimeMs(wrapper.duration)
        state.seekPreviewPositionMs = seekBarToPosition(progress, duration)
    }

    override fun onSeekFinished(progress: Int) {
        keepControlsAlive()
        val wrapper = mControlWrapper
        var seekTarget = -1
        if (wrapper != null) {
            val duration = PlayerUtils.safeTimeMs(wrapper.duration)
            seekTarget = seekBarToPosition(progress, duration).toInt()
            wrapper.seekTo(seekTarget.toLong())
        }
        // 顺序反了这次 seek 的位置就进不了记录:拖拽态下 setProgress 丢弃这一拍,而暂停态的循环开完这一拍就停
        state.dragging = false
        keySeekProgress = 0
        mControlWrapper?.startProgress()
        mControlWrapper?.startFadeOut()
        // 显式落盘:暂停态等不到下一拍,播放态也不该等到下一拍才更新历史页
        if (seekTarget >= 0) savePlaybackProgress(notifyHistory = true, seekTargetMs = seekTarget)
    }

    override fun onSeekCancelled() {
        state.dragging = false
        keySeekProgress = 0
        mControlWrapper?.startProgress()
        mControlWrapper?.startFadeOut()
    }

    override fun onSeekStep(dir: Int) {
        val wrapper = mControlWrapper ?: return
        val duration = PlayerUtils.safeTimeMs(wrapper.duration)
        if (duration <= 0) return
        if (!state.controlsVisible) applyShowBottom()
        if (!state.dragging) {
            state.dragging = true
            wrapper.stopProgress()
            wrapper.stopFadeOut()
        }
        keySeekProgress = (keySeekProgress + keySeekIncrement(duration) * dir).coerceIn(0, SEEK_MAX)
        state.seekPreviewPositionMs = seekBarToPosition(keySeekProgress, duration)
        updateSeekUiHint(
            PlayerUtils.safeTimeMs(wrapper.currentPosition),
            state.seekPreviewPositionMs.toInt(),
        )
        uiHandler.removeCallbacks(keySeekCommitRunnable)
        uiHandler.postDelayed(keySeekCommitRunnable, 400)
    }

    private fun commitKeySeek() {
        if (!state.dragging) return
        onSeekFinished(keySeekProgress)
    }

    private fun seekBarToPosition(progress: Int, duration: Int): Long {
        if (duration <= 0) return 0L
        return duration.toLong() * progress / SEEK_MAX
    }

    private fun keySeekIncrement(duration: Int): Int {
        val increment: Long = when {
            duration > 3 * 60 * 60 * 1000 -> 5 * 60 * 1000L
            duration > 30 * 60 * 1000 -> 60 * 1000L
            duration > 15 * 60 * 1000 -> 30 * 1000L
            duration > 10 * 60 * 1000 -> 15 * 1000L
            else -> 10 * 1000L
        }
        return maxOf(1, (increment * SEEK_MAX / duration).toInt())
    }


    override fun refreshSystemInfo() {
        val wrapper = mControlWrapper ?: return
        state.sysTime = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date())
        readBattery()
        val speed = runCatching { wrapper.tcpSpeed }.getOrDefault(0L)
        state.netSpeedTopRight = PlayerHelper.getDisplaySpeed(speed, true)
        state.netSpeedCenter = PlayerHelper.getDisplaySpeed(speed, false)
        val size = runCatching { wrapper.videoSize }.getOrDefault(intArrayOf(0, 0))
        state.videoSize = videoSizeGate.textFor(size[0], size[1])
        if (state.infoOsdVisible) refreshInfoOsd(speed)
    }

    private fun refreshInfoOsd(speed: Long) {
        val videoView = kernelSource?.get()
        val exo = videoView?.mediaPlayer as? ExoPlayer
        val video = exo?.selectedVideoFormat
        val left = ArrayList<String>()
        val right = ArrayList<String>()

        left.add(context.getString(R.string.osd_video) + " " + videoText(video, exo))
        left.add(context.getString(R.string.osd_decoder) + " " + (exo?.videoDecoderName()?.takeIf { it.isNotEmpty() } ?: "-"))
        left.add(context.getString(R.string.osd_audio) + " " + audioText(exo?.selectedAudioFormat))

        exo?.sampleFrameRate()
        val throughput = runCatching {
            DefaultBandwidthMeter.getSingletonInstance(context).bitrateEstimate
        }.getOrDefault(0L)
        left.add(
            context.getString(R.string.osd_network) + " " + PlayerHelper.getDisplaySpeed(speed, true)
                + " · " + bitrateText(throughput) + marginText(throughput, video)
        )
        left.add(context.getString(R.string.osd_playback) + " " + playbackText(exo))
        val footer = context.getString(R.string.osd_config) + " " + configText(videoView, exo)
        left.add(
            context.getString(R.string.osd_conclusion) + " "
                + context.getString(if (state.playState == VideoView.STATE_ERROR) R.string.osd_abnormal else R.string.osd_normal)
        )

        right.add(
            context.getString(R.string.osd_device) + " " + Build.MODEL + " / " + Build.DEVICE + " / "
                + (Build.SUPPORTED_ABIS.firstOrNull() ?: "-")
        )
        right.add(context.getString(R.string.osd_system) + " Android " + Build.VERSION.RELEASE + " / SDK " + Build.VERSION.SDK_INT)
        right.add(context.getString(R.string.osd_chip) + " " + chipText())
        right.add(context.getString(R.string.osd_screen) + " " + screenText())
        right.add("WebView " + webViewText())
        right.add(context.getString(R.string.osd_network_env) + " " + networkEnvText())

        state.infoOsdLeft = left
        state.infoOsdRight = right
        state.infoOsdFooter = footer
    }

    private fun videoText(format: Format?, exo: ExoPlayer?): String {
        if (format == null) return "-"
        val parts = ArrayList<String>()
        parts.add(videoCodecName(format))
        if (format.width > 0 && format.height > 0) parts.add(format.width.toString() + "x" + format.height)
        val fps = frameRateText(format, exo)
        if (fps.isNotEmpty()) parts.add(fps)
        val bitrate = bitrateText(format.bitrate.toLong())
        if (bitrate.isNotEmpty()) parts.add(bitrate)
        val codecs = format.codecs
        if (!codecs.isNullOrEmpty()) parts.add(codecs)
        return parts.joinToString(" · ")
    }

    private fun frameRateText(format: Format, exo: ExoPlayer?): String {
        if (format.frameRate > 0f) return format.frameRate.toInt().toString() + "fps"
        val measured = exo?.measuredFrameRate() ?: 0f
        if (measured <= 0f) return ""
        return String.format(Locale.US, "%.1ffps", measured)
    }

    private fun videoCodecName(format: Format): String = when (format.sampleMimeType) {
        MimeTypes.VIDEO_H264 -> "H.264"
        MimeTypes.VIDEO_H265 -> "H.265"
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.VIDEO_MP4V -> "MPEG-4"
        else -> format.sampleMimeType?.substringAfter('/')?.uppercase(Locale.US) ?: "-"
    }

    private fun audioText(format: Format?): String {
        if (format == null) return "-"
        val parts = ArrayList<String>()
        val codecs = format.codecs
        parts.add(if (!codecs.isNullOrEmpty()) codecs else format.sampleMimeType?.substringAfter('/')?.uppercase(Locale.US) ?: "-")
        if (format.channelCount > 0) parts.add(format.channelCount.toString() + ".0")
        if (format.sampleRate > 0) {
            val khz = format.sampleRate / 1000f
            parts.add((if (khz % 1f == 0f) khz.toInt().toString() else String.format(Locale.US, "%.1f", khz)) + "kHz")
        }
        return parts.joinToString(" · ")
    }

    private fun bitrateText(bps: Long): String {
        if (bps <= 0) return ""
        return String.format(Locale.US, "%.1fMbps", bps / 1000000f)
    }

    private fun marginText(throughput: Long, format: Format?): String {
        val bitrate = format?.bitrate ?: 0
        if (throughput <= 0 || bitrate <= 0) return ""
        return " · x" + String.format(Locale.US, "%.2f", throughput.toFloat() / bitrate)
    }

    private fun playbackText(exo: ExoPlayer?): String {
        val parts = ArrayList<String>()
        parts.add(context.getString(playStateRes()))
        parts.add(timeText(state.position) + " / " + timeText(state.duration))
        parts.add(context.getString(R.string.osd_dropped_frames, exo?.droppedFrames() ?: 0L))
        parts.add(context.getString(R.string.osd_rebuffer, exo?.rebufferCount() ?: 0))
        return parts.joinToString(" · ")
    }

    private fun playStateRes(): Int = when (state.playState) {
        VideoView.STATE_BUFFERING -> R.string.osd_state_buffering
        VideoView.STATE_PLAYING -> R.string.osd_state_playing
        VideoView.STATE_PAUSED -> R.string.osd_state_paused
        VideoView.STATE_PLAYBACK_COMPLETED -> R.string.osd_state_ended
        VideoView.STATE_ERROR -> R.string.osd_abnormal
        else -> R.string.osd_state_ready
    }

    private fun timeText(millis: Int): String {
        if (millis <= 0) return "00:00"
        val seconds = millis / 1000
        return String.format(Locale.US, "%02d:%02d", seconds / 60, seconds % 60)
    }

    private fun decodeText(exo: ExoPlayer?): String {
        val name = exo?.videoDecoderName()?.takeIf { it.isNotEmpty() } ?: return "-"
        val software = name.startsWith("c2.android.") || name.startsWith("OMX.google.") ||
            name.startsWith("OMX.ffmpeg.") || name.contains(".sw.")
        return context.getString(if (software) R.string.player_decode_soft else R.string.player_decode_hard)
    }

    private fun configText(videoView: MyVideoView?, exo: ExoPlayer?): String {
        val parts = ArrayList<String>()
        parts.add(context.getString(R.string.player_exo))
        parts.add(decodeText(exo))
        parts.add(if (videoView?.isSurfaceRenderActive == true) "Surface" else "Texture")
        parts.add(context.getString(R.string.osd_tunnel) + " " + onOffText(exo?.isTunnelingEnabled == true))
        parts.add(context.getString(R.string.osd_frame_rate_match) + " " + onOffText(false))
        parts.add(context.getString(R.string.osd_preload) + " " + onOffText(KV.get(HawkConfig.PRELOAD_NEXT_EPISODE, false) == true))
        parts.add(context.getString(R.string.osd_cache) + " " + onOffText(KV.get(HawkConfig.PLAY_CACHE, false) == true))
        return parts.joinToString(" · ")
    }

    private fun onOffText(on: Boolean): String = context.getString(if (on) R.string.common_on else R.string.common_off)

    private fun chipText(): String {
        val parts = ArrayList<String>()
        if (Build.VERSION.SDK_INT >= 31) {
            Build.SOC_MODEL?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        }
        Build.HARDWARE?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        Build.BOARD?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        return if (parts.isEmpty()) "-" else parts.joinToString(" / ")
    }

    @Suppress("DEPRECATION")
    private fun screenText(): String {
        val display = mActivity?.windowManager?.defaultDisplay ?: return "-"
        val parts = ArrayList<String>()
        val mode = display.mode
        if (mode != null) {
            parts.add(mode.physicalWidth.toString() + "x" + mode.physicalHeight)
        } else {
            parts.add(resources.displayMetrics.widthPixels.toString() + "x" + resources.displayMetrics.heightPixels)
        }
        parts.add(String.format(Locale.US, "%.0fHz", display.refreshRate))
        return parts.joinToString(" · ")
    }

    private fun webViewText(): String {
        if (Build.VERSION.SDK_INT < 26) return "-"
        return WebView.getCurrentWebViewPackage()?.versionName ?: "-"
    }

    private fun networkEnvText(): String {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return "-"
        val network = manager.activeNetwork ?: return "offline"
        val capabilities = manager.getNetworkCapabilities(network) ?: return "offline"
        val type = when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "WiFi"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Cellular"
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
            else -> "Other"
        }
        val validated = if (capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) "validated" else "unvalidated"
        return type + " / " + validated + (if (manager.isActiveNetworkMetered) " metered" else " unmetered")
    }

    private fun readBattery() {
        runCatching {
            val bm = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
                ?: return
            val level = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            state.batteryPercent = if (level in 0..100) level else -1
            val status = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_STATUS)
            state.batteryCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
        }
    }

    override fun hideSeekHint() {
        state.seekHintVisible = false
    }

    override fun hideSlideHint() {
        state.slideHintVisible = false
    }

    private fun showScaleDialog() {
        try {
            val cfg = playerConfig ?: return
            val scaleType = cfg.getInt("sc")
            val scales = ArrayList<String>()
            for (i in 0..5) {
                scales.add(PlayerHelper.getScaleName(i))
            }
            state.selectDialog = SelectDialogState(
                tip = context.getString(R.string.player_select_scale),
                items = scales,
                defaultIndex = scaleType.coerceIn(0, 5),
                onSelected = { index -> applyScale(index) },
            )
        } catch (e: JSONException) {
            LOG.e("ComposeVideoController", e)
        }
    }

    private fun showSpeedDialog() {
        try {
            val cfg = playerConfig ?: return
            val speed = cfg.getDouble("sp").toFloat()
            val speeds = speedOptions.map { "${it}x" }
            state.selectDialog = SelectDialogState(
                tip = context.getString(R.string.player_select_speed),
                items = speeds,
                defaultIndex = speedIndex(speed),
                onSelected = { index -> applySpeed(speedOptions[index]) },
            )
        } catch (e: JSONException) {
            LOG.e("ComposeVideoController", e)
        }
    }
}
