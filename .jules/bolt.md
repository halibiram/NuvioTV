## 2024-05-17 - Compose UI Gradient Node Optimization
**Learning:** In Compose on Android TV, multiple overlapping Box elements with backgrounds or gradients can cause severe overdraw issues and Compose node overhead (tree depth/width).
**Action:** Consolidate multiple overlapping Box elements into a single Box. Use `Modifier.drawWithCache` and `onDrawWithContent` to draw gradients on top of existing media, eliminating an entire node layer.
