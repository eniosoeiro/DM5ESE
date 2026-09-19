package com.dm5ese.usbprobe;

import android.content.Context;
import android.graphics.*;
import android.view.View;
import java.io.IOException;

final class Dm5eScreenView extends View {
    private final Paint paint = new Paint();
    private Bitmap frame;
    Dm5eScreenView(Context context) { super(context); setContentDescription("Visor do DM5E sem imagem atual"); }
    void show(byte[] bytes) throws IOException {
        frame = Bitmap.createBitmap(Dm5eScreen.pixels(bytes), 128, 64, Bitmap.Config.ARGB_8888);
        setContentDescription("Imagem atual do visor do DM5E recebida por USB"); invalidate();
    }
    void clear() { frame = null; setContentDescription("Visor do DM5E sem imagem atual"); invalidate(); }
    @Override protected void onMeasure(int width, int height) {
        int w = MeasureSpec.getSize(width); setMeasuredDimension(w, w / 2);
    }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas); canvas.drawColor(0xffe4efdb);
        if (frame != null) canvas.drawBitmap(frame, null, new Rect(0, 0, getWidth(), getHeight()), paint);
        else {
            paint.setColor(0xff102c30); paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(16 * getResources().getDisplayMetrics().scaledDensity);
            canvas.drawText("Aguardando visor", getWidth() / 2f, getHeight() / 2f, paint);
        }
    }
}
