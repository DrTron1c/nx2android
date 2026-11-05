#version 450
#extension GL_GOOGLE_include_directive : require
// AMD FidelityFX Super Resolution 1 (third_party/fsr1, MIT): the sharpening, into the swapchain. An sRGB swapchain encodes what is written, so it gets the picture decoded
#define A_GPU 1
#define A_GLSL 1
#include "ffx_a.h"
layout(set = 0, binding = 0) uniform sampler2D tex;
layout(push_constant) uniform P { uvec4 con; ivec2 origin; int linear; int pad; } pc;
layout(location = 0) out vec4 col;
#define FSR_RCAS_F 1
AF4 FsrRcasLoadF(ASU2 p) { return texelFetch(tex, ivec2(p), 0); }
void FsrRcasInputF(inout AF1 r, inout AF1 g, inout AF1 b) {}
#include "ffx_fsr1.h"
void main() {
    AF1 r, g, b; FsrRcasF(r, g, b, uvec2(ivec2(gl_FragCoord.xy) - pc.origin), pc.con);
    vec3 c = clamp(vec3(r, g, b), 0.0, 1.0);
    if (pc.linear != 0) c = mix(c / 12.92, pow((c + 0.055) / 1.055, vec3(2.4)), step(0.04045, c));
    col = vec4(c, 1.0);
}
