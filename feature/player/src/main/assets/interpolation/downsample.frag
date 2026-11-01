precision highp float;
uniform sampler2D uImage;
uniform vec2 uPixel;
varying vec2 vUv;
void main() {
    // Anti-alias before the coarse search. Sparse samples of full-resolution noise
    // cannot distinguish a motion between the old four-pixel search positions.
    float total = 0.0;
    for (int y = 0; y < 4; ++y) {
        for (int x = 0; x < 4; ++x) {
            vec2 offset = (vec2(float(x), float(y)) - 1.5) * uPixel;
            total += texture2D(uImage, vUv + offset).r;
        }
    }
    float value = total / 16.0;
    gl_FragColor = vec4(value, value, value, 1.0);
}
