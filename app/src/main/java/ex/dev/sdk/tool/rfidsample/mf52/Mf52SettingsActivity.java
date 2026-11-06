package ex.dev.sdk.tool.rfidsample.mf52;

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
 * <p><b>Reader:</b> MF52. Connect first — every setting here is the reader's own, and
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
public class Mf52SettingsActivity extends AppCompatActivity {

    /**
     * What each link profile id means on this reader, so the picker does not offer bare numbers.
     *
     * <p><b>The numbering is this reader's own.</b> The RF88 answers three-digit codes
     * for a different set of profiles, so an RF88 screen needs its own table — never share one.
     * Which ids exist still comes from {@link RfidManager#getSupportedLinkProfiles()}; this only
     * labels them, and an id that is not here is shown as it came.
     */
    private static final String[][] LINK_PROFILES = {
            {"0", "80 kbps · PR-ASK · M2"},
            {"3", "40 kbps · PR-ASK · M4"},
            {"4", "160 kbps · PR-ASK · FM0"},
            {"5", "160 kbps · DSB-ASK · FM0"},
            {"7", "80 kbps · DSB-ASK · M2"},
            {"8", "40 kbps · DSB-ASK · M4"},
    };

    private final RfidManager rfidManager = RfidManager.getInstance();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    /** SDK calls handed to {@link #executor} that have not returned yet. The progress bar shows while any do. */
    private final AtomicInteger callsRunning = new AtomicInteger();
    private LinearProgressIndicator progress;
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private final SpannableStringBuilder logBuffer = new SpannableStringBuilder();
    private final List<Row> rows = new ArrayList<>();

    private LinearLayout rfidRows, selectRows;
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
        setContentView(R.layout.activity_mf52_settings);
        progress = findViewById(R.id.progress);

        rfidRows = findViewById(R.id.rfidRows);
        selectRows = findViewById(R.id.selectRows);
        logText = findViewById(R.id.log);
        logScroll = findViewById(R.id.logScroll);
        // The header's own back arrow. Goes through the dispatcher rather than finish() so a screen
        // that later needs to intervene on back is handled the same from both routes.
        findViewById(R.id.back).setOnClickListener(
                v -> getOnBackPressedDispatcher().onBackPressed());

        applySystemBarInsets();
        setUpLogSheet();

        // Not "is something connected" but "is the MF52 connected". isConnected() with no
        // argument answers about whichever reader holds the link, which may be the other one.
        if (!rfidManager.isConnected(RfidReader.MF52)) {
            String reader = rfidManager.getActiveReader();
            logError(reader == null
                    ? "no reader connected — connect first, then come back"
                    : reader + " holds the link, not the MF52 — disconnect it first");
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
        addChoice(rfidRows, ConfigKey.POWER, "Power", this::powerChoices,
                rfidManager::getPower, rfidManager::setPower);
        addChoice(rfidRows, ConfigKey.REGION, "Region", this::regionChoices,
                rfidManager::getRegion, rfidManager::setRegion);
        addChoice(rfidRows, ConfigKey.LINK_PROFILE, "Link profile", this::linkProfileChoices,
                rfidManager::getLinkProfile, rfidManager::setLinkProfile);
        addChoice(rfidRows, ConfigKey.SESSION, "Session",
                () -> choices("S0=0", "S1=1", "S2=2", "S3=3"),
                rfidManager::getSession, rfidManager::setSession);
        // Dual target is not offered. The RF88 has it, this reader does not, and asking for it
        // comes back INVALID_PARAM rather than the reader quietly picking one. isSupported() says
        // the setting exists; which of its values a reader takes is not something it can answer, so
        // the list belongs to the reader's own screen and is never shared with the RF88's.
        addChoice(rfidRows, ConfigKey.SEARCH_MODE, "Search mode",
                () -> choices("Single target A=1", "Single target B=2"),
                rfidManager::getSearchMode, rfidManager::setSearchMode);
        // Only mute and low. The RF88 has a buzzer with two loudnesses; this reader has none, so
        // the SDK plays a tone through the device's speaker in its place — there is no second
        // loudness to give and "2" is refused rather than sounded as "1". It also needs
        // RfidManager.init(), which this app calls once on its first screen; without it the value is
        // remembered and nothing sounds.
        addChoice(rfidRows, ConfigKey.BUZZER_VOLUME, "Tag tone",
                () -> choices("off=0", "on=1"),
                rfidManager::getBuzzerVolume, rfidManager::setBuzzerVolume);
        addChoice(rfidRows, ConfigKey.RSSI_REPORTING, "RSSI in tag reports",
                () -> choices("on=true", "off=false"),
                rfidManager::getRssiReporting, rfidManager::setRssiReporting);

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
    private void write(Row row, String value) {
        inBackground(() -> {
            String result = row.setter.apply(value);
            if (ResultCode.isSuccessful(result)) {
                log(row.label + " = " + value);
            } else {
                logError(row.label + " rejected · " + ResultCode.codeOf(result));
            }
            String actual = row.getter.get();
            runOnUiThread(() -> row.show(actual));
        });
    }

    // ── Choices the reader publishes ─────────────────────────────────────────

    /** Levels in tenths of a dBm, weakest first. A ladder, not a range — nothing in between works. */
    private List<Choice> powerChoices() {
        List<Choice> out = new ArrayList<>();
        for (String level : split(rfidManager.getSupportedPowers())) {
            out.add(new Choice(tenthsToDbm(level), level));
        }
        return out;
    }

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
            input.setOnItemClickListener((p, v, position, id) -> write(row, options.get(position).value));
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
                write(row, v.getText().toString().trim());
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
