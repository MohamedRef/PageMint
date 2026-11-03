//! PageMint's platform-independent Rust image engine.
//! Android camera, permissions and document picker remain in a small host adapter.
//! No original document is uploaded or modified in place.

mod perspective;
mod enhance;
mod scan_bw;
mod detector;
#[cfg(target_os = "android")]
mod android;

pub use perspective::{Corner, OutputPlan, PaperFormat, PerspectiveError, plan, rectify};
pub use enhance::{EnhanceSettings, pro_enhance};
pub use scan_bw::scan_bw;
pub use detector::{detect, Detection};
