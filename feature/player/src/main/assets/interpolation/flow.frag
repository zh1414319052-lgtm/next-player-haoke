precision highp float;
uniform sampler2D uFrom;
uniform sampler2D uTo;
uniform sampler2D uSeed;
uniform vec2 uStep;
uniform vec2 uPatch;
uniform float uRefine;
varying vec2 vUv;

float cost(vec2 d) {
    float result = 0.0;
    if (uRefine < 0.5) {
        // The anti-aliased pyramid still needs spatial context: three sparse samples
        // per axis can prefer unrelated noise over a displacement between texels.
        // A wider, equally weighted coarse patch finds the basin; the fine patch
        // below remains local and centre-weighted to preserve object boundaries.
        for (int y = -2; y <= 2; ++y) {
            for (int x = -2; x <= 2; ++x) {
                vec2 p = vUv + vec2(float(x), float(y)) * uPatch;
                result += abs(texture2D(uFrom, p).r - texture2D(uTo, p + d).r);
            }
        }
        return result / 25.0;
    }
    for (int y = -1; y <= 1; ++y) {
        for (int x = -1; x <= 1; ++x) {
            vec2 p = vUv + vec2(float(x), float(y)) * uPatch;
            // Give the centre enough weight to retain small foreground features instead
            // of fitting the surrounding background. No change to output colour/HDR.
            float weight = x == 0 && y == 0 ? 4.0 : 1.0;
            result += weight * abs(texture2D(uFrom, p).r - texture2D(uTo, p + d).r);
        }
    }
    return result / 12.0;
}
void main() {
    vec2 seed = uRefine > 0.5 ? texture2D(uSeed, vUv).rg : vec2(0.0);
    // Bilinear sampling between coarse cells must not shift the entire refinement
    // lattice off pixel centres; fractional motion is fitted separately below.
    if (uRefine > 0.5) seed = floor(seed / uStep + 0.5) * uStep;
    vec2 best = vec2(0.0);
    float zero = cost(best);
    float bestCost = zero;
    for (int y = -4; y <= 4; ++y) {
        for (int x = -4; x <= 4; ++x) {
            if (uRefine > 0.5 && (x < -2 || x > 2 || y < -2 || y > 2)) continue;
            vec2 d = seed + vec2(float(x), float(y)) * uStep;
            float c = cost(d);
            // Prefer smaller motion when the matching cost is indistinguishable.
            if (c + 0.0002 * length(d / uStep) < bestCost) {
                bestCost = c;
                best = d;
            }
        }
    }
    if (uRefine > 0.5) {
        // A coarse cell can belong to the background while its finer child belongs
        // to a slowly moving object. Also consider local motion around zero.
        if (length(seed / uStep) > 2.5 && bestCost > 0.0005) {
            for (int y = -1; y <= 1; ++y) {
                for (int x = -1; x <= 1; ++x) {
                    if (x == 0 && y == 0) continue;
                    vec2 d = vec2(float(x), float(y)) * uStep;
                    float c = cost(d);
                    if (c + 0.0002 * length(d / uStep) < bestCost) {
                        bestCost = c;
                        best = d;
                    }
                }
            }
        }
        // Fit fractional-pixel motion as well as integer displacements. Keep the
        // centre fixed throughout this search so candidate order cannot cause drift.
        vec2 centre = best;
        if (bestCost > 0.0005) {
            for (int y = -1; y <= 1; ++y) {
                for (int x = -1; x <= 1; ++x) {
                    if (x == 0 && y == 0) continue;
                    vec2 d = centre + vec2(float(x), float(y)) * uStep * 0.5;
                    float c = cost(d);
                    if (c + 0.0002 * length(d / uStep) < bestCost) {
                        bestCost = c;
                        best = d;
                    }
                }
            }
        }
    }
    gl_FragColor = vec4(best, bestCost, zero);
}
