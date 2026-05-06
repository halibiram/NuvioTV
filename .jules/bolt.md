## 2024-05-24 - Node Overhead & Overdraw on Android TV
**Learning:** Overlapping Box components and separate composables for overlays (like gradients) create severe Compose node overhead (tree depth/width) and overdraw issues on Android TV, degrading performance during media playback/UI navigation.
**Action:** When applying gradients or visual overlays on top of media/images, consolidate them into a single Compose Box element by using `Modifier.drawWithCache` combined with `onDrawWithContent` on the background modifier instead of layering separate Box components.
