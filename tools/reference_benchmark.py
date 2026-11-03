#!/usr/bin/env python3
"""Deterministic desktop reference for the MVP image pipeline; NOT an Android integration test.

Requires numpy and opencv-python; generates synthetic sample documents, evaluates
four edge strategies, and writes a human-readable report. No photos are uploaded.
"""
from __future__ import annotations
import argparse
import json
from pathlib import Path
import cv2
import numpy as np

W, H = 900, 700
QUAD = np.float32([[155, 88], [742, 111], [708, 608], [98, 566]])


def synthetic(name: str) -> tuple[np.ndarray, np.ndarray]:
    backgrounds = {
        "contrasting": (105, 87, 75),
        "perspective": (52, 82, 103),
        "shadow": (150, 151, 151),
        "white_on_white": (225, 225, 226),
    }
    img = np.full((H, W, 3), backgrounds[name], np.uint8)
    paper = np.full((680, 500, 3), 248 if name != "white_on_white" else 246, np.uint8)
    for i in range(25):
        y = 68 + i * 23
        if y > 655:
            break
        end = 340 if i % 4 == 0 else 450
        cv2.line(paper, (32, y), (end, y), (42, 48, 60), 2 if i % 3 else 3)
    cv2.circle(paper, (390, 490), 35, (25, 70, 170), 4)
    source = np.float32([[0, 0], [499, 0], [499, 679], [0, 679]])
    quad = QUAD.copy()
    if name == "perspective":
        quad = np.float32([[210, 170], [694, 38], [757, 650], [105, 555]])
    m = cv2.getPerspectiveTransform(source, quad)
    warped = cv2.warpPerspective(paper, m, (W, H))
    mask = cv2.warpPerspective(np.full((680, 500), 255, np.uint8), m, (W, H))
    img[mask > 0] = warped[mask > 0]
    if name == "shadow":
        x = np.linspace(0, 1, W)[None, :]
        shade = (1 - .38 * np.maximum(0, 1 - np.abs(x - .35) * 3))
        img = np.clip(img.astype(np.float32) * shade[:, :, None], 0, 255).astype(np.uint8)
    return img, quad


def order(points: np.ndarray) -> np.ndarray:
    center = np.mean(points, axis=0)
    angles = np.arctan2(points[:, 1] - center[1], points[:, 0] - center[0])
    out = points[np.argsort(angles)]
    return np.roll(out, -int(np.argmin(np.sum(out, axis=1))), axis=0)


def valid(quad: np.ndarray) -> bool:
    q = quad / np.array([W, H], np.float32)
    area = abs(float(cv2.contourArea(q)))
    return area > .025 and cv2.isContourConvex(quad.astype(np.float32))


def score(quad: np.ndarray, portion: float) -> float:
    q = quad / np.array([W, H], np.float32)
    angles = 0.0
    for i in range(4):
        a, b, c = q[(i + 3) % 4], q[i], q[(i + 1) % 4]
        u, v = a - b, c - b
        angles += abs(float(np.dot(u, v))) / max(1e-5, np.linalg.norm(u) * np.linalg.norm(v))
    perpendicular = max(0, min(1, 1 - angles / 4))
    coverage = min(1, portion / .64)
    margin = min(min(x, 1 - x, y, 1 - y) for x, y in q)
    inside = max(0, min(1, margin / .025))
    return .58 * coverage + .34 * perpendicular + .08 * inside


def detect(img: np.ndarray):
    gray = cv2.cvtColor(img, cv2.COLOR_BGR2GRAY)
    softened = cv2.GaussianBlur(gray, (5, 5), 0)
    clahe = cv2.createCLAHE(2.0, (8, 8)).apply(gray)
    equalized = cv2.GaussianBlur(clahe, (5, 5), 0)
    gradient = cv2.morphologyEx(gray, cv2.MORPH_GRADIENT, np.ones((5, 5), np.uint8))
    best = None
    for source, low, high in ((softened, 35, 105), (softened, 65, 180), (equalized, 35, 105), (gradient, 45, 130)):
        edge = cv2.Canny(source, low, high)
        closed = cv2.morphologyEx(edge, cv2.MORPH_CLOSE, np.ones((5, 5), np.uint8))
        contours, _ = cv2.findContours(closed, cv2.RETR_LIST, cv2.CHAIN_APPROX_SIMPLE)
        for contour in contours:
            portion = cv2.contourArea(contour) / (W * H)
            if not .09 <= portion <= .94:
                continue
            curve = cv2.arcLength(contour, True)
            for epsilon in (.015, .025, .04):
                candidate = cv2.approxPolyDP(contour, curve * epsilon, True)
                if len(candidate) != 4 or not cv2.isContourConvex(candidate):
                    continue
                quad = order(candidate.reshape(4, 2).astype(np.float32))
                if not valid(quad):
                    continue
                strength = score(quad, portion)
                if best is None or strength > best[1]:
                    best = (quad, strength)
    return best if best and best[1] >= .57 else None


def test(out: Path) -> dict:
    out.mkdir(parents=True, exist_ok=True)
    report = {}
    for name in ("contrasting", "perspective", "shadow", "white_on_white"):
        img, expected = synthetic(name)
        found = detect(img)
        cv2.imwrite(str(out / f"{name}.png"), img)
        predicted = None if found is None else found[0]
        error = None if predicted is None else float(np.linalg.norm(predicted - order(expected), axis=1).mean())
        report[name] = {"detected": found is not None, "mean_corner_error_px": None if error is None else round(error, 2),
                        "confidence": None if found is None else round(float(found[1]), 3)}
    (out / "benchmark.json").write_text(json.dumps(report, indent=2) + "\n")
    return report


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=Path("tools/samples"))
    args = parser.parse_args()
    print(json.dumps(test(args.output), indent=2))
