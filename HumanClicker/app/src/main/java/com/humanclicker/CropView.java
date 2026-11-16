package com.humanclicker;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

/**
 * Shows a bitmap and lets the user drag a rectangle over it.
 * screenMapped = true: the bitmap is a screenshot drawn 1:1 at its real screen position.
 * screenMapped = false: the bitmap is fitted inside the view (gallery images).
 */
public final class CropView extends View {
    private final Bitmap bmp;
    private final boolean screenMapped;
    private final Matrix m = new Matrix();
    private final Matrix inv = new Matrix();
    private final Paint dim = new Paint();
    private final Paint border = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmpPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private float x0, y0, x1, y1;
    private boolean has = false;
    private final int[] loc = new int[2];

    public CropView(Context c, Bitmap bmp, boolean screenMapped) {
        super(c);
        this.bmp = bmp;
        this.screenMapped = screenMapped;
        dim.setColor(0x99000000);
        border.setColor(0xFF4FC3F7);
        border.setStyle(Paint.Style.STROKE);
        border.setStrokeWidth(4 * c.getResources().getDisplayMetrics().density);
    }

    private void updateMatrix() {
        m.reset();
        if (screenMapped) {
            getLocationOnScreen(loc);
            m.setTranslate(-loc[0], -loc[1]);
        } else {
            float s = Math.min((float) getWidth() / bmp.getWidth(), (float) getHeight() / bmp.getHeight());
            float dx = (getWidth() - bmp.getWidth() * s) / 2f, dy = (getHeight() - bmp.getHeight() * s) / 2f;
            m.setScale(s, s);
            m.postTranslate(dx, dy);
        }
        m.invert(inv);
    }

    @Override
    protected void onDraw(Canvas c) {
        updateMatrix();
        c.drawColor(0xFF000000);
        c.drawBitmap(bmp, m, bmpPaint);
        if (has) {
            float l = Math.min(x0, x1), t = Math.min(y0, y1), r = Math.max(x0, x1), b = Math.max(y0, y1);
            c.drawRect(0, 0, getWidth(), t, dim);
            c.drawRect(0, b, getWidth(), getHeight(), dim);
            c.drawRect(0, t, l, b, dim);
            c.drawRect(r, t, getWidth(), b, dim);
            c.drawRect(l, t, r, b, border);
        } else {
            c.drawRect(0, 0, getWidth(), getHeight(), dim);
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                x0 = x1 = e.getX();
                y0 = y1 = e.getY();
                has = true;
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
            case MotionEvent.ACTION_UP:
                x1 = e.getX();
                y1 = e.getY();
                invalidate();
                return true;
        }
        return true;
    }

    /** Selected rectangle in bitmap pixel coordinates, or null. */
    public Rect selection() {
        if (!has) return null;
        updateMatrix();
        RectF r = new RectF(Math.min(x0, x1), Math.min(y0, y1), Math.max(x0, x1), Math.max(y0, y1));
        inv.mapRect(r);
        int l = (int) Math.max(0, Math.floor(r.left)), t = (int) Math.max(0, Math.floor(r.top));
        int rr = (int) Math.min(bmp.getWidth(), Math.ceil(r.right)), b = (int) Math.min(bmp.getHeight(), Math.ceil(r.bottom));
        if (rr <= l || b <= t) return null;
        return new Rect(l, t, rr, b);
    }

    public boolean hasSelection() {
        return has;
    }
}
