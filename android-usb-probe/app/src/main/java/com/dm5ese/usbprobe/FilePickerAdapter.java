package com.dm5ese.usbprobe;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import java.util.List;

/** Presentation only: the activity retains the placeholder-to-file index mapping. */
final class FilePickerAdapter extends BaseAdapter {
    private final Context context;
    private final Spinner picker;
    private final List<String> labels;
    private static final int INK = InstrumentStyle.INK, TEAL = InstrumentStyle.TEAL, MUTED = InstrumentStyle.MUTED;

    FilePickerAdapter(Context context, Spinner picker, List<String> labels) {
        this.context = context; this.picker = picker; this.labels = List.copyOf(labels);
    }
    @Override public int getCount() { return labels.size(); }
    @Override public String getItem(int position) { return labels.get(position); }
    @Override public long getItemId(int position) { return position; }
    @Override public boolean areAllItemsEnabled() { return false; }
    @Override public boolean isEnabled(int position) { return position > 0; }
    private int dp(int value) { return (int) (value * context.getResources().getDisplayMetrics().density); }
    private TextView text(String value, int size, int color, boolean bold) {
        TextView view = new TextView(context); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        if (bold) view.setTypeface(null, Typeface.BOLD);
        return view;
    }
    @Override public View getView(int position, View recycled, ViewGroup parent) {
        LinearLayout row = new LinearLayout(context); row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(14), dp(12), dp(14), dp(12)); row.setMinimumHeight(dp(68));
        GradientDrawable bg = new GradientDrawable(); bg.setColor(0xfff0f7fa); bg.setCornerRadius(dp(12)); bg.setStroke(dp(1), 0xffbed5df);
        row.setBackground(bg);
        LinearLayout words = new LinearLayout(context); words.setOrientation(LinearLayout.VERTICAL);
        words.addView(text(position == 0 ? "ARQUIVOS DO INSTRUMENTO" : "ARQUIVO SELECIONADO", 10, MUTED, true));
        words.addView(text(position == 0 ? (labels.size() == 1 ? "Conecte o DM5E em Configurações" : "Selecione para abrir") : getItem(position), 17, INK, true));
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        TextView arrow = text("⌄", 24, TEAL, true); row.addView(arrow);
        row.setContentDescription(position == 0 ? "Selecione um arquivo para abrir" : "Arquivo " + getItem(position) + ". Toque para trocar.");
        return row;
    }
    @Override public View getDropDownView(int position, View recycled, ViewGroup parent) {
        if (position == 0) {
            TextView heading = text("ARQUIVOS DISPONÍVEIS  ·  " + (labels.size() - 1), 11, MUTED, true);
            heading.setPadding(dp(16), dp(16), dp(16), dp(12)); return heading;
        }
        boolean selected = position == picker.getSelectedItemPosition();
        LinearLayout outer = new LinearLayout(context); outer.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(context); row.setGravity(android.view.Gravity.CENTER_VERTICAL);
        row.setPadding(dp(16), dp(12), dp(16), dp(12)); row.setMinimumHeight(dp(68));
        row.setBackgroundColor(selected ? 0xffe5f2f6 : Color.WHITE);
        ImageView icon = new ImageView(context); icon.setImageResource(R.drawable.ic_folder); icon.setColorFilter(TEAL);
        icon.setPadding(0, 0, dp(12), 0); row.addView(icon, new LinearLayout.LayoutParams(dp(36), dp(24)));
        LinearLayout words = new LinearLayout(context); words.setOrientation(LinearLayout.VERTICAL);
        words.addView(text(getItem(position), 17, INK, true));
        words.addView(text(selected ? "Selecionado" : "Toque para abrir a planilha", 12, MUTED, false));
        row.addView(words, new LinearLayout.LayoutParams(0, -2, 1));
        row.addView(text(selected ? "✓" : "›", 22, TEAL, true)); outer.addView(row);
        View line = new View(context); line.setBackgroundColor(0xffe7eef2); outer.addView(line, new LinearLayout.LayoutParams(-1, dp(1)));
        outer.setContentDescription(getItem(position) + (selected ? ", selecionado" : ", abrir planilha"));
        return outer;
    }
}
