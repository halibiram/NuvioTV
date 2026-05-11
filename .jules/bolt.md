## 2024-05-24 - Compose Android TV Overdraw Reduction
**Learning:** Multiple overlapping Box elements with backgrounds or gradients cause severe overdraw and node overhead in Compose on Android TV, significantly impacting performance on lower-end devices.
**Action:** Always prefer consolidating visual layers (like background gradients over media) into a single Box using `Modifier.drawWithCache` and `onDrawWithContent` instead of stacking multiple Composable nodes.
