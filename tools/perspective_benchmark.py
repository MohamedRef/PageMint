#!/usr/bin/env python3
"""Synthetic perspective-rectification check (Python/OpenCV reference, not Android runtime).

Tests only image warping with known document corners. The actual output geometry is
unit-tested in Kotlin separately. No CamScanner data, model or private assets are used.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import cv2
import numpy as np

IMAGE_WIDTH, IMAGE_HEIGHT = 1200, 900
DOC_WIDTH, DOC_HEIGHT = 560, 792
CAMERA_FOCAL = 1250.0


def sample_page():
    image = np.full((DOC_HEIGHT, DOC_WIDTH, 3), 246, dtype=np.uint8)
    cv2.rectangle(image, (8, 8), (DOC_WIDTH - 9, DOC_HEIGHT - 9), (75, 75, 75), 2)
    cv2.putText(image, "PERSPECTIVE TEST", (30, 75), cv2.FONT_HERSHEY_SIMPLEX, .95, (30, 30, 30), 2, cv2.LINE_AA)
    for i in range(22):
        y = 125 + 28 * i
        x2 = (DOC_WIDTH - 30) if i % 3 else 380
        cv2.line(image, (29, y), (x2, y), (40, 47, 53), 2, cv2.LINE_AA)
    for x, y, color in [(36, 36, (30, 30, 200)), (DOC_WIDTH - 37, 36, (30, 180, 30)),
                        (DOC_WIDTH - 37, DOC_HEIGHT - 36, (200, 80, 30)), (36, DOC_HEIGHT - 36, (40, 180, 180))]:
        cv2.circle(image, (x, y), 12, color, -1, cv2.LINE_AA)
    return image


def projected_corners(pitch, yaw, roll):
    a, b, c = np.radians([pitch, yaw, roll])
    cx, sx = np.cos(a), np.sin(a)
    cy, sy = np.cos(b), np.sin(b)
    cz, sz = np.cos(c), np.sin(c)
    rx = np.array([[1, 0, 0], [0, cx, -sx], [0, sx, cx]])
    ry = np.array([[cy, 0, sy], [0, 1, 0], [-sy, 0, cy]])
    rz = np.array([[cz, -sz, 0], [sz, cz, 0], [0, 0, 1]])
    ratio = DOC_WIDTH / DOC_HEIGHT
    original = np.array([[-ratio/2, -.5, 0], [ratio/2, -.5, 0],
                         [ratio/2, .5, 0], [-ratio/2, .5, 0]]) * 550.0
    pts = (rz @ ry @ rx @ original.T).T + [0, 0, 1000]
    output = np.array([[CAMERA_FOCAL * p[0] / p[2] + IMAGE_WIDTH / 2,
                        CAMERA_FOCAL * p[1] / p[2] + IMAGE_HEIGHT / 2] for p in pts], dtype=np.float32)
    assert np.all(output[:, 0] > 0) and np.all(output[:, 0] < IMAGE_WIDTH - 1)
    assert np.all(output[:, 1] > 0) and np.all(output[:, 1] < IMAGE_HEIGHT - 1)
    return output


def perform(output_dir: Path):
    output_dir.mkdir(parents=True, exist_ok=True)
    page = sample_page()
    original_rect = np.float32([[0, 0], [DOC_WIDTH - 1, 0], [DOC_WIDTH - 1, DOC_HEIGHT - 1], [0, DOC_HEIGHT - 1]])
    rotations = {
        "front": (0, 0, 0),
        "tilted": (15, 35, 5),
        "rolled": (35, 25, 30),
        "steep": (50, 50, 15),
        "one_direction": (0, 65, 15),
    }
    report = {"notes": "Known-corner reference warp only; NOT camera, corner detection or Android runtime.", "cases": {}}
    for name, angles in rotations.items():
        quad = projected_corners(*angles)
        into_photo = cv2.getPerspectiveTransform(original_rect, quad)
        background = np.full((IMAGE_HEIGHT, IMAGE_WIDTH, 3), (88, 94, 105), dtype=np.uint8)
        rendered = cv2.warpPerspective(page, into_photo, (IMAGE_WIDTH, IMAGE_HEIGHT), flags=cv2.INTER_CUBIC)
        mask = cv2.warpPerspective(np.full((DOC_HEIGHT, DOC_WIDTH), 255, dtype=np.uint8),
                                   into_photo, (IMAGE_WIDTH, IMAGE_HEIGHT))
        background[mask >= 128] = rendered[mask >= 128]
        straighten = cv2.getPerspectiveTransform(quad, original_rect)
        rectified = cv2.warpPerspective(background, straighten, (DOC_WIDTH, DOC_HEIGHT),
                                        flags=cv2.INTER_CUBIC, borderMode=cv2.BORDER_REPLICATE)
        y0, y1 = int(.06 * DOC_HEIGHT), int(.94 * DOC_HEIGHT)
        x0, x1 = int(.06 * DOC_WIDTH), int(.94 * DOC_WIDTH)
        dif = np.abs(page[y0:y1, x0:x1].astype(np.int16) - rectified[y0:y1, x0:x1].astype(np.int16))
        mae = float(dif.mean())
        report["cases"][name] = {"angles_degrees": list(angles), "inner_mean_abs_error_0_255": round(mae, 2),
                                 "passed": bool(mae < 10.0)}
        cv2.imwrite(str(output_dir / f"{name}-photo.png"), background)
        cv2.imwrite(str(output_dir / f"{name}-straightened.png"), rectified)
    (output_dir / "report.json").write_text(json.dumps(report, indent=2) + "\n")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=Path("tools/perspective_samples"))
    args = parser.parse_args()
    result = perform(args.output)
    print(json.dumps(result, indent=2))
    if not all(v["passed"] for v in result["cases"].values()):
        raise SystemExit(1)
