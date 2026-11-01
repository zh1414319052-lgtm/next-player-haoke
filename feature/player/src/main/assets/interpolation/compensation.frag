precision highp float;
uniform sampler2D uForward;
uniform sampler2D uBackward;
uniform vec2 uPixel;
uniform float uFraction;
varying vec2 vUv;
vec4 candidate(vec2 displacement) {
    // Project one consistent displacement into both input frames. Using unrelated
    // initial forward/backward positions mixes foreground and background at edges.
    vec4 forward = texture2D(uForward, vUv - uFraction * displacement);
    vec4 backward = texture2D(uBackward, vUv + (1.0 - uFraction) * displacement);
    vec2 refined = 0.5 * (forward.rg - backward.rg);
    forward = texture2D(uForward, vUv - uFraction * refined);
    backward = texture2D(uBackward, vUv + (1.0 - uFraction) * refined);
    float consistency = length((forward.rg + backward.rg) / uPixel);
    float error = max(forward.b, backward.b);
    float valid = (1.0 - smoothstep(0.025, 0.075, error)) *
        (1.0 - smoothstep(1.0, 4.0, consistency));
    return vec4(0.5 * (forward.rg - backward.rg), valid, error + 0.02 * consistency);
}
void main() {
    // At an object boundary, only one input may contain the moving foreground at
    // this output coordinate. Evaluate both seeds before rejecting the region.
    vec4 fromPrevious = candidate(texture2D(uForward, vUv).rg);
    vec4 fromCurrent = candidate(-texture2D(uBackward, vUv).rg);
    bool useCurrent = fromCurrent.b > fromPrevious.b ||
        (fromCurrent.b == fromPrevious.b && fromCurrent.a < fromPrevious.a);
    vec4 best = useCurrent ? fromCurrent : fromPrevious;
    // Confidence-premultiply displacement so bilinear upsampling never pulls a
    // reliable vector towards an unrelated, rejected neighbour (4K magnifies this).
    // Keep displacement rather than absolute UV for FP16 subpixel precision.
    gl_FragColor = vec4(best.rg * best.b, best.b, 1.0);
}
