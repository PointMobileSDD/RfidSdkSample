package ex.dev.sdk.tool.rfidsample.rf88;

import android.animation.ValueAnimator;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
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
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import ex.dev.sdk.rfid.RfidManager;
import ex.dev.sdk.rfid.domain.constants.ResultCode;
import ex.dev.sdk.rfid.domain.reader.ConnectionState;
import ex.dev.sdk.rfid.frameworks.listener.OnConnectionStateChangedListener;
import ex.dev.sdk.tool.rfidsample.R;

/**
 * Connects the RF88 over the wire, through the cradle it sits in.
 *
 * <p><b>Reader:</b> RF88. This is the cabled path; {@link Rf88BluetoothConnectActivity} is the
 * wireless one and {@link Rf88NfcPairingActivity} reads the address off the RF88's own tag. All three
 * end at the same connected reader, so the screens past this one do not care which was used.
 *
 * <p><b>SDK calls demonstrated</b>
 * <ul>
 *   <li>{@link RfidManager#connect()} — no address: the cradle has only one RF88 in it</li>
 *   <li>{@link RfidManager#disconnect()}</li>
 *   <li>{@link RfidManager#setOnConnectionStateChangedListener}</li>
 * </ul>
 *
 * <p><b>To copy this into your own app</b>, take the {@code rfidManager} and {@code executor} fields,
 * {@code connect()} and {@code disconnect()}, the listener at the bottom, and the clean-up in
 * {@link #onDestroy()}. No permission is needed for the cabled path.
 * The progress bar and {@code inBackground()} only show that a call is running — call
 * {@code executor.execute} directly if you show that your own way.
 *
 * <p><b>Seating the RF88 does not connect it.</b> Some devices report the cradle over a
 * GPIO line and some do not, so an app that waits for a detach/attach signal will wait forever on the
 * ones that do not. Offer the button, as this screen does.
 *
 * <p><b>Threading.</b> {@link RfidManager#connect()} blocks until the attempt finishes, so it runs on
 * {@link #executor}. The outcome arrives through the listener, not from the call.
 */
public class Rf88WiredConnectActivity extends AppCompatActivity
        implements OnConnectionStateChangedListener {

    private final RfidManager rfidManager = RfidManager.getInstance();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    /** SDK calls handed to {@link #executor} that have not returned yet. The progress bar shows while any do. */
    private final AtomicInteger callsRunning = new AtomicInteger();
    private LinearProgressIndicator progress;
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private final SpannableStringBuilder logBuffer = new SpannableStringBuilder();

    private TextView state;
    private TextView detail;
    private TextView logText;
    private ScrollView logScroll;
    /** Whether the log panel is open, and the animation that opens and closes it. */
    private boolean logOpen;
    private ValueAnimator logFold;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_rf88_wired);
        progress = findViewById(R.id.progress);

        state = findViewById(R.id.state);
        detail = findViewById(R.id.detail);
        logText = findViewById(R.id.log);
        logScroll = findViewById(R.id.logScroll);
        findViewById(R.id.back).setOnClickListener(
                v -> getOnBackPressedDispatcher().onBackPressed());
        findViewById(R.id.connect).setOnClickListener(v -> connect());
        findViewById(R.id.disconnect).setOnClickListener(v -> disconnect());
        applySystemBarInsets();
        setUpLogSheet();

        // ─────────────────────────── SDK ───────────────────────────
        rfidManager.setOnConnectionStateChangedListener(this);
        // ───────────────────────────────────────────────────────────

        showState(rfidManager.getConnectionState());
        log("SDK " + rfidManager.getSdkVersion() + " ready");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // ─────────────────────────── SDK ───────────────────────────
        // The manager is app-wide and holds this until it is cleared.
        rfidManager.setOnConnectionStateChangedListener(null);
        // ───────────────────────────────────────────────────────────

        executor.shutdown();
    }

    // ── Listener callbacks (SDK thread) ──────────────────────────────────────

    @Override
    public void onConnectionStateChanged(ConnectionState newState, @Nullable String reader) {
        // SLEEP is a connected state. The RF88 powers its RF module down after the suspend timeout and
        // wakes on the next command; a screen that closes on it is closing on a working reader.
        log("state = " + newState + (reader == null ? "" : " (" + reader + ")"));
        runOnUiThread(() -> {
            // FAILURE is not explained here. The listener replays the current state as soon as it is
            // registered, so a FAILURE left by an attempt on another screen would read as this one's.
            // report() explains this screen's own attempts from the code they return.
            showState(newState);
        });
    }

    // ── SDK calls ────────────────────────────────────────────────────────────

    private void connect() {
        detail.setText("");
        log("connect()");
        inBackground(() -> {
            // No address: the wired path has exactly one RF88 to talk to.
            report("connect()", rfidManager.connect());
        });
    }

    /**
     * Turns the code {@code connect} answered with into a line on screen.
     *
     * <p><b>{@link ResultCode#BUSY} is not a fault.</b> Only one reader may be connected at a time,
     * so it means the other one holds the link. Nothing else reports this: a refused connect leaves the
     * state alone and calls no listener, so the returned code is the only place it shows.
     */
    private void report(String call, String outcome) {
        if (ResultCode.SUCCESS.equals(outcome)) {
            log(call + " = " + outcome);
            return;
        }
        if (ResultCode.BUSY.equals(outcome)) {
            String holder = rfidManager.getActiveReader();
            logError(call + " = " + outcome + " — "
                    + (holder == null ? "another reader" : holder)
                    + " holds the link, disconnect it first");
            return;
        }
        logError(call + " = " + outcome + ", state = " + rfidManager.getConnectionState());
        runOnUiThread(() -> detail.setText(
                "no RF88 answered on the wire — check it is seated in the cradle"));
    }

    private void disconnect() {
        log("disconnect()");
        inBackground(rfidManager::disconnect);
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

    /**
     * Paints the state line, naming the reader when the link belongs to the other one.
     *
     * <p><b>The state does not say whose link it is.</b> Only one reader may be connected at a time,
     * and {@link RfidManager#getConnectionState()} reports whichever holds it — so with the MF52
     *  up this line would otherwise read CONNECTED while no RF88 is attached at all.
     * {@link RfidManager#getActiveReader()} names the reader.
     *
     * <p>SLEEP counts as connected: the RF88 powers its RF module down after the suspend timeout and wakes
     * on the next command.
     */
    private void showState(ConnectionState current) {
        String reader = rfidManager.getActiveReader();
        boolean RF88 = "RF88".equals(reader);
        state.setText(reader == null || RF88 ? current.name() : current.name() + " · " + reader);

        int color;
        if (RF88) {
            color = R.color.pm_ok;
        } else if (reader != null || current == ConnectionState.FAILURE) {
            // Another reader holding the link is not a fault, but nothing on this screen will work
            // until it lets go — so it is painted like one rather than like a working RF88.
            color = R.color.pm_error;
        } else {
            color = R.color.pm_text_tertiary;
        }
        state.setTextColor(ContextCompat.getColor(this, color));
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
