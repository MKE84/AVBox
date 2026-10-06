package com.github.tvbox.osc.ui.page

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.github.tvbox.osc.R
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.ui.activity.ConfigManageActivity
import com.github.tvbox.osc.ui.components.AVBoxAlertDialog
import com.github.tvbox.osc.ui.components.AVBoxBottomSheet
import com.github.tvbox.osc.ui.components.LoadState
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.LoadStateBox
import com.github.tvbox.osc.ui.components.LocalSheetDismiss
import com.github.tvbox.osc.ui.components.LocalSheetDismissThen
import com.github.tvbox.osc.ui.components.SettingsCard
import com.github.tvbox.osc.ui.components.SettingsCardPosition
import com.github.tvbox.osc.ui.components.SettingsGroup
import com.github.tvbox.osc.ui.components.RowLeadingIcon
import com.github.tvbox.osc.ui.components.SettingsOptionRow
import com.github.tvbox.osc.ui.components.SettingsSwitch
import com.github.tvbox.osc.ui.components.TopBarActionBox
import com.github.tvbox.osc.ui.components.glassSurface
import com.github.tvbox.osc.ui.theme.cardContainer
import com.github.tvbox.osc.util.HistoryHelper

private fun badgeText(name: String, url: String, emptyText: String): String = when {
    name.isNotEmpty() -> name
    url.isEmpty() -> emptyText
    else -> url.substringAfter("://").substringBefore('/').ifEmpty { url }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun ConfigManageScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    val vm: ConfigManageViewModel = viewModel()
    var addDialogOpen by remember { mutableStateOf(false) }
    var repoSheetOpen by remember { mutableStateOf(false) }
    val vodItems by vm.vodItems.collectAsState()
    val activeUrl by vm.activeUrl.collectAsState()
    /** 被看门狗停用过的源地址(黑名单):只随页内增删变化 */
    val disabledUrls by vm.disabledUrls.collectAsState()
    /** 点到黑名单里的源时先挂起,由二次确认对话框决定是否放行 */
    val pendingSwitch by vm.pendingSwitch.collectAsState()
    val selected by vm.selected.collectAsState()
    val manageMode by vm.manageMode.collectAsState()
    val editTarget by vm.editTarget.collectAsState()
    val toastEvent by vm.toastEvent.collectAsState()
    /** 配置加载完成的信号:源类型统计要等源列表就绪才算得出来 */
    val bootState by AppBootstrap.state.collectAsState()

    val currentItems = vodItems

    LaunchedEffect(toastEvent) {
        toastEvent?.let {
            Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
            vm.clearToast()
        }
    }

    BackHandler(enabled = manageMode) { vm.exitManageMode() }

    // ---------- 换仓(2026-09-21) ----------
    // 多仓生效后启动地址被改写成仓里某个子源,订阅卡与"使用中"都不再指向用户填的仓地址,
    // 故需要独立入口:右上角图标 → bottom sheet。列表取与「配置切换」同一份数据,不另建状态。

    /** 当前源是否来自多仓 —— 不是仓源就没有可换的子源,入口整体隐藏 */
    val canSwitchRepo = HistoryHelper.isApiLineUrl(activeUrl)

    /** 仓里的子源条目("名字\t链接") */
    val repoEntries = HistoryHelper.getApiLines()

    /** 当前生效的子源地址:换仓列表据此打选中标记 */
    val repoActiveUrl = activeUrl

    val noSourceText = stringResource(R.string.config_no_source)
    val vodBadge = remember(vodItems, activeUrl, noSourceText) {
        badgeText(
            vodItems.firstOrNull { parseSubscribe(it).url == activeUrl }?.let { parseSubscribe(it).name }.orEmpty(),
            activeUrl,
            noSourceText,
        )
    }

    AppTopBarScaffold(
        collapseEnabled = false,
        titleContent = {
            Text(
                text = stringResource(R.string.settings_config_manage),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        navigationIcon = {
            TopBarActionBox(
                R.drawable.ic_arrow_left,
                stringResource(R.string.common_back),
                onClick = { if (manageMode) vm.exitManageMode() else onNavigateBack() },
            )
        },
        actions = {
            AnimatedContent(
                targetState = manageMode && currentItems.isNotEmpty(),
                transitionSpec = {
                    (fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMedium)) +
                        scaleIn(initialScale = 0.8f, animationSpec = spring(stiffness = Spring.StiffnessMedium)))
                        .togetherWith(fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMedium)))
                },
                label = "configTopAction",
            ) { managing ->
                if (managing) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ManageActionIcon(
                            iconRes = R.drawable.ic_edit,
                            contentDescription = stringResource(R.string.common_edit),
                            enabled = selected.size == 1,
                            onClick = { vm.editTarget.value = selected.firstOrNull()?.let { parseSubscribe(it) } },
                        )
                        ManageActionIcon(
                            iconRes = R.drawable.ic_delete,
                            contentDescription = stringResource(R.string.common_delete),
                            enabled = selected.isNotEmpty(),
                            onClick = { vm.deleteSelected() },
                        )
                    }
                } else {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 「换仓」入口(2026-09-21):仅在**当前源来自多仓**时出现 ——
                        // 不是仓源时没有可换的子源,按钮出现只会让人白点一次。
                        if (canSwitchRepo) {
                            TopBarActionBox(
                                iconRes = R.drawable.ic_switch_repo,
                                contentDescription = stringResource(R.string.config_switch_repo),
                                onClick = { repoSheetOpen = true },
                            )
                        }
                        TopBarActionBox(
                            iconRes = R.drawable.ic_subscribe_add,
                            contentDescription = stringResource(R.string.config_add_subscribe),
                            onClick = { addDialogOpen = true },
                        )
                    }
                }
            }
        },
    ) { topPad, _ ->
        Column(modifier = Modifier.fillMaxSize()) {
            if (currentItems.isEmpty()) {
                LoadStateBox(
                    state = LoadState.Empty,
                    emptyText = stringResource(R.string.config_empty_subscribe),
                    errorText = "",
                    retryText = "",
                    emptyIconRes = R.drawable.ic_empty_record,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                val mOrdered = remember(currentItems, activeUrl) {
                    currentItems.sortedByDescending { parseSubscribe(it).url == activeUrl }
                }
                // 当前源的类型分布(py/js/jar):在 composable 作用域算好,不能在 LazyListScope 里调 remember
                val typeStats = remember(activeUrl, bootState) { currentSourceTypeStats() }
                LazyColumn(
                    state = rememberLazyListState(),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 16.dp,
                        end = 16.dp,
                        top = topPad + 8.dp,
                        bottom = 8.dp,
                    ),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    if (typeStats.spider > 0) {
                        item(key = "__source_type_stats__") {
                            SourceTypeSummaryRow(stats = typeStats)
                        }
                    }
                    items(mOrdered, key = { it }) { value ->
                        val item = parseSubscribe(value)
                        // 2026-09-21 多仓:与上面 isInUse 同一套判定 —— 之前只比地址本身,
                        // 点了带"使用中"标记的仓卡会因为 activeUrl(仓地址)与 API_URL(仓里首条)
                        // 不等而误判成"未使用",再点一次又白跑一遍完整换源流程
                        val inUse = item.url == activeUrl || HistoryHelper.isApiLineSourceOf(item.url, activeUrl)
                        SubscribeCard(
                            modifier = Modifier.animateItem(),
                            item = item,
                            active = inUse,
                            disabled = item.url in disabledUrls,
                            manageMode = manageMode,
                            selected = value in selected,
                            onClick = {
                                if (manageMode) {
                                    vm.toggleSelected(value)
                                } else {
                                    vm.requestSwitch(item)
                                }
                            },
                            onLongClick = {
                                vm.longPressSelect(value)
                            },
                            onCheckedChange = { checked ->
                                if (checked) vm.requestSwitch(item)
                            },
                        )
                    }
                }
            }
        }
    }

    val editing = editTarget
    if (addDialogOpen || editing != null) {
        AddSubscribeDialog(
            title = if (editing != null) {
                stringResource(R.string.config_edit_subscribe)
            } else {
                stringResource(R.string.config_add_subscribe)
            },
            urlSupportingText = "",
            initialName = editing?.name.orEmpty(),
            initialUrl = editing?.url.orEmpty(),
            onDismiss = {
                addDialogOpen = false
                vm.editTarget.value = null
            },
            onSave = { name, url ->
                if (editing != null) vm.commitEdit(editing, name, url) else vm.commitAdd(name, url)
                addDialogOpen = false
            },
            onPickFile = { onPicked ->
                (context as? ConfigManageActivity)?.launchLocalConfig { api -> onPicked(api) }
            },
        )
    }

    val pending = pendingSwitch
    if (pending != null) {
        AVBoxAlertDialog(
            onDismissRequest = { vm.cancelPendingSwitch() },
            title = { Text(stringResource(R.string.dialog_source_disabled_title)) },
            text = {
                Text(stringResource(R.string.dialog_source_disabled_message, pending.item.name))
            },
            confirmButton = {
                val dismissThen = LocalSheetDismissThen.current
                TextButton(onClick = { dismissThen { vm.enableAndSwitch() } }) {
                    Text(stringResource(R.string.dialog_source_disabled_confirm))
                }
            },
            dismissButton = {
                val dismissAnimated = LocalSheetDismiss.current
                TextButton(onClick = { dismissAnimated() }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (repoSheetOpen) {
        RepoSwitchSheet(
            entries = repoEntries,
            activeUrl = repoActiveUrl,
            disabledUrls = disabledUrls,
            onDismiss = { repoSheetOpen = false },
            onSelect = { url ->
                val name = HistoryHelper.getApiLineName(
                    repoEntries.firstOrNull { HistoryHelper.getApiLineUrl(it) == url }.orEmpty(),
                )
                // 与在订阅列表里点同一条源等价 —— switchToVod 里已经处理了"是否落在仓里"的仓列表保留判定,
                // 所以换完仓后入口仍在。统一走 requestSwitch:仓里藏着的坏子源同样要过二次确认
                vm.requestSwitch(SubscribeSource(name, url))
                // 命中"源已停用"时 requestSwitch 会立刻弹确认对话框,而覆盖层槽位只有一个(面板会被顶掉)。
                // 这里同步收掉面板状态:否则面板的可见性标志还是 true,对话框关掉后它会被重新提交而"复活"。
                if (vm.pendingSwitch.value != null) repoSheetOpen = false
            },
        )
    }
}

/**
 * 「换仓」bottom sheet:列出当前仓里的全部子源,点一条即切换。
 *
 * <p>样式同 `AVBoxOptionSheet`,但每条多带一行地址 —— 仓里常有同名子源,只给名字分不清。
 * 被看门狗停用过的子源额外打「已禁用」标记(坏子源通常就藏在仓里,不标出来用户只会觉得"点了没反应")。
 */
@Composable
private fun RepoSwitchSheet(
    entries: List<String>,
    activeUrl: String,
    disabledUrls: Set<String>,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
) {
    val dismissAnimated = LocalSheetDismiss.current
    // 防连点(与 AVBoxOptionSheet 同款)
    var accepted by remember { mutableStateOf(false) }
    AVBoxBottomSheet(
        onDismissRequest = onDismiss,
        title = stringResource(R.string.config_switch_repo),
        containerColor = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        SettingsGroup(
            title = null,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
        ) {
            entries.forEachIndexed { index, entry ->
                val url = HistoryHelper.getApiLineUrl(entry)
                SettingsCard(
                    position = when {
                        entries.size <= 1 -> SettingsCardPosition.SINGLE
                        index == 0 -> SettingsCardPosition.FIRST
                        index == entries.size - 1 -> SettingsCardPosition.LAST
                        else -> SettingsCardPosition.MIDDLE
                    },
                    color = MaterialTheme.colorScheme.surfaceBright,
                ) {
                    SettingsOptionRow(
                        title = HistoryHelper.getApiLineName(entry),
                        selected = url == activeUrl,
                        onClick = onClick@{
                            // 先吃掉点击并关面板:点"当前已选中"那条时切换逻辑会直接返回,
                            // 把关闭放进守卫里会让面板卡住关不掉。
                            if (accepted) return@onClick
                            accepted = true
                            if (url.isNotEmpty() && url != activeUrl) onSelect(url)
                            // 只走动画关闭(它播完才回调 onDismiss);这里再置 repoSheetOpen=false 会把面板先拆掉
                            dismissAnimated()
                        },
                        trailing = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (url in disabledUrls) {
                                    DisabledSourceTag()
                                    Spacer(Modifier.width(8.dp))
                                }
                                Text(
                                    text = url,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.widthIn(max = 180.dp),
                                )
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun SubscribeCard(
    item: SubscribeSource,
    active: Boolean,
    disabled: Boolean,
    manageMode: Boolean,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onCheckedChange: (Boolean) -> Unit,
) {
    val shape = RoundedCornerShape(28.dp)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = shape,
        color = MaterialTheme.colorScheme.cardContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            RowLeadingIcon(R.drawable.ic_subscribe_source, enabled = true)
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = item.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (disabled) {
                        Spacer(Modifier.width(8.dp))
                        DisabledSourceTag()
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = item.url,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(12.dp))
            AnimatedContent(
                targetState = manageMode,
                transitionSpec = {
                    (fadeIn(animationSpec = spring(stiffness = Spring.StiffnessMedium)) +
                        scaleIn(initialScale = 0.7f, animationSpec = spring(stiffness = Spring.StiffnessMedium)))
                        .togetherWith(fadeOut(animationSpec = spring(stiffness = Spring.StiffnessMedium)))
                },
                label = "configRowControl",
            ) { managing ->
                if (managing) {
                    Checkbox(checked = selected, onCheckedChange = { onClick() })
                } else {
                    SettingsSwitch(checked = active, onCheckedChange = onCheckedChange)
                }
            }
        }
    }
}

/** 被看门狗停用过的源标记:红底小圆角,贴在源名(或换仓条目的地址)旁边 */
@Composable
private fun DisabledSourceTag() {
    Surface(
        shape = RoundedCornerShape(6.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Text(
            text = stringResource(R.string.config_source_disabled_tag),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

/** 当前源的类型分布条:总数 + py/js/jar 各自数量,py/js 用彩色小标签 */
@Composable
private fun SourceTypeSummaryRow(stats: SourceTypeStats) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = stringResource(R.string.config_source_stat_total, stats.total),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SourceTypeChip("PY", stats.py, MaterialTheme.colorScheme.tertiaryContainer, MaterialTheme.colorScheme.onTertiaryContainer)
        SourceTypeChip("JS", stats.js, MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        SourceTypeChip("JAR", stats.jar, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

@Composable
private fun SourceTypeChip(label: String, count: Int, bg: androidx.compose.ui.graphics.Color, fg: androidx.compose.ui.graphics.Color) {
    if (count <= 0) return
    Surface(shape = RoundedCornerShape(6.dp), color = bg) {
        Text(
            text = "$label $count",
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun AddSubscribeDialog(
    title: String,
    urlSupportingText: String,
    initialName: String,
    initialUrl: String,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
    onPickFile: (onPicked: (String) -> Unit) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var url by remember { mutableStateOf(initialUrl) }
    val urlHint: (@Composable () -> Unit)? = if (urlSupportingText.isEmpty()) {
        null
    } else {
        {
            Text(
                text = urlSupportingText,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    AVBoxAlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    modifier = Modifier.weight(1f),
                )
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .glassSurface(CircleShape, MaterialTheme.colorScheme.surfaceBright)
                        .clickable { onPickFile { picked -> url = picked } },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_file_choose),
                        contentDescription = stringResource(R.string.config_pick_local),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.config_field_name)) },
                )
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.config_field_url)) },
                    supportingText = urlHint,
                )
            }
        },
        confirmButton = {
            val dismissThen = LocalSheetDismissThen.current
            TextButton(
                onClick = { dismissThen { onSave(name.trim(), url.trim()) } },
                enabled = url.isNotBlank(),
            ) { Text(stringResource(R.string.common_save)) }
        },
    )
}
