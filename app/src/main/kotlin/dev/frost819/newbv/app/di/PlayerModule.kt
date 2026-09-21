package dev.frost819.newbv.app.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dev.frost819.newbv.app.network.BufferingJournal
import dev.frost819.newbv.app.network.DownloadMemoryJournal
import dev.frost819.newbv.core.log.CrashHandler
import dev.frost819.newbv.data.datastore.Prefs
import dev.frost819.newbv.player.download.DownloadTraceStore
import dev.frost819.newbv.player.impl.exo.ExoPlayerFactory
import java.io.File
import javax.inject.Singleton

/**
 * 播放器相关 Hilt 模块。
 *
 * 提供播放器工厂等播放器相关的依赖绑定。
 * VideoInfoRepository 已通过 `@Inject constructor` + `@Singleton` 自动绑定，无需在此声明。
 */
@Module
@InstallIn(SingletonComponent::class)
object PlayerModule {
    /**
     * 提供 [ExoPlayerFactory] 单例。
     *
     * 用于创建 Media3 ExoPlayer 实例。通过工厂模式抽象播放器创建，
     * 便于未来扩展其他播放器实现。
     */
    @Provides
    @Singleton
    fun provideExoPlayerFactory(downloadTraceStore: DownloadTraceStore): ExoPlayerFactory =
        ExoPlayerFactory(downloadTraceStore)

    /** Process-local, opt-in playback trace shared by players and the log server. */
    @Provides
    @Singleton
    fun provideDownloadTraceStore(
        memoryJournal: DownloadMemoryJournal,
        bufferingJournal: BufferingJournal,
    ): DownloadTraceStore =
        DownloadTraceStore(
            memoryEventSink = memoryJournal::record,
            bufferingEnabled = { Prefs.bufferingLogsEnabled },
            bufferingSink = bufferingJournal::record,
        )

    /** Stores buffering incidents with other locally managed diagnostic logs. */
    @Provides
    @Singleton
    fun provideBufferingJournal(
        @ApplicationContext context: Context,
    ): BufferingJournal = BufferingJournal(File(context.filesDir, CrashHandler.LOG_DIR))

    /** Retains opted-in memory samples across process death without creating files at startup. */
    @Provides
    @Singleton
    fun provideDownloadMemoryJournal(
        @ApplicationContext context: Context,
    ): DownloadMemoryJournal = DownloadMemoryJournal(File(context.filesDir, "download-memory"))
}
