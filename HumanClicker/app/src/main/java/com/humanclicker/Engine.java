package com.humanclicker;

import android.accessibilityservice.GestureDescription;
import android.graphics.Bitmap;
import android.graphics.Path;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** The worker that runs on its own thread and performs human-like actions. */
public final class Engine implements Runnable {

    public interface Host {
        /** Dispatch and wait until finished. */
        boolean gesture(GestureDescription g, long timeoutMs);

        /** Blocking screenshot, or null. */
        Bitmap screenshot();

        void status(String text, int state);

        void stopped(String reason);

        int screenW();

        int screenH();

        /** A harmless point on our own panel for self tests (nothing there reacts to touches), or null. */
        int[] safePoint();
    }

    public static final int ST_RUN = 0, ST_REST = 1, ST_WAIT = 2;
    private static final long MAX_GESTURE = 59000;

    final Host host;
    final Config c;
    private final List<Target> targets;
    private final List<TemplateMatcher.Template> templates;
    final Human h = new Human();
    private volatile boolean running = true;
    long startedAt;
    private int[] prevOrder;
    private final double phase, wavePeriod;
    private int actions = 0;

    public Engine(Host host, Config cfg, List<Target> targets, List<TemplateMatcher.Template> templates) {
        this.host = host;
        this.c = cfg.sanitized();
        this.targets = targets;
        this.templates = templates;
        phase = h.u() * Math.PI * 2;
        wavePeriod = 40000 + h.u() * 80000;
    }

    public void stop() {
        running = false;
    }

    public boolean isRunning() {
        return running;
    }

    @Override
    public void run() {
        String reason = "تم الإيقاف";
        HLog.verbose = c.logVerbose;
        try {
            HLog.i("RUN start mode=" + c.mode + " order=" + c.order + " screen=" + host.screenW() + "x" + host.screenH()
                    + " targets=" + targets.size() + " settings=" + c.toJson());
            for (int i = 0; i < targets.size(); i++) {
                Target t = targets.get(i);
                HLog.i("  target " + (i + 1) + " type=" + t.type + " regular=" + t.regular + " at " + (int) t.x + "," + (int) t.y
                        + (t.type == Target.SWIPE ? " -> " + (int) t.x2 + "," + (int) t.y2 : "") + " radius=" + (int) radius(t));
            }
            if (c.startDelaySec > 0) {
                host.status("يبدأ بعد " + c.startDelaySec + " ث", ST_WAIT);
                sleep(c.startDelaySec * 1000L);
            }
            startedAt = System.currentTimeMillis();
            if (c.mode == Config.MODE_IMAGE) reason = runImages();
            else if (c.order == Config.ORDER_TOGETHER) reason = new TogetherRunner(this, targets).run();
            else reason = runTargets();
        } catch (Throwable t) {
            reason = "خطأ: " + t;
            HLog.e("engine crashed", t);
        }
        running = false;
        HLog.i("RUN end: " + reason + " actions=" + actions);
        host.stopped(reason);
    }

    // =================================================================== helpers

    boolean sleep(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (running) {
            long left = end - System.currentTimeMillis();
            if (left <= 0) return true;
            try {
                Thread.sleep(Math.min(left, 40));
            } catch (InterruptedException e) {
                running = false;
            }
        }
        return false;
    }

    boolean timeUp() {
        return c.timeLimitMin > 0 && System.currentTimeMillis() - startedAt >= c.timeLimitMin * 60000L;
    }

    /** >1 means slower. Fatigue grows slowly and attention comes in waves. */
    double tired() {
        if (!c.fatigue) return 1.0;
        double el = System.currentTimeMillis() - startedAt;
        double f = 1.0 + Math.min(0.35, el / 60000.0 * 0.008);
        f += 0.09 * Math.sin(2 * Math.PI * el / wavePeriod + phase);
        return Math.max(0.8, f);
    }

    private void humanPause() {
        long d = (long) (h.delay(c.intervalMin, c.intervalMax) * tired());
        sleep(d);
        if (running && h.chance(c.restChance)) {
            long rest = (long) (h.between(c.restMinSec, c.restMaxSec) * 1000);
            host.status("استراحة " + (rest / 1000) + " ث", ST_REST);
            sleep(rest);
            host.status(String.valueOf(actions), ST_RUN);
        }
    }

    /** Order for one round. Regular targets keep their fixed slot; only the others are randomized. */
    private int[] roundOrder() {
        int n = targets.size();
        List<Integer> free = new ArrayList<Integer>();
        for (int i = 0; i < n; i++) if (!targets.get(i).regular) free.add(i);
        int m = free.size();
        if (m == n) return nextOrder(n);
        int[] rnd = m == 0 ? new int[0] : nextOrder(m);
        List<Integer> out = new ArrayList<Integer>();
        int k = 0;
        for (int i = 0; i < n; i++) {
            if (targets.get(i).regular) {
                out.add(i);
            } else {
                int from = k * rnd.length / m, to = (k + 1) * rnd.length / m;
                for (int j = from; j < to; j++) out.add(free.get(rnd[j]));
                k++;
            }
        }
        int[] o = new int[out.size()];
        for (int i = 0; i < o.length; i++) o[i] = out.get(i);
        return o;
    }

    private int[] nextOrder(int n) {
        int[] o;
        switch (c.order) {
            case Config.ORDER_SEQ:
                o = new int[n];
                for (int i = 0; i < n; i++) o[i] = i;
                break;
            case Config.ORDER_SHUFFLE:
                o = h.permutation(n);
                for (int k = 0; k < 4 && n > 2 && same(o, prevOrder); k++) o = h.permutation(n);
                break;
            case Config.ORDER_CHAOS: {
                int len = Math.max(1, n + h.range(-1, 2));
                o = new int[len];
                for (int i = 0; i < len; i++) {
                    int pick = h.nextInt(n);
                    if (n > 1 && i >= 2 && o[i - 1] == pick && o[i - 2] == pick) pick = (pick + 1 + h.nextInt(n - 1)) % n;
                    o[i] = pick;
                }
                break;
            }
            default: { // HUMAN
                int[] base;
                if (prevOrder != null && prevOrder.length == n && h.chance(25)) {
                    base = prevOrder.clone();           // habit: almost the same order as before
                    if (n > 1) {
                        int i = h.nextInt(n - 1);
                        int t = base[i];
                        base[i] = base[i + 1];
                        base[i + 1] = t;
                    }
                } else {
                    base = h.permutation(n);
                }
                List<Integer> l = new ArrayList<Integer>();
                for (int v : base) l.add(v);
                if (h.chance(15)) {                     // repeat one (1123)
                    int i = h.nextInt(l.size());
                    l.add(i, l.get(i));
                }
                if (n > 2 && h.chance(8)) l.remove(h.nextInt(l.size())); // forget one
                if (h.chance(5)) l.add(h.nextInt(n));   // an extra one
                o = new int[l.size()];
                for (int i = 0; i < o.length; i++) o[i] = l.get(i);
            }
        }
        if (o.length == n) prevOrder = o.clone();
        return o;
    }

    private static boolean same(int[] a, int[] b) {
        if (a == null || b == null || a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }

    // =================================================================== gestures

    private boolean dispatch(Path p, long dur) {
        dur = Math.max(1, Math.min(MAX_GESTURE, dur));
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(p, 0, dur));
        return host.gesture(b.build(), dur);
    }

    public boolean tap(float x, float y, float radius) {
        float[] p = h.jitter(x, y, radius);
        double t = tired();
        long press = (long) (h.between(c.pressMin, c.pressMax) * (0.85 + 0.15 * t));
        PathBuilder pb = new PathBuilder(h, host.screenW(), host.screenH(), 0.05);
        pb.start(p[0], p[1]);
        pb.lineTo(p[0] + h.gauss() * 0.8, p[1] + h.gauss() * 0.8); // tiny finger roll
        GestureDescription.Builder b = new GestureDescription.Builder();
        b.addStroke(new GestureDescription.StrokeDescription(pb.path, 0, press));
        long total = press;
        if (h.chance(c.doubleTapChance)) {
            long gap = h.range(60, 150);
            float[] q = h.jitter(p[0], p[1], 4);
            PathBuilder p2 = new PathBuilder(h, host.screenW(), host.screenH(), 0.05);
            p2.start(q[0], q[1]);
            p2.lineTo(q[0] + h.gauss() * 0.8, q[1] + h.gauss() * 0.8);
            long press2 = (long) h.between(c.pressMin, c.pressMax);
            b.addStroke(new GestureDescription.StrokeDescription(p2.path, press + gap, press2));
            total = press + gap + press2;
        }
        return host.gesture(b.build(), total);
    }

    private boolean swipe(Target t) {
        PathBuilder pb = buildSwipe(t);
        return dispatch(pb.path, pb.durationMs());
    }

    /** Human swipe path (curved, random length, sometimes overshoot, sometimes keeps holding). */
    PathBuilder buildSwipe(Target t) {
        float[] a = h.jitter(t.x, t.y, c.spread);
        float[] e = h.jitter(t.x2, t.y2, c.spread * 1.6f);
        // sometimes a shorter / longer swipe in the same direction
        double scale = 0.8 + h.u() * 0.4;
        double ex = a[0] + (e[0] - a[0]) * scale, ey = a[1] + (e[1] - a[1]) * scale;
        double dist = Math.max(5, Math.hypot(ex - a[0], ey - a[1]));
        long moveMs = (long) (h.between(c.swipeMin, c.swipeMax) * tired());
        if (h.chance(c.longChance / 2.0)) moveMs = (long) (moveMs * (1.4 + h.u() * 1.6)); // a slow lazy swipe
        PathBuilder pb = new PathBuilder(h, host.screenW(), host.screenH(), dist / Math.max(30, moveMs));
        pb.start(a[0], a[1]);
        pb.dwell(h.range(15, 60)); // finger lands first
        if (h.chance(20)) { // overshoot a little and come back
            double ox = ex + (ex - a[0]) * 0.06 + h.gauss() * 4, oy = ey + (ey - a[1]) * 0.06 + h.gauss() * 4;
            pb.curveTo(ox, oy, c.curve);
            pb.curveTo(ex, ey, 10);
        } else {
            pb.curveTo(ex, ey, c.curve);
        }
        if (h.chance(c.swipeHoldChance)) pb.dwell(h.heavy(80, Math.max(80, c.swipeHoldMax), c.longChance));
        return pb;
    }

    /** Camera turn: short drag mostly left / right (balanced so it doesn't spin one way), rarely up / down. */
    PathBuilder buildLook(Target t) {
        if (c.lookStyle == 0) return buildStickLook(t);
        float[] a = t.regular ? new float[]{t.x, t.y} : h.jitter(t.x, t.y, c.spread * 0.5f);
        long dur = t.regular ? (c.lookMin + c.lookMax) / 2 : (long) h.between(c.lookMin, c.lookMax);
        if (!t.regular && h.chance(15)) dur = (long) (c.lookMin + (dur - c.lookMin) * 0.3); // quick flick
        boolean right;
        if (t.regular) right = t.net <= 0;
        else right = h.u() < 0.5 - 0.35 * Math.tanh(t.net / (c.lookRange * 3.0));
        double dist = t.regular ? c.lookRange * 0.6
                : c.lookRange * (h.chance(20) ? 0.15 + h.u() * 0.2 : 0.35 + h.u() * 0.65);
        double dx = right ? dist : -dist;
        double dy = t.regular ? 0 : h.gauss() * dist * 0.06;     // hands are never perfectly level
        if (!t.regular && h.chance(c.lookVertChance)) {           // rare look up / down
            dy = (h.chance(50) ? 1 : -1) * c.lookRange * (0.15 + h.u() * 0.35);
            dx *= h.u() * 0.5;
        }
        t.net += dx;
        long land = t.regular ? 30 : h.range(20, 60);
        long endHold = t.regular ? 0 : (h.chance(35) ? h.range(30, 180) : 0);
        long move = Math.max(60, dur - land - endHold);
        double len = Math.max(3, Math.hypot(dx, dy));
        PathBuilder pb = new PathBuilder(h, host.screenW(), host.screenH(), len / move);
        pb.start(a[0], a[1]);
        pb.dwell(land);
        if (t.regular) pb.lineTo(a[0] + dx, a[1] + dy);
        else pb.curveTo(a[0] + dx, a[1] + dy, 8);
        pb.dwell(endHold);
        pb.fixedMs = Math.max(c.lookMin, Math.min(c.lookMax, dur));
        return pb;
    }

    /**
     * Right stick (camera): touch the stick, push it left or right, HOLD for 0.25-1 s so the view
     * keeps turning, then let go. Left/right are balanced over time; up/down is rare.
     */
    PathBuilder buildStickLook(Target t) {
        double R = radius(t);
        float[] a = t.regular ? new float[]{t.x, t.y} : h.jitter(t.x, t.y, c.spread * 0.3f);
        long hold = t.regular ? (c.lookMin + c.lookMax) / 2 : (long) h.between(c.lookMin, c.lookMax);
        if (!t.regular && h.chance(15)) hold = (long) (c.lookMin + (hold - c.lookMin) * 0.3); // quick flick
        boolean right;
        if (t.regular) right = t.net <= 0;
        else right = h.u() < 0.5 - 0.4 * Math.tanh(t.net / (c.lookMax * 2.0));
        double ang = right ? 0 : Math.PI;
        if (!t.regular) ang += h.gauss() * 0.08;                         // never perfectly level
        if (!t.regular && h.chance(c.lookVertChance)) ang = (h.chance(50) ? -1 : 1) * Math.PI / 2 + h.gauss() * 0.15;
        double defl = t.regular ? R * 0.9 : R * (h.chance(20) ? 0.5 + h.u() * 0.25 : 0.8 + h.u() * 0.3);
        if (Math.abs(Math.cos(ang)) > 0.5) t.net += (right ? 1 : -1) * hold;
        long land = t.regular ? 25 : h.range(15, 45);
        long push = t.regular ? 70 : h.range(45, 120);
        boolean slideBack = !t.regular && h.chance(30);
        double v = defl / push;
        PathBuilder pb = new PathBuilder(h, host.screenW(), host.screenH(), v);
        pb.start(a[0], a[1]);
        pb.dwell(land);
        double tx = a[0] + Math.cos(ang) * defl, ty = a[1] + Math.sin(ang) * defl;
        if (t.regular) pb.lineTo(tx, ty);
        else pb.curveTo(tx, ty, 6);
        long back = slideBack ? push : 0;
        pb.dwell(Math.max(10, hold - push - land - back));
        if (slideBack) pb.curveTo(a[0], a[1], 6);
        pb.fixedMs = Math.round(pb.timeMs());
        return pb;
    }

    float radius(Target t) {
        return t.radius > 5 ? t.radius : c.wander;
    }

    /** How long the joystick finger stays down: mostly a few seconds, sometimes up to holdMax
     *  (about a minute), rarely a bit longer than that. */
    long walkDuration() {
        long min = c.holdMin, max = c.holdMax;
        if (h.chance(c.rareLongChance)) return (long) Math.min(120000, h.between(max, max * 1.6));
        if (h.chance(c.longChance)) return (long) (min + (max - min) * (0.33 + 0.67 * Math.pow(h.u(), 1.3)));
        return (long) (min + (max - min) * 0.33 * Math.pow(h.u(), 1.7));
    }

    /** Joystick: random human walk around the center (sequential modes, one gesture <= 58 s). */
    private boolean hold(Target t) {
        float[] cc = h.jitter(t.x, t.y, c.spread * 0.3f);
        long total = Math.min(MAX_GESTURE - 1000, walkDuration());
        Walker w = new Walker(h, c, host.screenW(), host.screenH(), false, radius(t));
        w.startSession(cc[0], cc[1]);
        Path p = new Path();
        p.moveTo(w.px, w.py);
        w.fill(p, total);
        return dispatch(p, total);
    }

    // ---------------- regular (منتظم): exact point, fixed durations, no randomness

    private void regular(Target t) {
        int W = host.screenW(), H = host.screenH();
        if (t.type == Target.SWIPE) {
            long dur = (c.swipeMin + c.swipeMax) / 2;
            PathBuilder pb = new PathBuilder(h, W, H, 1);
            pb.start(t.x, t.y);
            pb.lineTo(t.x2, t.y2);
            dispatch(pb.path, dur);
        } else if (t.type == Target.HOLD) {
            // steady full circle around the center, same every time
            PathBuilder pb = new PathBuilder(h, W, H, 1);
            pb.start(t.x, t.y);
            double r = radius(t) * 0.9;
            pb.lineTo(t.x + r, t.y);
            for (int i = 1; i <= 72; i++) {
                double a = Math.PI * 2 * i / 72;
                pb.lineTo(t.x + Math.cos(a) * r, t.y + Math.sin(a) * r);
            }
            dispatch(pb.path, Math.min(MAX_GESTURE, (c.holdMin + c.holdMax) / 2));
        } else {
            Path p = new Path();
            p.moveTo(t.x, t.y);
            dispatch(p, (c.pressMin + c.pressMax) / 2);
        }
    }

    private void perform(Target t) {
        if (t.type == Target.LOOK) {
            PathBuilder pb = buildLook(t);
            dispatch(pb.path, pb.durationMs());
            actions++;
            host.status(String.valueOf(actions), ST_RUN);
            return;
        }
        if (t.regular) {
            regular(t);
            actions++;
            host.status(String.valueOf(actions), ST_RUN);
            return;
        }
        switch (t.type) {
            case Target.SWIPE:
                swipe(t);
                break;
            case Target.HOLD:
                hold(t);
                break;
            default:
                tap(t.x, t.y, c.spread);
        }
        actions++;
        host.status(String.valueOf(actions), ST_RUN);
    }

    // =================================================================== modes

    private String runTargets() {
        if (targets.isEmpty()) return "أضف هدفاً أولاً (+)";
        host.status("0", ST_RUN);
        int cycle = 0;
        while (running) {
            if (c.cycles > 0 && cycle >= c.cycles) return "اكتملت " + cycle + " دورة";
            if (timeUp()) return "انتهى الوقت";
            int[] order = roundOrder();
            for (int idx : order) {
                if (!running) break;
                Target t = targets.get(idx);
                perform(t);
                if (!running) break;
                if (t.regular) sleep((c.intervalMin + c.intervalMax) / 2);
                else humanPause();
                if (timeUp()) break;
            }
            cycle++;
        }
        return "تم الإيقاف";
    }

    private String runImages() {
        if (templates.isEmpty()) return "لا توجد صور مفعّلة";
        host.status("بحث…", ST_RUN);
        double thr = c.threshold / 100.0;
        HashMap<String, float[]> seen = new HashMap<String, float[]>(); // id -> {x, y, count}
        int clicks = 0;
        long lastShot = 0;
        while (running) {
            if (c.cycles > 0 && clicks >= c.cycles) return "اكتملت " + clicks + " نقرة";
            if (timeUp()) return "انتهى الوقت";
            long wait = 350 - (System.currentTimeMillis() - lastShot);
            if (wait > 0) sleep(wait);
            if (!running) break;
            Bitmap shot = host.screenshot();
            lastShot = System.currentTimeMillis();
            if (shot == null) {
                sleep(500);
                continue;
            }
            TemplateMatcher.Screen sc = new TemplateMatcher.Screen(shot);
            shot.recycle();

            List<TemplateMatcher.Template> order = new ArrayList<TemplateMatcher.Template>(templates);
            if (c.order != Config.ORDER_SEQ) {
                int[] p = h.permutation(order.size());
                List<TemplateMatcher.Template> o2 = new ArrayList<TemplateMatcher.Template>();
                for (int i : p) o2.add(order.get(i));
                order = o2;
            }
            boolean clicked = false;
            for (TemplateMatcher.Template t : order) {
                if (!running) break;
                TemplateMatcher.Match m = TemplateMatcher.find(sc, t, thr, c.colorCheck);
                if (m == null) {
                    seen.remove(t.id);
                    continue;
                }
                float[] prev = seen.get(t.id);
                float tol = Math.max(10, Math.min(m.w, m.h) * 0.25f);
                int count = (prev != null && Math.abs(prev[0] - m.cx) <= tol && Math.abs(prev[1] - m.cy) <= tol)
                        ? (int) prev[2] + 1 : 1;
                seen.put(t.id, new float[]{m.cx, m.cy, count});
                if (count < c.confirmFrames) continue;
                // click safely inside the central part of the found image
                float safe = Math.max(1, Math.min(c.spread, Math.min(m.w, m.h) * 0.18f));
                tap(m.cx, m.cy, safe);
                seen.remove(t.id);
                clicks++;
                actions++;
                clicked = true;
                host.status("✓" + clicks, ST_RUN);
                if (!c.clickAllMatches) break;
                humanPause();
            }
            if (clicked) humanPause();
            else sleep((long) (h.between(c.scanMin, c.scanMax) * tired()));
        }
        return "تم الإيقاف";
    }
}
