package com.aicode.feature.settings.data.repository

import android.content.Context
import com.aicode.R
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.aicode.core.datastore.preferencesCorruptionHandler
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.themeDataStore by preferencesDataStore(
    name = "theme_prefs",
    corruptionHandler = preferencesCorruptionHandler
)

enum class AppThemeMode(val labelRes: Int) {
    AUTO(R.string.theme_auto),
    DARK(R.string.theme_dark),
    LIGHT(R.string.theme_light);

    companion object {
        fun fromPersisted(value: String?): AppThemeMode? = entries.firstOrNull { it.name == value }
    }
}

/** 持久化 App 外观主题。默认跟随系统，也兼容旧版深色开关。 */
@Singleton
class ThemeSettingsRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private companion object {
        val THEME_MODE_KEY = stringPreferencesKey("theme_mode")
        val DARK_THEME_KEY = booleanPreferencesKey("dark_theme_enabled")
        val THEME_PRESET_KEY = stringPreferencesKey("theme_preset_id")
        val DYNAMIC_COLOR_KEY = booleanPreferencesKey("dynamic_color_enabled")
        /** 旧版「界面风格」键：只用于把 CLASSIC/MUSE 迁到新外观项，新代码不再写入。 */
        val APP_STYLE_KEY = stringPreferencesKey("app_style")
        val ACCENT_COLOR_KEY = stringPreferencesKey("appearance_accent_color")
        val PALETTE_STYLE_KEY = stringPreferencesKey("appearance_palette_style")
        val UI_STYLE_KEY = stringPreferencesKey("appearance_ui_style")
        val PURE_BLACK_KEY = booleanPreferencesKey("appearance_pure_black")
        val INTERFACE_SCALE_KEY = floatPreferencesKey("appearance_interface_scale")
    }

    val themeModeFlow: Flow<AppThemeMode> = context.themeDataStore.data.map { prefs ->
        AppThemeMode.fromPersisted(prefs[THEME_MODE_KEY])
            ?: prefs[DARK_THEME_KEY]?.let { if (it) AppThemeMode.DARK else AppThemeMode.LIGHT }
            ?: AppThemeMode.AUTO
    }

    /** 配色主题 id，对应 `AppThemePreset.id`；未设置时为 null，由调用方回退默认。 */
    val themePresetIdFlow: Flow<String?> = context.themeDataStore.data.map { it[THEME_PRESET_KEY] }

    /** 是否启用系统莫奈取色。开启后配色主题的选择不再生效。 */
    val dynamicColorFlow: Flow<Boolean> =
        context.themeDataStore.data.map { it[DYNAMIC_COLOR_KEY] == true }

    suspend fun setThemeMode(mode: AppThemeMode) {
        context.themeDataStore.edit { it[THEME_MODE_KEY] = mode.name }
    }

    suspend fun setThemePresetId(id: String) {
        context.themeDataStore.edit { it[THEME_PRESET_KEY] = id }
    }

    suspend fun setDynamicColorEnabled(enabled: Boolean) {
        context.themeDataStore.edit { it[DYNAMIC_COLOR_KEY] = enabled }
    }

    /** 强调色（强色调）。 */
    val accentColorFlow: Flow<com.aicode.core.theme.AccentColor> =
        context.themeDataStore.data.map { com.aicode.core.theme.AccentColor.fromId(it[ACCENT_COLOR_KEY]) }

    /** 色彩风格。 */
    val paletteStyleFlow: Flow<com.aicode.core.theme.PaletteStyle> =
        context.themeDataStore.data.map { com.aicode.core.theme.PaletteStyle.fromId(it[PALETTE_STYLE_KEY]) }

    /**
     * 界面样式（标准 / Miuix）。
     * 旧版的 `app_style`（CLASSIC / MUSE）在这里做一次兼容映射，用户之前的选样不会丢。
     */
    val uiStyleFlow: Flow<com.aicode.core.theme.UiStyle> = context.themeDataStore.data.map { prefs ->
        prefs[UI_STYLE_KEY]?.let { com.aicode.core.theme.UiStyle.fromId(it) }
            ?: if (prefs[APP_STYLE_KEY] == "MUSE") com.aicode.core.theme.UiStyle.MIUIX
            else com.aicode.core.theme.UiStyle.STANDARD
    }

    /** 纯黑背景（仅深色模式生效）。 */
    val pureBlackFlow: Flow<Boolean> = context.themeDataStore.data.map { it[PURE_BLACK_KEY] == true }

    /** 界面缩放（0.85~1.15）。 */
    val interfaceScaleFlow: Flow<Float> = context.themeDataStore.data.map {
        com.aicode.core.theme.AppearanceOptions.normalizeScale(
            it[INTERFACE_SCALE_KEY] ?: com.aicode.core.theme.AppearanceOptions.DEFAULT_SCALE
        )
    }

    suspend fun setAccentColor(value: com.aicode.core.theme.AccentColor) {
        context.themeDataStore.edit { it[ACCENT_COLOR_KEY] = value.id }
    }

    suspend fun setPaletteStyle(value: com.aicode.core.theme.PaletteStyle) {
        context.themeDataStore.edit { it[PALETTE_STYLE_KEY] = value.id }
    }

    suspend fun setUiStyle(value: com.aicode.core.theme.UiStyle) {
        context.themeDataStore.edit { it[UI_STYLE_KEY] = value.id }
    }

    suspend fun setPureBlack(enabled: Boolean) {
        context.themeDataStore.edit { it[PURE_BLACK_KEY] = enabled }
    }

    suspend fun setInterfaceScale(value: Float) {
        context.themeDataStore.edit {
            it[INTERFACE_SCALE_KEY] = com.aicode.core.theme.AppearanceOptions.normalizeScale(value)
        }
    }

    /** 备份快照：返回当前持久化的主题模式名（未设置时为 null，导入时回退默认）。 */
    suspend fun snapshot(): String? = themeModeFlow.first().name

    /** 从备份还原主题模式；null 时清除键回退默认。 */
    suspend fun restore(value: String?) {
        context.themeDataStore.edit {
            if (value == null) it.remove(THEME_MODE_KEY) else it[THEME_MODE_KEY] = value
        }
    }

    /** 备份快照：当前配色主题 id。 */
    suspend fun presetSnapshot(): String? = themePresetIdFlow.first()

    /** 备份快照：莫奈取色开关。 */
    suspend fun dynamicColorSnapshot(): Boolean = dynamicColorFlow.first()

    /** 备份快照：外观选项，导入时整体还原。 */
    suspend fun appearanceSnapshot(): com.aicode.feature.backup.domain.AppearanceSnapshot =
        com.aicode.feature.backup.domain.AppearanceSnapshot(
            accent = accentColorFlow.first().id,
            paletteStyle = paletteStyleFlow.first().id,
            uiStyle = uiStyleFlow.first().id,
            pureBlack = pureBlackFlow.first(),
            interfaceScale = interfaceScaleFlow.first()
        )

    suspend fun restoreAppearance(value: com.aicode.feature.backup.domain.AppearanceSnapshot?) {
        if (value == null) return
        context.themeDataStore.edit {
            it[ACCENT_COLOR_KEY] = value.accent
            it[PALETTE_STYLE_KEY] = value.paletteStyle
            it[UI_STYLE_KEY] = value.uiStyle
            it[PURE_BLACK_KEY] = value.pureBlack
            it[INTERFACE_SCALE_KEY] = com.aicode.core.theme.AppearanceOptions.normalizeScale(value.interfaceScale)
        }
    }

    /** 从备份还原配色主题与莫奈开关；id 为 null 时清除键回退默认。 */
    suspend fun restoreColors(presetId: String?, dynamicColor: Boolean) {
        context.themeDataStore.edit {
            if (presetId == null) it.remove(THEME_PRESET_KEY) else it[THEME_PRESET_KEY] = presetId
            it[DYNAMIC_COLOR_KEY] = dynamicColor
        }
    }
}
