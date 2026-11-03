//! Still-image document detection, implemented entirely in Rust.
//! The input is a *preview-sized*, EXIF-oriented RGB image. Output corners are
//! normalized and map directly back to the full-resolution original.
//!
//! Conservative by design: a weak or ambiguous candidate returns a full-frame
//! manual crop, not a confidently incorrect quadrilateral.

use crate::perspective::{valid, Corner};
use image::{imageops::FilterType, RgbImage};
use std::cmp::Ordering;

const MAX_DETECTION_EDGE: u32 = 1024;
const MIN_CANDIDATE_SCORE: f64 = 0.69;
const MAX_BOUNDARY_POINTS: usize = 50_000;

#[derive(Clone, Copy, Debug)]
pub struct Detection {
    pub corners: [Corner; 4],
    /// A ranking heuristic, not a calibrated probability.
    pub confidence: f32,
    pub automatic: bool,
}

impl Detection {
    pub fn manual() -> Self {
        Self {
            corners: [
                Corner { x: 0.0, y: 0.0 },
                Corner { x: 1.0, y: 0.0 },
                Corner { x: 1.0, y: 1.0 },
                Corner { x: 0.0, y: 1.0 },
            ],
            confidence: 0.0,
            automatic: false,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
struct Pixel {
    x: i32,
    y: i32,
}

impl Pixel {
    fn cross(a: Self, b: Self, c: Self) -> i64 {
        (b.x - a.x) as i64 * (c.y - a.y) as i64
            - (b.y - a.y) as i64 * (c.x - a.x) as i64
    }

    fn distance(a: Self, b: Self) -> f64 {
        ((a.x - b.x) as f64).hypot((a.y - b.y) as f64)
    }
}

#[derive(Clone, Copy)]
struct Candidate {
    corners: [Corner; 4],
    score: f64,
}

/// Analyze one photo without changing it. The image is resized only for boundary
/// detection; the calling application rectifies the original decoded image.
pub fn detect(image: &RgbImage) -> Detection {
    if image.width() < 64 || image.height() < 64 {
        return Detection::manual();
    }
    let scaled = if image.width().max(image.height()) > MAX_DETECTION_EDGE {
        let factor = MAX_DETECTION_EDGE as f64 / image.width().max(image.height()) as f64;
        let width = ((image.width() as f64 * factor).round() as u32).max(32);
        let height = ((image.height() as f64 * factor).round() as u32).max(32);
        image::imageops::resize(image, width, height, FilterType::Triangle)
    } else {
        image.clone()
    };
    let w = scaled.width() as usize;
    let h = scaled.height() as usize;
    let gray: Vec<u8> = scaled
        .pixels()
        .map(|pixel| {
            let [r, g, b] = pixel.0;
            (0.299 * r as f64 + 0.587 * g as f64 + 0.114 * b as f64)
                .round()
                .clamp(0.0, 255.0) as u8
        })
        .collect();
    let flattened = flatten_lighting(&gray, w, h);
    let mut best: Option<Candidate> = None;
    for source in [&gray, &flattened] {
        let t = otsu(source);
        let mut thresholds = vec![
            t.saturating_sub(12),
            t,
            t.saturating_add(12),
            180,
            207,
            223,
            236,
            245,
        ];
        thresholds.sort_unstable();
        thresholds.dedup();
        for threshold in thresholds {
            if !(16..=252).contains(&threshold) {
                continue;
            }
            for inverted in [false, true] {
                // Dark masks are useful for dark sheets on a pale desk,
                // but high dark thresholds mostly return the entire background.
                if inverted && threshold != t && threshold != t.saturating_sub(12) {
                    continue;
                }
                let mask: Vec<u8> = source
                    .iter()
                    .map(|&value| u8::from(if inverted {
                        value <= threshold
                    } else {
                        value >= threshold
                    }))
                    .collect();
                components(&mask, w, h, false, source, &mut best);
            }
        }
    }
    // Line/gradient fallback when the foreground silhouette is broken.
    if best.map_or(true, |candidate| candidate.score < 0.73) {
        let gradients = sobel(&flattened, w, h);
        for threshold in [48u16, 76u16] {
            let mask: Vec<u8> = gradients
                .iter()
                .map(|&value| u8::from(value >= threshold))
                .collect();
            let closed = close_small_gaps(&mask, w, h);
            components(&closed, w, h, true, &flattened, &mut best);
        }
    }
    match best {
        Some(candidate) if candidate.score >= MIN_CANDIDATE_SCORE => Detection {
            corners: candidate.corners,
            confidence: candidate.score.min(1.0) as f32,
            automatic: true,
        },
        _ => Detection::manual(),
    }
}

/// Illumination normalization: subtract a large-area box-mean to remove
/// gradual shadows without erasing local high-contrast page boundaries.
fn flatten_lighting(gray: &[u8], w: usize, h: usize) -> Vec<u8> {
    let stride = w + 1;
    let mut integral = vec![0u64; stride * (h + 1)];
    let mut total = 0u64;
    for y in 0..h {
        let mut row_sum = 0u64;
        for x in 0..w {
            row_sum += gray[y * w + x] as u64;
            total += gray[y * w + x] as u64;
            integral[(y + 1) * stride + x + 1] = integral[y * stride + x + 1] + row_sum;
        }
    }
    let mean = total as f64 / (w * h) as f64;
    let radius = (w.max(h) * 17 / 100).max(8);
    let mut output = vec![0u8; w * h];
    for y in 0..h {
        let top = y.saturating_sub(radius);
        let bottom = (y + radius + 1).min(h);
        for x in 0..w {
            let left = x.saturating_sub(radius);
            let right = (x + radius + 1).min(w);
            let sum = integral[bottom * stride + right]
                + integral[top * stride + left]
                - integral[bottom * stride + left]
                - integral[top * stride + right];
            let local = sum as f64 / ((right - left) * (bottom - top)) as f64;
            output[y * w + x] = (gray[y * w + x] as f64 - local + mean)
                .round()
                .clamp(0.0, 255.0) as u8;
        }
    }
    output
}

fn otsu(gray: &[u8]) -> u8 {
    let mut hist = [0u64; 256];
    for &l in gray {
        hist[l as usize] += 1;
    }
    let n = gray.len() as f64;
    let weighted: f64 = hist.iter().enumerate().map(|(i, &count)| i as f64 * count as f64).sum();
    let mut low_weight = 0.0;
    let mut low_sum = 0.0;
    let mut max_variance = -1.0;
    let mut best = 128u8;
    for i in 0..255 {
        low_weight += hist[i] as f64;
        low_sum += i as f64 * hist[i] as f64;
        let high_weight = n - low_weight;
        if low_weight == 0.0 || high_weight == 0.0 {
            continue;
        }
        let low_mean = low_sum / low_weight;
        let high_mean = (weighted - low_sum) / high_weight;
        let variance = low_weight * high_weight * (low_mean - high_mean).powi(2);
        if variance > max_variance {
            max_variance = variance;
            best = i as u8;
        }
    }
    best
}

fn convex_hull(mut points: Vec<Pixel>) -> Vec<Pixel> {
    points.sort_unstable_by_key(|p| (p.x, p.y));
    points.dedup();
    if points.len() <= 3 {
        return points;
    }
    let mut lower = Vec::new();
    for &p in &points {
        while lower.len() >= 2
            && Pixel::cross(lower[lower.len() - 2], lower[lower.len() - 1], p) <= 0
        {
            lower.pop();
        }
        lower.push(p);
    }
    let mut upper = Vec::new();
    for &p in points.iter().rev() {
        while upper.len() >= 2
            && Pixel::cross(upper[upper.len() - 2], upper[upper.len() - 1], p) <= 0
        {
            upper.pop();
        }
        upper.push(p);
    }
    lower.pop();
    upper.pop();
    lower.extend(upper);
    lower
}

fn polygon_area(points: &[Pixel]) -> f64 {
    if points.len() < 3 {
        return 0.0;
    }
    (0..points.len())
        .map(|i| {
            let a = points[i];
            let b = points[(i + 1) % points.len()];
            (a.x as i64 * b.y as i64 - b.x as i64 * a.y as i64) as f64
        })
        .sum::<f64>()
        .abs()
        * 0.5
}

fn segment_distance(point: Pixel, a: Pixel, b: Pixel) -> f64 {
    let abx = (b.x - a.x) as f64;
    let aby = (b.y - a.y) as f64;
    let length_squared = abx * abx + aby * aby;
    if length_squared < 1e-12 {
        return Pixel::distance(point, a);
    }
    let t = (((point.x - a.x) as f64 * abx + (point.y - a.y) as f64 * aby)
        / length_squared)
        .clamp(0.0, 1.0);
    (((point.x - a.x) as f64 - t * abx).powi(2)
        + ((point.y - a.y) as f64 - t * aby).powi(2))
    .sqrt()
}

fn simplify_open(points: &[Pixel], epsilon: f64, output: &mut Vec<Pixel>) {
    if points.len() <= 2 {
        output.push(points[0]);
        return;
    }
    let first = points[0];
    let last = *points.last().unwrap();
    let (index, max_distance) = (1..points.len() - 1)
        .map(|i| (i, segment_distance(points[i], first, last)))
        .max_by(|a, b| a.1.partial_cmp(&b.1).unwrap_or(Ordering::Equal))
        .unwrap();
    if max_distance > epsilon {
        simplify_open(&points[..=index], epsilon, output);
        simplify_open(&points[index..], epsilon, output);
    } else {
        output.push(first);
    }
}

fn approximate_closed_hull(hull: &[Pixel], epsilon: f64) -> Option<[Pixel; 4]> {
    if hull.len() < 4 {
        return None;
    }
    // Anchor the two open chains at the most distant hull vertices. This
    // avoids an arbitrary first/last seam destroying a real page corner.
    let mut endpoints = (0, 1);
    let mut farthest = 0.0;
    for i in 0..hull.len() {
        for j in (i + 1)..hull.len() {
            let distance = Pixel::distance(hull[i], hull[j]);
            if distance > farthest {
                farthest = distance;
                endpoints = (i, j);
            }
        }
    }
    let (a, b) = endpoints;
    let forward = &hull[a..=b];
    let mut wrap = hull[b..].to_vec();
    wrap.extend_from_slice(&hull[..=a]);
    let mut result = Vec::new();
    simplify_open(forward, epsilon, &mut result);
    simplify_open(&wrap, epsilon, &mut result);
    if result.len() != 4 {
        return None;
    }
    let mut corners = [result[0], result[1], result[2], result[3]];
    let start = (0..4).min_by_key(|&i| corners[i].x + corners[i].y).unwrap();
    corners.rotate_left(start);
    Some(corners)
}

fn score(corners: &[Corner; 4], coverage: f64, fill_ratio: f64, edges: bool, support: f64) -> f64 {
    let mut angles = 0.0;
    for i in 0..4 {
        let a = corners[(i + 3) % 4];
        let b = corners[i];
        let c = corners[(i + 1) % 4];
        let ux = a.x - b.x;
        let uy = a.y - b.y;
        let vx = c.x - b.x;
        let vy = c.y - b.y;
        let denom = (ux.hypot(uy) * vx.hypot(vy)).max(1e-5);
        angles += ((ux * vx + uy * vy) / denom).abs();
    }
    let perpendicular = (1.0 - angles / 4.0).clamp(0.0, 1.0);
    let inside = corners
        .iter()
        .map(|p| p.x.min(1.0 - p.x).min(p.y.min(1.0 - p.y)))
        .fold(1.0f64, f64::min);
    let baseline = 0.58 * (coverage / 0.64).min(1.0)
        + 0.34 * perpendicular
        + 0.08 * (inside / 0.025).clamp(0.0, 1.0);
    if edges {
        (0.70 * baseline + 0.30 * support).min(0.78)
    } else {
        0.65 * baseline + 0.35 * fill_ratio.min(1.0)
    }
}

fn components(
    mask: &[u8],
    w: usize,
    h: usize,
    edges: bool,
    gray: &[u8],
    best: &mut Option<Candidate>,
) {
    let mut visited = vec![false; w * h];
    let mut queue = Vec::<usize>::new();
    let min_component = w * h / 35;
    for seed in 0..w * h {
        if mask[seed] == 0 || visited[seed] {
            continue;
        }
        visited[seed] = true;
        queue.clear();
        queue.push(seed);
        let (mut min_x, mut max_x) = (w, 0);
        let (mut min_y, mut max_y) = (h, 0);
        let mut boundary = Vec::<Pixel>::new();
        let mut cursor = 0;
        while cursor < queue.len() {
            let current = queue[cursor];
            cursor += 1;
            let x = current % w;
            let y = current / w;
            min_x = min_x.min(x);
            max_x = max_x.max(x);
            min_y = min_y.min(y);
            max_y = max_y.max(y);
            let mut at_boundary = x == 0 || y == 0 || x + 1 == w || y + 1 == h;
            if x > 0 && mask[current - 1] == 0 {
                at_boundary = true;
            }
            if x + 1 < w && mask[current + 1] == 0 {
                at_boundary = true;
            }
            if y > 0 && mask[current - w] == 0 {
                at_boundary = true;
            }
            if y + 1 < h && mask[current + w] == 0 {
                at_boundary = true;
            }
            if at_boundary && boundary.len() < MAX_BOUNDARY_POINTS && (x + y) % 2 == 0 {
                boundary.push(Pixel {
                    x: x as i32,
                    y: y as i32,
                });
            }
            for dy in -1isize..=1 {
                for dx in -1isize..=1 {
                    if dx == 0 && dy == 0 { continue; }
                    let nx = x as isize + dx;
                    let ny = y as isize + dy;
                    if nx < 0 || ny < 0 || nx >= w as isize || ny >= h as isize {
                        continue;
                    }
                    let next = ny as usize * w + nx as usize;
                    if mask[next] != 0 && !visited[next] {
                        visited[next] = true;
                        queue.push(next);
                    }
                }
            }
        }
        if queue.len() < min_component || (!edges && queue.len() > w * h * 94 / 100)
            || max_x - min_x < w * 28 / 100 || max_y - min_y < h * 28 / 100
        {
            continue;
        }
        let touches = usize::from(min_x < 3)
            + usize::from(min_y < 3)
            + usize::from(max_x + 3 >= w)
            + usize::from(max_y + 3 >= h);
        if touches >= 3 {
            continue;
        }
        let hull = convex_hull(boundary);
        if hull.len() < 4 {
            continue;
        }
        let hull_area = polygon_area(&hull);
        let portion = hull_area / (w * h) as f64;
        if !(0.09..=0.94).contains(&portion) {
            continue;
        }
        let fill_ratio = queue.len() as f64 / hull_area.max(1.0);
        if !edges && fill_ratio < 0.47 {
            continue;
        }
        let perimeter: f64 = (0..hull.len())
            .map(|i| Pixel::distance(hull[i], hull[(i + 1) % hull.len()]))
            .sum();
        for tolerance in [0.008, 0.014, 0.022, 0.034, 0.050] {
            let Some(quad_pixels) = approximate_closed_hull(&hull, perimeter * tolerance) else {
                continue;
            };
            let quad = quad_pixels.map(|p| Corner {
                x: (p.x as f64 / (w - 1) as f64).clamp(0.0, 1.0),
                y: (p.y as f64 / (h - 1) as f64).clamp(0.0, 1.0),
            });
            if !valid(&quad) {
                continue;
            }
            let support = if edges {
                edge_support(&quad_pixels, gray, w, h)
            } else {
                1.0
            };
            if edges && support < 0.57 {
                continue;
            }
            let candidate = Candidate {
                corners: quad,
                score: score(&quad, portion, fill_ratio, edges, support),
            };
            if best.as_ref().is_none_or(|previous| candidate.score > previous.score) {
                *best = Some(candidate);
            }
            break;
        }
    }
}

fn sobel(gray: &[u8], w: usize, h: usize) -> Vec<u16> {
    let mut gradient = vec![0u16; w * h];
    for y in 1..h - 1 {
        for x in 1..w - 1 {
            let a = gray[(y - 1) * w + x - 1] as i32;
            let b = gray[(y - 1) * w + x] as i32;
            let c = gray[(y - 1) * w + x + 1] as i32;
            let d = gray[y * w + x - 1] as i32;
            let f = gray[y * w + x + 1] as i32;
            let g = gray[(y + 1) * w + x - 1] as i32;
            let h0 = gray[(y + 1) * w + x] as i32;
            let i = gray[(y + 1) * w + x + 1] as i32;
            let gx = (c + f * 2 + i - a - d * 2 - g).abs();
            let gy = (g + h0 * 2 + i - a - b * 2 - c).abs();
            gradient[y * w + x] = ((gx + gy) / 4).min(u16::MAX as i32) as u16;
        }
    }
    gradient
}

fn dilate(mask: &[u8], w: usize, h: usize, radius: usize) -> Vec<u8> {
    let mut result = vec![0u8; mask.len()];
    for y in 0..h {
        for x in 0..w {
            'search: for ny in y.saturating_sub(radius)..=(y + radius).min(h - 1) {
                for nx in x.saturating_sub(radius)..=(x + radius).min(w - 1) {
                    if mask[ny * w + nx] != 0 {
                        result[y * w + x] = 1;
                        break 'search;
                    }
                }
            }
        }
    }
    result
}

fn erode(mask: &[u8], w: usize, h: usize, radius: usize) -> Vec<u8> {
    let mut result = vec![1u8; mask.len()];
    for y in 0..h {
        for x in 0..w {
            'search: for ny in y.saturating_sub(radius)..=(y + radius).min(h - 1) {
                for nx in x.saturating_sub(radius)..=(x + radius).min(w - 1) {
                    if mask[ny * w + nx] == 0 {
                        result[y * w + x] = 0;
                        break 'search;
                    }
                }
            }
        }
    }
    result
}

fn close_small_gaps(mask: &[u8], w: usize, h: usize) -> Vec<u8> {
    erode(&dilate(mask, w, h, 2), w, h, 2)
}

fn edge_support(corners: &[Pixel; 4], gray: &[u8], w: usize, h: usize) -> f64 {
    let gradients = sobel(gray, w, h);
    let mut present = 0;
    let mut samples = 0;
    for i in 0..4 {
        let a = corners[i];
        let b = corners[(i + 1) % 4];
        for step in 2..=48 {
            let t = step as f64 / 50.0;
            let x = (a.x as f64 * (1.0 - t) + b.x as f64 * t).round() as isize;
            let y = (a.y as f64 * (1.0 - t) + b.y as f64 * t).round() as isize;
            let mut supported = false;
            for dy in -2..=2 {
                for dx in -2..=2 {
                    let px = x + dx;
                    let py = y + dy;
                    if px > 0 && py > 0 && px < w as isize - 1 && py < h as isize - 1
                        && gradients[py as usize * w + px as usize] >= 32
                    {
                        supported = true;
                        break;
                    }
                }
                if supported { break; }
            }
            samples += 1;
            if supported { present += 1; }
        }
    }
    present as f64 / samples as f64
}

#[cfg(test)]
mod tests {
    use super::*;
    use image::Rgb;

    fn within_quad(x: f64, y: f64, quad: &[Pixel; 4]) -> bool {
        (0..4).all(|i| {
            let a = quad[i];
            let b = quad[(i + 1) % 4];
            (b.x - a.x) as f64 * (y - a.y as f64)
                - (b.y - a.y) as f64 * (x - a.x as f64) >= 0.0
        })
    }

    fn synthetic(
        quad: [Pixel; 4], background: u8, paper: u8, shadow: bool,
    ) -> RgbImage {
        RgbImage::from_fn(900, 700, |x, y| {
            let on_page = within_quad(x as f64, y as f64, &quad);
            let mut value = if on_page { paper } else { background } as f64;
            // Short text lines remain separate from the page silhouette.
            if on_page && y % 32 < 3 && x > 265 && x < 650 && y > 180 && y < 510 {
                value = 45.0;
            }
            if shadow {
                let horizontal = x as f64 / 900.0;
                let dim = 1.0 - 0.38 * (1.0 - (horizontal - 0.35).abs() * 3.0).max(0.0);
                value *= dim;
            }
            let value = value.round().clamp(0.0, 255.0) as u8;
            Rgb([value, value, value])
        })
    }

    fn assert_close(found: Detection, target: &[Pixel; 4], max_px: f64) {
        assert!(found.automatic, "The document was not found: {:?}", found);
        let mean = found.corners.iter().zip(target.iter()).map(|(actual, expected)| {
            let x = actual.x * 899.0 - expected.x as f64;
            let y = actual.y * 699.0 - expected.y as f64;
            x.hypot(y)
        }).sum::<f64>() / 4.0;
        assert!(mean <= max_px, "Mean corner error {:.1}px; detection: {:?}", mean, found);
    }

    #[test]
    fn detects_high_contrast_document() {
        let quad = [
            Pixel{x:155,y:88}, Pixel{x:742,y:111}, Pixel{x:708,y:608}, Pixel{x:98,y:566},
        ];
        assert_close(detect(&synthetic(quad, 95, 246, false)), &quad, 12.0);
    }

    #[test]
    fn detects_oblique_document() {
        let quad = [
            Pixel{x:210,y:170}, Pixel{x:694,y:38}, Pixel{x:757,y:650}, Pixel{x:105,y:555},
        ];
        assert_close(detect(&synthetic(quad, 82, 246, false)), &quad, 12.0);
    }

    #[test]
    fn detects_shadowed_document() {
        let quad = [
            Pixel{x:155,y:88}, Pixel{x:742,y:111}, Pixel{x:708,y:608}, Pixel{x:98,y:566},
        ];
        assert_close(detect(&synthetic(quad, 150, 246, true)), &quad, 16.0);
    }

    #[test]
    fn detects_white_on_white() {
        let quad = [
            Pixel{x:155,y:88}, Pixel{x:742,y:111}, Pixel{x:708,y:608}, Pixel{x:98,y:566},
        ];
        assert_close(detect(&synthetic(quad, 226, 246, false)), &quad, 12.0);
    }

    #[test]
    fn uniform_image_falls_back_to_manual() {
        let blank = RgbImage::from_pixel(900,700,Rgb([235,235,235]));
        assert!(!detect(&blank).automatic);
    }

    #[test]
    fn low_resolution_image_falls_back_to_manual() {
        let tiny = RgbImage::from_pixel(24,24,Rgb([255,255,255]));
        assert!(!detect(&tiny).automatic);
    }

    #[test]
    fn convex_hull_preserves_polygon_corners() {
        let points = vec![
            Pixel{x:0,y:0}, Pixel{x:4,y:0}, Pixel{x:4,y:4},
            Pixel{x:0,y:4}, Pixel{x:2,y:2}, Pixel{x:2,y:0},
        ];
        let hull = convex_hull(points);
        assert_eq!(hull.len(),4);
        assert_eq!(polygon_area(&hull),16.0);
    }
}
