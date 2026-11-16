package com.aicode.core.theme

/*
 * [二开] 外观选项模型（参考 Eta 的外观与主题页设计）。
 *
 * 只做「能真实生效」的选项：
 *  - 强调色（Eta 的 accent color，本页叫「强色调」）：覆盖主色
 *  - 色彩风格（palette style）：对整套配色做饱和度/色相变换
 *  - 界面样式（UI style）：标准 / Miuix（更大圆角 + 更浅的分隔感）
 *  - 界面缩放：整体字号缩放
 *  - 纯黑：深色模式下背景压到纯黑
 *
 * 不引入新依赖，颜色变换用 HSV 做，避免为了「配色算法」再拉一个 Material color utilities。
 */

import android.graphics.Color as AndroidColor
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import com.aicode.R
import kotlin.math.roundToInt

/** 强调色（强色调）。SYSTEM 表示沿用当前配色方案的主色。 */
enum class AccentColor(val id: String, val labelRes: Int, val color: Color?) {
    SYSTEM("system", R.string.appearance_accent_system, null),
    BLUE("blue", R.string.appearance_accent_blue, Color(0xFF2563EB)),
    PURPLE("purple", R.string.appearance_accent_purple, Color(0xFF7C3AED)),
    PINK("pink", R.string.appearance_accent_pink, Color(0xFFDB2777)),
    RED("red", R.string.appearance_accent_red, Color(0xFFDC2626)),
    ORANGE("orange", R.string.appearance_accent_orange, Color(0xFFEA580C)),
    YELLOW("yellow", R.string.appearance_accent_yellow, Color(0xFFCA8A04)),
    GREEN("green", R.string.appearance_accent_green, Color(0xFF16A34A)),
    TEAL("teal", R.string.appearance_accent_teal, Color(0xFF0D9488));

    companion object {
        fun fromId(id: String?): AccentColor = entries.firstOrNull { it.id == id } ?: SYSTEM
    }
}

/** 色彩风格：决定配色整体的饱和度与色相走向。 */
enum class PaletteStyle(val id: String, val labelRes: Int) {
    TONAL_SPOT("tonal_spot", R.string.appearance_palette_tonal_spot),
    NEUTRAL("neutral", R.string.appearance_palette_neutral),
    VIBRANT("vibrant", R.string.appearance_palette_vibrant),
    EXPRESSIVE("expressive", R.string.appearance_palette_expressive),
    RAINBOW("rainbow", R.string.appearance_palette_rainbow),
    FRUIT_SALAD("fruit_salad", R.string.appearance_palette_fruit_salad),
    MONOCHROME("monochrome", R.string.appearance_palette_monochrome),
    FIDELITY("fidelity", R.string.appearance_palette_fidelity),
    CONTENT("content", R.string.appearance_palette_content);

    companion object {
        fun fromId(id: String?): PaletteStyle = entries.firstOrNull { it.id == id } ?: TONAL_SPOT
    }
}

/** 界面样式。STANDARD 为原有观感，MIUIX 为 Miui 风格的圆角与层次。 */
enum class UiStyle(val id: String, val labelRes: Int) {
    STANDARD("standard", R.string.appearance_ui_style_standard),
    MIUIX("miuix", R.string.appearance_ui_style_miuix);

    companion object {
        fun fromId(id: String?): UiStyle = entries.firstOrNull { it.id == id } ?: STANDARD
    }
}

/**
 * [二开] 当前界面样式，供业务组件读取。
 * Miuix 下分组卡片 / 列表行 / 按钮形态会切换成 Miui 语言，而不是只换色板。
 */
val LocalUiStyle = staticCompositionLocalOf { UiStyle.STANDARD }

/**
 * [二开] 是否正在显示自定义背景。
 * 页面据此让出底色（透明），背景层才能从内容下方透出来。
 */
val LocalBackgroundVisible = staticCompositionLocalOf { false }

/** 当前外观的全部可调项，由 ThemeSettingsRepository 组装后传给主题。 */
data class AppearanceOptions(
    val accent: AccentColor = AccentColor.SYSTEM,
    val paletteStyle: PaletteStyle = PaletteStyle.TONAL_SPOT,
    val uiStyle: UiStyle = UiStyle.STANDARD,
    val pureBlack: Boolean = false,
    val interfaceScale: Float = 1f
) {
    companion object {
        const val MIN_SCALE = 0.85f
        const val MAX_SCALE = 1.15f
        const val DEFAULT_SCALE = 1f

        fun normalizeScale(value: Float): Float =
            if (value.isFinite()) value.coerceIn(MIN_SCALE, MAX_SCALE) else DEFAULT_SCALE
    }
}

/** 对单个颜色做饱和度缩放与色相偏移（HSV 空间）。 */
private fun Color.adjustSaturation(satScale: Float, hueShiftDeg: Float = 0f): Color {
    val hsv = FloatArray(3)
    AndroidColor.RGBToHSV(
        (red * 255f).roundToInt().coerceIn(0, 255),
        (green * 255f).roundToInt().coerceIn(0, 255),
        (blue * 255f).roundToInt().coerceIn(0, 255),
        hsv
    )
    hsv[1] = (hsv[1] * satScale).coerceIn(0f, 1f)
    hsv[0] = (hsv[0] + hueShiftDeg + 360f) % 360f
    return Color(AndroidColor.HSVToColor(hsv))
}

/** 用 [tint] 以 [amount] 的比例给 [this] 上色（用于让配色风格影响页面底色）。 */
private fun Color.tintWith(tint: Color, amount: Float): Color =
    if (amount <= 0f) this else tint.copy(alpha = amount).compositeOver(this)

/** 主色与底色的混合，用于生成 container 色。 */
private fun tintedContainer(primary: Color, surface: Color, dark: Boolean): Color =
    primary.copy(alpha = if (dark) 0.30f else 0.16f).compositeOver(surface)

/**
 * 把基础配色按外观选项变换成最终 [ColorScheme]。
 *
 * @param base 来自配色预设或 Monet 取色的基础配色
 * @param dark 是否深色
 */
fun buildAppearanceColorScheme(
    base: ColorScheme,
    dark: Boolean,
    options: AppearanceOptions
): ColorScheme {
    val accent = options.accent.color
    val satScale = when (options.paletteStyle) {
        PaletteStyle.TONAL_SPOT -> 1f
        PaletteStyle.NEUTRAL -> 0.35f
        PaletteStyle.VIBRANT -> 1.45f
        PaletteStyle.EXPRESSIVE -> 1.2f
        PaletteStyle.RAINBOW -> 1.3f
        PaletteStyle.FRUIT_SALAD -> 1.25f
        PaletteStyle.MONOCHROME -> 0f
        PaletteStyle.FIDELITY -> 1.05f
        PaletteStyle.CONTENT -> 1f
    }
    val hueShift = when (options.paletteStyle) {
        PaletteStyle.EXPRESSIVE -> 40f
        PaletteStyle.RAINBOW -> 120f
        PaletteStyle.FRUIT_SALAD -> 200f
        else -> 0f
    }

    val primary = accent ?: base.primary
    val adjustedPrimary = primary.adjustSaturation(satScale)
    val secondary = when (options.paletteStyle) {
        // 彩虹/水果沙拉刻意拉开邻近色，其余风格次级色只做轻微偏移
        PaletteStyle.RAINBOW -> primary.adjustSaturation(satScale, 60f)
        PaletteStyle.FRUIT_SALAD -> primary.adjustSaturation(satScale, -70f)
        else -> primary.adjustSaturation(satScale, hueShift / 2f + 24f)
    }
    val tertiary = primary.adjustSaturation(satScale, hueShift + 48f)

    // [二开] 配色风格不只影响主色：页面底色/卡片也按比例蘸一点主色，
    // 否则「色彩风格」看起来只有按钮变色，整屏观感几乎不变。
    val tintStrength = when (options.paletteStyle) {
        PaletteStyle.TONAL_SPOT -> 0.02f
        PaletteStyle.NEUTRAL -> 0f
        PaletteStyle.VIBRANT -> 0.09f
        PaletteStyle.EXPRESSIVE -> 0.07f
        PaletteStyle.RAINBOW -> 0.06f
        PaletteStyle.FRUIT_SALAD -> 0.06f
        PaletteStyle.MONOCHROME -> 0f
        PaletteStyle.FIDELITY -> 0.03f
        PaletteStyle.CONTENT -> 0.03f
    }
    val surface = when {
        options.pureBlack && dark -> Color.Black
        else -> base.surface.tintWith(adjustedPrimary, tintStrength)
    }
    val background = when {
        options.pureBlack && dark -> Color.Black
        else -> base.background.tintWith(adjustedPrimary, tintStrength * 1.3f)
    }
    val surfaceVariant = when {
        options.pureBlack && dark -> Color(0xFF121212)
        else -> base.surfaceVariant.tintWith(adjustedPrimary, tintStrength * 1.6f)
    }

    return base.copy(
        primary = adjustedPrimary,
        onPrimary = base.onPrimary,
        primaryContainer = tintedContainer(adjustedPrimary, surface, dark),
        onPrimaryContainer = if (dark) adjustedPrimary.adjustSaturation(0.6f) else adjustedPrimary,
        secondary = secondary,
        secondaryContainer = tintedContainer(secondary, surface, dark),
        tertiary = tertiary,
        tertiaryContainer = tintedContainer(tertiary, surface, dark),
        background = background,
        onBackground = base.onBackground,
        surface = surface,
        onSurface = base.onSurface,
        surfaceVariant = surfaceVariant,
        onSurfaceVariant = base.onSurfaceVariant,
        surfaceContainer = if (options.pureBlack && dark) Color(0xFF0A0A0A) else base.surfaceContainer,
        surfaceContainerHigh = if (options.pureBlack && dark) Color(0xFF141414) else base.surfaceContainerHigh
    )
}
