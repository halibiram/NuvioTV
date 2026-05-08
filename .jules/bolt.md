## 2024-05-24 - Overlapping Composables create expensive tree depth
**Learning:** Combining overlapping Compose modifiers into a single composable using `.drawWithCache` alongside `onDrawWithContent` helps bypass an entire Compose node layout pass.
**Action:** Consolidate multiple overlapping elements such as gradients acting as an overlay by doing the drawing operations directly inside the background composable to avoid unnecessary extra components in the node tree and improve rendering speeds, specifically targeting overdraw optimizations for Android TV.
