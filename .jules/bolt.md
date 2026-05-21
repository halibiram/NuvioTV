## 2024-05-17 - Eliminate Overlapping Compose Nodes on Android TV
**Learning:** Multiple overlapping Box elements with backgrounds or gradients cause severe overdraw issues and Compose node overhead (tree depth/width) on Android TV devices.
**Action:** Consolidate them into a single Box by using `Modifier.drawWithCache` and `onDrawWithContent` to draw gradients on top of existing media, eliminating an entire node layer. Access `layoutDirection` directly from `DrawCacheModifierScope` instead of `LocalLayoutDirection.current`.
