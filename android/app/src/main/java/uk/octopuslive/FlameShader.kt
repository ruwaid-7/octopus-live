package uk.octopuslive

import android.graphics.RuntimeShader
import androidx.annotation.RequiresApi

/**
 * Fire for the gas flame (Android 13+): noise scrolling upward, multiplied by a height falloff
 * that's tallest in the middle, then a colour ramp (orange edges -> amber -> pale yellow core).
 * The standard real-time fire technique, sized for a small icon.
 */
@RequiresApi(33)
class FlameShader {
    val shader = RuntimeShader(SRC)

    /** [ox], [oy]: top-left of the 24-unit flame box in pixels; [size]: its width in pixels. */
    fun update(ox: Float, oy: Float, size: Float, time: Float, hot: Int, mid: Int, edge: Int) {
        shader.setFloatUniform("origin", ox, oy)
        shader.setFloatUniform("size", size)
        shader.setFloatUniform("iTime", time)
        shader.setColorUniform("hot", hot)
        shader.setColorUniform("mid", mid)
        shader.setColorUniform("edge", edge)
    }

    companion object {
        private const val SRC = """
uniform float2 origin;
uniform float size;
uniform float iTime;
layout(color) uniform half4 hot;
layout(color) uniform half4 mid;
layout(color) uniform half4 edge;

float hash(float2 p) { return fract(sin(p.x * 127.1 + p.y * 311.7) * 43758.5453); }

float noise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float2 u = f * f * (3.0 - 2.0 * f);
    float a = hash(i);
    float b = hash(i + float2(1.0, 0.0));
    float c = hash(i + float2(0.0, 1.0));
    float d = hash(i + float2(1.0, 1.0));
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

float fbm(float2 p) {
    float v = 0.0;
    float a = 0.5;
    for (int k = 0; k < 4; k++) {
        v += a * noise(p);
        p = p * 2.03 + float2(1.7, 9.2);
        a *= 0.5;
    }
    return v;
}

half4 main(float2 fc) {
    float2 uv = (fc - origin) / size;
    float x = uv.x - 0.5;
    float y = 1.0 - uv.y;
    float fy = clamp((y - 0.10) / 0.80, 0.0, 1.0);
    // The whole flame sways a little
    float sway = 0.05 * sin(iTime * 2.1) + 0.03 * sin(iTime * 5.3);
    float xs = x + sway * fy;
    // Tall in the middle, short at the sides, height flickering around the middle of the outline
    float h = 0.66 * (1.0 - pow(clamp(abs(xs) * 2.3, 0.0, 1.0), 1.6)) + 0.04 * sin(iTime * 7.7);
    // Noise stretched upward and scrolling up: licking tongues
    float n = 0.6 * fbm(float2(xs * 7.0, fy * 2.2 - iTime * 2.2))
            + 0.4 * fbm(float2(xs * 14.0 + 5.0, fy * 4.5 - iTime * 3.6));
    float f = 1.0 - fy / max(h, 0.05) - (n - 0.45) * (0.35 + fy * 1.6);
    float alpha = clamp(f / 0.08, 0.0, 1.0);
    float k1 = clamp((f - 0.12) / 0.25, 0.0, 1.0);
    float k2 = clamp((f - 0.42) / 0.25, 0.0, 1.0);
    half4 col = mix(mix(edge, mid, k1), hot, k2);
    return half4(col.rgb * alpha, alpha);
}
"""
    }
}
