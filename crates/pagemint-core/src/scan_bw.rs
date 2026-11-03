//! A flatbed-like black-and-white scan preset for documents with text, handwriting
//! and charts. Conceptual inspiration: Scanlite's MIT-licensed background
//! normalization pipeline (https://github.com/gcol33/scanlite).
//!
//! A morphological closing estimates the *light paper surface* rather than
//! averaging dark handwriting into the background. We then convert each source
//! pixel's relative darkness into soft grayscale ink density. Small marks remain
//! gray instead of disappearing under a hard global/adaptive threshold.

use image::{imageops::FilterType, Rgb, RgbImage};

const MAX_BACKGROUND_EDGE: u32 = 620;

fn gray(rgb: Rgb<u8>) -> u8 {
    let [r, g, b] = rgb.0;
    (0.299 * r as f32 + 0.587 * g as f32 + 0.114 * b as f32)
        .round()
        .clamp(0.0, 255.0) as u8
}

fn horizontal(src: &[u8], w: usize, h: usize, radius: usize, maximum: bool) -> Vec<u8> {
    let mut out = vec![0u8; src.len()];
    for y in 0..h {
        for x in 0..w {
            let mut value = if maximum { 0u8 } else { 255u8 };
            for xx in x.saturating_sub(radius)..=(x + radius).min(w - 1) {
                let item = src[y * w + xx];
                value = if maximum { value.max(item) } else { value.min(item) };
            }
            out[y * w + x] = value;
        }
    }
    out
}

fn vertical(src: &[u8], w: usize, h: usize, radius: usize, maximum: bool) -> Vec<u8> {
    let mut out = vec![0u8; src.len()];
    for y in 0..h {
        for x in 0..w {
            let mut value = if maximum { 0u8 } else { 255u8 };
            for yy in y.saturating_sub(radius)..=(y + radius).min(h - 1) {
                let item = src[yy * w + x];
                value = if maximum { value.max(item) } else { value.min(item) };
            }
            out[y * w + x] = value;
        }
    }
    out
}

fn estimate_background(input: &RgbImage) -> (Vec<u8>, u32, u32) {
    let long_side = input.width().max(input.height());
    let small = if long_side > MAX_BACKGROUND_EDGE {
        let scale = MAX_BACKGROUND_EDGE as f64 / long_side as f64;
        image::imageops::resize(
            input,
            ((input.width() as f64 * scale).round() as u32).max(1),
            ((input.height() as f64 * scale).round() as u32).max(1),
            FilterType::Triangle,
        )
    } else {
        input.clone()
    };
    let w = small.width() as usize;
    let h = small.height() as usize;
    let radius = ((w.max(h) as f64) / 36.0).round() as usize;
    let radius = radius.clamp(4, 25);
    let source: Vec<u8> = small.pixels().copied().map(gray).collect();

    // Separable rectangular morphological closing: local maximum followed
    // by local minimum. This fills thin dark strokes while keeping paper
    // shadows and light gradients.
    let dilated_x = horizontal(&source, w, h, radius, true);
    let dilated_y = vertical(&dilated_x, w, h, radius, true);
    let eroded_x = horizontal(&dilated_y, w, h, radius, false);
    let closed = vertical(&eroded_x, w, h, radius, false);
    (closed, small.width(), small.height())
}

fn interpolate_background(
    background: &[u8],
    bw: u32,
    bh: u32,
    x: u32,
    y: u32,
    input_w: u32,
    input_h: u32,
) -> f32 {
    let gx = x as f32 * (bw - 1) as f32 / (input_w - 1) as f32;
    let gy = y as f32 * (bh - 1) as f32 / (input_h - 1) as f32;
    let left = gx.floor() as u32;
    let top = gy.floor() as u32;
    let right = (left + 1).min(bw - 1);
    let bottom = (top + 1).min(bh - 1);
    let fx = gx - left as f32;
    let fy = gy - top as f32;
    let get = |xx: u32, yy: u32| background[yy as usize * bw as usize + xx as usize] as f32;
    let upper = get(left, top) * (1.0 - fx) + get(right, top) * fx;
    let lower = get(left, bottom) * (1.0 - fx) + get(right, bottom) * fx;
    upper * (1.0 - fy) + lower * fy
}

/// Generate high-contrast grayscale text and white paper with softly graded
/// strokes. Keeps output dimensions, does not alter the input, needs no model
/// download and is deterministic. Use only on a cropped document.
pub fn scan_bw(input: &RgbImage) -> RgbImage {
    let (width, height) = input.dimensions();
    if width < 2 || height < 2 {
        return input.clone();
    }
    let (background, bw, bh) = estimate_background(input);
    RgbImage::from_fn(width, height, |x, y| {
        let input_gray = gray(*input.get_pixel(x, y)) as f32;
        let paper = interpolate_background(&background, bw, bh, x, y, width, height).max(25.0);
        let relative_ink = (1.0 - input_gray / paper).max(0.0);
        // Leave differences below 1% entirely white, then progressively
        // darken strokes without an all-or-nothing cutoff at the paper edge.
        let t = ((relative_ink - 0.01) / 0.23).clamp(0.0, 1.0);
        let smooth = t * t * (3.0 - 2.0 * t);
        let value = (255.0 * (1.0 - smooth)).round() as u8;
        Rgb([value, value, value])
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn shadowed_paper_becomes_white_without_erasing_small_ink() {
        let original = RgbImage::from_fn(400, 300, |x, y| {
            let surface = (165.0 + 70.0 * x as f32 / 399.0) as u8;
            let stroke = y == 133 || y == 135 || (x == 191 && (88..=204).contains(&y));
            if stroke { Rgb([36,36,36]) } else { Rgb([surface,surface,surface]) }
        });
        let output = scan_bw(&original);
        assert!(output.get_pixel(80, 200)[0] >= 240);
        assert!(output.get_pixel(350, 200)[0] >= 240);
        assert!(output.get_pixel(170, 133)[0] < 65);
        assert!(output.get_pixel(191, 200)[0] < 65);
        assert_eq!(*original.get_pixel(170,133), Rgb([36,36,36]));
    }
    #[test]
    fn blue_tinted_page_becomes_neutral_white() {
        let input = RgbImage::from_pixel(320, 240, Rgb([180, 198, 221]));
        let output = scan_bw(&input);
        assert_eq!(*output.get_pixel(100,100), Rgb([255,255,255]));
    }
    #[test]
    fn near_blank_paper_does_not_gain_false_ink() {
        let input = RgbImage::from_fn(300,200,|x,_| {
            let v = (210 + x / 15).min(240) as u8;
            Rgb([v,v,v])
        });
        let output = scan_bw(&input);
        assert!(output.get_pixel(120,80)[0] >= 225);
    }
    #[test]
    fn tiny_input_is_safe() {
        let input = RgbImage::from_pixel(1,1,Rgb([42,42,42]));
        assert_eq!(scan_bw(&input), input);
    }
}
