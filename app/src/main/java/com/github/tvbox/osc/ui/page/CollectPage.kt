@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.github.tvbox.osc.ui.page

import android.content.Context
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.data.RoomDataManger
import com.github.tvbox.osc.data.VodCollect
import com.github.tvbox.osc.event.RefreshEvent
import com.github.tvbox.osc.ui.WindowSize
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.LoadStateBox
import com.github.tvbox.osc.ui.components.VodPoster
import com.github.tvbox.osc.util.HawkConfig
import com.github.tvbox.osc.util.KV
import com.github.tvbox.osc.util.SubscribeList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.greenrobot.eventbus.EventBus
import org.greenrobot.eventbus.Subscribe
import org.greenrobot.eventbus.ThreadMode

class CollectViewModel : ViewModel() {
    val loading = MutableStateFlow(true)
    val items = MutableStateFlow<List<VodCollect>>(emptyList())

    /** 收藏页栅格列数(固定 3 列) */
    val columns = MutableStateFlow(3)

    /** 站点不在当前订阅的收藏源 key(收藏不落站名,可用性只能按当前订阅现判,切订阅后要重算) */
    val unavailableKeys = MutableStateFlow<Set<String>>(emptySet())

    init {
        EventBus.getDefault().register(this)
        refresh()
        // 站点可用性要等配置就绪才有意义:换订阅后的重算挂在这里,挂 TYPE_API_URL_CHANGE 时新配置还没解析
        viewModelScope.launch {
            AppBootstrap.state.collect { boot ->
                if (boot is AppBootstrap.Boot.Ready) {
                    viewModelScope.launch(Dispatchers.IO) { recomputeUnavailableNow() }
                }
            }
        }
    }

    override fun onCleared() {
        EventBus.getDefault().unregister(this)
    }

    val scrollSignal = MutableStateFlow(0)

    val placementAnim = MutableStateFlow(false)

    fun refresh(scrollToTop: Boolean = false) {
        if (items.value.isEmpty()) loading.value = true
        if (scrollToTop) placementAnim.value = false
        viewModelScope.launch(Dispatchers.IO) {
            items.value = RoomDataManger.getAllVodCollect()
            recomputeUnavailableNow()
            loading.value = false
            if (scrollToTop) scrollSignal.value++
        }
    }

    /** 配置未就绪时 getSource 全为空,此刻判定会把所有收藏误标成不可用 —— 直接跳过,等 Ready 再算 */
    private fun recomputeUnavailableNow() {
        if (AppBootstrap.state.value !is AppBootstrap.Boot.Ready) return
        unavailableKeys.value = computeUnavailable(items.value)
    }

    /** 订阅已不在列表、或当前订阅里站点没了,都算打不开(其他订阅的收藏要先切回去才知道) */
    private fun computeUnavailable(list: List<VodCollect>): Set<String> {
        val current = RoomDataManger.currentCid()
        val known = SubscribeList.vodUrls()
        return list.filter { item ->
            val cid = item.cid.orEmpty()
            if (cid.isNotEmpty() && !known.contains(cid)) {
                true
            } else {
                (cid.isEmpty() || cid == current) && ApiConfig.get().getSource(item.sourceKey) == null
            }
        }.mapNotNull { it.sourceKey }.toSet()
    }

    @Subscribe(threadMode = ThreadMode.MAIN)
    fun onRefreshEvent(event: RefreshEvent) {
        if (event.type == RefreshEvent.TYPE_COLLECT_REFRESH) {
            refresh(scrollToTop = true)
        } else if (event.type == RefreshEvent.TYPE_API_URL_CHANGE) {
            viewModelScope.launch(Dispatchers.IO) { recomputeUnavailableNow() }
        }
    }

    fun deleteSelected(list: List<VodCollect>) {
        if (list.isEmpty()) return
        placementAnim.value = true
        viewModelScope.launch(Dispatchers.IO) {
            list.forEach { item -> RoomDataManger.deleteVodCollect(item.id) }
            refresh()
        }
    }

  
    fun deleteAll() {
        viewModelScope.launch(Dispatchers.IO) {
            RoomDataManger.deleteVodCollectAll()
            refresh()
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CollectPage(
    vm: CollectViewModel = viewModel(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    // 页面保持全出血(背景延伸到导航栏之下,玻璃才有内容可取),只把内容让开
    val navStart = contentPadding.calculateStartPadding(LocalLayoutDirection.current)
    val navBottom = contentPadding.calculateBottomPadding()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val items by vm.items.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val placementAnim by vm.placementAnim.collectAsStateWithLifecycle()
    val unavailableKeys by vm.unavailableKeys.collectAsStateWithLifecycle()
    val columns by vm.columns.collectAsStateWithLifecycle()
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<VodCollect?>(null) }
    var editMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<Int>()) }

    val listState = rememberLazyGridState()

    fun exitEdit() {
        editMode = false
        selected = emptySet()
    }

    BackHandler(enabled = editMode) { exitEdit() }

    LaunchedEffect(items) {
        val keys = items.map { it.id }.toSet()
        val pruned = selected.intersect(keys)
        if (pruned.size != selected.size) selected = pruned
        if (items.isEmpty()) editMode = false
    }

    LaunchedEffect(vm) {
        vm.scrollSignal.collect {
            if (items.isNotEmpty()) listState.animateScrollToItem(0)
        }
    }

    AppTopBarScaffold(
        topBarStartInset = navStart,
        titleContent = {},
        actions = {
            AnimatedContent(
                targetState = editMode && items.isNotEmpty(),
                transitionSpec = {
                    fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMedium)) togetherWith
                        fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMedium))
                },
                label = "collectTopAction",
            ) { editing ->
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (editing) {
                        ManageActionIcon(
                            iconRes = R.drawable.ic_check,
                            contentDescription = stringResource(R.string.common_done),
                            onClick = { exitEdit() },
                        )
                        ManageActionIcon(
                            iconRes = R.drawable.ic_delete,
                            contentDescription = stringResource(R.string.common_delete_selected),
                            enabled = selected.isNotEmpty(),
                            onClick = { showDeleteSelectedDialog = true },
                        )
                    } else {
                        if (items.isNotEmpty()) {
                            ManageActionIcon(
                                iconRes = R.drawable.ic_edit,
                                contentDescription = stringResource(R.string.common_edit),
                                onClick = { editMode = true },
                            )
                        }
                        ManageActionIcon(
                            iconRes = R.drawable.ic_delete,
                            contentDescription = stringResource(R.string.collect_clear),
                            onClick = { showDeleteAllDialog = true },
                        )
                    }
                }
            }
        },
    ) { topPad, _ ->
        when {
            loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topPad),
                contentAlignment = Alignment.Center,
            ) {
                ContainedLoadingIndicator(Modifier.size(64.dp))
            }

            else -> AnimatedContent(
                targetState = items,
                contentKey = { it.isEmpty() },
                transitionSpec = {
                    fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) togetherWith
                        fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
                },
                label = "collectContent",
            ) { list ->
                if (list.isEmpty()) {
                    LoadStateBox(
                        state = com.github.tvbox.osc.ui.components.LoadState.Empty,
                        emptyText = stringResource(R.string.collect_empty),
                        errorText = "",
                        retryText = "",
                        emptyIconRes = R.drawable.ic_empty_record,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = topPad),
                    )
                } else {
                    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                        val gridColumns = WindowSize.gridColumns(
                            availableWidthDp = (maxWidth - 32.dp - navStart).value.toInt(),
                            minColumns = columns,
                        )
                        LazyVerticalGrid(
                            state = listState,
                            columns = GridCells.Fixed(gridColumns),
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(
                                start = 16.dp + navStart,
                                end = 16.dp,
                                top = topPad + 8.dp,
                                bottom = 8.dp + navBottom,
                            ),
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                        ) {
                            items(list, key = { it.id }) { item ->
                                val toggle = {
                                    selected = if (item.id in selected) selected - item.id else selected + item.id
                                }
                                CollectCard(
                                    item = item,
                                    unavailable = unavailableKeys.contains(item.sourceKey),
                                    editMode = editMode,
                                    selected = item.id in selected,
                                    modifier = Modifier.animateItem(
                                        fadeInSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                        placementSpec = if (placementAnim) {
                                            spring(stiffness = Spring.StiffnessMediumLow)
                                        } else {
                                            null
                                        },
                                        fadeOutSpec = spring(stiffness = Spring.StiffnessMediumLow),
                                    ),
                                    onClick = {
                                        if (editMode) {
                                            toggle()
                                        } else {
                                            val cid = item.cid.orEmpty()
                                            when {
                                                cid.isEmpty() || cid == RoomDataManger.currentCid() ->
                                                    context.jumpToDetail(
                                                        item.vodId, item.sourceKey, item.name, item.pic, collect = true,
                                                    )

                                                SubscribeList.vodUrls().contains(cid) ->
                                                    scope.launch { reopenViaSubscription(context, item) }

                                                else -> context.jumpToSearch(item.name.orEmpty())
                                            }
                                        }
                                    },
                                    onLongClick = {
                                        if (editMode) {
                                            toggle()
                                        } else {
                                            deleteTarget = item
                                        }
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteAllDialog) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.collect_clear),
            text = stringResource(R.string.collect_clear_message),
            onConfirm = { vm.deleteAll() },
            onDismiss = { showDeleteAllDialog = false },
        )
    }
    if (showDeleteSelectedDialog) {
        val targets = items.filter { it.id in selected }
        ConfirmDeleteDialog(
            title = stringResource(R.string.common_delete_selected),
            text = stringResource(R.string.collect_delete_selected_message, targets.size),
            onConfirm = {
                vm.deleteSelected(targets)
                exitEdit()
            },
            onDismiss = { showDeleteSelectedDialog = false },
        )
    }
    deleteTarget?.let { target ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.detail_uncollect),
            text = stringResource(
                R.string.collect_uncollect_message,
                target.name ?: stringResource(R.string.common_unnamed),
            ),
            onConfirm = { vm.deleteSelected(listOf(target)) },
            onDismiss = { deleteTarget = null },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CollectCard(
    item: VodCollect,
    unavailable: Boolean,
    editMode: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(2f / 3f)
            .clip(RoundedCornerShape(16.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        VodPoster(name = item.name, pic = item.pic, modifier = Modifier.fillMaxSize())
        if (editMode) {
            SelectCircle(
                selected = selected,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(8.dp),
            )
        }
        if (unavailable) {
            Text(
                text = stringResource(R.string.source_unavailable),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                maxLines = 1,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(8.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.error.copy(alpha = 0.85f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
            )
        }
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0.5f to Color.Transparent,
                        1f to Color.Black.copy(alpha = 0.75f),
                    )
                ),
        )
        Text(
            text = item.name ?: "",
            style = MaterialTheme.typography.titleLarge,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp),
        )
    }
}

private const val SWITCH_SUBSCRIBE_TIMEOUT_MS = 20_000L

/** 收藏属于别的订阅:切回原订阅、等配置就绪再进详情;订阅已失效则退回按片名搜索 */
private suspend fun reopenViaSubscription(context: Context, item: VodCollect) {
    Toast.makeText(context, context.getString(R.string.detail_switching_source), Toast.LENGTH_SHORT).show()
    AppBootstrap.switchVodSubscription(item.cid.orEmpty())
    val ready = withTimeoutOrNull(SWITCH_SUBSCRIBE_TIMEOUT_MS) {
        AppBootstrap.state.first { it is AppBootstrap.Boot.Ready || it is AppBootstrap.Boot.Error }
    } is AppBootstrap.Boot.Ready
    if (ready && ApiConfig.get().getSource(item.sourceKey) != null) {
        context.jumpToDetail(item.vodId, item.sourceKey, item.name, item.pic, collect = true)
    } else {
        context.jumpToSearch(item.name.orEmpty())
    }
}
