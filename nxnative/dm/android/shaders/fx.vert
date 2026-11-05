#version 450
// Android port: a triangle over the target, for the picture's own passes (gfx_vk_fx.h)
layout(location = 0) out vec2 uv;
void main() { uv = vec2(gl_VertexIndex & 2, (gl_VertexIndex << 1) & 2); gl_Position = vec4(uv * 2.0 - 1.0, 0.0, 1.0); }
