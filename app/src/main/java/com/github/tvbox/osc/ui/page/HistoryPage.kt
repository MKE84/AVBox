@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class,
    androidx.compose.material3.ExperimentalMaterial3Api::class,
)

package com.github.tvbox.osc.ui.page

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.VodInfo
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.LoadState
import com.github.tvbox.osc.ui.components.LoadStateBox
import com.github.tvbox.osc.util.EpisodeTotals
import com.github.tvbox.osc.util.PlaybackProgress

// 退出动画期间旧内容仍按旧快照渲染:进度/集数快照已清空时,淡出中的卡片不会丢进度条
private data class HistoryContent(
    val items: List<VodInfo>,
    val episodeTotals: Map<String, Int>,
    val playedPercents: Map<String, Int>,
)

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HistoryPage(
    vm: HistoryViewModel = viewModel(),
    contentPadding: PaddingValues = PaddingValues(0.dp),
) {
    // 页面保持全出血(背景延伸到导航栏之下,玻璃才有内容可取),只把内容让开
    val navStart = contentPadding.calculateStartPadding(LocalLayoutDirection.current)
    val navBottom = contentPadding.calculateBottomPadding()
    val context = LocalContext.current
    val items by vm.items.collectAsStateWithLifecycle()
    val loading by vm.loading.collectAsStateWithLifecycle()
    val episodeTotals by vm.episodeTotals.collectAsStateWithLifecycle()
    val playedPercents by vm.playedPercents.collectAsStateWithLifecycle()
    val placementAnim by vm.placementAnim.collectAsStateWithLifecycle()
    val incognito by vm.incognito.collectAsStateWithLifecycle()
    var showDeleteAllDialog by remember { mutableStateOf(false) }
    var showDeleteSelectedDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<VodInfo?>(null) }
    var editMode by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(emptySet<String>()) }

    val listState = rememberLazyListState()

    fun exitEdit() {
        editMode = false
        selected = emptySet()
    }

    BackHandler(enabled = editMode) { exitEdit() }

    LaunchedEffect(items) {
        val keys = items.map { HistoryViewModel.key(it) }.toSet()
        val pruned = selected.intersect(keys)
        if (pruned.size != selected.size) selected = pruned
        if (items.isEmpty()) editMode = false
    }

    LaunchedEffect(vm) {
        vm.scrollSignal.collect {
            if (items.isNotEmpty()) listState.animateScrollToItem(0)
        }
    }

    LaunchedEffect(Unit) {
        AppBootstrap.state.collect { boot ->
            if (boot == AppBootstrap.Boot.Ready) vm.resolveSourceNames()
        }
    }

    AppTopBarScaffold(
        topBarStartInset = navStart,
        titleContent = {},
        actions = {
            AnimatedContent(
                targetState = editMode && !incognito && items.isNotEmpty(),
                transitionSpec = {
                    fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMedium)) togetherWith
                        fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMedium))
                },
                label = "historyTopAction",
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
                        if (!incognito && items.isNotEmpty()) {
                            ManageActionIcon(
                                iconRes = R.drawable.ic_edit,
                                contentDescription = stringResource(R.string.common_edit),
                                onClick = { editMode = true },
                            )
                        }
                        ManageActionIcon(
                            iconRes = R.drawable.ic_delete,
                            contentDescription = stringResource(R.string.history_clear),
                            onClick = { showDeleteAllDialog = true },
                        )
                    }
                }
            }
        },
    ) { topPad, _ ->
        when {
            incognito -> LoadStateBox(
                state = LoadState.Empty,
                emptyText = stringResource(R.string.history_incognito),
                errorText = "",
                retryText = "",
                emptyIconRes = R.drawable.ic_empty_record,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topPad),
            )

            loading -> Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = topPad),
                contentAlignment = Alignment.Center,
            ) {
                ContainedLoadingIndicator(Modifier.size(64.dp))
            }

            else -> AnimatedContent(
                targetState = HistoryContent(items, episodeTotals, playedPercents),
                contentKey = { it.items.isEmpty() },
                transitionSpec = {
                    fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMediumLow)) togetherWith
                        fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
                },
                label = "historyContent",
            ) { content ->
                if (content.items.isEmpty()) {
                    LoadStateBox(
                        state = LoadState.Empty,
                        emptyText = stringResource(R.string.history_empty),
                        errorText = "",
                        retryText = "",
                        emptyIconRes = R.drawable.ic_empty_record,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = topPad),
                    )
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            start = 16.dp + navStart,
                            end = 16.dp,
                            top = topPad + 8.dp,
                            bottom = 8.dp + navBottom,
                        ),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(content.items, key = { HistoryViewModel.key(it) }) { item ->
                            val itemKey = HistoryViewModel.key(item)
                            val toggle = {
                                selected = if (itemKey in selected) selected - itemKey else selected + itemKey
                            }
                            HistoryRow(
                                item = item,
                                totalEpisodes = content.episodeTotals[
                                    EpisodeTotals.key(item.sourceKey, item.id),
                                ],
                                playedPercent = content.playedPercents[
                                    PlaybackProgress.key(item.sourceKey, item.id),
                                ],
                                editMode = editMode,
                                selected = itemKey in selected,
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
                                        context.jumpToDetail(item.id, item.sourceKey, item.name, item.pic)
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

    if (showDeleteAllDialog) {
        ConfirmDeleteDialog(
            title = stringResource(R.string.history_clear),
            text = stringResource(R.string.history_clear_message),
            onConfirm = { vm.deleteAll() },
            onDismiss = { showDeleteAllDialog = false },
        )
    }
    if (showDeleteSelectedDialog) {
        val targets = items.filter { HistoryViewModel.key(it) in selected }
        ConfirmDeleteDialog(
            title = stringResource(R.string.common_delete_selected),
            text = stringResource(R.string.history_delete_selected_message, targets.size),
            onConfirm = {
                vm.deleteSelected(targets)
                exitEdit()
            },
            onDismiss = { showDeleteSelectedDialog = false },
        )
    }
    deleteTarget?.let { target ->
        ConfirmDeleteDialog(
            title = stringResource(R.string.history_delete_title),
            text = stringResource(
                R.string.history_delete_message,
                target.name ?: stringResource(R.string.common_unnamed),
            ),
            onConfirm = { vm.deleteSelected(listOf(target)) },
            onDismiss = { deleteTarget = null },
        )
    }
}
