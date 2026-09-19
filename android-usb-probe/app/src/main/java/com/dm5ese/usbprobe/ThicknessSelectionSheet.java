package com.dm5ese.usbprobe;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.*;
import android.text.Editable;
import android.text.TextWatcher;
import androidx.annotation.NonNull;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetDialog;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import org.json.*;
import java.io.File;
import java.math.BigDecimal;
import java.time.ZoneId;
import java.util.*;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import static com.dm5ese.usbprobe.SyncSheetUi.*;

/** Recyclable selector. Does not authenticate, enqueue, send or alter snapshots. */
final class ThicknessSelectionSheet extends BottomSheetDialog {
    record Source(JSONObject snapshot,String status,String fileHash){ Source(JSONObject snapshot,String status){this(snapshot,status,"");} }
    record Loaded(List<Source> sources,int totalFiles,int unreadable){}
    interface Loader { Loaded load() throws Exception; }
    private final Activity activity;
    private final Context ui;
    private final Executor worker;
    private final Loader loader;
    private final Consumer<List<JSONObject>> continuation;
    private final LinkedHashMap<String,JSONObject> snapshots=new LinkedHashMap<>();
    private final Map<String,String> fileHashes=new HashMap<>();
    private SyncSelectionModel model=new SyncSelectionModel(List.of());
    private final LinearLayout root,header,footer;
    private final TextView brand,subtitle,counter,results,notice,emptyTitle,emptyHint;
    private final EditText search;
    private final ImageButton clearSearch;
    private final MaterialButton proceed,clearSelection,sort,emptyAction;
    private final RecyclerView list;
    private final LinearLayout empty;
    private final ProgressBar loading;
    private final ChipGroup chips;
    private final HorizontalScrollView filterStrip;
    private final CaptureAdapter adapter=new CaptureAdapter();
    private final EnumMap<SyncSelectionModel.Filter,Chip> filterButtons=new EnumMap<>(SyncSelectionModel.Filter.class);
    private boolean loadStarted,loaded,disposed;
    private int unreadable,totalFiles;

    static ThicknessSelectionSheet create(Activity a,CaptureStore store,Executor worker,Consumer<List<JSONObject>> next){
        return new ThicknessSelectionSheet(a,worker,()->{
            List<File> files=store.history();List<Source> sources=new ArrayList<>();int bad=0;
            ThicknessSyncQueue queue=new ThicknessSyncQueue(a.getApplicationContext());
            for(File file:files.subList(0,Math.min(100,files.size()))){
                if(Thread.currentThread().isInterrupted())throw new java.io.InterruptedIOException();
                try {JSONObject snapshot=store.load(file);sources.add(new Source(snapshot,queue.statusForLastAccount(snapshot),queue.fileHashForLastAccount(snapshot)));}
                catch(Exception e){bad++;}
            }
            return new Loaded(sources,files.size(),bad);
        },next);
    }
    ThicknessSelectionSheet(Activity a,Executor worker,Loader loader,Consumer<List<JSONObject>> next){
        super(a,R.style.SyncSheetTheme);activity=a;ui=getContext();this.worker=worker;this.loader=loader;continuation=next;
        root=vertical(ui);root.setBackgroundColor(PAGE);root.setFocusableInTouchMode(true);
        View handle=new View(ui);handle.setBackground(shape(ui,0xffbdcdd1,0,3));
        LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(d(34),d(4));hp.gravity=Gravity.CENTER;hp.topMargin=d(10);hp.bottomMargin=d(8);root.addView(handle,hp);
        header=vertical(ui);header.setPadding(d(20),0,d(20),d(8));root.addView(header,lp(-1,-2));
        brand=text(ui,ui.getString(R.string.sync_brand),10,TEAL,true);brand.setLetterSpacing(.1f);header.addView(brand,lp(-1,-2));
        LinearLayout titleRow=new LinearLayout(ui);titleRow.setGravity(Gravity.CENTER_VERTICAL);header.addView(titleRow,lp(-1,-2));
        TextView title=text(ui,ui.getString(R.string.sync_title),22,INK,true);title.setAccessibilityHeading(true);titleRow.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        ImageButton close=iconButton(ui,R.drawable.ic_sync_close,ui.getString(R.string.sync_close));close.setId(R.id.sync_close_button);titleRow.addView(close,lp(d(48),d(48)));close.setOnClickListener(v->cancel());
        subtitle=text(ui,ui.getString(R.string.sync_subtitle),13,MUTED,false);subtitle.setLineSpacing(d(2),1);header.addView(subtitle,lp(-1,-2));
        LinearLayout searchBox=new LinearLayout(ui);searchBox.setGravity(Gravity.CENTER_VERTICAL);searchBox.setBackground(shape(ui,Color.WHITE,BORDER,14));
        LinearLayout.LayoutParams sp=lp(-1,d(50));sp.topMargin=d(16);header.addView(searchBox,sp);
        ImageView searchIcon=icon(ui,R.drawable.ic_sync_search,MUTED);LinearLayout.LayoutParams si=lp(d(20),d(20));si.leftMargin=d(14);si.rightMargin=d(8);searchBox.addView(searchIcon,si);
        search=new EditText(ui);search.setId(R.id.sync_search_field);search.setSingleLine(true);search.setTextSize(14);search.setTextColor(INK);search.setHintTextColor(MUTED);
        search.setHint(R.string.sync_search);search.setBackgroundColor(Color.TRANSPARENT);search.setPadding(0,0,0,0);search.setInputType(android.text.InputType.TYPE_CLASS_TEXT);search.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_DONE);
        searchBox.addView(search,new LinearLayout.LayoutParams(0,-1,1));clearSearch=iconButton(ui,R.drawable.ic_sync_close,ui.getString(R.string.sync_clear_search));searchBox.addView(clearSearch,lp(d(48),d(48)));clearSearch.setVisibility(View.GONE);
        clearSearch.setOnClickListener(v->search.setText(""));search.setOnEditorActionListener((v,id,event)->{hideKeyboard();return true;});
        filterStrip=new HorizontalScrollView(ui);filterStrip.setHorizontalScrollBarEnabled(false);filterStrip.setClipToPadding(false);filterStrip.setPadding(d(16),0,d(16),0);root.addView(filterStrip,lp(-1,d(48)));
        chips=new ChipGroup(ui);chips.setSingleLine(true);chips.setSingleSelection(true);chips.setSelectionRequired(true);chips.setChipSpacingHorizontal(d(6));filterStrip.addView(chips);
        addFilter(SyncSelectionModel.Filter.ALL,R.string.sync_all);addFilter(SyncSelectionModel.Filter.PENDING,R.string.sync_pending);addFilter(SyncSelectionModel.Filter.FAILED,R.string.sync_failures);
        addFilter(SyncSelectionModel.Filter.RECEIVED,R.string.sync_received);addFilter(SyncSelectionModel.Filter.SELECTED,R.string.sync_selected_filter);
        filterButtons.get(SyncSelectionModel.Filter.ALL).setChecked(true);
        LinearLayout listTop=new LinearLayout(ui);listTop.setGravity(Gravity.CENTER_VERTICAL);listTop.setPadding(d(20),0,d(16),0);root.addView(listTop,lp(-1,d(42)));
        results=text(ui,"",12,MUTED,false);listTop.addView(results,new LinearLayout.LayoutParams(0,-2,1));
        sort=button(ui,ui.getString(R.string.sync_recent),false);sort.setId(R.id.sync_sort_button);sort.setMinHeight(d(40));sort.setMinimumHeight(d(40));sort.setTextSize(12);sort.setStrokeWidth(0);sort.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));sort.setIconResource(R.drawable.ic_sync_sort);sort.setIconSize(d(16));sort.setIconTint(ColorStateList.valueOf(MUTED));sort.setContentDescription(ui.getString(R.string.sync_sort));listTop.addView(sort,lp(-2,d(40)));
        FrameLayout body=new FrameLayout(ui);root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        list=new RecyclerView(ui);list.setId(R.id.sync_capture_list);list.setLayoutManager(new LinearLayoutManager(ui));list.setAdapter(adapter);list.setItemAnimator(null);list.setClipToPadding(false);list.setPadding(d(16),d(2),d(16),d(12));body.addView(list,new FrameLayout.LayoutParams(-1,-1));
        sort.setOnClickListener(v->{model.order(model.order()==SyncSelectionModel.Order.RECENT?SyncSelectionModel.Order.NAME:SyncSelectionModel.Order.RECENT);refresh();list.scrollToPosition(0);});
        empty=vertical(ui);empty.setGravity(Gravity.CENTER);empty.setPadding(d(24),d(16),d(24),d(16));body.addView(empty,new FrameLayout.LayoutParams(-1,-1));
        loading=new ProgressBar(ui);empty.addView(loading,lp(d(32),d(32)));
        emptyTitle=text(ui,ui.getString(R.string.sync_loading),16,INK,true);emptyTitle.setGravity(Gravity.CENTER);LinearLayout.LayoutParams et=lp(-1,-2);et.topMargin=d(16);empty.addView(emptyTitle,et);
        emptyHint=text(ui,"",13,MUTED,false);emptyHint.setGravity(Gravity.CENTER);LinearLayout.LayoutParams eh=lp(-1,-2);eh.topMargin=d(8);empty.addView(emptyHint,eh);
        emptyAction=button(ui,ui.getString(R.string.sync_reset_filters),false);LinearLayout.LayoutParams ea=lp(-2,-2);ea.topMargin=d(16);empty.addView(emptyAction,ea);emptyAction.setVisibility(View.GONE);

        footer=vertical(ui);footer.setPadding(d(20),d(8),d(20),d(14));footer.setBackgroundColor(Color.WHITE);footer.setElevation(d(6));root.addView(footer,lp(-1,-2));
        LinearLayout selectionRow=new LinearLayout(ui);selectionRow.setGravity(Gravity.CENTER_VERTICAL);footer.addView(selectionRow,lp(-1,-2));
        counter=text(ui,ui.getString(R.string.sync_counter,0),13,INK,true);counter.setId(R.id.sync_selection_counter);counter.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);selectionRow.addView(counter,new LinearLayout.LayoutParams(0,-2,1));
        clearSelection=button(ui,ui.getString(R.string.sync_clear_selection),false);clearSelection.setId(R.id.sync_clear_selection);clearSelection.setMinHeight(d(40));clearSelection.setMinimumHeight(d(40));clearSelection.setTextSize(12);clearSelection.setStrokeWidth(0);clearSelection.setBackgroundTintList(ColorStateList.valueOf(Color.TRANSPARENT));selectionRow.addView(clearSelection,lp(-2,d(40)));
        clearSelection.setOnClickListener(v->{model.clear();refresh();});
        notice=text(ui,"",12,0xff845213,false);notice.setId(R.id.sync_selection_notice);notice.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);notice.setPadding(0,0,0,d(8));notice.setVisibility(View.GONE);footer.addView(notice,lp(-1,-2));
        LinearLayout actions=new LinearLayout(ui);actions.setGravity(Gravity.CENTER_VERTICAL);footer.addView(actions,lp(-1,-2));
        MaterialButton cancel=button(ui,ui.getString(R.string.sync_cancel),false);cancel.setOnClickListener(v->cancel());LinearLayout.LayoutParams ca=lp(-2,-2);ca.rightMargin=d(10);actions.addView(cancel,ca);
        proceed=button(ui,ui.getString(R.string.sync_continue),true);proceed.setId(R.id.sync_continue_button);proceed.setIconResource(R.drawable.ic_sync_arrow);proceed.setIconGravity(MaterialButton.ICON_GRAVITY_TEXT_END);proceed.setIconSize(d(18));proceed.setEnabled(false);actions.addView(proceed,new LinearLayout.LayoutParams(0,-2,1));
        LinearLayout safetyRow=new LinearLayout(ui);safetyRow.setGravity(Gravity.CENTER);LinearLayout.LayoutParams sr=lp(-1,-2);sr.topMargin=d(10);footer.addView(safetyRow,sr);
        safetyRow.addView(icon(ui,R.drawable.ic_sync_shield,MUTED),lp(d(14),d(14)));TextView safety=text(ui,ui.getString(R.string.sync_safety),11,MUTED,false);safety.setPadding(d(6),0,0,0);safetyRow.addView(safety);
        proceed.setOnClickListener(v->{
            if(!loaded||model.count()==0||model.count()>SyncSelectionModel.LIMIT)return;
            List<JSONObject> picked=new ArrayList<>();for(String id:model.selectedIds())if(snapshots.containsKey(id))picked.add(snapshots.get(id));
            if(picked.size()!=model.count())return;
            hideKeyboard();hide();continuation.accept(List.copyOf(picked));
        });
        search.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int start,int count,int after){}
            public void onTextChanged(CharSequence s,int start,int before,int count){model.query(s.toString());clearSearch.setVisibility(s.length()>0?View.VISIBLE:View.GONE);refresh();list.scrollToPosition(0);}
            public void afterTextChanged(Editable s){}
        });
        chips.setOnCheckedStateChangeListener((group,ids)->{
            for(var entry:filterButtons.entrySet())if(ids.contains(entry.getValue().getId())){model.filter(entry.getKey());refresh();list.scrollToPosition(0);break;}
        });
        setContentView(root);setDismissWithAnimation(true);setCanceledOnTouchOutside(true);
        getBehavior().setSkipCollapsed(true);getBehavior().setMaxWidth(d(720));
        setOnShowListener(dialog->{
            Window window=getWindow();if(window==null)return;
            WindowCompat.setDecorFitsSystemWindows(window,false);
            window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING);
            View sheet=findViewById(com.google.android.material.R.id.design_bottom_sheet);
            if(sheet!=null){sheet.setClipToOutline(true);resizeSheet(0);}
            getBehavior().setState(BottomSheetBehavior.STATE_EXPANDED);root.requestFocus();
            ViewCompat.requestApplyInsets(root);
            if(!loadStarted)load();
        });
        ViewCompat.setOnApplyWindowInsetsListener(root,(view,insets)->{
            int systemBottom=insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom;
            int keyboard=insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
            root.setPadding(0,0,0,Math.max(systemBottom,keyboard));
            resizeSheet(insets.getInsets(WindowInsetsCompat.Type.statusBars()).top);
            boolean compact=keyboard>0||activity.getResources().getConfiguration().screenHeightDp<560;
            brand.setVisibility(compact?View.GONE:View.VISIBLE);subtitle.setVisibility(compact?View.GONE:View.VISIBLE);
            filterStrip.setVisibility(keyboard>0?View.GONE:View.VISIBLE);
            return insets;
        });
        setOnDismissListener(dialog->{disposed=true;});
    }
    private int d(float n){return dp(ui,n);}
    private static LinearLayout.LayoutParams lp(int w,int h){return new LinearLayout.LayoutParams(w,h);}
    private void resizeSheet(int top){
        View sheet=findViewById(com.google.android.material.R.id.design_bottom_sheet);if(sheet==null)return;
        var metrics=activity.getWindowManager().getCurrentWindowMetrics();
        int status=Math.max(top,metrics.getWindowInsets().getInsets(android.view.WindowInsets.Type.statusBars()).top);
        int height=metrics.getBounds().height()-status-d(12);
        if(sheet.getLayoutParams().height!=height){sheet.getLayoutParams().height=height;sheet.requestLayout();}
    }
    private void addFilter(SyncSelectionModel.Filter filter,int label){
        Chip chip=new Chip(ui);chip.setId(View.generateViewId());chip.setText(label);chip.setCheckable(true);chip.setCheckedIconVisible(false);
        chip.setTextSize(12);chip.setEnsureMinTouchTargetSize(true);chip.setChipMinHeight(d(34));
        chip.setChipCornerRadius(d(17));chip.setChipStrokeWidth(d(1));chip.setChipStrokeColor(ColorStateList.valueOf(BORDER));
        int[][] states={new int[]{android.R.attr.state_checked},new int[]{}};
        chip.setChipBackgroundColor(new ColorStateList(states,new int[]{TEAL,Color.WHITE}));
        chip.setTextColor(new ColorStateList(states,new int[]{Color.WHITE,MUTED}));
        chip.setTag(filter.name());chips.addView(chip);filterButtons.put(filter,chip);
    }
    private void hideKeyboard(){
        InputMethodManager manager=(InputMethodManager)ui.getSystemService(Context.INPUT_METHOD_SERVICE);
        if(manager!=null)manager.hideSoftInputFromWindow(search.getWindowToken(),0);search.clearFocus();
    }
    private void load(){
        loadStarted=true;loaded=false;loading.setVisibility(View.VISIBLE);emptyAction.setVisibility(View.GONE);
        try{worker.execute(()->{
            try{Loaded data=loader.load();activity.runOnUiThread(()->{if(!disposed&&!activity.isDestroyed())loaded(data);});}
            catch(Exception e){activity.runOnUiThread(()->{if(!disposed&&!activity.isDestroyed())loadError();});}
        });}catch(java.util.concurrent.RejectedExecutionException e){loadError();}
    }
    private void loadError(){
        loading.setVisibility(View.GONE);empty.setVisibility(View.VISIBLE);list.setVisibility(View.GONE);
        emptyTitle.setText(R.string.sync_loading_error);emptyHint.setText("");emptyAction.setText(R.string.sync_retry);emptyAction.setVisibility(View.VISIBLE);emptyAction.setOnClickListener(v->load());
    }
    private void loaded(Loaded data){
        totalFiles=data.totalFiles;unreadable=data.unreadable;snapshots.clear();fileHashes.clear();List<SyncSelectionModel.Item> items=new ArrayList<>();
        for(Source source:data.sources){
            try{
                JSONObject snapshot=source.snapshot;String id=snapshot.getString("captureId");
                if(id.isBlank()||snapshots.containsKey(id)){unreadable++;continue;}
                JSONArray points=snapshot.getJSONArray("readings");int measured=0,emptyCount=0,invalid=0;
                for(int i=0;i<points.length();i++){
                    JSONObject point=points.optJSONObject(i);if(point==null){invalid++;continue;}
                    String state=point.optString("state"),value=point.optString("valueDecimal");
                    if("EMPTY".equals(state)&&value.isEmpty()){emptyCount++;continue;}
                    if(("OK".equals(state)||"LOCAL_MEASURED".equals(state))&&"mm".equals(point.optString("unit"))&&value.matches("[0-9]+([.][0-9]+)?")&&new BigDecimal(value).signum()>0)measured++;else invalid++;
                }
                snapshots.put(id,snapshot);fileHashes.put(id,source.fileHash);
                items.add(new SyncSelectionModel.Item(id,snapshot.optString("file",ui.getString(R.string.sync_no_name)),source.status,
                    snapshot.optString("editedAt",snapshot.optString("capturedAt","")),points.length(),measured,emptyCount,invalid));
            }catch(Exception e){unreadable++;}
        }
        SyncSelectionModel next=new SyncSelectionModel(items);next.filter(model.filter());next.order(model.order());next.query(search.getText().toString());
        model=next;loaded=true;loading.setVisibility(View.GONE);refresh();
    }
    private void refresh(){
        if(!loaded)return;
        List<SyncSelectionModel.Item> visible=model.visible();adapter.update(visible);
        results.setText(ui.getString(R.string.sync_rows_count,visible.size(),model.total()));sort.setText(model.order()==SyncSelectionModel.Order.RECENT?R.string.sync_recent:R.string.sync_by_name);
        counter.setText(ui.getString(R.string.sync_counter,model.count()));counter.setTextColor(model.count()>0?TEAL:INK);
        proceed.setEnabled(model.count()>0);proceed.setText(model.count()==0?ui.getString(R.string.sync_continue):ui.getString(R.string.sync_continue_count,model.count()));
        clearSelection.setEnabled(model.count()>0);
        int hidden=model.hiddenSelected();String detail=hidden>0?ui.getString(R.string.sync_hidden_selection,hidden):unreadable>0?ui.getString(R.string.sync_unreadable,unreadable):totalFiles>100?ui.getString(R.string.sync_more):"";
        notice.setText(detail);notice.setVisibility(detail.isEmpty()?View.GONE:View.VISIBLE);
        empty.setVisibility(visible.isEmpty()?View.VISIBLE:View.GONE);list.setVisibility(visible.isEmpty()?View.GONE:View.VISIBLE);
        if(visible.isEmpty()){
            emptyTitle.setText(model.total()==0?(unreadable>0?R.string.sync_loading_error:R.string.sync_no_captures):R.string.sync_empty);emptyHint.setText(model.total()==0?R.string.sync_no_captures_hint:R.string.sync_empty_hint);
            emptyAction.setVisibility(model.total()>0?View.VISIBLE:View.GONE);emptyAction.setText(R.string.sync_reset_filters);
            emptyAction.setOnClickListener(v->{search.setText("");filterButtons.get(SyncSelectionModel.Filter.ALL).setChecked(true);model.filter(SyncSelectionModel.Filter.ALL);refresh();});
        }
    }
    private void select(String id){
        if(model.toggle(id)==SyncSelectionModel.Toggle.LIMIT){notice.setText(R.string.sync_limit);notice.setVisibility(View.VISIBLE);notice.announceForAccessibility(ui.getString(R.string.sync_limit));return;}
        refresh();
    }

    private record Row(SyncSelectionModel.Item item,boolean selected){}
    private final class CaptureAdapter extends RecyclerView.Adapter<CaptureHolder> {
        private List<Row> rows=List.of();
        private final Map<String,Long> ids=new HashMap<>();
        CaptureAdapter(){setHasStableIds(true);}
        void update(List<SyncSelectionModel.Item> values){
            List<Row> next=new ArrayList<>();for(var i:values){next.add(new Row(i,model.isSelected(i.id)));ids.computeIfAbsent(i.id,key->(long)ids.size());}
            List<Row> old=rows;
            DiffUtil.DiffResult diff=DiffUtil.calculateDiff(new DiffUtil.Callback(){
                public int getOldListSize(){return old.size();} public int getNewListSize(){return next.size();}
                public boolean areItemsTheSame(int a,int b){return old.get(a).item.id.equals(next.get(b).item.id);}
                public boolean areContentsTheSame(int a,int b){return old.get(a).equals(next.get(b));}
            });rows=next;diff.dispatchUpdatesTo(this);
        }
        @Override public long getItemId(int position){return ids.get(rows.get(position).item.id);}
        @Override public int getItemCount(){return rows.size();}
        @NonNull @Override public CaptureHolder onCreateViewHolder(@NonNull ViewGroup parent,int type){return new CaptureHolder();}
        @Override public void onBindViewHolder(@NonNull CaptureHolder holder,int position){holder.bind(rows.get(position));}
    }
    private final class CaptureHolder extends RecyclerView.ViewHolder {
        final MaterialCardView card;
        final TextView name,date,revision,metrics,status,cells,fileHash;
        final ImageView checked;
        CaptureHolder(){
            super(new MaterialCardView(ui));card=(MaterialCardView)itemView;
            RecyclerView.LayoutParams params=new RecyclerView.LayoutParams(-1,-2);params.bottomMargin=d(10);card.setLayoutParams(params);
            card.setRadius(d(18));card.setCardElevation(0);card.setUseCompatPadding(false);card.setCheckable(true);card.setCheckedIcon(null);
            card.setStrokeWidth(d(1));card.setStrokeColor(BORDER);card.setClickable(true);card.setFocusable(true);
            card.setRippleColor(ColorStateList.valueOf(0x1a087f70));
            LinearLayout body=vertical(ui);body.setPadding(d(14),d(14),d(14),d(14));body.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);card.addView(body);
            LinearLayout top=new LinearLayout(ui);top.setGravity(Gravity.CENTER_VERTICAL);body.addView(top,lp(-1,-2));
            FrameLayout gridIcon=new FrameLayout(ui);gridIcon.setBackground(shape(ui,0xffedf3f6,0,11));LinearLayout.LayoutParams gi=lp(d(38),d(38));gi.rightMargin=d(10);top.addView(gridIcon,gi);
            FrameLayout.LayoutParams imageParams=new FrameLayout.LayoutParams(d(21),d(21),Gravity.CENTER);gridIcon.addView(icon(ui,R.drawable.ic_grid,0xff4d7080),imageParams);
            LinearLayout identity=vertical(ui);top.addView(identity,new LinearLayout.LayoutParams(0,-2,1));
            name=text(ui,"",16,INK,true);name.setMaxLines(2);name.setEllipsize(android.text.TextUtils.TruncateAt.END);identity.addView(name,lp(-1,-2));
            date=text(ui,"",12,MUTED,false);LinearLayout.LayoutParams dt=lp(-1,-2);dt.topMargin=d(6);identity.addView(date,dt);
            checked=icon(ui,R.drawable.ic_sync_check,Color.WHITE);checked.setPadding(d(4),d(4),d(4),d(4));LinearLayout.LayoutParams cp=lp(d(25),d(25));cp.leftMargin=d(8);top.addView(checked,cp);
            LinearLayout detail=new LinearLayout(ui);detail.setGravity(Gravity.CENTER_VERTICAL);LinearLayout.LayoutParams dl=lp(-1,-2);dl.topMargin=d(12);body.addView(detail,dl);
            revision=text(ui,"",11,MUTED,false);revision.setTypeface(android.graphics.Typeface.MONOSPACE);detail.addView(revision,new LinearLayout.LayoutParams(0,-2,1));
            cells=text(ui,"",11,MUTED,false);detail.addView(cells,lp(-2,-2));
            LinearLayout bottom=new LinearLayout(ui);bottom.setGravity(Gravity.CENTER_VERTICAL);LinearLayout.LayoutParams bp=lp(-1,-2);bp.topMargin=d(10);body.addView(bottom,bp);
            status=text(ui,"",11,MUTED,true);status.setPadding(d(8),d(5),d(8),d(5));bottom.addView(status,lp(-2,-2));
            metrics=text(ui,"",12,MUTED,false);metrics.setGravity(Gravity.END);metrics.setPadding(d(8),0,0,0);bottom.addView(metrics,new LinearLayout.LayoutParams(0,-2,1));
            fileHash=text(ui,"",11,MUTED,false);fileHash.setTypeface(android.graphics.Typeface.MONOSPACE);fileHash.setPadding(0,d(10),0,0);body.addView(fileHash,lp(-1,-2));
            card.setOnClickListener(v->{int position=getBindingAdapterPosition();if(position!=RecyclerView.NO_POSITION)select(adapter.rows.get(position).item.id);});
        }
        void bind(Row row){
            var i=row.item;card.setTag(i.id);name.setText(i.name);String formatted=i.date(ZoneId.systemDefault());date.setText(formatted.isBlank()?ui.getString(R.string.sync_no_date):formatted);
            String hash=fileHashes.getOrDefault(i.id,"");fileHash.setText(hash.isBlank()?"":"SHA-256 · "+hash.substring(0,16)+"…");fileHash.setVisibility(hash.isBlank()?View.GONE:View.VISIBLE);
            revision.setText(ui.getString(R.string.sync_revision,i.shortId()));cells.setText(ui.getString(R.string.sync_cells,i.cells));
            String summary=ui.getString(R.string.sync_metrics,i.measured,i.empty)+(i.invalid>0?ui.getString(R.string.sync_invalid,i.invalid):"");metrics.setText(summary);
            status.setText(statusLabel(i.status));status.setTextColor(statusInk(i.status));status.setBackground(shape(ui,statusFill(i.status),0,7));
            card.setChecked(row.selected);card.setCardBackgroundColor(row.selected?SELECTED:Color.WHITE);card.setStrokeColor(row.selected?TEAL:BORDER);card.setStrokeWidth(d(row.selected?2:1));
            checked.setImageTintList(ColorStateList.valueOf(row.selected?Color.WHITE:Color.TRANSPARENT));checked.setBackground(shape(ui,row.selected?TEAL:Color.WHITE,row.selected?0:0xffaabcc4,7));
            card.setContentDescription(i.name+", "+date.getText()+", "+revision.getText()+", "+cells.getText()+", "+summary+", "+status.getText());
            card.setStateDescription(ui.getString(row.selected?R.string.sync_selected_a11y:R.string.sync_unselected_a11y));
            card.setAccessibilityDelegate(new View.AccessibilityDelegate(){@Override public void onInitializeAccessibilityNodeInfo(View host,AccessibilityNodeInfo info){
                super.onInitializeAccessibilityNodeInfo(host,info);info.setClassName("android.widget.CheckBox");info.setCheckable(true);info.setChecked(row.selected);
            }});
        }
    }
}
