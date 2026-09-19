package com.dm5ese.usbprobe;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

/** Modern numeric typography inside the instrument's LCD housing. */
final class RetroDigitsView extends View {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private String value = "—,—";
    private int ink = 0xff26391f;
    RetroDigitsView(Context context) { super(context); setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES); setText(value); }
    void setText(String value) { this.value = value; setContentDescription("Espessura: " + value + " milímetros"); invalidate(); }
    void setTextColor(int color) { ink = color; invalidate(); }
    @Override protected void onMeasure(int width, int height) {
        setMeasuredDimension(MeasureSpec.getSize(width), (int) (112 * getResources().getDisplayMetrics().density));
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        paint.setColor(ink);
        paint.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(getHeight() * 0.94f);
        float width = paint.measureText(value);
        float available = getWidth() * 0.94f;
        if (width > available && width > 0) paint.setTextSize(paint.getTextSize() * available / width);
        Paint.FontMetrics metrics = paint.getFontMetrics();
        float baseline = (getHeight() - metrics.ascent - metrics.descent) / 2f;
        canvas.drawText(value, getWidth() / 2f, baseline, paint);
    }
}
