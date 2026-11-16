package com.humanclicker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.List;

/** Settings screen (Arabic, built in code). */
public final class MainActivity extends Activity {
    private static final int REQ_PICK = 7;
    private static final int BG = 0xFF0F151C, CARD = 0xFF1B2430, ACCENT = 0xFF4FC3F7, TEXT = 0xFFECEFF1, SUB = 0xFF90A4AE;

    private Config cfg;
    private LinearLayout root;
    private TextView statusText;
    private Button panelBtn;
    private LinearLayout templatesBox, profilesBox;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (Build.VERSION.SDK_INT >= 21) getWindow().setStatusBarColor(BG);
        build();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        refreshTemplates();
    }

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    // ============================================================ layout

    private void build() {
        cfg = Store.loadSettings(this);
        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(BG);
        sv.setFillViewport(true);
        root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setPadding(dp(14), dp(18), dp(14), dp(40));
        sv.addView(root);
        setContentView(sv);

        TextView title = text("Human Clicker", 26, ACCENT);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        root.addView(text("نقر تلقائي عشوائي يحاكي حركة الإنسان — نقرات متعددة، سحب، جويستيك، والبحث عن الصور", 13, SUB));

        // ---------------- status
        LinearLayout st = card("التشغيل");
        statusText = text("", 14, TEXT);
        st.addView(statusText);
        Button acc = button("① تفعيل خدمة الوصول (Accessibility)");
        acc.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Store.setFlag(MainActivity.this, "showOnConnect", ClickerService.instance == null);
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                toast("ابحث عن Human Clicker وفعّله");
            }
        });
        st.addView(acc);
        panelBtn = button("② إظهار اللوحة العائمة");
        panelBtn.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ClickerService s = ClickerService.instance;
                if (s == null) {
                    toast("فعّل خدمة الوصول أولاً");
                    return;
                }
                if (s.isPanelShown()) s.hidePanel();
                else {
                    s.showPanel();
                    moveTaskToBack(true);
                }
                refreshStatus();
            }
        });
        st.addView(panelBtn);
        st.addView(text("ملاحظة: في أندرويد 13+ إذا كان خيار التفعيل رمادياً: الإعدادات ← التطبيقات ← Human Clicker ← (⋮) ← السماح بالإعدادات المقيدة، ثم فعّل الخدمة.", 12, SUB));

        // ---------------- mode
        LinearLayout md = card("الوضع");
        radio(md, "mode", new String[]{
                "نقر / سحب / جويستيك (أهداف متعددة)",
                "النقر على الصور (Image Clicker)"});

        LinearLayout od = card("ترتيب الأهداف (العشوائية)");
        radio(od, "order", new String[]{
                "بالترتيب: 123 123 123",
                "خلط كل دورة: 213 321 132",
                "عشوائي كامل: 1123 31 2213 (تكرار وتخطي)",
                "بشري: خلط + عادات بشرية (تكرار، نسيان، تبديل)",
                "كلهم بنفس الوقت 🖐 (مستحسن للألعاب): كل هدف إصبع مستقل بإيقاعه الخاص"});

        // ---------------- timing
        LinearLayout tm = card("التوقيت والحركة البشرية");
        pair(tm, "intervalMin", "intervalMax", "الانتظار بين النقرات (ms)");
        pair(tm, "pressMin", "pressMax", "مدة ضغط الإصبع (ms)");
        num(tm, "spread", "انتشار مكان النقر حول الهدف (px)");
        num(tm, "doubleTapChance", "احتمال نقرة مزدوجة عفوية (%)");
        check(tm, "fatigue", "تعب تدريجي وموجات تركيز (أبطأ أحياناً وأسرع أحياناً)");
        num(tm, "restChance", "احتمال استراحة بعد كل حركة (%)");
        pair(tm, "restMinSec", "restMaxSec", "مدة الاستراحة (ثانية)");

        LinearLayout sw = card("السحب ⇆ والجويستيك 🕹");
        pair(sw, "swipeMin", "swipeMax", "سرعة السحب: المدة (ms)");
        num(sw, "curve", "انحناء مسار السحب (%)");
        num(sw, "swipeHoldChance", "احتمال إبقاء الإصبع بعد السحب (%)");
        num(sw, "swipeHoldMax", "أقصى مدة إبقاء بعد السحب (ms)");
        pair(sw, "holdMin", "holdMax", "مدة المشي بالجويستيك (ms) — 60000 = دقيقة");
        num(sw, "wander", "نصف قطر حركة الجويستيك (px)");
        num(sw, "longChance", "احتمال مشي طويل (%) — قريب من الدقيقة");
        num(sw, "rareLongChance", "احتمال نادر لمشي أطول من دقيقة (%)");
        sw.addView(text("🕹 الجويستيك: يمشي بكل الاتجاهات بالتساوي (يتذكر الجهات التي مشى فيها ويروح للجهات الأقل)، يعدّل اتجاهه شوي شوي مثل الإنسان، يلف، يتوقف، يتعرج يمين ويسار. غالباً المشي ثواني، أحياناً قرب الدقيقة، ونادراً أكثر من دقيقة.", 12, SUB));

        LinearLayout lk = card("جويستيك اللف ↔ (الكاميرا)");
        pair(lk, "lookMin", "lookMax", "مدة اللفة (ms) — 250 إلى 1000 = ربع ثانية لثانية");
        num(lk, "lookRange", "أقصى مسافة سحب للف (px)");
        num(lk, "lookVertChance", "احتمال نادر للف فوق/تحت (%)");
        pair(lk, "lookGapMin", "lookGapMax", "الانتظار بين اللفات (ms)");
        lk.addView(text("↔ ضعه على مكان الكاميرا في اللعبة (الجهة اليمين عادة). يلف يمين ويسار بالتناوب العشوائي بدون ما يدور لجهة وحدة دائماً، ونادراً فوق أو تحت.", 12, SUB));

        LinearLayout sp = card("الإيقاف التلقائي");
        num(sp, "cycles", "عدد الدورات (0 = بلا نهاية)");
        num(sp, "timeLimitMin", "مدة التشغيل بالدقائق (0 = بلا حد)");
        num(sp, "startDelaySec", "تأخير قبل البدء (ثانية)");

        // ---------------- images
        LinearLayout im = card("النقر على الصور 🖼");
        num(im, "threshold", "نسبة التطابق المطلوبة (%) — أعلى = أدق");
        pair(im, "scanMin", "scanMax", "الفحص كل (ms)");
        num(im, "confirmFrames", "تأكيد الصورة في عدد لقطات متتالية (1-5)");
        check(im, "colorCheck", "تحقق من الألوان (يمنع الضغط الخاطئ)");
        check(im, "clickAllMatches", "اضغط كل الصور الموجودة في نفس اللقطة");
        im.addView(text("أفضل طريقة: افتح اللعبة/التطبيق، ثم اضغط 📷 في اللوحة العائمة وحدد الشيء. يمكنك أيضاً إضافة لقطة شاشة من المعرض.", 12, SUB));
        Button pick = button("+ إضافة صورة من المعرض");
        pick.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                i.addCategory(Intent.CATEGORY_OPENABLE);
                i.setType("image/*");
                startActivityForResult(i, REQ_PICK);
            }
        });
        im.addView(pick);
        templatesBox = new LinearLayout(this);
        templatesBox.setOrientation(LinearLayout.VERTICAL);
        im.addView(templatesBox);

        // ---------------- profiles
        LinearLayout pr = card("الإعدادات المحفوظة");
        final EditText name = new EditText(this);
        name.setHint("اسم الإعداد (مثلاً: لعبة 1)");
        name.setTextColor(TEXT);
        name.setHintTextColor(SUB);
        pr.addView(name);
        Button save = button("حفظ الإعدادات والأهداف الحالية");
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String n = name.getText().toString().trim();
                if (n.isEmpty()) {
                    toast("اكتب اسماً");
                    return;
                }
                Store.saveProfile(MainActivity.this, n);
                name.setText("");
                toast("تم الحفظ");
                refreshProfiles();
            }
        });
        pr.addView(save);
        profilesBox = new LinearLayout(this);
        profilesBox.setOrientation(LinearLayout.VERTICAL);
        pr.addView(profilesBox);

        // ---------------- help
        LinearLayout hp = card("طريقة الاستخدام");
        hp.addView(text(
                "＋ هدف نقر   ⇆ هدف سحب (اسحب الدائرتين)   🕹 جويستيك المشي   ↔ جويستيك اللف (الكاميرا)\n"
                        + "－ حذف آخر هدف   👁 إخفاء الأهداف   📌 اضغطه ثم اضغط على هدف ليصير منتظم/عشوائي\n"
                        + "اضغط على ⠿ لتصغير اللوحة أو تكبيرها، واسحبه لتحريكها\n"
                        + "اضغط مطولاً على أي هدف ← 📌 اجعله منتظم (نفس المكان ونفس التوقيت ونفس مكانه في الترتيب كل دورة، والباقي يبقى عشوائي) أو 🗑 حذف\n"
                        + "📷 التقاط صورة من الشاشة لوضع الصورة   ⚙ الإعدادات   ✕ إغلاق\n"
                        + "▶ تشغيل / ■ إيقاف. اللون الأصفر = استراحة بشرية.\n\n"
                        + "كل شيء عشوائي: المكان داخل الهدف، مدة الضغط، الانتظار، ترتيب الأهداف، مسار السحب، ومدة الجويستيك.",
                13, TEXT));

        refreshStatus();
        refreshTemplates();
        refreshProfiles();
    }

    private TextView text(String s, int sp, int color) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setPadding(0, dp(3), 0, dp(3));
        t.setTextDirection(View.TEXT_DIRECTION_RTL);
        return t;
    }

    private Button button(String s) {
        Button b = new Button(this);
        b.setText(s);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        GradientDrawable d = new GradientDrawable();
        d.setColor(0xFF263445);
        d.setCornerRadius(dp(10));
        d.setStroke(dp(1), 0x554FC3F7);
        b.setBackground(d);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(46));
        lp.setMargins(0, dp(5), 0, dp(5));
        b.setLayoutParams(lp);
        return b;
    }

    private LinearLayout card(String title) {
        LinearLayout c = new LinearLayout(this);
        c.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable d = new GradientDrawable();
        d.setColor(CARD);
        d.setCornerRadius(dp(14));
        c.setBackground(d);
        c.setPadding(dp(14), dp(10), dp(14), dp(10));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.setMargins(0, dp(12), 0, 0);
        c.setLayoutParams(lp);
        TextView t = text(title, 16, ACCENT);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        c.addView(t);
        root.addView(c);
        return c;
    }

    // ============================================================ binding (reflection on Config fields)

    private int getInt(String f) {
        try {
            return Config.class.getField(f).getInt(cfg);
        } catch (Exception e) {
            return 0;
        }
    }

    private void setInt(String f, int v) {
        try {
            Config.class.getField(f).setInt(cfg, v);
        } catch (Exception ignored) {
        }
        persist();
    }

    private void persist() {
        Store.saveSettings(this, cfg);
        ClickerService s = ClickerService.instance;
        if (s != null) s.onSettingsChanged();
    }

    private EditText numberField(final String field) {
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(getInt(field)));
        e.setTextColor(TEXT);
        e.setGravity(Gravity.CENTER);
        e.setSelectAllOnFocus(true);
        e.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            public void afterTextChanged(Editable s) {
                try {
                    setInt(field, Integer.parseInt(s.toString().trim()));
                } catch (NumberFormatException ignored) {
                }
            }
        });
        return e;
    }

    private void num(LinearLayout parent, String field, String label) {
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = text(label, 14, TEXT);
        row.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(numberField(field), new LinearLayout.LayoutParams(dp(96), ViewGroup.LayoutParams.WRAP_CONTENT));
        parent.addView(row);
    }

    private void pair(LinearLayout parent, String fMin, String fMax, String label) {
        parent.addView(text(label, 14, TEXT));
        LinearLayout row = new LinearLayout(this);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(text("من", 13, SUB));
        row.addView(numberField(fMin), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(text("إلى", 13, SUB));
        row.addView(numberField(fMax), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        parent.addView(row);
    }

    private void check(LinearLayout parent, final String field, String label) {
        CheckBox cb = new CheckBox(this);
        cb.setText(label);
        cb.setTextColor(TEXT);
        try {
            cb.setChecked(Config.class.getField(field).getBoolean(cfg));
        } catch (Exception ignored) {
        }
        cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            public void onCheckedChanged(CompoundButton b, boolean v) {
                try {
                    Config.class.getField(field).setBoolean(cfg, v);
                } catch (Exception ignored) {
                }
                persist();
            }
        });
        parent.addView(cb);
    }

    private void radio(LinearLayout parent, final String field, String[] options) {
        RadioGroup g = new RadioGroup(this);
        int cur = getInt(field);
        for (int i = 0; i < options.length; i++) {
            RadioButton rb = new RadioButton(this);
            rb.setId(1000 + i);
            rb.setText(options[i]);
            rb.setTextColor(TEXT);
            g.addView(rb);
            if (i == cur) rb.setChecked(true);
        }
        g.setOnCheckedChangeListener(new RadioGroup.OnCheckedChangeListener() {
            public void onCheckedChanged(RadioGroup group, int id) {
                setInt(field, id - 1000);
            }
        });
        parent.addView(g);
    }

    // ============================================================ dynamic parts

    private void refreshStatus() {
        if (statusText == null) return;
        ClickerService s = ClickerService.instance;
        if (s == null) {
            statusText.setText("خدمة الوصول: غير مفعّلة ✗");
            statusText.setTextColor(0xFFEF9A9A);
            panelBtn.setText("② إظهار اللوحة العائمة");
        } else {
            statusText.setText("خدمة الوصول: مفعّلة ✓");
            statusText.setTextColor(0xFFA5D6A7);
            panelBtn.setText(s.isPanelShown() ? "② إخفاء اللوحة العائمة" : "② إظهار اللوحة العائمة");
        }
    }

    private void refreshTemplates() {
        if (templatesBox == null) return;
        templatesBox.removeAllViews();
        List<ImageStore.Item> items = ImageStore.list(this);
        if (items.isEmpty()) {
            templatesBox.addView(text("لا توجد صور بعد.", 13, SUB));
            return;
        }
        for (final ImageStore.Item it : items) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(4), 0, dp(4));
            ImageView iv = new ImageView(this);
            iv.setImageBitmap(ImageStore.thumb(this, it.id, dp(56)));
            iv.setAdjustViewBounds(true);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            GradientDrawable bd = new GradientDrawable();
            bd.setColor(0xFF000000);
            bd.setStroke(dp(1), 0x554FC3F7);
            iv.setBackground(bd);
            row.addView(iv, new LinearLayout.LayoutParams(dp(56), dp(56)));
            CheckBox cb = new CheckBox(this);
            cb.setText(it.name);
            cb.setTextColor(TEXT);
            cb.setChecked(it.enabled);
            cb.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                public void onCheckedChanged(CompoundButton b, boolean v) {
                    ImageStore.setEnabled(MainActivity.this, it.id, v);
                }
            });
            row.addView(cb, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Button del = new Button(this);
            del.setText("حذف");
            del.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    ImageStore.delete(MainActivity.this, it.id);
                    refreshTemplates();
                }
            });
            row.addView(del);
            templatesBox.addView(row);
        }
    }

    private void refreshProfiles() {
        profilesBox.removeAllViews();
        for (final String n : Store.profileNames(this)) {
            LinearLayout row = new LinearLayout(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.addView(text(n, 14, TEXT), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
            Button load = new Button(this);
            load.setText("تحميل");
            load.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    if (ClickerService.instance != null && ClickerService.instance.isRunning()) {
                        toast("أوقف التشغيل أولاً");
                        return;
                    }
                    if (Store.loadProfile(MainActivity.this, n)) {
                        if (ClickerService.instance != null) ClickerService.instance.reloadTargets();
                        toast("تم تحميل " + n);
                        build();
                    }
                }
            });
            Button del = new Button(this);
            del.setText("حذف");
            del.setOnClickListener(new View.OnClickListener() {
                public void onClick(View v) {
                    Store.deleteProfile(MainActivity.this, n);
                    refreshProfiles();
                }
            });
            row.addView(load);
            row.addView(del);
            profilesBox.addView(row);
        }
    }

    // ============================================================ gallery import

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        Bitmap b = decode(data.getData());
        if (b == null) {
            toast("تعذر فتح الصورة");
            return;
        }
        showGalleryCrop(b);
    }

    private Bitmap decode(Uri uri) {
        try {
            InputStream in = getContentResolver().openInputStream(uri);
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            o.inScaled = false;
            Bitmap b = BitmapFactory.decodeStream(in, null, o);
            if (in != null) in.close();
            return b;
        } catch (Exception e) {
            return null;
        }
    }

    private void showGalleryCrop(final Bitmap b) {
        final CropView cv = new CropView(this, b, false);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(text("  حدد الجزء الذي تريد الضغط عليه (الصورة يجب أن تكون لقطة شاشة من نفس الجهاز)", 12, SUB));
        box.addView(cv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.6f)));
        new AlertDialog.Builder(this)
                .setView(box)
                .setPositiveButton("حفظ التحديد", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        Rect r = cv.selection();
                        if (r == null || r.width() < 20 || r.height() < 20) {
                            toast("التحديد صغير جداً، تم حفظ الصورة كاملة");
                            saveTemplate(b);
                        } else {
                            saveTemplate(Bitmap.createBitmap(b, r.left, r.top, r.width(), r.height()));
                        }
                    }
                })
                .setNeutralButton("الصورة كاملة", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) {
                        saveTemplate(b);
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void saveTemplate(Bitmap b) {
        if (b.getWidth() < 20 || b.getHeight() < 20) {
            toast("الصورة صغيرة جداً");
            return;
        }
        toast(ImageStore.add(this, b) ? "تمت إضافة الصورة ✓" : "تعذر حفظ الصورة");
        refreshTemplates();
    }
}
