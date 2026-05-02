#version 150

// Standard MC vertex shader for a textured + per-vertex-colored quad.
// Inputs match the POSITION_COLOR_TEX vertex format we declare on the
// Kotlin side (TerminalRenderer's CRT_FX_RENDER_TYPE).
//
// Per-vertex Color carries the per-block CRT FX params packed by the
// renderer:
//   .rgb = phosphor color × brightness scalar (final RGB tint)
//   .a   = scanline strength (0..1, where 0 = no scanlines, 1 = max
//          dimming on between-rows)
//
// The fragment shader reads these to apply phosphor coloring and
// scanline darkening. Encoding the params in vertex attrs (rather
// than uniforms) lets MC's MultiBufferSource batch quads from
// multiple terminal block entities into a single draw call without
// us flushing between blocks.

in vec3 Position;
in vec4 Color;
in vec2 UV0;

uniform mat4 ModelViewMat;
uniform mat4 ProjMat;

out vec4 vertexColor;
out vec2 texCoord0;

void main() {
    gl_Position = ProjMat * ModelViewMat * vec4(Position, 1.0);
    vertexColor = Color;
    texCoord0   = UV0;
}
