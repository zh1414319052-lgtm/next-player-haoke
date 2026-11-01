precision highp float;
uniform sampler2D uPrevious;
uniform sampler2D uCurrent;
uniform sampler2D uMotion;
uniform float uFraction;
varying vec2 vUv;
void main() {
    vec4 motion = texture2D(uMotion, vUv);
    vec2 displacement = motion.rg / max(motion.b, 0.0001);
    vec2 p = vUv - uFraction * displacement;
    vec2 q = vUv + (1.0 - uFraction) * displacement;
    bool valid = motion.b > 0.0001;
    valid = valid && all(greaterThanEqual(p, vec2(0.0))) && all(lessThanEqual(p, vec2(1.0)));
    valid = valid && all(greaterThanEqual(q, vec2(0.0))) && all(lessThanEqual(q, vec2(1.0)));
    vec4 original = uFraction <= 0.5 ? texture2D(uPrevious, vUv) : texture2D(uCurrent, vUv);
    if (!valid) { gl_FragColor = original; return; }
    vec4 previous = texture2D(uPrevious, p);
    vec4 current = texture2D(uCurrent, q);
    // Validate at output resolution, then feather confidence instead of cutting
    // at 0.99 on a 320px mask. That cut drew enlarged stair steps on moving edges.
    // This only selects motion; the original linear SDR/HDR colour stays intact.
    vec3 difference = abs(previous.rgb - current.rgb) / (vec3(1.0) + max(abs(previous.rgb), abs(current.rgb)));
    float error = max(difference.r, max(difference.g, difference.b));
    float confidence = smoothstep(0.0, 0.5, motion.b) * (1.0 - smoothstep(0.04, 0.12, error));
    gl_FragColor = mix(original, mix(previous, current, uFraction), confidence);
}
