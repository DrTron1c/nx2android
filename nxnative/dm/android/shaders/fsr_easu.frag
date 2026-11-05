#version 450
#extension GL_GOOGLE_include_directive : require
// AMD FidelityFX Super Resolution 1 (third_party/fsr1, MIT): the upscale. The picture's bytes go in as they are: FSR expects a perceptual space
#define A_GPU 1
#define A_GLSL 1
#include "ffx_a.h"
layout(set = 0, binding = 0) uniform sampler2D tex;
layout(push_constant) uniform P { uvec4 con0, con1, con2, con3; vec4 flip; } pc;
layout(location = 0) out vec4 col;
#define FSR_EASU_F 1
AF4 FsrEasuRF(AF2 p) { return textureGather(tex, p, 0); }
AF4 FsrEasuGF(AF2 p) { return textureGather(tex, p, 1); }
AF4 FsrEasuBF(AF2 p) { return textureGather(tex, p, 2); }
#include "ffx_fsr1.h"
void main() {
    uvec2 ip = uvec2(gl_FragCoord.xy); if (pc.flip.x > 0.0) ip.y = uint(pc.flip.y) - 1u - ip.y;
    AF3 c; FsrEasuF(c, ip, pc.con0, pc.con1, pc.con2, pc.con3); col = vec4(c, 1.0);
}
