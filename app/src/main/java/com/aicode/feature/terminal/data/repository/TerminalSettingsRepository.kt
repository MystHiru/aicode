package com.aicode.feature.terminal.data.repository

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.feature.terminal.domain.model.TerminalThemePreset
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.terminalDataStore by preferencesDataStore(name = "terminal_settings_prefs")

/** 终端配置模型。 */
data class TerminalSettings(
    val themeId: String = TerminalThemePreset.TERMIUS_DARK.id,
    val fontSizeSp: Int = 12,
    val cursorStyle: Int = 0, // 0=Block, 1=Underline, 2=Bar
    /** 自定义字体文件绝对路径，空表示系统等宽字体。 */
    val fontPath: String = ""
) {
    val theme: TerminalThemePreset
        get() = TerminalThemePreset.findById(themeId)
}

/** 终端个性化配置持久化仓库（DataStore）。 */
@Singleton
class TerminalSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val THEME_ID_KEY = stringPreferencesKey("terminal_theme_id")
        val FONT_SIZE_SP_KEY = intPreferencesKey("terminal_font_size_sp")
        val CURSOR_STYLE_KEY = intPreferencesKey("terminal_cursor_style")
        val FONT_PATH_KEY = stringPreferencesKey("terminal_font_path")
    }

    val settingsFlow: Flow<TerminalSettings> = context.terminalDataStore.data.map { prefs ->
        TerminalSettings(
            themeId = prefs[THEME_ID_KEY] ?: TerminalThemePreset.TERMIUS_DARK.id,
            fontSizeSp = prefs[FONT_SIZE_SP_KEY]?.coerceIn(10, 22) ?: 12,
            cursorStyle = prefs[CURSOR_STYLE_KEY] ?: 0,
            fontPath = prefs[FONT_PATH_KEY]?.takeIf { File(it).isFile } ?: ""
        )
    }

    suspend fun setThemeId(themeId: String) {
        context.terminalDataStore.edit { it[THEME_ID_KEY] = themeId }
    }

    suspend fun setFontSizeSp(sizeSp: Int) {
        context.terminalDataStore.edit { it[FONT_SIZE_SP_KEY] = sizeSp.coerceIn(10, 22) }
    }

    suspend fun setCursorStyle(style: Int) {
        context.terminalDataStore.edit { it[CURSOR_STYLE_KEY] = style.coerceIn(0, 2) }
    }

    suspend fun setFontPath(path: String) {
        context.terminalDataStore.edit { it[FONT_PATH_KEY] = path }
    }
}
