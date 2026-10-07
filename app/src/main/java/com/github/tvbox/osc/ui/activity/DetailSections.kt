package com.github.tvbox.osc.ui.activity

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.github.tvbox.osc.R
import com.github.tvbox.osc.bean.Movie
import com.github.tvbox.osc.ui.components.VodCard
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
    Column(
        modifier = Modifier
            .padding(start = 6.dp, end = 6.dp, top = 12.dp)
            .background(MaterialTheme.colorScheme.surfaceBright, RoundedCornerShape(16.dp))
            .padding(vertical = 12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
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
            // 已匹配到 N 个源 —— 替换原来的"寻找片源中…"动态计数
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
        if (currentSourceName != null) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SourceLine(
                    name = currentSourceName,
                    type = "",
                    latency = -1, // 当前源特殊标记:不显示耗时行,仅作为"当前片源"提示
                    isCurrent = true,
                )
                Text(
                    text = stringResource(R.string.detail_current_source),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        sourceChips.forEach { chip ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { vm.candidateForKey(chip.key)?.let { vm.switchSource(it) } }
                    .padding(horizontal = 16.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                SourceLine(
                    name = chip.name,
                    type = chip.type,
                    latency = chip.latency,
                    isCurrent = false,
                )
            }
        }
    }
}

/** 换源半面板的一行:左=源名+类型小字, 中=耗时(秒,最快标绿), 右=延迟(ms,绿/黄/红三级) */
@Composable
private fun RowScope.SourceLine(
    name: String,
    type: String,
    latency: Long,
    isCurrent: Boolean,
) {
    val accent = if (isCurrent) {
        MaterialTheme.colorScheme.primary
    } else {
        // 延迟颜色分级:<300ms 绿, 800ms 内 黄, 更慢红
        when {
            latency < 0 -> MaterialTheme.colorScheme.onSurfaceVariant
            latency < 300 -> MaterialTheme.colorScheme.tertiary
            latency < 800 -> Color(0xFFB5A642)
            else -> MaterialTheme.colorScheme.error
        }
    }
    var sourceLabel = name
    if (type.isNotEmpty()) sourceLabel = "$name.$type"
    Text(
        text = sourceLabel,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
    )
    if (!isCurrent && latency >= 0) {
        Text(
            text = "%.2f秒".format(latency / 1000.0),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 16.dp),
        )
        Text(
            text = stringResource(R.string.detail_latency_format, latency),
            style = MaterialTheme.typography.bodySmall,
            color = accent,
        )
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
