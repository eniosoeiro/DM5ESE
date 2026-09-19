package com.dm5ese.usbprobe;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.hardware.usb.*;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class MainActivity extends Activity {
    private static final String PERMISSION = "com.dm5ese.usbprobe.USB_PERMISSION";
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final Object commitLock = new Object();
    private UsbManager usb;
    private CaptureStore store;
    private TextView status, captureTitle, usbBadge;
    private Spinner devicePicker, filePicker;
    private LinearLayout table;
    private LinearLayout measurements, settings, connectionPanel, settingsDetails, filesPanel, live;
    private ScrollView pageScroll;
    private Button measurementsTab, settingsTab, liveTab, liveStart, liveStop;
    private TextView liveState;
    private RetroDigitsView liveValue;
    private TextView liveCoupling, liveReadingHint;
    private CheckBox imageMode;
    private Dm5eScreenView liveScreen;
    private long screenSequence;
    private boolean pendingLive, liveRunning;
    private Button deleteFiles, createGrid, connect, history, refresh, sync;
    private LinearLayout operationPanel;
    private TextView operationMessage;
    private final List<UsbDevice> devices = new ArrayList<>();
    private List<Dm5eProtocol.FileEntry> files = List.of();
    private String listedDevice, pendingName;
    private volatile AtomicBoolean cancellation = new AtomicBoolean();
    private boolean active, busy, creating, deleting;
    private int generation, page;
    private JSONObject capture;
    private final CellMeasurement cellMeasurement = new CellMeasurement();
    private final Map<String, TextView> matrixCells = new HashMap<>();
    private final ExecutorService cellWriter = Executors.newSingleThreadExecutor();
    private String matrixCaptureId;
    private TextView cellHint;
    private Button cellStart, cellSave, cellStop, cellInstrumentSave;
    private Button sendPending;
    private boolean batchRunning;
    private Button downloadAll;
    private Button createOffline,sendNewFiles;
    private boolean downloadingAll;
    private volatile String downloadAllProgress="";
    private final Set<String> pendingCells = new HashSet<>();
    private boolean savingCell;
    private byte[] exportBytes;
    private byte[] logExportBytes;
    private DiagnosticLog diagnosticLog;
    private final android.os.Handler diagnosticHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private String lastUsbReport = "";
    private int diagnosticTicks;
    private final Runnable sampleUsb = new Runnable() {
        @Override public void run() {
            if (!active) return;
            String report = usbDiagnostic();
            if (!report.equals(lastUsbReport) || diagnosticTicks++ % 5 == 0) {
                logEvent("estado_usb", report); lastUsbReport = report;
            }
            diagnosticHandler.postDelayed(this, 2000);
        }
    };
    private void logEvent(String type, String detail) { if (diagnosticLog != null) diagnosticLog.event(type, detail); }

    private final BroadcastReceiver permissionReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            if (active && PERMISSION.equals(intent.getAction())) { logEvent("resposta_permissao", usbDiagnostic()); resumePermission(); }
        }
    };
    private final BroadcastReceiver attachmentReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            logEvent("evento_usb", intent.getAction());
            cancel(); pendingName = null; clearDirectory(); refreshDevices();
            status.setText("Conexão USB alterada. Liste novamente. Capturas salvas permanecem no celular.");
        }
    };

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        usb = (UsbManager) getSystemService(USB_SERVICE); store = new CaptureStore(this);
        try { diagnosticLog = new DiagnosticLog(getFilesDir()); }
        catch (IOException e) { Toast.makeText(this, "Log indisponível: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
        logEvent("inicio_app", "v" + appVersion());
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setBackground(new android.graphics.drawable.GradientDrawable(android.graphics.drawable.GradientDrawable.Orientation.TOP_BOTTOM, new int[]{InstrumentStyle.NAVY, Color.WHITE}));
        LinearLayout brand = new LinearLayout(this); brand.setOrientation(LinearLayout.VERTICAL);
        brand.setPadding(dp(20), dp(10), dp(20), dp(16));
        brand.setBackgroundColor(InstrumentStyle.NAVY); root.addView(brand);
        LinearLayout heading = new LinearLayout(this); heading.setGravity(android.view.Gravity.CENTER_VERTICAL); brand.addView(heading);
        ImageView logo = new ImageView(this); logo.setImageResource(R.drawable.ic_es_mark);
        logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        heading.addView(logo, new LinearLayout.LayoutParams(dp(56), dp(56)));
        LinearLayout wordmark = new LinearLayout(this); wordmark.setOrientation(LinearLayout.VERTICAL); wordmark.setPadding(dp(12), 0, 0, 0);
        heading.addView(wordmark, new LinearLayout.LayoutParams(0, -2, 1));
        TextView title = text("ES Medição", 25); title.setTypeface(android.graphics.Typeface.create("sans-serif-medium", android.graphics.Typeface.NORMAL)); title.setTextColor(Color.WHITE); wordmark.addView(title);
        TextView subtitle = text("PRECISÃO EM CADA PONTO", 9); subtitle.setLetterSpacing(0.16f); subtitle.setTextColor(InstrumentStyle.MINT); wordmark.addView(subtitle);
        usbBadge = text("USB · não conectado", 12); usbBadge.setTextColor(0xffc9dbe1);
        usbBadge.setPadding(dp(12), dp(7), dp(12), dp(7)); usbBadge.setBackground(InstrumentStyle.surface(0xff203e4b, 0, dp(20)));
        LinearLayout.LayoutParams badgeParams = new LinearLayout.LayoutParams(-2, -2); badgeParams.topMargin=dp(12); brand.addView(usbBadge, badgeParams);
        operationPanel = new LinearLayout(this); operationPanel.setGravity(android.view.Gravity.CENTER_VERTICAL);
        operationPanel.setPadding(dp(20), dp(12), dp(20), dp(12)); operationPanel.setBackgroundColor(0xffdceff5);
        TextView spinner = text("⌛", 28); spinner.setContentDescription("Operação em andamento");
        operationPanel.addView(spinner, new LinearLayout.LayoutParams(dp(38), dp(38)));
        operationMessage = text("Aguarde…", 15); operationMessage.setTypeface(null, android.graphics.Typeface.BOLD);
        operationMessage.setPadding(dp(14), 0, 0, 0); operationMessage.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        operationPanel.addView(operationMessage, new LinearLayout.LayoutParams(0, -2, 1));
        operationPanel.setVisibility(View.GONE); root.addView(operationPanel);
        ScrollView scroll = new ScrollView(this); scroll.setBackgroundColor(InstrumentStyle.PAGE); scroll.setFillViewport(true); pageScroll = scroll; root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout tabs = new LinearLayout(this); tabs.setPadding(dp(12), dp(8), dp(12), dp(8));
        tabs.setBackground(InstrumentStyle.surface(Color.WHITE, InstrumentStyle.LINE, 0)); root.addView(tabs);
        measurementsTab = button(tabs, "Medições", () -> showTab(0));
        liveTab = button(tabs, "Visor", () -> showTab(1));
        settingsTab = button(tabs, "Ajustes", () -> showTab(2)); settingsTab.setContentDescription("Configurações");
        navigationIcon(measurementsTab, R.drawable.ic_grid); navigationIcon(liveTab, R.drawable.ic_pulse); navigationIcon(settingsTab, R.drawable.ic_settings);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); scroll.addView(content);
        measurements = new LinearLayout(this); measurements.setOrientation(LinearLayout.VERTICAL); content.addView(measurements);
        live = new LinearLayout(this); live.setOrientation(LinearLayout.VERTICAL); content.addView(live);
        settings = new LinearLayout(this); settings.setOrientation(LinearLayout.VERTICAL); content.addView(settings);
        TextView settingsTitle = text("Ajustes", 30); settingsTitle.setTypeface(null, android.graphics.Typeface.BOLD); settingsTitle.setPadding(0, 0, 0, dp(12)); settings.addView(settingsTitle);
        LinearLayout layout = measurements;
        int padding = (int) (16 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, dp(16), padding, padding);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            view.setPadding(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom()); return insets;
        });
        getWindow().setStatusBarColor(InstrumentStyle.NAVY);
        getWindow().setNavigationBarColor(Color.WHITE);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        TextView sectionLabel = text("ESPAÇO DE TRABALHO", 10); sectionLabel.setLetterSpacing(0.14f); sectionLabel.setTextColor(InstrumentStyle.TEAL); layout.addView(sectionLabel);
        TextView workspaceTitle = text("Medições", 30); workspaceTitle.setTypeface(null, android.graphics.Typeface.BOLD); workspaceTitle.setPadding(0, dp(4), 0, dp(12)); layout.addView(workspaceTitle);
        LinearLayout actions = new LinearLayout(this); layout.addView(actions);
        button(actions, "Arquivos", () -> filesPanel.setVisibility(filesPanel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
        history = button(actions, "Histórico", this::history);
        status = text("Conecte o DM5E em Configurações para importar seus arquivos.", 13);
        status.setPadding(dp(14), dp(12), dp(14), dp(12));
        status.setBackground(InstrumentStyle.surface(0xffe0ebef, 0, dp(12)));
        status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        LinearLayout.LayoutParams statusParams = new LinearLayout.LayoutParams(-1, -2); statusParams.setMargins(0, dp(10), 0, dp(12)); layout.addView(status, statusParams);
        connectionPanel = card(settings, "CONEXÃO USB");
        TextView connectionStatus = text(status.getText().toString(), 13); connectionPanel.addView(connectionStatus);
        status.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { connectionStatus.setText(s); if (operationMessage != null) operationMessage.setText(s); }
            public void afterTextChanged(android.text.Editable s) { }
        });
        devicePicker = new Spinner(this); connectionPanel.addView(devicePicker);
        refresh = button(connectionPanel, "Atualizar dispositivos", () -> { clearDirectory(); refreshDevices(); });
        button(connectionPanel, "Diagnóstico USB", this::showUsbDiagnostic);
        button(connectionPanel, "Salvar log para enviar", this::exportDiagnosticLog);
        connect = button(connectionPanel, "Conectar e listar arquivos", this::requestDirectory);
        InstrumentStyle.button(connect, true, false);
        settingsDetails = new LinearLayout(this); settingsDetails.setOrientation(LinearLayout.VERTICAL); settings.addView(settingsDetails);
        filesPanel = card(layout, "CENTRAL DE ARQUIVOS");
        filesPanel.addView(text("Prepare uma matriz offline ou importe as medições do seu instrumento.", 14));
        filePicker = new Spinner(this, Spinner.MODE_DROPDOWN);
        filePicker.setBackgroundColor(Color.TRANSPARENT);
        filePicker.setPopupBackgroundDrawable(new android.graphics.drawable.ColorDrawable(Color.WHITE));
        filePicker.setDropDownVerticalOffset(dp(6));
        LinearLayout.LayoutParams pickerParams = new LinearLayout.LayoutParams(-1, -2);
        pickerParams.setMargins(0, dp(8), 0, dp(10)); filesPanel.addView(filePicker, pickerParams);
        updateFilePicker();
        filePicker.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                if (active && !busy && position > 0) download();
            }
            @Override public void onNothingSelected(AdapterView<?> parent) { }
        });
        downloadAll=button(filesPanel,"Baixar todos para o celular",this::downloadAllFiles);
        InstrumentStyle.button(downloadAll,true,false);
        createOffline=button(filesPanel,"Criar matriz no celular",this::createOfflineGrid);
        sendNewFiles=button(filesPanel,"Enviar arquivos novos ao DM5E",this::chooseNewFiles);
        createGrid = button(filesPanel, "Criar matriz no DM5E", this::createGrid);
        deleteFiles = button(filesPanel, "Apagar arquivos do DM5E", this::chooseDeletion);
        InstrumentStyle.button(deleteFiles, false, true);
        button(filesPanel, "Cancelar leitura", () -> {
            if(downloadingAll || batchRunning) { cancel(); return; }
            boolean wasCreating = creating, wasDeleting = deleting; cancel();
            status.setText(wasDeleting ? "Exclusão interrompida. Alguns arquivos podem ter sido apagados. Liste novamente."
                : wasCreating ? "Envio interrompido. O arquivo pode estar incompleto no DM5E. Confira o instrumento antes de tentar novamente."
                : "Leitura cancelada. Nenhuma captura parcial foi salva.");
        });
        captureTitle = text("Suas medições", 23); captureTitle.setTypeface(null, android.graphics.Typeface.BOLD); captureTitle.setPadding(0, dp(12), 0, dp(4)); layout.addView(captureTitle);
        table = new LinearLayout(this); table.setOrientation(LinearLayout.VERTICAL); layout.addView(table);
        LinearLayout empty = card(table, "SUA PRÓXIMA INSPEÇÃO COMEÇA AQUI");
        ImageView emptyIcon = new ImageView(this); emptyIcon.setImageResource(R.drawable.ic_pulse); emptyIcon.setColorFilter(InstrumentStyle.TEAL);
        emptyIcon.setPadding(dp(18), dp(18), dp(18), dp(18)); emptyIcon.setBackground(InstrumentStyle.surface(0xffe4f6ee, 0, dp(20)));
        LinearLayout.LayoutParams illustration = new LinearLayout.LayoutParams(dp(76), dp(76)); illustration.gravity=android.view.Gravity.CENTER_HORIZONTAL; illustration.setMargins(0, dp(16), 0, dp(20)); empty.addView(emptyIcon, illustration);
        TextView emptyTitle = text("Cada ponto conta.", 25); emptyTitle.setTypeface(null, android.graphics.Typeface.BOLD); empty.addView(emptyTitle);
        empty.addView(text("Crie uma matriz no celular ou conecte o DM5E para importar suas espessuras. Os arquivos salvos ficam no Histórico.", 15));
        Button start = button(empty, "Criar matriz no celular", this::createOfflineGrid); InstrumentStyle.button(start, true, false);
        button(empty, "Conectar instrumento", () -> showTab(2));
        filesPanel.setVisibility(View.GONE);
        sync = button(layout, "Sincronizar", () -> ThicknessSyncDialog.show(this, store, worker, () -> {
            try { renderCapture(); } catch (JSONException e) { status.setText("Recibo salvo, mas a prévia local não pôde ser atualizada."); }
        }));
        InstrumentStyle.button(sync, false, false);
        createLiveView(); setContentView(root); showTab(0); renderSettings();
        registerReceiver(permissionReceiver, new IntentFilter(PERMISSION), Context.RECEIVER_NOT_EXPORTED);
        IntentFilter filter = new IntentFilter(UsbManager.ACTION_USB_DEVICE_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_DEVICE_DETACHED);
        registerReceiver(attachmentReceiver, filter, Context.RECEIVER_EXPORTED);
        List<File> savedFiles = store.history();
        if (!savedFiles.isEmpty()) {
            try {
                capture = store.load(savedFiles.get(0));
                String draftId=getSharedPreferences("batch",MODE_PRIVATE).getString("draft","");
                if(!draftId.isEmpty())for(File candidate:savedFiles) {
                    JSONObject draft=store.load(candidate);
                    if(draftId.equals(draft.optString("captureId"))) { capture=draft; break; }
                }
                renderCapture(); filesPanel.setVisibility(View.GONE); status.setText("Captura salva no celular • disponível offline");
            }
            catch (Exception e) { logEvent("erro", e.toString()); status.setText("Falha ao abrir captura salva: " + e.getMessage()); }
        }
        updateControls();
    }
    private TextView text(String value, int size) {
        TextView t = new TextView(this); t.setText(value); t.setTextSize(size); t.setTextColor(InstrumentStyle.INK);
        t.setFontFeatureSettings("tnum"); t.setLineSpacing(dp(2), 1f); return t;
    }
    private Button button(LinearLayout parent, String label, Runnable action) {
        Button b = new Button(this); b.setText(label); b.setAllCaps(false); b.setTextSize(14);
        b.setTypeface(null, android.graphics.Typeface.BOLD); b.setMinHeight(dp(48)); b.setMinimumWidth(0);
        InstrumentStyle.button(b, false, false);
        int icon = label.equals("Arquivos") ? R.drawable.ic_folder : label.equals("Histórico") ? R.drawable.ic_history
            : label.startsWith("Criar matriz") ? R.drawable.ic_plus : label.startsWith("Baixar") ? R.drawable.ic_download
            : label.startsWith("Enviar") ? R.drawable.ic_upload : label.startsWith("Iniciar") ? R.drawable.ic_play
            : label.startsWith("Parar") ? R.drawable.ic_stop : label.startsWith("Conectar") ? R.drawable.ic_usb
            : label.equals("Sincronizar") ? R.drawable.ic_cloud : 0;
        if (icon != 0) { android.graphics.drawable.Drawable drawable = getDrawable(icon); drawable.setBounds(0, 0, dp(20), dp(20)); b.setCompoundDrawablesRelative(drawable, null, null, null); b.setCompoundDrawablePadding(dp(8)); }
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(parent.getOrientation() == LinearLayout.HORIZONTAL ? 0 : -1, -2,
            parent.getOrientation() == LinearLayout.HORIZONTAL ? 1 : 0);
        params.setMargins(dp(3), dp(5), dp(3), dp(5));
        b.setOnClickListener(v -> action.run()); parent.addView(b, params); return b;
    }
    private int dp(int value) { return (int)(value * getResources().getDisplayMetrics().density); }
    private void navigationIcon(Button button, int resource) {
        android.graphics.drawable.Drawable drawable = getDrawable(resource); drawable.setBounds(0, 0, dp(22), dp(22));
        button.setCompoundDrawables(null, drawable, null, null); button.setCompoundDrawablePadding(dp(4));
        button.setTextSize(11); button.setPadding(dp(4), dp(8), dp(4), dp(8));
    }
    private LinearLayout card(LinearLayout parent, String title) {
        LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(16), dp(16), dp(16), dp(16));
        android.graphics.drawable.GradientDrawable bg = InstrumentStyle.surface(Color.WHITE, InstrumentStyle.LINE, dp(18));
        panel.setBackground(bg);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.setMargins(0, dp(8), 0, dp(12)); parent.addView(panel, params);
        TextView heading = text(title, 12); heading.setLetterSpacing(0.08f); heading.setTypeface(null, android.graphics.Typeface.BOLD); heading.setPadding(0, 0, 0, dp(10)); panel.addView(heading);
        return panel;
    }
    private void detail(LinearLayout panel, String label, String value) {
        TextView name = text(label, 12); name.setTextColor(Color.rgb(87, 109, 127)); panel.addView(name);
        TextView data = text(value == null || value.isBlank() ? "Não informado pelo arquivo" : value, 17);
        data.setPadding(0, dp(3), 0, dp(14)); panel.addView(data);
    }
    private void showTab(int selected) {
        if (pageScroll != null) pageScroll.post(() -> pageScroll.scrollTo(0, 0));
        LinearLayout[] panels = {measurements, live, settings};
        Button[] buttons = {measurementsTab, liveTab, settingsTab};
        for (int i = 0; i < panels.length; i++) {
            panels[i].setVisibility(i == selected ? View.VISIBLE : View.GONE);
            buttons[i].setBackgroundTintList(null);
            buttons[i].setBackground(new android.graphics.drawable.RippleDrawable(android.content.res.ColorStateList.valueOf(0x20087f70), InstrumentStyle.surface(i == selected ? 0xffe4f6ee : Color.WHITE, 0, dp(16)), null));
            buttons[i].setTextColor(i == selected ? InstrumentStyle.TEAL : InstrumentStyle.MUTED);
            buttons[i].setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(i == selected ? InstrumentStyle.TEAL : InstrumentStyle.MUTED));
            buttons[i].setSelected(i == selected);
        }
    }
    private void createLiveView() {
        TextView liveTitle = text("Visor de espessura", 30); liveTitle.setTypeface(null, android.graphics.Typeface.BOLD); live.addView(liveTitle);
        live.addView(text("Espessura numérica recebida pelo cabo USB", 14));
        LinearLayout numeric = card(live, "DM5E  /  LEITURA USB");
        android.graphics.drawable.GradientDrawable housing = new android.graphics.drawable.GradientDrawable();
        housing.setColor(InstrumentStyle.NAVY); housing.setCornerRadius(dp(18)); housing.setStroke(dp(1), 0xff29505e); numeric.setBackground(housing);
        ((TextView) numeric.getChildAt(0)).setTextColor(0xffcbd5d8);
        LinearLayout lcd = new LinearLayout(this); lcd.setOrientation(LinearLayout.VERTICAL); lcd.setPadding(dp(12), dp(12), dp(12), dp(12));
        android.graphics.drawable.GradientDrawable glass = new android.graphics.drawable.GradientDrawable();
        glass.setColor(0xffe5f7ee); glass.setCornerRadius(dp(16)); lcd.setBackground(glass);
        numeric.addView(lcd, new LinearLayout.LayoutParams(-1, -2));
        LinearLayout lcdHeader = new LinearLayout(this); lcd.addView(lcdHeader);
        TextView unit = text("ESPESSURA", 12);
        unit.setTypeface(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD); unit.setTextColor(0xff26391f);
        lcdHeader.addView(unit, new LinearLayout.LayoutParams(0, -2, 1));
        TextView mm = text("mm", 14); mm.setTextColor(0xff26391f); mm.setTypeface(android.graphics.Typeface.MONOSPACE); lcdHeader.addView(mm);
        liveValue = new RetroDigitsView(this); lcd.addView(liveValue, new LinearLayout.LayoutParams(-1, -2));
        liveCoupling = text("AGUARDANDO LEITURA", 16); liveCoupling.setGravity(android.view.Gravity.CENTER);
        liveCoupling.setTypeface(null, android.graphics.Typeface.BOLD); liveCoupling.setPadding(dp(8), dp(10), dp(8), dp(10)); lcd.addView(liveCoupling);
        liveReadingHint = text("Conecte o DM5E para começar", 13); liveReadingHint.setGravity(android.view.Gravity.CENTER);
        liveReadingHint.setTextColor(0xff26391f); liveReadingHint.setPadding(0, dp(8), 0, 0); lcd.addView(liveReadingHint);
        TextView velocity = text("VELOCIDADE DO SOM\nConfira o ajuste atual no DM5E", 12);
        velocity.setTextColor(0xffdce4e5); velocity.setPadding(0, dp(12), 0, 0); numeric.addView(velocity);
        clearNumeric();
        imageMode = new CheckBox(this); imageMode.setText("Modo imagem (opcional)"); live.addView(imageMode);
        LinearLayout display = card(live, "VISOR DO INSTRUMENTO");
        liveScreen = new Dm5eScreenView(this);
        display.addView(liveScreen, new LinearLayout.LayoutParams(-1, -2));
        liveScreen.setVisibility(View.GONE);
        imageMode.setOnCheckedChangeListener((button, checked) -> {
            liveScreen.setVisibility(checked ? View.VISIBLE : View.GONE);
            numeric.setVisibility(checked ? View.GONE : View.VISIBLE);
        });
        liveState = text("Conecte o instrumento e inicie o visor.", 15);
        display.addView(liveState);
        liveStart = button(live, "Iniciar visor USB", this::requestLive);
        InstrumentStyle.button(liveStart, true, false);
        liveStop = button(live, "Parar visor", this::cancel);
        button(live, "Diagnóstico USB", this::showUsbDiagnostic);
        button(live, "Salvar log para enviar", this::exportDiagnosticLog);
        LinearLayout info = card(live, "ACOMPANHAMENTO POR USB");
        info.addView(text("O modo numérico consulta a espessura diretamente por USB, em milímetros. Sem acoplamento (U), o número é identificado como valor retido. A imagem do visor é opcional.", 14));
        info.addView(text("Sessão experimental de 2 minutos. Respostas inválidas são descartadas; após 1,5 s sem atualização, o visor fica indisponível. Nenhum parâmetro é alterado.", 13));
        info.addView(text("A velocidade do som da aba Medições pertence ao arquivo salvo. A imagem acima mostra apenas o que está na tela atual do DM5E.", 13));
    }
    private void requestLive() {
        UsbDevice device = selected();
        if (device == null || busy) return;
        pendingLive = true;
        if (usb.hasPermission(device)) { pendingName = null; pendingLive = false; startLive(device); return; }
        pendingName = device.getDeviceName();
        liveState.setText("Autorize o acesso USB para iniciar o visor.");
        PendingIntent intent = PendingIntent.getBroadcast(this, device.getDeviceId(), new Intent(PERMISSION).setPackage(getPackageName()),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        logEvent("pedido_permissao", "Solicitando autorização USB ao Android"); usb.requestPermission(device, intent);
    }
    private void startLive(UsbDevice device) {
        if (busy) return;
        if (!imageMode.isChecked()) { startNumeric(device); return; }
        busy = true; liveRunning = true; updateControls();
        liveScreen.clear(); screenSequence++;
        liveState.setText("Verificando a identidade do DM5E…");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        AtomicBoolean token = new AtomicBoolean(); cancellation = token; int request = ++generation;
        worker.execute(() -> {
            String result;
            try {
                File folder = new File(getFilesDir(), "live-diagnostics");
                if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Não foi possível salvar o diagnóstico.");
                File log = new File(folder, System.currentTimeMillis() + ".jsonl");
                try (Dm5eUsb connection = new Dm5eUsb(usb, device, token);
                     Writer writer = new OutputStreamWriter(new FileOutputStream(log), StandardCharsets.UTF_8)) {
                    new Dm5eProtocol(connection).identify();
                    writer.write(new JSONObject().put("event", "start").put("at", java.time.Instant.now().toString())
                        .put("mode", "screen_8Y").toString() + "\n"); writer.flush();
                    long start = android.os.SystemClock.elapsedRealtime();
                    int frames = 0, rejected = 0, failures = 0;
                    while (!token.get() && android.os.SystemClock.elapsedRealtime() - start < 120000) {
                        byte[] frame = connection.readScreen();
                        boolean complete = frame.length == Dm5eScreen.BYTES;
                        if (complete) { frames++; failures = 0; } else { rejected++; failures++; }
                        writer.write(new JSONObject().put("at", java.time.Instant.now().toString())
                            .put("size", frame.length).put("accepted", complete)
                            .put("base64", Base64.getEncoder().encodeToString(frame)).toString() + "\n"); writer.flush();

                        runOnUiThread(() -> {
                            if (!active || generation != request) return;
                            if (complete) {
                                try { liveScreen.show(frame); }
                                catch (IOException e) { liveScreen.clear(); liveState.setText(e.getMessage()); return; }
                                liveState.setText("USB ativo · imagem recebida agora");
                                final long sequence = ++screenSequence;
                                liveScreen.postDelayed(() -> {
                                    if (generation == request && liveRunning && screenSequence == sequence) {
                                        liveScreen.clear(); liveState.setText("Sem imagem recente. Aguardando o DM5E…");
                                    }
                                }, 1500);
                            } else {
                                liveScreen.clear(); screenSequence++;
                                liveState.setText("Imagem incompleta descartada. Tentando novamente…");
                            }
                        });
                        if (failures >= 5) throw new IOException("Cinco imagens incompletas. Confira o cabo e reinicie o visor.");
                        android.os.SystemClock.sleep(250);
                    }
                    result = "Sessão encerrada: " + frames + " imagens recebidas; " + rejected + " descartadas.";
                    writer.write(new JSONObject().put("event", "end").put("frames", frames).put("rejected", rejected)
                        .put("cancelled", token.get()).toString() + "\n");
                }
            } catch (Exception e) { logEvent("erro", e.toString()); result = "Visor encerrado: " + e.getMessage(); }
            final String outcome = result;
            runOnUiThread(() -> {
                if (!active || generation != request) return;
                busy = false; liveRunning = false; liveScreen.clear(); screenSequence++;
                liveState.setText(outcome); updateControls();
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        });
    }
    private void clearNumeric() {
        cellMeasurement.clear(); updateCellPreview();
        liveValue.setText("—,—"); liveValue.setTextColor(0xff59654a);
        liveCoupling.setText("SEM LEITURA ATUAL"); liveCoupling.setBackgroundColor(0xff606950); liveCoupling.setTextColor(Color.WHITE);
        liveReadingHint.setText("Aguardando dados do instrumento");
    }
    private void startNumeric(UsbDevice device) {
        busy = true; liveRunning = true; updateControls(); clearNumeric(); liveScreen.clear();
        liveState.setText("Consultando espessura numérica…");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        AtomicBoolean token = new AtomicBoolean(); cancellation = token; int request = ++generation;
        worker.execute(() -> {
            String result;
            try {
                File folder = new File(getFilesDir(), "live-diagnostics");
                if (!folder.isDirectory() && !folder.mkdirs()) throw new IOException("Falha ao salvar diagnóstico.");
                File log = new File(folder, System.currentTimeMillis() + "-numeric.jsonl");
                try (Dm5eUsb connection = new Dm5eUsb(usb, device, token);
                     Writer writer = new OutputStreamWriter(new FileOutputStream(log), StandardCharsets.UTF_8)) {
                    new Dm5eProtocol(connection).identify(); connection.discardInput();
                    writer.write(new JSONObject().put("event", "start").put("mode", "MS")
                        .put("at", java.time.Instant.now().toString()).toString() + "\n"); writer.flush();
                    long start = android.os.SystemClock.elapsedRealtime(); int count = 0, failures = 0;
                    while (!token.get() && android.os.SystemClock.elapsedRealtime() - start < 120000) {
                        connection.write("\u001bMS\r");
                        byte[] response = connection.readLine();
                        Dm5eLiveReading reading;
                        try { reading = Dm5eLiveReading.parse(response); failures = 0; }
                        catch (IOException e) {
                            writer.write(new JSONObject().put("event", "rejected").put("at", java.time.Instant.now().toString())
                                .put("base64", Base64.getEncoder().encodeToString(response)).toString() + "\n"); writer.flush();
                            runOnUiThread(() -> {
                                if (active && generation == request) {
                                    clearNumeric(); screenSequence++; liveState.setText("Resposta inválida descartada. Tentando novamente…");
                                }
                            });
                            if (++failures >= 5) throw e;
                            connection.discardInput(); continue;
                        }
                        writer.write(new JSONObject().put("at", java.time.Instant.now().toString())
                            .put("raw", reading.raw()).put("millimetres", reading.millimetres().toPlainString())
                            .put("coupled", reading.coupled()).toString() + "\n"); writer.flush();
                        count++;
                        final long receivedAt = android.os.SystemClock.elapsedRealtime();
                        runOnUiThread(() -> {
                            if (!active || generation != request) return;
                            cellMeasurement.accept(reading, receivedAt); updateCellPreview();
                            liveValue.setText(reading.millimetres().setScale(2, java.math.RoundingMode.HALF_UP).toPlainString().replace('.', ','));
                            liveValue.setTextColor(reading.coupled() ? 0xff173b20 : 0xff596044);
                            liveCoupling.setText(reading.coupled() ? "●  ACOPLADO" : "⚠  SEM ACOPLAMENTO");
                            liveCoupling.setBackgroundColor(reading.coupled() ? 0xff146137 : 0xffffc65b);
                            liveCoupling.setTextColor(reading.coupled() ? Color.WHITE : 0xff412b00);
                            liveReadingHint.setText(reading.coupled() ? "LEITURA ATIVA · cabeçote acoplado" : "VALOR RETIDO · não é uma nova medição");
                            liveState.setText("USB ativo · resposta numérica recebida");
                            final long sequence = ++screenSequence;
                            liveValue.postDelayed(() -> {
                                if (generation == request && liveRunning && screenSequence == sequence) {
                                    clearNumeric(); liveState.setText("Sem resposta recente do DM5E…");
                                }
                            }, 1500);
                        });
                        android.os.SystemClock.sleep(400);
                    }
                    result = "Sessão numérica encerrada: " + count + " respostas.";
                    writer.write(new JSONObject().put("event", "end").put("count", count).toString() + "\n");
                }
            } catch (Exception e) { logEvent("erro", e.toString()); result = "Leitura numérica encerrada: " + e.getMessage(); }
            final String outcome = result;
            runOnUiThread(() -> {
                if (!active || generation != request) return;
                clearNumeric(); screenSequence++; busy = false; liveRunning = false;
                liveState.setText(outcome); updateControls();
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
            });
        });
    }
    private void renderSettings() {
        LinearLayout settings = settingsDetails;
        settings.removeAllViews();
        TextView version = text("ES Medição  ·  VERSÃO " + appVersion(), 11); version.setTextColor(InstrumentStyle.MUTED); version.setPadding(0, dp(8), 0, dp(12)); settings.addView(version);
        settings.addView(text("Configurações capturadas", 23));
        TextView note = text("Valores do arquivo importado. Não representam uma consulta ao estado atual do instrumento.", 13); note.setPadding(0, dp(8), 0, dp(8)); settings.addView(note);
        if (capture == null) { settings.addView(text("Importe ou abra uma captura para consultar os dados.", 16)); return; }
        JSONObject meta = capture.optJSONObject("metadata"); if (meta == null) return;
        LinearLayout instrument = card(settings, "INSTRUMENTO • ARQUIVO " + capture.optString("file"));
        detail(instrument, "Identificação", meta.optString("INMD"));
        detail(instrument, "Número de série", meta.optString("SRNM"));
        detail(instrument, "Firmware", meta.optString("SFVR"));
        LinearLayout acoustic = card(settings, "PARÂMETROS ACÚSTICOS");
        detail(acoustic, "Velocidade do som no cabeçalho", meta.optString("VELC").isEmpty() ? "" : meta.optString("VELC").replace('.', ',') + " m/s");
        detail(acoustic, "Unidade das espessuras", meta.optString("UNIT"));
        LinearLayout probe = card(settings, "CABEÇOTE / TRANSDUTOR");
        detail(probe, "Modelo selecionado", "Não identificado nos dados recebidos");
        detail(probe, "Frequência nominal", "Não capturada");
        detail(probe, "Diâmetro / área ativa", "Não capturado");
        detail(probe, "Tipo de elemento", "Não identificado");
        probe.addView(text("A ficha técnica depende da identificação do cabeçote. A velocidade do material não identifica o modelo.", 13));
        LinearLayout file = card(settings, "ESTRUTURA DO ARQUIVO");
        detail(file, "Formato", meta.optString("TPNM") + " · " + meta.optString("ADDR"));
        detail(file, "Matriz", meta.optString("L2NL") + " linhas × " + meta.optString("L3NL") + " colunas");
        detail(file, "Quantidade de pontos", meta.optString("NMBR"));
        detail(file, "Descrição", meta.optString("DESC"));
        detail(file, "Data de criação no DM5E", "Ainda não confirmada");
        LinearLayout storage = card(settings, "ARMAZENAMENTO");
        detail(storage, "Capturas", "Salvas neste dispositivo"); detail(storage, "Nuvem", "Sincronização ainda não implementada");
        storage.addView(text("Versão experimental • USB e formatos adicionais ainda em validação.", 13));
    }
    private <T> void options(Spinner picker, List<T> values) {
        ArrayAdapter<T> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); picker.setAdapter(adapter);
    }
    @Override protected void onStart() { super.onStart(); active = true; refreshDevices(); resumePermission(); diagnosticHandler.removeCallbacks(sampleUsb); diagnosticHandler.post(sampleUsb); }
    @Override protected void onStop() { ThicknessSyncDialog.pause(this); logEvent("pausa_app", "Leituras interrompidas ao sair da tela"); active = false; diagnosticHandler.removeCallbacks(sampleUsb); cancel(); super.onStop(); }
    @Override protected void onDestroy() {
        unregisterReceiver(permissionReceiver); unregisterReceiver(attachmentReceiver); worker.shutdownNow(); cellWriter.shutdown(); super.onDestroy();
    }
    private void cancel() {
        if(downloadingAll) {
            downloadingAll=false;
            status.setText("Download cancelado. "+downloadAllProgress+". Os arquivos completos já salvos continuam no Histórico.");
        }
        if(batchRunning) {
            batchRunning=false;
            status.setText("Envio interrompido. Algumas células podem ter sido gravadas. Rascunho preservado; o próximo envio conferirá o DM5E antes de continuar.");
            logEvent("envio_lote_cancelado","Resultado parcial possível; conferir remotamente antes de continuar.");
        }
        if (deleting) {
            logEvent("exclusao_interrompida", "Conferir a lista do instrumento; exclusões já realizadas não são desfeitas.");
            status.setText("Exclusão interrompida. Alguns arquivos podem ter sido apagados. Liste novamente.");
            deleting = false;
        }
        if (creating) {
            logEvent("criacao_interrompida", "Arquivo pode estar incompleto no instrumento; conferir antes de novo envio.");
            status.setText("Criação interrompida. O arquivo pode estar incompleto no DM5E. Confira o instrumento antes de novo envio.");
            creating = false;
        }
        synchronized (commitLock) { cancellation.set(true); generation++; }
        if (liveRunning && liveState != null) { liveScreen.clear(); clearNumeric(); screenSequence++; liveState.setText("Visor interrompido."); }
        liveRunning = false; busy = false; updateControls(); getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    }
    private void updateControls() {
        if (connect == null || sync == null) return;
        if (usbBadge != null) {
            usbBadge.setText(devices.isEmpty() ? "○  USB não conectado  ·  modo offline"
                : "●  DM5E detectado por USB" + (busy ? "  ·  em uso" : ""));
            usbBadge.setTextColor(devices.isEmpty() ? 0xffc9dbe1 : 0xff8ee3d0);
        }
        if (operationPanel != null) operationPanel.setVisibility(busy && !liveRunning ? View.VISIBLE : View.GONE);
        createGrid.setEnabled(!busy && !devices.isEmpty());
        if (deleteFiles != null) deleteFiles.setEnabled(!busy);
        if(downloadAll!=null)downloadAll.setEnabled(!busy && !savingCell && !devices.isEmpty());
        if(createOffline!=null)createOffline.setEnabled(!busy && !savingCell);
        if(sendNewFiles!=null)sendNewFiles.setEnabled(!busy && !savingCell && !devices.isEmpty());
        connect.setEnabled(!busy && !devices.isEmpty()); refresh.setEnabled(!busy);
        devicePicker.setEnabled(!busy); filePicker.setEnabled(!busy && !files.isEmpty());
        history.setEnabled(!busy);
        sync.setEnabled(!busy && !store.history().isEmpty());
        if (liveStart != null) { liveStart.setEnabled(!busy && !devices.isEmpty()); liveStop.setEnabled(liveRunning); }
        if (imageMode != null) imageMode.setEnabled(!busy);
        if (savingCell) { history.setEnabled(false); filePicker.setEnabled(false); }
        updateCellPreview();
    }
    private void updateFilePicker() {
        List<String> labels = new ArrayList<>(); labels.add("Selecione um arquivo para abrir");
        for (Dm5eProtocol.FileEntry file : files) labels.add(file.name());
        filePicker.setAdapter(new FilePickerAdapter(this, filePicker, labels));
        filePicker.setSelection(0);
    }
    private void clearDirectory() { files = List.of(); listedDevice = null; updateFilePicker(); updateControls(); }
    private String appVersion() {
        try { return getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (android.content.pm.PackageManager.NameNotFoundException e) { return "desconhecida"; }
    }
    private String usbDiagnostic() {
        boolean host = getPackageManager().hasSystemFeature(android.content.pm.PackageManager.FEATURE_USB_HOST);
        Collection<UsbDevice> attached = usb.getDeviceList().values();
        StringBuilder report = new StringBuilder("ES Medição v" + appVersion())
            .append("\nAparelho: ").append(android.os.Build.MANUFACTURER).append(" ").append(android.os.Build.MODEL)
            .append("\nAndroid: ").append(android.os.Build.VERSION.RELEASE)
            .append("\nSuporte USB Host declarado: ").append(host ? "SIM" : "NÃO")
            .append("\nDispositivos USB detectados pelo Android: ").append(attached.size());
        boolean found = false;
        for (UsbDevice device : attached) {
            boolean dm5e = device.getVendorId() == 0xc251 && device.getProductId() == 0x1705;
            found |= dm5e;
            report.append(String.format(Locale.ROOT, "\n\n%s · %04X:%04X", dm5e ? "DM5E" : "Outro dispositivo", device.getVendorId(), device.getProductId()))
                .append("\nInterfaces: ").append(device.getInterfaceCount())
                .append("\nPermissão para este app: ").append(usb.hasPermission(device) ? "SIM" : "NÃO");
        }
        if (attached.isEmpty()) report.append("\n\nO Android não enumerou nenhum dispositivo USB. Confira a conexão direta DM5E → cabo de dados → adaptador OTG → aparelho. Verifique se o modelo exige ativar OTG nas configurações. Este resultado não distingue falha de cabo, adaptador, porta ou alimentação.");
        else if (!found) report.append("\n\nHá dispositivos USB, mas nenhum corresponde ao DM5E esperado (C251:1705). Copie este diagnóstico para análise.");
        else report.append("\n\nDM5E detectado. Para usar os números modernos, abra Visor, deixe Modo imagem desmarcado e toque em Iniciar visor USB. Autorize o acesso se solicitado.");
        return report.toString();
    }
    private void showUsbDiagnostic() {
        if (!busy) refreshDevices();
        TextView reportView = text(usbDiagnostic(), 15);
        reportView.setPadding(dp(20), dp(12), dp(20), dp(12)); reportView.setTextIsSelectable(true);
        LinearLayout content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        content.addView(reportView);
        button(content, "Salvar log para enviar", this::exportDiagnosticLog);
        ScrollView scroll = new ScrollView(this); scroll.addView(content);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("USB · atualização automática").setView(scroll)
            .setPositiveButton("Fechar", null).setNegativeButton("Copiar", (window, which) -> {
                android.content.ClipboardManager clipboard = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("Diagnóstico ES Medição", usbDiagnostic()));
                Toast.makeText(this, "Diagnóstico copiado", Toast.LENGTH_SHORT).show();
            }).setNeutralButton("Autorizar DM5E", null).create();
        dialog.show();
        Button authorize = dialog.getButton(AlertDialog.BUTTON_NEUTRAL);
        authorize.setOnClickListener(view -> {
            if (busy) return;
            for (UsbDevice device : usb.getDeviceList().values()) {
                if (device.getVendorId() != 0xc251 || device.getProductId() != 0x1705) continue;
                if (!usb.hasPermission(device)) {
                    pendingName = null; pendingLive = false;
                    PendingIntent permission = PendingIntent.getBroadcast(this, device.getDeviceId(),
                        new Intent(PERMISSION).setPackage(getPackageName()), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
                    logEvent("pedido_permissao", "Solicitando autorização USB ao Android"); usb.requestPermission(device, permission);
                }
                break;
            }
        });
        android.os.Handler handler = new android.os.Handler(getMainLooper());
        Runnable update = new Runnable() {
            @Override public void run() {
                if (!dialog.isShowing()) return;
                if (!active) { dialog.dismiss(); return; }
                String report = usbDiagnostic();
                if (!report.contentEquals(reportView.getText())) reportView.setText(report);
                boolean needsPermission = false;
                for (UsbDevice device : usb.getDeviceList().values()) {
                    if (device.getVendorId() == 0xc251 && device.getProductId() == 0x1705 && !usb.hasPermission(device)) needsPermission = true;
                }
                authorize.setEnabled(!busy && needsPermission);
                handler.postDelayed(this, 1000);
            }
        };
        dialog.setOnDismissListener(window -> handler.removeCallbacks(update));
        update.run();
    }
    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent); setIntent(intent);
        if (!busy && UsbManager.ACTION_USB_DEVICE_ATTACHED.equals(intent.getAction())) refreshDevices();
    }
    private void refreshDevices() {
        devices.clear();
        for (UsbDevice d : usb.getDeviceList().values()) if (d.getVendorId() == 0xc251 && d.getProductId() == 0x1705) devices.add(d);
        devices.sort(Comparator.comparing(UsbDevice::getDeviceName));
        List<String> labels = new ArrayList<>();
        for (UsbDevice d : devices) labels.add("DM5E · " + d.getDeviceName()); options(devicePicker, labels);
        if (listedDevice != null && devices.stream().noneMatch(d -> d.getDeviceName().equals(listedDevice))) clearDirectory();
        if (devices.isEmpty()) {
            String message = "DM5E não detectado. Abra Diagnóstico USB para verificar a conexão.";
            status.setText(message);
            if (!busy && liveState != null) liveState.setText(message);
        }
        updateControls();
    }
    private UsbDevice selected() {
        int i = devicePicker.getSelectedItemPosition(); return i >= 0 && i < devices.size() ? devices.get(i) : null;
    }
    private void requestDirectory() {
        UsbDevice d = selected(); if (d == null || busy) return; pendingLive = false; pendingName = null; clearDirectory();
        if (usb.hasPermission(d)) { list(d); return; }
        pendingName = d.getDeviceName();
        PendingIntent intent = PendingIntent.getBroadcast(this, d.getDeviceId(), new Intent(PERMISSION).setPackage(getPackageName()),
            PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        logEvent("pedido_permissao", "Solicitando autorização USB ao Android"); usb.requestPermission(d, intent);
    }
    private void resumePermission() {
        if (pendingName == null || busy) return;
        UsbDevice d = usb.getDeviceList().get(pendingName);
        if (d != null && usb.hasPermission(d)) {
            pendingName = null;
            boolean startMonitor = pendingLive; pendingLive = false;
            if (startMonitor) startLive(d); else list(d);
        } else {
            status.setText("Conceda a permissão USB e toque em Conectar e listar arquivos.");
            if (pendingLive) liveState.setText("Permissão USB não concedida. Toque em Iniciar visor para tentar novamente.");
        }
    }
    private interface Operation { Object run(Dm5eProtocol protocol) throws Exception; }
    private interface Success { void accept(Object value) throws Exception; }
    private void execute(UsbDevice device, String progress, Operation operation, Success success) {
        if (busy) return;
        busy = true; status.setText(progress); updateControls(); getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        AtomicBoolean token = new AtomicBoolean(); cancellation = token; final int request = ++generation;
        worker.execute(() -> {
            try {
                Object value;
                try (Dm5eUsb connection = new Dm5eUsb(usb, device, token)) { value = operation.run(new Dm5eProtocol(connection)); }
                synchronized (commitLock) {
                    if (token.get()) return;
                    if (value instanceof Dm5eProtocol.Capture) value = store.save((Dm5eProtocol.Capture) value);
                }
                final Object completed = value;
                runOnUiThread(() -> {
                    if (!active || generation != request) return;
                    busy = false; creating = false; deleting = false; getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    try { success.accept(completed); } catch (Exception e) { logEvent("erro", e.toString()); status.setText("Falha ao exibir: " + e.getMessage()); }
                    updateControls();
                });
            } catch (Exception e) { logEvent("erro", android.util.Log.getStackTraceString(e));
                runOnUiThread(() -> {
                    if (!active || generation != request) return;
                    boolean failedDelete = deleting, failedCreate = creating;
                    busy = false; creating = false; deleting = false; clearDirectory(); updateControls(); getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    status.setText((failedDelete ? "Exclusão não confirmada: " : "Falha: ") + e.getMessage());
                });
            }
        });
    }
    @SuppressWarnings("unchecked")
    private void list(UsbDevice d) {
        execute(d, "Lendo a lista de arquivos do DM5E…", p -> {
            final int listGeneration = generation;
            return p.directoryWithRecovery(message -> {
                logEvent("listagem_usb", message);
                runOnUiThread(() -> { if (active && busy && generation == listGeneration) status.setText(message); });
            });
        }, value -> {
            files = (List<Dm5eProtocol.FileEntry>) value; listedDevice = d.getDeviceName(); updateFilePicker();
            status.setText(files.size() + " arquivos encontrados. Selecione um para abrir a planilha automaticamente.");
            filesPanel.setVisibility(View.VISIBLE); showTab(0);
        });
    }
    private void chooseDeletion() {
        UsbDevice device = selected();
        if (busy || device == null || !device.getDeviceName().equals(listedDevice) || files.isEmpty()) {
            status.setText("Use Conectar e listar arquivos na engrenagem antes de apagar."); return;
        }
        List<Dm5eProtocol.FileEntry> snapshot = List.copyOf(files);
        String[] names = snapshot.stream().map(Dm5eProtocol.FileEntry::name).toArray(String[]::new);
        boolean[] checked = new boolean[names.length];
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Selecionar arquivos para apagar")
            .setMultiChoiceItems(names, checked, (window, index, value) -> checked[index] = value)
            .setNegativeButton("Voltar", null).setNeutralButton("Selecionar todos", null)
            .setPositiveButton("Continuar", null).create();
        dialog.setOnShowListener(ignored -> {
            dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v -> {
                boolean all = true; for (boolean value : checked) all &= value;
                for (int i = 0; i < checked.length; i++) { checked[i] = !all; dialog.getListView().setItemChecked(i, !all); }
                dialog.getButton(AlertDialog.BUTTON_NEUTRAL).setText(all ? "Selecionar todos" : "Desmarcar todos");
            });
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
                List<String> targets = new ArrayList<>();
                for (int i = 0; i < names.length; i++) if (checked[i]) targets.add(names[i]);
                if (targets.isEmpty()) { Toast.makeText(this, "Selecione pelo menos um arquivo.", Toast.LENGTH_SHORT).show(); return; }
                dialog.dismiss(); confirmDeletion(device, snapshot, List.copyOf(targets));
            });
        }); dialog.show();
    }
    private void confirmDeletion(UsbDevice device, List<Dm5eProtocol.FileEntry> snapshot, List<String> targets) {
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL);
        form.setPadding(dp(20), dp(12), dp(20), dp(12));
        form.addView(text("Apagar " + targets.size() + " arquivo(s) definitivamente do DM5E:\n\n" + String.join("\n", targets)
            + "\n\nHá um travamento conhecido nessa operação pelo celular, ainda não resolvido. O arquivo pode ser apagado mesmo se houver erro na confirmação."
            + "\n\nAs cópias do celular permanecem. Mantenha o cabo conectado até terminar.", 14));
        ScrollView scroll = new ScrollView(this); scroll.addView(form);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Confirmar exclusão")
            .setView(scroll).setNegativeButton("Cancelar", null).setPositiveButton("Confirmar exclusão", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            UsbDevice current = selected();
            if (!active || busy || current == null || !device.getDeviceName().equals(current.getDeviceName()) || !usb.hasPermission(current)) {
                dialog.dismiss(); status.setText("Conexão mudou. Conecte e liste novamente."); return;
            }
            dialog.dismiss(); deleting = true; logEvent("exclusao_solicitada", String.join(", ", targets));
            final int expectedGeneration = generation + 1;
            execute(current, "Conferindo a lista antes de apagar " + targets.size() + " arquivo(s)…", p -> p.deleteFiles(targets, snapshot, message -> {
                logEvent("exclusao_andamento", message);
                runOnUiThread(() -> { if (active && generation == expectedGeneration && deleting) status.setText(message); });
            }), value -> {
                Dm5eProtocol.DeleteResult result = (Dm5eProtocol.DeleteResult) value;
                files = result.remaining(); listedDevice = current.getDeviceName(); updateFilePicker();
                status.setText("Exclusão confirmada: " + result.deleted() + ". Restam " + files.size() + " arquivos no DM5E. Cópias do celular preservadas.");
                logEvent("exclusao_confirmada", result.deleted());
            });
        })); dialog.show();
    }
    private void createGrid() {
        UsbDevice d = selected(); if (d == null || busy) return;
        if (!usb.hasPermission(d)) { status.setText("Conecte e liste arquivos para autorizar a USB primeiro."); return; }
        LinearLayout form = new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL);
        int padding = (int) (20 * getResources().getDisplayMetrics().density); form.setPadding(padding, padding, padding, padding);
        form.addView(text("Nova matriz vazia dentro do instrumento. Até 100 pontos. Mantenha o app aberto e o cabo conectado até terminar.", 14));
        EditText name = new EditText(this); name.setHint("Nome novo (A–Z, 0–9, _ ou -)"); form.addView(name);
        EditText rows = new EditText(this); rows.setHint("Linhas numéricas"); rows.setInputType(2); rows.setText("2"); form.addView(rows);
        EditText cols = new EditText(this); cols.setHint("Colunas A–Z"); cols.setInputType(2); cols.setText("2"); form.addView(cols);
        AlertDialog dialog = new AlertDialog.Builder(this).setTitle("Criar arquivo no DM5E").setView(form)
            .setNegativeButton("Voltar", null).setPositiveButton("Criar no instrumento", null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            try {
                Dm5eProtocol.NewGrid grid = new Dm5eProtocol.NewGrid(name.getText().toString().trim().toUpperCase(Locale.ROOT),
                    Integer.parseInt(rows.getText().toString()), Integer.parseInt(cols.getText().toString()));
                dialog.dismiss(); creating = true; logEvent("criar_matriz", grid.name() + " " + grid.rows() + "x" + grid.columns());
                execute(d, "Criando " + grid.name() + " no DM5E e conferindo a leitura de volta… Mantenha o app aberto.",
                    p -> p.create(grid), value -> {
                        capture = (JSONObject) value; page = 0; clearDirectory(); renderCapture();
                        status.setText("Arquivo " + grid.name() + " criado no DM5E e conferido. Cópia vazia salva no celular. Liste novamente para atualizar.");
                        logEvent("matriz_criada", grid.name());
                    });
            } catch (IllegalArgumentException e) { name.setError(e.getMessage()); }
        })); dialog.show();
    }
    private void download() {
        UsbDevice d = selected(); int i = filePicker.getSelectedItemPosition() - 1;
        if (d == null || !d.getDeviceName().equals(listedDevice) || i < 0 || i >= files.size()) {
            status.setText("Liste os arquivos deste dispositivo primeiro."); return;
        }
        Dm5eProtocol.FileEntry file = files.get(i);
        execute(d, "Baixando " + file.name() + " e verificando cada bloco…", p -> {
            final int importGeneration = generation;
            return p.importFile(file, message -> {
                logEvent("importacao_usb", message);
                runOnUiThread(() -> { if (active && busy && generation == importGeneration) status.setText(message); });
            });
        }, value -> {
            capture = (JSONObject) value; page = 0; renderCapture();
            status.setText("Arquivo " + capture.getString("file") + ": " + capture.getJSONArray("readings").length()
                + " pontos recebidos e salvos no celular.");
        });
    }
    private void renderCapture() throws JSONException {
        table.removeAllViews(); if (capture == null) return;
        pendingCells.clear();
        JSONArray localPoints=capture.getJSONArray("readings");
        for(int i=0;i<localPoints.length();i++) if("LOCAL_MEASURED".equals(localPoints.getJSONObject(i).optString("state")))
            pendingCells.add(localPoints.getJSONObject(i).getString("position"));
        matrixCells.clear();
        if (!capture.optString("captureId").equals(matrixCaptureId)) cellMeasurement.select(null);
        matrixCaptureId = capture.optString("captureId");
        renderSettings();
        JSONArray points = capture.getJSONArray("readings");
        captureTitle.setText("Arquivo " + capture.getString("file"));
        String savedAt = capture.getString("capturedAt");
        try { savedAt = java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy • HH:mm", new Locale("pt", "BR"))
            .withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.parse(savedAt)); } catch (Exception ignored) { }
        table.addView(text(points.length() + (capture.optBoolean("newFileDraft") ? " pontos · Criado no celular em " : " pontos · Importado em ") + savedAt, 12));
        JSONObject metadata = capture.getJSONObject("metadata");
        String velocity = metadata.optString("VELC", "");
        LinearLayout velocityCard = new LinearLayout(this);
        velocityCard.setOrientation(LinearLayout.VERTICAL);
        int cardPadding = (int)(16 * getResources().getDisplayMetrics().density);
        velocityCard.setPadding(cardPadding, cardPadding, cardPadding, cardPadding);
        android.graphics.drawable.GradientDrawable cardBackground = new android.graphics.drawable.GradientDrawable();
        cardBackground.setColors(new int[]{InstrumentStyle.NAVY, 0xff145160});
        cardBackground.setOrientation(android.graphics.drawable.GradientDrawable.Orientation.TL_BR);
        cardBackground.setCornerRadius(dp(18));
        velocityCard.setBackground(cardBackground);
        LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        cardParams.setMargins(0, cardPadding / 2, 0, cardPadding);
        table.addView(velocityCard, cardParams);
        TextView velocityLabel = text("VELOCIDADE DO SOM", 12); velocityLabel.setLetterSpacing(0.08f);
        velocityLabel.setTextColor(Color.WHITE);
        velocityLabel.setTypeface(null, android.graphics.Typeface.BOLD);
        velocityCard.addView(velocityLabel);
        TextView velocityValue = text(velocity.isEmpty() ? "Não informada" : velocity.replace('.', ',') + " m/s", 34);
        velocityValue.setTextColor(0xff9ff3d8);
        velocityValue.setTypeface(null, android.graphics.Typeface.BOLD);
        velocityCard.addView(velocityValue);
        TextView velocitySource = text(capture.optBoolean("newFileDraft") ? "Velocidade planejada para os pontos · arquivo ainda não enviado" : "Parâmetro registrado neste arquivo", 12);
        velocitySource.setTextColor(0xffc6dce1);
        velocityCard.addView(velocitySource);
        if (capture.has("localEdits")) {
            TextView warning = text("Matriz com medições locais. A velocidade acima pertence à importação original; confira o ajuste atual no DM5E.", 12);
            warning.setTextColor(Color.WHITE); velocityCard.addView(warning);
        }
        LinearLayout cellPanel = card(table, "MEDIR E GRAVAR NA CÉLULA");
        table.removeView(cellPanel);
        cellHint = text("Toque em uma célula da matriz para direcionar a leitura.", 14); cellPanel.addView(cellHint);
        LinearLayout cellActions = new LinearLayout(this); cellPanel.addView(cellActions);
        cellStart = button(cellActions, "Iniciar leitura", () -> {
            if (cellMeasurement.position == null || savingCell) return;
            imageMode.setChecked(false); requestLive(); updateCellPreview();
        });
        cellStop = button(cellActions, "Parar leitura", this::cancel);
        cellInstrumentSave = button(cellPanel, "Gravar no DM5E", this::confirmInstrumentCell); InstrumentStyle.button(cellInstrumentSave, true, false);
        cellSave = button(cellPanel, "Gravar só no celular", this::confirmCellSave);
        LinearLayout pendingPanel = null;
        if(!pendingCells.isEmpty() || capture.optBoolean("newFileDraft")) {
            pendingPanel=card(table,"ALTERAÇÕES NO CELULAR");
            table.removeView(pendingPanel);
            pendingPanel.addView(text(capture.optBoolean("newFileDraft") ? "ARQUIVO NOVO · salvo apenas no celular. Preencha agora ou envie a matriz vazia." : pendingCells.size()+(pendingCells.size()==1 ? " célula pendente" : " células pendentes")+" · destaque amarelo na matriz. O envio confere o arquivo antes de alterar o medidor.",14));
            sendPending=button(pendingPanel,capture.optBoolean("newFileDraft") ? "Enviar arquivos novos ao DM5E" : "Enviar alterações ao DM5E",()->{if(capture.optBoolean("newFileDraft"))chooseNewFiles();else confirmBatchSend();});
            InstrumentStyle.button(sendPending,true,false);
        } else sendPending=null;
        TextView localNote = text("A prévia não grava. Escolha o destino e confira a velocidade do som usada na medição.", 12); localNote.setTextColor(InstrumentStyle.MUTED); cellPanel.addView(localNote);
        TextView matrixTitle = text("Matriz de espessuras", 20); matrixTitle.setTypeface(null, android.graphics.Typeface.BOLD); table.addView(matrixTitle);
        TextView matrixHint = text(capture.optBoolean("newFileDraft") ? "MILÍMETROS  ·  RASCUNHO NO CELULAR" : "MILÍMETROS  ·  VALORES RECEBIDOS DO DM5E", 10); matrixHint.setTextColor(InstrumentStyle.MUTED); matrixHint.setPadding(0, dp(4), 0, dp(12)); table.addView(matrixHint);
        int rows = Integer.parseInt(metadata.getString("L2NL"));
        int cols = Integer.parseInt(metadata.getString("L3NL"));
        int rowStart = Integer.parseInt(metadata.getString("L2SI"));
        int colStart = Integer.parseInt(metadata.getString("L3SI"));
        if (rows < 1 || cols < 1 || (long) rows * cols != points.length()) throw new JSONException("Dimensões da matriz inválidas.");
        // Bounded tiles also handle large files without allocating thousands of views.
        int colPages = (cols + 11) / 12, pages = ((rows + 9) / 10) * colPages;
        page = Math.max(0, Math.min(page, pages - 1));
        int firstRow = (page / colPages) * 10, lastRow = Math.min(firstRow + 10, rows);
        int firstCol = (page % colPages) * 12, lastCol = Math.min(firstCol + 12, cols);
        HorizontalScrollView horizontal = new HorizontalScrollView(this);
        horizontal.setFillViewport(true);
        TableLayout grid = new TableLayout(this); grid.setStretchAllColumns(true); horizontal.addView(grid); table.addView(horizontal);
        TableRow header = new TableRow(this); grid.addView(header);
        gridCell(header, "Linha", true, "Número da linha");
        for (int col = firstCol; col < lastCol; col++) {
            String label = Dm5eProtocol.alpha(colStart + col);
            gridCell(header, label, true, "Coluna " + label);
        }
        for (int row = firstRow; row < lastRow; row++) {
            TableRow line = new TableRow(this); grid.addView(line);
            gridCell(line, Integer.toString(rowStart + row), true, "Linha " + (rowStart + row));
            for (int col = firstCol; col < lastCol; col++) {
                JSONObject point = points.getJSONObject(row * cols + col);
                String position = (rowStart + row) + Dm5eProtocol.alpha(colStart + col);
                if (!position.equals(point.getString("position"))) throw new JSONException("Posição incompatível com a matriz: " + position);
                String value = point.getString("valueDecimal").replace('.', ',');
                if (value.isEmpty()) value = "—";
                gridCell(line, value, false, "Ponto " + position + ": " + value + " " + point.getString("unit"));
                TextView cell = (TextView) line.getChildAt(line.getChildCount() - 1);
                cell.setTag(value); matrixCells.put(position, cell);
                cell.setFocusable(true); cell.setMinHeight(dp(48));
                cell.setOnClickListener(v -> {
                    if (savingCell || (busy && !liveRunning)) return;
                    cellMeasurement.select(position); updateCellPreview();
                    pageScroll.smoothScrollTo(0, table.getTop() + cellPanel.getTop());
                });
            }
        }
        table.addView(text("Linhas " + (rowStart + firstRow) + "–" + (rowStart + lastRow - 1)
            + " · Colunas " + Dm5eProtocol.alpha(colStart + firstCol) + "–" + Dm5eProtocol.alpha(colStart + lastCol - 1), 14));
        if (page > 0) button(table, "Página anterior", () -> changePage(-1));
        if (page + 1 < pages) button(table, "Próxima página", () -> changePage(1));
        table.addView(cellPanel);
        if (pendingPanel != null) table.addView(pendingPanel);
        updateCellPreview();
    }
    private void downloadAllFiles() {
        if(busy || savingCell)return;
        UsbDevice device=selected();
        if(device==null || !usb.hasPermission(device)) { status.setText("Autorize o USB em Configurações antes de baixar todos os arquivos."); return; }
        busy=true; downloadingAll=true; downloadAllProgress="Nenhum arquivo salvo nesta operação";
        getSharedPreferences("downloads",MODE_PRIVATE).edit().putString("lastSummary",
            "Download iniciado. Se a operação for interrompida, consulte os arquivos completos no Histórico.").apply();
        status.setText("Consultando a lista atual do DM5E…"); updateControls();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        AtomicBoolean token=new AtomicBoolean(); cancellation=token; final int request=++generation;
        worker.execute(()->{
            java.util.function.Consumer<String> progress=message->{
                logEvent("download_todos",message);
                runOnUiThread(()->{if(active && generation==request)status.setText(message);});
            };
            String summary;
            try(Dm5eUsb connection=new Dm5eUsb(usb,device,token,900000)) {
                var protocol=new Dm5eProtocol(connection);
                var listing=protocol.directoryWithRecovery(progress);
                var result=Dm5eBulkDownload.run(listing,file->protocol.importFile(file,progress),downloaded->{
                    synchronized(commitLock) {
                        if(token.get())throw new IOException("Operação cancelada.");
                        store.save(downloaded);
                    }
                },token::get,message->{
                    if(message.contains(" salvos · ")) {
                        downloadAllProgress=message;
                        getSharedPreferences("downloads",MODE_PRIVATE).edit().putString("lastSummary",
                            "Último progresso: "+message+". Conclusão ainda não confirmada. As cópias completas estão no Histórico.").apply();
                    }
                    progress.accept(message);
                });
                summary=result.summary();
            } catch(Exception e) {
                summary="Download interrompido: "+e.getMessage()+"\n"+downloadAllProgress
                    +". Arquivos completos já salvos permanecem no Histórico.";
            }
            final String outcome=summary;
            logEvent("download_todos_resumo",outcome);
            getSharedPreferences("downloads",MODE_PRIVATE).edit().putString("lastSummary",outcome).apply();
            runOnUiThread(()->{
                if(!active || generation!=request)return;
                busy=false; downloadingAll=false; status.setText(outcome);
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);updateControls();
                new AlertDialog.Builder(this).setTitle("Cópias para consulta offline").setMessage(outcome)
                    .setNegativeButton("Fechar",null).setPositiveButton("Abrir Histórico",(d,w)->history()).show();
            });
        });
    }
    private void updateCellPreview() {
        if (cellHint == null) return;
        boolean fresh = liveRunning && cellMeasurement.canSave(android.os.SystemClock.elapsedRealtime());
        for (Map.Entry<String, TextView> entry : matrixCells.entrySet()) {
            TextView cell = entry.getValue(); boolean selected = entry.getKey().equals(cellMeasurement.position);
            cell.setText(selected && fresh ? cellMeasurement.reading.millimetres().toPlainString().replace('.', ',') + "\nprévia" : String.valueOf(cell.getTag()));
            cell.setTextColor(selected && fresh ? 0xff0758b0 : InstrumentStyle.INK);
            cell.setTypeface(null, selected && fresh ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
            android.graphics.drawable.GradientDrawable bg = InstrumentStyle.surface(selected ? 0xffd7f3e9 : pendingCells.contains(entry.getKey()) ? 0xffffedb8 : Color.WHITE, InstrumentStyle.LINE, 0);
            if (selected) bg.setStroke(dp(2), InstrumentStyle.TEAL);
            cell.setBackground(bg); cell.setSelected(selected);
            cell.setContentDescription("Célula " + entry.getKey() + (selected ? ", selecionada, " : ", ") + cell.getText() + " mm" + (pendingCells.contains(entry.getKey()) ? ", pendente de envio" : ""));
        }
        String position = cellMeasurement.position;
        cellHint.setText(savingCell ? "Salvando a medição no celular…" : position == null ? "Toque em uma célula da matriz para direcionar a leitura."
            : "Célula " + position + " · " + (fresh ? "ACOPLADO · prévia " + cellMeasurement.reading.millimetres().toPlainString().replace('.', ',') + " mm"
            : cellMeasurement.reading != null && !cellMeasurement.reading.coupled() ? "SEM ACOPLAMENTO · gravação indisponível"
            : liveRunning ? "Aguardando leitura recente com acoplamento" : "Pronta para iniciar a leitura USB"));
        cellStart.setEnabled(position != null && !busy && !savingCell && !devices.isEmpty());
        cellSave.setEnabled(!savingCell && (fresh || (!busy && position!=null)));
        cellSave.setText(liveRunning ? "Gravar só no celular" : "Informar valor no celular");
        if(sendPending!=null)sendPending.setEnabled(!busy && !savingCell && !devices.isEmpty());
        cellStop.setEnabled(liveRunning && !savingCell);
        if (cellInstrumentSave != null) cellInstrumentSave.setEnabled(position != null && !savingCell && (!busy || liveRunning) && !devices.isEmpty() && capture!=null && !capture.optBoolean("newFileDraft"));
    }
    private void confirmInstrumentCell() {
        if(capture==null || cellMeasurement.position==null || savingCell || (busy && !liveRunning))return;
        if(!pendingCells.isEmpty()) { status.setText("Há alterações locais. Use Enviar alterações ao DM5E para preservar todas as células pendentes."); return; }
        final JSONObject source=capture; final String position=cellMeasurement.position;
        final UsbDevice device=selected();
        if(device==null || !usb.hasPermission(device)) { status.setText("Autorize a conexão USB em Configurações antes de gravar no DM5E."); return; }
        final Dm5eProtocol.Capture expected;
        try { expected=CaptureStore.originalSnapshot(source); Dm5eCellWriter.validate(expected); }
        catch(Exception e) { new AlertDialog.Builder(this).setTitle("Gravação indisponível neste arquivo").setMessage(e.getMessage()).setPositiveButton("Entendi",null).show(); return; }
        String initial="";
        if(liveRunning) {
            if(!cellMeasurement.canSave(android.os.SystemClock.elapsedRealtime())) { status.setText("Aguarde uma leitura recente com acoplamento ou pare a leitura para informar um valor."); return; }
            initial=cellMeasurement.reading.millimetres().toPlainString();
        } else {
            JSONArray points=source.optJSONArray("readings");
            for(int i=0;points!=null && i<points.length();i++) { JSONObject p=points.optJSONObject(i); if(p!=null && position.equals(p.optString("position")))initial=p.optString("valueDecimal"); }
        }
        cancel(); // Release the numeric session before the serial write/verify operation.
        LinearLayout form=new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(22),dp(12),dp(22),0);
        form.addView(text("Arquivo "+expected.file().name()+" · célula "+position+"\nA leitura foi pausada. O valor confirmado será escrito no medidor, substituindo o ponto existente.",14));
        form.addView(text("Espessura a gravar · mm",13));
        EditText thickness=new EditText(this); thickness.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL); thickness.setText(initial.replace('.',',')); form.addView(thickness);
        form.addView(text("Velocidade usada nesta medição · m/s",13));
        EditText velocity=new EditText(this); velocity.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL); velocity.setText(expected.metadata().getOrDefault("VELC","").replace('.',',')); form.addView(velocity);
        CheckBox checkedVelocity=new CheckBox(this); checkedVelocity.setText("Conferi a velocidade no DM5E. O valor sugerido vem do arquivo, não de uma consulta atual."); form.addView(checkedVelocity);
        form.addView(text("Grava espessura, unidade e velocidade na célula. Outros parâmetros auxiliares ficam não informados. Uma cópia anterior será salva no Histórico.",12));
        ScrollView scroller=new ScrollView(this); scroller.addView(form);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Gravar no DM5E · "+position).setView(scroller)
            .setNegativeButton("Cancelar",null).setPositiveButton("Confirmar gravação",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(!active || busy || capture!=source || !position.equals(cellMeasurement.position)) { dialog.dismiss(); return; }
            if(!checkedVelocity.isChecked()) { checkedVelocity.setError("Confira a velocidade antes de confirmar."); return; }
            final java.math.BigDecimal value, speed;
            try {
                value=new java.math.BigDecimal(thickness.getText().toString().trim().replace(',','.'));
                speed=new java.math.BigDecimal(velocity.getText().toString().trim().replace(',','.'));
                var point=expected.readings().stream().filter(p->p.position().equals(position)).findFirst().orElseThrow();
                Dm5eCellWriter.record(point,value,speed);
            } catch(Exception e) { thickness.setError("Informe espessura positiva (até 3 casas) e velocidade entre 100 e 20000, em até 7 caracteres."); return; }
            if(!usb.hasPermission(device) || !usb.getDeviceList().containsKey(device.getDeviceName())) { status.setText("Conexão USB mudou. Reconecte antes de gravar."); dialog.dismiss(); return; }
            dialog.dismiss();
            executeInstrumentCell(device,expected,position,value,speed);
        })); dialog.show();
    }
    private void executeInstrumentCell(UsbDevice device, Dm5eProtocol.Capture expected, String position,
                                       java.math.BigDecimal value, java.math.BigDecimal velocity) {
        if(busy)return;
        busy=true; updateControls(); status.setText("Gravando "+position+" no DM5E e conferindo todos os pontos…");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        AtomicBoolean token=new AtomicBoolean(); cancellation=token; final int request=++generation;
        worker.execute(()->{
            try {
                Dm5eProtocol.Capture result;
                try(Dm5eUsb connection=new Dm5eUsb(usb,device,token)) {
                    result=Dm5eCellWriter.write(connection,expected,position,value,velocity, before->{ store.save(before); logEvent("backup_antes_gravacao_dm5e",before.file().name()+" · "+position); });
                }
                JSONObject saved;
                try { saved=store.save(result); }
                catch(Exception e) { throw new IOException("Ponto confirmado no DM5E, mas falhou ao salvar a cópia no celular. Importe novamente; não repita a escrita.",e); }
                runOnUiThread(()->{
                    if(!active || generation!=request)return;
                    busy=false; capture=saved; matrixCaptureId=saved.optString("captureId");
                    try { renderCapture(); } catch(Exception e) { logEvent("erro",e.toString()); }
                    status.setText("Confirmado no DM5E: "+expected.file().name()+" · "+position+" = "+value.toPlainString().replace('.',',')+" mm. Outros pontos conferidos.");
                    logEvent("gravacao_dm5e_confirmada",expected.file().name()+" · "+position+" · "+value);
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); updateControls();
                });
            } catch(Exception e) {
                logEvent("gravacao_dm5e_erro",android.util.Log.getStackTraceString(e));
                runOnUiThread(()->{ if(!active || generation!=request)return; busy=false; clearDirectory(); status.setText(e.getMessage()); getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); updateControls(); });
            }
        });
    }
    private void confirmCellSave() {
        if(!liveRunning) { confirmManualCell(); return; }
        if (savingCell || !liveRunning || capture == null) return;
        final Dm5eLiveReading reading;
        try { reading = cellMeasurement.snapshot(android.os.SystemClock.elapsedRealtime()); }
        catch (IllegalStateException e) { updateCellPreview(); return; }
        final JSONObject source = capture;
        final String position = cellMeasurement.position, measuredAt = java.time.Instant.now().toString();
        final int session = generation;
        Runnable commit = () -> {
            if (!active || savingCell || capture != source || generation != session || !position.equals(cellMeasurement.position)
                || !liveRunning || !cellMeasurement.canSave(android.os.SystemClock.elapsedRealtime())) {
                Toast.makeText(this, "Leitura ou seleção mudou. Confira a célula e tente novamente.", Toast.LENGTH_LONG).show(); return;
            }
            savingCell = true; updateControls();
            cellWriter.execute(() -> {
                try {
                    JSONObject saved = store.saveCell(source, position, reading, measuredAt);
                    runOnUiThread(() -> {
                        savingCell = false;
                        if (capture == source) {
                            capture = saved; matrixCaptureId = saved.optString("captureId");
                            try { renderCapture(); } catch (Exception e) { logEvent("erro", e.toString()); }
                            status.setText("Célula " + position + " salva no celular · " + reading.millimetres().toPlainString().replace('.', ',') + " mm. Original preservado no Histórico.");
                        }
                        logEvent("celula_salva", position + " · " + saved.optString("captureId")); updateControls();
                    });
                } catch (Exception e) { runOnUiThread(() -> { savingCell = false; status.setText("Não foi possível gravar a célula: " + e.getMessage()); updateControls(); }); }
            });
        };
        boolean occupied = false;
        JSONArray points = source.optJSONArray("readings");
        for (int i = 0; points != null && i < points.length(); i++) {
            JSONObject p = points.optJSONObject(i);
            if (p != null && position.equals(p.optString("position"))) occupied = !p.optString("valueDecimal").isEmpty();
        }
        if (occupied) new AlertDialog.Builder(this).setTitle("Substituir a célula " + position + "?")
            .setMessage("Gravar " + reading.millimetres().toPlainString().replace('.', ',') + " mm, capturados ao tocar em Gravar?\n\nO valor foi congelado para esta confirmação. A captura original permanece no Histórico; o arquivo no DM5E não será alterado.")
            .setNegativeButton("Cancelar", null).setPositiveButton("Confirmar gravação", (d, w) -> commit.run()).show();
        else commit.run();
    }
    private void confirmManualCell() {
        if(busy || savingCell || capture==null || cellMeasurement.position==null)return;
        final JSONObject source=capture; final String position=cellMeasurement.position;
        EditText input=new EditText(this); input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
        input.setHint("Espessura em mm");
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Salvar no celular · "+position)
            .setMessage("Valor informado manualmente. Substitui esta célula apenas no celular e fica pendente de envio.")
            .setView(input).setNegativeButton("Cancelar",null).setPositiveButton("Salvar no celular",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(!active || busy || savingCell || capture!=source || !position.equals(cellMeasurement.position)) { dialog.dismiss(); return; }
            try {
                var value=new java.math.BigDecimal(input.getText().toString().trim().replace(',','.'));
                capture=store.saveManualCell(source,position,value); matrixCaptureId=capture.optString("captureId");
                renderCapture(); updateControls(); status.setText("Célula "+position+" salva no celular. Envio ao DM5E pendente."); dialog.dismiss();
            } catch(Exception e) { input.setError(e.getMessage()); }
        })); dialog.show();
    }
    private void confirmBatchSend() {
        if(busy || savingCell || capture==null)return;
        final JSONObject source=capture; final UsbDevice device=selected();
        if(device==null || !usb.hasPermission(device)) { status.setText("Autorize o USB em Configurações antes de enviar."); return; }
        try {
            var original=CaptureStore.originalSnapshot(source); Dm5eCellWriter.validate(original);
            var edits=CaptureStore.pending(source); if(edits.isEmpty())return;
            LinearLayout form=new LinearLayout(this); form.setOrientation(LinearLayout.VERTICAL); form.setPadding(dp(20),dp(12),dp(20),0);
            StringBuilder summary=new StringBuilder("Arquivo "+original.file().name()+" · "+edits.size()+" células\n");
            edits.forEach((position,value)->summary.append(position).append(" = ").append(value.toPlainString().replace('.',',')).append(" mm\n"));
            form.addView(text(summary.toString(),14)); form.addView(text("Velocidade usada em TODAS estas medições · m/s",14));
            EditText velocity=new EditText(this); velocity.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
            velocity.setText(original.metadata().getOrDefault("VELC","")); form.addView(velocity);
            CheckBox checked=new CheckBox(this); checked.setText("Confirmo que todas estas medições usaram a velocidade informada. O cabeçalho não confirma o ajuste atual."); form.addView(checked);
            form.addView(text("Cópias de segurança serão salvas. O envio é sequencial e pode ser parcial se interrompido. Valores já aplicados serão conferidos antes de continuar. Parâmetros auxiliares ficam não informados.",12));
            ScrollView scroll=new ScrollView(this); scroll.addView(form);
            AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Enviar alterações ao DM5E").setView(scroll)
                .setNegativeButton("Cancelar",null).setPositiveButton("Confirmar envio",null).create();
            dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                if(!active || busy || savingCell || capture!=source) { dialog.dismiss(); return; }
                if(!checked.isChecked()) { checked.setError("Confirme a velocidade das medições."); return; }
                try {
                    var speed=new java.math.BigDecimal(velocity.getText().toString().trim().replace(',','.'));
                    for(var point:original.readings())if(edits.containsKey(point.position()))Dm5eCellWriter.record(point,edits.get(point.position()),speed);
                    dialog.dismiss(); executeBatch(device,source,original,edits,speed);
                } catch(Exception e) { velocity.setError(e.getMessage()); }
            })); dialog.show();
        } catch(Exception e) { status.setText("Não foi possível preparar o envio: "+e.getMessage()); }
    }
    private void executeBatch(UsbDevice device, JSONObject source, Dm5eProtocol.Capture original,
                              Map<String,java.math.BigDecimal> edits, java.math.BigDecimal velocity) {
        if(!getSharedPreferences("batch",MODE_PRIVATE).edit().putString("draft",source.optString("captureId")).commit()) {
            status.setText("Não foi possível preservar a referência do rascunho. Nenhum envio iniciado."); return;
        }
        batchRunning=true;
        busy=true; status.setText("Preparando envio ao DM5E…"); updateControls();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        AtomicBoolean token=new AtomicBoolean(); cancellation=token; final int request=++generation;
        worker.execute(()->{
            try {
                Dm5eProtocol.Capture result;
                try(Dm5eUsb connection=new Dm5eUsb(usb,device,token,900000)) {
                    result=Dm5eBatchWriter.send(connection,original,edits,velocity,before->store.save(before),message->{
                        logEvent("envio_lote",message);
                        runOnUiThread(()->{if(active && generation==request)status.setText(message);});
                    });
                }
                JSONObject saved;
                try { saved=store.save(result); }
                catch(Exception e) { throw new IOException("Envio conferido no DM5E, mas a cópia local não pôde ser salva. Rascunho preservado; confira antes de reenviar.",e); }
                var prefs=getSharedPreferences("batch",MODE_PRIVATE);
                if(source.optString("captureId").equals(prefs.getString("draft","")))prefs.edit().remove("draft").commit();
                runOnUiThread(()->{
                    if(!active || generation!=request)return;
                    busy=false; batchRunning=false; if(capture==source)capture=saved;
                    try { renderCapture(); } catch(Exception e) {logEvent("erro",e.toString());}
                    status.setText("Envio concluído: "+edits.size()+" células conferidas no DM5E. Nenhuma alteração pendente nesta cópia.");
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); updateControls();
                });
            } catch(Exception e) {
                logEvent("envio_lote_erro",android.util.Log.getStackTraceString(e));
                runOnUiThread(()->{if(!active || generation!=request)return; busy=false; batchRunning=false;
                    status.setText(e.getMessage()+" O rascunho continua no Histórico.");
                    getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); updateControls();});
            }
        });
    }
    private void gridCell(TableRow row, String value, boolean heading, String description) {
        TextView cell = text(value, 17);
        float density = getResources().getDisplayMetrics().density;
        cell.setGravity(android.view.Gravity.CENTER);
        cell.setPadding((int)(10 * density), (int)(12 * density), (int)(10 * density), (int)(12 * density));
        cell.setMinWidth((int)(64 * density));
        cell.setContentDescription(description);
        if (heading) cell.setTypeface(null, android.graphics.Typeface.BOLD);
        android.graphics.drawable.GradientDrawable background = new android.graphics.drawable.GradientDrawable();
        background.setColor(heading ? 0xffdeebef : Color.WHITE);
        background.setStroke(Math.max(1, (int)density), InstrumentStyle.LINE);
        cell.setBackground(background); row.addView(cell);
    }
    private void changePage(int delta) { page += delta; try { renderCapture(); } catch (Exception e) { logEvent("erro", e.toString()); status.setText(e.getMessage()); } }
    private void history() {
        new SavedCapturesDialog(this,store,selectedCapture->{
            try { capture=selectedCapture;page=0;renderCapture();updateControls();status.setText("Captura salva aberta. N\u00e3o requer conex\u00e3o USB."); }
            catch(Exception e){logEvent("erro",e.toString());status.setText("Falha ao abrir: "+e.getMessage());}
        },()->new AlertDialog.Builder(this).setTitle("\u00daltimo download")
            .setMessage(getSharedPreferences("downloads",MODE_PRIVATE).getString("lastSummary","Nenhum download registrado."))
            .setPositiveButton("Entendi",null).show()).show();
    }
    private EditText draftInput(LinearLayout form,String label,String initial,boolean decimal) {
        form.addView(text(label,14));EditText input=new EditText(this);input.setText(initial);
        input.setInputType(decimal ? android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL : android.text.InputType.TYPE_CLASS_TEXT);
        form.addView(input);return input;
    }
    private void createOfflineGrid() {
        if(busy || savingCell)return;
        LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(20),dp(12),dp(20),0);
        EditText name=draftInput(form,"Nome do arquivo", "",false);
        EditText rows=draftInput(form,"Linhas numéricas", "4",true),cols=draftInput(form,"Colunas A, B, C…", "4",true);
        EditText velocity=draftInput(form,"Velocidade do som · opcional · m/s", "",true);
        rows.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        cols.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        velocity.setHint("Pode deixar em branco ao criar");
        form.addView(text("Até 100 células e 26 colunas. Cria somente no celular. Depois você poderá preencher e enviar vários arquivos por USB.",13));
        ScrollView scroll=new ScrollView(this);scroll.addView(form);
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Nova matriz no celular").setView(scroll).setNegativeButton("Cancelar",null).setPositiveButton("Criar no celular",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(!active || busy || savingCell)return;
            name.setError(null);rows.setError(null);cols.setError(null);velocity.setError(null);
            String fileName=name.getText().toString().trim().toUpperCase(Locale.ROOT);
            if(!fileName.matches("[A-Z0-9-]{1,15}")) {
                name.setError("Use de 1 a 15 caracteres: letras A–Z, números ou hífen.");name.requestFocus();return;
            }
            int rowCount, columnCount;
            try { rowCount=Integer.parseInt(rows.getText().toString().trim());if(rowCount<1 || rowCount>100)throw new NumberFormatException(); }
            catch(NumberFormatException e){rows.setError("Informe um número inteiro de 1 a 100.");rows.requestFocus();return;}
            try { columnCount=Integer.parseInt(cols.getText().toString().trim());if(columnCount<1 || columnCount>26)throw new NumberFormatException(); }
            catch(NumberFormatException e){cols.setError("Informe a quantidade de colunas: de 1 a 26.");cols.requestFocus();return;}
            if(rowCount*columnCount>100){cols.setError("Linhas × colunas deve ser no máximo 100.");cols.requestFocus();return;}
            java.math.BigDecimal soundVelocity;
            try {
                soundVelocity=velocity.getText().toString().isBlank()?null:new java.math.BigDecimal(velocity.getText().toString().trim().replace(',','.'));
                new Dm5eNewFileSender.Draft(fileName,rowCount,columnCount,soundVelocity,java.util.Map.of()).records();
            } catch(Exception e){velocity.setError("Informe uma velocidade válida entre 100 e 20000 m/s (até 7 caracteres).");velocity.requestFocus();return;}
            try {
                capture=store.createDraft(fileName,rowCount,columnCount,soundVelocity);
                page=0;renderCapture();updateControls();filesPanel.setVisibility(View.GONE);status.setText("Matriz criada no celular. Toque em uma célula para preencher; envio ao DM5E pendente.");dialog.dismiss();
            } catch(Exception e){logEvent("erro",e.toString());new AlertDialog.Builder(this).setTitle("Não foi possível criar a matriz").setMessage(e.getMessage()==null ? "Falha ao salvar no celular. Tente novamente." : e.getMessage()).setPositiveButton("Entendi",null).show();}
        }));dialog.show();
    }
    private void chooseNewFiles() {
        if(busy || savingCell)return;
        UsbDevice device=selected();
        if(device==null || !usb.hasPermission(device)){status.setText("Autorize a conexão USB em Configurações antes de enviar.");return;}
        try {
            var drafts=store.newDrafts();if(drafts.isEmpty()){status.setText("Nenhum arquivo novo pendente. Use Criar matriz no celular.");return;}
            String[] labels=new String[drafts.size()];boolean[] checked=new boolean[drafts.size()];Arrays.fill(checked,true);
            for(int i=0;i<drafts.size();i++){var draft=CaptureStore.newFileData(drafts.get(i));labels[i]=draft.name()+" · "+draft.rows()+" × "+draft.columns()+" · "+draft.values().size()+" pontos preenchidos · "+(draft.velocity()==null?"velocidade não informada":draft.velocity()+" m/s");}
            new AlertDialog.Builder(this).setTitle("Selecionar arquivos novos").setMultiChoiceItems(labels,checked,(d,i,on)->checked[i]=on)
                .setNegativeButton("Cancelar",null).setPositiveButton("Conferir envio",(d,w)->{
                    List<JSONObject> chosen=new ArrayList<>();for(int i=0;i<checked.length;i++)if(checked[i])chosen.add(drafts.get(i));
                    if(chosen.isEmpty()){status.setText("Selecione pelo menos um arquivo.");return;}
                    for(JSONObject item:chosen) {
                        try {
                            var data=CaptureStore.newFileData(item);
                            if(data.velocity()==null && !data.values().isEmpty()) {
                                EditText speed=new EditText(this);speed.setInputType(android.text.InputType.TYPE_CLASS_NUMBER|android.text.InputType.TYPE_NUMBER_FLAG_DECIMAL);
                                AlertDialog speedDialog=new AlertDialog.Builder(this).setTitle("Velocidade dos pontos: "+data.name()).setMessage("Esta matriz tem medi\u00e7\u00f5es. Informe a velocidade usada nos pontos antes de enviar.").setView(speed).setNegativeButton("Cancelar",null).setPositiveButton("Salvar velocidade",null).create();
                                speedDialog.setOnShowListener(a->speedDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(b->{
                                    try {store.setDraftVelocity(item,new java.math.BigDecimal(speed.getText().toString().trim().replace(',','.')));speedDialog.dismiss();chooseNewFiles();}
                                    catch(Exception e){speed.setError("Informe uma velocidade v\u00e1lida de 100 a 20000 m/s.");}
                                }));speedDialog.show();return;
                            }
                        } catch(Exception e){status.setText("Falha: "+e.getMessage());return;}
                    }
                    LinearLayout form=new LinearLayout(this);form.setOrientation(LinearLayout.VERTICAL);form.setPadding(dp(20),dp(12),dp(20),0);
                    StringBuilder names=new StringBuilder();for(int i=0;i<checked.length;i++)if(checked[i])names.append(labels[i]).append("\n\n");
                    form.addView(text(names.toString(),14));
                    CheckBox confirm=new CheckBox(this);confirm.setText("Conferi os arquivos e, quando preenchidos, as velocidades dos pontos.");form.addView(confirm);
                    form.addView(text("Arquivos existentes não serão sobrescritos. Se o nome já existir, o app só aceitará como concluído se todos os pontos coincidirem. Em caso de falha, os demais ficam pendentes. O ajuste do instrumento não é alterado; o cabeçalho recebido vem do DM5E.",13));
                    ScrollView scroll=new ScrollView(this);scroll.addView(form);
                    AlertDialog dialog=new AlertDialog.Builder(this).setTitle("Enviar "+chosen.size()+" arquivos ao DM5E").setView(scroll).setNegativeButton("Cancelar",null).setPositiveButton("Enviar arquivos",null).create();
                    dialog.setOnShowListener(x->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                        if(!active || busy || savingCell)return;
                        if(!confirm.isChecked()){confirm.setError("Confirme as velocidades.");return;}
                        dialog.dismiss();sendNewDrafts(device,List.copyOf(chosen));
                    }));dialog.show();
                }).show();
        } catch(Exception e){status.setText("Falha ao preparar arquivos: "+e.getMessage());}
    }
    private void sendNewDrafts(UsbDevice device,List<JSONObject> drafts) {
        busy=true;creating=true;status.setText("Preparando arquivos novos…");updateControls();
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        AtomicBoolean token=new AtomicBoolean();cancellation=token;final int request=++generation;
        worker.execute(()->{
            Map<String,JSONObject> sent=new LinkedHashMap<>();List<String> names=new ArrayList<>();String failure="";
            try(Dm5eUsb link=new Dm5eUsb(usb,device,token,900000)) {
                for(var draft:drafts) {
                    if(token.get())throw new IOException("Envio cancelado.");
                    var data=CaptureStore.newFileData(draft);
                    java.util.function.Consumer<String> progress=message->{logEvent("arquivos_novos",message);runOnUiThread(()->{if(active && generation==request)status.setText(message);});};
                    progress.accept("Arquivo "+(names.size()+1)+" de "+drafts.size()+": "+data.name());
                    var confirmed=Dm5eNewFileSender.send(link,data,progress);
                    var saved=store.markDraftSent(draft,confirmed);sent.put(draft.optString("draftId"),saved);names.add(data.name());
                    logEvent("arquivo_novo_confirmado",data.name());
                }
            } catch(Exception e){failure=e.getMessage();logEvent("arquivos_novos_erro",android.util.Log.getStackTraceString(e));}
            String outcome=names.size()+" de "+drafts.size()+" arquivos conferidos no DM5E."
                +(names.isEmpty()?"":"\nConfirmados: "+String.join(", ",names))
                +(failure.isEmpty()?"":"\n"+failure+"\nRascunhos não concluídos continuam no celular.");
            getSharedPreferences("newFiles",MODE_PRIVATE).edit().putString("lastSummary",outcome).apply();
            runOnUiThread(()->{
                if(!active || generation!=request)return;
                busy=false;creating=false;clearDirectory();
                if(capture!=null && sent.containsKey(capture.optString("draftId")))capture=sent.get(capture.optString("draftId"));
                try {renderCapture();}catch(Exception e){logEvent("erro",e.toString());}
                status.setText(outcome);getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);updateControls();
                new AlertDialog.Builder(this).setTitle("Envio de arquivos novos").setMessage(outcome).setPositiveButton("Entendi",null).show();
            });
        });
    }
    private void exportDiagnosticLog() {
        if (diagnosticLog == null) { Toast.makeText(this, "Log indisponível", Toast.LENGTH_LONG).show(); return; }
        cancel();
        logEvent("exportacao_log", usbDiagnostic());
        try {
            logExportBytes = diagnosticLog.archive(usbDiagnostic());
            String stamp = java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss").format(java.time.LocalDateTime.now());
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/zip").putExtra(Intent.EXTRA_TITLE, "DM5ESE-log-v" + appVersion() + "-" + stamp + ".zip");
            startActivityForResult(intent, 21);
        } catch (IOException e) { logExportBytes = null; Toast.makeText(this, "Falha ao preparar log: " + e.getMessage(), Toast.LENGTH_LONG).show(); }
    }
    private void export(boolean json) {
        if (capture == null) return;
        try {
            exportBytes = (json ? capture.toString(2) : CaptureStore.csv(capture)).getBytes(StandardCharsets.UTF_8);
            Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(json ? "application/json" : "text/csv").putExtra(Intent.EXTRA_TITLE,
                    "DM5E-" + capture.getString("file").replaceAll("[^a-zA-Z0-9_-]", "_") + (json ? ".json" : ".csv"));
            startActivityForResult(intent, 20);
        } catch (Exception e) { logEvent("erro", e.toString()); status.setText("Falha ao exportar: " + e.getMessage()); }
    }
    @Override protected void onActivityResult(int request, int result, Intent intent) {
        super.onActivityResult(request, result, intent);
        if (request == 21) {
            if (result != RESULT_OK || intent == null || intent.getData() == null || logExportBytes == null) { logExportBytes = null; return; }
            android.net.Uri uri = intent.getData();
            try (OutputStream out = getContentResolver().openOutputStream(uri, "wt")) {
                if (out == null) throw new IOException("Arquivo indisponível."); out.write(logExportBytes);
            } catch (IOException e) { Toast.makeText(this, "Falha ao salvar log: " + e.getMessage(), Toast.LENGTH_LONG).show(); logExportBytes = null; return; }
            logExportBytes = null;
            new AlertDialog.Builder(this).setTitle("Log salvo").setMessage("Você pode anexar o ZIP nesta conversa. O arquivo inclui dados USB e sessões recentes de medição.")
                .setNegativeButton("Fechar", null).setPositiveButton("Compartilhar", (dialog, which) -> {
                    Intent send = new Intent(Intent.ACTION_SEND).setType("application/zip").putExtra(Intent.EXTRA_STREAM, uri)
                        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                    send.setClipData(ClipData.newRawUri("Log ES Medição", uri));
                    startActivity(Intent.createChooser(send, "Compartilhar log ES Medição"));
                }).show();
            return;
        }
        if (request != 20 || result != RESULT_OK || intent == null || intent.getData() == null || exportBytes == null) return;
        try (OutputStream out = getContentResolver().openOutputStream(intent.getData(), "wt")) {
            if (out == null) throw new IOException("Arquivo indisponível."); out.write(exportBytes); status.setText("Arquivo exportado.");
        } catch (Exception e) { logEvent("erro", e.toString()); status.setText("Falha ao exportar: " + e.getMessage()); }
        finally { exportBytes = null; }
    }
}
