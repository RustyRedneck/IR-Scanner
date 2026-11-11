package ch.bertholet.irscanner;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.hardware.ConsumerIrManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;

/**
 * IR-Scanner fuer Heizstrahler ohne Fernbedienung.
 * Sendet nacheinander alle NEC-Codes (38 kHz) ueber den IR-Sender des Handys.
 */
public class MainActivity extends Activity {

    private static final int FREQ = 38000;
    private static final int TOTAL = 256 * 256;
    private static final int HISTORY_SIZE = 20;

    // Haeufige Geraetecodes zuerst (Phase 1), danach alle uebrigen (Phase 2)
    private static final int[] PRIORITY = {
            0x00, 0xFF, 0x01, 0x80, 0x02, 0x40, 0x04, 0x20,
            0x08, 0x10, 0x03, 0x7F, 0xFE, 0x0F, 0xF0, 0x55, 0xAA
    };

    private ConsumerIrManager ir;
    private SharedPreferences prefs;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private final int[] order = new int[256];
    private int phase1Count = 0;

    private volatile boolean scanning = false;
    private volatile int scanIndex = 0;
    private volatile int delayMs = 150;
    private volatile int repeats = 1;
    private final LinkedList<int[]> history = new LinkedList<>();

    private TextView statusText, codeText, progressText, delayText;
    private Button startBtn;
    private LinearLayout historyBox, savedBox;
    private EditText addrInput, cmdInput;

    // ---------------------------------------------------------------- Lebenszyklus

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("irscanner", MODE_PRIVATE);
        ir = (ConsumerIrManager) getSystemService(Context.CONSUMER_IR_SERVICE);

        buildOrder();
        scanIndex = prefs.getInt("scanIndex", 0);
        delayMs = prefs.getInt("delay", 150);
        repeats = prefs.getInt("repeats", 1);

        setContentView(buildUi());
        refreshStatus();
        refreshProgress();
        refreshHistory();
        refreshSaved();
        if (scanIndex > 0) startBtn.setText("Weiter");
    }

    @Override
    protected void onDestroy() {
        scanning = false;
        saveIndex();
        super.onDestroy();
    }

    private void buildOrder() {
        boolean[] used = new boolean[256];
        int n = 0;
        for (int a : PRIORITY) {
            if (!used[a]) { order[n++] = a; used[a] = true; }
        }
        phase1Count = n;
        for (int a = 0; a < 256; a++) {
            if (!used[a]) order[n++] = a;
        }
    }

    // ---------------------------------------------------------------- IR senden

    /** NEC-Protokoll: 9 ms Puls, 4,5 ms Pause, dann Adresse, ~Adresse, Befehl, ~Befehl (LSB zuerst). */
    private static int[] nec(int addr, int cmd) {
        int[] p = new int[2 + 64 + 1];
        int i = 0;
        p[i++] = 9000;
        p[i++] = 4500;
        int data = (addr & 0xFF)
                | ((~addr & 0xFF) << 8)
                | ((cmd & 0xFF) << 16)
                | ((~cmd & 0xFF) << 24);
        for (int b = 0; b < 32; b++) {
            p[i++] = 562;
            p[i++] = ((data >>> b) & 1) == 1 ? 1687 : 562;
        }
        p[i] = 562;
        return p;
    }

    private boolean hasIr() {
        return ir != null && ir.hasIrEmitter();
    }

    private boolean send(int addr, int cmd) {
        if (!hasIr()) return false;
        int[] pattern = nec(addr, cmd);
        try {
            for (int r = 0; r < repeats; r++) {
                ir.transmit(FREQ, pattern);
                if (r < repeats - 1) Thread.sleep(40);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ---------------------------------------------------------------- Suchlauf

    private void startScan() {
        if (scanning) return;
        if (!hasIr()) { toast("Kein IR-Sender gefunden."); return; }
        if (scanIndex >= TOTAL) scanIndex = 0;

        scanning = true;
        startBtn.setText("Läuft …");
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        new Thread(() -> {
            while (scanning && scanIndex < TOTAL) {
                int addr = order[scanIndex / 256];
                int cmd = scanIndex % 256;
                send(addr, cmd);
                synchronized (history) {
                    history.addFirst(new int[]{addr, cmd});
                    while (history.size() > HISTORY_SIZE) history.removeLast();
                }
                scanIndex++;
                ui.post(this::refreshProgress);
                try {
                    Thread.sleep(delayMs);
                } catch (InterruptedException e) {
                    break;
                }
            }
            scanning = false;
            ui.post(() -> {
                getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                saveIndex();
                refreshProgress();
                refreshHistory();
                startBtn.setText(scanIndex >= TOTAL ? "Start" : "Weiter");
                if (scanIndex >= TOTAL) toast("Suchlauf fertig – alle Codes gesendet.");
            });
        }).start();
    }

    private void stopScan() {
        scanning = false;
        saveIndex();
        refreshHistory();
    }

    private void reacted() {
        stopScan();
        toast("Gestoppt. Teste jetzt die letzten Codes einzeln – meist ist es einer der Einträge 3 bis 10.");
    }

    private void resetScan() {
        new AlertDialog.Builder(this)
                .setTitle("Von vorn beginnen?")
                .setMessage("Der Suchlauf startet wieder beim ersten Code.")
                .setPositiveButton("Ja", (d, w) -> {
                    scanning = false;
                    scanIndex = 0;
                    saveIndex();
                    synchronized (history) { history.clear(); }
                    startBtn.setText("Start");
                    refreshProgress();
                    refreshHistory();
                })
                .setNegativeButton("Abbrechen", null)
                .show();
    }

    private void saveIndex() {
        prefs.edit().putInt("scanIndex", scanIndex).apply();
    }

    // ---------------------------------------------------------------- Anzeige aktualisieren

    private void refreshStatus() {
        if (!hasIr()) {
            statusText.setText("⚠ Kein IR-Sender gefunden. Dieses Handy kann leider nicht senden.");
            statusText.setTextColor(Color.rgb(255, 120, 120));
        } else {
            statusText.setText("IR-Sender bereit. Oberkante des Handys auf den Strahler richten, Abstand 1–2 m.");
        }
    }

    private void refreshProgress() {
        int idx = scanIndex;
        if (idx > 0) {
            int last = idx - 1;
            codeText.setText(fmt(order[last / 256], last % 256));
        } else {
            codeText.setText("–");
        }
        int addrNr = Math.min(idx / 256 + 1, 256);
        String phase = (idx < phase1Count * 256) ? "Phase 1 (häufige Gerätecodes)" : "Phase 2 (alle übrigen)";
        long remainingSec = (long) (TOTAL - idx) * (delayMs + 70L * repeats) / 1000L;
        long phase1Sec = Math.max(0, (long) (phase1Count * 256 - idx)) * (delayMs + 70L * repeats) / 1000L;
        String eta = (idx < phase1Count * 256)
                ? String.format(Locale.ROOT, "Phase 1 noch ca. %d Min.", phase1Sec / 60 + 1)
                : String.format(Locale.ROOT, "Noch ca. %d Min.", remainingSec / 60 + 1);
        progressText.setText(String.format(Locale.ROOT,
                "%s\nCode %d von %d · Gerätecode %d von 256\n%s",
                phase, idx, TOTAL, addrNr, eta));
    }

    private void updateDelayText() {
        delayText.setText("Pause zwischen Codes: " + delayMs + " ms");
    }

    private void refreshHistory() {
        historyBox.removeAllViews();
        List<int[]> copy;
        synchronized (history) { copy = new ArrayList<>(history); }
        if (copy.isEmpty()) {
            historyBox.addView(text("Noch nichts gesendet.", 14));
            return;
        }
        int nr = 1;
        for (int[] c : copy) {
            LinearLayout row = hrow();
            TextView t = text(nr + ". " + fmt(c[0], c[1]), 15);
            t.setTypeface(Typeface.MONOSPACE);
            row.addView(t, weight());
            row.addView(button("Senden", v -> {
                if (send(c[0], c[1])) toast("Gesendet: " + fmt(c[0], c[1]));
            }));
            row.addView(button("Speichern", v -> askSave(c[0], c[1])));
            historyBox.addView(row);
            nr++;
        }
    }

    // ---------------------------------------------------------------- Gespeicherte Tasten

    private List<String[]> loadSaved() {
        List<String[]> list = new ArrayList<>();
        String s = prefs.getString("saved", "");
        for (String line : s.split("\n")) {
            String[] f = line.split("\\|");
            if (f.length == 3) list.add(f);
        }
        return list;
    }

    private void storeSaved(List<String[]> list) {
        StringBuilder sb = new StringBuilder();
        for (String[] f : list) {
            sb.append(f[0]).append('|').append(f[1]).append('|').append(f[2]).append('\n');
        }
        prefs.edit().putString("saved", sb.toString()).apply();
    }

    private void saveCode(String name, int addr, int cmd) {
        name = name.replace("|", " ").replace("\n", " ").trim();
        if (name.isEmpty()) return;
        List<String[]> list = loadSaved();
        for (int i = list.size() - 1; i >= 0; i--) {
            if (list.get(i)[0].equals(name)) list.remove(i);
        }
        list.add(new String[]{name, String.valueOf(addr), String.valueOf(cmd)});
        storeSaved(list);
        refreshSaved();
        toast("Gespeichert: " + name);
    }

    private void askSave(int addr, int cmd) {
        String[] opts = {"Ein/Aus", "Stufe 1 (750 W)", "Stufe 2 (1500 W)", "Stufe 3 (2000 W)", "Eigener Name …"};
        new AlertDialog.Builder(this)
                .setTitle("Speichern als")
                .setItems(opts, (d, which) -> {
                    if (which == opts.length - 1) {
                        EditText e = new EditText(this);
                        e.setSingleLine(true);
                        new AlertDialog.Builder(this)
                                .setTitle("Name der Taste")
                                .setView(e)
                                .setPositiveButton("OK", (d2, w2) -> saveCode(e.getText().toString(), addr, cmd))
                                .setNegativeButton("Abbrechen", null)
                                .show();
                    } else {
                        saveCode(opts[which], addr, cmd);
                    }
                })
                .show();
    }

    private void refreshSaved() {
        savedBox.removeAllViews();
        List<String[]> list = loadSaved();
        if (list.isEmpty()) {
            savedBox.addView(text("Noch keine Tasten gespeichert. Finde zuerst einen Code mit dem Suchlauf.", 14));
            return;
        }
        for (String[] f : list) {
            final String name = f[0];
            final int a = Integer.parseInt(f[1]);
            final int c = Integer.parseInt(f[2]);
            Button b = button(name + "\n" + fmt(a, c), v -> {
                if (send(a, c)) toast("Gesendet: " + name);
            });
            b.setTextSize(18);
            b.setMinHeight(dp(72));
            b.setOnLongClickListener(v -> {
                new AlertDialog.Builder(this)
                        .setTitle("Taste löschen?")
                        .setMessage(name)
                        .setPositiveButton("Löschen", (d, w) -> {
                            List<String[]> l = loadSaved();
                            for (int i = l.size() - 1; i >= 0; i--) {
                                if (l.get(i)[0].equals(name)) l.remove(i);
                            }
                            storeSaved(l);
                            refreshSaved();
                        })
                        .setNegativeButton("Abbrechen", null)
                        .show();
                return true;
            });
            savedBox.addView(b, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
    }

    // ---------------------------------------------------------------- Handtest

    private void manualSend(boolean save) {
        try {
            int a = Integer.parseInt(addrInput.getText().toString().trim(), 16);
            int c = Integer.parseInt(cmdInput.getText().toString().trim(), 16);
            if (a < 0 || a > 255 || c < 0 || c > 255) throw new NumberFormatException();
            if (save) {
                askSave(a, c);
            } else if (send(a, c)) {
                toast("Gesendet: " + fmt(a, c));
            }
        } catch (NumberFormatException e) {
            toast("Bitte zwei Hex-Werte zwischen 00 und FF eingeben.");
        }
    }

    // ---------------------------------------------------------------- Oberfläche

    private View buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = vbox();
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad * 2);
        scroll.addView(root);

        TextView title = text("IR-Scanner Heizstrahler", 24);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);
        statusText = text("", 14);
        root.addView(statusText);

        // Meine Fernbedienung (oben, für den Alltag)
        root.addView(section("Meine Fernbedienung"));
        root.addView(text("Lang drücken = Taste löschen", 12));
        savedBox = vbox();
        root.addView(savedBox);

        // Suchlauf
        root.addView(section("Suchlauf"));
        codeText = text("–", 28);
        codeText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
        codeText.setGravity(Gravity.CENTER);
        root.addView(codeText);
        progressText = text("", 14);
        progressText.setGravity(Gravity.CENTER);
        root.addView(progressText);

        delayText = text("", 14);
        root.addView(delayText);
        SeekBar seek = new SeekBar(this);
        seek.setMax(450);
        seek.setProgress(delayMs - 50);
        seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                delayMs = p + 50;
                updateDelayText();
                prefs.edit().putInt("delay", delayMs).apply();
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { refreshProgress(); }
        });
        root.addView(seek);
        updateDelayText();

        CheckBox rep = new CheckBox(this);
        rep.setText("Jeden Code doppelt senden (langsamer, aber zuverlässiger)");
        rep.setChecked(repeats == 2);
        rep.setOnCheckedChangeListener((v, checked) -> {
            repeats = checked ? 2 : 1;
            prefs.edit().putInt("repeats", repeats).apply();
            refreshProgress();
        });
        root.addView(rep);

        LinearLayout row = hrow();
        startBtn = button("Start", v -> startScan());
        row.addView(startBtn, weight());
        row.addView(button("Stopp", v -> stopScan()), weight());
        row.addView(button("Von vorn", v -> resetScan()), weight());
        root.addView(row);

        Button hit = button("REAGIERT!", v -> reacted());
        hit.setTextSize(28);
        hit.setTypeface(Typeface.DEFAULT_BOLD);
        hit.setTextColor(Color.WHITE);
        hit.setBackgroundColor(Color.rgb(200, 40, 40));
        hit.setMinHeight(dp(110));
        LinearLayout.LayoutParams hitLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        hitLp.topMargin = dp(12);
        hitLp.bottomMargin = dp(8);
        root.addView(hit, hitLp);
        root.addView(text("Drücke REAGIERT, sobald der Strahler piepst oder angeht. "
                + "Dann unten die letzten Codes einzeln mit «Senden» testen.", 13));

        // Verlauf
        root.addView(section("Zuletzt gesendet (neueste oben)"));
        historyBox = vbox();
        root.addView(historyBox);

        // Handtest
        root.addView(section("Code von Hand testen (Hex)"));
        LinearLayout mrow = hrow();
        addrInput = hexInput("Gerätecode, z. B. 00");
        cmdInput = hexInput("Befehl, z. B. 12");
        mrow.addView(addrInput, weight());
        mrow.addView(cmdInput, weight());
        root.addView(mrow);
        LinearLayout mrow2 = hrow();
        mrow2.addView(button("Senden", v -> manualSend(false)), weight());
        mrow2.addView(button("Speichern", v -> manualSend(true)), weight());
        root.addView(mrow2);

        return scroll;
    }

    // ---------------------------------------------------------------- Hilfsfunktionen

    private static String fmt(int addr, int cmd) {
        return String.format(Locale.ROOT, "Gerät %02X · Befehl %02X", addr, cmd);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private TextView text(String s, int sp) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setPadding(0, dp(4), 0, dp(4));
        return t;
    }

    private TextView section(String s) {
        TextView t = text(s, 18);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(20), 0, dp(6));
        return t;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private EditText hexInput(String hint) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        return e;
    }

    private LinearLayout vbox() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    private LinearLayout hrow() {
        LinearLayout l = new LinearLayout(this);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    private LinearLayout.LayoutParams weight() {
        return new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }
}
