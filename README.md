# PageMint

An independent, offline-first Android document scanner. **MVP / not production validated.**

## What is implemented

- CameraX camera capture and Android system image import.
- Automatic four-corner detection using multiple OpenCV preprocessing strategies.
- Review and drag each corner before perspective correction.
- Perspective rectification at the original decoded image resolution, using a camera-aware aspect estimator or exact A4, Letter and ID-card presets. Longest edge capped at 4800 px, output at 12 megapixels.
- Optional 1.2% safety border to reduce accidental clipping of edge text.
- Local **Pro Enhance** mode: illumination normalization in Lab colour space, moderate CLAHE and subtle sharpening, preserving colour information.
- Before/after preview with **Original crop** and **Pro Enhance**.
- Save single-page PDF or JPEG through the system document picker; share a JPEG by scoped FileProvider.
- No login, network permissions, advertising SDK or upload endpoint. Photos remain on the device.

## Limitations

- Auto cropping is classical computer vision and can fail on severe shadows, white-on-white backgrounds, folded corners and curved book pages. When uncertain it defaults to a full-frame quad for manual correction.
- This is a local restoration filter, **not CamScanner's proprietary Magic Pro/Omnifix**. Fine text, coloured stamps and severe highlights need device-level testing.
- Auto perspective ratio is estimated, not camera-calibrated; extreme one-axis skew or wide-angle lens distortion can require selecting the known paper size. Folded or curved pages still need dewarping.
- No live edge overlay, auto shutter, dewarping, multi-page PDF, OCR, history, camera zoom controls or release signing yet.
- Photos are decoded to a maximum of about 4200 px on the longest edge to limit memory; no promise of full sensor resolution.
- State is in-memory; work in progress does not survive process death. Export before closing.
- No generated APK has been verified on a device yet.

## Build

Open this folder in current Android Studio and install Android SDK 36. JDK 17 is required. The project uses Kotlin 2.3.20, AGP 8.13.2, Gradle 8.13, CameraX 1.6.2, OpenCV 4.12.0 and Compose 2026.02.00.

```bash
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

The wrapper scripts download the **official Gradle 8.13 wrapper JAR** on first use and verify its SHA-256; the Gradle distribution ZIP is also SHA-256 pinned in `gradle-wrapper.properties`.

The debug APK is created under `app/build/outputs/apk/debug/` after a successful build. Once the repository is pushed to GitHub, `.github/workflows/android.yml` runs unit tests and uploads a debug APK artifact on pushes to `main` and pull requests.

## Image pipeline

`DocumentDetector` downsamples only for candidate detection, tests four grayscale/contrast/edge strategies, ranks convex quadrilaterals by area, perpendicularity and image margins, and returns normalized corners. `CropEditor` always shows the corner handles for review. `RectificationGeometry` estimates physical proportions for oblique photos and offers user-selectable paper ratios. `DocumentProcessor.rectify` applies a projective warp to the decoded full-size bitmap with a bounded optional safety border. `DocumentProcessor.proEnhance` operates on the Lab luminance channel, keeping chroma separate from illumination correction. See [the perspective correction notes](docs/PERSPECTIVE_CORRECTION.md).

The detector score is a heuristic and is **not a calibrated probability**. Any candidate should be manually checked before export, particularly on documents containing text near an edge.

## Development tests

- Android JVM unit tests: `QuadGeometryTest` validates point ordering, convexity, fallback and invalid geometry; `RectificationGeometryTest` checks planar projected views, exact paper ratios, borders and output memory caps.
- `tools/geometry_smoke.kt` validates the core ratio arithmetic with a standalone Kotlin compiler and no Android SDK.
- `python tools/perspective_benchmark.py` generates five tilted paper photos and validates reference corner-to-corner warping against ground-truth paper images. These are synthetic reference warps and not device-level measurements.
- `python tools/reference_benchmark.py` generates four synthetic documents and evaluates a Python/OpenCV *reference port* of the detector. These tests do **not** run Android code or establish field reliability.
- See `docs/TEST_PLAN.md` for planned camera-device, OCR and visual-regression tests.

## Design boundaries

The app is an original implementation guided by public computer-vision methods. It does not use CamScanner code, assets, marks or back-end services. Dependencies retain their upstream licences.

## Licence

MIT for PageMint's original source (see `LICENSE`). Review the Apache-2.0 and AndroidX licences of third-party dependencies when distributing.
