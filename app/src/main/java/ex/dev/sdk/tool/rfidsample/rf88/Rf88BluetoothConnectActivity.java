package ex.dev.sdk.tool.rfidsample.rf88;

import android.animation.ValueAnimator;
import android.Manifest;
import android.annotation.SuppressLint;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.view.LayoutInflater;
import android.view.ViewGroup;
import android.widget.Button;

import androidx.annotation.NonNull;
import androidx.core.app.ActivityCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import android.os.Bundle;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
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

import java.util.ArrayList;
import java.util.List;

/**
 * Finds an RF88 over the air and connects to it.
 *
 * <p><b>Reader:</b> RF88. Discovery and permissions here are plain Android — the SDK only ever
 * sees the address string. {@link Rf88NfcPairingActivity} reaches the same connection without a
 * discovery list, and {@link Rf88WiredConnectActivity} over the cradle.
 *
 * <p><b>SDK calls demonstrated</b>
 * <ul>
 *   <li>{@link RfidManager#connect(String)} with a discovered address</li>
 *   <li>{@link RfidManager#disconnect()}</li>
 *   <li>{@link RfidManager#setOnConnectionStateChangedListener}</li>
 * </ul>
 *
 * <p><b>To copy this into your own app</b>, take the {@code rfidManager} and {@code executor} fields,
 * {@code connect(String)}, the listener at the bottom, and the manifest permissions. The discovery
 * half — receiver, adapter, runtime permissions — is ordinary Bluetooth code and can be replaced with
 * whatever the host app already has; all the SDK needs is the address.
 * The progress bar and {@code inBackground()} only show that a call is running — call
 * {@code executor.execute} directly if you show that your own way.
 *
 * <p><b>Discovery needs permission at runtime, and it is not the same permission on every
 * version.</b> From Android 12 it is {@code BLUETOOTH_SCAN} and {@code BLUETOOTH_CONNECT}; below
 * that it is location. Without them {@code startDiscovery} quietly finds nothing.
 *
 * <p><b>Threading.</b> {@link RfidManager#connect(String)} blocks until the attempt finishes, so it
 * runs on {@link #executor}. The outcome arrives through the listener.
 */
public class Rf88BluetoothConnectActivity extends AppCompatActivity
        implements OnConnectionStateChangedListener {

    /** RF88s answer discovery with this in their name. Everything else is noise on this screen. */
    private static final String READER_NAME = "RF88";

    private static final int REQUEST_PERMISSIONS = 1;

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

    private final DeviceAdapter adapter = new DeviceAdapter();
    private final List<BluetoothDevice> found = new ArrayList<>();

    private BluetoothAdapter bluetooth;
    private Button scanButton;
    private TextView empty;
    private boolean scanning;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_rf88_bluetooth);
        progress = findViewById(R.id.progress);

        state = findViewById(R.id.state);
        detail = findViewById(R.id.detail);
        logText = findViewById(R.id.log);
        logScroll = findViewById(R.id.logScroll);
        empty = findViewById(R.id.empty);
        scanButton = findViewById(R.id.scan);

        RecyclerView list = findViewById(R.id.devices);
        list.setLayoutManager(new LinearLayoutManager(this));
        list.setAdapter(adapter);

        findViewById(R.id.back).setOnClickListener(
                v -> getOnBackPressedDispatcher().onBackPressed());
        scanButton.setOnClickListener(v -> toggleScan());
        findViewById(R.id.disconnect).setOnClickListener(v -> disconnect());
        applySystemBarInsets();
        setUpLogSheet();

        BluetoothManager manager = getSystemService(BluetoothManager.class);
        bluetooth = manager == null ? null : manager.getAdapter();
        if (bluetooth == null) {
            logError("this device has no Bluetooth — use the wired connection instead");
        } else if (!bluetooth.isEnabled()) {
            logError("Bluetooth is off — turn it on, then scan");
        }

        registerReceiver(receiver, discoveryFilter());

        // ─────────────────────────── SDK ───────────────────────────
        rfidManager.setOnConnectionStateChangedListener(this);
        // ───────────────────────────────────────────────────────────

        showState(rfidManager.getConnectionState());
        log("SDK " + rfidManager.getSdkVersion() + " ready");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopScan();
        unregisterReceiver(receiver);
        rfidManager.setOnConnectionStateChangedListener(null);
        executor.shutdown();
    }

    // ── Listener callbacks (SDK thread) ──────────────────────────────────────

    @Override
    public void onConnectionStateChanged(ConnectionState newState, @Nullable String reader) {
        log("state = " + newState + (reader == null ? "" : " (" + reader + ")"));
        runOnUiThread(() -> {
            // FAILURE is not explained here. The listener replays the current state as soon as it is
            // registered, so a FAILURE left by an attempt on another screen would read as this one's.
            // report() explains this screen's own attempts from the code they return.
            showState(newState);
        });
    }

    // ── SDK calls ────────────────────────────────────────────────────────────

    private void connect(BluetoothDevice device) {
        String address = device.getAddress();
        stopScan();
        detail.setText("");
        log("connect(" + address + ")");
        inBackground(() -> {
            // Discovery is the host's business; the SDK takes the address and nothing else.
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
        runOnUiThread(() -> detail.setText(
                "the RF88 did not answer — it may be out of range or held by another app"));
    }

    private void disconnect() {
        log("disconnect()");
        inBackground(rfidManager::disconnect);
    }

    // ── Discovery (plain Android, no SDK) ────────────────────────────────────

    private void toggleScan() {
        if (scanning) {
            stopScan();
        } else {
            startScan();
        }
    }

    @SuppressLint("MissingPermission")
    private void startScan() {
        if (bluetooth == null || !bluetooth.isEnabled()) {
            logError("Bluetooth is not available");
            return;
        }
        if (!hasPermissions()) {
            ActivityCompat.requestPermissions(this, requiredPermissions(), REQUEST_PERMISSIONS);
            return;
        }
        found.clear();
        adapter.submit(new ArrayList<>(found));
        empty.setVisibility(View.VISIBLE);
        // startDiscovery() answers whether it started. Throwing that away is how a scan that
        // never ran looks like a scan that found nothing.
        boolean started = bluetooth.startDiscovery();
        if (started) {
            log("startDiscovery() = true");
        } else {
            logError("startDiscovery() = false — the adapter refused; nothing is scanning");
        }
    }

    @SuppressLint("MissingPermission")
    private void stopScan() {
        if (bluetooth != null && bluetooth.isDiscovering() && hasPermissions()) {
            bluetooth.cancelDiscovery();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions,
                                           @NonNull int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != REQUEST_PERMISSIONS) return;
        if (hasPermissions()) {
            startScan();
        } else {
            logError("scanning needs the Bluetooth permissions — nothing will be found without them");
        }
    }

    private String[] requiredPermissions() {
        // From Android 12 discovery is its own permission pair; before that it rode on location.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            // Location is asked for on 12 and up as well: some devices still want it before
            // startDiscovery() will run, and asking for it costs nothing where they do not.
            return new String[]{Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT,
                    Manifest.permission.ACCESS_FINE_LOCATION};
        }
        return new String[]{Manifest.permission.ACCESS_FINE_LOCATION};
    }

    private boolean hasPermissions() {
        for (String permission : requiredPermissions()) {
            if (ActivityCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
                return false;
            }
        }
        return true;
    }

    private IntentFilter discoveryFilter() {
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_FOUND);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED);
        filter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        return filter;
    }

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            log("broadcast " + action);
            if (BluetoothAdapter.ACTION_DISCOVERY_STARTED.equals(action)) {
                scanning = true;
                scanButton.setText(R.string.rf88_stop_scan);
            } else if (BluetoothAdapter.ACTION_DISCOVERY_FINISHED.equals(action)) {
                scanning = false;
                scanButton.setText(R.string.rf88_scan);
            } else if (BluetoothDevice.ACTION_FOUND.equals(action)) {
                BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                if (device == null || !hasPermissions()) return;
                String name = device.getName();
                // Anonymous devices and everything that is not an RF88 are noise here.
                if (name == null || !name.contains(READER_NAME)) return;
                for (BluetoothDevice known : found) {
                    if (known.getAddress().equals(device.getAddress())) return;
                }
                found.add(device);
                adapter.submit(new ArrayList<>(found));
                empty.setVisibility(View.GONE);
                log("found " + name + " " + device.getAddress());
            }
        }
    };

    private final class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.Holder> {

        private List<BluetoothDevice> items = new ArrayList<>();

        @SuppressLint("NotifyDataSetChanged")
        void submit(List<BluetoothDevice> next) {
            items = next;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new Holder(LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_device, parent, false));
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
            private final TextView name, address;

            Holder(@NonNull View view) {
                super(view);
                name = view.findViewById(R.id.name);
                address = view.findViewById(R.id.address);
            }

            @SuppressLint("MissingPermission")
            void bind(BluetoothDevice device) {
                name.setText(hasPermissions() ? device.getName() : device.getAddress());
                address.setText(device.getAddress());
                itemView.setOnClickListener(v -> connect(device));
            }
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
