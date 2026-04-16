## 2026-04-16 - Avoid overlapping Box elements for backgrounds/gradients on Android TV
**Learning:** Overlapping `Box` elements just to draw backgrounds or gradients causes significant Compose node overhead and overdraw, especially on Android TV which has strict performance constraints.
**Action:** Instead of stacking multiple `Box` elements, consolidate them by using `Modifier.drawWithCache` and `onDrawWithContent` (or `onDrawBehind`) on the underlying media layer's modifier.
