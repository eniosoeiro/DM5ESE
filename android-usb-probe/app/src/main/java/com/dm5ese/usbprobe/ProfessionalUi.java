package com.dm5ese.usbprobe;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;

final class ProfessionalUi {
    static final int NAVY=0xff0b2d42, TEAL=0xff0f766e, AQUA=0xff14b8a6;
    static final int INK=0xff133348, MUTED=0xff607586, LINE=0xffdce7ec, PAGE=0xfff3f7fa, MINT=0xffe5f6f1;
    static int dp(Context c,float n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    static LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w,h);}
    static LinearLayout column(Context c){LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);return l;}
    static TextView text(Context c,CharSequence value,float sp,int color,boolean bold){
        TextView t=new TextView(c);t.setText(value);t.setTextSize(sp);t.setTextColor(color);t.setIncludeFontPadding(false);
        t.setTypeface(Typeface.create(bold?"sans-serif-medium":"sans-serif",Typeface.NORMAL));t.setFontFeatureSettings("tnum");
        t.setLineSpacing(dp(c,2),1);return t;
    }
    static GradientDrawable shape(Context c,int fill,int border,float radius){
        GradientDrawable d=new GradientDrawable();d.setColor(fill);d.setCornerRadius(dp(c,radius));
        if(border!=0)d.setStroke(dp(c,1),border);return d;
    }
    static ImageView icon(Context c,int resource,int tint){
        ImageView v=new ImageView(c);v.setImageResource(resource);v.setImageTintList(ColorStateList.valueOf(tint));
        v.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);return v;
    }
    static void styleButton(Button b,boolean primary,boolean danger){
        Context c=b.getContext();b.setAllCaps(false);b.setTextSize(14);b.setMinHeight(dp(c,50));b.setMinimumWidth(0);
        b.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));b.setElevation(0);b.setStateListAnimator(null);
        int fill=primary?TEAL:danger?0xfffff5f3:Color.WHITE, ink=primary?Color.WHITE:danger?0xffa5382b:INK;
        int[][] states={new int[]{-android.R.attr.state_enabled},new int[]{}};
        GradientDrawable d=shape(c,fill,primary?0:danger?0xffefd5d0:LINE,14);
        d.setColor(new ColorStateList(states,new int[]{0xffe5ecef,fill}));b.setBackgroundTintList(null);
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x200f766e),d,null));
        b.setTextColor(new ColorStateList(states,new int[]{0xff60717b,ink}));
        androidx.core.widget.TextViewCompat.setCompoundDrawableTintList(b,new ColorStateList(states,new int[]{0xff60717b,ink}));
        b.setPadding(dp(c,14),dp(c,10),dp(c,14),dp(c,10));
    }
    static Button button(Context c,int label,int icon,boolean primary,Runnable action){
        Button b=new Button(c);b.setText(label);styleButton(b,primary,false);setIcon(b,icon);b.setOnClickListener(v->action.run());return b;
    }
    static void setIcon(Button b,int icon){
        if(icon==0)return;Context c=b.getContext();var d=androidx.appcompat.content.res.AppCompatResources.getDrawable(c,icon);d.setBounds(0,0,dp(c,20),dp(c,20));
        b.setCompoundDrawablesRelative(d,null,null,null);b.setCompoundDrawablePadding(dp(c,8));
    }
    static LinearLayout card(LinearLayout parent){
        Context c=parent.getContext();LinearLayout card=column(c);card.setPadding(dp(c,16),dp(c,16),dp(c,16),dp(c,16));
        card.setBackground(shape(c,Color.WHITE,LINE,18));var p=lp(-1,-2);p.bottomMargin=dp(c,14);parent.addView(card,p);return card;
    }
    static void title(LinearLayout parent,int title,int hint){
        Context c=parent.getContext();TextView t=text(c,c.getString(title),26,INK,true);t.setAccessibilityHeading(true);
        parent.addView(t,lp(-1,-2));TextView h=text(c,c.getString(hint),13,MUTED,false);
        h.setPadding(0,dp(c,6),0,dp(c,18));parent.addView(h,lp(-1,-2));
    }
    static LinearLayout row(LinearLayout parent,int icon,int label,int hint,Runnable action){
        Context c=parent.getContext();LinearLayout row=new LinearLayout(c);row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(c,14),dp(c,14),dp(c,14),dp(c,14));row.setMinimumHeight(dp(c,68));
        row.setBackground(new RippleDrawable(ColorStateList.valueOf(0x160f766e),shape(c,Color.WHITE,LINE,16),null));
        var p=lp(-1,-2);p.bottomMargin=dp(c,8);parent.addView(row,p);
        ImageView symbol=icon(c,icon,TEAL);symbol.setPadding(dp(c,9),dp(c,9),dp(c,9),dp(c,9));
        symbol.setBackground(shape(c,MINT,0,12));row.addView(symbol,lp(dp(c,42),dp(c,42)));
        LinearLayout labels=column(c);labels.setPadding(dp(c,12),0,dp(c,8),0);row.addView(labels,new LinearLayout.LayoutParams(0,-2,1));
        labels.addView(text(c,c.getString(label),15,INK,true));
        if(hint!=0){TextView h=text(c,c.getString(hint),12,MUTED,false);h.setPadding(0,dp(c,4),0,0);labels.addView(h);}
        row.addView(icon(c,R.drawable.ic_pro_chevron,MUTED),lp(dp(c,18),dp(c,18)));
        row.setFocusable(true);row.setOnClickListener(v->action.run());return row;
    }
    static void section(LinearLayout parent,int label){
        Context c=parent.getContext();TextView t=text(c,c.getString(label),13,MUTED,true);
        t.setAccessibilityHeading(true);t.setPadding(0,dp(c,16),0,dp(c,10));parent.addView(t,lp(-1,-2));
    }
    static void buttonSpace(LinearLayout parent,Button b){
        var p=lp(-1,-2);p.topMargin=dp(parent.getContext(),8);parent.addView(b,p);
    }
    static LinearLayout expandable(LinearLayout parent,int title,int hint,boolean danger){
        LinearLayout panel=card(parent);Context c=parent.getContext();
        Button toggle=button(c,title,danger?R.drawable.ic_pro_usb:R.drawable.ic_pro_settings,false,()->{});
        styleButton(toggle,false,danger);panel.addView(toggle,lp(-1,-2));
        LinearLayout body=column(c);body.setPadding(0,dp(c,12),0,0);body.setVisibility(View.GONE);panel.addView(body,lp(-1,-2));
        if(hint!=0)body.addView(text(c,c.getString(hint),12,MUTED,false));
        toggle.setOnClickListener(v->{boolean open=body.getVisibility()!=View.VISIBLE;body.setVisibility(open?View.VISIBLE:View.GONE);toggle.setSelected(open);});
        return body;
    }
    static void decorateInput(EditText input){
        Context c=input.getContext();input.setBackgroundResource(R.drawable.pro_edit_background);input.setTextSize(15);
        input.setTextColor(INK);input.setHintTextColor(MUTED);input.setMinHeight(dp(c,56));input.setPadding(dp(c,16),dp(c,12),dp(c,16),dp(c,12));
        int variation=input.getInputType()&android.text.InputType.TYPE_MASK_VARIATION;
        int icon=variation==android.text.InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS?R.drawable.ic_pro_mail:variation==android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD?R.drawable.ic_pro_lock:0;
        if(icon!=0){var d=androidx.appcompat.content.res.AppCompatResources.getDrawable(c,icon);d.setBounds(0,0,dp(c,20),dp(c,20));input.setCompoundDrawablesRelative(d,null,null,null);input.setCompoundDrawablePadding(dp(c,10));}
        LinearLayout.LayoutParams p=lp(-1,-2);p.bottomMargin=dp(c,12);input.setLayoutParams(p);
    }
}
