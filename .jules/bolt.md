## 2024-05-04 - Eliminate Compose Node Overhead on Android TV
**Learning:** Overlapping sibling `Box` elements with backgrounds or gradients can cause severe overdraw issues and Compose node overhead (tree depth/width) on Android TV (e.g., in `ModernHome` layout).
**Action:** Use `Modifier.drawWithCache` and `onDrawWithContent` to draw gradients directly on top of existing media elements. This eliminates entire node layers in the Compose tree and reduces overdraw.
