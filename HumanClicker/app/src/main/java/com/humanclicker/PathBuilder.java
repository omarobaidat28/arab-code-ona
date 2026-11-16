package com.humanclicker;

import android.graphics.Path;

/**
 * Builds a finger path that is traversed at constant speed v (px/ms) by the gesture system.
 * Because time = length / v, "standing still" is produced by tiny trembling movements
 * (like a real finger) whose length equals v * wanted time.
 */
public final class PathBuilder {
    public final Path path = new Path();
    private final Human h;
    private final float maxX, maxY;
    public final double v;
    private double len = 0;
    public float cx, cy;
    private int segs = 0;

    public PathBuilder(Human h, int screenW, int screenH, double speedPxPerMs) {
        this.h = h;
        this.maxX = Math.max(2, screenW - 2);
        this.maxY = Math.max(2, screenH - 2);
        this.v = Math.max(0.02, speedPxPerMs);
    }

    private float clx(double x) {
        return (float) Math.max(1, Math.min(maxX, x));
    }

    private float cly(double y) {
        return (float) Math.max(1, Math.min(maxY, y));
    }

    public void start(float x, float y) {
        cx = clx(x);
        cy = cly(y);
        path.moveTo(cx, cy);
    }

    public void lineTo(double x, double y) {
        float nx = clx(x), ny = cly(y);
        len += Math.hypot(nx - cx, ny - cy);
        path.lineTo(nx, ny);
        cx = nx;
        cy = ny;
        segs++;
    }

    /** Curved movement (cubic bezier with random bend + slight tremor). */
    public void curveTo(double x, double y, double curvePct) {
        double x0 = cx, y0 = cy;
        double dx = x - x0, dy = y - y0;
        double d = Math.hypot(dx, dy);
        if (d < 2) {
            lineTo(x, y);
            return;
        }
        double nx = -dy / d, ny = dx / d;
        double bend = d * curvePct / 100.0;
        double o1 = h.gauss() * bend * 0.5, o2 = h.gauss() * bend * 0.5;
        double t1 = 0.2 + h.u() * 0.2, t2 = 0.6 + h.u() * 0.2;
        double c1x = x0 + dx * t1 + nx * o1, c1y = y0 + dy * t1 + ny * o1;
        double c2x = x0 + dx * t2 + nx * o2, c2y = y0 + dy * t2 + ny * o2;
        int steps = (int) Math.max(6, Math.min(80, d / 6));
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps, it = 1 - t;
            double px = it * it * it * x0 + 3 * it * it * t * c1x + 3 * it * t * t * c2x + t * t * t * x;
            double py = it * it * it * y0 + 3 * it * it * t * c1y + 3 * it * t * t * c2y + t * t * t * y;
            if (i < steps) {
                px += h.gauss() * 0.5;
                py += h.gauss() * 0.5;
            }
            lineTo(px, py);
        }
    }

    /** Circular movement around (ox,oy) - used to make a game character turn. */
    public void arc(double ox, double oy, double radius, double fromAngle, double sweep) {
        curveTo(ox + Math.cos(fromAngle) * radius, oy + Math.sin(fromAngle) * radius, 15);
        int steps = (int) Math.max(8, Math.min(160, Math.abs(sweep) * radius / 6));
        double rr = radius;
        for (int i = 1; i <= steps; i++) {
            double a = fromAngle + sweep * i / steps;
            rr += h.gauss() * radius * 0.015;
            rr = Math.max(radius * 0.6, Math.min(radius * 1.3, rr));
            lineTo(ox + Math.cos(a) * rr, oy + Math.sin(a) * rr);
        }
    }

    /** Keep the finger (almost) still for ms milliseconds with a natural tremor. */
    public void dwell(long ms) {
        if (ms <= 0) return;
        double target = v * ms;
        double rad = 1.2 + h.u() * 1.6;
        if (target / rad > 3500) rad = target / 3500; // keep the path size reasonable
        rad = Math.min(rad, 6);
        float bx = cx, by = cy;
        double acc = 0;
        int guard = 0;
        while (acc < target && guard++ < 20000) {
            double nx = bx + h.gauss() * rad, ny = by + h.gauss() * rad;
            double ddx = nx - bx, ddy = ny - by, dist = Math.hypot(ddx, ddy);
            if (dist > rad * 2.5) {
                nx = bx + ddx / dist * rad * 2.5;
                ny = by + ddy / dist * rad * 2.5;
            }
            double before = len;
            lineTo(nx, ny);
            double step = len - before;
            acc += step < 0.2 ? 0.2 : step;
        }
        lineTo(bx, by);
    }

    public double timeMs() {
        return len / v;
    }

    public long durationMs() {
        return Math.max(1, Math.round(len / v));
    }

    public int segments() {
        return segs;
    }
}
