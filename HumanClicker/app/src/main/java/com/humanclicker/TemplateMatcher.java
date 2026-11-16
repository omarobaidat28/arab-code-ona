package com.humanclicker;

import android.graphics.Bitmap;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * Finds a template image on a screenshot using normalized cross-correlation (NCC),
 * coarse-to-fine: a fast scan on a downscaled screen, then refinement of the best
 * candidates at higher resolution, then an optional color check to avoid false clicks.
 */
public final class TemplateMatcher {

    public static final class Match {
        public float cx, cy;      // center on screen (px)
        public int x, y, w, h;    // bounding box on screen (px)
        public double score;
    }

    /** Grayscale image at some downscale factor. */
    static final class Gray {
        int w, h;
        float[] g;
        double[] sum, sq; // integral images (lazy)
    }

    /** Zero-mean template at some downscale factor. */
    static final class Level {
        int w, h;
        float[] z;
        double norm;
    }

    // ---------------------------------------------------------------- screen

    public static final class Screen {
        final int W, H;
        final int[] argb;
        final int[] gray;
        private final HashMap<Integer, Gray> cache = new HashMap<Integer, Gray>();

        public Screen(Bitmap b) {
            this(pixels(b), b.getWidth(), b.getHeight());
        }

        public Screen(int[] argb, int w, int h) {
            W = w;
            H = h;
            this.argb = argb;
            gray = toGray(argb);
        }

        Gray at(int s) {
            Gray g = cache.get(s);
            if (g == null) {
                g = down(gray, W, H, s);
                cache.put(s, g);
            }
            return g;
        }
    }

    // ---------------------------------------------------------------- template

    public static final class Template {
        public final String id;
        public final int w, h;
        final int[] argb;
        final int[] gray;
        final float mr, mg, mb;
        private final HashMap<Integer, Level> cache = new HashMap<Integer, Level>();

        public Template(String id, Bitmap b) {
            this(id, pixels(b), b.getWidth(), b.getHeight());
        }

        public Template(String id, int[] argb, int w, int h) {
            this.id = id;
            this.w = w;
            this.h = h;
            this.argb = argb;
            gray = toGray(argb);
            double r = 0, g = 0, bl = 0;
            for (int p : argb) {
                r += (p >> 16) & 255;
                g += (p >> 8) & 255;
                bl += p & 255;
            }
            int n = Math.max(1, argb.length);
            mr = (float) (r / n);
            mg = (float) (g / n);
            mb = (float) (bl / n);
        }

        Level at(int s) {
            Level L = cache.get(s);
            if (L == null) {
                Gray g = down(gray, w, h, s);
                L = new Level();
                L.w = g.w;
                L.h = g.h;
                int n = g.w * g.h;
                double mean = 0;
                for (int i = 0; i < n; i++) mean += g.g[i];
                mean /= Math.max(1, n);
                L.z = new float[n];
                double ss = 0;
                for (int i = 0; i < n; i++) {
                    float v = (float) (g.g[i] - mean);
                    L.z[i] = v;
                    ss += v * v;
                }
                L.norm = Math.sqrt(ss);
                cache.put(s, L);
            }
            return L;
        }

        /** True if the template has no texture at all (cannot be matched reliably). */
        public boolean isFlat() {
            return at(1).norm < 1e-3 * Math.sqrt(w * h) + 1;
        }
    }

    // ---------------------------------------------------------------- helpers

    static int[] pixels(Bitmap b) {
        int[] a = new int[b.getWidth() * b.getHeight()];
        b.getPixels(a, 0, b.getWidth(), 0, 0, b.getWidth(), b.getHeight());
        return a;
    }

    static int[] toGray(int[] argb) {
        int[] g = new int[argb.length];
        for (int i = 0; i < argb.length; i++) {
            int p = argb[i];
            g[i] = (((p >> 16) & 255) * 77 + ((p >> 8) & 255) * 150 + (p & 255) * 29) >> 8;
        }
        return g;
    }

    static Gray down(int[] gray, int W, int H, int s) {
        Gray out = new Gray();
        out.w = Math.max(1, W / s);
        out.h = Math.max(1, H / s);
        out.g = new float[out.w * out.h];
        if (s == 1) {
            for (int i = 0; i < out.g.length; i++) out.g[i] = gray[i];
            return out;
        }
        float inv = 1f / (s * s);
        for (int y = 0; y < out.h; y++) {
            int sy = y * s;
            for (int x = 0; x < out.w; x++) {
                int sx = x * s, acc = 0;
                for (int j = 0; j < s; j++) {
                    int row = (sy + j) * W + sx;
                    for (int i = 0; i < s; i++) acc += gray[row + i];
                }
                out.g[y * out.w + x] = acc * inv;
            }
        }
        return out;
    }

    static void integral(Gray g) {
        if (g.sum != null) return;
        int W1 = g.w + 1;
        g.sum = new double[W1 * (g.h + 1)];
        g.sq = new double[W1 * (g.h + 1)];
        for (int y = 0; y < g.h; y++) {
            double rs = 0, rq = 0;
            for (int x = 0; x < g.w; x++) {
                double v = g.g[y * g.w + x];
                rs += v;
                rq += v * v;
                g.sum[(y + 1) * W1 + x + 1] = g.sum[y * W1 + x + 1] + rs;
                g.sq[(y + 1) * W1 + x + 1] = g.sq[y * W1 + x + 1] + rq;
            }
        }
    }

    static double rect(double[] I, int W1, int x, int y, int w, int h) {
        return I[(y + h) * W1 + x + w] - I[y * W1 + x + w] - I[(y + h) * W1 + x] + I[y * W1 + x];
    }

    /** NCC score of template level L placed at (x,y) on image g (computed directly). */
    static double scoreAt(Gray g, Level L, int x, int y) {
        if (x < 0 || y < 0 || x + L.w > g.w || y + L.h > g.h) return -2;
        double dot = 0, s = 0, q = 0;
        for (int j = 0; j < L.h; j++) {
            int row = (y + j) * g.w + x, tb = j * L.w;
            for (int i = 0; i < L.w; i++) {
                float v = g.g[row + i];
                dot += L.z[tb + i] * v;
                s += v;
                q += v * v;
            }
        }
        int n = L.w * L.h;
        double var = q - s * s / n;
        if (var < n * 0.5 || L.norm < 1e-6) return -2;
        return dot / (L.norm * Math.sqrt(var));
    }

    // ---------------------------------------------------------------- search

    public static Match find(Screen sc, Template t, double threshold, boolean colorCheck) {
        if (t.w > sc.W || t.h > sc.H || t.w < 8 || t.h < 8) return null;
        int minDim = Math.min(t.w, t.h);
        int s = Math.max(1, minDim / 16);
        // keep the coarse scan cheap
        while (s < minDim / 6 && (double) (sc.W / s) * (sc.H / s) * (t.w / s) * (t.h / s) > 45e6) s++;
        int fin = Math.max(1, minDim / 100);
        if (fin > s) fin = s;

        Gray g = sc.at(s);
        Level L = t.at(s);
        if (L.w < 3 || L.h < 3 || L.w > g.w || L.h > g.h || L.norm < 1e-6) return null;
        integral(g);

        // ---- coarse scan, keep best few non-overlapping candidates
        final int K = 6;
        List<double[]> cand = new ArrayList<double[]>(); // {score, x, y}
        double minCoarse = Math.max(0.3, threshold - 0.25);
        int n = L.w * L.h, W1 = g.w + 1;
        for (int y = 0; y + L.h <= g.h; y++) {
            for (int x = 0; x + L.w <= g.w; x++) {
                double s1 = rect(g.sum, W1, x, y, L.w, L.h);
                double q1 = rect(g.sq, W1, x, y, L.w, L.h);
                double var = q1 - s1 * s1 / n;
                if (var < n * 0.5) continue;
                float dot = 0;
                for (int j = 0; j < L.h; j++) {
                    int row = (y + j) * g.w + x, tb = j * L.w;
                    for (int i = 0; i < L.w; i++) dot += L.z[tb + i] * g.g[row + i];
                }
                double sc1 = dot / (L.norm * Math.sqrt(var));
                if (sc1 < minCoarse) continue;
                addCandidate(cand, K, sc1, x, y, Math.max(2, Math.min(L.w, L.h) / 2));
            }
        }
        if (cand.isEmpty()) return null;

        // ---- refine each candidate down to the final scale
        Match best = null;
        for (double[] c : cand) {
            int cs = s;
            int bx = (int) c[1], by = (int) c[2];
            double bs = c[0];
            while (cs > fin) {
                int ns = Math.max(fin, cs / 3);
                if (ns == cs) ns = cs - 1;
                Gray gn = sc.at(ns);
                Level Ln = t.at(ns);
                int ratio = (cs + ns - 1) / ns;
                int ox = bx * cs / ns, oy = by * cs / ns;
                int rad = ratio + 1;
                bs = -2;
                for (int yy = oy - rad; yy <= oy + rad; yy++) {
                    for (int xx = ox - rad; xx <= ox + rad; xx++) {
                        double v = scoreAt(gn, Ln, xx, yy);
                        if (v > bs) {
                            bs = v;
                            bx = xx;
                            by = yy;
                        }
                    }
                }
                cs = ns;
            }
            if (bs < threshold) continue;
            Match m = new Match();
            m.x = bx * cs;
            m.y = by * cs;
            m.w = t.w;
            m.h = t.h;
            m.cx = m.x + t.w / 2f;
            m.cy = m.y + t.h / 2f;
            m.score = bs;
            if (colorCheck && !colorOk(sc, t, m)) continue;
            if (best == null || m.score > best.score) best = m;
        }
        return best;
    }

    private static void addCandidate(List<double[]> cand, int K, double score, int x, int y, int minDist) {
        for (int i = 0; i < cand.size(); i++) {
            double[] c = cand.get(i);
            if (Math.abs(c[1] - x) < minDist && Math.abs(c[2] - y) < minDist) {
                if (score > c[0]) {
                    c[0] = score;
                    c[1] = x;
                    c[2] = y;
                }
                return;
            }
        }
        if (cand.size() < K) {
            cand.add(new double[]{score, x, y});
            return;
        }
        int worst = 0;
        for (int i = 1; i < cand.size(); i++) if (cand.get(i)[0] < cand.get(worst)[0]) worst = i;
        if (score > cand.get(worst)[0]) cand.set(worst, new double[]{score, x, y});
    }

    /** Compare average color of the found region with the template (rejects same shape, other color). */
    static boolean colorOk(Screen sc, Template t, Match m) {
        int step = Math.max(1, Math.min(t.w, t.h) / 24);
        double r = 0, g = 0, b = 0, dr = 0;
        int cnt = 0;
        for (int y = 0; y < t.h; y += step) {
            int sy = m.y + y;
            if (sy < 0 || sy >= sc.H) continue;
            for (int x = 0; x < t.w; x += step) {
                int sx = m.x + x;
                if (sx < 0 || sx >= sc.W) continue;
                int p = sc.argb[sy * sc.W + sx];
                int q = t.argb[y * t.w + x];
                int pr = (p >> 16) & 255, pg = (p >> 8) & 255, pb = p & 255;
                r += pr;
                g += pg;
                b += pb;
                dr += Math.abs(pr - ((q >> 16) & 255)) + Math.abs(pg - ((q >> 8) & 255)) + Math.abs(pb - (q & 255));
                cnt++;
            }
        }
        if (cnt == 0) return false;
        r /= cnt;
        g /= cnt;
        b /= cnt;
        double meanDiff = Math.max(Math.abs(r - t.mr), Math.max(Math.abs(g - t.mg), Math.abs(b - t.mb)));
        double pixelDiff = dr / (cnt * 3.0);
        return meanDiff <= 28 && pixelDiff <= 45;
    }
}
