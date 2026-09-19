package com.dm5ese.usbprobe;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.*;
import com.google.android.material.button.MaterialButton;

/** Local design tokens. Does not restyle the instrument's other screens. */
final class SyncSheetUi {
    static final int INK=0xff17313e, MUTED=0xff5d7280, TEAL=0xff087f70;
    static final int PAGE=0xfff4f7f8, BORDER=0xffdce6e9, SELECTED=0xffeaf7f2;
    static int dp(Context c,float n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    static TextView text(Context c, String value, float sp, int color, boolean bold){
        TextView t=new TextView(c);t.setText(value);t.setTextSize(sp);t.setTextColor(color);t.setIncludeFontPadding(false);
        t.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",Typeface.NORMAL));return t;
    }
    static LinearLayout vertical(Context c){LinearLayout v=new LinearLayout(c);v.setOrientation(LinearLayout.VERTICAL);return v;}
    static GradientDrawable shape(Context c,int fill,int stroke,float radius){
        GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(c,radius));
        if(stroke!=0)d.setStroke(dp(c,1),stroke);return d;
    }
    static ImageView icon(Context c,int resource,int tint){
        ImageView v=new ImageView(c);v.setImageResource(resource);v.setImageTintList(ColorStateList.valueOf(tint));
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return v;
    }
    static ImageButton iconButton(Context c,int resource,String label){
        ImageButton b=new ImageButton(c);b.setImageResource(resource);b.setImageTintList(ColorStateList.valueOf(MUTED));
        b.setBackgroundResource(android.R.drawable.list_selector_background);b.setContentDescription(label);
        b.setPadding(dp(c,12),dp(c,12),dp(c,12),dp(c,12));return b;
    }
    static MaterialButton button(Context c,String label,boolean primary){
        MaterialButton b=new MaterialButton(c);b.setText(label);b.setAllCaps(false);b.setTextSize(14);
        b.setCornerRadius(dp(c,14));b.setMinHeight(dp(c,52));b.setInsetTop(0);b.setInsetBottom(0);
        b.setElevation(0);b.setStateListAnimator(null);b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        b.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{0xff647681,primary?Color.WHITE:INK}));
        b.setBackgroundTintList(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{0xffe4eaec,primary?TEAL:Color.WHITE}));
        b.setStrokeWidth(primary?0:dp(c,1));b.setStrokeColor(ColorStateList.valueOf(BORDER));return b;
    }
    static int statusLabel(String status){return switch(status){
        case "RECEIVED" -> R.string.sync_sent;case "FAILED" -> R.string.sync_failed;
        case "CONFLICT" -> R.string.sync_conflict;case "OTHER_ACCOUNT" -> R.string.sync_other_account;
        case "PENDING","SENDING" -> R.string.sync_queued;default -> R.string.sync_local;
    };}
    static int statusInk(String status){return switch(status){
        case "RECEIVED" -> 0xff076957;case "FAILED","CONFLICT" -> 0xffa03629;
        case "PENDING","SENDING","OTHER_ACCOUNT" -> 0xff845213;default -> 0xff536975;
    };}
    static int statusFill(String status){return switch(status){
        case "RECEIVED" -> 0xffdcf4e8;case "FAILED","CONFLICT" -> 0xfffbeae6;
        case "PENDING","SENDING","OTHER_ACCOUNT" -> 0xfffff1d8;default -> 0xffedf2f4;
    };}
    private SyncSheetUi(){}
}
