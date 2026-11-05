#version 450
// Frame generation: how far each block moved between two frames, found on small copies of them by a shrinking search. Written as a uv offset from the newer frame to the older
layout(set = 0, binding = 0) uniform sampler2D older;
layout(set = 0, binding = 1) uniform sampler2D newer;
layout(push_constant) uniform P { vec4 texel; } pc;   // xy: one texel of the small copies
layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 o;
float luma(vec3 c) { return dot(c, vec3(0.3, 0.59, 0.11)); }
float cost(vec2 m) {
    float s = 0.0;
    for (int y = -1; y <= 1; y++) for (int x = -1; x <= 1; x++) { vec2 p = uv + vec2(x, y) * pc.texel.xy * 2.0; s += abs(luma(texture(newer, p).rgb) - luma(texture(older, p + m).rgb)); }
    return s;
}
void main() {
    const vec2 dirs[8] = vec2[8](vec2(1, 0), vec2(-1, 0), vec2(0, 1), vec2(0, -1), vec2(1, 1), vec2(-1, 1), vec2(1, -1), vec2(-1, -1));
    vec2 best = vec2(0.0); float bc = cost(best) * 0.92;   // standing still wins a tie: flat areas stay put
    for (float step_ = 8.0; step_ >= 1.0; step_ /= 2.0) {
        vec2 c = best;
        for (int i = 0; i < 8; i++) { vec2 m = c + dirs[i] * step_ * pc.texel.xy; float k = cost(m); if (k < bc) { bc = k; best = m; } }
    }
    o = vec4(best, 0.0, 1.0);
}
