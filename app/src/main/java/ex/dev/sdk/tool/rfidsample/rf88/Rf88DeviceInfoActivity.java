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
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

import ex.dev.sdk.rfid.RfidManager;
import ex.dev.sdk.rfid.domain.constants.RfidReader;
import ex.dev.sdk.rfid.domain.constants.ConfigKey;
import ex.dev.sdk.rfid.domain.constants.ResultCode;
import ex.dev.sdk.rfid.domain.reader.ConnectionType;
import ex.dev.sdk.tool.rfidsample.R;

/**
 * Everything the RF88 says about itself: charge, firmware and identity.
 *
 * <p><b>Reader:</b> RF88. The reader built into a device has no battery and none of the RF88's
 * hardware identity, so most of this reads empty there — which is why it is on the RF88's menu and not
 * shared.
 *
 * <p><b>SDK calls demonstrated</b>
 * <ul>
 *   <li>every {@code getX} in {@link #rows()} — one line each, all read-only</li>
 *   <li>{@link RfidManager#isSupported(String)} — a value this reader does not have says so in
 *       place of a reading</li>
 * </ul>
 *
 * <p><b>An empty answer is not a zero.</b> A field the RF88 did not supply comes back as
 * an empty string, and a charge of {@code ""} is not a flat battery. This screen shows a dash for it.
 *
 * <p><b>{@code getChargingStatus()} answers {@code "1"}/{@code "2"}/{@code "3"}</b> while
 * the battery listener on the inventory screen delivers {@code "DISCHARGING"}/{@code "CHARGING"}/
 * {@code "FULL_CHARGED"} for the same thing. Whichever path a host uses, it handles that path's form.
 *
 * <p><b>Threading.</b> Each getter blocks until the RF88 answers. The rows go up first, labelled and
 * empty, and the sweep runs on {@link #executor} and posts each value to its own row as it arrives —
 * so the screen is never a blank card waiting on a reader that may take a while or never answer.
 */
public class Rf88DeviceInfoActivity extends AppCompatActivity {

    private final RfidManager rfidManager = RfidManager.getInstance();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    /** SDK calls handed to {@link #executor} that have not returned yet. The progress bar shows while any do. */
    private final AtomicInteger callsRunning = new AtomicInteger();
    private LinearProgressIndicator progress;
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private final SpannableStringBuilder logBuffer = new SpannableStringBuilder();

    private final List<Row> values = new ArrayList<>();

    private LinearLayout rows;
    private TextView logText;
    private ScrollView logScroll;
    /** Whether the log panel is open, and the animation that opens and closes it. */
    private boolean logOpen;
    private ValueAnimator logFold;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_rf88_info);
        progress = findViewById(R.id.progress);

        rows = findViewById(R.id.rows);
        logText = findViewById(R.id.log);
        logScroll = findViewById(R.id.logScroll);
        findViewById(R.id.back).setOnClickListener(
                v -> getOnBackPressedDispatcher().onBackPressed());
        findViewById(R.id.refresh).setOnClickListener(v -> refresh());
        applySystemBarInsets();
        setUpLogSheet();
        // The rows go up before a single value is read, so the screen shows what it is going to
        // answer even when nothing is connected.
        draw();

        // Not "is something connected" but "is the RF88 connected". isConnected() with no
        // argument answers about whichever reader holds the link, which may be the other one.
        if (!rfidManager.isConnected(RfidReader.RF88)) {
            String reader = rfidManager.getActiveReader();
            logError(reader == null
                    ? "no reader connected — connect first, then come back"
                    : reader + " holds the link, not the RF88 — disconnect it first");
            return;
        }
        refresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    // ── SDK calls ────────────────────────────────────────────────────────────

    /** One line per value: its label, the key that says whether this reader has it, and its getter. */
    private List<Row> rows() {
        List<Row> out = new ArrayList<>();
        out.add(new Row("Charge", ConfigKey.BATTERY_LEVEL, () -> suffix(rfidManager.getBatteryLevel(), "%")));
        out.add(new Row("Charging", ConfigKey.CHARGING_STATUS, this::chargingStatus));
        out.add(new Row("Voltage", ConfigKey.BATTERY_VOLTAGE, () -> suffix(rfidManager.getBatteryVoltage(), " V")));
        out.add(new Row("Temperature", ConfigKey.BATTERY_TEMPERATURE, () -> suffix(rfidManager.getBatteryTemperature(), " C")));
        out.add(new Row("Health", ConfigKey.BATTERY_HEALTH, rfidManager::getBatteryHealth));
        out.add(new Row("RFID firmware", ConfigKey.RFID_VERSION, rfidManager::getRfidVersion));
        out.add(new Row("RF88 firmware", ConfigKey.FIRMWARE_VERSION, rfidManager::getFirmwareVersion));
        out.add(new Row("Bluetooth firmware", ConfigKey.BLUETOOTH_VERSION, rfidManager::getBluetoothVersion));
        out.add(new Row("Name", ConfigKey.DEVICE_NAME, rfidManager::getDeviceName));
        out.add(new Row("Address", ConfigKey.MAC_ADDRESS, rfidManager::getMacAddress));
        out.add(new Row("Power class", ConfigKey.POWER_CLASS, rfidManager::getPowerClass));
        out.add(new Row("Hardware revision", ConfigKey.HARDWARE_REVISION, rfidManager::getHardwareRevision));
        out.add(new Row("Attached over", ConfigKey.CONNECTION_TYPE, this::connectionType));
        return out;
    }

    private void refresh() {
        for (Row row : values) row.unknown();
        inBackground(() -> {
            int supported = 0;
            for (Row row : values) {
                // Asking rather than guessing: an empty read alone cannot tell "this reader has no
                // such value" from "the read did not come back".
                if (!rfidManager.isSupported(row.key)) {
                    runOnUiThread(row::unsupported);
                    continue;
                }
                supported++;
                // Each getter is a round trip to the reader. Post the answer as it comes rather than
                // collecting them all, or the screen stays empty for as long as the slowest one takes.
                String value = row.getter.get();
                runOnUiThread(() -> row.show(value));
            }
            log(supported + " of " + values.size() + " values this reader has");
        });
    }

    /**
     * Turns the getter's digit into a word. The battery listener reports the same thing as
     * {@code "DISCHARGING"} / {@code "CHARGING"} / {@code "FULL_CHARGED"}; this path answers digits.
     */
    private String chargingStatus() {
        String value = rfidManager.getChargingStatus();
        switch (value) {
            case "1": return "1 · discharging";
            case "2": return "2 · charging";
            case "3": return "3 · fully charged";
            default: return value;
        }
    }

    /**
     * Turns the wire value into the word {@link ConnectionType} names it by. The getter answers a
     * digit; the enum is what gives it a meaning, and a host that shows the digit shows nothing.
     */
    private String connectionType() {
        String value = rfidManager.getConnectionType();
        ConnectionType type = ConnectionType.ofValue(value);
        return type == null ? value : value + " · " + type.name().toLowerCase(Locale.US);
    }

    private String suffix(String value, String unit) {
        return TextUtils.isEmpty(value) ? value : value + unit;
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

    /** Puts every row on screen with its label and no value yet. Called once, before any read. */
    private void draw() {
        values.clear();
        rows.removeAllViews();
        for (Row row : rows()) {
            View view = LayoutInflater.from(this).inflate(R.layout.item_info, rows, false);
            ((TextView) view.findViewById(R.id.label)).setText(row.label);
            row.view = view.findViewById(R.id.value);
            row.unknown();
            rows.addView(view);
            values.add(row);
        }
    }

    /** One value on screen, and the SDK call behind it. */
    private final class Row {
        final String label;
        final String key;
        final Supplier<String> getter;
        TextView view;

        Row(String label, String key, Supplier<String> getter) {
            this.label = label;
            this.key = key;
            this.getter = getter;
        }

        /** Not read yet. */
        void unknown() {
            set(getString(R.string.info_unknown), false);
        }

        /** This reader does not have the value at all — {@code isSupported} said so. */
        void unsupported() {
            set(getString(R.string.info_unsupported), false);
        }

        /**
         * What the reader answered. An empty answer is not a zero, so it shows as a dash and is
         * never read as a measurement.
         */
        void show(String value) {
            boolean answered = !TextUtils.isEmpty(value);
            set(answered ? value : "\u2014", answered);
        }

        private void set(String text, boolean answered) {
            view.setText(text);
            view.setTextColor(ContextCompat.getColor(Rf88DeviceInfoActivity.this,
                    answered ? R.color.pm_text_primary : R.color.pm_text_secondary));
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
}
