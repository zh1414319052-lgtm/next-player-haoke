attribute vec4 aFramePosition;
varying vec2 vUv;
void main() {
    gl_Position = aFramePosition;
    vUv = aFramePosition.xy * 0.5 + 0.5;
}
