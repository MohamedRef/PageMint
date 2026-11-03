use image::{Rgb, RgbImage};
use nalgebra::{SMatrix, SVector};
use std::error::Error;
use std::fmt;

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct Corner { pub x: f64, pub y: f64 }

#[derive(Clone, Copy, Debug, PartialEq)]
pub enum PaperFormat { Auto, A4Portrait, A4Landscape, LetterPortrait, LetterLandscape, IdLandscape }

impl PaperFormat {
    pub fn ratio(self) -> Option<f64> {
        match self {
            Self::Auto => None,
            Self::A4Portrait => Some(210.0 / 297.0),
            Self::A4Landscape => Some(297.0 / 210.0),
            Self::LetterPortrait => Some(8.5 / 11.0),
            Self::LetterLandscape => Some(11.0 / 8.5),
            Self::IdLandscape => Some(85.60 / 53.98),
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq)]
pub struct OutputPlan { pub width: u32, pub height: u32, pub ratio: f64 }

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum PerspectiveError { InvalidImage, InvalidCorners, SingularTransform }

impl fmt::Display for PerspectiveError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        write!(f, "{}", match self {
            Self::InvalidImage => "invalid image size",
            Self::InvalidCorners => "invalid four-corner document boundary",
            Self::SingularTransform => "perspective transform is singular",
        })
    }
}
impl Error for PerspectiveError {}

fn cross(a: Corner, b: Corner, c: Corner) -> f64 {
    (b.x - a.x) * (c.y - b.y) - (b.y - a.y) * (c.x - b.x)
}
fn length(a: Corner, b: Corner) -> f64 { (a.x - b.x).hypot(a.y - b.y) }

pub fn valid(points: &[Corner; 4]) -> bool {
    if !points.iter().all(|p| p.x.is_finite() && p.y.is_finite() && (0.0..=1.0).contains(&p.x) && (0.0..=1.0).contains(&p.y)) {
        return false;
    }
    let area = (0..4).map(|i| {
        let a = points[i]; let b = points[(i + 1) % 4]; a.x * b.y - b.x * a.y
    }).sum::<f64>().abs() * 0.5;
    if area < 0.025 { return false; }
    let mut sign = 0;
    for i in 0..4 {
        if length(points[i], points[(i + 1) % 4]) < 0.04 { return false; }
        let value = cross(points[i], points[(i + 1) % 4], points[(i + 2) % 4]);
        if value.abs() < 0.0001 { return false; }
        let s = if value > 0.0 { 1 } else { -1 };
        if sign != 0 && sign != s { return false; }
        sign = s;
    }
    true
}

fn px(p: Corner, width: u32, height: u32) -> Corner {
    Corner { x: p.x * (width - 1) as f64, y: p.y * (height - 1) as f64 }
}

fn camera_ratio(points: &[Corner; 4], width: u32, height: u32) -> Option<f64> {
    let [p0,p1,p2,p3] = *points;
    let dx1 = p1.x - p2.x;
    let dx2 = p3.x - p2.x;
    let dx3 = p0.x - p1.x + p2.x - p3.x;
    let dy1 = p1.y - p2.y;
    let dy2 = p3.y - p2.y;
    let dy3 = p0.y - p1.y + p2.y - p3.y;
    let determinant = dx1 * dy2 - dx2 * dy1;
    if determinant.abs() < 1e-10 * width as f64 * height as f64 { return None; }
    let g = (dx3 * dy2 - dx2 * dy3) / determinant;
    let h = (dx1 * dy3 - dx3 * dy1) / determinant;
    let a = p1.x - p0.x + g * p1.x;
    let b = p3.x - p0.x + h * p3.x;
    let d = p1.y - p0.y + g * p1.y;
    let e = p3.y - p0.y + h * p3.y;
    let cx = (width - 1) as f64 / 2.0;
    let cy = (height - 1) as f64 / 2.0;
    let ux = a - cx * g; let uy = d - cy * g;
    let vx = b - cx * h; let vy = e - cy * h;
    let largest = width.max(height) as f64;
    let f2 = -(ux * vx + uy * vy) / (g * h);
    let focal = if f2.is_finite() && f2 >= (0.4 * largest).powi(2) && f2 <= (5.0 * largest).powi(2) {
        f2.sqrt()
    } else { 1.2 * largest };
    let ratio = (ux * ux + uy * uy + (focal * g).powi(2)).sqrt() /
        (vx * vx + vy * vy + (focal * h).powi(2)).sqrt();
    ratio.is_finite().then_some(ratio)
}

/// Return an output plan without ever exceeding max 4800 pixels on one side or 12 megapixels.
/// The four corners must be clockwise in image coordinates, beginning at the top-left.
pub fn plan(width: u32, height: u32, points: [Corner; 4], format: PaperFormat) -> Result<OutputPlan, PerspectiveError> {
    if width < 2 || height < 2 { return Err(PerspectiveError::InvalidImage); }
    if !valid(&points) { return Err(PerspectiveError::InvalidCorners); }
    let p = points.map(|pt| px(pt, width, height));
    let top = length(p[0], p[1]); let bottom = length(p[2], p[3]);
    let right = length(p[1], p[2]); let left = length(p[3], p[0]);
    let edge_ratio = (top + bottom) / (left + right);
    let auto = camera_ratio(&p, width, height)
        .filter(|r| (0.12..=8.0).contains(r) && *r >= edge_ratio / 2.7 && *r <= edge_ratio * 2.7);
    let ratio = format.ratio().or(auto).unwrap_or(edge_ratio);
    if !ratio.is_finite() || ratio <= 0.0 { return Err(PerspectiveError::InvalidCorners); }
    let wanted_width = top.max(bottom).max(right.max(left) * ratio);
    let wanted_height = wanted_width / ratio;
    let scale = 1.0_f64.min(4800.0 / wanted_width.max(wanted_height))
        .min((12_000_000.0 / (wanted_width * wanted_height)).sqrt());
    let output_width = (wanted_width * scale).floor().max(64.0).min(4800.0) as u32;
    let output_height = (wanted_height * scale).floor().max(64.0).min(4800.0) as u32;
    Ok(OutputPlan { width: output_width, height: output_height, ratio })
}

/// Expand the polygon away from its centre by at most 5%, with coordinates clipped to the image.
fn safe_border(points: [Corner; 4], amount: f64) -> [Corner; 4] {
    let cx = points.iter().map(|p| p.x).sum::<f64>() / 4.0;
    let cy = points.iter().map(|p| p.y).sum::<f64>() / 4.0;
    let expanded = points.map(|p| Corner {
        x: (cx + (p.x - cx) * (1.0 + amount)).clamp(0.0, 1.0),
        y: (cy + (p.y - cy) * (1.0 + amount)).clamp(0.0, 1.0),
    });
    if valid(&expanded) { expanded } else { points }
}

/// 8 parameter homography mapping an output coordinate to an input image coordinate.
fn solve_transform(destination: [Corner; 4], source: [Corner; 4]) -> Option<SVector<f64, 8>> {
    let mut a = SMatrix::<f64, 8, 8>::zeros();
    let mut b = SVector::<f64, 8>::zeros();
    for i in 0..4 {
        let (x, y) = (destination[i].x, destination[i].y);
        let (u, v) = (source[i].x, source[i].y);
        a[(2 * i, 0)] = x; a[(2 * i, 1)] = y; a[(2 * i, 2)] = 1.0;
        a[(2 * i, 6)] = -u * x; a[(2 * i, 7)] = -u * y;
        b[2 * i] = u;
        a[(2 * i + 1, 3)] = x; a[(2 * i + 1, 4)] = y; a[(2 * i + 1, 5)] = 1.0;
        a[(2 * i + 1, 6)] = -v * x; a[(2 * i + 1, 7)] = -v * y;
        b[2 * i + 1] = v;
    }
    a.lu().solve(&b)
}

fn sample(image: &RgbImage, x: f64, y: f64) -> Rgb<u8> {
    let x = x.clamp(0.0, (image.width() - 1) as f64);
    let y = y.clamp(0.0, (image.height() - 1) as f64);
    let x0 = x.floor() as u32; let y0 = y.floor() as u32;
    let x1 = (x0 + 1).min(image.width() - 1); let y1 = (y0 + 1).min(image.height() - 1);
    let fx = x - x0 as f64; let fy = y - y0 as f64;
    let q = [image.get_pixel(x0,y0), image.get_pixel(x1,y0), image.get_pixel(x0,y1), image.get_pixel(x1,y1)];
    let mut channels = [0u8; 3];
    for c in 0..3 {
        let top = q[0][c] as f64 * (1.0 - fx) + q[1][c] as f64 * fx;
        let bottom = q[2][c] as f64 * (1.0 - fx) + q[3][c] as f64 * fx;
        channels[c] = (top * (1.0 - fy) + bottom * fy).round().clamp(0.0, 255.0) as u8;
    }
    Rgb(channels)
}

/// Perform projective rectification on an owned RGB image (preserving its original).
/// Uses inverse mapping and bilinear interpolation to avoid holes.
pub fn rectify(input: &RgbImage, points: [Corner; 4], paper: PaperFormat, safety_border: bool) -> Result<RgbImage, PerspectiveError> {
    let plan = plan(input.width(), input.height(), points, paper)?;
    let corners = if safety_border { safe_border(points, 0.012) } else { points };
    let output = [
        Corner { x: 0.0, y: 0.0 },
        Corner { x: (plan.width - 1) as f64, y: 0.0 },
        Corner { x: (plan.width - 1) as f64, y: (plan.height - 1) as f64 },
        Corner { x: 0.0, y: (plan.height - 1) as f64 }
    ];
    let input_points = corners.map(|p| px(p, input.width(), input.height()));
    let transform = solve_transform(output, input_points).ok_or(PerspectiveError::SingularTransform)?;
    let mut result = RgbImage::new(plan.width, plan.height);
    for (x,y,pixel) in result.enumerate_pixels_mut() {
        let x = x as f64; let y = y as f64;
        let denominator = transform[6] * x + transform[7] * y + 1.0;
        if denominator.abs() < 1e-12 { return Err(PerspectiveError::SingularTransform); }
        let src_x = (transform[0] * x + transform[1] * y + transform[2]) / denominator;
        let src_y = (transform[3] * x + transform[4] * y + transform[5]) / denominator;
        if !src_x.is_finite() || !src_y.is_finite() { return Err(PerspectiveError::SingularTransform); }
        *pixel = sample(input, src_x, src_y);
    }
    Ok(result)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn rejects_bowties_and_degenerate_pages() {
        let bowtie = [Corner{x:0.1,y:0.1}, Corner{x:0.9,y:0.9}, Corner{x:0.9,y:0.1}, Corner{x:0.1,y:0.9}];
        assert!(!valid(&bowtie));
    }

    #[test]
    fn respects_a4_and_pixel_budget() {
        let quad = [Corner{x:0.0,y:0.0},Corner{x:1.0,y:0.0},Corner{x:1.0,y:1.0},Corner{x:0.0,y:1.0}];
        let plan = plan(8000, 8000, quad, PaperFormat::A4Portrait).unwrap();
        assert!((plan.width as f64 / plan.height as f64 - 210.0/297.0).abs() < 0.003);
        assert!(plan.width <= 4800 && plan.height <= 4800);
        assert!(plan.width as u64 * plan.height as u64 <= 12_000_000);
    }

    #[test]
    fn identity_transform_preserves_document() {
        let input = RgbImage::from_fn(128,128,|x,y| Rgb([x as u8, y as u8, 100]));
        let quad = [Corner{x:0.0,y:0.0},Corner{x:1.0,y:0.0},Corner{x:1.0,y:1.0},Corner{x:0.0,y:1.0}];
        let result = rectify(&input, quad, PaperFormat::Auto, false).unwrap();
        assert_eq!(result.dimensions(), input.dimensions());
        assert_eq!(*result.get_pixel(40,50), *input.get_pixel(40,50));
    }
}
