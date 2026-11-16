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
    }

    public static final int ST_RUN = 0, ST_REST = 1, ST_WAIT = 2;
    private static final long MAX_GESTURE = 59000;

    private final Host host;
    private final Config c;
    private final List<Target> targets;
    private final List<TemplateMatcher.Template> templates;
    private final Human h = new Human();
    private volatile boolean running = true;
    private long startedAt;
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
        try {
            if (c.startDelaySec > 0) {
                host.status("يبدأ بعد " + c.startDelaySec + " ث", ST_WAIT);
                sleep(c.startDelaySec * 1000L);
            }
            startedAt = System.currentTimeMillis();
            if (c.mode == Config.MODE_IMAGE) reason = runImages();
            else reason = runTargets();
        } catch (Throwable t) {
            reason = "خطأ: " + t.getMessage();
        }
        running = false;
        host.stopped(reason);
    }

    // =================================================================== helpers

    private boolean sleep(long ms) {
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

    private boolean timeUp() {
        return c.timeLimitMin > 0 && System.currentTimeMillis() - startedAt >= c.timeLimitMin * 60000L;
    }

    /** >1 means slower. Fatigue grows slowly and attention comes in waves. */
    private double tired() {
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
        return dispatch(pb.path, pb.durationMs());
    }

    /** Random wandering drag around a center: moves a game character in random directions,
     *  turns, stops, sometimes for a long time, sometimes short. */
    private boolean hold(Target t) {
        float[] cc = h.jitter(t.x, t.y, c.spread * 0.5f);
        double cx = cc[0], cy = cc[1];
        long total = (long) Math.min(MAX_GESTURE - 1000, h.heavy(c.holdMin, c.holdMax, c.longChance));
        double v = 0.12 + h.u() * 0.25;
        PathBuilder pb = new PathBuilder(h, host.screenW(), host.screenH(), v);
        pb.start((float) cx, (float) cy);
        pb.dwell(h.range(40, 140));
        double ang = h.u() * Math.PI * 2;
        double R = c.wander;
        int guard = 0;
        while (pb.timeMs() < total && guard++ < 400) {
            double r = R * (0.4 + 0.6 * h.u());
            double pick = h.u();
            long remain = (long) (total - pb.timeMs());
            if (pick < 0.42) {                      // walk in a (new) direction
                ang = h.chance(55) ? ang + h.gauss() * 0.9 : h.u() * Math.PI * 2;
                pb.curveTo(cx + Math.cos(ang) * r, cy + Math.sin(ang) * r, 25);
                pb.dwell(Math.min(remain, h.heavy(150, 4000, c.longChance)));
            } else if (pick < 0.78) {               // turn around the center (circle / arc)
                double sweep = (0.4 + h.u() * 2.8) * (h.chance(50) ? 1 : -1);
                pb.arc(cx, cy, r, ang, sweep);
                ang += sweep;
                pb.dwell(Math.min(remain, h.heavy(60, 1800, c.longChance)));
            } else if (pick < 0.88) {               // stop moving for a moment
                float[] q = h.jitter((float) cx, (float) cy, 6);
                pb.curveTo(q[0], q[1], 15);
                pb.dwell(Math.min(remain, h.range(100, 800)));
            } else {                                // small correction
                pb.curveTo(pb.cx + h.gauss() * R * 0.15, pb.cy + h.gauss() * R * 0.15, 10);
                pb.dwell(Math.min(remain, h.range(80, 600)));
            }
        }
        return dispatch(pb.path, pb.durationMs());
    }

    private void perform(Target t) {
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
            int[] order = nextOrder(targets.size());
            for (int idx : order) {
                if (!running) break;
                perform(targets.get(idx));
                if (!running) break;
                humanPause();
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
