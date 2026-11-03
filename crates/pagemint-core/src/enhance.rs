use image::{Rgb, RgbImage};

/// Conservative locally adaptive enhancement. Modifies luminance while keeping hue
/// and saturation approximately stable. It cannot recover truly clipped pixels.
#[derive(Clone, Copy, Debug)]
pub struct EnhanceSettings {
    /// Overall strength, from 0 (original) to 1 (full enhancement).
    pub strength: f32,
    /// Sampling radius for local illumination, in original-image pixels.
    pub radius: u32,
}
impl Default for EnhanceSettings {
    fn default() -> Self { Self { strength: 0.8, radius: 64 } }
}

fn clamp_byte(x: f32) -> u8 { x.round().clamp(0.0, 255.0) as u8 }

/// Luminance correction using a downsampled integral-image local illumination map.
/// Avoids black-white binarization to preserve small handwriting and coloured stamps.
pub fn pro_enhance(input: &RgbImage, settings: EnhanceSettings) -> RgbImage {
    let (width, height) = input.dimensions();
    if width == 0 || height == 0 { return input.clone(); }
    let strength = settings.strength.clamp(0.0, 1.0);
    if strength <= f32::EPSILON { return input.clone(); }

    let stride = ((width.max(height) as f32 / 600.0).ceil() as u32).max(1);
    let grid_width = width.div_ceil(stride);
    let grid_height = height.div_ceil(stride);
    let cols = grid_width as usize + 1;
    let mut integral = vec![0f64; cols * (grid_height as usize + 1)];

    // +1-integral indexing avoids the need for separate handling at top/left boundaries.
    for y in 0..grid_height {
        let mut row_sum = 0.0f64;
        for x in 0..grid_width {
            let rgb = input.get_pixel((x * stride).min(width - 1), (y * stride).min(height - 1)).0;
            row_sum += 0.2126 * rgb[0] as f64 + 0.7152 * rgb[1] as f64 + 0.0722 * rgb[2] as f64;
            let idx = (y as usize + 1) * cols + (x as usize + 1);
            integral[idx] = integral[y as usize * cols + (x as usize + 1)] + row_sum;
        }
    }

    let radius = settings.radius.max(1).div_ceil(stride);
    let local_average = |x: u32, y: u32| -> f32 {
        let gx = (x / stride).min(grid_width - 1);
        let gy = (y / stride).min(grid_height - 1);
        let l = gx.saturating_sub(radius);
        let t = gy.saturating_sub(radius);
        let r = (gx + radius + 1).min(grid_width);
        let b = (gy + radius + 1).min(grid_height);
        let index = |x: u32, y: u32| integral[y as usize * cols + x as usize];
        let area = ((r - l) * (b - t)).max(1);
        ((index(r,b) - index(l,b) - index(r,t) + index(l,t)) / area as f64) as f32
    };

    RgbImage::from_fn(width, height, |x,y| {
        let rgb = input.get_pixel(x,y).0;
        let luma = 0.2126 * rgb[0] as f32 + 0.7152 * rgb[1] as f32 + 0.0722 * rgb[2] as f32;
        let background = local_average(x,y).max(32.0);
        let target = (luma * 242.0 / background).clamp(0.0, 255.0);
        // Mix with original luminance to avoid deleting faint text or coloured ink.
        let restored = luma * (1.0 - strength) + target * strength;
        let scale = if luma < 1.0 { 1.0 } else { restored / luma };
        Rgb([clamp_byte(rgb[0] as f32 * scale),clamp_byte(rgb[1] as f32 * scale),clamp_byte(rgb[2] as f32 * scale)])
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    #[test]
    fn zero_strength_is_identity() {
        let input = RgbImage::from_fn(160,200,|x,y| Rgb([x as u8, y as u8, 72]));
        assert_eq!(pro_enhance(&input, EnhanceSettings{ strength:0.0, radius:64 }), input);
    }
    #[test]
    fn neutral_grey_does_not_gain_unwanted_color() {
        let input = RgbImage::from_pixel(300,200,Rgb([150,150,150]));
        let output = pro_enhance(&input, EnhanceSettings::default());
        let px = output.get_pixel(100,100);
        assert_eq!(px[0],px[1]);
        assert_eq!(px[1],px[2]);
    }
    #[test]
    fn retains_color_information() {
        let input = RgbImage::from_pixel(200,200,Rgb([170,70,60]));
        let out = pro_enhance(&input, EnhanceSettings::default());
        let px = out.get_pixel(100,100);
        assert!(px[0] > px[1] && px[1] > px[2]);
    }
}
