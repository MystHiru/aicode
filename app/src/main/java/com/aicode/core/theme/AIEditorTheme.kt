package com.aicode.core.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object Spacing {
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 24.dp
    val xxl = 32.dp
}

/**
 * 圆角 token。取值随 [AppStyle] 变化：业务代码一律引用 `Radius.md` 这类名字，
 * 切换风格档时数值整体调大，不需要改任何调用点。
 *
 * 用可变字段而不是 CompositionLocal：这些值也在非 Composable 上下文被引用，
 * 且风格只在设置里切换一次，AIEditorTheme 重组时读到的就是新值。
 */
object Radius {
    internal var style: AppStyle = AppStyle.CLASSIC

    val xs: Dp get() = if (style == AppStyle.MUSE) 6.dp else 4.dp
    val sm: Dp get() = if (style == AppStyle.MUSE) 10.dp else 8.dp
    val md: Dp get() = if (style == AppStyle.MUSE) 12.dp else 10.dp
    val mdLarge: Dp get() = if (style == AppStyle.MUSE) 14.dp else 12.dp
    val lg: Dp get() = if (style == AppStyle.MUSE) 18.dp else 14.dp
    val xl: Dp get() = if (style == AppStyle.MUSE) 20.dp else 16.dp
    val pill: Dp get() = 999.dp
}

object Brand {
    val Blue = Color(0xFF2563EB)
    val Sky = Color(0xFF38BDF8)
    val IconGray = Color(0xFF64748B)
    val Orange = Color(0xFFF57C00)
}

/** 全局统一语义色彩，解决业务代码私自 hardcode 颜色问题。 */
data class AppSemanticColors(
    val success: Color,
    val onSuccess: Color,
    val successContainer: Color,
    val onSuccessContainer: Color,
    val warning: Color,
    val onWarning: Color,
    val warningContainer: Color,
    val onWarningContainer: Color,
    val info: Color,
    val onInfo: Color,
    val infoContainer: Color,
    val onInfoContainer: Color,
    val diffAdd: Color,
    val diffAddBg: Color,
    val diffRemove: Color,
    val diffRemoveBg: Color,
    val subtleText: Color,
    val subtleBorder: Color,
    val cardSurface: Color,
    val pageBackground: Color,
    val mutedSurface: Color,
    val capsuleSurface: Color,
    val buttonMutedBg: Color
)

val LightSemanticColors = AppSemanticColors(
    success = Color(0xFF22C55E),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFDCFCE7),
    onSuccessContainer = Color(0xFF15803D),
    warning = Color(0xFFF59E0B),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFEF3C7),
    onWarningContainer = Color(0xFF92400E),
    info = Color(0xFF0284C7),
    onInfo = Color(0xFFFFFFFF),
    infoContainer = Color(0xFFE0F2FE),
    onInfoContainer = Color(0xFF075985),
    diffAdd = Color(0xFF22C55E),
    diffAddBg = Color(0x2622C55E),
    diffRemove = Color(0xFFEF4444),
    diffRemoveBg = Color(0x26EF4444),
    subtleText = Color(0xFF8E8E93),
    subtleBorder = Color(0xFFE5E5EA),
    cardSurface = Color(0xFFFFFFFF),
    pageBackground = Color(0xFFF8F8F8),
    mutedSurface = Color(0xFFF2F2F7),
    capsuleSurface = Color(0xFFE9E9EB),
    buttonMutedBg = Color(0xFFF0F2F5)
)

val DarkSemanticColors = AppSemanticColors(
    success = Color(0xFF4ADE80),
    onSuccess = Color(0xFF052E16),
    successContainer = Color(0x59052E16),
    onSuccessContainer = Color(0xFFBBF7D0),
    warning = Color(0xFFFBBF24),
    onWarning = Color(0xFF78350F),
    warningContainer = Color(0x5978350F),
    onWarningContainer = Color(0xFFFDE68A),
    info = Color(0xFF38BDF8),
    onInfo = Color(0xFF0C4A6E),
    infoContainer = Color(0x590C4A6E),
    onInfoContainer = Color(0xFFBAE6FD),
    diffAdd = Color(0xFF4ADE80),
    diffAddBg = Color(0x334ADE80),
    diffRemove = Color(0xFFF87171),
    diffRemoveBg = Color(0x33F87171),
    subtleText = Color(0xFF94A3B8),
    subtleBorder = Color(0xFF2A3F56),
    cardSurface = Color(0xFF0D1B2E),
    pageBackground = Color(0xFF07111F),
    mutedSurface = Color(0xFF13273F),
    capsuleSurface = Color(0xFF1E293B),
    buttonMutedBg = Color(0xFF1E293B)
)

val LocalAppSemanticColors = staticCompositionLocalOf { LightSemanticColors }

val MaterialTheme.semanticColors: AppSemanticColors
    @Composable
    @ReadOnlyComposable
    get() = LocalAppSemanticColors.current

/** Git 模块统一 8 色泳道调色板 */
object GitLanePalette {
    val colors = listOf(
        Color(0xFF2563EB), // 1. 经典蓝
        Color(0xFF16A34A), // 2. 翠绿
        Color(0xFFF59E0B), // 3. 琥珀
        Color(0xFF8B5CF6), // 4. 优雅紫
        Color(0xFF06B6D4), // 5. 青蓝
        Color(0xFFEF4444), // 6. 珊瑚红
        Color(0xFFEC4899), // 7. 亮粉
        Color(0xFFEAB308)  // 8. 柠檬金
    )
}

/** Git 状态徽章统一色彩映射 */
object GitStatusColors {
    val Added = Color(0xFF16A34A)
    val Modified = Color(0xFFD97706)
    val Deleted = Color(0xFFDC2626)
    val Renamed = Color(0xFF2563EB)
    val Untracked = Color(0xFF94A3B8)
    val Conflict = Color(0xFF9333EA)
    val TypeChanged = Color(0xFF0891B2)
    val Default = Color(0xFF64748B)
}

/** 日志级别统一色彩映射 */
object LogLevelColors {
    val Verbose = Color(0xFF94A3B8)
    val Debug = Color(0xFF3B82F6)
    val Info = Color(0xFF22C55E)
    val Warn = Color(0xFFF59E0B)
    val Error = Color(0xFFEF4444)
    val None = Color(0xFF64748B)
}

/** Token / 统计图表统一调色板 */
object TokenStatsPalette {
    val Input = Color(0xFF3B82F6)
    val Output = Color(0xFFF59E0B)
    val Cache = Color(0xFF8B5CF6)
    val Error = Color(0xFFEF4444)
    val Cancelled = Color(0xFF94A3B8)
    val Progress = Color(0xFF22C55E)
}

/** 存储空间页各占用分类的色标（堆叠条与行前色点共用）。 */
object StorageUsagePalette {
    val Chat = Color(0xFF3B82F6)
    val Container = Color(0xFF8B5CF6)
    val ContainerImages = Color(0xFFA78BFA)
    val Workspaces = Color(0xFF22C55E)
    val AiConfig = Color(0xFF14B8A6)
    val Checkpoints = Color(0xFFF59E0B)
    val Logs = Color(0xFFF97316)
    val Caches = Color(0xFF94A3B8)
    val OtherData = Color(0xFFCBD5E1)
    val Apk = Color(0xFF64748B)
}

internal val DarkColorScheme = darkColorScheme(
    primary = Color(0xFF60A5FA),
    onPrimary = Color(0xFF082F49),
    primaryContainer = Color(0xFF0F3A63),
    onPrimaryContainer = Color(0xFFDBEAFE),
    secondary = Color(0xFF7DD3FC),
    onSecondary = Color(0xFF082F49),
    secondaryContainer = Color(0xFF0C4A6E),
    onSecondaryContainer = Color(0xFFBAE6FD),
    tertiary = Color(0xFF22C55E),
    tertiaryContainer = Color(0xFF14532D),
    onTertiaryContainer = Color(0xFFBBF7D0),
    background = Color(0xFF07111F),
    onBackground = Color(0xFFEAF2FF),
    surface = Color(0xFF0D1B2E),
    onSurface = Color(0xFFEAF2FF),
    surfaceVariant = Color(0xFF13273F),
    onSurfaceVariant = Color(0xFFB8C7DA),
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color(0xFF050C17),
    surfaceContainerLow = Color(0xFF07111F),
    surfaceContainer = Color(0xFF0D1B2E),
    surfaceContainerHigh = Color(0xFF13273F),
    surfaceContainerHighest = Color(0xFF1B3350),
    surfaceBright = Color(0xFF24405F),
    surfaceDim = Color(0xFF07111F),
    outline = Color(0xFF64748B),
    outlineVariant = Color(0xFF334155),
    error = Color(0xFFF87171),
    onError = Color(0xFF450A0A),
    errorContainer = Color(0xFF7F1D1D),
    onErrorContainer = Color(0xFFFECACA)
)

internal val LightColorScheme = lightColorScheme(
    primary = Brand.Blue,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF0B3B76),
    secondary = Color(0xFF0284C7),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE0F2FE),
    onSecondaryContainer = Color(0xFF075985),
    tertiary = Color(0xFF16A34A),
    tertiaryContainer = Color(0xFFDCFCE7),
    onTertiaryContainer = Color(0xFF15803D),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFEAF4FF),
    onSurfaceVariant = Color(0xFF475569),
    surfaceTint = Color.White,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFAFCFF),
    surfaceContainer = Color(0xFFF4F9FF),
    surfaceContainerHigh = Color(0xFFEAF4FF),
    surfaceContainerHighest = Color(0xFFE0EDFA),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFE8EEF6),
    outline = Color(0xFFD1D1D6),
    outlineVariant = Color(0xFFE5E5EA),
    error = Color(0xFFDC2626),

    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFEE2E2),
    onErrorContainer = Color(0xFF7F1D1D)
)

/**
 * Muse 风格档的配色：MIUIX / HyperOS 观感（更亮的蓝、iOS 系语义色、近黑深色底）。
 *
 * 只在这一档启用，避免影响经典档已有用户的观感；语义色也一并替换，
 * 这样成功/警告/差异色与主色是一套调性。
 */
private val MuseLightSemanticColors = AppSemanticColors(
    success = Color(0xFF00B34A),
    onSuccess = Color(0xFFFFFFFF),
    successContainer = Color(0xFFE6F7ED),
    onSuccessContainer = Color(0xFF0A7F38),
    warning = Color(0xFFFF8A00),
    onWarning = Color(0xFFFFFFFF),
    warningContainer = Color(0xFFFFF3E0),
    onWarningContainer = Color(0xFF8A4B00),
    info = Color(0xFF3482FF),
    onInfo = Color(0xFFFFFFFF),
    infoContainer = Color(0xFFE8F1FF),
    onInfoContainer = Color(0xFF0B4FD8),
    diffAdd = Color(0xFF00B34A),
    diffAddBg = Color(0x1F00B34A),
    diffRemove = Color(0xFFE54545),
    diffRemoveBg = Color(0x1FE54545),
    subtleText = Color(0xFF9A9A9E),
    subtleBorder = Color(0xFFECECEE),
    cardSurface = Color(0xFFFFFFFF),
    pageBackground = Color(0xFFF5F5F6),
    mutedSurface = Color(0xFFF2F2F3),
    capsuleSurface = Color(0xFFF0F0F2),
    buttonMutedBg = Color(0xFFF0F0F2)
)

private val MuseDarkSemanticColors = AppSemanticColors(
    success = Color(0xFF32D74B),
    onSuccess = Color(0xFF052E16),
    successContainer = Color(0x3D32D74B),
    onSuccessContainer = Color(0xFFA8E6B8),
    warning = Color(0xFFFFB340),
    onWarning = Color(0xFF3D2600),
    warningContainer = Color(0x3DFFB340),
    onWarningContainer = Color(0xFFFFD79A),
    info = Color(0xFF4A8DFF),
    onInfo = Color(0xFF04102B),
    infoContainer = Color(0x3D4A8DFF),
    onInfoContainer = Color(0xFFB7D2FF),
    diffAdd = Color(0xFF32D74B),
    diffAddBg = Color(0x3332D74B),
    diffRemove = Color(0xFFFF6B6B),
    diffRemoveBg = Color(0x33FF6B6B),
    subtleText = Color(0xFF8E8E93),
    subtleBorder = Color(0xFF2C2C30),
    cardSurface = Color(0xFF1A1A1D),
    pageBackground = Color(0xFF000000),
    mutedSurface = Color(0xFF232326),
    capsuleSurface = Color(0xFF2A2A2E),
    buttonMutedBg = Color(0xFF252528)
)

private val MuseLightColorScheme = lightColorScheme(
    primary = Color(0xFF3482FF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFE8F1FF),
    onPrimaryContainer = Color(0xFF0B4FD8),
    secondary = Color(0xFF5B8DEF),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFEDF3FF),
    onSecondaryContainer = Color(0xFF1B4FA8),
    tertiary = Color(0xFF00B34A),
    tertiaryContainer = Color(0xFFE6F7ED),
    onTertiaryContainer = Color(0xFF0A7F38),
    background = Color(0xFFF5F5F6),
    onBackground = Color(0xFF0D0D0D),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0D0D0D),
    surfaceVariant = Color(0xFFF2F2F3),
    onSurfaceVariant = Color(0xFF7A7A7E),
    surfaceTint = Color.White,
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFBFBFC),
    surfaceContainer = Color(0xFFF5F5F6),
    surfaceContainerHigh = Color(0xFFF0F0F2),
    surfaceContainerHighest = Color(0xFFEAEAEC),
    surfaceBright = Color(0xFFFFFFFF),
    surfaceDim = Color(0xFFEDEDEF),
    outline = Color(0xFFD9D9DB),
    outlineVariant = Color(0xFFEBEBED),
    error = Color(0xFFE54545),
    onError = Color(0xFFFFFFFF),
    errorContainer = Color(0xFFFDECEC),
    onErrorContainer = Color(0xFF8A1F1F)
)

private val MuseDarkColorScheme = darkColorScheme(
    primary = Color(0xFF4A8DFF),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF14305C),
    onPrimaryContainer = Color(0xFFD6E5FF),
    secondary = Color(0xFF7EB2FF),
    onSecondary = Color(0xFF04102B),
    secondaryContainer = Color(0xFF1B3B66),
    onSecondaryContainer = Color(0xFFCFE0FF),
    tertiary = Color(0xFF32D74B),
    tertiaryContainer = Color(0xFF10361C),
    onTertiaryContainer = Color(0xFFB7EEBE),
    background = Color(0xFF000000),
    onBackground = Color(0xFFF2F2F3),
    surface = Color(0xFF17171A),
    onSurface = Color(0xFFF2F2F3),
    surfaceVariant = Color(0xFF232326),
    onSurfaceVariant = Color(0xFFA0A0A5),
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color(0xFF000000),
    surfaceContainerLow = Color(0xFF0E0E10),
    surfaceContainer = Color(0xFF17171A),
    surfaceContainerHigh = Color(0xFF1F1F22),
    surfaceContainerHighest = Color(0xFF2A2A2E),
    surfaceBright = Color(0xFF33333A),
    surfaceDim = Color(0xFF000000),
    outline = Color(0xFF48484D),
    outlineVariant = Color(0xFF2C2C30),
    error = Color(0xFFFF6B6B),
    onError = Color(0xFF3D0000),
    errorContainer = Color(0xFF5C1010),
    onErrorContainer = Color(0xFFFFC9C9)
)

/** Muse 风格档的完整配色（colorScheme + 语义色）。 */
private fun museThemeColors(darkTheme: Boolean): AppThemeColors =
    if (darkTheme) {
        AppThemeColors(MuseDarkColorScheme, MuseDarkSemanticColors)
    } else {
        AppThemeColors(MuseLightColorScheme, MuseLightSemanticColors)
    }

/** 界面风格：经典沿用默认圆角，Muse 用更大的圆角贴近 MIUIX 观感。 */
enum class AppStyle { CLASSIC, MUSE }

private fun appShapes(style: AppStyle): androidx.compose.material3.Shapes = when (style) {
    AppStyle.CLASSIC -> androidx.compose.material3.Shapes()
    AppStyle.MUSE -> androidx.compose.material3.Shapes(
        extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        small = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        medium = androidx.compose.foundation.shape.RoundedCornerShape(16.dp),
        large = androidx.compose.foundation.shape.RoundedCornerShape(22.dp),
        extraLarge = androidx.compose.foundation.shape.RoundedCornerShape(28.dp)
    )
}

private val AppTypography = Typography().run {
    copy(
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.sp),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = bodyLarge.copy(lineHeight = 24.sp),
        bodyMedium = bodyMedium.copy(lineHeight = 21.sp),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.sp)
    )
}

/**
 * @param preset 用户选定的配色主题。
 * @param dynamicColor 是否启用系统莫奈取色；低于 Android 12 时自动回退到 [preset]。
 */
@Composable
fun AIEditorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    preset: AppThemePreset = AppThemePreset.DEFAULT,
    dynamicColor: Boolean = false,
    style: AppStyle = AppStyle.CLASSIC,
    content: @Composable () -> Unit
) {
    val context = LocalContext.current
    // 先落圆角 token：下面的页面组合时会直接读 Radius.*，必须在此之前更新。
    Radius.style = style
    val themeColors = remember(darkTheme, preset, dynamicColor, style, context) {
        when {
            style == AppStyle.MUSE -> museThemeColors(darkTheme)
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
                val dynamicScheme =
                    if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
                deriveDynamicThemeColors(dynamicScheme, darkTheme)
            }
            darkTheme -> preset.dark
            else -> preset.light
        }
    }

    CompositionLocalProvider(LocalAppSemanticColors provides themeColors.semanticColors) {
        MaterialTheme(
            colorScheme = themeColors.colorScheme,
            typography = AppTypography,
            shapes = appShapes(style),
            content = content
        )
    }
}
