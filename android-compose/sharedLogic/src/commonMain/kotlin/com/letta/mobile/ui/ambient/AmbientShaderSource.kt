package com.letta.mobile.ui.ambient

/**
 * The ambient agent-status glow shader, ONE source for both platforms:
 * Android compiles it as AGSL (`android.graphics.RuntimeShader`), desktop as
 * SkSL (`org.jetbrains.skia.RuntimeEffect`) — AGSL is SkSL-derived and this
 * source stays in the common subset. Sharing the string is what keeps the two
 * renderers pixel-equivalent instead of "similar".
 *
 * Uniform contract (all driven from [AmbientMotion] specs):
 * - uTime: speed-integrated phase in radians (NOT wall time — the renderer
 *   integrates dt * baseRate * speed so status speed changes glide)
 * - uAgitation: noise displacement multiplier
 * - uEnvelope: intensity envelope (bloom→settle for transient states)
 * - uColor: premultiplied-alpha-free RGBA tint
 */
const val AMBIENT_GLOW_SHADER_SOURCE: String =
    """
uniform float2 uSize;
uniform float uTime;
uniform float uAgitation;
uniform float uEnvelope;
uniform vec4 uColor;

float hash(float2 p) {
    return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453123);
}

float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(
        mix(hash(i + float2(0.0, 0.0)), hash(i + float2(1.0, 0.0)), u.x),
        mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x),
        u.y
    );
}

half4 main(float2 fragCoord) {
    float2 uv = fragCoord / max(uSize, float2(1.0, 1.0));
    float aspect = uSize.x / max(uSize.y, 1.0);
    float2 p = float2((uv.x - 0.50) * aspect, uv.y - 0.82);

    float breath = 0.5 + 0.5 * sin(uTime);
    float2 warp = float2(
        noise(uv * 3.1 + float2(uTime * 0.11, 0.0)),
        noise(uv * 3.7 + float2(0.0, -uTime * 0.07))
    );
    float2 driftUv = float2(uv.x * 2.2 + uTime * 0.08, uv.y * 1.6 - uTime * 0.05)
        + (warp - 0.5) * (0.9 * uAgitation);
    float drift = 0.65 * noise(driftUv) + 0.35 * noise(driftUv * 2.4 + warp * 1.7);
    float radius = mix(0.42, 0.62, breath) + (drift - 0.5) * (0.08 + 0.06 * uAgitation);
    float glow = 1.0 - smoothstep(0.0, radius, length(p));

    float upperFade = 1.0 - smoothstep(0.0, 0.78, uv.y);
    float lowerAnchor = smoothstep(0.25, 1.0, uv.y);
    float alpha = glow * upperFade * lowerAnchor * 0.18 * uEnvelope * uColor.a;
    return half4(uColor.rgb, alpha);
}
"""
