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
    var progressSize by rememberSaveable { mutableStateOf(Prefs.downloadProgressSize) }
    var showProgressSize by rememberSaveable { mutableStateOf(false) }
    var diagnostics by rememberSaveable { mutableStateOf(Prefs.showDownloadDiagnostics) }
    var chart by rememberSaveable { mutableStateOf(Prefs.showDownloadChart) }
    var keepVisible by rememberSaveable { mutableStateOf(Prefs.keepDownloadControlsVisible) }
    var minimumBlock by rememberSaveable { mutableStateOf(Prefs.minimumDownloadBlockKiB) }
    var showMinimumBlock by rememberSaveable { mutableStateOf(false) }
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
        supportText = "下次加载生效。显示视频/音频下载进度与并发数；分块填色表示已接收，底部短线记录下载事件",
        checked = visualization,
        onCheckedChange = {
            visualization = it
            Prefs.showParallelDownloads = it
        },
    )
    SettingListItem(
        title = "下载进度显示大小",
        supportText = progressSizeLabel(progressSize),
        enabled = visualization,
        onClick = { showProgressSize = true },
    )
    if (showProgressSize) {
        OptionDialog(
            options = arrayOf("compact", "normal", "large", "extra_large"),
            selectedOption = progressSize,
            onDismiss = { showProgressSize = false },
            onSelect = {
                progressSize = it
                Prefs.downloadProgressSize = it
            },
            getDisplayName = ::progressSizeLabel,
        )
    }
    SettingSwitchListItem(
        title = "显示 CDN 下载诊断",
        supportText = "下次加载生效。加速开启时，按遥控器下键或轻触画面，在播放控制栏查看节点使用、测速和失败情况",
        checked = diagnostics,
        onCheckedChange = {
            diagnostics = it
            Prefs.showDownloadDiagnostics = it
        },
    )
    SettingSwitchListItem(
        title = "显示下载调试曲线",
        supportText = "需开启 CDN 诊断；每秒采样，保留最近 120 点，显示速率、缓冲、并发和分段大小",
        checked = chart,
        enabled = diagnostics,
        onCheckedChange = {
            chart = it
            Prefs.showDownloadChart = it
        },
    )
    SettingSwitchListItem(
        title = "下载调试：保持播放控制栏显示",
        supportText = "进入播放器自动显示；不自动隐藏，返回键仍可收起",
        checked = keepVisible,
        onCheckedChange = {
            keepVisible = it
            Prefs.keepDownloadControlsVisible = it
        },
    )
    if (accelerated) {
        SettingListItem(
            title = "最小下载块",
            supportText = "${blockSizeLabel(minimumBlock)} · 下次加载生效；短分段及启动探测除外",
            onClick = { showMinimumBlock = true },
        )
        SettingListItem(
            title = "最大并发下载数",
            supportText = "$requestLimit（视频、音频和重试共用，下次加载生效）",
            onClick = { showRequests = true },
        )
        SettingListItem(
            title = "自动 CDN 范围",
            supportText =
                when {
                    mode == "overseas" -> "海外（下次加载生效）"
                    else -> "中国大陆（下次加载生效）"
                },
            onClick = { showMode = true },
        )
    }
    if (showMinimumBlock) {
        OptionDialog(
            options = arrayOf(64, 256, 512, 1024, 2048, 4096),
            selectedOption = minimumBlock,
            onDismiss = { showMinimumBlock = false },
            onSelect = {
                minimumBlock = it
                Prefs.minimumDownloadBlockKiB = it
            },
            getDisplayName = ::blockSizeLabel,
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
            options = arrayOf(4, 8, 12, 16, 24, 32, 48, 64),
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
        supportText = if (accelerated) "并行下载开启时自动选择 CDN" else "当前：$region（下次加载生效）",
        enabled = !accelerated,
        onClick = { showRegions = true },
    )
    if (host.isNotEmpty()) {
        SettingListItem(
            title = if (nodes.isEmpty()) "自定义播放源" else "播放源节点",
            supportText = host,
            enabled = !accelerated,
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
    if (showRegions && !accelerated) {
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
    if (showNodes && nodes.isNotEmpty() && !accelerated) {
        OptionDialog(
            options = nodes.toTypedArray(),
            selectedOption = host,
            onDismiss = { showNodes = false },
            onSelect = saveHost,
            getDisplayName = { it },
        )
    }
    if (showCustom && !accelerated) {
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

private fun blockSizeLabel(kib: Int): String =
    when {
        kib < 1024 -> "$kib KiB"
        else -> "${kib / 1024} MiB"
    }

private fun progressSizeLabel(size: String): String =
    when (size) {
        "compact" -> "紧凑"
        "large" -> "大"
        "extra_large" -> "特大"
        else -> "标准"
    }
