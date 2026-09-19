package com.dm5ese.usbprobe;

import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.widget.Button;

/** Shared visual tokens; no instrument or transport behavior. */
final class InstrumentStyle {
    static final int NAVY = 0xff0b2d42, TEAL = 0xff0f766e, INK = 0xff133348;
    static final int MUTED = 0xff607586, LINE = 0xffdce6e9, PAGE = 0xfff3f7fa;
    static final int MINT = 0xffa7f3d0;
    static GradientDrawable surface(int fill, int stroke, float radius) {
        GradientDrawable d = new GradientDrawable(); d.setColor(fill); d.setCornerRadius(radius);
        if (stroke != 0) d.setStroke(1, stroke);
        return d;
    }
    static void button(Button button, boolean primary, boolean danger) {
        ProfessionalUi.styleButton(button,primary,danger);
    }
}
