package com.humanclicker;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/** Image templates for the image clicker (PNG files + small metadata list). */
public final class ImageStore {
    public static final class Item {
        public String id;
        public String name;
        public boolean enabled;
    }

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences("hc_images", Context.MODE_PRIVATE);
    }

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "templates");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public static File file(Context c, String id) {
        return new File(dir(c), id + ".png");
    }

    public static List<Item> list(Context c) {
        List<Item> out = new ArrayList<Item>();
        try {
            JSONArray a = new JSONArray(p(c).getString("items", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item it = new Item();
                it.id = o.getString("id");
                it.name = o.optString("name", it.id);
                it.enabled = o.optBoolean("enabled", true);
                if (file(c, it.id).exists()) out.add(it);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void save(Context c, List<Item> items) {
        JSONArray a = new JSONArray();
        for (Item it : items) {
            try {
                JSONObject o = new JSONObject();
                o.put("id", it.id);
                o.put("name", it.name);
                o.put("enabled", it.enabled);
                a.put(o);
            } catch (Exception ignored) {
            }
        }
        p(c).edit().putString("items", a.toString()).apply();
    }

    public static boolean add(Context c, Bitmap bmp) {
        String id = "img" + System.currentTimeMillis();
        try {
            FileOutputStream fos = new FileOutputStream(file(c, id));
            bmp.compress(Bitmap.CompressFormat.PNG, 100, fos);
            fos.close();
        } catch (Exception e) {
            return false;
        }
        List<Item> items = list(c);
        Item it = new Item();
        it.id = id;
        it.name = "صورة " + (items.size() + 1);
        it.enabled = true;
        items.add(it);
        save(c, items);
        return true;
    }

    public static void setEnabled(Context c, String id, boolean enabled) {
        List<Item> items = list(c);
        for (Item it : items) if (it.id.equals(id)) it.enabled = enabled;
        save(c, items);
    }

    public static void delete(Context c, String id) {
        List<Item> items = list(c);
        List<Item> keep = new ArrayList<Item>();
        for (Item it : items) if (!it.id.equals(id)) keep.add(it);
        save(c, keep);
        file(c, id).delete();
    }

    public static Bitmap load(Context c, String id) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inPreferredConfig = Bitmap.Config.ARGB_8888;
        o.inScaled = false;
        return BitmapFactory.decodeFile(file(c, id).getAbsolutePath(), o);
    }

    public static Bitmap thumb(Context c, String id, int maxSide) {
        BitmapFactory.Options b = new BitmapFactory.Options();
        b.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file(c, id).getAbsolutePath(), b);
        int s = 1;
        while (Math.max(b.outWidth, b.outHeight) / (s * 2) >= maxSide) s *= 2;
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inSampleSize = s;
        return BitmapFactory.decodeFile(file(c, id).getAbsolutePath(), o);
    }
}
