package com.humanclicker;

import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;

/** All user settings. Public int/boolean fields are (de)serialized automatically. */
public final class Config {
    public static final int MODE_TARGETS = 0;
    public static final int MODE_IMAGE = 1;

    public static final int ORDER_SEQ = 0;      // 123 123 123
    public static final int ORDER_SHUFFLE = 1;  // 213 321 132 (each target once per round, new order every round)
    public static final int ORDER_CHAOS = 2;    // 1123 31 2213 ... (fully random, repeats & skips)
    public static final int ORDER_HUMAN = 3;    // mostly shuffled, with human habits (repeats, skips, swaps)

    public int mode = MODE_TARGETS;
    public int order = ORDER_HUMAN;

    // Timing between actions (ms)
    public int intervalMin = 280;
    public int intervalMax = 950;
    // Finger press duration for a tap (ms)
    public int pressMin = 45;
    public int pressMax = 140;
    // Random spread around a target (px radius)
    public int spread = 18;
    // Probability (%) of an accidental-looking double tap
    public int doubleTapChance = 3;
    // Rest breaks
    public int restChance = 3;
    public int restMinSec = 2;
    public int restMaxSec = 12;
    // Gradually slower + "attention waves"
    public boolean fatigue = true;

    // Swipe
    public int swipeMin = 180;
    public int swipeMax = 950;
    public int curve = 35;               // % curvature of swipe paths
    public int swipeHoldChance = 35;     // % chance to keep finger down at the end of a swipe
    public int swipeHoldMax = 2500;      // ms (heavy tailed: sometimes short, sometimes long)

    // Hold / joystick (random wandering drag, e.g. moving a game character)
    public int holdMin = 700;
    public int holdMax = 15000;
    public int wander = 150;             // px radius around the joystick center
    public int longChance = 30;          // % chance of a long hold / long dwell

    // Stop conditions
    public int cycles = 0;               // 0 = infinite (in image mode: number of clicks)
    public int timeLimitMin = 0;         // 0 = no limit
    public int startDelaySec = 2;

    // Image mode
    public int threshold = 88;           // % similarity needed
    public int scanMin = 400;
    public int scanMax = 1100;
    public int confirmFrames = 2;        // must be seen in N consecutive scans before clicking
    public boolean colorCheck = true;
    public boolean clickAllMatches = false;

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        for (Field f : Config.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers())) continue;
            try {
                if (f.getType() == int.class) o.put(f.getName(), f.getInt(this));
                else if (f.getType() == boolean.class) o.put(f.getName(), f.getBoolean(this));
            } catch (Exception ignored) {
            }
        }
        return o;
    }

    public static Config fromJson(JSONObject o) {
        Config c = new Config();
        if (o == null) return c;
        for (Field f : Config.class.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) || !o.has(f.getName())) continue;
            try {
                if (f.getType() == int.class) f.setInt(c, o.getInt(f.getName()));
                else if (f.getType() == boolean.class) f.setBoolean(c, o.getBoolean(f.getName()));
            } catch (Exception ignored) {
            }
        }
        return c;
    }

    /** Returns a copy with sane ranges (min <= max, positive values...). */
    public Config sanitized() {
        Config c = fromJson(toJson());
        c.intervalMin = Math.max(20, c.intervalMin);
        c.intervalMax = Math.max(c.intervalMin, c.intervalMax);
        c.pressMin = Math.max(10, c.pressMin);
        c.pressMax = Math.max(c.pressMin, c.pressMax);
        c.spread = clamp(c.spread, 0, 400);
        c.doubleTapChance = clamp(c.doubleTapChance, 0, 100);
        c.restChance = clamp(c.restChance, 0, 100);
        c.restMinSec = Math.max(0, c.restMinSec);
        c.restMaxSec = Math.max(c.restMinSec, c.restMaxSec);
        c.swipeMin = Math.max(30, c.swipeMin);
        c.swipeMax = Math.max(c.swipeMin, c.swipeMax);
        c.curve = clamp(c.curve, 0, 150);
        c.swipeHoldChance = clamp(c.swipeHoldChance, 0, 100);
        c.swipeHoldMax = clamp(c.swipeHoldMax, 0, 50000);
        c.holdMin = clamp(c.holdMin, 100, 58000);
        c.holdMax = clamp(Math.max(c.holdMin, c.holdMax), 100, 58000);
        c.wander = clamp(c.wander, 10, 2000);
        c.longChance = clamp(c.longChance, 0, 100);
        c.cycles = Math.max(0, c.cycles);
        c.timeLimitMin = Math.max(0, c.timeLimitMin);
        c.startDelaySec = clamp(c.startDelaySec, 0, 600);
        c.threshold = clamp(c.threshold, 50, 100);
        c.scanMin = Math.max(350, c.scanMin);
        c.scanMax = Math.max(c.scanMin, c.scanMax);
        c.confirmFrames = clamp(c.confirmFrames, 1, 5);
        if (c.mode != MODE_IMAGE) c.mode = MODE_TARGETS;
        c.order = clamp(c.order, 0, 3);
        return c;
    }

    static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
