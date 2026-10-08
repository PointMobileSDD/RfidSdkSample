package ex.dev.sdk.tool.rfidsample.rf88;

import android.animation.ValueAnimator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import ex.dev.sdk.rfid.RfidManager;
import ex.dev.sdk.rfid.domain.constants.ResultCode;
import ex.dev.sdk.rfid.domain.constants.RfidReader;
import ex.dev.sdk.rfid.domain.operation.OperationState;
import ex.dev.sdk.rfid.domain.operation.OperationType;
import ex.dev.sdk.rfid.frameworks.listener.OnHardwareKeyListener;
import ex.dev.sdk.rfid.frameworks.listener.OnInventoryResultListener;
import ex.dev.sdk.rfid.frameworks.listener.OnOperationStateChangedListener;
import ex.dev.sdk.tool.rfidsample.R;

/**
 * Scans for tags with the RF88 and lists what it sees.
 *
 * <p><b>Reader:</b> RF88. Scanning starts from the RF88's own trigger as well as from the button
 * on screen, and the button follows whichever did it. Connect on one of the three connect screens
 * first — {@code inventory()} on a reader that is not connected is refused with {@code E0}.
 *
 * <p><b>SDK calls demonstrated</b>
 * <ul>
 *   <li>{@link RfidManager#inventory()} / {@link RfidManager#stop()}</li>
 *   <li>{@link RfidManager#setOnInventoryResultListener} — one call per tag seen, on an SDK thread</li>
 *   <li>{@link RfidManager#setOnOperationStateChangedListener} — what is running, so the button
 *       matches the reader even when something else started or stopped the scan</li>
 *   <li>{@link RfidManager#setOnHardwareKeyListener} — the RF88's two triggers. Which one does what
 *       is the {@code KEY_MAPPING} setting; the MF52 has no keys at all</li>
 * </ul>
 *
 * <p><b>To copy this into your own app</b>, take:
 * <ol>
 *   <li>the {@code rfidManager}, {@code executor}, {@code tags} and {@code uiRefresh} fields,</li>
 *   <li>{@code inBackground()} and {@code showProgress()} with the {@code progress} bar — or call
 *       {@code executor.execute} directly if you show progress your own way,</li>
 *   <li>{@code onInventoryDiscovered} and {@code onOperationStateChanged},</li>
 *   <li>{@code startStop()} and {@code stopScan()} with {@code explain()}, and the refresh throttle in
 *       {@code scheduleRefresh()},</li>
 *   <li>if you use the RF88's trigger, {@code onInventoryKeyPressed} and {@code onInventoryKeyReleased},</li>
 *   <li>the listener clean-up and {@code stop()} in {@link #onDestroy()}.</li>
 * </ol>
 *
 * <p><b>Threading.</b> Tag reports arrive on an SDK thread and can arrive fast, so they are folded
 * into {@link #tags} there and the list is redrawn on a timer rather than once per tag. Posting a
 * redraw per report is what makes a scanning screen stutter.
 */
public class Rf88InventoryActivity extends AppCompatActivity
        implements OnInventoryResultListener, OnOperationStateChangedListener,
        OnHardwareKeyListener {

    /** How often the list is redrawn while reports are coming in. */
    private static final long REFRESH_MS = 200L;

    private final RfidManager rfidManager = RfidManager.getInstance();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    /** SDK calls handed to {@link #executor} that have not returned yet. The progress bar shows while any do. */
    private final AtomicInteger callsRunning = new AtomicInteger();
    private LinearProgressIndicator progress;
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private final SpannableStringBuilder logBuffer = new SpannableStringBuilder();
    private final Handler uiRefresh = new Handler(Looper.getMainLooper());

    /** EPC to the tag's running totals. Guarded by itself; reports arrive on an SDK thread. */
    private final Map<String, Tag> tags = new LinkedHashMap<>();

    private final TagAdapter adapter = new TagAdapter();
    private boolean refreshQueued;
    private boolean scanning;
    private long totalReads;
    private long startedAt;

    private View header;
    private Button startStop;
    private Button clear;
    private TextView stats;
    private TextView empty;
    private TextView logText;
    private ScrollView logScroll;
    /** Whether the log panel is open, and the animation that opens and closes it. */
    private boolean logOpen;
    private ValueAnimator logFold;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Draws behind the system bars on every version. From Android 15 the platform does this
        // whether asked or not; below it the bars would otherwise take a strip of their own and the
        // red header would stop short of the top edge.
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_rf88_inventory);
        progress = findViewById(R.id.progress);

        header = findViewById(R.id.header);
        startStop = findViewById(R.id.startStop);
        clear = findViewById(R.id.clear);
        stats = findViewById(R.id.stats);
        empty = findViewById(R.id.empty);
        logText = findViewById(R.id.log);
        logScroll = findViewById(R.id.logScroll);

        RecyclerView list = findViewById(R.id.tags);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        // The header's own back arrow. Goes through the dispatcher rather than finish() so a screen
        // that later needs to intervene on back is handled the same from both routes.
        findViewById(R.id.back).setOnClickListener(
                v -> getOnBackPressedDispatcher().onBackPressed());

        startStop.setOnClickListener(v -> startStop());
        clear.setOnClickListener(v -> clearList());
        applySystemBarInsets();
        setUpLogSheet();

        // ─────────────────────────── SDK ───────────────────────────
        rfidManager.setOnInventoryResultListener(this);
        rfidManager.setOnOperationStateChangedListener(this);
        rfidManager.setOnHardwareKeyListener(this);
        // ───────────────────────────────────────────────────────────

        // Not "is something connected" but "is the RF88 connected". isConnected() with no
        // argument answers about whichever reader holds the link, which may be the other one.
        if (!rfidManager.isConnected(RfidReader.RF88)) {
            String reader = rfidManager.getActiveReader();
            logError(reader == null
                    ? "no reader connected — connect on the previous screen first"
                    : reader + " holds the link, not the RF88 — disconnect it first");
        }
        showStats();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // ─────────────────────────── SDK ───────────────────────────
        // Stop first, then drop the listeners. A scan left running keeps the RF module on after the
        // screen is gone, and listeners left registered keep this Activity alive.
        executor.execute(rfidManager::stop);
        rfidManager.setOnInventoryResultListener(null);
        rfidManager.setOnOperationStateChangedListener(null);
        rfidManager.setOnHardwareKeyListener(null);
        // ───────────────────────────────────────────────────────────

        uiRefresh.removeCallbacksAndMessages(null);
        executor.shutdown();
    }

    // ── Listener callbacks (SDK thread) ──────────────────────────────────────

    /**
     * One tag, once per read. Override this six-argument form or the five-argument one, never both —
     * the SDK calls this one, and its default passes the values on to the shorter form.
     *
     * <p>A field the reader cannot supply arrives as an empty string, never null, and never as a
     * measured zero. The MF52 has no carrier frequency, so {@code frequency} is always
     * empty here; RSSI is empty when {@code setRssiReporting} has it switched off.
     */
    @Override
    public void onInventoryDiscovered(String epc, String ascii, String rssi, String frequency,
                                      String crc, String pc) {
        synchronized (tags) {
            Tag tag = tags.get(epc);
            if (tag == null) {
                tag = new Tag(epc);
                tags.put(epc, tag);
            }
            tag.rssi = rssi;
            tag.crc = crc;
            tag.pc = pc;
            tag.reads++;
            totalReads++;
        }
        scheduleRefresh();
    }

    /**
     * The RF88's trigger, pulled and released.
     *
     * <p><b>Which trigger arrives here is a setting, not a fact.</b> {@code KEY_MAPPING}
     * decides whether the upper and lower triggers mean RFID or barcode, so an RF88 set to
     * {@code "ss"} never calls this at all. The scanner trigger also stops reaching the host entirely
     * when {@code SCANNER_TRIGGER_MODE} is {@code "g"}.
     *
     * <p>Callbacks arrive on an SDK thread, so the work is handed to {@link #executor} exactly as the
     * on-screen button does it.
     */
    @Override
    public void onInventoryKeyPressed() {
        log("trigger pulled");
        if (!scanning) startStop();
    }

    @Override
    public void onInventoryKeyReleased() {
        log("trigger released");
        // Under CONTINUOUS_MODE "p" the RF88 scans while the trigger is held, so releasing ends it.
        // Under "c" a double tap starts a scan that keeps running and the release means nothing.
        // Stop even if the scan has not reported STARTED yet: a quick pull is let go that early, and
        // stop() with nothing running does no harm.
        stopScan();
    }

    /** Keeps the button honest about what the reader is doing, whoever started or stopped it. */
    @Override
    public void onOperationStateChanged(OperationType type, OperationState state) {
        if (type != OperationType.INVENTORY) return;

        boolean running = state == OperationState.STARTED;
        log("inventory " + (running ? "started" : "finished"));
        runOnUiThread(() -> {
            scanning = running;
            if (running) startedAt = System.currentTimeMillis();
            startStop.setText(running ? R.string.inv_stop : R.string.inv_start);
            // Clearing mid-scan reads as a button that did nothing: the list refills at once.
            clear.setEnabled(!running);
            showStats();
        });
    }

    // ── SDK calls ────────────────────────────────────────────────────────────

    private void startStop() {
        if (scanning) {
            stopScan();
        } else {
            log("inventory()");
            inBackground(() -> {
                // Runs until something stops it. The button follows onOperationStateChanged rather
                // than this call, so it stays right if the reader stops on its own. The call returns
                // once the command is sent; a code other than success means nothing was started.
                String result = rfidManager.inventory();
                if (!ResultCode.isSuccessful(result)) {
                    logError("inventory() refused · " + result + explain(result));
                }
            });
        }
    }

    /**
     * Stops the scan. It goes through {@link #executor} like the start does, so it never overtakes a
     * start that has not returned yet.
     */
    private void stopScan() {
        log("stop()");
        inBackground(rfidManager::stop);
    }

    /** What a refused start means on this screen. */
    private String explain(String code) {
        switch (code) {
            case ResultCode.NOT_CONNECTED:
                return " (no reader connected — connect on one of the connect screens first)";
            case ResultCode.BUSY:
                return " (another operation is running — stop it first)";
            default:
                return "";
        }
    }

    /**
     * Hands one piece of SDK work to {@link #executor}, with the progress bar showing from the moment
     * it is sent until it returns. The SDK calls are synchronous: on the main thread they would freeze
     * the screen, and without the bar nothing shows that one is still running.
     */
    private void inBackground(Runnable work) {
        callsRunning.incrementAndGet();
        runOnUiThread(this::showProgress);
        executor.execute(() -> {
            try {
                work.run();
            } finally {
                callsRunning.decrementAndGet();
                runOnUiThread(this::showProgress);
            }
        });
    }

    // ── UI ───────────────────────────────────────────────────────────────────

    private void showProgress() {
        progress.setVisibility(callsRunning.get() > 0 ? View.VISIBLE : View.INVISIBLE);
    }

    private void clearList() {
        synchronized (tags) {
            tags.clear();
            totalReads = 0;
        }
        refresh();
        log("list cleared");
    }

    /** Coalesces reports into one redraw per {@link #REFRESH_MS}. */
    private void scheduleRefresh() {
        synchronized (uiRefresh) {
            if (refreshQueued) return;
            refreshQueued = true;
        }
        uiRefresh.postDelayed(() -> {
            synchronized (uiRefresh) {
                refreshQueued = false;
            }
            refresh();
        }, REFRESH_MS);
    }

    private void refresh() {
        List<Tag> snapshot;
        synchronized (tags) {
            snapshot = new ArrayList<>(tags.values());
        }
        runOnUiThread(() -> {
            adapter.submit(snapshot);
            empty.setVisibility(snapshot.isEmpty() ? View.VISIBLE : View.GONE);
            showStats();
        });
    }

    private void showStats() {
        int unique;
        long reads;
        synchronized (tags) {
            unique = tags.size();
            reads = totalReads;
        }
        long seconds = scanning ? Math.max(1L, (System.currentTimeMillis() - startedAt) / 1000L) : 0L;
        String rate = seconds > 0 ? "  " + (reads / seconds) + "/s" : "";
        stats.setText(getString(R.string.inv_stats, unique, reads) + rate);
    }

    private void copyEpc(String epc) {
        ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
        clipboard.setPrimaryClip(ClipData.newPlainText("EPC", epc));
        Toast.makeText(this, R.string.inv_copied, Toast.LENGTH_SHORT).show();
    }

    private void log(String line) {
        append(line, false);
    }

    private void logError(String line) {
        append(line, true);
    }

    /**
     * Appends one line. Safe from any thread.
     *
     * <p><b>The text is read at render time, not at append time.</b> Taking a snapshot here and
     * handing it to the UI thread loses lines: {@code runOnUiThread} called <i>on</i> the main
     * thread runs straight away, so an older snapshot already queued from a background thread lands
     * afterwards and overwrites the newer text. Rendering the buffer as it stands cannot go stale.
     */
    private void append(String line, boolean isError) {
        synchronized (logBuffer) {
            SpannableString text = new SpannableString(clock.format(new Date()) + "  " + line + "\n");
            if (isError) {
                text.setSpan(new ForegroundColorSpan(ContextCompat.getColor(this, R.color.pm_log_error)),
                        0, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            }
            logBuffer.append(text);
        }
        logText.post(() -> {
            final CharSequence current;
            synchronized (logBuffer) {
                current = new SpannedString(logBuffer);
            }
            logText.setText(current);
            logScroll.fullScroll(View.FOCUS_DOWN);
        });
    }

    /**
     * Folds the log away behind the bar at the bottom of the screen.
     *
     * <p>The log is worth having on every screen and worth looking at on almost none of them, so it
     * shows one bar and opens when that bar is tapped.
     *
     * <p>The panel is a row of this screen rather than something floating over it, so the room it
     * opens into comes from the content above. Animating its height moves both together, and a tap
     * while it is still moving turns it around from where it has got to.
     */
    private void setUpLogSheet() {
        View chevron = findViewById(R.id.logChevron);
        int openHeight = getResources().getDimensionPixelSize(R.dimen.log_open_height);
        findViewById(R.id.logHandle).setOnClickListener(v -> {
            logOpen = !logOpen;
            if (logFold != null) logFold.cancel();
            logFold = ValueAnimator.ofInt(logScroll.getHeight(), logOpen ? openHeight : 0);
            logFold.setDuration(220L);
            logFold.addUpdateListener(step -> {
                logScroll.getLayoutParams().height = (int) step.getAnimatedValue();
                logScroll.requestLayout();
                // Hold the newest line against the bottom edge as the panel grows.
                logScroll.scrollTo(0, logText.getHeight());
            });
            logFold.start();
            chevron.animate().rotation(logOpen ? 0f : 180f).setDuration(220L);
        });
    }

    private void applySystemBarInsets() {
        View content = findViewById(R.id.content);
        int top = header.getPaddingTop(), left = header.getPaddingLeft(), right = header.getPaddingRight();
        int contentTop = content.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            header.setPadding(left + bars.left, top + bars.top, right + bars.right, header.getPaddingBottom());
            // The log sheet is the last row of this column, so the column is what stops above the
            // navigation bar and the sheet comes to rest on top of it.
            v.setPadding(0, 0, 0, bars.bottom);
            content.setPadding(bars.left, contentTop, bars.right, content.getPaddingBottom());
            return insets;
        });
    }

    /** One tag and what the reads so far said about it. */
    private static final class Tag {
        final String epc;
        String rssi = "", crc = "", pc = "";
        int reads;

        Tag(String epc) {
            this.epc = epc;
        }
    }

    private final class TagAdapter extends RecyclerView.Adapter<TagAdapter.Holder> {

        private List<Tag> items = new ArrayList<>();

        void submit(List<Tag> next) {
            items = next;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_tag, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            holder.bind(items.get(position));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        final class Holder extends RecyclerView.ViewHolder {
            private final TextView epc, detail, count;

            Holder(@NonNull View view) {
                super(view);
                epc = view.findViewById(R.id.epc);
                detail = view.findViewById(R.id.detail);
                count = view.findViewById(R.id.count);
            }

            void bind(Tag tag) {
                epc.setText(tag.epc);
                // An empty field means the reader did not supply it — shown as a dash so it is never
                // mistaken for a measured zero.
                detail.setText(getString(R.string.inv_detail,
                        or(tag.pc), or(tag.crc), or(tag.rssi)));
                count.setText(getString(R.string.inv_count, tag.reads));
                itemView.setOnClickListener(v -> copyEpc(tag.epc));
            }

            private String or(String value) {
                return TextUtils.isEmpty(value) ? "—" : value;
            }
        }
    }
}
