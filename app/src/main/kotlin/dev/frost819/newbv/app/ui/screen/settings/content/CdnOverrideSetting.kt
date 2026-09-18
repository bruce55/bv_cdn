package dev.frost819.newbv.app.ui.screen.settings.content

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.tv.material3.Text
import dev.frost819.newbv.app.entity.CdnOverrideCatalog
import dev.frost819.newbv.app.ui.component.settings.OptionDialog
import dev.frost819.newbv.app.ui.component.settings.SettingListItem
import dev.frost819.newbv.app.ui.component.settings.SettingSwitchListItem
import dev.frost819.newbv.data.datastore.Prefs

/** Independent VOD acceleration/visualization settings and an optional pinned CDN override. */
@Composable
fun CdnOverrideSetting() {
    var host by rememberSaveable { mutableStateOf(Prefs.cdnOverrideHost) }
    var showRegions by rememberSaveable { mutableStateOf(false) }
    var showNodes by rememberSaveable { mutableStateOf(false) }
    var showCustom by rememberSaveable { mutableStateOf(false) }
    var customInput by rememberSaveable { mutableStateOf("") }
    var accelerated by rememberSaveable { mutableStateOf(Prefs.parallelDownloadEnabled) }
    var visualization by rememberSaveable { mutableStateOf(Prefs.showParallelDownloads) }
    var requestLimit by rememberSaveable { mutableStateOf(Prefs.parallelDownloadRequests) }
    var mode by rememberSaveable { mutableStateOf(Prefs.parallelDownloadMode) }
    var showMode by rememberSaveable { mutableStateOf(false) }
    var showRequests by rememberSaveable { mutableStateOf(false) }
    val region = CdnOverrideCatalog.regionForHost(host)
    val nodes = CdnOverrideCatalog.nodesForRegion(region)
    val saveHost: (String) -> Unit = {
        host = it
        Prefs.cdnOverrideHost = it
    }

    SettingSwitchListItem(
        title = "并行下载加速（实验性）",
        supportText = "点播视频与音频共享并发额度，下次加载生效",
        checked = accelerated,
        onCheckedChange = {
            accelerated = it
            Prefs.parallelDownloadEnabled = it
        },
    )
    SettingSwitchListItem(
        title = "显示下载分块",
        supportText = "下次加载生效。加速开启时显示视频/音频分块与活跃下载数；空框待下载，蓝色下载中，绿色完成，橙色重试",
        checked = visualization,
        onCheckedChange = {
            visualization = it
            Prefs.showParallelDownloads = it
        },
    )
    if (accelerated) {
        SettingListItem(
            title = "最大并发下载数",
            supportText = "$requestLimit（视频、音频和重试共用，下次加载生效）",
            onClick = { showRequests = true },
        )
        SettingListItem(
            title = "自动 CDN 范围",
            supportText =
                when {
                    host.isNotEmpty() -> "已固定节点：$host（自动选择不生效）"
                    mode == "overseas" -> "海外（下次加载生效）"
                    else -> "中国大陆（下次加载生效）"
                },
            onClick = { showMode = true },
        )
    }
    if (showMode) {
        OptionDialog(
            options = arrayOf("mainland", "overseas"),
            selectedOption = mode,
            onDismiss = { showMode = false },
            onSelect = {
                mode = it
                Prefs.parallelDownloadMode = it
            },
            getDisplayName = { if (it == "overseas") "海外" else "中国大陆" },
        )
    }
    if (showRequests) {
        OptionDialog(
            options = arrayOf(4, 8),
            selectedOption = requestLimit,
            onDismiss = { showRequests = false },
            onSelect = {
                requestLimit = it
                Prefs.parallelDownloadRequests = it
            },
            getDisplayName = { "$it" },
        )
    }
    SettingListItem(
        title = "手动固定 CDN 节点",
        supportText = "当前：$region（固定节点优先于自动选择；选择默认恢复自动，下次加载生效）",
        onClick = { showRegions = true },
    )
    if (host.isNotEmpty()) {
        SettingListItem(
            title = if (nodes.isEmpty()) "自定义播放源" else "播放源节点",
            supportText = host,
            onClick = {
                if (nodes.isEmpty()) {
                    customInput = host
                    showCustom = true
                } else {
                    showNodes = true
                }
            },
        )
    }
    if (showRegions) {
        OptionDialog(
            options = CdnOverrideCatalog.regions.toTypedArray(),
            selectedOption = region,
            onDismiss = { showRegions = false },
            onSelect = {
                when (it) {
                    CdnOverrideCatalog.defaultRegion -> saveHost("")
                    CdnOverrideCatalog.customRegion -> {
                        customInput = host
                        showCustom = true
                    }
                    else -> saveHost(CdnOverrideCatalog.nodesForRegion(it).first())
                }
            },
            getDisplayName = { it },
        )
    }
    if (showNodes && nodes.isNotEmpty()) {
        OptionDialog(
            options = nodes.toTypedArray(),
            selectedOption = host,
            onDismiss = { showNodes = false },
            onSelect = saveHost,
            getDisplayName = { it },
        )
    }
    if (showCustom) {
        val normalized = CdnOverrideCatalog.normalizeHost(customInput)
        AlertDialog(
            onDismissRequest = { showCustom = false },
            title = { Text("自定义播放源") },
            text = {
                OutlinedTextField(
                    value = customInput,
                    onValueChange = { customInput = it },
                    label = { Text("CDN 节点域名或 URL") },
                    singleLine = true,
                    isError = customInput.isNotBlank() && normalized.isEmpty(),
                )
            },
            confirmButton = {
                TextButton(
                    enabled = normalized.isNotEmpty(),
                    onClick = {
                        saveHost(normalized)
                        showCustom = false
                    },
                ) { Text("确定") }
            },
            dismissButton = {
                TextButton(onClick = { showCustom = false }) { Text("取消") }
            },
        )
    }
}
