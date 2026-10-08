package ex.dev.sdk.tool.rfidsample.mf52;

import android.animation.ValueAnimator;
import android.graphics.Typeface;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.SpannedString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
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
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import ex.dev.sdk.rfid.RfidManager;
import ex.dev.sdk.rfid.domain.constants.ResultCode;
import ex.dev.sdk.rfid.domain.constants.ConfigKey;
import ex.dev.sdk.rfid.domain.reader.ConnectionState;
import ex.dev.sdk.rfid.frameworks.listener.OnConnectionStateChangedListener;
import ex.dev.sdk.rfid.frameworks.listener.OnLicenseStateChangedListener;
import ex.dev.sdk.tool.rfidsample.R;

/**
 * Connects the RFID reader built into the device (MF52) and shows its license standing.
 *
 * <p><b>Reader:</b> MF52. The RF88 connects a different way — see the
 * {@code rf88} package. Only one reader can be connected at a time.
 *
 * <p><b>SDK calls demonstrated</b>
 * <ul>
 *   <li>{@link RfidManager#connectInternal()} / {@link RfidManager#disconnect()}</li>
 *   <li>{@link RfidManager#setOnConnectionStateChangedListener} — every state change, including SLEEP</li>
 *   <li>{@link RfidManager#setOnLicenseStateChangedListener} — the only way to hear that a license
 *       was withdrawn while the reader was up</li>
 *   <li>{@link RfidManager#getLicenseState()} and {@link RfidManager#isSupported(String)} — how to tell
 *       "this reader has no license at all" from "the read did not come back"</li>
 * </ul>
 *
 * <p><b>To copy this into your own app</b>, take:
 * <ol>
 *   <li>the {@code rfidManager} and {@code executor} fields,</li>
 *   <li>{@code inBackground()} and {@code showProgress()} with the {@code progress} bar — or call
 *       {@code executor.execute} directly if you show progress your own way,</li>
 *   <li>{@code connect()}, {@code disconnect()} and {@code readLicenseState()},</li>
 *   <li>the two listener implementations at the bottom,</li>
 *   <li>the listener clean-up in {@link #onDestroy()} — leaving listeners registered leaks this Activity.</li>
 * </ol>
 * No permission is needed for the MF52.
 *
 * <p><b>Threading.</b> Every synchronous SDK method blocks until the reader answers, so this screen
 * calls them on {@link #executor} and never on the main thread. Listener callbacks arrive on an SDK
 * thread, so anything that touches a View is handed to {@link #runOnUiThread(Runnable)} first.
 */
public class Mf52ConnectActivity extends AppCompatActivity
        implements OnConnectionStateChangedListener, OnLicenseStateChangedListener {

    private final RfidManager rfidManager = RfidManager.getInstance();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    /** SDK calls handed to {@link #executor} that have not returned yet. The progress bar shows while any do. */
    private final AtomicInteger callsRunning = new AtomicInteger();
    private LinearProgressIndicator progress;
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);

    /**
     * Everything the log has shown so far. Guarded by its own monitor because lines arrive from
     * three threads — the SDK's callback thread, this screen's {@link #executor}, and the main
     * thread — and a log whose lines are out of order is worse than no log at all.
     */
    private final SpannableStringBuilder logBuffer = new SpannableStringBuilder();

    private TextView stateText;
    private TextView licenseText;
    private TextView logText;
    private View header;
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
        setContentView(R.layout.activity_mf52_connect);
        progress = findViewById(R.id.progress);

        header = findViewById(R.id.header);
        stateText = findViewById(R.id.state);
        licenseText = findViewById(R.id.license);
        logText = findViewById(R.id.log);
        logScroll = findViewById(R.id.logScroll);
        // The header's own back arrow. Goes through the dispatcher rather than finish() so a screen
        // that later needs to intervene on back is handled the same from both routes.
        findViewById(R.id.back).setOnClickListener(
                v -> getOnBackPressedDispatcher().onBackPressed());

        findViewById(R.id.connect).setOnClickListener(v -> connect());
        findViewById(R.id.disconnect).setOnClickListener(v -> disconnect());
        applySystemBarInsets();
        setUpLogSheet();

        // ─────────────────────────── SDK ───────────────────────────
        // Register before connecting, or the states the connection itself goes through are missed.
        rfidManager.setOnConnectionStateChangedListener(this);
        rfidManager.setOnLicenseStateChangedListener(this);
        // ───────────────────────────────────────────────────────────

        showState(rfidManager.getConnectionState());
        log("SDK " + rfidManager.getSdkVersion() + " ready");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // ─────────────────────────── SDK ───────────────────────────
        // The manager is an app-wide singleton and holds these until they are cleared, so a screen
        // that forgets this line keeps being called after it is gone.
        rfidManager.setOnConnectionStateChangedListener(null);
        rfidManager.setOnLicenseStateChangedListener(null);
        // ───────────────────────────────────────────────────────────

        executor.shutdown();
    }

    // ── Listener callbacks (SDK thread) ──────────────────────────────────────

    @Override
    public void onConnectionStateChanged(ConnectionState newState, @Nullable String reader) {
        // SLEEP is a connected state: the reader is idle and wakes on the next command. Treating it
        // as a disconnection is the usual mistake here.
        log("state = " + newState + (reader == null ? "" : " (" + reader + ")"));
        runOnUiThread(() -> showState(newState));

        if (newState == ConnectionState.CONNECTED) {
            readLicenseState();
        }
    }

    @Override
    public void onLicenseStateChanged(String state) {
        // A license can be withdrawn while the reader is up. Reading getLicenseState() once at
        // connect time would not catch that; this callback is what does.
        log("license changed to " + state);
        showLicense(state);
    }

    // ── SDK calls ────────────────────────────────────────────────────────────

    /** Brings up the MF52. Blocks, so it runs off the main thread. */
    private void connect() {
        log("connectInternal()");
        inBackground(() -> {
            String outcome = rfidManager.connectInternal();
            if (ResultCode.SUCCESS.equals(outcome)) {
                log("connectInternal() = " + outcome);
            } else if (ResultCode.BUSY.equals(outcome)) {
                String holder = rfidManager.getActiveReader();
                logError("connectInternal() = " + outcome + " — "
                        + (holder == null ? "another reader" : holder)
                        + " holds the link, disconnect it first");
            } else {
                // Not a crash: a device with no MF52 answers this way, and that is the
                // normal outcome on most models.
                logError("connectInternal() = " + outcome
                        + " — no MF52 on this device, or it is already in use");
            }
        });
    }

    /** Takes the link down. Safe to call when nothing is connected. */
    private void disconnect() {
        log("disconnect()");
        inBackground(rfidManager::disconnect);
    }

    /**
     * Reads the license standing, and says plainly when it cannot be read.
     *
     * <p>An empty answer is ambiguous on its own: the reader may have no license at all, or the read
     * may simply not have come back. {@link RfidManager#isSupported(String)} is what tells those
     * apart — and it only answers true once a reader is connected.
     */
    private void readLicenseState() {
        inBackground(() -> {
            // isSupported() only answers true once a reader is connected, so asking it on a link
            // that has gone down would report "no license at all" about a reader that has one. And a
            // link held by the RF88 is no better: the RF88 has no license, so the same question would
            // report this reader as unlicensed hardware. Check which reader answers first.
            if (!"MF52".equals(rfidManager.getActiveReader())) {
                log("skipped getLicenseState() — the MF52 is not connected");
                showLicense("not connected");
                return;
            }

            String state = rfidManager.getLicenseState();
            String shown;
            boolean failed = false;
            if (!TextUtils.isEmpty(state)) {
                // UNCHECKED / VALID / INVALID / REVOKED. UNCHECKED means the reader has not settled
                // it yet — it does not mean there is no license.
                shown = state;
            } else if (rfidManager.isSupported(ConfigKey.LICENSE_STATE)) {
                shown = "read failed — reader has a license but did not answer";
                failed = true;
            } else {
                shown = "not licensed hardware — this reader has no license at all";
            }

            String line = "getLicenseState() = " + (TextUtils.isEmpty(state) ? "\"\"" : state);
            if (failed) logError(line); else log(line);

            // The link can drop while this runs. Without this check the answer would overwrite the
            // dash that the disconnect just put there.
            if ("MF52".equals(rfidManager.getActiveReader())) {
                showLicense(shown);
            }
        });
    }

    private void showLicense(String text) {
        runOnUiThread(() -> licenseText.setText(getString(R.string.mf52_license, text)));
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
     * and {@link RfidManager#getConnectionState()} reports whichever holds it — so with the RF88 up
     * this line would otherwise read CONNECTED while the MF52 is not connected at all.
     * {@link RfidManager#getActiveReader()} names the reader.
     *
     * <p>SLEEP counts as usable — the reader is idle and wakes on the next command — so it is shown in
     * the same colour as CONNECTED rather than as a fault.
     */
    private void showState(ConnectionState state) {
        String reader = rfidManager.getActiveReader();
        boolean mine = "MF52".equals(reader);
        stateText.setText(reader == null || mine ? state.name() : state.name() + " · " + reader);

        int color;
        if (mine) {
            color = R.color.pm_ok;
        } else if (reader != null || state == ConnectionState.FAILURE) {
            // The RF88 holding the link is not a fault, but nothing on this screen will work until it
            // lets go — so it is painted like one rather than like a working reader.
            color = R.color.pm_error;
        } else {
            color = R.color.pm_text_tertiary;
        }
        stateText.setTextColor(ContextCompat.getColor(this, color));

        if (!mine) {
            licenseText.setText(getString(R.string.mf52_license, "-"));
        }
    }

    /** Appends one line to the on-screen log. Safe to call from any thread. */
    private void log(String line) {
        append(line, false);
    }

    /**
     * Same, but painted so a failure cannot be missed while scrolling. Every screen in this sample
     * shows what went wrong on screen rather than only in logcat, because that is what a customer
     * can send back in a support thread.
     */
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
            // Stamping and appending happen under the same lock. Doing either outside it lets two
            // threads stamp in one order and land in the other, which shows up as timestamps that
            // run backwards.
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
     * The red header spans the full width and reaches under the status bar, so the top and side
     * insets go on it. Everything below takes the side and bottom insets instead — otherwise a grey
     * strip shows beside the red where the navigation bar sits.
     */
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
        int headerTop = header.getPaddingTop();
        int headerLeft = header.getPaddingLeft();
        int headerRight = header.getPaddingRight();
        int contentTop = content.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            header.setPadding(headerLeft + bars.left, headerTop + bars.top,
                    headerRight + bars.right, header.getPaddingBottom());
            // The log sheet is the last row of this column, so the column is what stops above the
            // navigation bar and the sheet comes to rest on top of it.
            v.setPadding(0, 0, 0, bars.bottom);
            content.setPadding(bars.left, contentTop, bars.right, content.getPaddingBottom());
            return insets;
        });
    }
}
