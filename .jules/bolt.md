## 2024-04-13 - [Overdraw in ModernHomeContent Box elements]
**Learning:** Overlapping Box elements in ModernHomeContent (and possibly other compose elements) causes overdraw issues on Android TV and Compose node overhead. Consolidate them using Modifier.drawWithCache and onDrawBehind.
**Action:** Always check Box layout stacking and consolidate when multiple layers just add colors/gradients to the background.
