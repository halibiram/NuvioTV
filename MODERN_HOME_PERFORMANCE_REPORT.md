# Modern Home Layout Performance Report

## Executive Summary

The "high frames" (frame drop/jank) issue in the Modern Home layout is primarily caused by **severe overdraw** resulting from stacking multiple full-screen gradient layers and semi-transparent backgrounds. Additionally, animating the alpha of video surfaces and layout thrashing in list items contribute significantly to the performance degradation on Android TV hardware.

## detailed Findings

### 1. Excessive Overdraw (Gradient Stacking)

The current implementation draws **6 layers of full-screen (or near full-screen) semi-transparent pixels** over the hero content. On Android TV devices, which often have limited GPU fill-rate, this causes the GPU to spend most of its time blending pixels rather than rendering new frames.

**Identified Layers:**

1.  **`ModernHeroMediaLayer`**:
    *   **Hero Image/Video**: The base content.
    *   **`drawWithCache` Overlay**: Draws a *horizontal gradient* AND a *radial gradient* in a single pass.
    *   **`verticalOverlayGradient`**: A separate `Box` drawing a vertical gradient.
2.  **`ModernHomeContent`**:
    *   **`dimColor`**: A full-screen `Box` with `alpha = 0.08f`.
    *   **`leftGradient`**: A full-screen `Box` with a horizontal gradient.
    *   **`bottomGradient`**: A full-screen `Box` with a vertical gradient.

**Impact**: The GPU draws every pixel on the screen at least 6 times per frame, involving complex alpha blending.

### 2. Video Compositing Costs

In `ModernHeroMediaLayer` (and potentially `ModernCarouselCard`), the `TrailerPlayer` is wrapped in a `graphicsLayer` that animates `alpha`.

```kotlin
TrailerPlayer(
    // ...
    modifier = Modifier
        .fillMaxSize()
        .graphicsLayer { alpha = heroTrailerAlpha }
)
```

`TrailerPlayer` uses `ExoPlayer`'s `PlayerView`, which typically uses a `SurfaceView`. Animating the alpha of a `SurfaceView` forces the system to composite the video surface off-screen before blending it with the window. This is an extremely expensive operation for 4K video playback on TV hardware and frequently causes frame drops during transitions.

### 3. Layout Thrashing in Cards

`ModernCarouselCard` uses `animateDpAsState` to animate the card width when expanded:

```kotlin
val animatedCardWidth by if (focusedPosterBackdropExpandEnabled) {
    animateDpAsState(targetValue = targetCardWidth, ...)
}
```

When this value changes, it triggers a layout pass for the card and potentially its parent `LazyRow`. If this animation runs while other UI elements are updating or scrolling, it contributes to jank.

### 4. Blur Overhead

`ModernSidebarBlurPanel` utilizes `dev.chrisbanes.haze.hazeChild` to apply a real-time blur effect. While this appears to be conditional, any active blur on Android TV is performance-intensive.

## Recommendations

### 1. Consolidate Gradients

Merge the multiple gradient layers into a single `drawWithCache` modifier. Instead of stacking 3 `Box` composables in `ModernHomeContent`, draw all necessary gradients in one drawing pass.

**Proposed Change:**
Remove the 3 `Box` layers in `ModernHomeContent` and the overlay `Box` layers in `ModernHeroMediaLayer`. Create a unified `ModernHomeOverlay` composable that draws the dim, left, bottom, and radial gradients in a single `onDrawBehind` block. This reduces the overdraw from ~6x to ~2x (Content + 1 Overlay).

### 2. Optimize Video Transitions

Avoid animating the alpha of the `TrailerPlayer` directly if possible.
*   **Alternative**: Fade a solid color overlay *over* the video instead of fading the video itself.
*   **Optimization**: Ensure `SurfaceView` is used (default) and avoid applying `alpha` to it unless absolutely necessary. If cross-fading is required, consider if the visual quality trade-off of `TextureView` is worth it (though `TextureView` consumes more battery/power, it handles alpha better, but on TV `SurfaceView` is preferred for smooth playback).
*   **Best approach**: Keep `SurfaceView` opaque and overlay a black `Box` whose alpha animates from 1f to 0f.

### 3. Optimize List Item Layouts

*   Review the `animateDpAsState` usage. If possible, use `graphicsLayer` scale for expansion effects to avoid re-layout, though this might not achieve the desired reflow effect.
*   Ensure `LazyRow` and `LazyColumn` state management is optimized to avoid unnecessary recompositions of items that are not changing.

### 4. Reduce Blur Usage

Ensure that the blur effect in `ModernSidebarBlurPanel` is completely disabled (not just invisible) when the sidebar is collapsed to free up GPU resources.

## Conclusion

Implementing the **Gradient Consolidation** (Recommendation 1) is the highest priority and will yield the most significant performance improvement. Optimizing the video transition (Recommendation 2) will further smooth out the "hero" interactions.
