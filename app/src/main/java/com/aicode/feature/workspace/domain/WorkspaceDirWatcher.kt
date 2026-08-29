package com.aicode.feature.workspace.domain

import android.os.FileObserver
import com.aicode.feature.settings.data.repository.ExecutionMode
import com.aicode.feature.settings.data.repository.ExecutionModeHolder
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 监听某个目录「直接子项」的增删改，供文件浏览自动刷新。
 *
 * 只监听单层、不递归：文件浏览本身是单层的，递归 watch 整棵树会在大仓库上开出成百上千个 inotify 句柄。
 * 变更来源不限于 AI——终端里的命令、其它 App 改动同样会触发。
 *
 * 远程模式没有本地 inotify 可用，[watch] 返回不发事件的空流，由调用方退化为手动刷新。
 */
@Singleton
class WorkspaceDirWatcher @Inject constructor(
    private val modeHolder: ExecutionModeHolder,
    private val pathMapper: WorkspacePathMapper
) {

    /** [containerPath] 为容器路径（如 `~/workspace/app`）；每次发射表示该目录内有变动，不携带细节。 */
    fun watch(containerPath: String): Flow<Unit> = callbackFlow {
        val dir = if (modeHolder.currentMode() == ExecutionMode.REMOTE_SSH) {
            null
        } else {
            pathMapper.toHostFile(containerPath).takeIf { it.isDirectory }
        }
        if (dir == null) {
            awaitClose { }
            return@callbackFlow
        }
        // 用 String 构造：File 版本要 API 29，本项目 minSdk 26。
        @Suppress("DEPRECATION")
        val observer = object : FileObserver(dir.absolutePath, WATCH_MASK) {
            override fun onEvent(event: Int, path: String?) {
                trySend(Unit)
            }
        }
        observer.startWatching()
        awaitClose { observer.stopWatching() }
    }

    private companion object {
        const val WATCH_MASK = FileObserver.CREATE or
            FileObserver.DELETE or
            FileObserver.MOVED_TO or
            FileObserver.MOVED_FROM or
            FileObserver.CLOSE_WRITE
    }
}
