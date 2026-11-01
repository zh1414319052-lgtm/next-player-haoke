precision highp float;
uniform sampler2D uImage;
uniform vec2 uFilterStep;
varying vec2 vUv;
void main() {
    // The first 4K -> 640px reduction also needs a low-pass filter. A single
    // source sample aliases thin lines into false motion before the pyramid starts.
    vec3 rgb = vec3(0.0);
    for (int y = 0; y < 4; ++y) {
        for (int x = 0; x < 4; ++x) {
            vec2 offset = (vec2(float(x), float(y)) - 1.5) * uFilterStep;
            rgb += max(texture2D(uImage, vUv + offset).rgb, vec3(0.0));
        }
    }
    rgb /= 16.0;
    // Bounded matching signal for both linear SDR and HDR; output color is untouched.
    float y = dot(rgb, vec3(0.2627, 0.6780, 0.0593));
    float luma = log(1.0 + 32.0 * y) / log(33.0);
    gl_FragColor = vec4(luma, luma, luma, 1.0);
}
