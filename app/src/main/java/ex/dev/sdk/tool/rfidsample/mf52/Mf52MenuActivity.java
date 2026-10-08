package ex.dev.sdk.tool.rfidsample.mf52;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.Nullable;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import ex.dev.sdk.rfid.RfidManager;
import ex.dev.sdk.rfid.domain.constants.RfidReader;
import ex.dev.sdk.rfid.domain.reader.ConnectionState;
import ex.dev.sdk.tool.rfidsample.R;

/**
 * The screens for the reader built into the device.
 *
 * <p><b>Reader:</b> MF52. Menu only — it issues no reader commands beyond reading the
 * current connection state for the line under the title, so the rest of the screens can be copied out
 * without bringing this one along.
 *
 * <p>Connect first. Inventory and tag access both need a reader that is up.
 */
public class Mf52MenuActivity extends AppCompatActivity {

    private TextView state;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Draws behind the system bars on every version. From Android 15 the platform does this
        // whether asked or not; below it the bars would otherwise take a strip of their own and the
        // red header would stop short of the top edge.
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_mf52_menu);

        state = findViewById(R.id.state);
        // The header's own back arrow. Goes through the dispatcher rather than finish() so a screen
        // that later needs to intervene on back is handled the same from both routes.
        findViewById(R.id.back).setOnClickListener(
                v -> getOnBackPressedDispatcher().onBackPressed());

        findViewById(R.id.connect).setOnClickListener(v -> open(Mf52ConnectActivity.class));
        findViewById(R.id.inventory).setOnClickListener(v -> open(Mf52InventoryActivity.class));
        findViewById(R.id.access).setOnClickListener(v -> open(Mf52AccessActivity.class));
        findViewById(R.id.settings).setOnClickListener(v -> open(Mf52SettingsActivity.class));
        applySystemBarInsets();
    }

    /**
     * Everything past connecting needs a reader that is up, so until there is one only the connect
     * screen can be opened. This is not politeness: {@code isSupported()} answers false while nothing
     * is connected, reads come back empty, and an inventory started on a dead link simply does
     * nothing — all of which read as faults rather than as "connect first".
     *
     * <p><b>"Something is connected" and "the MF52 is connected" are different
     * questions.</b> {@link RfidManager#getConnectionState()} reports the link of whichever reader
     * holds it, so with the RF88 up it says CONNECTED here as well — and the commands these screens
     * send would go to the RF88 instead. {@link RfidManager#getActiveReader()} is the one that names
     * the reader.
     *
     * <p>Asked on return from a screen rather than kept in a listener: this one only reports, and a
     * listener here would fight the screen that actually owns the connection.
     */
    @Override
    protected void onResume() {
        super.onResume();
        RfidManager rfidManager = RfidManager.getInstance();
        // The state of this screen's own reader, not of whichever one holds the link.
        ConnectionState current = rfidManager.getConnectionState(RfidReader.MF52);
        String holder = rfidManager.getActiveReader();
        boolean usable = current.isUsable();

        if (usable) {
            state.setText(current.name());
        } else if (holder != null) {
            // The RF88 is holding the link. Only one reader may be connected at a time, so the MF52 cannot come up until the RF88 is disconnected.
            state.setText(getString(R.string.menu_other_reader, holder));
        } else {
            state.setText(getString(R.string.menu_connect_first, current.name()));
        }

        setUsable(findViewById(R.id.inventory), usable);
        setUsable(findViewById(R.id.access), usable);
        setUsable(findViewById(R.id.settings), usable);
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
