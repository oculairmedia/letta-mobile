use jni::objects::{JClass, JString};
use jni::sys::{jboolean, jbyteArray, jint, jstring};
use jni::JNIEnv;
use mermaid_rs_renderer::{RenderOptions, Theme, render_with_options};
use std::ptr::null_mut;
use std::sync::{Arc, LazyLock, Mutex, OnceLock};

static LAST_ERROR: Mutex<Option<String>> = Mutex::new(None);
static STYLE_RE: LazyLock<regex::Regex> = LazyLock::new(|| {
    regex::Regex::new(
        r"(?m)^(\s*)style\s+(\S+)\s+((?:fill|stroke|color|stroke-width|stroke-dasharray)[^,]*(?:,\s*(?:fill|stroke|color|stroke-width|stroke-dasharray)[^,]*)*)",
    ).unwrap()
});
static FILL_RE: LazyLock<regex::Regex> = LazyLock::new(|| {
    regex::Regex::new(r"fill:\s*(#[0-9a-fA-F]{6})").unwrap()
});

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

/// Pre-process Mermaid source to inject color parameter into style commands.
/// The mermaid-rs-renderer already supports the `color` parameter in style commands,
/// which sets the text color for styled nodes. This function automatically calculates
/// and injects the appropriate text color based on the fill color for contrast,
/// using the Material Design theme text color.
fn inject_contrast_colors(source: &str, text_argb: jint) -> String {
    let mut result = source.to_string();
    
    // Use Material Design text color for light backgrounds
    let theme_text_color = rgb_hex_from_argb(text_argb);
    
    // Match style commands: "style NodeName fill:#xxxxxx,stroke:..."
    // Pattern: style <nodename> <style-options>
    result = STYLE_RE.replace_all(&result, |caps: &regex::Captures| {
        let _indent = caps.get(1).map_or("", |m| m.as_str());
        let _node_name = caps.get(2).map_or("", |m| m.as_str());
        let style_part = caps.get(3).map_or("", |m| m.as_str());
        
        // Check if color is already specified
        let has_color = style_part.contains("color:");
        
        if has_color {
            // Already has color, keep as-is
            return caps.get(0).map_or("", |m| m.as_str()).to_string();
        }
        
        // Extract fill color to calculate appropriate text color
        let text_color = if let Some(cap) = FILL_RE.captures(style_part) {
            if let Some(fill) = cap.get(1) {
                let fill_str = fill.as_str().to_lowercase();
                // Use Material theme text color for light backgrounds, white for dark
                if is_light_color(&fill_str) {
                    theme_text_color.clone()  // Material theme text color
                } else {
                    "#ffffff".to_string()  // White text for dark backgrounds
                }
            } else {
                String::new()
            }
        } else {
            String::new()
        };
        
        if text_color.is_empty() {
            // No fill color found, keep as-is
            return caps.get(0).map_or("", |m| m.as_str()).to_string();
        }
        
        // Inject color parameter
        format!(
            "style {} {},color:{}",
            _node_name,
            style_part.trim().trim_end_matches(','),
            text_color,
        )
    }).to_string();
    
    result
}

fn is_light_color(hex: &str) -> bool {
    if hex.len() != 7 || !hex.starts_with('#') {
        return false;
    }
    
    let r = u8::from_str_radix(&hex[1..3], 16).ok();
    let g = u8::from_str_radix(&hex[3..5], 16).ok();
    let b = u8::from_str_radix(&hex[5..7], 16).ok();
    
    match (r, g, b) {
        (Some(r), Some(g), Some(b)) => {
            let lum = 0.299 * r as f32 + 0.587 * g as f32 + 0.114 * b as f32;
            lum > 150.0
        }
        _ => false,
    }
}

/// Theme colors shared by every rendering entry point.
#[derive(Clone, Copy)]
struct ThemeArgb {
    dark_theme: bool,
    text: jint,
    border: jint,
    surface: jint,
    primary: jint,
    secondary: jint,
    tertiary: jint,
}

/// Render Mermaid source to an SVG document using the Material theme mapping.
///
/// This is the single source of truth for theme mapping + contrast injection;
/// both the SVG and the PNG entry points go through it.
fn build_svg(source: &str, colors: ThemeArgb) -> Result<String, String> {
    let ThemeArgb {
        dark_theme,
        text: text_argb,
        border: border_argb,
        surface: surface_argb,
        primary: primary_argb,
        secondary: secondary_argb,
        tertiary: tertiary_argb,
    } = colors;

    let mut theme = Theme::modern();
    let surface_alpha = if !dark_theme { 0.92 } else { 0.72 };
    let surface = rgba_from_argb(surface_argb, surface_alpha);
    let primary = rgb_hex_from_argb(primary_argb);
    let secondary = rgb_hex_from_argb(secondary_argb);
    let tertiary = rgb_hex_from_argb(tertiary_argb);

    theme.background = "transparent".to_string();

    let foreground = contrast_adjusted_text_hex(text_argb, surface_argb);
    let line_color = line_color_adjusted(border_argb, surface_argb, dark_theme);

    theme.primary_text_color = contrast_adjusted_text_hex(text_argb, primary_argb);
    theme.text_color = foreground.clone();
    theme.line_color = line_color.clone();
    theme.primary_border_color = line_color.clone();
    // Opaque-ish backdrop behind edge labels: with "none", labels drawn over
    // crossing edges (or over each other in tight layouts) become unreadable
    // text soup. A surface-toned pill masks whatever passes underneath.
    theme.edge_label_background = rgba_from_argb(surface_argb, 0.85);
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

    // 0.2.2 centers every edge label on its edge midpoint. Converging edges in
    // the same rank gap therefore collide unless the columns are far enough
    // apart for the label boxes to clear each other — hence the wide node
    // spacing; rank spacing gives multi-line labels vertical room. (0.3.1 was
    // tried and regressed: labels clipped off-canvas, broken cluster edges.)
    let options = RenderOptions::modern()
        .with_node_spacing(170.0)
        .with_rank_spacing(150.0);
    let mut layout = options.layout;
    // Narrower wrap (default 22): converging edges put two labels in the same
    // rank gap, and only slim label boxes can sit side by side there after the
    // de-overlap pass below.
    layout.max_label_width_chars = 16;
    let options = RenderOptions { theme, layout };

    // Pre-process Mermaid source to inject contrast-aware text colors into style commands
    let processed_source = inject_contrast_colors(source, text_argb);

    render_with_options(&processed_source, options)
        .map(|svg| de_overlap_edge_labels(&svg))
        .map_err(|error| format!("native Mermaid render failed: {error}"))
}

// ---------------------------------------------------------------------------
// Edge-label de-overlap post-pass.
//
// mermaid-rs-renderer 0.2.2 centers every edge label on its edge midpoint and
// emits its backdrop <rect> with fill-opacity="0.00". Converging edges have
// near-identical midpoints, so multi-line labels stack into unreadable text
// soup no matter how the layout is spaced. The emitted SVG tags each label
// pair (<rect data-edge-id=..> + <g class="edgeLabel" data-edge-id=..>), so we
// repair it here: measure honest label boxes from the tspans, iteratively push
// intersecting boxes apart, then rewrite the rect geometry (opacity on) and
// translate the matching text group by the same delta.
// ---------------------------------------------------------------------------

const LABEL_FONT_SIZE: f32 = 14.0;
/// Average glyph advance ≈ 0.55em for Inter-like UI faces at small sizes.
const LABEL_CHAR_WIDTH: f32 = LABEL_FONT_SIZE * 0.55;
const LABEL_PAD_X: f32 = 8.0;
const LABEL_SEPARATION_MARGIN: f32 = 4.0;

static LABEL_RECT_RE: LazyLock<regex::Regex> = LazyLock::new(|| {
    regex::Regex::new(
        r#"<rect data-edge-id="(edge-\d+)" data-label-kind="center" x="([-\d.]+)" y="([-\d.]+)" width="([-\d.]+)" height="([-\d.]+)" rx="[-\d.]+" ry="[-\d.]+" fill="([^"]*)" fill-opacity="[-\d.]+" stroke="[^"]*" stroke-opacity="[-\d.]+" stroke-width="[-\d.]+"/>"#,
    ).unwrap()
});
static LABEL_GROUP_RE: LazyLock<regex::Regex> = LazyLock::new(|| {
    regex::Regex::new(r#"<g class="edgeLabel" data-edge-id="(edge-\d+)" data-label-kind="center">"#).unwrap()
});
static LABEL_TEXT_X_RE: LazyLock<regex::Regex> = LazyLock::new(|| {
    regex::Regex::new(r#"<text x="([-\d.]+)""#).unwrap()
});
static LABEL_TSPAN_RE: LazyLock<regex::Regex> = LazyLock::new(|| {
    regex::Regex::new(r#"<tspan[^>]*>([^<]*)</tspan>"#).unwrap()
});
/// Node body rects (obstacles): mermaid-rs 0.2.2 emits them with
/// stroke-linejoin, which cluster/background rects don't carry.
static NODE_RECT_RE: LazyLock<regex::Regex> = LazyLock::new(|| {
    regex::Regex::new(
        r#"<rect x="([-\d.]+)" y="([-\d.]+)" width="([-\d.]+)" height="([-\d.]+)" rx="[-\d.]+" ry="[-\d.]+" fill="[^"]*" stroke="[^"]*" stroke-width="[-\d.]+" stroke-linejoin"#,
    ).unwrap()
});
static SVG_SIZE_RE: LazyLock<regex::Regex> = LazyLock::new(|| {
    regex::Regex::new(r#"<svg [^>]*width="([-\d.]+)" height="([-\d.]+)""#).unwrap()
});

#[derive(Clone)]
struct LabelBox {
    id: String,
    x: f32,
    y: f32,
    w: f32,
    h: f32,
    dx: f32,
    dy: f32,
}

fn de_overlap_edge_labels(svg: &str) -> String {
    let mut boxes: Vec<LabelBox> = Vec::new();
    for caps in LABEL_RECT_RE.captures_iter(svg) {
        let id = caps[1].to_string();
        let (Ok(x), Ok(y), Ok(w), Ok(h)) = (
            caps[2].parse::<f32>(),
            caps[3].parse::<f32>(),
            caps[4].parse::<f32>(),
            caps[5].parse::<f32>(),
        ) else {
            continue;
        };
        // The emitted rect width is a uniform worst-case, far wider than the
        // text. Shrink to the longest tspan so collision checks (and the now
        // visible backdrop) hug the actual label.
        let text_w = label_text_width(svg, &id).unwrap_or(w);
        let shrunk = text_w.min(w);
        boxes.push(LabelBox {
            id,
            x: x + (w - shrunk) / 2.0,
            y,
            w: shrunk,
            h,
            dx: 0.0,
            dy: 0.0,
        });
    }
    if boxes.is_empty() {
        return svg.to_string();
    }

    // Node bodies are immovable obstacles: a label shoved onto a node is as
    // unreadable as two labels shoved onto each other.
    let obstacles: Vec<(f32, f32, f32, f32)> = NODE_RECT_RE
        .captures_iter(svg)
        .filter_map(|caps| {
            Some((
                caps[1].parse::<f32>().ok()?,
                caps[2].parse::<f32>().ok()?,
                caps[3].parse::<f32>().ok()?,
                caps[4].parse::<f32>().ok()?,
            ))
        })
        .collect();
    let canvas = SVG_SIZE_RE
        .captures(svg)
        .and_then(|caps| Some((caps[1].parse::<f32>().ok()?, caps[2].parse::<f32>().ok()?)));

    // Deterministic resolve. Colliding labels share a rank gap that is usually
    // too short to stack them vertically (iterative pushes oscillate against
    // the node rows and never converge), but the canvas is almost always wide
    // enough to seat them side by side. So: group mutually-overlapping labels,
    // lay each group out as one horizontal row centered on the group's mean
    // center, then nudge off any node and clamp onto the canvas.
    let components = overlap_components(&boxes);
    for component in components {
        if component.len() < 2 {
            continue;
        }
        let mut members = component;
        members.sort_by(|&a, &b| {
            let ca = boxes[a].x + boxes[a].w / 2.0;
            let cb = boxes[b].x + boxes[b].w / 2.0;
            ca.partial_cmp(&cb).unwrap_or(std::cmp::Ordering::Equal)
        });
        let total_w: f32 = members.iter().map(|&i| boxes[i].w).sum::<f32>()
            + LABEL_SEPARATION_MARGIN * (members.len() - 1) as f32;
        let mean_cx: f32 =
            members.iter().map(|&i| boxes[i].x + boxes[i].w / 2.0).sum::<f32>() / members.len() as f32;
        let mut cursor = mean_cx - total_w / 2.0;
        if let Some((cw, _)) = canvas {
            cursor = cursor.clamp(
                LABEL_SEPARATION_MARGIN,
                (cw - total_w - LABEL_SEPARATION_MARGIN).max(LABEL_SEPARATION_MARGIN),
            );
        }
        for &i in &members {
            boxes[i].dx = cursor - boxes[i].x;
            cursor += boxes[i].w + LABEL_SEPARATION_MARGIN;
        }
    }

    // Single obstacle pass: a label that still straddles a node row slides off
    // along its least-penetration axis. Then keep everything on the canvas.
    for label in boxes.iter_mut() {
        for &(ox, oy, ow, oh) in &obstacles {
            let lx = label.x + label.dx;
            let ly = label.y + label.dy;
            let overlap_x = ((lx + label.w).min(ox + ow) - lx.max(ox)) + LABEL_SEPARATION_MARGIN;
            let overlap_y = ((ly + label.h).min(oy + oh) - ly.max(oy)) + LABEL_SEPARATION_MARGIN;
            if overlap_x <= 0.0 || overlap_y <= 0.0 {
                continue;
            }
            if overlap_y <= overlap_x {
                if ly + label.h / 2.0 <= oy + oh / 2.0 {
                    label.dy -= overlap_y;
                } else {
                    label.dy += overlap_y;
                }
            } else if lx + label.w / 2.0 <= ox + ow / 2.0 {
                label.dx -= overlap_x;
            } else {
                label.dx += overlap_x;
            }
        }
        if let Some((cw, ch)) = canvas {
            let min_dx = LABEL_SEPARATION_MARGIN - label.x;
            let max_dx = cw - LABEL_SEPARATION_MARGIN - label.w - label.x;
            let min_dy = LABEL_SEPARATION_MARGIN - label.y;
            let max_dy = ch - LABEL_SEPARATION_MARGIN - label.h - label.y;
            if max_dx >= min_dx {
                label.dx = label.dx.clamp(min_dx, max_dx);
            }
            if max_dy >= min_dy {
                label.dy = label.dy.clamp(min_dy, max_dy);
            }
        }
    }

    apply_label_fixes(svg, &boxes)
}

/// Connected components of the label-overlap graph (indices into `boxes`).
fn overlap_components(boxes: &[LabelBox]) -> Vec<Vec<usize>> {
    let n = boxes.len();
    let mut component_of: Vec<Option<usize>> = vec![None; n];
    let mut components: Vec<Vec<usize>> = Vec::new();
    for start in 0..n {
        if component_of[start].is_some() {
            continue;
        }
        let id = components.len();
        let mut stack = vec![start];
        let mut members = Vec::new();
        component_of[start] = Some(id);
        while let Some(i) = stack.pop() {
            members.push(i);
            for j in 0..n {
                if component_of[j].is_some() {
                    continue;
                }
                let a = &boxes[i];
                let b = &boxes[j];
                let overlap_x = ((a.x + a.w).min(b.x + b.w) - a.x.max(b.x)) + LABEL_SEPARATION_MARGIN;
                let overlap_y = ((a.y + a.h).min(b.y + b.h) - a.y.max(b.y)) + LABEL_SEPARATION_MARGIN;
                if overlap_x > 0.0 && overlap_y > 0.0 {
                    component_of[j] = Some(id);
                    stack.push(j);
                }
            }
        }
        components.push(members);
    }
    components
}

/// Width of the widest tspan of the label group for [edge_id], padded.
fn label_text_width(svg: &str, edge_id: &str) -> Option<f32> {
    let marker = format!(r#"<g class="edgeLabel" data-edge-id="{edge_id}" data-label-kind="center">"#);
    let start = svg.find(&marker)?;
    let rest = &svg[start..];
    let end = rest.find("</g>")?;
    let group = &rest[..end];
    let longest = LABEL_TSPAN_RE
        .captures_iter(group)
        .map(|c| c[1].chars().count())
        .max()?;
    Some(longest as f32 * LABEL_CHAR_WIDTH + LABEL_PAD_X * 2.0)
}

/// Rewrite label rects (new geometry, backdrop visible) and translate label
/// text groups by their resolved offsets.
fn apply_label_fixes(svg: &str, boxes: &[LabelBox]) -> String {
    let mut result = LABEL_RECT_RE
        .replace_all(svg, |caps: &regex::Captures| {
            let id = &caps[1];
            let Some(label) = boxes.iter().find(|b| b.id == id) else {
                return caps[0].to_string();
            };
            let fill = &caps[6];
            format!(
                r#"<rect data-edge-id="{}" data-label-kind="center" x="{:.2}" y="{:.2}" width="{:.2}" height="{:.2}" rx="4" ry="4" fill="{}" fill-opacity="1.00" stroke="none" stroke-opacity="0.00" stroke-width="0"/>"#,
                id,
                label.x + label.dx,
                label.y + label.dy,
                label.w,
                label.h,
                fill,
            )
        })
        .to_string();
    result = LABEL_GROUP_RE
        .replace_all(&result, |caps: &regex::Captures| {
            let id = &caps[1];
            let Some(label) = boxes.iter().find(|b| b.id == id) else {
                return caps[0].to_string();
            };
            if label.dx == 0.0 && label.dy == 0.0 {
                return caps[0].to_string();
            }
            format!(
                r#"<g class="edgeLabel" data-edge-id="{}" data-label-kind="center" transform="translate({:.2}, {:.2})">"#,
                id, label.dx, label.dy,
            )
        })
        .to_string();
    result
}

fn render_to_svg(
    mut env: JNIEnv,
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

    let colors = ThemeArgb {
        dark_theme: dark_theme != 0,
        text: text_argb,
        border: border_argb,
        surface: surface_argb,
        primary: primary_argb,
        secondary: secondary_argb,
        tertiary: tertiary_argb,
    };

    let svg = match build_svg(&source, colors) {
        Ok(svg) => svg,
        Err(error) => {
            set_last_error(error);
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

// ---------------------------------------------------------------------------
// SVG -> PNG rasterization (desktop path)
//
// The desktop client used to rasterize the SVG with skiko's `SVGDOM`, which
// exposes no font manager and therefore silently dropped every `<text>` node.
// Rasterizing here with resvg lets us bind a real font database so labels
// actually paint.
// ---------------------------------------------------------------------------

/// Maximum pixmap edge, guarding against pathological diagrams eating memory.
const MAX_PIXMAP_DIMENSION: f32 = 8192.0;
const MIN_RASTER_SCALE: f32 = 0.5;
const MAX_RASTER_SCALE: f32 = 4.0;

/// System font database, built once — `load_system_fonts()` is expensive.
static FONT_DB: OnceLock<Arc<fontdb::Database>> = OnceLock::new();

/// First candidate family that is actually installed, else `fallback`.
fn pick_family(db: &fontdb::Database, candidates: &[&str], fallback: &str) -> String {
    for candidate in candidates {
        let query = fontdb::Query {
            families: &[fontdb::Family::Name(candidate)],
            ..fontdb::Query::default()
        };
        if db.query(&query).is_some() {
            return (*candidate).to_string();
        }
    }
    fallback.to_string()
}

fn font_database() -> Arc<fontdb::Database> {
    FONT_DB
        .get_or_init(|| {
            let mut db = fontdb::Database::new();
            db.load_system_fonts();

            // Generic-family defaults, per-OS preferred first with a portable
            // fallback chain behind it.
            let sans = pick_family(
                &db,
                &["Segoe UI", "Helvetica Neue", "Arial", "Liberation Sans", "DejaVu Sans", "Noto Sans"],
                "sans-serif",
            );
            let serif = pick_family(
                &db,
                &["Georgia", "Times New Roman", "Liberation Serif", "DejaVu Serif", "Noto Serif"],
                "serif",
            );
            let mono = pick_family(
                &db,
                &["Consolas", "Menlo", "DejaVu Sans Mono", "Liberation Mono", "Courier New"],
                "monospace",
            );
            db.set_sans_serif_family(sans.clone());
            db.set_serif_family(serif);
            db.set_monospace_family(mono);
            db.set_cursive_family(sans.clone());
            db.set_fantasy_family(sans);
            Arc::new(db)
        })
        .clone()
}

/// Rasterize an SVG document to PNG bytes, scaled so the output is roughly
/// `target_width_px` wide while preserving the SVG's intrinsic aspect ratio.
fn svg_to_png(svg: &str, target_width_px: i32) -> Result<Vec<u8>, String> {
    let mut options = resvg::usvg::Options {
        fontdb: font_database(),
        ..resvg::usvg::Options::default()
    };
    let db = font_database();
    options.font_family = db.family_name(&fontdb::Family::SansSerif).to_string();

    let tree = resvg::usvg::Tree::from_str(svg, &options)
        .map_err(|error| format!("failed to parse rendered SVG: {error}"))?;

    let size = tree.size();
    let (intrinsic_w, intrinsic_h) = (size.width(), size.height());
    if !(intrinsic_w.is_finite() && intrinsic_h.is_finite()) || intrinsic_w <= 0.0 || intrinsic_h <= 0.0 {
        return Err(format!("rendered SVG has invalid size {intrinsic_w}x{intrinsic_h}"));
    }

    let mut scale = if target_width_px > 0 {
        target_width_px as f32 / intrinsic_w
    } else {
        1.0
    };
    if !scale.is_finite() || scale <= 0.0 {
        scale = 1.0;
    }
    scale = scale.clamp(MIN_RASTER_SCALE, MAX_RASTER_SCALE);

    // Cap the pixmap so a huge diagram cannot exhaust memory. Both axes are
    // scaled by the same factor, so the aspect ratio is preserved.
    let max_edge = intrinsic_w.max(intrinsic_h);
    if max_edge * scale > MAX_PIXMAP_DIMENSION {
        scale = MAX_PIXMAP_DIMENSION / max_edge;
    }

    let width = (intrinsic_w * scale).round().max(1.0) as u32;
    let height = (intrinsic_h * scale).round().max(1.0) as u32;

    let mut pixmap = resvg::tiny_skia::Pixmap::new(width, height)
        .ok_or_else(|| format!("failed to allocate {width}x{height} pixmap"))?;

    resvg::render(
        &tree,
        resvg::tiny_skia::Transform::from_scale(scale, scale),
        &mut pixmap.as_mut(),
    );

    pixmap
        .encode_png()
        .map_err(|error| format!("failed to encode PNG: {error}"))
}

fn render_to_png(
    mut env: JNIEnv,
    source: JString,
    dark_theme: jboolean,
    text_argb: jint,
    border_argb: jint,
    surface_argb: jint,
    primary_argb: jint,
    secondary_argb: jint,
    tertiary_argb: jint,
    target_width_px: jint,
) -> jbyteArray {
    clear_last_error();

    let source: String = match env.get_string(&source) {
        Ok(value) => value.into(),
        Err(error) => {
            set_last_error(format!("failed to read Mermaid source from JNI: {error}"));
            return null_mut();
        }
    };

    let colors = ThemeArgb {
        dark_theme: dark_theme != 0,
        text: text_argb,
        border: border_argb,
        surface: surface_argb,
        primary: primary_argb,
        secondary: secondary_argb,
        tertiary: tertiary_argb,
    };

    let svg = match build_svg(&source, colors) {
        Ok(svg) => svg,
        Err(error) => {
            set_last_error(error);
            return null_mut();
        }
    };

    let png = match svg_to_png(&svg, target_width_px) {
        Ok(png) => png,
        Err(error) => {
            set_last_error(error);
            return null_mut();
        }
    };

    match env.byte_array_from_slice(&png) {
        Ok(array) => array.into_raw(),
        Err(error) => {
            set_last_error(format!("failed to allocate PNG byte array: {error}"));
            null_mut()
        }
    }
}

fn take_last_error(env: JNIEnv) -> jstring {
    let message = LAST_ERROR.lock().ok().and_then(|mut slot| slot.take());
    match message {
        Some(message) => match env.new_string(message) {
            Ok(value) => value.into_raw(),
            Err(_) => null_mut(),
        },
        None => null_mut(),
    }
}


#[unsafe(no_mangle)]
pub extern "system" fn Java_com_letta_mobile_mermaid_MermaidNativeRenderer_nativeRenderToSvg(
    env: JNIEnv,
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
    render_to_svg(
        env, source, dark_theme, text_argb, border_argb, surface_argb,
        primary_argb, secondary_argb, tertiary_argb,
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_letta_mobile_mermaid_MermaidNativeRenderer_nativeRenderToPng(
    env: JNIEnv,
    _class: JClass,
    source: JString,
    dark_theme: jboolean,
    text_argb: jint,
    border_argb: jint,
    surface_argb: jint,
    primary_argb: jint,
    secondary_argb: jint,
    tertiary_argb: jint,
    target_width_px: jint,
) -> jbyteArray {
    render_to_png(
        env, source, dark_theme, text_argb, border_argb, surface_argb,
        primary_argb, secondary_argb, tertiary_argb, target_width_px,
    )
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_com_letta_mobile_mermaid_MermaidNativeRenderer_nativeTakeLastError(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    take_last_error(env)
}

#[cfg(test)]
mod tests {
    use super::*;

    const LIGHT: ThemeArgb = ThemeArgb {
        dark_theme: false,
        text: 0xff1c1b1f_u32 as i32,
        border: 0xff79747e_u32 as i32,
        surface: 0xfffffbfe_u32 as i32,
        primary: 0xff6750a4_u32 as i32,
        secondary: 0xff625b71_u32 as i32,
        tertiary: 0xff7d5260_u32 as i32,
    };

    #[test]
    fn renders_flowchart_to_valid_png_with_content() {
        let svg = build_svg("graph TD; A[Hello]-->B[World]", LIGHT).expect("svg render");
        assert!(svg.contains("<svg"), "expected an SVG document");

        let png = svg_to_png(&svg, 1024).expect("png render");
        assert!(!png.is_empty(), "PNG byte array must not be empty");
        assert_eq!(
            &png[..8],
            &[0x89, b'P', b'N', b'G', 0x0d, 0x0a, 0x1a, 0x0a],
            "expected PNG magic bytes"
        );

        // Rasterize again to inspect pixels: the diagram must paint something,
        // i.e. the pixmap is not uniformly the (transparent) background.
        let options = resvg::usvg::Options {
            fontdb: font_database(),
            ..resvg::usvg::Options::default()
        };
        let tree = resvg::usvg::Tree::from_str(&svg, &options).expect("parse svg");
        let size = tree.size();
        let mut pixmap = resvg::tiny_skia::Pixmap::new(
            size.width().ceil() as u32,
            size.height().ceil() as u32,
        )
        .expect("pixmap");
        resvg::render(
            &tree,
            resvg::tiny_skia::Transform::identity(),
            &mut pixmap.as_mut(),
        );
        let first = pixmap.pixels()[0];
        assert!(
            pixmap.pixels().iter().any(|p| *p != first),
            "pixmap is uniformly the background color — nothing was drawn"
        );
    }

    #[test]
    fn raster_scale_is_clamped_and_aspect_preserved() {
        let svg = build_svg("graph TD; A[Hello]-->B[World]", LIGHT).expect("svg render");
        // Absurd target width must not blow up: scale clamps at 4.0.
        let png = svg_to_png(&svg, 1_000_000).expect("png render");
        assert!(!png.is_empty());
        // Zero/negative target width falls back to scale 1.0.
        let png = svg_to_png(&svg, 0).expect("png render");
        assert!(!png.is_empty());
    }

}
