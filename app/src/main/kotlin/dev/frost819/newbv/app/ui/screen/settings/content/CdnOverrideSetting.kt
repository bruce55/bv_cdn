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
import dev.frost819.newbv.data.datastore.Prefs

/** VOD region/node selection; choosing the default restores upstream automatic CDN selection. */
@Composable
fun CdnOverrideSetting() {
    var host by rememberSaveable { mutableStateOf(Prefs.cdnOverrideHost) }
    var showRegions by rememberSaveable { mutableStateOf(false) }
    var showNodes by rememberSaveable { mutableStateOf(false) }
    var showCustom by rememberSaveable { mutableStateOf(false) }
    var customInput by rememberSaveable { mutableStateOf("") }
    val region = CdnOverrideCatalog.regionForHost(host)
    val nodes = CdnOverrideCatalog.nodesForRegion(region)
    val saveHost: (String) -> Unit = {
        host = it
        Prefs.cdnOverrideHost = it
    }

    SettingListItem(
        title = "播放源地区覆盖",
        supportText = "当前：$region（点播视频与音频，下次加载生效）",
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
