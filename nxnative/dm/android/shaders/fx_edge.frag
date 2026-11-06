#version 450
// Android port: Ultra and Extreme sharpening, into the swapchain. A picture rendered at 630p and shown on 2032 lines has edges several pixels wide, and FSR's own sharpening looks one pixel
// away, so it cannot reach them. This looks as far as an edge is wide, and keeps the result between the darkest and brightest values nearby, so an edge gets steeper with no halo round it
layout(set = 0, binding = 0) uniform sampler2D tex;
layout(push_constant) uniform P { vec2 texel; float radius; float amount; ivec2 origin; int linear; int pad; } pc;
layout(location = 0) out vec4 col;
void main() {
    vec2 p = (gl_FragCoord.xy - vec2(pc.origin)) * pc.texel, r = pc.radius * pc.texel, d = r * 0.7071;
    vec3 c = textureLod(tex, p, 0.0).rgb;
    vec3 a = textureLod(tex, p + vec2(r.x, 0.0), 0.0).rgb, b = textureLod(tex, p - vec2(r.x, 0.0), 0.0).rgb, e = textureLod(tex, p + vec2(0.0, r.y), 0.0).rgb, f = textureLod(tex, p - vec2(0.0, r.y), 0.0).rgb;
    vec3 g = textureLod(tex, p + d, 0.0).rgb, h = textureLod(tex, p - d, 0.0).rgb, i = textureLod(tex, p + vec2(d.x, -d.y), 0.0).rgb, j = textureLod(tex, p + vec2(-d.x, d.y), 0.0).rgb;
    vec3 lo = min(min(min(a, b), min(e, f)), min(min(g, h), min(min(i, j), c))), hi = max(max(max(a, b), max(e, f)), max(max(g, h), max(max(i, j), c)));
    vec3 blur = (a + b + e + f + g + h + i + j) * 0.125;
    float range = dot(hi - lo, vec3(0.299, 0.587, 0.114));   // flat areas stay as they are: there the only thing to sharpen is noise
    vec3 s = clamp(c + (c - blur) * pc.amount * smoothstep(0.015, 0.08, range), lo, hi);
    if (pc.linear != 0) s = mix(s / 12.92, pow((s + 0.055) / 1.055, vec3(2.4)), step(0.04045, s));
    col = vec4(s, 1.0);
}
