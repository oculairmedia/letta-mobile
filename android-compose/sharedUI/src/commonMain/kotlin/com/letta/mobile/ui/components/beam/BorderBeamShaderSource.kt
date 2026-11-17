package com.letta.mobile.ui.components.beam

import androidx.compose.ui.graphics.drawscope.DrawScope

/**
 * The beam as one shader pass (crisp edge, tight halo and soft inner glow) over a rounded rectangle.
 * The same source compiles as AGSL on Android and as SkSL on desktop and web; it returns a
 * premultiplied colour, which both expect from this source. All lengths are in dp so the beam looks
 * the same at every density. Each corner has its own circular radius.
 *
 * Ported from border-beam-android by ismnoiet (MIT, see sharedUI/THIRD_PARTY_LICENSES), itself a port
 * of the iOS Metal shader. Changes: per-corner radii, the fade and intensity are one `intensity`
 * uniform, and the sweep always runs along the bottom edge.
 */
internal const val BORDER_BEAM_SHADER = """
uniform float2 size;          // draw area in dp
uniform float density;        // px per dp
uniform float4 radii;         // dp corner radii (top-right, bottom-right, bottom-left, top-left), each clamped to half the shorter side
uniform float phase;          // 0...1, loops
uniform float direction;      // +1 clockwise (left to right along the top), -1 counter-clockwise
uniform float mode;           // 0 = orbit the border, 1 = sweep along the bottom edge
uniform float arc;            // orbit: the comet's length as a fraction of the lap
uniform float widthScale;     // relative thickness of the crisp edge
uniform float glow;           // soft inner glow reach in dp
uniform float intensity;      // overall brightness and fade, 0...1
uniform float count;          // colours in the palette, 1...8
uniform float4 colors[8];     // palette, tail to head

const float PI = 3.14159265;

// Corner radii are (top-right, bottom-right, bottom-left, top-left) in dp; y grows downward.
// The radius of the corner in p's quadrant.
float cornerFor(float2 p, float4 r) {
    return p.x >= 0.0 ? (p.y >= 0.0 ? r.y : r.x) : (p.y >= 0.0 ? r.z : r.w);
}

// Signed distance to a rounded box centred at the origin (negative inside).
float sdRoundedBox(float2 p, float2 hs, float4 r) {
    float rc = cornerFor(p, r);
    float2 q = abs(p) - (hs - rc);
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - rc;
}

// The border's length: four straight edges shortened by their corners, plus four quarter arcs.
float perimeterLength(float2 hs, float4 r) {
    float sum = r.x + r.y + r.z + r.w;
    return max(4.0 * (hs.x + hs.y) - 2.0 * sum + 0.5 * PI * sum, 0.001);
}

// Position along the border, clockwise from the right edge's midpoint, as 0...1. Distance ALONG
// the edge (not the angle around the centre) keeps the beam's ends perpendicular to the border.
// The border runs: right edge (lower half), bottom-right arc, bottom edge, bottom-left arc, left
// edge, top-left arc, top edge, top-right arc, right edge (upper half).
float perimeterParam(float2 p, float2 hs, float4 r) {
    float L = perimeterLength(hs, r);
    float baseBR = hs.y - r.y;
    float baseB = baseBR + 0.5 * PI * r.y;
    float baseBL = baseB + 2.0 * hs.x - r.y - r.z;
    float baseL = baseBL + 0.5 * PI * r.z;
    float baseTL = baseL + 2.0 * hs.y - r.z - r.w;
    float baseT = baseTL + 0.5 * PI * r.w;
    float baseTR = baseT + 2.0 * hs.x - r.w - r.x;
    float rc = cornerFor(p, r);
    float2 ap = abs(p);
    float s = 0.0;
    if (ap.x > hs.x - rc && ap.y > hs.y - rc) {
        float2 v = ap - (hs - rc);
        float ang = atan(v.y, v.x);
        if (p.x >= 0.0 && p.y >= 0.0) {
            s = baseBR + rc * ang;
        } else if (p.x < 0.0 && p.y >= 0.0) {
            s = baseBL + rc * (PI * 0.5 - ang);
        } else if (p.x < 0.0) {
            s = baseTL + rc * ang;
        } else {
            s = baseTR + rc * (PI * 0.5 - ang);
        }
    } else if ((hs.x - ap.x) < (hs.y - ap.y)) {
        if (p.x >= 0.0) {
            s = p.y >= 0.0 ? p.y : L + p.y;
        } else {
            s = baseL + (hs.y - r.z - p.y);
        }
    } else {
        if (p.y >= 0.0) {
            s = baseB + (hs.x - r.y - p.x);
        } else {
            s = baseT + (p.x + hs.x - r.w);
        }
    }
    return fract(s / L);
}

// Linear blend across the palette at t in 0...1, with constant array indices only.
float4 samplePalette(float t) {
    int n = int(count);
    float4 result = colors[0];
    if (n <= 1) {
        return result;
    }
    float x = clamp(t, 0.0, 1.0) * float(n - 1);
    for (int i = 0; i < 7; i++) {
        if (i + 1 < n) {
            result = mix(result, colors[i + 1], clamp(x - float(i), 0.0, 1.0));
        }
    }
    return result;
}

// The point on the border at distance s (dp), clockwise from the right edge's midpoint.
float2 boundaryPoint(float s, float2 hs, float4 r) {
    float L = perimeterLength(hs, r);
    s = s - L * floor(s / L);
    float l = hs.y - r.y;
    if (s < l) { return float2(hs.x, s); }
    s -= l;
    l = 0.5 * PI * r.y;
    if (s < l) { float a = s / r.y; return float2(hs.x - r.y, hs.y - r.y) + r.y * float2(cos(a), sin(a)); }
    s -= l;
    l = 2.0 * hs.x - r.y - r.z;
    if (s < l) { return float2(hs.x - r.y - s, hs.y); }
    s -= l;
    l = 0.5 * PI * r.z;
    if (s < l) { float a = PI * 0.5 + s / r.z; return float2(-hs.x + r.z, hs.y - r.z) + r.z * float2(cos(a), sin(a)); }
    s -= l;
    l = 2.0 * hs.y - r.z - r.w;
    if (s < l) { return float2(-hs.x, hs.y - r.z - s); }
    s -= l;
    l = 0.5 * PI * r.w;
    if (s < l) { float a = PI + s / r.w; return float2(-hs.x + r.w, -hs.y + r.w) + r.w * float2(cos(a), sin(a)); }
    s -= l;
    l = 2.0 * hs.x - r.w - r.x;
    if (s < l) { return float2(-hs.x + r.w + s, -hs.y); }
    s -= l;
    l = 0.5 * PI * r.x;
    if (s < l) { float a = PI * 1.5 + s / r.x; return float2(hs.x - r.x, -hs.y + r.x) + r.x * float2(cos(a), sin(a)); }
    s -= l;
    return float2(hs.x, -hs.y + r.x + s);
}

// The orbiting beam's soft glow: a chain of Gaussian blobs along its path on the border, so corners
// come out round on any shape. Premultiplied rgb + weight, peaking near 1 on the border.
float4 orbitGlowBlobs(float2 p, float dist, float2 hs, float4 r, float sigmaBase) {
    float L = perimeterLength(hs, r);
    float delta = arc * L / 24.0;
    float sigma = max(sigmaBase, 0.8 * delta);
    if (dist > 3.0 * sigma) { return float4(0.0); }
    float inv2s2 = 0.5 / (sigma * sigma);
    float norm = delta / (sigma * 2.50663);
    float start = 1.0 - arc;
    float4 acc = float4(0.0);
    for (int k = 0; k < 24; k++) {
        float t = (float(k) + 0.5) / 24.0;
        float sp = fract(start + t * arc + direction * phase);
        float2 pt = boundaryPoint(sp * L, hs, r);
        float2 dv = p - pt;
        float g = exp(-dot(dv, dv) * inv2s2);
        if (g < 0.003) { continue; }
        float bell = pow(max(sin(PI * t), 0.0), 1.6);
        float4 c4 = samplePalette(t);
        float v = g * norm * bell * clamp(c4.a, 0.0, 1.0);
        acc += float4(c4.rgb * v, v);
    }
    return acc;
}

half4 main(float2 fragCoord) {
    float2 position = fragCoord / density;
    float2 hs = size * 0.5;
    float4 r = min(radii, float4(min(hs.x, hs.y)));
    float2 p = position - hs;
    float dist = -sdRoundedBox(p, hs, r);
    if (dist < 0.0) { return half4(0.0); }

    float4 acc = float4(0.0);
    if (mode < 0.5) {
        float a = perimeterParam(p, hs, r);
        float tt = (fract(a - direction * phase) - (1.0 - arc)) / arc;
        if (tt >= 0.0) {
            float profile = pow(max(sin(PI * clamp(tt, 0.0, 1.0)), 0.0), 1.2);
            float crisp = 1.0 - smoothstep(0.5 * widthScale, 2.25 * widthScale, dist);
            float halo = 0.6 * exp(-dist / max(glow * 0.1, 0.6));
            float4 c4 = samplePalette(tt);
            float w = (crisp + halo) * profile * clamp(c4.a, 0.0, 1.0);
            acc += float4(c4.rgb * w, w);
        }
        acc += 0.55 * orbitGlowBlobs(p, dist, hs, r, max(glow, 0.001) * 0.7);
    } else {
        float u = position.x / size.x;
        float edgeDist = size.y - position.y;
        float len = 0.28;
        float tri = phase < 0.5 ? phase * 2.0 : (1.0 - phase) * 2.0;
        float eased = tri * tri * (3.0 - 2.0 * tri);
        float c = len * 0.5 + (1.0 - len) * eased;
        float k = (u - (c - len * 0.5)) / len;
        if (k < 0.0 || k > 1.0) { return half4(0.0); }
        float tp = 1.0 - abs(2.0 * k - 1.0);
        float profile = tp * tp * (3.0 - 2.0 * tp);
        float4 c4 = samplePalette(k);
        float crisp = 1.0 - smoothstep(0.5 * widthScale, 2.25 * widthScale, edgeDist);
        float halo = 0.8 * exp(-edgeDist / max(glow * 0.2, 0.5));
        float wide = 0.6 * exp(-pow(edgeDist / max(glow, 0.001), 1.4));
        float w = ((crisp + halo) * profile + wide * profile) * clamp(c4.a, 0.0, 1.0);
        acc += float4(c4.rgb * w, w);
    }

    float total = acc.a;
    if (total <= 0.0001) { return half4(0.0); }
    float3 rgb = acc.rgb / total;
    float alpha = clamp(total * intensity, 0.0, 1.0);
    return half4(half3(rgb) * half(alpha), half(alpha));
}
"""

/** The shader's scalar uniforms for [frame] drawn over this scope, in the order the platforms set them. */
internal class BeamUniforms(scope: DrawScope, frame: BeamFrame) {
    val density: Float = scope.density
    val width: Float = scope.size.width / density
    val height: Float = scope.size.height / density
    val radii: FloatArray = FloatArray(CORNERS) { frame.cornersPx[it] / density }
    val phase: Float = frame.phase
    val direction: Float = frame.direction.sign
    val mode: Float = if (frame.style == LettaBeamStyle.Sweep) 1f else 0f
    val arc: Float = frame.style.arc
    val glow: Float = with(scope) { frame.style.glowReach.toPx() } / density * frame.widthScale
    val widthScale: Float = frame.widthScale
    val intensity: Float = frame.alpha.coerceIn(0f, 1f)
    val count: Float = frame.palette.count.toFloat()
    val colors: FloatArray = frame.palette.packed
}
