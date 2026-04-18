## 2024-05-18 - Nested Boxes
**Learning:** Found nested boxes causing Compose node overhead.
**Action:** Consolidate multiple overlapping Box elements into a single Box using Modifier.drawWithCache and onDrawBehind (or onDrawWithContent).
