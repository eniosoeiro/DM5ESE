package com.dm5ese.usbprobe;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.widget.Button;

/** Shared visual tokens; no instrument or transport behavior. */
final class InstrumentStyle {
    static final int NAVY = 0xff102a36, TEAL = 0xff087f70, INK = 0xff17313e;
    static final int MUTED = 0xff5d7280, LINE = 0xffdce6e9, PAGE = 0xfff3f7f8;
    static final int MINT = 0xffa7f3d0;
    static GradientDrawable surface(int fill, int stroke, float radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(fill); d.setCornerRadius(radius);
        if (stroke != 0) d.setStroke(1, stroke);
        return d;
    }
    static void button(Button button, boolean primary, boolean danger) {
        float density = button.getResources().getDisplayMetrics().density;
        int fill = primary ? NAVY : danger ? 0xfffff3f0 : Color.WHITE;
        int ink = primary ? Color.WHITE : danger ? 0xffa43125 : INK;
        int[][] states = {new int[]{-android.R.attr.state_enabled}, new int[]{}};
        GradientDrawable shape = surface(fill, primary ? 0 : danger ? 0xffe9c9c2 : LINE, 12 * density);
        shape.setColor(new ColorStateList(states, new int[]{0xffe3e9ec, fill}));
        button.setBackgroundTintList(null);
        button.setBackground(new RippleDrawable(ColorStateList.valueOf(0x26096b72), shape, null));
        button.setTextColor(new ColorStateList(states, new int[]{0xff647682, ink}));
        button.setCompoundDrawableTintList(new ColorStateList(states, new int[]{0xff647682, ink}));
        button.setElevation(0); button.setStateListAnimator(null);
        button.setPadding((int)(14*density), (int)(12*density), (int)(14*density), (int)(12*density));
    }
}
