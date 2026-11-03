# Perspective correction — first accuracy pass

Scope: **only the perspective-rectification stage**. The detector, image enhancement,
PDF encoder and camera capture have not been changed in this iteration.

## The problem addressed

The initial implementation selected the longest horizontal and vertical edges of a detected
quadrilateral as the new image width and height. With a strongly tilted photograph, a nearer
edge can be much longer than the opposite edge. That approach can stretch an A4 sheet into an
unrealistic proportion even when all four corners are correctly placed.

## New behaviour

1. `RectificationGeometry.plan` computes output dimensions independently of Android or OpenCV.
2. `Auto` estimates the width-to-height ratio using a unit-square homography and a pinhole-camera
   assumption. If the vanishing-point-based focal length is plausible, it is used; otherwise the
   camera focal length is approximated from image size. Implausible ratios fall back to opposite
   edge lengths, which may be less accurate on severe one-axis perspective.
3. The crop screen offers explicit ratios for **A4 portrait/landscape**, **US Letter
   portrait/landscape** and **landscape ID**. These are particularly useful for strongly tilted
   captures, photos with lens distortions or an uncertain Auto ratio. Selecting one presumes the
   paper really has that ratio: using it on a different page will distort the image.
4. A **small safety border** is on by default: the four corner positions are moved about 1.2%
   away from the quadrilateral centroid (clamped to the source image). This may preserve text
   close to the edge at the cost of showing a narrow strip of surrounding background. It cannot
   restore text outside the camera photograph, and it is not a substitute for accurate corners.
5. Output sizing respects both **4800 pixels on the longest edge** and **12 megapixels total**.
   This reduces the memory cost of the subsequent enhancement step while keeping the best-sampled
   direction at a useful resolution.
6. The final perspective warp still runs on the **decoded full-size image**, not on the
   downsampled detector preview. The app preserves the original bitmap in memory for re-editing.

## What the math does, and does not do

For corners (TL, TR, BR, BL), a homography maps an ideal unit square into the photographed
quadrilateral. Back-projecting its axis columns through an assumed camera matrix allows an
estimate of physical width / height. With known focal length and ideal pinhole geometry, this
ratio is exact. Real phones introduce lens distortion, rolling shutter, inaccurate corners and
imperfect focal-length assumptions; therefore `Auto` must be treated as an **estimate**.

This is **planar projective rectification**, not curved-book dewarping, optical text enhancement,
corner detection or camera calibration.

## Validation executed without Android SDK

- `tools/geometry_smoke.kt` compiled and passed using Kotlin/JVM 1.9.0.
- Existing and new geometry test methods: 10 cases executed in a temporary JVM test harness,
  covering corner ordering, invalid quads, two strongly tilted planar views, paper presets,
  safety borders and the output memory ceiling. A Gradle/JUnit Android project run is pending.
- `python tools/perspective_benchmark.py` generated five synthetic camera views and applied a
  reference OpenCV projective warp using **ground-truth** corners. Across a center crop of each
  corrected image, mean absolute per-channel error after two interpolations (0–255 range) was:

| Known-corner case | Mean absolute error |
|---|---:|
| Frontal | 2.71 |
| Tilted (15°, 35°, 5°) | 2.91 |
| Rolled (35°, 25°, 30°) | 3.11 |
| Steep (50°, 50°, 15°) | 4.79 |
| Single-direction vanishing (0°, 65°, 15°) | 2.84 |

**These numbers measure a standalone Python/OpenCV reference warp, not an Android device or the
app's corner detector.** Known-corner synthetic tests alone do not establish real-camera quality.

## Repeat local checks

```bash
# No Android SDK needed for the geometry smoke test:
kotlinc app/src/main/java/com/mohamedref/pagemint/cv/QuadGeometry.kt \
  app/src/main/java/com/mohamedref/pagemint/cv/RectificationGeometry.kt \
  tools/geometry_smoke.kt -include-runtime -d /tmp/pagemint-geometry.jar
java -jar /tmp/pagemint-geometry.jar

# Requires Python, numpy and opencv-python:
python tools/perspective_benchmark.py

# Android SDK is required for the actual app / JUnit checks:
./gradlew :app:testDebugUnitTest :app:assembleDebug
```

## Next verification gate

On a physical Android device, scan at least 20 documents spanning frontal, oblique, rolled and
partially shadowed views. Compare measured page shape against known A4 / Letter dimensions,
check for clipped handwritten characters near corners and review exported PDFs for orientation.
Keep the false crop, orientation and extreme-perspective cases in an annotated regression set.
