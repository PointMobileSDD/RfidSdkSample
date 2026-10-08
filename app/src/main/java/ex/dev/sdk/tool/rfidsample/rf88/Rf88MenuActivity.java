package ex.dev.sdk.tool.rfidsample.rf88;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import ex.dev.sdk.rfid.RfidManager;
import ex.dev.sdk.rfid.domain.constants.RfidReader;
import ex.dev.sdk.rfid.domain.reader.ConnectionState;
import ex.dev.sdk.tool.rfidsample.R;

/**
 * The screens for the RF88 attached to the device.
 *
 * <p><b>Reader:</b> RF88. Menu only — it issues no reader commands beyond reading the current
 * connection state for the line under the title, so the rest of the screens can be copied out without
 * bringing this one along.
 *
 * <p>The RF88 attaches three ways and any of them leads to the same connected reader: over Bluetooth
 * by address, over the Wire connection, or by reading the address off the RF88's NFC tag. Until
 * one of them succeeds the rest of the screens stay shut — see {@link #onResume()}.
 */
public class Rf88MenuActivity extends AppCompatActivity {

    private TextView state;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_rf88_menu);

        state = findViewById(R.id.state);
        findViewById(R.id.back).setOnClickListener(
                v -> getOnBackPressedDispatcher().onBackPressed());
        findViewById(R.id.bluetooth).setOnClickListener(v -> open(Rf88BluetoothConnectActivity.class));
        findViewById(R.id.wired).setOnClickListener(v -> open(Rf88WiredConnectActivity.class));
        findViewById(R.id.nfc).setOnClickListener(v -> open(Rf88NfcPairingActivity.class));
        findViewById(R.id.inventory).setOnClickListener(v -> open(Rf88InventoryActivity.class));
        findViewById(R.id.nread).setOnClickListener(v -> open(Rf88InventoryAndReadActivity.class));
        findViewById(R.id.access).setOnClickListener(v -> open(Rf88AccessActivity.class));
        findViewById(R.id.settings).setOnClickListener(v -> open(Rf88SettingsActivity.class));
        findViewById(R.id.info).setOnClickListener(v -> open(Rf88DeviceInfoActivity.class));
        applySystemBarInsets();
    }

    /**
     * Everything past connecting needs the RF88, so until the RF88 is the reader answering, only the
     * three connect screens can be opened.
     *
     * <p><b>"Something is connected" and "the RF88 is connected" are different questions.</b>
     * {@link RfidManager#getConnectionState()} reports the link of whichever reader holds it, so with
     * the MF52 up it says CONNECTED here as well — and the commands these screens send
     * would go to that reader instead of to the RF88.
     * {@link RfidManager#getActiveReader()} is the one that names the reader.
     *
     * <p>{@link ConnectionState#SLEEP} counts as connected. The RF88 powers its RF module down after
     * {@code setSuspendTimeout} and wakes on the next command; treating that as a disconnection is the
     * usual mistake, and it would shut these screens while the RF88 is perfectly usable.
     */
    @Override
    protected void onResume() {
        super.onResume();
        RfidManager rfidManager = RfidManager.getInstance();
        // The state of this screen's own reader, not of whichever one holds the link.
        ConnectionState current = rfidManager.getConnectionState(RfidReader.RF88);
        String holder = rfidManager.getActiveReader();
        boolean usable = current.isUsable();

        if (usable) {
            state.setText(current.name());
        } else if (holder != null) {
            // The MF52 is holding the link. Only one reader may be connected at a time, so
            // the RF88 cannot come up until that one is disconnected.
            state.setText(getString(R.string.menu_other_reader, holder));
        } else {
            state.setText(getString(R.string.menu_connect_first, current.name()));
        }

        setUsable(findViewById(R.id.inventory), usable);
        setUsable(findViewById(R.id.nread), usable);
        setUsable(findViewById(R.id.access), usable);
        setUsable(findViewById(R.id.settings), usable);
        setUsable(findViewById(R.id.info), usable);
    }

    private void setUsable(View card, boolean usable) {
        card.setEnabled(usable);
        card.setClickable(usable);
        card.setFocusable(usable);
        card.setAlpha(usable ? 1f : 0.4f);
    }

    private void open(Class<?> screen) {
        startActivity(new Intent(this, screen));
    }

    private void applySystemBarInsets() {
        View header = findViewById(R.id.header);
        View content = findViewById(R.id.content);
        int top = header.getPaddingTop(), left = header.getPaddingLeft(), right = header.getPaddingRight();
        int contentTop = content.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.root), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            header.setPadding(left + bars.left, top + bars.top, right + bars.right, header.getPaddingBottom());
            content.setPadding(bars.left, contentTop, bars.right, bars.bottom);
            return insets;
        });
    }
}
