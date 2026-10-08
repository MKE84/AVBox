package com.github.tvbox.osc.ui.page

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.github.tvbox.osc.api.ApiConfig
import com.github.tvbox.osc.bean.SourceBean
import com.github.tvbox.osc.util.SourceKind
import com.github.tvbox.osc.util.SourceManager
import com.github.tvbox.osc.util.SourceType

/**
 * 源管理 / 最近删除:
 * 1) 列出订阅展开的所有源(py/js/jar),逐条可「删除(隐藏)」——删除后订阅刷新不再复活;
 * 2) 「最近删除」区列出被删的源,可一键「恢复」,恢复后下次刷新订阅重新出现。
 * 语言固定简体,不引 resource 字符串(避免 build 耦合)。
 */
@Composable
fun SourceManageScreen(onNavigateBack: () -> Unit) {
    val ctx = LocalContext.current
    var refreshKey by remember { androidx.compose.runtime.mutableIntStateOf(0) }
    val mgr = SourceManager.get()

    // 展开的源列表(每次 refreshKey 变化重读)
    val sources: List<SourceBean> = remember(refreshKey) { ApiConfig.get().getSourceBeanList() }
    // 最近删除记录(name\tkey)
    val recent: List<String> = remember(refreshKey) { mgr.recentDeleted() }

    Column(modifier = Modifier.fillMaxSize()) {
        // ===== 顶栏 =====
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onNavigateBack) { Text("←") }
            Text(
                text = "源管理",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = "共 ${sources.size} 源 · 已隐藏 ${mgr.hiddenKeys().size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        // ===== 类型筛选: 全部源 / 已隐藏 =====
        val showHidden = remember { androidx.compose.runtime.mutableStateOf(false) }
        val hiddenKeys = remember(refreshKey) { mgr.hiddenKeys() }
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = { showHidden.value = false }) {
                Text(
                    text = "全部源",
                    color = if (!showHidden.value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (!showHidden.value) FontWeight.SemiBold else FontWeight.Normal
                )
            }
            TextButton(onClick = { showHidden.value = true }) {
                Text(
                    text = "已隐藏 (${hiddenKeys.size})",
                    color = if (showHidden.value) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = if (showHidden.value) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = 24.dp)
        ) {
            if (showHidden.value) {
                // ===== 已隐藏视图: 列出全部被隐藏的源,可单独恢复 =====
                if (hiddenKeys.isEmpty()) {
                    item {
                        Text(
                            text = "暂无已隐藏的源",
                            modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                items(hiddenKeys.size, key = { it }) { i ->
                    val k = hiddenKeys[i]
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = k,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1
                            )
                            TextButton(onClick = {
                                mgr.restore(k)
                                refreshKey++
                                Toast.makeText(ctx, "已恢复「$k」，刷新订阅后重新出现", Toast.LENGTH_SHORT).show()
                            }) { Text("恢复", color = MaterialTheme.colorScheme.tertiary) }
                        }
                    }
                }
            } else {
                // ===== 最近删除区 =====
                if (recent.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "最近删除",
                                modifier = Modifier.padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(Modifier.weight(1f))
                            TextButton(onClick = {
                                mgr.clearRecentAll()
                                refreshKey++
                                Toast.makeText(ctx, "已清空最近删除", Toast.LENGTH_SHORT).show()
                            }) { Text("全部清空", color = MaterialTheme.colorScheme.error) }
                        }
                    }
                    items(recent.size, key = { it }) { i ->
                        val parts = recent[i].split("\t")
                        val rName = parts.getOrElse(0) { recent[i] }
                        val rKey = parts.getOrElse(1) { recent[i] }
                        Surface(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = rName,
                                    style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.weight(1f),
                                    maxLines = 1
                                )
                                TextButton(onClick = {
                                    mgr.restore(rKey)
                                    refreshKey++
                                    Toast.makeText(ctx, "已恢复「$rName」，刷新订阅后重新出现", Toast.LENGTH_SHORT).show()
                                }) { Text("恢复", color = MaterialTheme.colorScheme.tertiary) }
                            }
                        }
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                }

                    // ===== 展开的源列表 =====
                item { SectionTitle("全部源") }

                if (sources.isEmpty()) {
                    item {
                        Text(
                            text = "暂无源",
                            modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                items(sources.size, key = { sources[it].getKey() ?: it }) { idx ->
                    val sb = sources[idx]
                    val hidden = mgr.isHidden(sb.getKey())
                    val typeLabel = sourceTypeLabel(sb)
                    Surface(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 3.dp),
                        shape = RoundedCornerShape(12.dp),
                        color = if (hidden) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                        else MaterialTheme.colorScheme.surface
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 类型徽标
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = badgeColor(typeLabel)
                            ) {
                                Text(
                                    text = typeLabel,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    fontSize = 10.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                            Spacer(Modifier.width(10.dp))
                            Text(
                                text = sb.getName(),
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                                color = if (hidden) MaterialTheme.colorScheme.onSurfaceVariant
                                else MaterialTheme.colorScheme.onSurface
                            )
                            if (hidden) {
                                Text(
                                    "已隐藏",
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.error
                                )
                            } else {
                                TextButton(onClick = {
                                    mgr.hide(sb)
                                    refreshKey++
                                    Toast.makeText(ctx, "已删除「${sb.getName()}」，订阅刷新不会复活", Toast.LENGTH_SHORT).show()
                                }) { Text("删除", color = MaterialTheme.colorScheme.error) }
                            }
                        }
                    }
            }
            }   // close else (showHidden 分支)
        }   // close LazyColumn
    }   // close Column
}   // close fun

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

/** 源类型徽标:py/js/jar/接口。统一判定见 util/SourceType */
private fun sourceTypeLabel(sb: SourceBean): String =
    when (SourceType.of(sb)) {
        SourceKind.PY -> "py"
        SourceKind.JS -> "js"
        SourceKind.JAR -> "jar"
        SourceKind.API -> "接口"
    }

@Composable
private fun badgeColor(type: String) =
    when (type) {
        "py" -> MaterialTheme.colorScheme.tertiaryContainer
        "js" -> MaterialTheme.colorScheme.primaryContainer
        else -> MaterialTheme.colorScheme.secondaryContainer
    }