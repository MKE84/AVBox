package com.github.tvbox.osc.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.grid.itemsIndexed as gridItemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.ui.components.LocalSheetDismiss
import com.github.tvbox.osc.ui.components.VodCard
import com.github.tvbox.osc.ui.components.AVBoxBottomSheet
import com.github.tvbox.osc.ui.theme.filterChipColors
import com.github.tvbox.osc.ui.page.openVodCardOrDetail

/** 分区标题前的裸图标(22dp、onSurface 着色):画稿图标与内置图标共用 */
@Composable
internal fun SectionTitleIcon(painter: Painter) {
    Icon(
        painter = painter,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(22.dp),
    )
}

@Composable
internal fun SectionTitleIcon(imageVector: ImageVector) {
    Icon(
        imageVector = imageVector,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurface,
        modifier = Modifier.size(22.dp),
    )
}

@Composable
internal fun SourceSection(vm: DetailViewModel, currentSourceName: String?, revision: Int) {
    @Suppress("UNUSED_EXPRESSION") revision
    val sourceChips by vm.sourceChips.collectAsState()
    val sourcesSearching by vm.sourcesSearching.collectAsState()
    if (!sourcesSearching && sourceChips.isEmpty()) return
    // 入口卡片:点击弹出换源半面板(网格小卡片)
    Column(
        modifier = Modifier
            .padding(start = 6.dp, end = 6.dp, top = 12.dp)
            .background(MaterialTheme.colorScheme.surfaceBright, RoundedCornerShape(16.dp))
            .clickable { vm.showSourceSheet() }
            .padding(vertical = 12.dp),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitleIcon(painterResource(R.drawable.ic_detail_switch_source))
            Text(
                text = stringResource(R.string.detail_switch_source),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .weight(1f)
                    .padding(start = 8.dp),
            )
            // 当前源名(若有) + 计数
            Column(
                modifier = Modifier.weight(1f),
                horizontalAlignment = Alignment.End,
            ) {
                if (currentSourceName != null) {
                    Text(
                        text = currentSourceName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = if (sourcesSearching) {
                        stringResource(R.string.detail_matched_sources_searching, sourceChips.size)
                    } else {
                        stringResource(R.string.detail_matched_sources, sourceChips.size)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 换源半面板:点入口卡片弹出,网格小卡片,每源一格,点卡片切换 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SourceSheet(vm: DetailViewModel, revision: Int, slideFromEnd: Boolean, currentSourceName: String?) {
    @Suppress("UNUSED_EXPRESSION") revision
    val show by vm.sourceSheet.collectAsState()
    if (!show) return
    val sourceChips by vm.sourceChips.collectAsState()

    val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()
    val dismissAnimated = LocalSheetDismiss.current

    AVBoxBottomSheet(
        onDismissRequest = { vm.dismissSourceSheet() },
        title = stringResource(R.string.detail_switch_source),
        isScrollable = false,
        slideFromEnd = slideFromEnd,
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // 当前片源固定一行(备用源列表不含当前源,名字由参数传入)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.detail_current_source),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(modifier = Modifier.weight(1f))
                if (!currentSourceName.isNullOrEmpty()) {
                    Text(
                        text = currentSourceName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            val gridColumnCount = 3
            val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding() + 16.dp
            androidx.compose.foundation.lazy.grid.LazyVerticalGrid(
                state = gridState,
                columns = androidx.compose.foundation.lazy.grid.GridCells.Fixed(gridColumnCount),
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    bottom = bottomInset,
                ),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                gridItemsIndexed(sourceChips, key = { _, c -> c.key }) { _, chip ->
                    val accent = sourceChipAccent(chip.latency)
                    FilterChip(
                        selected = false,
                        onClick = {
                            vm.candidateForKey(chip.key)?.let { vm.switchSource(it) }
                            dismissAnimated()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp),
                        label = {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(
                                    text = chip.name,
                                    maxLines = 1,
                                    softWrap = false,
                                    textAlign = TextAlign.Center,
                                    fontSize = 13.sp,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = when {
                                        chip.latency >= 0 && chip.latency < 86400000L ->
                                            "%.2f秒".format(chip.latency / 1000.0)
                                        else -> "-"
                                    },
                                    maxLines = 1,
                                    fontSize = 11.sp,
                                    color = accent,
                                )
                            }
                        },
                        contentPadding = PaddingValues(horizontal = 6.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = MaterialTheme.colorScheme.filterChipColors(),
                    )
                }
            }
        }
    }
}

/** 按延迟给源的耗时小字配色 */
@Composable
private fun sourceChipAccent(latency: Long): Color {
    return when {
        latency < 0 -> MaterialTheme.colorScheme.onSurfaceVariant
        latency < 300 -> MaterialTheme.colorScheme.tertiary
        latency < 800 -> Color(0xFFB5A642)
        else -> MaterialTheme.colorScheme.error
    }
}

@Composable
internal fun RelatedSection(
    activity: DetailActivity,
    vm: DetailViewModel,
    onCardLongClick: (Movie.Video) -> Unit = {},
) {
    val relatedVideos by vm.relatedVideos.collectAsState()
    if (relatedVideos.isEmpty()) return
    Column(modifier = Modifier.padding(top = 20.dp)) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
        ) {
            SectionTitleIcon(painterResource(R.drawable.ic_detail_recommend))
            Text(
                text = stringResource(R.string.detail_recommend),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            itemsIndexed(
                relatedVideos,
                key = { _, v -> (v.sourceKey ?: "") + "|" + (v.id ?: "") },
            ) { _, video ->
                VodCard(
                    video = video,
                    onClick = { activity.openVodCardOrDetail(video) },
                    onLongClick = { onCardLongClick(video) },
                    modifier = Modifier.width(110.dp),
                )
            }
        }
    }
}

private val CR_LINK_REGEX = Regex("\\[a=cr:(?:\\{.*?\\}|\\[.*?\\])/](.*?)\\[/a]")
private val WHITESPACE_REGEX = Regex("\\s")

internal fun removeHtmlTag(info: String?): String {
    if (info.isNullOrEmpty()) return ""
    var text = info.replace(CR_LINK_REGEX, "$1")
    text = android.text.Html.fromHtml(text, android.text.Html.FROM_HTML_MODE_LEGACY).toString()
    return text.replace(WHITESPACE_REGEX, "")
}
