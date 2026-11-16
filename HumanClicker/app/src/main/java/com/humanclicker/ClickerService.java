package com.humanclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Accessibility service: floating panel, draggable targets, gesture + screenshot host. */
public final class ClickerService extends AccessibilityService implements Engine.Host {

    public static volatile ClickerService instance;

    private WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());

    private LinearLayout panel;
    private WindowManager.LayoutParams panelLp;
    private TextView btnPlay, statusView;
    private final List<View> targetButtons = new ArrayList<View>();
    private View captureButton;
    private LinesView lines;
    private FrameLayout cropRoot;
    private View markerMenu;
    private final List<View> collapsible = new ArrayList<View>();
    private boolean collapsed = false;
    private boolean pickRegular = false;
    private TextView pinButton;

    private final List<Marker> markers = new ArrayList<Marker>();
    private boolean markersHidden = false;

    private Engine engine;
    private Thread engineThread;
    private volatile int gen = 0;
    private long stopAskedAt = 0;
    private volatile int fails = 0;
    private boolean savedCollapsed;
    private int savedX, savedY;
    private int sw, sh;
    private Config cfg = new Config();

    static final class Marker {
        Target t;
        TextView a, b;
        WindowManager.LayoutParams la, lb;
    }

    // ================================================================ lifecycle

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (Store.getFlag(this, "showOnConnect")) {
            Store.setFlag(this, "showOnConnect", false);
            showPanel();
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public boolean onUnbind(Intent intent) {
        hidePanel();
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        hidePanel();
        instance = null;
        super.onDestroy();
    }

    // ================================================================ utils

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private WindowManager.LayoutParams params(int w, int h, boolean touchable) {
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        if (!touchable) flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, flags, PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.LEFT;
        if (Build.VERSION.SDK_INT >= 28) {
            p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        return p;
    }

    /** Real screen size in the CURRENT rotation (landscape games included). */
    private int[] screenSize() {
        try {
            android.hardware.display.DisplayManager dm =
                    (android.hardware.display.DisplayManager) getSystemService(Context.DISPLAY_SERVICE);
            Display d = dm.getDisplay(Display.DEFAULT_DISPLAY);
            android.graphics.Point p = new android.graphics.Point();
            d.getRealSize(p);
            if (p.x > 0 && p.y > 0) return new int[]{p.x, p.y};
        } catch (Exception ignored) {
        }
        DisplayMetrics dm = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(dm);
        return new int[]{dm.widthPixels, dm.heightPixels};
    }

    private boolean landscape() {
        int[] ss = screenSize();
        return ss[0] > ss[1];
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        main.postDelayed(new Runnable() {
            public void run() {
                orientPanel();
            }
        }, 300);
    }

    private void orientPanel() {
        if (panel == null) return;
        panel.setOrientation(landscape() ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        int[] ss = screenSize();
        panelLp.x = Math.max(0, Math.min(panelLp.x, ss[0] - dp(60)));
        panelLp.y = Math.max(0, Math.min(panelLp.y, ss[1] - dp(60)));
        try {
            wm.updateViewLayout(panel, panelLp);
        } catch (Exception ignored) {
        }
    }

    private void setCollapsed(boolean c) {
        collapsed = c;
        for (View v : collapsible) v.setVisibility(c ? View.GONE : View.VISIBLE);
        if (!c) applyMode();
    }

    private static GradientDrawable circle(int color, int strokeColor, int stroke) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        d.setStroke(stroke, strokeColor);
        return d;
    }

    private void safeRemove(View v) {
        if (v == null) return;
        try {
            wm.removeView(v);
        } catch (Exception ignored) {
        }
    }

    public boolean isPanelShown() {
        return panel != null;
    }

    public boolean isRunning() {
        return engine != null && engine.isRunning();
    }

    // ================================================================ panel

    public void showPanel() {
        if (panel != null || wm == null) return;
        cfg = Store.loadSettings(this);

        lines = new LinesView(this);
        wm.addView(lines, params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, false));

        for (Target t : Store.loadTargets(this)) addMarker(t);

        panel = new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE61B2430);
        bg.setCornerRadius(dp(14));
        bg.setStroke(dp(1), 0x664FC3F7);
        panel.setBackground(bg);
        panel.setPadding(dp(3), dp(4), dp(3), dp(4));

        TextView handle = button("⠿", 0x00000000);
        handle.setOnTouchListener(new PanelDrag());
        panel.addView(handle);

        btnPlay = button("▶", 0xFF2E7D32);
        btnPlay.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (isRunning()) stopRun();
                else startRun();
            }
        });
        panel.addView(btnPlay);

        statusView = new TextView(this);
        statusView.setTextColor(0xFFB0BEC5);
        statusView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 9);
        statusView.setGravity(Gravity.CENTER);
        statusView.setMaxLines(2);
        statusView.setLayoutParams(new LinearLayout.LayoutParams(dp(44), ViewGroup.LayoutParams.WRAP_CONTENT));
        panel.addView(statusView);

        targetButtons.clear();
        TextView addTap = button("＋", 0xFF1565C0);
        addTap.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                addNew(Target.TAP);
            }
        });
        TextView addSwipe = button("⇆", 0xFF2E7D32);
        addSwipe.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                addNew(Target.SWIPE);
            }
        });
        TextView addHold = button("🕹", 0xFFEF6C00);
        addHold.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                addNew(Target.HOLD);
            }
        });
        TextView addLook = button("↔", 0xFF00838F);
        addLook.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                addNew(Target.LOOK);
            }
        });
        TextView remove = button("－", 0xFF6D4C41);
        remove.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                if (!markers.isEmpty()) removeMarker(markers.get(markers.size() - 1));
            }
        });
        pinButton = button("📌", 0xFF8D6E00);
        pinButton.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                setPickRegular(!pickRegular);
            }
        });
        TextView eye = button("👁", 0xFF455A64);
        eye.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                markersHidden = !markersHidden;
                applyMarkerVisibility();
            }
        });
        for (TextView b : new TextView[]{addTap, addSwipe, addHold, addLook, pinButton, remove, eye}) {
            panel.addView(b);
            targetButtons.add(b);
        }

        TextView cap = button("📷", 0xFF6A1B9A);
        cap.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                captureTemplate();
            }
        });
        captureButton = cap;
        panel.addView(cap);

        TextView settings = button("⚙", 0xFF37474F);
        settings.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(ClickerService.this, MainActivity.class);
                i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
                startActivity(i);
            }
        });
        panel.addView(settings);

        TextView close = button("✕", 0xFFB71C1C);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                hidePanel();
            }
        });
        panel.addView(close);

        collapsible.clear();
        collapsible.addAll(targetButtons);
        collapsible.add(cap);
        collapsible.add(settings);
        collapsible.add(close);
        collapsed = false;
        pickRegular = false;
        panel.setOrientation(landscape() ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);

        panelLp = params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true);
        int[] ss = screenSize();
        panelLp.x = 0;
        panelLp.y = ss[1] / 5;
        wm.addView(panel, panelLp);
        applyMode();
        updatePlay(false, ST_IDLE);
    }

    private static final int ST_IDLE = -1;

    private TextView button(String text, int color) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        b.setGravity(Gravity.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(10));
        b.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(40), dp(40));
        lp.setMargins(dp(2), dp(2), dp(2), dp(2));
        b.setLayoutParams(lp);
        return b;
    }

    public void hidePanel() {
        if (engine != null) engine.stop();
        gen++; // cut off any running engine immediately
        if (panel != null) saveTargets();
        safeRemove(cropRoot);
        cropRoot = null;
        closeMarkerMenu();
        for (Marker m : markers) {
            safeRemove(m.a);
            safeRemove(m.b);
        }
        markers.clear();
        safeRemove(lines);
        lines = null;
        safeRemove(panel);
        panel = null;
    }

    /** Called by the settings screen whenever something changes. */
    public void onSettingsChanged() {
        cfg = Store.loadSettings(this);
        applyMode();
        if (lines != null) lines.invalidate();
    }

    /** Called after a profile was loaded: rebuild markers from storage. */
    public void reloadTargets() {
        if (panel == null) return;
        for (Marker m : markers) {
            safeRemove(m.a);
            safeRemove(m.b);
        }
        markers.clear();
        for (Target t : Store.loadTargets(this)) addMarker(t);
        // keep panel on top
        safeRemove(panel);
        wm.addView(panel, panelLp);
        onSettingsChanged();
    }

    private void applyMode() {
        if (panel == null) return;
        boolean img = cfg.mode == Config.MODE_IMAGE;
        for (View v : targetButtons) v.setVisibility(img || collapsed ? View.GONE : View.VISIBLE);
        applyMarkerVisibility();
        if (statusView != null && !isRunning()) statusView.setText(img ? "صورة" : "نقر");
    }

    private void applyMarkerVisibility() {
        boolean hide = markersHidden || cfg.mode == Config.MODE_IMAGE;
        int vis = hide ? View.INVISIBLE : View.VISIBLE;
        for (Marker m : markers) {
            m.a.setVisibility(vis);
            if (m.b != null) m.b.setVisibility(vis);
        }
        if (lines != null) lines.setVisibility(vis);
    }

    private final class PanelDrag implements View.OnTouchListener {
        float dx, dy;
        int sx, sy;
        boolean moved;

        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    dx = e.getRawX();
                    dy = e.getRawY();
                    sx = panelLp.x;
                    sy = panelLp.y;
                    moved = false;
                    return true;
                case MotionEvent.ACTION_UP:
                    if (!moved) setCollapsed(!collapsed); // tap the handle = small / full panel
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if (Math.hypot(e.getRawX() - dx, e.getRawY() - dy) > dp(6)) moved = true;
                    panelLp.x = (int) (sx + e.getRawX() - dx);
                    panelLp.y = (int) (sy + e.getRawY() - dy);
                    try {
                        wm.updateViewLayout(panel, panelLp);
                    } catch (Exception ignored) {
                    }
                    return true;
            }
            return true;
        }
    }

    // ================================================================ markers

    private int markerSize() {
        return dp(46);
    }

    private void addNew(int type) {
        if (isRunning()) {
            toast("أوقف التشغيل أولاً");
            return;
        }
        int[] ss = screenSize();
        float cx = ss[0] / 2f + (markers.size() % 4) * dp(24) - dp(36);
        float cy = ss[1] / 2f + (markers.size() / 4 % 4) * dp(24) - dp(36);
        Target t = new Target(type, cx, cy, cx + dp(150), cy);
        if (type == Target.SWIPE) {
            t.x = cx - dp(75);
            t.x2 = cx + dp(75);
        }
        addMarker(t);
        if (markersHidden) {
            markersHidden = false;
            applyMarkerVisibility();
        }
        saveTargets();
        // keep the panel above the new marker
        safeRemove(panel);
        wm.addView(panel, panelLp);
    }

    private void addMarker(Target t) {
        Marker m = new Marker();
        m.t = t.copy();
        int size = markerSize();
        m.a = markerView(circle(0, Color.WHITE, dp(2)));
        m.la = params(size, size, true);
        m.la.x = (int) (t.x - size / 2f);
        m.la.y = (int) (t.y - size / 2f);
        m.a.setOnTouchListener(new MarkerTouch(m, false));
        wm.addView(m.a, m.la);
        if (t.type == Target.SWIPE) {
            m.b = markerView(circle(0x8843A047, 0xFFC8E6C9, dp(3)));
            m.lb = params(size, size, true);
            m.lb.x = (int) (t.x2 - size / 2f);
            m.lb.y = (int) (t.y2 - size / 2f);
            m.b.setOnTouchListener(new MarkerTouch(m, true));
            wm.addView(m.b, m.lb);
        }
        markers.add(m);
        style(m);
        renumber();
        if (lines != null) lines.invalidate();
    }

    /** Colors: regular (منتظم) targets get a gold border. */
    private void style(Marker m) {
        int color = m.t.type == Target.TAP ? 0xCC1E88E5 : (m.t.type == Target.SWIPE ? 0xCC43A047
                : (m.t.type == Target.LOOK ? 0xCC00ACC1 : 0xCCFB8C00));
        int stroke = m.t.regular ? 0xFFFFD600 : Color.WHITE;
        int w = m.t.regular ? dp(4) : dp(2);
        m.a.setBackground(circle(color, stroke, w));
        if (m.b != null) m.b.setBackground(circle(0x8843A047, m.t.regular ? 0xFFFFD600 : 0xFFC8E6C9, dp(3)));
    }

    private TextView markerView(GradientDrawable bg) {
        TextView v = new TextView(this);
        v.setBackground(bg);
        v.setTextColor(Color.WHITE);
        v.setGravity(Gravity.CENTER);
        v.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        v.setShadowLayer(3, 0, 0, Color.BLACK);
        return v;
    }

    private void renumber() {
        for (int i = 0; i < markers.size(); i++) {
            Marker m = markers.get(i);
            String n = (m.t.regular ? "📌" : "") + (i + 1);
            if (m.t.type == Target.HOLD) m.a.setText("🕹" + n);
            else if (m.t.type == Target.LOOK) m.a.setText("↔" + n);
            else m.a.setText(n);
            if (m.b != null) m.b.setText(n + "▸");
        }
    }

    private void removeMarker(Marker m) {
        if (isRunning()) {
            toast("أوقف التشغيل أولاً");
            return;
        }
        safeRemove(m.a);
        safeRemove(m.b);
        markers.remove(m);
        renumber();
        saveTargets();
        if (lines != null) lines.invalidate();
    }

    private void syncFromViews() {
        int half = markerSize() / 2;
        for (Marker m : markers) {
            m.t.x = m.la.x + half;
            m.t.y = m.la.y + half;
            if (m.lb != null) {
                m.t.x2 = m.lb.x + half;
                m.t.y2 = m.lb.y + half;
            }
        }
    }

    private void saveTargets() {
        syncFromViews();
        List<Target> list = new ArrayList<Target>();
        for (Marker m : markers) list.add(m.t.copy());
        Store.saveTargets(this, list);
    }

    /** Real on-screen coordinates of each target, used for the gestures. */
    private List<Target> runtimeTargets() {
        List<Target> out = new ArrayList<Target>();
        int[] loc = new int[2];
        for (Marker m : markers) {
            Target t = m.t.copy();
            m.a.getLocationOnScreen(loc);
            t.x = loc[0] + m.a.getWidth() / 2f;
            t.y = loc[1] + m.a.getHeight() / 2f;
            if (m.b != null) {
                m.b.getLocationOnScreen(loc);
                t.x2 = loc[0] + m.b.getWidth() / 2f;
                t.y2 = loc[1] + m.b.getHeight() / 2f;
            }
            out.add(t);
        }
        return out;
    }

    private void setMarkersTouchable(boolean touchable) {
        for (Marker m : markers) {
            setTouchable(m.a, m.la, touchable);
            if (m.b != null) setTouchable(m.b, m.lb, touchable);
        }
    }

    private void setTouchable(View v, WindowManager.LayoutParams lp, boolean touchable) {
        if (touchable) lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        try {
            wm.updateViewLayout(v, lp);
        } catch (Exception ignored) {
        }
    }

    private final class MarkerTouch implements View.OnTouchListener {
        final Marker m;
        final boolean end;
        float rx, ry;
        int sx, sy;
        boolean moved;
        final Runnable longPress = new Runnable() {
            public void run() {
                if (!moved) showMarkerMenu(m);
            }
        };

        MarkerTouch(Marker m, boolean end) {
            this.m = m;
            this.end = end;
        }

        public boolean onTouch(View v, MotionEvent e) {
            WindowManager.LayoutParams lp = end ? m.lb : m.la;
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    rx = e.getRawX();
                    ry = e.getRawY();
                    sx = lp.x;
                    sy = lp.y;
                    moved = false;
                    main.postDelayed(longPress, 800);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - rx, dy = e.getRawY() - ry;
                    if (!moved && Math.hypot(dx, dy) > dp(6)) {
                        moved = true;
                        main.removeCallbacks(longPress);
                    }
                    if (moved) {
                        int[] ss = screenSize();
                        int half = markerSize() / 2;
                        lp.x = (int) Math.max(-half, Math.min(ss[0] - half, sx + dx));
                        lp.y = (int) Math.max(-half, Math.min(ss[1] - half, sy + dy));
                        try {
                            wm.updateViewLayout(v, lp);
                        } catch (Exception ignored) {
                        }
                        if (lines != null) lines.invalidate();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    main.removeCallbacks(longPress);
                    if (moved) saveTargets();
                    else if (pickRegular && e.getActionMasked() == MotionEvent.ACTION_UP) toggleRegular(m);
                    return true;
            }
            return true;
        }
    }

    /** Small menu shown on long press: regular / random, delete. */
    private void showMarkerMenu(final Marker m) {
        closeMarkerMenu();
        if (isRunning()) {
            toast("أوقف التشغيل أولاً");
            return;
        }
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF01B2430);
        bg.setCornerRadius(dp(12));
        bg.setStroke(dp(1), 0x884FC3F7);
        box.setBackground(bg);
        box.setPadding(dp(6), dp(6), dp(6), dp(6));

        TextView title = new TextView(this);
        title.setText("الهدف " + (markers.indexOf(m) + 1) + (m.t.regular ? " — منتظم 📌" : " — عشوائي 🎲"));
        title.setTextColor(0xFFB0BEC5);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        title.setPadding(dp(4), 0, dp(4), dp(4));
        box.addView(title);

        TextView toggle = menuItem(m.t.regular ? "🎲 اجعله عشوائي" : "📌 اجعله منتظم", 0xFF8D6E00);
        toggle.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeMarkerMenu();
                toggleRegular(m);
            }
        });
        box.addView(toggle);
        TextView del = menuItem("🗑 حذف", 0xFFB71C1C);
        del.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeMarkerMenu();
                removeMarker(m);
            }
        });
        box.addView(del);
        TextView close = menuItem("✕ إغلاق", 0xFF455A64);
        close.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeMarkerMenu();
            }
        });
        box.addView(close);

        WindowManager.LayoutParams lp = params(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, true);
        int[] ss = screenSize();
        lp.x = Math.max(0, Math.min(ss[0] - dp(190), m.la.x + markerSize()));
        lp.y = Math.max(0, Math.min(ss[1] - dp(200), m.la.y));
        markerMenu = box;
        wm.addView(box, lp);
    }

    private void toggleRegular(Marker m) {
        if (isRunning()) {
            toast("أوقف التشغيل أولاً");
            return;
        }
        m.t.regular = !m.t.regular;
        style(m);
        renumber();
        saveTargets();
        toast("الهدف " + (markers.indexOf(m) + 1) + (m.t.regular
                ? ": منتظم 📌 (نفس المكان ونفس التوقيت)" : ": عشوائي 🎲"));
    }

    private void setPickRegular(boolean on) {
        pickRegular = on;
        if (pinButton != null) {
            GradientDrawable d = (GradientDrawable) pinButton.getBackground();
            d.setColor(on ? 0xFFFFD600 : 0xFF8D6E00);
        }
        if (on) toast("📌 اضغط على أي هدف لتجعله منتظم أو عشوائي، ثم اضغط 📌 للإنهاء");
    }

    private TextView menuItem(String text, int color) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setTextColor(Color.WHITE);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        b.setGravity(Gravity.CENTER);
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(8));
        b.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(170), dp(42));
        lp.setMargins(0, dp(3), 0, dp(3));
        b.setLayoutParams(lp);
        return b;
    }

    private void closeMarkerMenu() {
        safeRemove(markerMenu);
        markerMenu = null;
    }

    /** Non-touchable full screen layer that draws swipe arrows and joystick ranges. */
    static final class LinesView extends View {
        final ClickerService s;
        final Paint line = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);

        LinesView(ClickerService s) {
            super(s);
            this.s = s;
            line.setColor(0xCC81C784);
            line.setStrokeWidth(s.dp(3));
            line.setStyle(Paint.Style.STROKE);
            ring.setColor(0xAAFFB74D);
            ring.setStrokeWidth(s.dp(2));
            ring.setStyle(Paint.Style.STROKE);
            ring.setPathEffect(new DashPathEffect(new float[]{s.dp(8), s.dp(6)}, 0));
        }

        @Override
        protected void onDraw(Canvas c) {
            int half = s.markerSize() / 2;
            for (Marker m : s.markers) {
                float ax = m.la.x + half, ay = m.la.y + half;
                if (m.t.type == Target.SWIPE && m.lb != null) {
                    float bx = m.lb.x + half, by = m.lb.y + half;
                    c.drawLine(ax, ay, bx, by, line);
                    double ang = Math.atan2(by - ay, bx - ax);
                    float al = s.dp(14);
                    float ex = (float) (bx - Math.cos(ang) * half), ey = (float) (by - Math.sin(ang) * half);
                    c.drawLine(ex, ey, (float) (ex - Math.cos(ang - 0.5) * al), (float) (ey - Math.sin(ang - 0.5) * al), line);
                    c.drawLine(ex, ey, (float) (ex - Math.cos(ang + 0.5) * al), (float) (ey - Math.sin(ang + 0.5) * al), line);
                } else if (m.t.type == Target.HOLD) {
                    c.drawCircle(ax, ay, s.cfg.wander, ring);
                } else if (m.t.type == Target.LOOK) {
                    float r = s.cfg.lookRange, al = s.dp(12);
                    c.drawLine(ax - r, ay, ax + r, ay, ring);
                    c.drawLine(ax - r, ay, ax - r + al, ay - al * 0.7f, ring);
                    c.drawLine(ax - r, ay, ax - r + al, ay + al * 0.7f, ring);
                    c.drawLine(ax + r, ay, ax + r - al, ay - al * 0.7f, ring);
                    c.drawLine(ax + r, ay, ax + r - al, ay + al * 0.7f, ring);
                }
            }
        }
    }

    // ================================================================ run / stop

    private void startRun() {
        if (isRunning()) return;
        if (engineThread != null && engineThread.isAlive() && System.currentTimeMillis() - stopAskedAt < 3000) {
            toast("لحظة… يتم الإيقاف");
            return;
        }
        if (cropRoot != null) return;
        closeMarkerMenu();
        if (pickRegular) setPickRegular(false);
        cfg = Store.loadSettings(this);
        int[] ss = screenSize();
        sw = ss[0];
        sh = ss[1];
        List<Target> rt = new ArrayList<Target>();
        List<TemplateMatcher.Template> tmpl = new ArrayList<TemplateMatcher.Template>();
        if (cfg.mode == Config.MODE_IMAGE) {
            if (Build.VERSION.SDK_INT < 30) {
                toast("وضع الصورة يحتاج أندرويد 11 أو أحدث");
                return;
            }
            for (ImageStore.Item it : ImageStore.list(this)) {
                if (!it.enabled) continue;
                Bitmap b = ImageStore.load(this, it.id);
                if (b == null) continue;
                TemplateMatcher.Template t = new TemplateMatcher.Template(it.id, b);
                b.recycle();
                if (t.isFlat()) {
                    toast(it.name + ": الصورة بلا تفاصيل، تم تجاهلها");
                    continue;
                }
                tmpl.add(t);
            }
            if (tmpl.isEmpty()) {
                toast("لا توجد صور مفعّلة. اضغط 📷 لالتقاط صورة");
                return;
            }
        } else {
            saveTargets();
            rt = runtimeTargets();
            if (rt.isEmpty()) {
                toast("أضف هدفاً أولاً (＋ ⇆ 🕹 ↔)");
                return;
            }
        }
        setMarkersTouchable(false);
        final int g = ++gen;
        fails = 0;
        engine = new Engine(new RunHost(g), cfg, rt, tmpl);
        engineThread = new Thread(engine, "human-engine");
        engineThread.setPriority(Thread.NORM_PRIORITY + 1);
        engineThread.start();
        updatePlay(true, Engine.ST_WAIT);
        // small panel, moved away from every target so the automation can never press it
        savedCollapsed = collapsed;
        savedX = panelLp.x;
        savedY = panelLp.y;
        setCollapsed(true);
        main.post(new Runnable() {
            public void run() {
                if (g == gen) movePanelAwayFromTargets();
            }
        });
    }

    private void stopRun() {
        if (engine == null) return;
        engine.stop();
        stopAskedAt = System.currentTimeMillis();
        if (btnPlay != null) {
            btnPlay.setText("…");
            ((GradientDrawable) btnPlay.getBackground()).setColor(0xFF616161);
        }
    }

    /** Called on the main thread when the run with generation g has really finished. */
    private void finishRun(int g, String reason) {
        if (g != gen) return; // an old run that was replaced: ignore it
        engine = null;
        setMarkersTouchable(true);
        updatePlay(false, ST_IDLE);
        if (statusView != null) statusView.setText(cfg.mode == Config.MODE_IMAGE ? "صورة" : "نقر");
        if (panel != null) {
            panelLp.x = savedX;
            panelLp.y = savedY;
            try {
                wm.updateViewLayout(panel, panelLp);
            } catch (Exception ignored) {
            }
            setCollapsed(savedCollapsed);
            toast(reason + (fails > 0 ? " (" + fails + " حركة انقطعت)" : ""));
        }
    }

    /** Area (in overlay coordinates) that a target's finger can reach. */
    private Rect reach(Marker m) {
        int half = markerSize() / 2;
        int x = m.la.x + half, y = m.la.y + half;
        int r;
        Rect out;
        switch (m.t.type) {
            case Target.HOLD:
                r = (int) (cfg.wander * 1.4f + cfg.spread);
                out = new Rect(x - r, y - r, x + r, y + r);
                break;
            case Target.LOOK:
                r = cfg.lookRange + cfg.spread + half;
                out = new Rect(x - r, y - r / 2, x + r, y + r / 2);
                break;
            case Target.SWIPE: {
                int x2 = m.lb.x + half, y2 = m.lb.y + half, e = (int) (cfg.spread * 1.8f) + half;
                out = new Rect(Math.min(x, x2) - e, Math.min(y, y2) - e, Math.max(x, x2) + e, Math.max(y, y2) + e);
                break;
            }
            default:
                r = cfg.spread + half;
                out = new Rect(x - r, y - r, x + r, y + r);
        }
        return out;
    }

    private void movePanelAwayFromTargets() {
        if (panel == null) return;
        panel.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int pw = panel.getMeasuredWidth(), ph = panel.getMeasuredHeight();
        int[] ss = screenSize();
        int m = dp(6);
        int[][] cand = {
                {panelLp.x, panelLp.y},
                {(ss[0] - pw) / 2, m}, {m, m}, {ss[0] - pw - m, m},
                {m, (ss[1] - ph) / 2}, {ss[0] - pw - m, (ss[1] - ph) / 2},
                {(ss[0] - pw) / 2, ss[1] - ph - m}, {m, ss[1] - ph - m}, {ss[0] - pw - m, ss[1] - ph - m}};
        List<Rect> areas = new ArrayList<Rect>();
        if (cfg.mode != Config.MODE_IMAGE) for (Marker mk : markers) areas.add(reach(mk));
        int bestI = 0;
        long bestOverlap = Long.MAX_VALUE;
        for (int i = 0; i < cand.length; i++) {
            Rect pr = new Rect(cand[i][0], cand[i][1], cand[i][0] + pw, cand[i][1] + ph);
            long ov = 0;
            for (Rect a : areas) {
                Rect x = new Rect(pr);
                if (x.intersect(a)) ov += (long) x.width() * x.height();
            }
            if (ov < bestOverlap) {
                bestOverlap = ov;
                bestI = i;
                if (ov == 0) break;
            }
        }
        panelLp.x = cand[bestI][0];
        panelLp.y = cand[bestI][1];
        try {
            wm.updateViewLayout(panel, panelLp);
        } catch (Exception ignored) {
        }
        if (bestOverlap > 0) toast("⚠ اللوحة قريبة من الأهداف، حركها لمكان فاضي");
    }

    /** Host given to each run; once a newer run starts, the old one is cut off completely. */
    private final class RunHost implements Engine.Host {
        final int g;

        RunHost(int g) {
            this.g = g;
        }

        public boolean gesture(GestureDescription d, long timeoutMs) {
            if (g != gen) return false;
            boolean ok = ClickerService.this.gesture(d, timeoutMs);
            if (!ok && g == gen) fails++;
            return ok;
        }

        public Bitmap screenshot() {
            return g == gen ? ClickerService.this.screenshot() : null;
        }

        public void status(final String text, final int state) {
            main.post(new Runnable() {
                public void run() {
                    if (g != gen || engine == null || !engine.isRunning()) return;
                    if (statusView != null) statusView.setText(text);
                    updatePlay(true, state);
                }
            });
        }

        public void stopped(final String reason) {
            main.post(new Runnable() {
                public void run() {
                    finishRun(g, reason);
                }
            });
        }

        public int screenW() {
            return sw;
        }

        public int screenH() {
            return sh;
        }
    }

    private void updatePlay(boolean running, int state) {
        if (btnPlay == null) return;
        GradientDrawable d = (GradientDrawable) btnPlay.getBackground();
        if (!running) {
            btnPlay.setText("▶");
            d.setColor(0xFF2E7D32);
        } else {
            btnPlay.setText("■");
            d.setColor(state == Engine.ST_REST ? 0xFFF9A825 : (state == Engine.ST_WAIT ? 0xFF1565C0 : 0xFFC62828));
        }
    }

    // ================================================================ Engine.Host

    @Override
    public boolean gesture(GestureDescription g, long timeoutMs) {
        final CountDownLatch latch = new CountDownLatch(1);
        final boolean[] ok = {false};
        boolean sent;
        try {
            sent = dispatchGesture(g, new GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription gestureDescription) {
                    ok[0] = true;
                    latch.countDown();
                }

                @Override
                public void onCancelled(GestureDescription gestureDescription) {
                    latch.countDown();
                }
            }, main);
        } catch (Exception e) {
            return false;
        }
        if (!sent) return false;
        try {
            latch.await(timeoutMs + 4000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ignored) {
        }
        return ok[0];
    }

    @Override
    public Bitmap screenshot() {
        if (Build.VERSION.SDK_INT < 30) return null;
        for (int attempt = 0; attempt < 3; attempt++) {
            final CountDownLatch latch = new CountDownLatch(1);
            final Bitmap[] out = {null};
            final int[] err = {0};
            takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                @Override
                public void onSuccess(ScreenshotResult r) {
                    out[0] = toSoftware(r);
                    latch.countDown();
                }

                @Override
                public void onFailure(int errorCode) {
                    err[0] = errorCode;
                    latch.countDown();
                }
            });
            try {
                latch.await(4, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                return null;
            }
            if (out[0] != null) return out[0];
            if (err[0] != ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT) return null;
            try {
                Thread.sleep(400);
            } catch (InterruptedException e) {
                return null;
            }
        }
        return null;
    }

    private static Bitmap toSoftware(ScreenshotResult r) {
        HardwareBuffer hb = r.getHardwareBuffer();
        try {
            Bitmap hw = Bitmap.wrapHardwareBuffer(hb, r.getColorSpace());
            if (hw == null) return null;
            Bitmap sw = hw.copy(Bitmap.Config.ARGB_8888, false);
            hw.recycle();
            return sw;
        } catch (Exception e) {
            return null;
        } finally {
            hb.close();
        }
    }

    @Override
    public void status(final String text, final int state) {
        main.post(new Runnable() {
            public void run() {
                if (statusView != null) statusView.setText(text);
                updatePlay(true, state);
            }
        });
    }

    @Override
    public void stopped(final String reason) {
        main.post(new Runnable() {
            public void run() {
                setMarkersTouchable(true);
                updatePlay(false, ST_IDLE);
                if (statusView != null) statusView.setText(cfg.mode == Config.MODE_IMAGE ? "صورة" : "نقر");
                if (panel != null) toast(reason);
            }
        });
    }

    @Override
    public int screenW() {
        return sw;
    }

    @Override
    public int screenH() {
        return sh;
    }

    // ================================================================ image capture

    private void setOverlaysVisible(boolean visible) {
        if (panel != null) panel.setVisibility(visible ? View.VISIBLE : View.INVISIBLE);
        if (visible) applyMarkerVisibility();
        else {
            for (Marker m : markers) {
                m.a.setVisibility(View.INVISIBLE);
                if (m.b != null) m.b.setVisibility(View.INVISIBLE);
            }
            if (lines != null) lines.setVisibility(View.INVISIBLE);
        }
    }

    private void captureTemplate() {
        if (Build.VERSION.SDK_INT < 30) {
            toast("التقاط الصورة يحتاج أندرويد 11 أو أحدث. أضف صورة من المعرض بدلاً من ذلك");
            return;
        }
        if (isRunning()) {
            toast("أوقف التشغيل أولاً");
            return;
        }
        setOverlaysVisible(false);
        main.postDelayed(new Runnable() {
            public void run() {
                takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(), new TakeScreenshotCallback() {
                    @Override
                    public void onSuccess(ScreenshotResult r) {
                        Bitmap b = toSoftware(r);
                        setOverlaysVisible(true);
                        if (b == null) toast("فشل التقاط الشاشة");
                        else showCrop(b);
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        setOverlaysVisible(true);
                        toast("فشل التقاط الشاشة (" + errorCode + ")");
                    }
                });
            }
        }, 400);
    }

    private void showCrop(final Bitmap shot) {
        if (cropRoot != null) safeRemove(cropRoot);
        cropRoot = new FrameLayout(this);
        final CropView cv = new CropView(this, shot, true);
        cropRoot.addView(cv, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.VERTICAL);
        bar.setGravity(Gravity.CENTER_HORIZONTAL);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xEE1B2430);
        bg.setCornerRadius(dp(16));
        bar.setBackground(bg);
        bar.setPadding(dp(12), dp(8), dp(12), dp(8));
        TextView hint = new TextView(this);
        hint.setText("اسحب إصبعك حول الشيء الذي تريد الضغط عليه");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        bar.addView(hint);
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER);
        TextView save = button("✓", 0xFF2E7D32);
        TextView cancel = button("✕", 0xFFB71C1C);
        TextView move = button("⇅", 0xFF455A64);
        LinearLayout.LayoutParams wlp = new LinearLayout.LayoutParams(dp(64), dp(44));
        wlp.setMargins(dp(6), dp(6), dp(6), 0);
        save.setLayoutParams(wlp);
        cancel.setLayoutParams(new LinearLayout.LayoutParams(wlp));
        move.setLayoutParams(new LinearLayout.LayoutParams(wlp));
        row.addView(save);
        row.addView(move);
        row.addView(cancel);
        bar.addView(row);
        final FrameLayout.LayoutParams barLp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        barLp.bottomMargin = dp(48);
        cropRoot.addView(bar, barLp);

        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Rect r = cv.selection();
                if (r == null || r.width() < 20 || r.height() < 20) {
                    toast("حدد مساحة أكبر (20×20 على الأقل)");
                    return;
                }
                Bitmap crop = Bitmap.createBitmap(shot, r.left, r.top, r.width(), r.height());
                boolean ok = ImageStore.add(ClickerService.this, crop);
                closeCrop();
                if (!ok) {
                    toast("تعذر حفظ الصورة");
                } else if (cfg.mode != Config.MODE_IMAGE) {
                    toast("تم حفظ الصورة ✓ — فعّل (وضع الصورة) من الإعدادات ⚙");
                } else {
                    toast("تم حفظ الصورة ✓");
                }
            }
        });
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                closeCrop();
            }
        });
        move.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                barLp.gravity = (barLp.gravity & Gravity.VERTICAL_GRAVITY_MASK) == Gravity.BOTTOM
                        ? Gravity.TOP | Gravity.CENTER_HORIZONTAL : Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
                barLp.topMargin = dp(48);
                v.getRootView().requestLayout();
                ((View) v.getParent().getParent()).setLayoutParams(barLp);
            }
        });

        WindowManager.LayoutParams lp = params(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT, true);
        wm.addView(cropRoot, lp);
    }

    private void closeCrop() {
        safeRemove(cropRoot);
        cropRoot = null;
    }
}
