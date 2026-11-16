package com.humanclicker;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** A point on screen: a tap, a swipe (start -> end) or a hold/joystick center. */
public final class Target {
    public static final int TAP = 0;
    public static final int SWIPE = 1;
    public static final int HOLD = 2;

    public int type;
    public float x, y;   // tap point / swipe start / joystick center
    public float x2, y2; // swipe end

    public Target(int type, float x, float y, float x2, float y2) {
        this.type = type;
        this.x = x;
        this.y = y;
        this.x2 = x2;
        this.y2 = y2;
    }

    public Target copy() {
        return new Target(type, x, y, x2, y2);
    }

    JSONObject toJson() throws Exception {
        JSONObject o = new JSONObject();
        o.put("t", type);
        o.put("x", x);
        o.put("y", y);
        o.put("x2", x2);
        o.put("y2", y2);
        return o;
    }

    static Target fromJson(JSONObject o) {
        return new Target(o.optInt("t", TAP), (float) o.optDouble("x", 200), (float) o.optDouble("y", 400),
                (float) o.optDouble("x2", 400), (float) o.optDouble("y2", 400));
    }

    static JSONArray listToJson(List<Target> list) {
        JSONArray a = new JSONArray();
        for (Target t : list) {
            try {
                a.put(t.toJson());
            } catch (Exception ignored) {
            }
        }
        return a;
    }

    static List<Target> listFromJson(JSONArray a) {
        List<Target> out = new ArrayList<Target>();
        if (a == null) return out;
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) out.add(fromJson(o));
        }
        return out;
    }
}
