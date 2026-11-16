package com.humanclicker;

import android.graphics.Path;

import java.util.ArrayDeque;

/**
 * A human-like joystick finger. Produces an endless stream of integer points around a center:
 * walking in a direction while steering a little, turning in arcs, stopping, strafing (zigzag),
 * sudden direction changes. Directions are balanced over the 8 sectors so it visits ALL sides
 * instead of preferring one.
 *
 * Points are consumed by length: the gesture system moves the finger at constant speed v along
 * the path, so time = length / v. "Holding still" is a tiny tremor whose length = v * time.
 * All points are whole pixels so a stroke can be continued exactly in the next gesture.
 */
public final class Walker {
    private final Human h;
    private final Config c;
    private final int maxX, maxY;
    private final boolean regular;
    private final double R;

    public int cx, cy;   // joystick center (where the finger landed)
    public int px, py;   // current finger point
    public double v;     // finger speed for this session (px / ms)

    private double ang;
    private double planX, planY;
    private final double[] sector = new double[8];
    private final double[] stat = new double[8];
    private final ArrayDeque<double[]> q = new ArrayDeque<double[]>(); // {kind(0 point / 1 tremor), x, y, lengthLeft}
    private int[] pending;

    public Walker(Human h, Config c, int screenW, int screenH, boolean regular) {
        this.h = h;
        this.c = c;
        this.maxX = Math.max(2, screenW - 2);
        this.maxY = Math.max(2, screenH - 2);
        this.regular = regular;
        this.R = c.wander;
        ang = h.u() * Math.PI * 2;
    }

    private int clx(double x) {
        return (int) Math.max(1, Math.min(maxX, Math.round(x)));
    }

    private int cly(double y) {
        return (int) Math.max(1, Math.min(maxY, Math.round(y)));
    }

    /** Finger touches down near (x, y). */
    public void startSession(float x, float y) {
        cx = clx(x);
        cy = cly(y);
        px = cx;
        py = cy;
        planX = cx;
        planY = cy;
        q.clear();
        pending = null;
        v = regular ? 0.25 : 0.12 + h.u() * 0.3;
        if (!regular) ang = pickDirection();
        tremor(cx, cy, regular ? 60 : h.range(40, 160));
    }

    /** Finger lifted and touches the center again (used when the device cannot continue strokes). */
    public void recenter() {
        px = cx;
        py = cy;
        planX = cx;
        planY = cy;
        q.clear();
        pending = null;
        tremor(cx, cy, h.range(30, 90));
    }

    // ------------------------------------------------------------------ planning primitives

    private void point(double x, double y) {
        q.add(new double[]{0, x, y, 0});
        planX = x;
        planY = y;
    }

    private void tremor(double x, double y, double ms) {
        if (ms > 0) q.add(new double[]{1, x, y, ms * v});
    }

    private void hold(double ms) {
        tremor(planX, planY, ms);
    }

    /** Curved move from the planned position; slow > 1 makes the finger move slower. */
    private void move(double x, double y, double curvePct, double slow) {
        x = Math.max(1, Math.min(maxX, x));
        y = Math.max(1, Math.min(maxY, y));
        double x0 = planX, y0 = planY, dx = x - x0, dy = y - y0, d = Math.hypot(dx, dy);
        if (d < 1) return;
        double nx = -dy / d, ny = dx / d, bend = d * curvePct / 100.0;
        double o1 = h.gauss() * bend * 0.5, o2 = h.gauss() * bend * 0.5;
        double c1x = x0 + dx * 0.3 + nx * o1, c1y = y0 + dy * 0.3 + ny * o1;
        double c2x = x0 + dx * 0.7 + nx * o2, c2y = y0 + dy * 0.7 + ny * o2;
        int steps = (int) Math.max(2, Math.min(80, d / 5));
        double stepLen = d / steps;
        for (int i = 1; i <= steps; i++) {
            double t = (double) i / steps, it = 1 - t;
            double bx = it * it * it * x0 + 3 * it * it * t * c1x + 3 * it * t * t * c2x + t * t * t * x;
            double by = it * it * it * y0 + 3 * it * it * t * c1y + 3 * it * t * t * c2y + t * t * t * y;
            point(bx, by);
            if (slow > 1) q.add(new double[]{1, bx, by, (slow - 1) * stepLen});
        }
    }

    private double angleNow() {
        return Math.atan2(planY - cy, planX - cx);
    }

    /** Keep pushing in the current direction, with small natural steering corrections. */
    private void steerHold(long ms) {
        long left = ms;
        while (left > 0) {
            long chunk = Math.min(left, h.range(250, 800));
            double r = Math.hypot(planX - cx, planY - cy);
            if (r < R * 0.2) {
                hold(chunk);
            } else {
                double a = angleNow() + h.gauss() * 0.07;
                r = Math.max(R * 0.3, Math.min(R * 1.1, r * (1 + h.gauss() * 0.03)));
                move(cx + Math.cos(a) * r, cy + Math.sin(a) * r, 0, 1);
                hold(chunk);
            }
            left -= chunk;
        }
    }

    /** Pick a direction, preferring sides that were used less recently (covers all directions). */
    private double pickDirection() {
        double total = 0;
        for (int i = 0; i < 8; i++) {
            sector[i] *= 0.85; // forget slowly
            total += sector[i];
        }
        double[] w = new double[8];
        double sum = 0;
        for (int i = 0; i < 8; i++) {
            double share = total > 0 ? sector[i] / total : 0.125;
            w[i] = 1.0 / Math.pow(1 + 8 * share, 2);
            sum += w[i];
        }
        double pick = h.u() * sum;
        int s = 7;
        for (int i = 0; i < 8; i++) {
            pick -= w[i];
            if (pick <= 0) {
                s = i;
                break;
            }
        }
        return (s + h.u()) * Math.PI / 4;
    }

    private void plan() {
        if (regular) { // steady circle, same every time
            double r = R * 0.7, a0 = 0;
            if (Math.hypot(planX - cx, planY - cy) < r * 0.5) move(cx + r, cy, 0, 1);
            else a0 = angleNow();
            for (int i = 1; i <= 48; i++) {
                double a = a0 + Math.PI * 2 * i / 48;
                point(cx + Math.cos(a) * r, cy + Math.sin(a) * r);
            }
            return;
        }
        double p = h.u();
        double r = R * (h.chance(20) ? 0.35 + h.u() * 0.2 : 0.6 + h.u() * 0.45);
        if (p < 0.38) {                 // walk in a direction and keep going
            ang = h.chance(50) ? ang + h.gauss() * 0.6 : pickDirection();
            move(cx + Math.cos(ang) * r, cy + Math.sin(ang) * r, 25, h.chance(30) ? 1.5 + h.u() * 1.5 : 1);
            steerHold(h.heavy(300, 7000, c.longChance));
        } else if (p < 0.58) {          // turn around (arc)
            double a0 = Math.hypot(planX - cx, planY - cy) > R * 0.2 ? angleNow() : ang;
            double sweep = (0.5 + h.u() * 3.0) * (h.chance(50) ? 1 : -1);
            move(cx + Math.cos(a0) * r, cy + Math.sin(a0) * r, 15, 1);
            int steps = (int) Math.max(6, Math.min(160, Math.abs(sweep) * r / 5));
            double rr = r;
            for (int i = 1; i <= steps; i++) {
                double a = a0 + sweep * i / steps;
                rr = Math.max(r * 0.75, Math.min(r * 1.25, rr + h.gauss() * r * 0.015));
                point(cx + Math.cos(a) * rr, cy + Math.sin(a) * rr);
            }
            ang = a0 + sweep;
            steerHold(h.heavy(200, 3000, c.longChance));
        } else if (p < 0.68) {          // stop for a moment
            move(cx + h.gauss() * 3, cy + h.gauss() * 3, 10, 1);
            hold(h.heavy(150, 3500, c.longChance));
        } else if (p < 0.78) {          // zigzag / strafe
            int n = h.range(2, 4);
            double base = ang;
            for (int k = 0; k < n; k++) {
                double a = base + (k % 2 == 0 ? 1 : -1) * (0.35 + h.u() * 0.6);
                move(cx + Math.cos(a) * r, cy + Math.sin(a) * r, 15, 1);
                hold(h.range(120, 600));
            }
        } else {                        // sudden change of direction
            ang = pickDirection();
            move(cx + Math.cos(ang) * r, cy + Math.sin(ang) * r, 10, 1);
            steerHold(h.heavy(250, 5000, c.longChance));
        }
    }

    // ------------------------------------------------------------------ output

    private int[] next() {
        if (pending != null) {
            int[] p = pending;
            pending = null;
            return p;
        }
        for (int guard = 0; guard < 2000; guard++) {
            if (q.isEmpty()) plan();
            double[] it = q.peek();
            if (it[0] == 0 || it[3] <= 0) {
                q.poll();
                int[] p = {clx(it[1]), cly(it[2])};
                if (p[0] == px && p[1] == py) continue;
                return p;
            }
            int ax = clx(it[1]), ay = cly(it[2]);
            int nx = clx(ax + Math.max(-3, Math.min(3, Math.round(h.gauss() * 1.3))));
            int ny = cly(ay + Math.max(-3, Math.min(3, Math.round(h.gauss() * 1.3))));
            if (nx == px && ny == py) {
                it[3] -= 1;
                continue;
            }
            it[3] -= Math.hypot(nx - px, ny - py);
            return new int[]{nx, ny};
        }
        return new int[]{px, py};
    }

    private void record(double len) {
        double dx = px - cx, dy = py - cy;
        if (Math.hypot(dx, dy) < R * 0.3) return;
        double a = Math.atan2(dy, dx);
        if (a < 0) a += Math.PI * 2;
        int s = Math.min(7, (int) (a / (Math.PI / 4)));
        sector[s] += len / v;
        stat[s] += len / v;
    }

    /** Appends lineTo points to path for `ms` milliseconds of finger movement. */
    public void fill(Path path, double ms) {
        double budget = ms * v;
        int guard = 0;
        while (budget > 0.01 && guard++ < 200000) {
            int[] p = next();
            double d = Math.hypot(p[0] - px, p[1] - py);
            if (d < 0.5) {
                budget -= 0.5;
                continue;
            }
            if (d <= budget) {
                path.lineTo(p[0], p[1]);
                px = p[0];
                py = p[1];
                budget -= d;
                record(d);
            } else {
                double f = budget / d;
                int ix = (int) Math.round(px + (p[0] - px) * f), iy = (int) Math.round(py + (p[1] - py) * f);
                pending = p;
                if (ix == px && iy == py) break;
                path.lineTo(ix, iy);
                record(Math.hypot(ix - px, iy - py));
                px = ix;
                py = iy;
                break;
            }
        }
    }

    /** For tests: how much time was spent in each of the 8 directions. */
    public double[] sectors() {
        return stat.clone();
    }
}
