#version 450
// Frame generation: the frame half way between two, each moved half way along the flow. Where they still disagree (something appeared or left) the newer one is taken
layout(set = 0, binding = 0) uniform sampler2D older;
layout(set = 0, binding = 1) uniform sampler2D newer;
layout(set = 0, binding = 2) uniform sampler2D flow;
layout(location = 0) in vec2 uv;
layout(location = 0) out vec4 o;
void main() {
    vec2 m = texture(flow, uv).xy;
    vec3 a = texture(older, uv + 0.5 * m).rgb, b = texture(newer, uv - 0.5 * m).rgb;
    float d = length(a - b);
    o = vec4(mix(0.5 * (a + b), b, smoothstep(0.12, 0.35, d)), 1.0);
}
