package com.aicode.core.ui

import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.ui.unit.IntOffset
import com.aicode.core.theme.Motion

/**
 * 整页切换的过渡规范（Material「shared axis X」）：两个页面**同向**横移，前进时一起向左推、
 * 返回时一起向右退，配合错开的淡出/淡入。顶层路由（NavHost）与设置页内部分区共用同一份，
 * 免得两处各写一套参数、动画节奏不一致。
 *
 * 为什么是同向小位移而不是 iOS 那种「新页盖住旧页 / 旧页抽走露出下层」：
 * `AnimatedContent` 里**入场内容恒被放在最上层**（同 zIndex 时 target 最后放置），而
 * navigation-compose 的 NavHost 不暴露 `targetContentZIndex`，返回时没法把被弹出的页面盖在上面。
 * 同向平移不依赖 z 序，任何方向都不会出现「该露出的页面被遮住」。
 *
 * 淡出比淡入快且淡入延后一点，两页几乎不同时可见，避免重叠期两层内容混色发糊。
 * 曲线与时长统一取 [Motion] token，与弹层、开关的节奏一致。
 */
const val PAGE_MOTION_MS = Motion.PAGE_MS

private const val PAGE_FADE_IN_MS = 220
private const val PAGE_FADE_IN_DELAY_MS = 70
private const val PAGE_FADE_OUT_MS = 100

/** 横移幅度占容器宽度的比例。整屏位移在大屏上像「甩」页，取五分之一足够表达方向。 */
private const val PAGE_SLIDE_FRACTION = 0.2f

private val PageSlideSpec = tween<IntOffset>(
    durationMillis = PAGE_MOTION_MS,
    easing = Motion.Decelerate
)

/**
 * 入场页过渡：[forward] 为 true（进入下一层）时从右侧滑入，false（返回上一层）时从左侧滑入。
 */
fun pageEnter(forward: Boolean): EnterTransition =
    slideInHorizontally(PageSlideSpec) { width ->
        val offset = (width * PAGE_SLIDE_FRACTION).toInt()
        if (forward) offset else -offset
    } + fadeIn(tween(PAGE_FADE_IN_MS, delayMillis = PAGE_FADE_IN_DELAY_MS))

/** 退场页过渡：与 [pageEnter] 同向，前进时向左退出、返回时向右退出。 */
fun pageExit(forward: Boolean): ExitTransition =
    slideOutHorizontally(PageSlideSpec) { width ->
        val offset = (width * PAGE_SLIDE_FRACTION).toInt()
        if (forward) -offset else offset
    } + fadeOut(tween(PAGE_FADE_OUT_MS))
