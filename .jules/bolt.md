## 2026-04-08 - Fixed compose node overhead in ModernHomeContent
**Learning:** In Android TV development, using multiple overlapping `Box` elements with backgrounds or gradients increases Compose node overhead (tree depth/width).
**Action:** Consolidate these into a single `Box` using `Modifier.drawWithCache` and `onDrawBehind` to draw multiple backgrounds/gradients efficiently onto a single canvas, avoiding extra composable nodes.
