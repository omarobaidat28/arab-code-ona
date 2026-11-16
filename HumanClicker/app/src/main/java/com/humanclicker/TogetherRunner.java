package com.humanclicker;

import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.GestureDescription.StrokeDescription;
import android.graphics.Path;

import java.util.ArrayList;
import java.util.List;

/**
 * "All at the same time" mode: every target is its own finger with its own rhythm, like a real
 * player using several fingers. Time is cut into windows of a few seconds; each window is one
 * multi-touch gesture. Joystick fingers that are still walking at the end of a window stay down
 * (continued strokes), so a walk can last a long time (even more than a minute).
 */
final class TogetherRunner {
    private final Engine e;
    private final Engine.Host host;
    private final Config c;
    private final Human h;
    private final List<Lane> lanes = new ArrayList<Lane>();
    private final int maxStrokes;
    private boolean allowContinue = true;
    private int quickFails = 0;
    private long T0 = 0;   // virtual time (ms) of the start of the current window
    private int actions = 0;

    private static final class Lane {
        Target t;
        long nextFree;
        double tempo;
        float bx, by;      // slowly drifting habit offset of this finger
        int burstLeft;
        // joystick
        Walker walker;
        boolean inSession;
        long sessStart, sessEnd;
        StrokeDescription open;
    }

    TogetherRunner(Engine e, List<Target> targets) {
        this.e = e;
        this.host = e.host;
        this.c = e.c;
        this.h = e.h;
        int ms = 10;
        try {
            ms = GestureDescription.getMaxStrokeCount();
        } catch (Throwable ignored) {
        }
        maxStrokes = Math.max(4, Math.min(20, ms));
        for (Target t : targets) {
            Lane l = new Lane();
            l.t = t;
            l.nextFree = h.range(0, 1200);                 // fingers don't all start together
            l.tempo = t.regular ? 1 : 0.8 + h.u() * 0.45;  // each finger has its own speed
            if (t.type == Target.HOLD) l.walker = new Walker(h, c, host.screenW(), host.screenH(), t.regular);
            lanes.add(l);
        }
    }

    // ================================================================ main loop

    String run() {
        if (lanes.isEmpty()) return "أضف هدفاً أولاً (+)";
        String reason = "تم الإيقاف";
        host.status("0", Engine.ST_RUN);
        while (e.isRunning()) {
            if (c.cycles > 0 && actions >= c.cycles) {
                reason = "اكتملت " + actions + " حركة";
                break;
            }
            if (e.timeUp()) {
                reason = "انتهى الوقت";
                break;
            }
            long len = h.range(2500, 7000);
            if (h.chance(c.restChance * len / 10000.0)) { // the whole hand takes a break
                liftAll();
                long rest = (long) (h.between(c.restMinSec, c.restMaxSec) * 1000);
                host.status("استراحة " + (rest / 1000) + " ث", Engine.ST_REST);
                if (!e.sleep(rest)) break;
                for (Lane l : lanes) {
                    l.inSession = false;
                    l.nextFree = Math.max(l.nextFree, T0) + rest + h.range(0, 1500);
                }
                T0 += rest;
                host.status(String.valueOf(actions), Engine.ST_RUN);
                continue;
            }
            window(len);
        }
        liftAll();
        return reason;
    }

    // ================================================================ one window

    private void window(long len) {
        List<StrokeDescription> strokes = new ArrayList<StrokeDescription>();
        long end = T0 + len;
        int holdLanes = 0;
        for (Lane l : lanes) if (l.t.type == Target.HOLD) holdLanes++;
        int budget = Math.max(2, maxStrokes - 2 * holdLanes);
        long maxEnd = T0;

        // ---- taps and swipes, in time order across fingers
        List<Lane> deferred = new ArrayList<Lane>();
        while (true) {
            Lane best = null;
            for (Lane l : lanes) {
                if (l.t.type == Target.HOLD || deferred.contains(l) || l.nextFree >= end) continue;
                if (best == null || l.nextFree < best.nextFree) best = l;
            }
            if (best == null) break;
            long start = Math.max(best.nextFree, T0);
            List<Path> paths = new ArrayList<Path>();
            List<long[]> times = new ArrayList<long[]>(); // {relStart, duration}
            long d = plan(best, paths, times);
            if (start + d > end) {
                if (start == T0 && strokes.isEmpty() && d < 58000) {
                    end = T0 + d + 20;      // a long swipe at the very start: make the window fit it
                } else {
                    deferred.add(best);     // next window
                    continue;
                }
            }
            if (strokes.size() + paths.size() > budget) {
                end = Math.max(maxEnd, start);
                break;
            }
            for (int i = 0; i < paths.size(); i++) {
                long[] tm = times.get(i);
                strokes.add(new StrokeDescription(paths.get(i), start - T0 + tm[0], tm[1]));
            }
            maxEnd = Math.max(maxEnd, start + d);
            best.nextFree = start + d + interval(best);
            actions++;
        }
        if (end <= T0) end = T0 + 50;

        // ---- joystick fingers
        boolean hasContinue = false;
        for (Lane l : lanes) {
            if (l.t.type != Target.HOLD) continue;
            long cursor = T0;
            int used = 0;
            while (cursor < end && used < 2) {
                if (!l.inSession) {
                    if (l.nextFree >= end) break;
                    l.sessStart = Math.max(l.nextFree, cursor);
                    if (end - l.sessStart < 150) {
                        l.nextFree = end;
                        break;
                    }
                    l.sessEnd = l.sessStart + sessionLength(l);
                    float[] cc = l.t.regular ? new float[]{l.t.x, l.t.y} : h.jitter(l.t.x, l.t.y, c.spread * 0.3f);
                    l.walker.startSession(cc[0], cc[1]);
                    l.open = null;
                    l.inSession = true;
                    actions++;
                }
                if (l.open == null && l.sessStart >= end - 30) break;
                long segStart = l.open != null ? T0 : Math.max(l.sessStart, cursor);
                long segEnd = Math.min(l.sessEnd, end);
                long dur = Math.max(1, segEnd - segStart);
                Path p = new Path();
                p.moveTo(l.walker.px, l.walker.py);
                l.walker.fill(p, dur);
                boolean cont = allowContinue && l.sessEnd > end && segEnd == end;
                StrokeDescription sd = l.open != null
                        ? l.open.continueStroke(p, 0, dur, cont)
                        : new StrokeDescription(p, segStart - T0, dur, cont);
                if (l.open != null) hasContinue = true;
                strokes.add(sd);
                used++;
                if (cont) {
                    l.open = sd;
                } else {
                    l.open = null;
                    if (l.sessEnd <= end) {
                        l.inSession = false;
                        l.nextFree = l.sessEnd + idleGap(l);
                    } else {             // device can't keep the finger down: touch again
                        l.walker.recenter();
                        l.sessStart = end + h.range(30, 90);
                    }
                }
                cursor = segEnd;
            }
        }

        if (strokes.isEmpty()) {
            e.sleep(end - T0);
            T0 = end;
            return;
        }
        GestureDescription.Builder b = new GestureDescription.Builder();
        for (StrokeDescription sd : strokes) b.addStroke(sd);
        long t0 = System.currentTimeMillis();
        boolean ok;
        try {
            ok = host.gesture(b.build(), end - T0);
        } catch (RuntimeException ex) {
            ok = false;
        }
        long took = System.currentTimeMillis() - t0;
        T0 = end;
        if (ok) {
            quickFails = 0;
        } else {
            // interrupted (e.g. you touched the screen) -> fingers are up now
            if (hasContinue && took < 200 && ++quickFails >= 3 && allowContinue) {
                allowContinue = false;
                host.status("بدون إمساك متواصل", Engine.ST_RUN);
            }
            for (Lane l : lanes) {
                l.open = null;
                if (l.inSession) {
                    l.inSession = false;
                    l.nextFree = T0 + h.range(150, 600);
                }
            }
            e.sleep(250);
        }
        host.status(String.valueOf(actions), Engine.ST_RUN);
    }

    /** Lift every finger that is still down. */
    private void liftAll() {
        List<StrokeDescription> list = new ArrayList<StrokeDescription>();
        for (Lane l : lanes) {
            if (l.open != null) {
                Path p = new Path();
                p.moveTo(l.walker.px, l.walker.py);
                try {
                    list.add(l.open.continueStroke(p, 0, 20, false));
                } catch (RuntimeException ignored) {
                }
                l.open = null;
            }
            l.inSession = false;
        }
        if (list.isEmpty()) return;
        GestureDescription.Builder b = new GestureDescription.Builder();
        for (StrokeDescription sd : list) b.addStroke(sd);
        try {
            host.gesture(b.build(), 20);
        } catch (RuntimeException ignored) {
        }
    }

    // ================================================================ finger behaviour

    /** Builds the strokes of one tap / swipe (relative times). Returns total duration. */
    private long plan(Lane l, List<Path> paths, List<long[]> times) {
        Target t = l.t;
        if (t.type == Target.SWIPE) {
            if (t.regular) {
                Path p = new Path();
                p.moveTo(t.x, t.y);
                p.lineTo(t.x2, t.y2);
                long d = (c.swipeMin + c.swipeMax) / 2;
                paths.add(p);
                times.add(new long[]{0, d});
                return d;
            }
            PathBuilder pb = e.buildSwipe(t);
            long d = Math.min(58000, pb.durationMs());
            paths.add(pb.path);
            times.add(new long[]{0, d});
            return d;
        }
        // tap
        if (t.regular) {
            Path p = new Path();
            p.moveTo(t.x, t.y);
            long d = (c.pressMin + c.pressMax) / 2;
            paths.add(p);
            times.add(new long[]{0, d});
            return d;
        }
        // the finger's favourite spot drifts slowly, and each tap lands around it
        float lim = c.spread * 0.6f;
        l.bx = Math.max(-lim, Math.min(lim, (float) (l.bx + h.gauss() * c.spread * 0.08)));
        l.by = Math.max(-lim, Math.min(lim, (float) (l.by + h.gauss() * c.spread * 0.08)));
        float[] pt = h.jitter(t.x + l.bx, t.y + l.by, c.spread * 0.7f);
        long press = (long) (h.between(c.pressMin, c.pressMax) * (0.85 + 0.15 * e.tired()));
        paths.add(tapPath(pt[0], pt[1]));
        times.add(new long[]{0, press});
        long total = press;
        if (h.chance(c.doubleTapChance)) {
            long gap = h.range(60, 150);
            float[] q = h.jitter(pt[0], pt[1], 4);
            long press2 = (long) h.between(c.pressMin, c.pressMax);
            paths.add(tapPath(q[0], q[1]));
            times.add(new long[]{press + gap, press2});
            total = press + gap + press2;
        }
        return total;
    }

    private Path tapPath(float x, float y) {
        float mx = Math.max(1, Math.min(host.screenW() - 2, x)), my = Math.max(1, Math.min(host.screenH() - 2, y));
        Path p = new Path();
        p.moveTo(mx, my);
        p.lineTo(Math.max(1, (float) (mx + h.gauss() * 0.8)), Math.max(1, (float) (my + h.gauss() * 0.8)));
        return p;
    }

    /** Wait after a tap/swipe: own tempo, bursts of fast taps, sometimes distracted. */
    private long interval(Lane l) {
        if (l.t.regular) return (c.intervalMin + c.intervalMax) / 2;
        double base = h.delay(c.intervalMin, c.intervalMax) * l.tempo * e.tired();
        l.tempo = Math.max(0.65, Math.min(1.6, l.tempo * (1 + h.gauss() * 0.03)));
        if (l.burstLeft > 0) {
            l.burstLeft--;
            base *= 0.35 + h.u() * 0.25;
            if (l.burstLeft == 0) base *= 1.5 + h.u() * 1.5;
        } else if (h.chance(12)) {
            l.burstLeft = h.range(2, 5);
        }
        if (h.chance(4)) base += h.range(1500, 6000); // this finger is busy / distracted
        return Math.max(40, (long) base);
    }

    private long sessionLength(Lane l) {
        if (l.t.regular) return Math.min(58000, (c.holdMin + c.holdMax) / 2);
        return e.walkDuration();
    }

    /** Finger up between two walks: usually short, sometimes longer. */
    private long idleGap(Lane l) {
        if (l.t.regular) return 500;
        double p = h.u();
        if (p < 0.70) return h.range(120, 900);
        if (p < 0.95) return h.range(1000, 4000);
        return h.range(5000, 12000);
    }
}
