## 2024-05-18 - Consolidate overlapping Compose nodes using Modifier.drawWithCache
**Learning:** In Compose, stacking multiple Box elements with full screen sizes for drawing gradients over media creates unnecessary depth in the node tree and may apply the parent modifier multiple times if passed to both sibling boxes.
**Action:** When a Box is only used to draw backgrounds or gradients on top of an existing component, consolidate them into a single node using `Modifier.drawWithCache` and `onDrawWithContent`. Ensure `drawContent()` is called first so gradients render on top.
