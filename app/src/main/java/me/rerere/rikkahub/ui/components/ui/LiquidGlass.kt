package me.rerere.rikkahub.ui.components.ui

// Modified by AI Hello World on 2026-09-25.
// This file is part of LiquidHub, a fork of RikkaHub.
// Licensed under AGPL-3.0.

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.chrisbanes.haze.HazeInput
import dev.chrisbanes.haze.blur.HazeBlurStyle
import dev.chrisbanes.haze.blur.hazeBlur
import dev.chrisbanes.haze.blur.material3.Material3
import dev.chrisbanes.haze.glass.GlassDefaults
import dev.chrisbanes.haze.glass.GlassStyle
import dev.chrisbanes.haze.glass.OpticalSizeValue
import dev.chrisbanes.haze.glass.hazeGlass
import dev.chrisbanes.haze.glass.material3.Material3
import me.rerere.rikkahub.ui.theme.LocalDarkMode

/**
 * 液态玻璃视觉参数。
 *
 * 毛玻璃（Haze）负责背景模糊，本层再叠加“多层渐变 + 边缘高光”，
 * 得到比纯 blur 更接近真实玻璃的层次感。
 */
object LiquidGlassDefaults {
    /** 连续大圆角，接近 iOS 26 液态玻璃的轮廓。 */
    val Shape: RoundedCornerShape = RoundedCornerShape(28.dp)
    const val TintAlpha: Float = 0.62f

    /** Android < 12 无系统级模糊，回退为更实的半透明纯色以保证可读性。 */
    const val FallbackAlpha: Float = 0.92f
    const val BorderAlpha: Float = 0.42f
    const val HighlightAlpha: Float = 0.18f

    /** 玻璃折射/模糊强度，数值越大越明显。 */
    val BlurRadius: Dp = 26.dp
    const val GlassDepth: Float = 0.55f

    @Composable
    @ReadOnlyComposable
    fun containerColor(): Color {
        val scheme = MaterialTheme.colorScheme
        // 深色/AMOLED：用略亮的容器色托起玻璃卡片，保证文字对比度。
        return if (LocalDarkMode.current) scheme.surfaceContainerHigh else scheme.surface
    }
}

/** 边缘高光描边：左上受光、右下收暗，模拟玻璃折射边缘。 */
fun Modifier.liquidGlassEdge(
    shape: Shape,
    alpha: Float = LiquidGlassDefaults.BorderAlpha,
    width: Dp = 1.dp,
): Modifier = border(
    width = width,
    brush = Brush.linearGradient(
        colors = listOf(
            Color.White.copy(alpha = alpha),
            Color.White.copy(alpha = alpha * 0.18f),
            Color.White.copy(alpha = 0.02f),
        ),
    ),
    shape = shape,
)

enum class LiquidGlassMode { GLASS, BLUR }

/**
 * 只绘制液态玻璃背景与边缘高光，不含内容层。
 *
 * 适合顶栏这类贴边固定区域，避免为列表项逐个加模糊。
 */
@Composable
fun Modifier.liquidGlassHost(
    input: HazeInput?,
    shape: RoundedCornerShape,
    tint: Color,
    tintAlpha: Float = LiquidGlassDefaults.TintAlpha,
    borderAlpha: Float = LiquidGlassDefaults.BorderAlpha,
    mode: LiquidGlassMode = LiquidGlassMode.GLASS,
): Modifier {
    val hazeInput = input?.takeIf { Build.VERSION.SDK_INT >= Build.VERSION_CODES.S }
    val containerColor = tint.copy(alpha = if (hazeInput != null) tintAlpha else LiquidGlassDefaults.FallbackAlpha)
    return this
        .clip(shape)
        .then(
            if (hazeInput != null) {
                when (mode) {
                    LiquidGlassMode.GLASS -> Modifier.hazeGlass(
                        input = hazeInput,
                        style = GlassStyle.Material3(
                            containerColor = tint,
                            tint = containerColor,
                        ) {
                            optics(
                                GlassDefaults.optics.copy(
                                    blurRadius = OpticalSizeValue.Fixed(LiquidGlassDefaults.BlurRadius),
                                    depth = OpticalSizeValue.Fixed(LiquidGlassDefaults.GlassDepth),
                                ),
                            )
                            shape(shape)
                        },
                    )

                    LiquidGlassMode.BLUR -> Modifier.hazeBlur(
                        input = hazeInput,
                        style = HazeBlurStyle.Material3 { blurRadius(LiquidGlassDefaults.BlurRadius) },
                    )
                }
            } else {
                Modifier.background(containerColor)
            },
        )
        .liquidGlassEdge(shape = shape, alpha = borderAlpha)
}

/**
 * 液态玻璃容器。
 *
 * @param input Haze 数据源；为 null 或系统低于 Android 12 时自动降级为半透明纯色。
 * @param mode GLASS 使用光学折射，BLUR 使用普通背景模糊。
 */
@Composable
fun LiquidGlassSurface(
    input: HazeInput?,
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = LiquidGlassDefaults.Shape,
    tint: Color = LiquidGlassDefaults.containerColor(),
    tintAlpha: Float = LiquidGlassDefaults.TintAlpha,
    borderAlpha: Float = LiquidGlassDefaults.BorderAlpha,
    highlightAlpha: Float = LiquidGlassDefaults.HighlightAlpha,
    mode: LiquidGlassMode = LiquidGlassMode.GLASS,
    content: @Composable BoxScope.() -> Unit,
) {
    Box(
        modifier.liquidGlassHost(
            input = input,
            shape = shape,
            tint = tint,
            tintAlpha = tintAlpha,
            borderAlpha = borderAlpha,
            mode = mode,
        ),
    ) {
        // 顶部斜向高光，让玻璃表面有“受光面”。
        Box(
            Modifier
                .matchParentSize()
                .clip(shape)
                .background(
                    Brush.verticalGradient(
                        0f to Color.White.copy(alpha = highlightAlpha),
                        0.42f to Color.Transparent,
                    ),
                ),
        )
        content()
    }
}
