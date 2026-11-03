# MVP test plan

The first milestone is **measurable automatic cropping** and a conservative enhancement filter, not feature parity with any proprietary scanner.

## Required device checks

| Case | What to verify |
|---|---|
| High contrast flat page | All four detected corners within 2% of the original diagonal |
| White paper on pale desk | No confidently wrong auto crop; manual recovery must always work |
| Severe perspective | Rectification has no cut-off text and no unexpected rotation; compare Auto vs selected A4/Letter physical ratio |
| Lower-left corner hidden | Safe manual fallback and no fabricated cropped text |
| Strong cast shadow | Improved paper consistency without erasing faint text |
| Arabic small print | Dots, diacritics, digit shapes and punctuation preserved |
| Coloured seal and handwriting | Original hue and line thickness are respected |
| Overexposed white region | No invented content or claim of recovery |
| EXIF rotation / mirrored image | Corner overlay matches displayed image coordinates |
| 12MP image on Galaxy S24 Ultra | No UI-blocking processing or out-of-memory crash |
| Camera permission declined | Gallery import remains usable |
| PDF export and share | File opens, correct aspect ratio, no broad storage permission |

## Metrics

- **Corner error:** average pixel distance to hand-annotated true corners divided by image diagonal. Report median, 90th percentile and failure rate separately.
- **Completeness:** percentage of ground-truth text pixels retained after crop; high-quality scans must not silently lose borders.
- **Legibility:** OCR character error rate against manually transcribed samples, including Arabic and small digits. Accuracy should not regress after enhancement.
- **Colour fidelity:** measure error on coloured stamps/marks, not just average white background brightness.
- **Performance:** image load, detection, perspective warp and enhancement milliseconds on actual devices; memory peak. Output must stay at or below 12 MP and 4800 px per edge.
- **Perspective quality:** relative error against known paper aspect ratio, plus checks for clipped edge content and rotated / mirrored results. See [perspective validation](PERSPECTIVE_CORRECTION.md).

## Stages

1. Ship an instrumented detector test harness and a human-annotated corpus for diverse lighting, perspective, papers and camera configurations.
2. Improve uncertain crops using line reconstruction and a compact document-segmentation model, but never auto-crop when confidence is low.
3. Add live camera overlay and stability-gated auto-capture only after measuring offline still-image detection.
4. Compare model-based document restoration against the local luminance baseline while preserving the original and preventing invented detail.
5. Add multi-page export, OCR, dewarping and searchable local document history after the two core quality gates are met.

## Privacy and legality

Only test photos you own or have permission to process. Test datasets must not contain real personal information, medical records or financial documents. Avoid distributing brand assets or code from proprietary applications.
