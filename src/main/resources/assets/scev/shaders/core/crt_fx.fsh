#version 150

// CRT FX fragment shader — per-pixel effects applied to the live mlterm
// framebuffer texture sampled at Sampler0.
//
// Per-block params arrive via the vertex `Color` attribute (packed by
// TerminalRenderer in Kotlin):
//   vertexColor.rgb = phosphor color × brightness scalar
//   vertexColor.a   = scanline strength (0 = off, 1 = max)
//
// World-level params arrive via uniforms set at shader load (default
// values in crt_fx.json). These represent the "physical" CRT
// characteristics that don't normally vary per-terminal —
// curvature is a property of the picture tube, vignette of the
// optics, aperture mask of the shadow grille.
//
// Effects applied, in order, in main():
//   1. Curvature  — barrel distortion of texCoord (UV warp)
//   2. Sample     — texture lookup at warped coord
//   3. Phosphor   — tint × brightness from vertex color
//   4. Bloom      — cheap 5-tap blur added back at low intensity
//   5. Scanlines  — alternating-row dimming based on source pixel y
//   6. Aperture   — 3-pixel-period RGB cell darkening
//   7. Vignette   — radial corner falloff
//
// Effects 1, 6, 7 use uniforms; 3 and 5 use vertex attributes; 2, 4
// are unconditional. All can be set to 0 to skip the visual effect
// (most short-circuit on zero to save GPU cycles, but the cost is
// negligible either way for our 480×312 source).

uniform sampler2D Sampler0;
uniform float TexHeightPx;       // source mlterm height (312) for scanlines
uniform float TexWidthPx;        // source mlterm width (480) for aperture mask
uniform float Curvature;         // 0..1, barrel distortion strength
uniform float Vignette;          // 0..1, corner darkening intensity
uniform float Bloom;             // 0..1, glow blend factor
uniform float ApertureMask;      // 0..1, RGB shadow-mask intensity

in vec4 vertexColor;
in vec2 texCoord0;

out vec4 fragColor;

// Barrel distortion: warp UV outward from center quadratically with
// distance². Curvature=0 returns identity; Curvature=0.3 looks like a
// typical 80s consumer CRT; >0.5 is exaggerated. Out-of-bounds UV
// after warp is detected by main() and rendered as black border.
vec2 curvedUV(vec2 uv) {
    if (Curvature <= 0.0) return uv;
    vec2 c = uv - 0.5;
    float r2 = dot(c, c);
    return 0.5 + c * (1.0 + Curvature * r2);
}

// 5-tap cheap bloom: average 4 cardinal neighbours into the centre
// at a small UV offset (1/200 of the surface width and height — about
// 2 source pixels). Real bloom needs a separable Gaussian pass with
// a downsampled framebuffer, which we'd want for proper glow on
// bright phosphor lines, but this fake gets us 80% of the look at
// 5% of the implementation cost.
vec3 cheapBloom(vec2 uv, vec3 baseTint) {
    if (Bloom <= 0.0) return vec3(0.0);
    float dx = 1.0 / TexWidthPx;
    float dy = 1.0 / TexHeightPx;
    vec3 acc =
        texture(Sampler0, uv + vec2(-dx,  0.0)).rgb +
        texture(Sampler0, uv + vec2( dx,  0.0)).rgb +
        texture(Sampler0, uv + vec2(0.0, -dy)).rgb +
        texture(Sampler0, uv + vec2(0.0,  dy)).rgb;
    return (acc * 0.25) * baseTint * Bloom;
}

// Scanline mask: every other source-pixel row dimmed by
// vertexColor.a. step() makes this a hard 0/1 cutoff rather than
// smooth — which actually matches the visual of real CRT gaps,
// where the line spacing appears as a dark stripe between adjacent
// scan rows.
float scanlineFactor(vec2 uv) {
    float row = floor(uv.y * TexHeightPx);
    return 1.0 - vertexColor.a * step(0.5, mod(row, 2.0));
}

// Aperture grille: every 3-px column gets one R/G/B emphasis. Each
// pixel in the ApertureMask=1 case has TWO of the three channels
// dimmed by ApertureMask, leaving one full-strength. Lower mask
// values blend toward uniform.
vec3 apertureMask(vec2 uv) {
    if (ApertureMask <= 0.0) return vec3(1.0);
    float x = floor(uv.x * TexWidthPx);
    int cell = int(mod(x, 3.0));
    vec3 mask;
    if      (cell == 0) mask = vec3(1.0, 1.0 - ApertureMask, 1.0 - ApertureMask);
    else if (cell == 1) mask = vec3(1.0 - ApertureMask, 1.0, 1.0 - ApertureMask);
    else                mask = vec3(1.0 - ApertureMask, 1.0 - ApertureMask, 1.0);
    return mask;
}

// Vignette: radial darkening from screen centre. Uses smoothstep on
// distance² to keep the centre untouched and only darken the corners
// (real CRT optics fall off at the edges due to the curvature of the
// glass + electron beam angle). Tuned so Vignette=0.5 looks like a
// well-aged 70s/80s consumer CRT.
float vignette(vec2 uv) {
    if (Vignette <= 0.0) return 1.0;
    vec2 c = uv - 0.5;
    float r = length(c) * 1.4142;          // normalise so corner ≈ 1.0
    return 1.0 - Vignette * smoothstep(0.4, 1.0, r);
}

void main() {
    vec2 uv = curvedUV(texCoord0);

    // Black bezel beyond the warped surface — happens at high
    // Curvature where the corners of the destination quad map to
    // out-of-source-rectangle UVs. Returning here also avoids any
    // sampling of garbage pixels at the wrap-around edges.
    if (uv.x < 0.0 || uv.x > 1.0 || uv.y < 0.0 || uv.y > 1.0) {
        fragColor = vec4(0.0, 0.0, 0.0, 1.0);
        return;
    }

    vec4 texel = texture(Sampler0, uv);
    vec3 tinted = texel.rgb * vertexColor.rgb;

    // Bloom glows ON TOP of the tinted pixel — added, not multiplied,
    // because phosphor halos are emissive.
    tinted += cheapBloom(uv, vertexColor.rgb);

    // Scanlines + aperture mask + vignette all multiplicative,
    // applied in any order (commute because they're all per-pixel
    // scalars or per-channel scalars without cross-channel mixing).
    tinted *= scanlineFactor(uv);
    tinted *= apertureMask(uv);
    tinted *= vignette(uv);

    fragColor = vec4(tinted, texel.a);
}
