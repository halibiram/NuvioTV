
## 2024-05-18 - Overdraw Optimization with Modifier.drawWithCache
**Learning:** Consolidating overlapping `Box` elements with backgrounds or gradients into a single `Box` using a custom modifier with `Modifier.drawWithCache` and `onDrawWithContent` significantly reduces Compose node overhead (tree depth/width) on Android TV. The layoutDirection can also be accessed directly from `DrawCacheModifierScope`.
**Action:** When developing for Android TV or observing high node counts, look for overlapping decorative `Box` elements (especially gradients or background dimming on top of media) and refactor them into drawing operations within a single modifier applied to the primary component.
