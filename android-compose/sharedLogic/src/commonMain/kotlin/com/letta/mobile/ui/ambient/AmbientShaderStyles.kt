package com.letta.mobile.ui.ambient


/**
 * The full shader source for [style]: the shared prelude (uniforms, palette, dither) and the
 * style's own `ambientColor`. Every style takes the same uniforms and keeps the same rules as the
 * default (AMBIENT_GLOW_SHADER_SOURCE): time rates are whole multiples of F so the phase wraps
 * without a seam, stream energy scales amplitude and never rate, and with the phase held and
 * agitation at zero (reduced motion) the frame is still.
 */
fun ambientGlowShaderSource(style: ThinkingGlowStyle): String = when (style) {
    ThinkingGlowStyle.Aurora -> AMBIENT_GLOW_SHADER_SOURCE
    ThinkingGlowStyle.Sparkle -> AMBIENT_SHADER_PRELUDE + SPARKLE_BODY
    ThinkingGlowStyle.Tide -> AMBIENT_SHADER_PRELUDE + TIDE_BODY
    ThinkingGlowStyle.Ribbons -> AMBIENT_SHADER_PRELUDE + RIBBONS_BODY
}

/**
 * Sparkle: a halftone lattice of four-pointed stars (rows offset by half a cell) over a faint
 * base glow. Stars grow and brighten toward the bottom edge and shrink to dots as they rise past
 * the band, each twinkling at its own whole-cycle rate while a slow swell rises through the
 * field. Colour runs from the tint toward its hue-pulled palette partner across the width.
 */
private const val SPARKLE_BODY: String =
    """
half4 ambientColor(float2 fragCoord) {
    float2 uv = fragCoord / max(uSize, float2(1.0, 1.0));
    float t = uTime;
    float h = max(uBandPeak - uBandTop, 0.05);
    // The stars reach a little past the top of the band, fading out on the way.
    float reach = smoothstep(uBandTop - 0.3 * h, uBandPeak, uv.y);

    float cell = clamp(uSize.x / 46.0, 9.0, 26.0);
    float2 g = fragCoord / cell;
    float row = floor(g.y);
    g.x += 0.5 * mod(row, 2.0);
    float2 id = float2(floor(g.x), row);
    float2 q = (fract(g) - 0.5) * 2.0;

    float seed = hash(id);
    float rate = F * (128.0 + 64.0 * floor(hash(id + float2(17.0, 31.0)) * 3.0));
    float twinkle = 0.55 + 0.45 * sin(TAU * (seed + rate * t));
    float swell = 0.75 + 0.25 * sin(TAU * (uv.y * 2.5 + uv.x * 0.6 + 48.0 * F * t));
    float lively = 0.9 + 0.1 * min(uAgitation, 2.0) + 0.15 * uStreamEnergy;
    float size = clamp(reach * reach * (0.3 + 0.7 * twinkle * swell) * lively, 0.0, 0.95);

    // A concave four-pointed star: |x|^0.7 + |y|^0.7 inside size^0.7, with a soft core.
    float star = pow(abs(q.x), 0.7) + pow(abs(q.y), 0.7) - pow(size, 0.7);
    float cover = 1.0 - smoothstep(-0.12, 0.08, star);
    float core = exp(-dot(q, q) / max(size * size * 0.08, 0.0005));
    float starA = (0.75 * cover + 0.25 * core) * (0.4 + 0.6 * twinkle) * reach * 0.55;

    float3 partner = palB(0.62 + uv.x * 0.45 + 4.0 * F * t);
    float3 starRgb = mix(uColor.rgb, partner, 0.15 + 0.25 * uv.x) * 1.45 + 0.04;
    float baseA = smoothstep(uBandTop, uBandPeak + 0.05, uv.y) * 0.1;
    float4 layered = glowOver(starRgb, starA, uColor.rgb, baseA);
    return glowFinish(layered.rgb, layered.a, fragCoord);
}
"""

/**
 * Tide: the calm one. A deep gradient rising from the bottom edge, its top edge rolling in two
 * slow, broad swells across the width, brighter toward the bottom and darker as it climbs, with
 * a gentle breath.
 */
private const val TIDE_BODY: String =
    """
half4 ambientColor(float2 fragCoord) {
    float2 uv = fragCoord / max(uSize, float2(1.0, 1.0));
    float t = uTime;
    float h = max(uBandPeak - uBandTop, 0.05);
    float swell = 0.3 * sin(TAU * (uv.x * 0.6 + 6.0 * F * t)) +
        0.2 * sin(TAU * (uv.x * 1.7 - 10.0 * F * t) + 1.3);
    swell *= h * 0.3 * (0.6 + 0.2 * min(uAgitation, 2.0));
    float rise = smoothstep(uBandTop - 0.8 * h, uBandPeak, uv.y + swell);
    float breath = 0.9 + 0.1 * sin(TAU * 12.0 * F * t) + 0.06 * uStreamEnergy;
    float3 deep = uColor.rgb * mix(0.65, 1.25, rise);
    float3 rgb = mix(deep, palB(uv.x * 0.3 + 4.0 * F * t), 0.12);
    return glowFinish(rgb, pow(rise, 1.6) * 0.2 * breath, fragCoord);
}
"""

/**
 * Ribbons: three thin ribbons of light stacked just above the composer, each waving at its own
 * slow rate with a brightness that travels along it, over a faint haze.
 */
private const val RIBBONS_BODY: String =
    """
half4 ambientColor(float2 fragCoord) {
    float2 uv = fragCoord / max(uSize, float2(1.0, 1.0));
    float t = uTime;
    float spread = clamp((uBandPeak - uBandTop) * 0.6, 0.12, 0.2);
    float amp = 0.7 + 0.15 * min(uAgitation, 2.0);
    float3 acc = float3(0.0);
    float lines = 0.0;
    for (int i = 0; i < 3; i++) {
        float fi = float(i);
        float y0 = uBandPeak - spread * (0.2 + 0.4 * fi);
        float wave = 0.022 * sin(TAU * (uv.x * (1.1 + 0.35 * fi) + F * (8.0 + 3.0 * fi) * t) + fi * 1.9) +
            0.01 * sin(TAU * (uv.x * (2.7 - 0.4 * fi) - F * (13.0 + 2.0 * fi) * t));
        float dy = (uv.y - y0 - wave * amp) / (0.01 + 0.004 * fi);
        float travel = 0.7 + 0.3 * sin(TAU * (uv.x * 1.5 - F * (40.0 + 8.0 * fi) * t) + fi);
        float line = exp(-dy * dy) * (1.0 - 0.25 * fi) * travel;
        acc += palB(0.15 * fi + uv.x * 0.3 + 5.0 * F * t) * line;
        lines += line;
    }
    float3 lineRgb = mix(uColor.rgb * 1.3, acc / max(lines, 0.001), 0.3);
    float lineA = clamp(lines, 0.0, 1.0) * (0.32 + 0.1 * uStreamEnergy);
    float hazeA = smoothstep(uBandTop, uBandPeak + 0.05, uv.y) * 0.07;
    float4 layered = glowOver(lineRgb, lineA, uColor.rgb, hazeA);
    return glowFinish(layered.rgb, layered.a, fragCoord);
}
"""
