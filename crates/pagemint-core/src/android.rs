//! JNI bridge for the Android host. Transfer ABGR-safe, explicitly packed RGBA bytes.
//! Returned image is [u32 width LE][u32 height LE][RGBA pixels].
//! These routines never touch the network or Android storage.

use crate::{rectify, pro_enhance, Corner, EnhanceSettings, PaperFormat};
use image::{Rgb, RgbImage};
use jni::objects::{JByteArray, JFloatArray, JObject};
use jni::sys::{jboolean, jint, jbyteArray};
use jni::JNIEnv;

fn from_rgba(bytes: &[u8], width: u32, height: u32) -> Option<RgbImage> {
    let count = (width as usize).checked_mul(height as usize)?;
    if count == 0 || count > 18_000_000 || bytes.len() != count.checked_mul(4)? {
        return None;
    }
    let mut rgb = Vec::with_capacity(count * 3);
    for pixel in bytes.chunks_exact(4) {
        rgb.extend_from_slice(&pixel[..3]);
    }
    RgbImage::from_raw(width, height, rgb)
}

fn encode_rgba(image: &RgbImage) -> Vec<u8> {
    let mut output = Vec::with_capacity(8 + image.width() as usize * image.height() as usize * 4);
    output.extend_from_slice(&image.width().to_le_bytes());
    output.extend_from_slice(&image.height().to_le_bytes());
    for Rgb([r, g, b]) in image.pixels() {
        output.extend_from_slice(&[*r, *g, *b, 255]);
    }
    output
}

fn convert(env: &mut JNIEnv, image: JByteArray, width: jint, height: jint) -> Option<RgbImage> {
    if width <= 0 || height <= 0 { return None; }
    let bytes = env.convert_byte_array(image).ok()?;
    from_rgba(&bytes, width as u32, height as u32)
}

fn make_result(env: &mut JNIEnv, image: &RgbImage) -> jbyteArray {
    match env.byte_array_from_slice(&encode_rgba(image)) {
        Ok(result) => result.into_raw(),
        Err(_) => std::ptr::null_mut(),
    }
}

/// Called by com.mohamedref.pagemint.cv.RustProcessor.nativeRectify().
#[no_mangle]
pub extern "system" fn Java_com_mohamedref_pagemint_cv_RustProcessor_nativeRectify(
    mut env: JNIEnv,
    _receiver: JObject,
    pixels: JByteArray,
    width: jint,
    height: jint,
    quad: JFloatArray,
    paper: jint,
    with_border: jboolean,
) -> jbyteArray {
    let work = || -> Option<RgbImage> {
        let bitmap = convert(&mut env, pixels, width, height)?;
        let points = env.convert_float_array(quad).ok()?;
        if points.len() != 8 { return None; }
        let corners = [
            Corner { x: points[0] as f64, y: points[1] as f64 },
            Corner { x: points[2] as f64, y: points[3] as f64 },
            Corner { x: points[4] as f64, y: points[5] as f64 },
            Corner { x: points[6] as f64, y: points[7] as f64 },
        ];
        let selected = match paper {
            1 => PaperFormat::A4Portrait,
            2 => PaperFormat::A4Landscape,
            3 => PaperFormat::LetterPortrait,
            4 => PaperFormat::LetterLandscape,
            5 => PaperFormat::IdLandscape,
            _ => PaperFormat::Auto,
        };
        rectify(&bitmap, corners, selected, with_border != 0).ok()
    };
    // Malformed input should never take the entire Android process down.
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(work)).ok().flatten();
    result.as_ref().map(|image| make_result(&mut env, image)).unwrap_or(std::ptr::null_mut())
}

/// Called by com.mohamedref.pagemint.cv.RustProcessor.nativeEnhance().
#[no_mangle]
pub extern "system" fn Java_com_mohamedref_pagemint_cv_RustProcessor_nativeEnhance(
    mut env: JNIEnv,
    _receiver: JObject,
    pixels: JByteArray,
    width: jint,
    height: jint,
) -> jbyteArray {
    let work = || -> Option<RgbImage> {
        let bitmap = convert(&mut env, pixels, width, height)?;
        Some(pro_enhance(&bitmap, EnhanceSettings::default()))
    };
    let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(work)).ok().flatten();
    result.as_ref().map(|image| make_result(&mut env, image)).unwrap_or(std::ptr::null_mut())
}
