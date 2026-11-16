package com.humanclicker;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/** Persistence: current settings, current targets, and named profiles. */
public final class Store {
    private static final String PREFS = "hc";

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public static Config loadSettings(Context c) {
        try {
            String s = p(c).getString("settings", null);
            return s == null ? new Config() : Config.fromJson(new JSONObject(s));
        } catch (Exception e) {
            return new Config();
        }
    }

    public static void saveSettings(Context c, Config cfg) {
        p(c).edit().putString("settings", cfg.toJson().toString()).apply();
    }

    public static List<Target> loadTargets(Context c) {
        try {
            String s = p(c).getString("targets", null);
            return s == null ? new ArrayList<Target>() : Target.listFromJson(new JSONArray(s));
        } catch (Exception e) {
            return new ArrayList<Target>();
        }
    }

    public static void saveTargets(Context c, List<Target> list) {
        p(c).edit().putString("targets", Target.listToJson(list).toString()).apply();
    }

    public static boolean getFlag(Context c, String key) {
        return p(c).getBoolean(key, false);
    }

    public static void setFlag(Context c, String key, boolean v) {
        p(c).edit().putBoolean(key, v).apply();
    }

    // ---------- profiles ----------

    private static JSONObject profiles(Context c) {
        try {
            return new JSONObject(p(c).getString("profiles", "{}"));
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static List<String> profileNames(Context c) {
        List<String> out = new ArrayList<String>();
        Iterator<String> it = profiles(c).keys();
        while (it.hasNext()) out.add(it.next());
        java.util.Collections.sort(out);
        return out;
    }

    public static void saveProfile(Context c, String name) {
        try {
            JSONObject all = profiles(c);
            JSONObject o = new JSONObject();
            o.put("settings", loadSettings(c).toJson());
            o.put("targets", Target.listToJson(loadTargets(c)));
            all.put(name, o);
            p(c).edit().putString("profiles", all.toString()).apply();
        } catch (Exception ignored) {
        }
    }

    public static boolean loadProfile(Context c, String name) {
        try {
            JSONObject o = profiles(c).getJSONObject(name);
            saveSettings(c, Config.fromJson(o.optJSONObject("settings")));
            saveTargets(c, Target.listFromJson(o.optJSONArray("targets")));
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    public static void deleteProfile(Context c, String name) {
        JSONObject all = profiles(c);
        all.remove(name);
        p(c).edit().putString("profiles", all.toString()).apply();
    }
}
