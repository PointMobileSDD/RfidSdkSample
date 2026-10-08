package ex.dev.sdk.tool.rfidsample.rf88;

import android.animation.ValueAnimator;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Button;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.textfield.MaterialAutoCompleteTextView;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.function.Supplier;

import ex.dev.sdk.rfid.RfidManager;
import ex.dev.sdk.rfid.domain.constants.RfidReader;
import ex.dev.sdk.rfid.domain.constants.ConfigKey;
import ex.dev.sdk.rfid.domain.constants.ResultCode;
import ex.dev.sdk.tool.rfidsample.R;

/**
 * Reads and changes the reader's settings, including the Select the other screens act through.
 *
 * <p><b>Reader:</b> RF88. Connect first — every setting here is the reader's own, and
 * {@link RfidManager#isSupported(String)} only answers true once a reader is up, so a screen opened
 * before connecting shows nothing.
 *
 * <p><b>Why the Select lives here.</b> A mask is not one value. What it is compared against is the
 * Select bank, where the comparison starts is the Select pointer <i>in bits</i>, and whether the
 * matching tag or every other tag is acted on is the Select action. The mask the tag access screen
 * sends is meaningless without these three, so they are set in one place and that screen shows what is
 * in force rather than owning a second copy.
 *
 * <p><b>SDK calls demonstrated</b>
 * <ul>
 *   <li>{@link RfidManager#isSupported(String)} — a setting the reader does not have is left out
 *       rather than shown as a failure</li>
 *   <li>{@link RfidManager#getSupportedPowers()} / {@link RfidManager#getSupportedRegions()} /
 *       {@link RfidManager#getSupportedLinkProfiles()} — ask the reader for its choices instead of
 *       carrying a table, because the numbers differ between readers</li>
 *   <li>every {@code getX} / {@code setX} pair below, one line each in {@link #buildRows()}</li>
 * </ul>
 *
 * <p><b>To copy this into your own app</b>, take the getter/setter pair from the row you want out of
 * {@link #buildRows()} and the {@code write()} body, which is what turns a result code into something
 * a person can act on. The row plumbing above it is only this screen's form.
 * The progress bar and {@code inBackground()} only show that a call is running — call
 * {@code executor.execute} directly if you show that your own way.
 *
 * <p><b>Threading.</b> Every call blocks until the reader answers, so reads and writes run on
 * {@link #executor} and the form is filled back on the main thread.
 */
public class Rf88SettingsActivity extends AppCompatActivity {

    /**
     * What each link profile id means on this reader, so the picker does not offer bare numbers.
     *
     * <p><b>The numbering is this reader's own.</b> The MF52 answers single
     * digits for a different set of profiles, so its screen needs its own table — never share one.
     * Which ids exist still comes from {@link RfidManager#getSupportedLinkProfiles()}; this only
     * labels them, and an id that is not here is shown as it came.
     */
    private static final String[][] LINK_PROFILES = {
            {"103", "read rate · DSB · 640 kHz · M1"},
            {"302", "read rate · PR · 640 kHz · M1"},
            {"120", "read rate · DSB · 640 kHz · M2"},
            {"323", "read rate · PR · 640 kHz · M2"},
            {"345", "read rate · PR · 640 kHz · M4"},
            {"344", "ET-UB(D) · PR · 640 kHz · M4"},
            {"223", "ET-LB · PR · 320 kHz · M4"},
            {"222", "ET-LB · PR · 320 kHz · M2"},
            {"241", "ET-LB(D) · PR · 320 kHz · M4"},
            {"244", "FCC(D) · PR · 250 kHz · M4"},
            {"285", "sensitivity · PR · 160 kHz · M8"},
    };

    private final RfidManager rfidManager = RfidManager.getInstance();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    /** SDK calls handed to {@link #executor} that have not returned yet. The progress bar shows while any do. */
    private final AtomicInteger callsRunning = new AtomicInteger();
    private LinearProgressIndicator progress;
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private final SpannableStringBuilder logBuffer = new SpannableStringBuilder();
    private final List<Row> rows = new ArrayList<>();

    private LinearLayout rfidRows, qRows, selectRows, applyBar;
    private Button applyButton;
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
        setContentView(R.layout.activity_rf88_settings);
        progress = findViewById(R.id.progress);

        rfidRows = findViewById(R.id.rfidRows);
        qRows = findViewById(R.id.qRows);
        selectRows = findViewById(R.id.selectRows);
        applyBar = findViewById(R.id.applyBar);
        applyButton = findViewById(R.id.apply);
        applyButton.setOnClickListener(v -> apply());
        findViewById(R.id.discard).setOnClickListener(v -> discard());
        setApplyEnabled(0);
        setUpLogSheet();
        logText = findViewById(R.id.log);
        logScroll = findViewById(R.id.logScroll);
        // The header's own back arrow. Goes through the dispatcher rather than finish() so a screen
        // that later needs to intervene on back is handled the same from both routes.
        findViewById(R.id.back).setOnClickListener(
                v -> getOnBackPressedDispatcher().onBackPressed());

        applySystemBarInsets();

        // Not "is something connected" but "is the RF88 connected". isConnected() with no
        // argument answers about whichever reader holds the link, which may be the other one.
        if (!rfidManager.isConnected(RfidReader.RF88)) {
            String reader = rfidManager.getActiveReader();
            logError(reader == null
                    ? "no reader connected — connect first, then come back"
                    : reader + " holds the link, not the RF88 — disconnect it first");
            return;
        }
        inBackground(this::buildRows);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    // ── SDK calls ────────────────────────────────────────────────────────────

    /**
     * One line per setting: its label, where its choices come from, and the getter and setter that
     * carry it. Runs on {@link #executor} because every call here talks to the reader.
     */
    private void buildRows() {
        // A text field here, a picker on the MF52's screen. getSupportedPowers() is
        // the MF52's alone: it takes a fixed ladder of levels and refuses anything
        // between them, so its screen offers exactly those. The RF88 publishes no ladder — it takes
        // any value in the range — so asking for one here answers empty, and a screen that treats
        // empty as "no such setting" drops the row and the power cannot be set at all.
        addText(rfidRows, ConfigKey.POWER, "Power (tenths of a dBm, 1-300)",
                rfidManager::getPower, rfidManager::setPower);
        addChoice(rfidRows, ConfigKey.REGION, "Region", this::regionChoices,
                rfidManager::getRegion, rfidManager::setRegion);
        addChoice(rfidRows, ConfigKey.LINK_PROFILE, "Link profile", this::linkProfileChoices,
                rfidManager::getLinkProfile, rfidManager::setLinkProfile);
        addChoice(rfidRows, ConfigKey.SESSION, "Session",
                () -> choices("S0=0", "S1=1", "S2=2", "S3=3"),
                rfidManager::getSession, rfidManager::setSession);
        // The RF88 takes all three. The MF52 has no dual target and refuses it, which is
        // why its screen offers only two — the list belongs to the reader, never shared.
        addChoice(rfidRows, ConfigKey.SEARCH_MODE, "Search mode",
                () -> choices("Dual target=0", "Single target A=1", "Single target B=2"),
                rfidManager::getSearchMode, rfidManager::setSearchMode);
        // The RF88 has a buzzer of its own with two loudnesses. The MF52 has none, so the
        // SDK substitutes the device's speaker there and takes only the first two values.
        addChoice(rfidRows, ConfigKey.BUZZER_VOLUME, "Buzzer",
                () -> choices("mute=0", "low=1", "high=2"),
                rfidManager::getBuzzerVolume, rfidManager::setBuzzerVolume);
        addChoice(rfidRows, ConfigKey.RSSI_REPORTING, "RSSI in tag reports",
                () -> choices("on=true", "off=false"),
                rfidManager::getRssiReporting, rfidManager::setRssiReporting);

        addChoice(rfidRows, ConfigKey.VIBRATOR_MODE, "Vibrator",
                () -> choices("off=0", "on=1"),
                rfidManager::getVibratorMode, rfidManager::setVibratorMode);
        addText(rfidRows, ConfigKey.SUSPEND_TIMEOUT, "Sleep after (minutes, 0 = never)",
                rfidManager::getSuspendTimeout, rfidManager::setSuspendTimeout);
        addChoice(rfidRows, ConfigKey.CONTINUOUS_MODE, "Trigger behaviour",
                () -> choices("hold to scan=p", "double-tap to start=c"),
                rfidManager::getContinuousMode, rfidManager::setContinuousMode);
        addChoice(rfidRows, ConfigKey.KEY_MAPPING, "Triggers (upper, lower)",
                () -> choices("RFID · RFID=rr", "RFID · scanner=rs", "scanner · RFID=sr",
                        "scanner · scanner=ss"),
                rfidManager::getKeyMapping, rfidManager::setKeyMapping);
        // What a tag report carries. Changing it changes how every following report must be
        // parsed — fields the RF88 is not asked for arrive empty rather than as zero.
        addChoice(rfidRows, ConfigKey.PACKET_OPTION, "Tag report carries",
                () -> choices("PC + EPC=0", "EPC only=32", "+ checksum=1", "+ RSSI=2",
                        "+ frequency=8", "+ all three=11"),
                rfidManager::getPacketOption, rfidManager::setPacketOption);
        addChoice(rfidRows, ConfigKey.SCANNER_TRIGGER_MODE, "Scanner trigger reaches the host as",
                () -> choices("serial command=c", "GPIO line=g"),
                rfidManager::getScannerTriggerMode, rfidManager::setScannerTriggerMode);
        addChoice(rfidRows, ConfigKey.TRIGGER_EVENT_MODE, "Trigger events",
                () -> choices("off=0", "on=1"),
                rfidManager::getTriggerEventMode, rfidManager::setTriggerEventMode);

        addText(qRows, ConfigKey.Q_START, "Start Q", rfidManager::getQStart, rfidManager::setQStart);
        addText(qRows, ConfigKey.Q_MIN, "Min Q", rfidManager::getQMin, rfidManager::setQMin);
        addText(qRows, ConfigKey.Q_MAX, "Max Q", rfidManager::getQMax, rfidManager::setQMax);
        addChoice(qRows, ConfigKey.Q_INCREMENT, "Increment Q",
                () -> choices("off=0", "on=1"), rfidManager::getQIncrement, rfidManager::setQIncrement);
        addChoice(qRows, ConfigKey.Q_DECREMENT, "Decrement Q",
                () -> choices("off=0", "on=1"), rfidManager::getQDecrement, rfidManager::setQDecrement);
        addChoice(qRows, ConfigKey.Q_FIXED, "Fixed Q",
                () -> choices("off=0", "on=1"), rfidManager::getQFixed, rfidManager::setQFixed);

        addChoice(selectRows, ConfigKey.MEMORY_BANK, "Mask is compared against",
                () -> choices("0 · Reserved=0", "1 · EPC=1", "2 · TID=2", "3 · User=3"),
                rfidManager::getMemoryBank, rfidManager::setMemoryBank);
        // In bits, and counted with the PC word in front — the EPC itself starts at bit 32, not 0.
        addText(selectRows, ConfigKey.POINTER, "Mask starts at bit",
                rfidManager::getPointer, rfidManager::setPointer);
        addChoice(selectRows, ConfigKey.ACTION, "Select action",
                () -> choices("0 · select the match=0", "1 · select the match only=1",
                        "2 · deselect the rest=2", "3 · toggle the match=3",
                        "4 · deselect the match=4", "5 · deselect the match only=5",
                        "6 · select the rest=6", "7 · toggle the rest=7"),
                rfidManager::getAction, rfidManager::setAction);
        addChoice(selectRows, ConfigKey.TARGET, "Select target",
                () -> choices("S0=0", "S1=1", "S2=2", "S3=3", "SL=4"),
                rfidManager::getTarget, rfidManager::setTarget);
        addChoice(selectRows, ConfigKey.SEL, "SL flag filter",
                () -> choices("ALL=ALL", "SELECT=SELECT", "DESELECT=DESELECT"),
                rfidManager::getSel, rfidManager::setSel);

        log(rows.size() + " settings this reader has");
    }

    /**
     * Sends one setting, then reads it back. The reader is what decides, not what was asked for — a
     * value it does not take leaves the old one in place, and the field has to say so.
     */
    /**
     * Holds a chosen value until Apply. Nothing reaches the reader here.
     *
     * <p>Writing on every keystroke or every dropdown pick sends one command per change, and a screen
     * with twenty settings then spends its time on the link. Collecting them lets a host send what the
     * operator actually meant, once.
     */
    private void stage(Row row, String value) {
        row.pending = value.equals(row.applied) ? null : value;
        showApplyBar();
    }

    /** Sends every staged value, then reads each one back. */
    private void apply() {
        List<Row> staged = new ArrayList<>();
        for (Row row : rows) if (row.pending != null) staged.add(row);
        if (staged.isEmpty()) return;

        runOnUiThread(() -> setApplyEnabled(0));
        inBackground(() -> {
            int failed = 0;
            for (Row row : staged) {
                String value = row.pending;

                // ─────────────────────────── SDK ───────────────────────────
                String result = row.setter.apply(value);
                // ───────────────────────────────────────────────────────────

                if (ResultCode.isSuccessful(result)) {
                    log(row.label + " = " + value);
                } else {
                    failed++;
                    logError(row.label + " rejected · " + ResultCode.codeOf(result));
                }

                // The reader is the source of truth. It can take a value and store another — a
                // level between two rungs of a ladder, say — so the row shows what came back, not
                // what was sent.
                String actual = row.getter.get();
                row.pending = null;
                row.applied = actual;
                runOnUiThread(() -> row.show(actual));
            }
            log(staged.size() + " setting(s) sent, " + failed + " rejected");
            runOnUiThread(this::showApplyBar);
        });
    }

    /** Drops every staged value and puts the rows back to what the reader last answered. */
    private void discard() {
        for (Row row : rows) {
            row.pending = null;
            String applied = row.applied;
            runOnUiThread(() -> row.show(applied));
        }
        showApplyBar();
    }

    /**
     * Keeps the Apply bar in step with what is staged.
     *
     * <p>The bar is always on screen, greyed until something is staged. Hiding it would leave a first
     * look at the screen with no sign that changes are collected rather than sent as they are made.
     */
    private void showApplyBar() {
        int staged = 0;
        for (Row row : rows) if (row.pending != null) staged++;
        final int count = staged;
        runOnUiThread(() -> setApplyEnabled(count));
    }

    private void setApplyEnabled(int count) {
        boolean any = count > 0;
        applyButton.setEnabled(any);
        applyButton.setAlpha(any ? 1f : 0.4f);
        applyButton.setText(any ? getString(R.string.settings_apply, count)
                : getString(R.string.settings_apply_idle));
        findViewById(R.id.discard).setEnabled(any);
        findViewById(R.id.discard).setAlpha(any ? 1f : 0.4f);
    }

    // ── Choices the reader publishes ─────────────────────────────────────────

    /** {@code NAME=code} pairs. The code is what setRegion takes; codes differ between readers. */
    private List<Choice> regionChoices() {
        return parsePairs(rfidManager.getSupportedRegions());
    }

    /**
     * The ids the reader publishes, labelled with what each one does. Ids it does not offer are
     * already left out by the SDK, so everything listed here can be written.
     */
    private List<Choice> linkProfileChoices() {
        List<Choice> out = new ArrayList<>();
        for (String id : split(rfidManager.getSupportedLinkProfiles())) {
            out.add(new Choice(id + " · " + describeLinkProfile(id), id));
        }
        return out;
    }

    private String describeLinkProfile(String id) {
        for (String[] row : LINK_PROFILES) {
            if (row[0].equals(id)) return row[1];
        }
        return "profile " + id;
    }

    // ── Form ─────────────────────────────────────────────────────────────────

    private void addChoice(LinearLayout into, String key, String label,
                           Supplier<List<Choice>> choices, Supplier<String> getter,
                           Function<String, String> setter) {
        if (!supported(key, label)) return;
        List<Choice> options = choices.get();
        if (options.isEmpty()) {
            logError(label + ": the reader published no values to choose from");
            return;
        }
        String current = getter.get();
        // Supported and readable are not the same thing. A reader can take a setting and still
        // not report it back, and an empty box looks like a fault; say what it is instead.
        if (TextUtils.isEmpty(current)) {
            log(label + ": the reader takes this but does not report it back");
        }
        Row row = new Row(label, getter, setter, options);
        runOnUiThread(() -> {
            TextInputLayout field = (TextInputLayout) LayoutInflater.from(this)
                    .inflate(R.layout.item_setting, into, false);
            field.setHint(label);
            MaterialAutoCompleteTextView input = field.findViewById(R.id.value);
            List<String> labels = new ArrayList<>();
            for (Choice choice : options) labels.add(choice.label);
            input.setAdapter(new ArrayAdapter<>(this, R.layout.item_dropdown, labels));
            // Choosing does not write. It records the value and the Apply bar takes it from there.
            input.setOnItemClickListener((p, v, position, id) -> stage(row, options.get(position).value));
            row.attach(input);
            row.show(current);
            into.addView(field);
        });
        rows.add(row);
    }

    private void addText(LinearLayout into, String key, String label, Supplier<String> getter,
                         Function<String, String> setter) {
        if (!supported(key, label)) return;
        String current = getter.get();
        if (TextUtils.isEmpty(current)) {
            log(label + ": the reader takes this but does not report it back");
        }
        Row row = new Row(label, getter, setter, null);
        runOnUiThread(() -> {
            TextInputLayout field = (TextInputLayout) LayoutInflater.from(this)
                    .inflate(R.layout.item_setting_text, into, false);
            field.setHint(label);
            TextInputEditText input = field.findViewById(R.id.value);
            input.setOnEditorActionListener((v, actionId, event) -> {
                if (actionId != EditorInfo.IME_ACTION_DONE) return false;
                stage(row, v.getText().toString().trim());
                return true;
            });
            row.attach(input);
            row.show(current);
            into.addView(field);
        });
        rows.add(row);
    }

    /**
     * Leaves out what this reader does not have. Asking rather than guessing is the point: an empty
     * read alone cannot tell "no such setting" from "the read did not come back".
     */
    private boolean supported(String key, String label) {
        if (rfidManager.isSupported(key)) return true;
        log(label + ": not on this reader");
        return false;
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

    private List<Choice> choices(String... pairs) {
        return parsePairs(TextUtils.join(",", pairs));
    }

    private List<Choice> parsePairs(String csv) {
        List<Choice> out = new ArrayList<>();
        for (String pair : split(csv)) {
            int eq = pair.lastIndexOf('=');
            if (eq > 0) out.add(new Choice(pair.substring(0, eq), pair.substring(eq + 1)));
        }
        return out;
    }

    private List<String> split(String csv) {
        List<String> out = new ArrayList<>();
        if (TextUtils.isEmpty(csv)) return out;
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) out.add(trimmed);
        }
        return out;
    }

    private String tenthsToDbm(String tenths) {
        try {
            return (Integer.parseInt(tenths) / 10.0) + " dBm";
        } catch (NumberFormatException e) {
            return tenths;
        }
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
        View header = findViewById(R.id.header);
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

    /** A label the user sees and the value the reader takes. */
    private static final class Choice {
        final String label, value;

        Choice(String label, String value) {
            this.label = label;
            this.value = value;
        }
    }

    /** One setting on screen, and the pair of SDK calls behind it. */
    private static final class Row {
        final String label;
        final Supplier<String> getter;
        final Function<String, String> setter;
        @Nullable final List<Choice> options;
        private TextView input;

        /**
         * Chosen but not sent yet. Null when the row matches the reader.
         *
         * <p>Written on the main thread by {@code stage}, read on the worker thread by {@code apply} —
         * hence volatile.
         */
        @Nullable volatile String pending;

        /** What the reader last answered, so Discard has something to go back to. */
        @Nullable volatile String applied;

        Row(String label, Supplier<String> getter, Function<String, String> setter,
            @Nullable List<Choice> options) {
            this.label = label;
            this.getter = getter;
            this.setter = setter;
            this.options = options;
        }

        void attach(TextView input) {
            this.input = input;
        }

        /** Shows what the reader answered, by label where there is one. */
        void show(String value) {
            if (pending == null) applied = value;
            if (input == null) return;
            if (TextUtils.isEmpty(value)) {
                // Not a blank field — the reader simply did not answer. Saying so keeps it from
                // reading as a value of its own.
                input.setText("not reported back");
                return;
            }
            String shown = value;
            if (options != null) {
                for (Choice choice : options) {
                    if (choice.value.equalsIgnoreCase(value)) {
                        shown = choice.label;
                        break;
                    }
                }
            }
            if (input instanceof MaterialAutoCompleteTextView) {
                ((MaterialAutoCompleteTextView) input).setText(shown, false);
            } else {
                input.setText(shown);
            }
        }
    }
}
