package ex.dev.sdk.tool.rfidsample.rf88;

import android.animation.ValueAnimator;
import android.app.PendingIntent;
import android.content.Intent;
import android.nfc.NdefMessage;
import android.nfc.NdefRecord;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.Parcelable;
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
import androidx.core.content.IntentCompat;
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

import java.util.Arrays;

/**
 * Connects the RF88 by reading the Bluetooth address off the RF88's own NFC tag.
 *
 * <p><b>Reader:</b> RF88. This is the same Bluetooth connection
 * {@link Rf88BluetoothConnectActivity} makes; only the way the address is obtained differs. Holding
 * the device against the RF88 is quicker than picking it out of a discovery list, and it cannot pick
 * the wrong RF88 in a room full of them.
 *
 * <p><b>SDK calls demonstrated</b>
 * <ul>
 *   <li>{@link RfidManager#connect(String)} with the address read from the tag</li>
 *   <li>{@link RfidManager#disconnect()}</li>
 *   <li>{@link RfidManager#setOnConnectionStateChangedListener}</li>
 * </ul>
 *
 * <p><b>To copy this into your own app</b>, take {@code enableForegroundDispatch} in
 * {@link #onResume()}, {@code parseMacFromOobIntent} and everything it calls, the {@code onNewIntent}
 * hook, the {@code rfidManager} and {@code executor} fields, and the {@code android.permission.NFC}
 * entry from the manifest. The parsing is plain Android — the SDK only ever sees the finished address
 * string.
 * The progress bar and {@code inBackground()} only show that a call is running — call
 * {@code executor.execute} directly if you show that your own way.
 *
 * <p><b>An intent-filter in the manifest will not do.</b> Android handles a Bluetooth
 * out-of-band record itself: the NFC service matches it as a peripheral handover and starts its own
 * pairing, before any app's filter is consulted. Only foreground dispatch takes priority over that,
 * so this screen has to be open and resumed for the tap to reach it. There is no manifest filter here
 * for that reason — one would never fire for this record type.
 *
 * <p><b>The tag carries its address backwards.</b> A Bluetooth out-of-band handover
 * record stores the six MAC bytes little-endian, so they have to be reversed before they read as an
 * address. A tag that is not an OOB record, or one whose payload is truncated, is ignored rather than
 * parsed — a malformed tag would otherwise throw inside the parser.
 */
public class Rf88NfcPairingActivity extends AppCompatActivity
        implements OnConnectionStateChangedListener {

    /** MIME type of the Bluetooth out-of-band pairing handover record. */
    private static final String OOB_MIME_TYPE = "application/vnd.bluetooth.ep.oob";

    /** Two length bytes plus six address bytes. Anything shorter cannot carry an address. */
    private static final int MIN_OOB_PAYLOAD_LENGTH = 8;

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

    private NfcAdapter nfcAdapter;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_rf88_nfc);
        progress = findViewById(R.id.progress);

        state = findViewById(R.id.state);
        detail = findViewById(R.id.detail);
        logText = findViewById(R.id.log);
        logScroll = findViewById(R.id.logScroll);
        findViewById(R.id.back).setOnClickListener(
                v -> getOnBackPressedDispatcher().onBackPressed());
        findViewById(R.id.disconnect).setOnClickListener(v -> disconnect());
        applySystemBarInsets();
        setUpLogSheet();

        nfcAdapter = NfcAdapter.getDefaultAdapter(this);
        if (nfcAdapter == null) {
            logError("this device has no NFC — use Bluetooth or the wired connection instead");
        } else if (!nfcAdapter.isEnabled()) {
            logError("NFC is off — turn it on in system settings, then come back");
        }

        // ─────────────────────────── SDK ───────────────────────────
        rfidManager.setOnConnectionStateChangedListener(this);
        // ───────────────────────────────────────────────────────────

        showState(rfidManager.getConnectionState());
        handleTag(getIntent());
    }

    /**
     * Claims every tag tapped while this screen is in front.
     *
     * <p>This is what makes the tap arrive here at all. Without it the NFC service sees a Bluetooth
     * out-of-band record and runs its own pairing instead, and nothing reaches the app.
     */
    @Override
    protected void onResume() {
        super.onResume();
        if (nfcAdapter == null || !nfcAdapter.isEnabled()) return;
        // The system fills the tag into this intent, so on Android 12 and up it has to be mutable.
        int flags = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ? PendingIntent.FLAG_MUTABLE : 0;
        PendingIntent delivery = PendingIntent.getActivity(this, 0,
                new Intent(this, getClass()).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), flags);
        // Null filters mean "every tag", so a tag that is not a pairing tag still reaches the screen
        // and can be reported as one, rather than silently going somewhere else.
        nfcAdapter.enableForegroundDispatch(this, delivery, null, null);
        log("ready — hold the device's NFC area near the NFC mark on the RF88");
    }

    @Override
    protected void onPause() {
        super.onPause();
        // Left on, it would keep claiming tags for a screen the user has left.
        if (nfcAdapter != null && nfcAdapter.isEnabled()) nfcAdapter.disableForegroundDispatch(this);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleTag(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        rfidManager.setOnConnectionStateChangedListener(null);
        executor.shutdown();
    }

    // ── Listener callbacks (SDK thread) ──────────────────────────────────────

    @Override
    public void onConnectionStateChanged(ConnectionState newState, @Nullable String reader) {
        log("state = " + newState + (reader == null ? "" : " (" + reader + ")"));
        runOnUiThread(() -> showState(newState));
    }

    // ── SDK calls ────────────────────────────────────────────────────────────

    /**
     * Reads the address off a tapped tag.
     *
     * <p>The action is not checked. Foreground dispatch with no filters delivers
     * {@code ACTION_TAG_DISCOVERED}, not {@code ACTION_NDEF_DISCOVERED}, so a screen that insists on
     * the latter ignores every tap it just claimed. What matters is whether the tag carried a message.
     */
    private void handleTag(Intent intent) {
        if (intent == null || !intent.hasExtra(NfcAdapter.EXTRA_NDEF_MESSAGES)) return;
        String address = parseMacFromOobIntent(intent);
        if (address == null) {
            logError("that tag is not a Bluetooth pairing tag — nothing to connect to");
            return;
        }
        log("tag says " + address);
        runOnUiThread(() -> detail.setText(address));
        connect(address);
    }

    private void connect(String address) {
        log("connect(" + address + ")");
        inBackground(() -> {
            // The SDK takes the address as a plain string; where it came from is the host's business.
            report("connect()", rfidManager.connect(address));
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
    }

    private void disconnect() {
        log("disconnect()");
        inBackground(rfidManager::disconnect);
    }

    // ── NFC (plain Android, no SDK) ──────────────────────────────────────────

    @Nullable
    private String parseMacFromOobIntent(Intent intent) {
        Parcelable[] raw = IntentCompat.getParcelableArrayExtra(
                intent, NfcAdapter.EXTRA_NDEF_MESSAGES, NdefMessage.class);
        if (raw == null || raw.length == 0 || !(raw[0] instanceof NdefMessage)) return null;

        NdefRecord[] records = ((NdefMessage) raw[0]).getRecords();
        if (records.length == 0) return null;

        String mime = records[0].toMimeType();
        if (mime == null || !mime.contains(OOB_MIME_TYPE)) return null;

        byte[] payload = records[0].getPayload();
        // A tag carrying the right MIME type with a truncated payload would throw in the parser.
        if (payload == null || payload.length < MIN_OOB_PAYLOAD_LENGTH) return null;

        // Bytes 0-1 are a length the address does not need; 2-7 are the address, little-endian.
        byte[] mac = Arrays.copyOfRange(payload, 2, 8);
        StringBuilder out = new StringBuilder();
        for (int i = mac.length - 1; i >= 0; i--) {
            if (out.length() > 0) out.append(':');
            out.append(String.format(Locale.US, "%02X", mac[i]));
        }
        return out.toString();
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
