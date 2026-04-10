## $(date +%Y-%m-%d) - [Reduce overdraw and composition depth in Android TV Compose]
**Learning:** Multiple overlapping `Box` elements with backgrounds or gradients cause severe overdraw and node overhead on Android TV.
**Action:** Combine them into a single `Box` using `Modifier.drawWithCache` and `onDrawWithContent` or `onDrawBehind` when consolidating background effects over media layers.
