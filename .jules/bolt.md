## 2024-04-14 - Prevent Overdraw with DrawCache
**Learning:** Multiple overlapping `Box` elements with backgrounds or gradients can cause severe overdraw and Compose node overhead (tree depth/width) on Android TV (e.g., in ModernHome layout).
**Action:** When adding gradients or backgrounds over media elements, consolidate them into a single `Box` using `Modifier.drawWithCache` and `onDrawWithContent` (or `onDrawBehind`) instead of stacking separate `Box` layers.
