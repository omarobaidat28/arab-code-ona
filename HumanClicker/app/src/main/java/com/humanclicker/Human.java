package com.humanclicker;

import java.security.SecureRandom;
import java.util.Random;

/**
 * Randomness that looks human: bell-shaped timings, hesitations, heavy-tailed durations
 * (mostly short, sometimes long), gaussian finger placement, and random orders.
 */
public final class Human {
    private final Random r;

    public Human() {
        long seed = new SecureRandom().nextLong() ^ System.nanoTime();
        r = new Random(seed);
    }

    public double u() {
        return r.nextDouble();
    }

    public double gauss() {
        return r.nextGaussian();
    }

    public int nextInt(int n) {
        return n <= 1 ? 0 : r.nextInt(n);
    }

    public boolean chance(double percent) {
        return percent > 0 && r.nextDouble() * 100.0 < percent;
    }

    public int range(int a, int b) {
        if (b < a) {
            int t = a;
            a = b;
            b = t;
        }
        return a + r.nextInt(b - a + 1);
    }

    /** Bell shaped value in [a,b] (sum of uniforms), never exactly the same twice. */
    public double between(double a, double b) {
        if (b < a) {
            double t = a;
            a = b;
            b = t;
        }
        double t = (u() + u() + u()) / 3.0;
        return a + (b - a) * t;
    }

    /** Delay between actions: bell-shaped, with occasional hesitations and quick bursts. */
    public long delay(int min, int max) {
        double v = between(min, max);
        if (chance(7)) v *= 1.3 + u() * 1.4;            // hesitation / thinking
        else if (chance(5)) v = min + (v - min) * 0.35;  // quick burst
        return Math.max((long) (min * 0.9), (long) v);
    }

    /** Heavy tailed in [min,max]: usually short-to-medium, with longChance% it's long. */
    public long heavy(long min, long max, int longChance) {
        if (max <= min) return min;
        double span = max - min;
        if (chance(longChance)) return (long) (min + span * (0.5 + 0.5 * Math.pow(u(), 0.6)));
        return (long) (min + span * 0.5 * Math.pow(u(), 1.8));
    }

    /** Gaussian finger placement inside a circle of the given radius. */
    public float[] jitter(float x, float y, float radius) {
        if (radius <= 0.5f) return new float[]{x, y};
        for (int i = 0; i < 10; i++) {
            double dx = gauss() * radius / 2.3;
            double dy = gauss() * radius / 2.3;
            if (dx * dx + dy * dy <= radius * radius) return new float[]{(float) (x + dx), (float) (y + dy)};
        }
        return new float[]{x, y};
    }

    public int[] permutation(int n) {
        int[] a = new int[n];
        for (int i = 0; i < n; i++) a[i] = i;
        for (int i = n - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            int t = a[i];
            a[i] = a[j];
            a[j] = t;
        }
        return a;
    }
}
