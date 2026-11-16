package com.aicode.feature.settings.presentation.component

/*
 * [二开] 外观与主题整页（参考 Eta 的外观与主题页设计）。
 *
 * 分组：
 *  - 颜色与主题：主题模式、配色方案、Monet 取色、色彩风格、强色调（强调色）
 *  - 界面样式：标准 / Miuix、界面缩放
 *  - 界面与效果：纯黑背景
 *
 * 所有选项都真实作用到 AIEditorTheme：强调色覆盖主色，色彩风格做饱和度/色相变换，
 * Miuix 换一套更大的圆角，界面缩放作用于 LocalDensity 的 fontScale。
 */

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aicode.R
import com.aicode.core.theme.Spacing
import com.aicode.core.theme.AccentColor
import com.aicode.core.theme.AppThemePreset
import com.aicode.core.theme.AppearanceOptions
import com.aicode.core.theme.PaletteStyle
import com.aicode.core.theme.UiStyle
import com.aicode.core.ui.AppSwitch
import com.aicode.feature.settings.data.repository.AppThemeMode
import com.aicode.feature.settings.presentation.SettingsViewModel
import compose.icons.FeatherIcons
import compose.icons.feathericons.Check

@Composable
internal fun AppearanceSettingsSection(viewModel: SettingsViewModel) {
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val themePresetId by viewModel.themePresetId.collectAsStateWithLifecycle()
    val dynamicColorEnabled by viewModel.dynamicColorEnabled.collectAsStateWithLifecycle()
    val accentColor by viewModel.accentColor.collectAsStateWithLifecycle()
    val paletteStyle by viewModel.paletteStyle.collectAsStateWithLifecycle()
    val uiStyle by viewModel.uiStyle.collectAsStateWithLifecycle()
    val pureBlack by viewModel.pureBlack.collectAsStateWithLifecycle()
    val interfaceScale by viewModel.interfaceScale.collectAsStateWithLifecycle()

    val dynamicColorSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    // 纯黑只对深色模式有意义：浅色模式下开关置灰并说明原因，避免「点了没反应」。
    val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
    val effectiveDark = when (themeMode) {
        AppThemeMode.AUTO -> systemDark
        AppThemeMode.DARK -> true
        AppThemeMode.LIGHT -> false
    }

    // 缩放滑块用 85~115 的百分比，避免浮点抖动
    var scalePercent by remember(interfaceScale) {
        mutableFloatStateOf((interfaceScale * 100f).coerceIn(85f, 115f))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg)
            .padding(bottom = Spacing.xl),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        // ── 颜色与主题 ──
        SettingsGroupHeader(text = stringResource(R.string.appearance_group_color))
        SettingsGroup {
            Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
                OptionLabel(stringResource(R.string.appearance_theme_mode))
                ChipRow(
                    options = listOf(
                        AppThemeMode.AUTO to stringResource(R.string.theme_auto),
                        AppThemeMode.DARK to stringResource(R.string.theme_dark),
                        AppThemeMode.LIGHT to stringResource(R.string.theme_light)
                    ),
                    selected = themeMode,
                    onSelect = { viewModel.setThemeMode(it) }
                )
            }
            SettingsDivider()
            Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
                OptionLabel(stringResource(R.string.appearance_preset))
                ChipRow(
                    options = AppThemePreset.ALL_PRESETS.map { it to stringResource(it.nameRes) },
                    selected = AppThemePreset.findById(themePresetId),
                    enabled = !dynamicColorEnabled,
                    onSelect = { viewModel.setThemePreset(it.id) }
                )
            }
            SettingsDivider()
            SettingsRow(
                title = stringResource(R.string.appearance_monet),
                subtitle = stringResource(R.string.appearance_monet_desc),
                enabled = dynamicColorSupported,
                trailing = {
                    AppSwitch(
                        checked = dynamicColorEnabled && dynamicColorSupported,
                        onCheckedChange = { viewModel.setDynamicColorEnabled(it) },
                        enabled = dynamicColorSupported
                    )
                }
            )
            SettingsDivider()
            Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
                OptionLabel(stringResource(R.string.appearance_palette_style))
                ChipRow(
                    options = PaletteStyle.entries.map { style ->
                        style to stringResource(style.labelRes)
                    },
                    selected = paletteStyle,
                    onSelect = { viewModel.setPaletteStyle(it) }
                )
            }
            SettingsDivider()
            Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
                OptionLabel(stringResource(R.string.appearance_accent_color))
                AccentRow(
                    selected = accentColor,
                    onSelect = { viewModel.setAccentColor(it) }
                )
            }
        }

        // ── 界面样式 ──
        SettingsGroupHeader(text = stringResource(R.string.appearance_group_style))
        SettingsGroup {
            Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
                OptionLabel(stringResource(R.string.appearance_ui_style))
                ChipRow(
                    options = UiStyle.entries.map { style ->
                        style to stringResource(style.labelRes)
                    },
                    selected = uiStyle,
                    onSelect = { viewModel.setUiStyle(it) }
                )
            }
            SettingsDivider()
            Column(modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.sm)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.appearance_interface_scale),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = "${scalePercent.toInt()}%",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Slider(
                    value = scalePercent,
                    onValueChange = {
                        // 以 5% 为步进，避免每 0.01 都写一次 DataStore
                        val stepped = (it / 5f).toInt() * 5f
                        val clamped = stepped.coerceIn(85f, 115f)
                        scalePercent = clamped
                        viewModel.setInterfaceScale(clamped / 100f)
                    },
                    valueRange = 85f..115f
                )
                Text(
                    text = stringResource(R.string.appearance_interface_scale_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // ── 界面与效果 ──
        SettingsGroupHeader(text = stringResource(R.string.appearance_group_effects))
        SettingsGroup {
            SettingsRow(
                title = stringResource(R.string.appearance_pure_black),
                subtitle = if (effectiveDark) {
                    stringResource(R.string.appearance_pure_black_desc)
                } else {
                    stringResource(R.string.appearance_pure_black_dark_only)
                },
                enabled = effectiveDark,
                trailing = {
                    AppSwitch(
                        checked = pureBlack && effectiveDark,
                        onCheckedChange = { viewModel.setPureBlack(it) },
                        enabled = effectiveDark
                    )
                }
            )
        }
    }
}

@Composable
private fun OptionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = Spacing.xs)
    )
}

/** 横向可滚动单选 chips，选项多时不会挤爆窄屏。 */
@Composable
private fun <T> ChipRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    enabled: Boolean = true
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        contentPadding = PaddingValues(vertical = 2.dp)
    ) {
        items(options) { (value, label) ->
            FilterChip(
                selected = value == selected,
                onClick = { if (enabled) onSelect(value) },
                enabled = enabled,
                label = { Text(label) },
                leadingIcon = if (value == selected) {
                    {
                        androidx.compose.material3.Icon(
                            imageVector = FeatherIcons.Check,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                } else {
                    null
                }
            )
        }
    }
}

/** 强调色色点：系统色用空心圆表示「跟随主题」。 */
@Composable
private fun AccentRow(
    selected: AccentColor,
    onSelect: (AccentColor) -> Unit
) {
    LazyRow(
        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        items(AccentColor.entries) { accent ->
            val isSelected = accent == selected
            val swatch = accent.color ?: MaterialTheme.colorScheme.primary
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(swatch)
                    .border(
                        width = if (isSelected) 3.dp else 1.dp,
                        color = if (isSelected) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                        shape = CircleShape
                    )
                    .clickable { onSelect(accent) },
                contentAlignment = Alignment.Center
            ) {
                if (accent.color == null) {
                    // 跟随主题：画一个小圆点提示这是「系统」而不是某个具体颜色
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.onPrimary)
                    )
                }
                if (isSelected) {
                    androidx.compose.material3.Icon(
                        imageVector = FeatherIcons.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }
        }
    }
}
