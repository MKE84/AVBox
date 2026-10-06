package com.github.tvbox.osc.ui.page

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.util.LinkedHashSet
import com.github.tvbox.osc.R
import com.github.tvbox.osc.ui.components.AppTopBarScaffold
import com.github.tvbox.osc.ui.components.SettingsCard
import com.github.tvbox.osc.ui.components.SettingsCardPosition
import com.github.tvbox.osc.ui.components.SettingsGroup
import com.github.tvbox.osc.ui.components.SettingsSwitchRow
import com.github.tvbox.osc.ui.components.TopBarActionBox
import com.github.tvbox.osc.util.ApiLog

/** 日志类型筛选:全部 / PY / JS / 其他 */
private enum class LogFilter(val label: String, val match: (String) -> Boolean) {
    All("全部", { true }),
    Py("PY", { it.contains("| PY |") }),
    Js("JS", { it.contains("| JS |") }),
    Other("其他", { !it.contains("| PY |") && !it.contains("| JS |") }),
}

@Composable
fun ApiLogScreen(onNavigateBack: () -> Unit) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(ApiLog.enabled()) }
    var filter by remember { mutableStateOf(LogFilter.All) }
    var lines by remember { mutableStateOf(ApiLog.recent()) }

    // 打开页面时刷新一次(日志在后台线程写,不主动推送)
    LaunchedEffect(enabled, filter) {
        lines = ApiLog.recent().filter { filter.match(it) }
    }

    AppTopBarScaffold(
        collapseEnabled = false,
        titleContent = {
            Text(
                text = stringResource(R.string.settings_api_log),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface,
            )
        },
        navigationIcon = {
            TopBarActionBox(
                R.drawable.ic_arrow_left,
                stringResource(R.string.common_back),
                onClick = onNavigateBack,
            )
        },
    ) { topPad, _ ->
        LazyColumn(
            state = rememberLazyListState(),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = topPad + 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(key = "__switch__") {
                SettingsGroup(title = null) {
                    SettingsCard(SettingsCardPosition.SINGLE) {
                        SettingsSwitchRow(
                            title = stringResource(R.string.settings_api_log_enable),
                            subtitle = stringResource(R.string.settings_api_log_enable_subtitle),
                            checked = enabled,
                            onCheckedChange = {
                                enabled = it
                                ApiLog.setEnabled(it)
                                lines = ApiLog.recent().filter { f -> filter.match(f) }
                            },
                        )
                    }
                }
            }
            item(key = "__actions__") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LogFilter.entries.forEach { f ->
                        LogFilterChip(
                            text = f.label,
                            selected = filter == f,
                            modifier = Modifier.weight(1f),
                            onClick = {
                                filter = f
                                lines = ApiLog.recent().filter { f.match(it) }
                            },
                        )
                    }
                }
            }
            item(key = "__toolbar__") {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    LogActionButton(
                        text = stringResource(R.string.settings_api_log_refresh),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            lines = ApiLog.recent().filter { filter.match(it) }
                        },
                    )
                    LogActionButton(
                        text = stringResource(R.string.settings_api_log_clear),
                        modifier = Modifier.weight(1f),
                        onClick = {
                            ApiLog.clear()
                            lines = emptyList()
                        },
                    )
                    LogActionButton(
                        text = stringResource(R.string.settings_api_log_export),
                        modifier = Modifier.weight(1f),
                        onClick = { exportLog(context) },
                    )
                }
            } else if (lines.isEmpty()) {
                item(key = "__empty__") {
                    Text(
                        text = stringResource(
                            if (enabled) R.string.settings_api_log_empty else R.string.settings_api_log_off,
                        ),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(top = 24.dp),
                    )
                }
            } else {
                items(lines, key = { it }) { line ->
                    LogLineCard(line)
                }
            }
        }
    }
}


@Composable
private fun LogActionButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(vertical = 10.dp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun LogFilterChip(text: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Surface(
        modifier = modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 10.dp),
            textAlign = TextAlign.Center,
        )
    }
}

/** 单行日志:整体等宽字体,失败行用 error 色突出 */
@Composable
private fun LogLineCard(line: String) {
    val isFail = line.contains("| FAIL |")
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Spacer(
                Modifier
                    .padding(top = 5.dp)
                    .width(6.dp)
                    .height(6.dp)
                    .background(
                        if (isFail) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        RoundedCornerShape(3.dp),
                    ),
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = line,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (isFail) FontWeight.SemiBold else FontWeight.Normal,
                color = if (isFail) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/**
 * 导出接口日志:内存缓冲 + api_log.txt 合并去重,按时间升序,
 * 写到应用外部 Download 目录(可用文件管理器找到并发送)。
 */
private fun exportLog(context: android.content.Context) {
    val app = context.applicationContext
    android.widget.Toast.makeText(app, "正在导出…", android.widget.Toast.LENGTH_SHORT).show()
    Thread {
        var msg: String
        try {
            // 合并内存 + 落盘日志,LinkedHashSet 天然去重
            val seen = LinkedHashSet<String>()
            for (line in ApiLog.recent()) if (line.isNotBlank()) seen.add(line)
            val file = ApiLog.file()
            if (file != null && file.exists()) {
                file.readLines().forEach { l -> if (l.isNotBlank()) seen.add(l) }
            }
            val all = seen.toList().sorted()

            val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US)
                .format(java.util.Date())
            val dir = app.getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: app.filesDir
            val outFile = java.io.File(dir, "AVBox-apilog-$stamp.txt")
            outFile.bufferedWriter().use { w ->
                w.write("# AVBox 接口日志  共 ${all.size} 条\n")
                w.write("# 导出时间: " + java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
                    .format(java.util.Date()) + "\n")
                w.write("# 格式: 时间 | 类型 | OK/FAIL | 源名 | 动作 [:: 错误] | 耗时\n\n")
                for (l in all) w.write(l + "\n")
            }
            msg = "已导出: " + outFile.absolutePath
        } catch (e: Throwable) {
            msg = "导出失败: " + (e.message ?: e.toString())
        }
        val finalMsg = msg
        android.os.Handler(android.os.Looper.getMainLooper()).post {
            android.widget.Toast.makeText(app, finalMsg, android.widget.Toast.LENGTH_LONG).show()
        }
    }.start()
}