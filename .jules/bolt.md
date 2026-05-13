
## 2024-05-13 - Compose Box Node Layer Elimination
**Learning:** To prevent severe overdraw issues and Compose node overhead (tree depth/width) on Android TV (e.g., in ModernHome layout), multiple overlapping Box elements with backgrounds or gradients should be consolidated into a single Box. Use `Modifier.drawWithCache` and `onDrawWithContent` to draw gradients on top of existing media, eliminating an entire node layer.
**Action:** When I see a separate `Box` rendering a gradient on top of another `Box` rendering an image, combine them by using `drawWithCache` and `onDrawWithContent` on the container or the image's modifier to eliminate the extra `Box` layer.
