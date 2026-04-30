use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jint, jstring};
use jni::JNIEnv;
use mermaid_rs_renderer::{RenderOptions, Theme, render_with_options};
use std::ptr::null_mut;
use std::sync::Mutex;

static LAST_ERROR: Mutex<Option<String>> = Mutex::new(None);

fn set_last_error(message: impl Into<String>) {
    if let Ok(mut slot) = LAST_ERROR.lock() {
        *slot = Some(message.into());
    }
}

fn clear_last_error() {
    if let Ok(mut slot) = LAST_ERROR.lock() {
        *slot = None;
    }
}

fn rgb_hex_from_argb(argb: jint) -> String {
    let value = argb as u32;
    format!("#{:02x}{:02x}{:02x}", (value >> 16) & 0xff, (value >> 8) & 0xff, value & 0xff)
}

fn rgba_from_argb(argb: jint, alpha: f32) -> String {
    let value = argb as u32;
    let r = (value >> 16) & 0xff;
    let g = (value >> 8) & 0xff;
    let b = value & 0xff;
    format!("rgba({r}, {g}, {b}, {alpha:.3})")
}

/// Compute relative luminance per WCAG 2.1 from RGB (0-255).
fn luminance_from_argb(argb: jint) -> f32 {
    let value = argb as u32;
    let r = ((value >> 16) & 0xff) as f32 / 255.0;
    let g = ((value >> 8) & 0xff) as f32 / 255.0;
    let b = (value & 0xff) as f32 / 255.0;
    let r = if r <= 0.03928 { r / 12.92 } else { ((r + 0.055) / 1.055).powf(2.4) };
    let g = if g <= 0.03928 { g / 12.92 } else { ((g + 0.055) / 1.055).powf(2.4) };
    let b = if b <= 0.03928 { b / 12.92 } else { ((b + 0.055) / 1.055).powf(2.4) };
    0.2126 * r + 0.7152 * g + 0.0722 * b
}

/// Contrast ratio between two luminance values (returns 1.0–21.0).
fn contrast_ratio(l1: f32, l2: f32) -> f32 {
    let lighter = l1.max(l2);
    let darker = l1.min(l2);
    (lighter + 0.05) / (darker + 0.05)
}

/// Choose between the provided text color and a fallback (white/black) based on
/// which yields a higher contrast ratio on the given background ARGB.
/// Returns an RGB hex string suitable for Mermaid theme fields.
fn contrast_adjusted_text_hex(text_argb: jint, bg_argb: jint) -> String {
    let bg_lum = luminance_from_argb(bg_argb);
    let text_lum = luminance_from_argb(text_argb);
    let current_ratio = contrast_ratio(text_lum, bg_lum);

    // Fallback candidates
    let white_ratio = contrast_ratio(1.0, bg_lum);   // white = luminance 1.0
    let black_ratio = contrast_ratio(0.0, bg_lum);   // black = luminance 0.0

    // Pick whichever candidate gives the best contrast
    let (best_r, best_g, best_b) = if white_ratio >= black_ratio && white_ratio >= current_ratio {
        (255, 255, 255)
    } else if black_ratio >= current_ratio {
        (0, 0, 0)
    } else {
        // Current text color already wins
        let value = text_argb as u32;
        ((value >> 16) & 0xff, (value >> 8) & 0xff, value & 0xff)
    };
    format!("#{:02x}{:02x}{:02x}", best_r, best_g, best_b)
}

/// Adjust a line/border color to maintain visible contrast against a background,
/// without jumping to pure black/white. Shifts luminance by at least `min_delta`
/// away from the background luminance, preserving the original hue character.
fn line_color_adjusted(border_argb: jint, bg_argb: jint, dark_theme: bool) -> String {
    let bg_lum = luminance_from_argb(bg_argb);
    let border_lum = luminance_from_argb(border_argb);
    let delta = border_lum - bg_lum;
    // If border is already well-separated, keep it as-is
    if delta.abs() > 0.20 {
        return rgb_hex_from_argb(border_argb);
    }

    // Shift border away from background luminance
    let target_lum = if dark_theme {
        (bg_lum + 0.30).clamp(0.4, 0.85)
    } else {
        (bg_lum - 0.30).clamp(0.05, 0.6)
    };

    let value = border_argb as u32;
    let r = ((value >> 16) & 0xff) as f32 / 255.0;
    let g = ((value >> 8) & 0xff) as f32 / 255.0;
    let b = (value & 0xff) as f32 / 255.0;
    let current_lum = 0.2126 * r + 0.7152 * g + 0.0722 * b;

    let scale = if current_lum > 0.001 { target_lum / current_lum } else { 1.0 };
    let scale = scale.clamp(0.1, 5.0);

    let r = (r * scale * 255.0).round().clamp(0.0, 255.0) as u8;
    let g = (g * scale * 255.0).round().clamp(0.0, 255.0) as u8;
    let b = (b * scale * 255.0).round().clamp(0.0, 255.0) as u8;
    format!("#{:02x}{:02x}{:02x}", r, g, b)
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_letta_mobile_ui_components_MermaidNativeBridge_nativeRenderToSvg(
    mut env: JNIEnv,
    _class: JClass,
    source: JString,
    dark_theme: jboolean,
    text_argb: jint,
    border_argb: jint,
    surface_argb: jint,
    primary_argb: jint,
    secondary_argb: jint,
    tertiary_argb: jint,
) -> jstring {
    clear_last_error();

    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => {
            set_last_error(format!("failed to read Mermaid source from JNI: {error}"));
            return null_mut();
        }
    };

    let mut theme = Theme::modern();
    let surface_alpha = if dark_theme == 0 { 0.92 } else { 0.72 };
    let surface = rgba_from_argb(surface_argb, surface_alpha);
    let primary = rgb_hex_from_argb(primary_argb);
    let secondary = rgb_hex_from_argb(secondary_argb);
    let tertiary = rgb_hex_from_argb(tertiary_argb);

    theme.background = "transparent".to_string();

    let foreground = contrast_adjusted_text_hex(text_argb, surface_argb);
    let line_color = line_color_adjusted(border_argb, surface_argb, dark_theme != 0);

    theme.primary_text_color = foreground.clone();
    theme.text_color = foreground.clone();
    theme.line_color = line_color.clone();
    theme.primary_border_color = line_color.clone();
    theme.edge_label_background = "none".to_string();
    theme.cluster_background = surface.clone();
    theme.cluster_border = line_color.clone();
    theme.primary_color = primary.clone();
    theme.secondary_color = secondary.clone();
    theme.tertiary_color = tertiary.clone();
    theme.sequence_actor_fill = primary.clone();
    theme.sequence_actor_border = line_color.clone();
    theme.sequence_actor_line = line_color.clone();
    theme.sequence_note_fill = tertiary.clone();
    theme.sequence_note_border = line_color.clone();
    theme.sequence_activation_fill = secondary.clone();
    theme.sequence_activation_border = line_color.clone();
    theme.git_commit_label_background = surface.clone();
    theme.git_tag_label_background = surface.clone();
    theme.git_tag_label_border = line_color.clone();

    let options = RenderOptions::modern();
    let options = RenderOptions {
        theme,
        layout: options.layout,
    };

    let svg = match render_with_options(&source, options) {
        Ok(svg) => svg,
        Err(error) => {
            set_last_error(format!("native Mermaid render failed: {error}"));
            return null_mut();
        }
    };

    match env.new_string(svg) {
        Ok(value) => value.into_raw(),
        Err(error) => {
            set_last_error(format!("failed to allocate SVG JNI string: {error}"));
            null_mut()
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_letta_mobile_ui_components_MermaidNativeBridge_nativeTakeLastError(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let message = LAST_ERROR.lock().ok().and_then(|mut slot| slot.take());
    match message {
        Some(message) => match env.new_string(message) {
            Ok(value) => value.into_raw(),
            Err(_) => null_mut(),
        },
        None => null_mut(),
    }
}
