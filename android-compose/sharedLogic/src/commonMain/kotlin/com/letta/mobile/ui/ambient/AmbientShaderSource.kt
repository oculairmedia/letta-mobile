package com.letta.mobile.ui.ambient

/**
 * The ambient agent-status glow shader — "mesh-flow", chosen via lookdev
 * (desktop/lookdev-shaders carries the full candidate family): five color
 * fields stretched into wide ellipses shearing slowly sideways, so the glow
 * reads as a horizontal current under the conversation. Color comes from a
 * curated indigo→teal→gold cosine palette, leashed 22% toward the tint so the
 * status/identity color still speaks.
 *
 * ONE source for both platforms: Android compiles it as AGSL
 * (`android.graphics.RuntimeShader`), desktop as SkSL
 * (`org.jetbrains.skia.RuntimeEffect`) — this stays in the common subset
 * (constant-bound loops only; Skia runtime effects reject dynamic loops).
 *
 * Uniform contract (all driven from [AmbientMotion] specs):
 * - uTime: speed-integrated phase in radians (NOT wall time — the renderer
 *   integrates dt * baseRate * speed so status speed changes glide)
 * - uAgitation: energy multiplier on the field intensity
 * - uEnvelope: intensity envelope (bloom→settle for transient states)
 * - uStreamEnergy: smoothed visible assistant-delta activity in [0,1]
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
uniform vec4 uColor;

float hash(float2 p) {
    return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453123);
}

// Indigo -> teal -> gold cosine palette (IQ-style).
// Amplitudes widened (letta-mobile-shader-saturation-2026-08-06) so the
// palette covers more of the color gamut; mid-amplitude terms give the
// glow a vivid palette instead of the previous mid-gray hover. Second
// pass (letta-mobile-shader-tint-2026-08-06) bumped 0.68/0.58/0.50 ->
// 0.85/0.72/0.62 because the narrow bottom band drops the palette at
// low-energy pixels, leaving the tint mix reading as gray instead of teal.
float3 palB(float t) {
    return float3(0.42, 0.36, 0.38)
         + float3(0.85, 0.72, 0.62) * cos(6.28318 * (float3(0.9, 1.0, 1.0) * t + float3(0.55, 0.30, 0.05)));
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
    float t = uTime * (0.09 + 0.009 * uStreamEnergy);

    float3 acc = float3(0.0);
    float wsum = 0.0;
    // Related fields share the same current but vary phase, speed, width, and
    // amplitude slightly. Their family resemblance is intentional: one living
    // force, not five independent particles.
    for (int i = 0; i < 5; i++) {
        float fi = float(i);
        float2 c = float2(
            (fract(0.19 * fi + t * (0.05 + 0.015 * fi)) * 1.4 - 0.7) * aspect,
            0.78 + 0.06 * fi * (1.0 - 0.12 * fi) + 0.05 * sin(t + fi * 2.0)
        );
        float2 d = p - c;
        d.x *= 0.38;
        d.y *= 1.9 - 0.6 * sin(t * 0.5 + fi);
        float width = 0.027 + 0.002 * sin(fi * 1.7 + t * 0.4);
        float w = exp(-dot(d, d) / width) * (0.94 + 0.06 * sin(fi * 2.1 + t));
        acc += palB(fi * 0.19 + uv.x * 0.25 + t * 0.05) * w;
        wsum += w;
    }

    // Broad left-to-right scan integrated by the host. It gently lifts the
    // existing field rather than drawing a stripe. Stream energy adds only a
    // small vividness/height response, preserving the calm baseline.
    float scanPhase = fract(uTime * 0.075 * (1.0 + 0.10 * uStreamEnergy));
    float scanCenter = mix(-0.22, 1.22, scanPhase);
    float scan = 1.0 - smoothstep(0.16, 0.48, abs(uv.x - scanCenter));
    float scanWeight = scan * (0.025 + 0.050 * uStreamEnergy);
    acc += palB(uv.x * 0.25 + t * 0.05) * scanWeight;
    wsum += scanWeight;
    // Mix ratio lifted 0.22 -> 0.40 (letta-mobile-shader-saturation), then
    // 0.40 -> 0.58 (letta-mobile-shader-tint-2026-08-06) so the green/teal
    // tint dominates the palette at low-energy pixels in the narrow band.
    float3 fieldColor = acc / max(wsum, 0.001);
    float3 rgb = mix(fieldColor, uColor.rgb, 0.58 - scan * (0.02 + 0.06 * uStreamEnergy));

    float energy = clamp(wsum * (0.8 + 0.2 * uAgitation), 0.0, 1.4);
    // Alpha curve narrowed to bottom band (0.72..0.98) so the visible glow
    // occupies less vertical real estate — a thin glow under the composer
    // instead of a broad mid-screen band. Amplitude reduced 0.55 -> 0.34
    // for a more subtle, less dominant presence. Alpha cap 0.98 -> 0.78.
    float aRaw = energy * smoothstep(0.72, 0.98, uv.y) * (0.34 + scan * 0.020 * uStreamEnergy);
    float alpha = clamp(aRaw * uEnvelope * uColor.a, 0.0, 0.78);
    alpha = max(alpha + dither(fragCoord), 0.0);
    return half4(rgb, alpha);
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
