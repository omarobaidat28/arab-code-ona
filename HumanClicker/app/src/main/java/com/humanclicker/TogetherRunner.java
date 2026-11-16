package com.humanclicker;

import android.accessibilityservice.GestureDescription;
import android.accessibilityservice.GestureDescription.StrokeDescription;
import android.graphics.Path;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * "All at the same time" mode: every target is its own finger with its own rhythm.
 *
 * Rules learned from real devices (S26 Ultra + Moonlight):
 *  - Android numbers injected fingers 0..9 and only frees the numbers when ALL fingers are up.
 *    So every gesture ("window") holds at most FRESH_MAX new touches and ends with all fingers
 *    up. A walk that lasts longer simply touches the stick again at the next window (in the same
 *    direction), a gap of a few milliseconds.
 *  - Continued strokes are NOT used: on real devices they were rejected most of the time.
 *  - Game sticks (e.g. Moonlight) treat two presses less than ~350 ms apart as a stick CLICK
 *    (L3 / R3). A stick finger never presses again within STICK_REPRESS ms.
 *  - A stick finger always lands near the stick center, never outside the stick.
 */
final class TogetherRunner {
    static final int FRESH_MAX = 9;
    static final long STICK_REPRESS = 450;

    private final Engine e;
    private final Engine.Host host;
    private final Config c;
    private final Human h;
    private final List<Lane> lanes = new ArrayList<Lane>();
    private long T0 = 0;   // virtual time (ms) of the start of the current window
    private int actions = 0;
    private int windowNo = 0;
    private int cancelled = 0;

    private static final class Lane {
        Target t;
        int no;
        long nextFree;
        long lastDown = -100000;
        double tempo;
        float bx, by;
        int burstLeft;
        // walking stick
        Walker walker;
        boolean inSession;
        boolean pendingStart;   // a walk was planned but did not fit: start it fresh next window
        long sessEnd;
    }

    TogetherRunner(Engine e, List<Target> targets) {
        this.e = e;
        this.host = e.host;
        this.c = e.c;
        this.h = e.h;
        for (int i = 0; i < targets.size(); i++) {
            Target t = targets.get(i);
            Lane l = new Lane();
            l.t = t;
            l.no = i + 1;
            l.nextFree = h.range(0, 1200);
            l.tempo = t.regular ? 1 : 0.8 + h.u() * 0.45;
            if (t.type == Target.HOLD) l.walker = new Walker(h, c, host.screenW(), host.screenH(), t.regular, e.radius(t));
            lanes.add(l);
        }
        HLog.i("together: " + lanes.size() + " fingers, max " + FRESH_MAX + " touches per gesture, stick re-press >= " + STICK_REPRESS + "ms");
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
            if (h.chance(c.restChance * 0.4)) { // the whole hand takes a break
                long rest = (long) (h.between(c.restMinSec, c.restMaxSec) * 1000);
                HLog.v("rest " + rest + "ms");
                host.status("استراحة " + (rest / 1000) + " ث", Engine.ST_REST);
                if (!e.sleep(rest)) break;
                for (Lane l : lanes) {
                    l.inSession = false;
                    l.pendingStart = false;
                    l.nextFree = Math.max(l.nextFree, T0) + rest + h.range(0, 1500);
                }
                T0 += rest;
                host.status(String.valueOf(actions), Engine.ST_RUN);
                continue;
            }
            window();
        }
        HLog.i("together end: windows=" + windowNo + " actions=" + actions + " cancelled=" + cancelled);
        return reason;
    }

    private boolean isStick(Lane l) {
        return l.t.type == Target.HOLD || (l.t.type == Target.LOOK && c.lookStyle == 0);
    }

    /** When this finger wants to press next (absolute virtual time). */
    private long eventTime(Lane l) {
        long t = Math.max(l.nextFree, T0);
        if (isStick(l)) t = Math.max(t, l.lastDown + STICK_REPRESS);
        return t;
    }

    // ================================================================ one window

    private void window() {
        boolean hasWalk = false;
        for (Lane l : lanes) if (l.t.type == Target.HOLD) hasWalk = true;
        long end = T0 + (hasWalk ? h.range(8000, 30000) : h.range(2500, 7000));
        int fresh = 0;
        long maxEnd = T0, lastSessStart = T0;
        List<StrokeDescription> strokes = new ArrayList<StrokeDescription>();
        Map<Lane, List<long[]>> segs = new HashMap<Lane, List<long[]>>(); // {start, sessionEnd, newSession}

        // 1. walks that go on from the previous window: touch the stick again
        for (Lane l : lanes) {
            if (l.t.type != Target.HOLD) continue;
            segs.put(l, new ArrayList<long[]>());
            if (!l.inSession) continue;
            long s = Math.max(T0, l.lastDown + STICK_REPRESS);
            if (s >= l.sessEnd - 50) {
                l.inSession = false;
                continue;
            }
            segs.get(l).add(new long[]{s, l.sessEnd, l.pendingStart ? 1 : 0});
            l.pendingStart = false;
            l.lastDown = s;
            fresh++;
            lastSessStart = Math.max(lastSessStart, s);
        }

        // 2. everything else, in time order across fingers
        Set<Lane> skip = new HashSet<Lane>();
        while (true) {
            Lane best = null;
            long bt = 0;
            for (Lane l : lanes) {
                if (skip.contains(l)) continue;
                long t = eventTime(l);
                if (t >= end) continue;
                if (best == null || t < bt) {
                    best = l;
                    bt = t;
                }
            }
            if (best == null) break;
            if (best.t.type == Target.HOLD) {                       // a new walk starts
                if (fresh + 1 > FRESH_MAX) {
                    end = Math.max(Math.max(maxEnd, bt), lastSessStart + 250);
                    break;
                }
                best.sessEnd = bt + sessionLength(best);
                best.nextFree = best.sessEnd + idleGap(best);
                best.lastDown = bt;
                best.inSession = true;
                segs.get(best).add(new long[]{bt, best.sessEnd, 1});
                fresh++;
                lastSessStart = Math.max(lastSessStart, bt);
                actions++;
                HLog.v("walk " + best.no + " starts for " + (best.sessEnd - bt) + "ms");
                continue;
            }
            List<Path> paths = new ArrayList<Path>();
            List<long[]> times = new ArrayList<long[]>();
            long d = plan(best, paths, times);
            if (fresh + paths.size() > FRESH_MAX) {
                end = Math.max(Math.max(maxEnd, bt), lastSessStart + 250);
                break;
            }
            if (bt + d > end) {
                if (strokes.isEmpty() && !hasWalk && bt == T0 && d < 58000) {
                    end = bt + d + 20;
                } else {
                    skip.add(best);
                    continue;
                }
            }
            for (int i = 0; i < paths.size(); i++) {
                long[] tm = times.get(i);
                strokes.add(new StrokeDescription(paths.get(i), bt - T0 + tm[0], tm[1]));
            }
            fresh += paths.size();
            maxEnd = Math.max(maxEnd, bt + d);
            if (isStick(best)) best.lastDown = bt;
            best.nextFree = bt + d + interval(best);
            actions++;
        }
        if (end < T0 + 50) end = T0 + 50;

        // 3. walking strokes, now that the window end is known
        for (Lane l : lanes) {
            if (l.t.type != Target.HOLD) continue;
            for (long[] sg : segs.get(l)) {
                long s = sg[0], e2 = Math.min(sg[1], end);
                if (e2 - s < 30) {
                    if (sg[1] > end) {                    // continue next window
                        l.inSession = true;
                        if (sg[2] == 1) l.pendingStart = true;
                    }
                    continue;
                }
                if (sg[2] == 1) {
                    float[] cc = l.t.regular ? new float[]{l.t.x, l.t.y} : h.jitter(l.t.x, l.t.y, c.spread * 0.3f);
                    l.walker.startSession(cc[0], cc[1]);
                } else {
                    l.walker.retouch();
                }
                Path p = new Path();
                p.moveTo(l.walker.px, l.walker.py);
                l.walker.fill(p, e2 - s);
                strokes.add(new StrokeDescription(p, s - T0, e2 - s));
                l.inSession = sg[1] > end;
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
            HLog.e("gesture build/dispatch failed", ex);
            ok = false;
        }
        long took = System.currentTimeMillis() - t0;
        windowNo++;
        String line = "W" + windowNo + " len=" + (end - T0) + "ms touches=" + strokes.size() + " -> "
                + (ok ? "OK" : "CANCELLED") + " in " + took + "ms";
        if (ok) HLog.v(line);
        else HLog.w(line + (took < 200 ? " (rejected immediately)" : " (interrupted - screen touched?)"));
        if (ok) {
            T0 = end;
        } else {
            cancelled++;
            T0 += Math.max(took, 1);
            e.sleep(150);
        }
        host.status(String.valueOf(actions), Engine.ST_RUN);
    }

    // ================================================================ finger behaviour

    /** Builds the strokes of one tap / swipe / camera turn (relative times). Returns total duration. */
    private long plan(Lane l, List<Path> paths, List<long[]> times) {
        Target t = l.t;
        if (t.type == Target.LOOK) {
            PathBuilder pb = e.buildLook(t);
            long d = Math.min(58000, pb.durationMs());
            paths.add(pb.path);
            times.add(new long[]{0, d});
            return d;
        }
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

    /** Wait after a tap / turn: own tempo, bursts, sometimes distracted. */
    private long interval(Lane l) {
        if (l.t.type == Target.LOOK) {
            if (l.t.regular) return (c.lookGapMin + c.lookGapMax) / 2;
            long g = (long) (h.delay(Math.max(1, c.lookGapMin), Math.max(1, c.lookGapMax)) * l.tempo);
            if (h.chance(20)) g = h.range(60, 250);          // two turns close together
            else if (h.chance(5)) g += h.range(2000, 5000);  // looks around a while
            return g;
        }
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
        if (h.chance(4)) base += h.range(1500, 6000);
        return Math.max(40, (long) base);
    }

    private long sessionLength(Lane l) {
        if (l.t.regular) return Math.min(58000, (c.holdMin + c.holdMax) / 2);
        return e.walkDuration();
    }

    private long idleGap(Lane l) {
        if (l.t.regular) return 500;
        double p = h.u();
        if (p < 0.70) return h.range(150, 900);
        if (p < 0.95) return h.range(1000, 4000);
        return h.range(5000, 12000);
    }
}
