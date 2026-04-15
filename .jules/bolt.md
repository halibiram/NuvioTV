## 2024-05-14 - Compose Modifiers drawWithCache
**Learning:** In Jetpack Compose, using multiple overlapping `Box` elements with different backgrounds (e.g. gradients) causes severe overdraw and adds overhead to the Compose node tree.
**Action:** Consolidate multiple overlapping `Box` layouts with `background` or `gradient` modifiers into a single `Box` using `Modifier.drawWithCache` and `onDrawBehind` to draw multiple layers sequentially on a single node.
