package dev.frost819.newbv.app.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.rememberNavController
import dev.frost819.newbv.app.ui.screen.detail.videoDetailScreen
import dev.frost819.newbv.app.ui.screen.home.homeScreen
import dev.frost819.newbv.app.ui.screen.live.liveAreaScreen
import dev.frost819.newbv.app.ui.screen.live.liveFollowScreen
import dev.frost819.newbv.app.ui.screen.login.loginScreen
import dev.frost819.newbv.app.ui.screen.pgc.pgcFeatureScreen
import dev.frost819.newbv.app.ui.screen.player.livePlayerScreen
import dev.frost819.newbv.app.ui.screen.player.seasonPlayerScreen
import dev.frost819.newbv.app.ui.screen.player.videoPlayerScreen
import dev.frost819.newbv.app.ui.screen.search.searchResultScreen
import dev.frost819.newbv.app.ui.screen.settings.settingsScreen
import dev.frost819.newbv.app.ui.screen.user.followScreen
import dev.frost819.newbv.app.ui.screen.user.userSpaceScreen
import dev.frost819.newbv.app.ui.screen.user.userSwitchScreen
import dev.frost819.newbv.core.log.Loggers

/**
 * 应用 Navigation 宿主。
 *
 * 单 Activity 架构：所有页面通过 Navigation-Compose 导航，无新 Activity 启动。
 * 路由定义见 [Routes]。
 *
 * @param navController 导航控制器，默认使用 [rememberNavController]
 * @param startDestination 起始路由，默认 [HomeRoute]
 */
@Composable
fun AppNavHost(
    navController: NavHostController = rememberNavController(),
    startDestination: Any = HomeRoute,
) {
    val logger = Loggers.get("AppNavHost")
    val remoteBackGuard = remember { RemoteBackKeyGuard() }

    LaunchedEffect(navController) {
        var previousRoute: String? = null
        navController.currentBackStackEntryFlow.collect { entry ->
            val route = entry.destination.route?.substringAfterLast('.') ?: "unknown"
            if (previousRoute != null && previousRoute != route) {
                logger.info { "[NAV] from=$previousRoute to=$route" }
            }
            previousRoute = route
        }
    }

    // The TV player can pop on key-down. Its key-up then belongs to the old screen;
    // letting it reach system Back can ask NavHost to transition an already removed entry.
    NavHost(
        modifier =
            Modifier.onPreviewKeyEvent { event ->
                if (event.key != Key.Back) return@onPreviewKeyEvent false
                val entryId = navController.currentBackStackEntry?.id
                when (event.type) {
                    KeyEventType.KeyDown -> {
                        remoteBackGuard.onDown(entryId)
                        false
                    }
                    KeyEventType.KeyUp -> remoteBackGuard.consumeUp(entryId)
                    else -> false
                }
            },
        navController = navController,
        startDestination = startDestination,
    ) {
        // ── 主流程 ────────────────────────────────────────────────────
        homeScreen(navController)
        searchResultScreen(navController)

        // ── 视频详情 ─────────────────────────────────────────────────
        videoDetailScreen(navController)

        // ── 直播 ─────────────────────────────────────────────────────
        liveAreaScreen(navController)
        liveFollowScreen(navController)

        // ── 播放器 ───────────────────────────────────────────────────
        videoPlayerScreen(navController)
        seasonPlayerScreen(navController)
        livePlayerScreen(navController)

        // ── 用户 ─────────────────────────────────────────────────────
        userSpaceScreen(navController)
        followScreen(navController)

        // ── PGC ──────────────────────────────────────────────────────
        pgcFeatureScreen(navController)

        // ── 设置 ─────────────────────────────────────────────────────
        settingsScreen(navController)
        userSwitchScreen(navController)

        // ── 登录 ─────────────────────────────────────────────────────
        loginScreen(navController)
    }
}
