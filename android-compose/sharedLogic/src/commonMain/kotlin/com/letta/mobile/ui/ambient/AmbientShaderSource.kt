package com.letta.mobile.ui.ambient

/**
 * The ambient agent-status glow shader — "mesh-flow", chosen via lookdev
 * (desktop/lookdev-shaders carries the full candidate family): five color
 * fields stretched into wide ellipses shearing slowly sideways, so the glow
 * reads as a horizontal current under the conversation. Color comes from a curated
 * indigo→teal→gold cosine palette whose hues are rotated toward the tint (uPalettePull)
 * so the field speaks the theme's colour language rather than fighting it, and is then
 * mixed with the tint itself.
 *
 * ONE source for both platforms: Android compiles it as AGSL
 * (`android.graphics.RuntimeShader`), desktop as SkSL
 * (`org.jetbrains.skia.RuntimeEffect`) — this stays in the common subset
 * (constant-bound loops only; Skia runtime effects reject dynamic loops).
 *
 * Uniform contract (all driven from [AmbientMotion] specs):
 * - uTime: speed-integrated phase in TURNS (NOT wall time — the renderer integrates
 *   dt * speed so status speed changes glide, and wraps at AmbientMotion.PHASE_WRAP_TURNS,
 *   which every frequency here is commensurate with)
 * - uAgitation: energy multiplier on the field intensity
 * - uEnvelope: intensity envelope (bloom→settle for transient states)
 * - uStreamEnergy: smoothed visible assistant-delta activity in [0,1]; amplitude only
 * - uPalettePull: how far the palette's hues rotate toward the tint, 0..1
 * - uColor: RGBA tint; alpha scales the whole effect
 *
 * Output of [ambientColor] is UNPREMULTIPLIED; the two runtimes disagree on
 * what a shader must return, so each platform appends its own one-line main
 * (SkSL has no preprocessor to switch on).
 */
const val AMBIENT_GLOW_SHADER_SOURCE: String =
    """
uniform float2 uSize;
uniform float uTime;
uniform float uAgitation;
uniform float uEnvelope;
uniform float uStreamEnergy;
uniform float uPalettePull;
uniform vec4 uColor;

// Every frequency below is an integer multiple of F, so the whole field is periodic
// in uTime with period 1/F = 1024 and the host can wrap its phase there with no seam.
// That wrap is what keeps uTime small: an ever-growing float loses resolution until a
// frame's advance no longer changes it, which is the judder that appeared only after
// hours of continuous animation. See AmbientMotion.PHASE_WRAP_TURNS.
const float F = 1.0 / 1024.0;
// Where the glow starts fading in, as a fraction of canvas height. The band is meant to
// be an accent under the composer, not a wash across the lower third, so this is the one
// number to move when it reads too tall.
const float BAND_TOP = 0.80;
// Where the band reaches full strength. It used to climb all the way to 0.98, which is
// BEHIND the composer: shortening the band left only the faint head of that ramp on
// screen, so the colour disappeared. Peaking just above the composer puts the strong
// part where it can actually be seen.
const float BAND_PEAK = 0.90;
/** Overall strength of the glow. The one knob for "too intense" / "too faint". */
const float BAND_OPACITY = 0.14;
const float TAU = 6.28318;

float hash(float2 p) {
    return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453123);
}

// Indigo -> teal -> gold cosine palette (IQ-style).
float3 palRaw(float t) {
    return float3(0.42, 0.36, 0.38)
         + float3(0.85, 0.72, 0.62) * cos(TAU * (float3(0.9, 1.0, 1.0) * t + float3(0.55, 0.30, 0.05)));
}

// Rotate a colour's chroma toward the tint's hue while keeping its own luminance and
// its own chroma magnitude. The curated palette then speaks the theme's hue language
// instead of fighting it, without flattening the field into one colour. YIQ keeps this
// to a couple of dot products and a normalize - no per-pixel atan2, no OKLab round trip.
float3 towardTintHue(float3 c, float3 tint, float pull) {
    const float3 Y = float3(0.299, 0.587, 0.114);
    const float3 I = float3(0.5959, -0.2746, -0.3213);
    const float3 Q = float3(0.2115, -0.5227, 0.3112);
    float2 chroma = float2(dot(c, I), dot(c, Q));
    float2 target = float2(dot(tint, I), dot(tint, Q));
    float magnitude = length(chroma);
    float targetLength = length(target);
    if (magnitude < 0.0001 || targetLength < 0.0001) return c;
    // Blending two unit vectors collapses to zero length when they point opposite ways,
    // and normalizing that amplifies float noise into an arbitrary hue. The palette's
    // gold IS the complement of an indigo tint, so that was not a corner case here: it
    // washed the steady glow to mauve. Snap to the target hue instead of dividing by
    // nothing.
    float2 blended = mix(chroma / magnitude, target / targetLength, pull);
    float blendedLength = length(blended);
    float2 direction = blendedLength > 0.001 ? blended / blendedLength : target / targetLength;
    float2 rotated = direction * magnitude;
    float luma = dot(c, Y);
    return float3(
        luma + 0.956 * rotated.x + 0.619 * rotated.y,
        luma - 0.272 * rotated.x - 0.647 * rotated.y,
        luma - 1.106 * rotated.x + 1.703 * rotated.y
    );
}

float3 palB(float t) {
    return towardTintHue(clamp(palRaw(t), 0.0, 1.0), uColor.rgb, uPalettePull);
}

// +-0.5/255-scale dither: faint gradients over the near-black background
// posterize into visible contour bands at 8 bits without it.
float dither(float2 fragCoord) {
    return (hash(fragCoord * 0.7131) - 0.5) * (1.6 / 255.0);
}

half4 ambientColor(float2 fragCoord) {
    float2 uv = fragCoord / max(uSize, float2(1.0, 1.0));
    float aspect = uSize.x / max(uSize.y, 1.0);
    float2 p = float2((uv.x - 0.5) * aspect, uv.y);
    // Stream energy scales amplitude only, never rate: a live signal on the rate both
    // pops the motion when it moves and makes the period unknowable, so no wrap could
    // be seamless. The five drift rates below are 5F, 6F, 8F, 9F, 11F.
    float t = uTime;

    float3 acc = float3(0.0);
    float wsum = 0.0;
    // Related fields share the same current but vary phase, speed, width, and
    // amplitude slightly. Their family resemblance is intentional: one living
    // force, not five independent particles.
    for (int i = 0; i < 5; i++) {
        float fi = float(i);
        float drift = F * (5.0 + fi + step(1.5, fi) + step(3.5, fi));
        float2 c = float2(
            (fract(0.19 * fi + t * drift) * 1.4 - 0.7) * aspect,
            0.78 + 0.06 * fi * (1.0 - 0.12 * fi) + 0.05 * sin(TAU * 15.0 * F * t + fi * 2.0)
        );
        float2 d = p - c;
        d.x *= 0.38;
        d.y *= 1.9 - 0.6 * sin(TAU * 8.0 * F * t + fi);
        float width = 0.027 + 0.002 * sin(TAU * 6.0 * F * t + fi * 1.7);
        float w = exp(-dot(d, d) / width) * (0.94 + 0.06 * sin(TAU * 15.0 * F * t + fi * 2.1));
        acc += palB(fi * 0.19 + uv.x * 0.25 + 5.0 * F * t) * w;
        wsum += w;
    }

    // Broad left-to-right scan integrated by the host. It gently lifts the
    // existing field rather than drawing a stripe. Stream energy adds only a
    // small vividness/height response, preserving the calm baseline.
    float scanPhase = fract(80.0 * F * t);
    float scanCenter = mix(-0.22, 1.22, scanPhase);
    float scan = 1.0 - smoothstep(0.16, 0.48, abs(uv.x - scanCenter));
    float scanWeight = scan * (0.025 + 0.050 * uStreamEnergy);
    acc += palB(uv.x * 0.25 + 5.0 * F * t) * scanWeight;
    wsum += scanWeight;
    // The field now carries the tint's hue (towardTintHue), so the flat mix toward the
    // tint no longer has to do that job alone and can hold a higher ratio without
    // washing the field out.
    float3 fieldColor = acc / max(wsum, 0.001);
    // The field is a LIGHT field, not a palette: its job is where the glow is bright, not
    // what colour it is. Taking its luminance and letting the tint own the hue is what
    // keeps the chroma the theme asked for; mixing its colour in at any weight pulls the
    // result toward the palette's pale base and greys it out.
    float fieldLuma = dot(fieldColor, float3(0.299, 0.587, 0.114));
    float3 lit = uColor.rgb * (0.45 + 0.85 * fieldLuma);
    // A small seasoning of the (hue-pulled) field keeps the drift visible as colour, not
    // just as brightness. The scan lifts it slightly where the sweep passes.
    float season = 0.15 + scan * (0.02 + 0.06 * uStreamEnergy);
    float3 rgb = mix(lit, fieldColor, season);

    float energy = clamp(wsum * (0.8 + 0.2 * uAgitation), 0.0, 1.4);
    // Alpha curve narrowed to the BAND_TOP..BAND_PEAK strip so the visible glow
    // occupies less vertical real estate — a thin glow under the composer
    // instead of a broad mid-screen band.
    // Shortening the band also dims it, because the ramp now has less height to climb.
    // The amplitude comes up to keep the same presence in less space.
    float aRaw = energy * smoothstep(BAND_TOP, BAND_PEAK, uv.y) *
        (BAND_OPACITY + scan * 0.020 * uStreamEnergy);
    float alpha = clamp(aRaw * uEnvelope * uColor.a, 0.0, 0.78);
    alpha = max(alpha + dither(fragCoord), 0.0);
    return half4(clamp(rgb, 0.0, 1.0), alpha);
}
"""

/**
 * Platform `main` suffixes. [ambientColor] returns an UNPREMULTIPLIED color;
 * the two runtimes disagree about what a shader must output:
 * - Android AGSL expects unpremultiplied → identity main.
 * - Skia RuntimeEffect expects PREMULTIPLIED → without multiplying rgb by
 *   alpha, a faint glow renders as a full-opacity color flood.
 */
const val AMBIENT_GLOW_MAIN_UNPREMULTIPLIED: String =
    "\nhalf4 main(float2 fragCoord) { return ambientColor(fragCoord); }\n"

const val AMBIENT_GLOW_MAIN_PREMULTIPLIED: String =
    "\nhalf4 main(float2 fragCoord) { half4 c = ambientColor(fragCoord); return half4(c.rgb * c.a, c.a); }\n"
