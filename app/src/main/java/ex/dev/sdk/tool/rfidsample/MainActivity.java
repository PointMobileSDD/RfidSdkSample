package ex.dev.sdk.tool.rfidsample;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import ex.dev.sdk.rfid.RfidManager;
import ex.dev.sdk.tool.rfidsample.mf52.Mf52MenuActivity;
import ex.dev.sdk.tool.rfidsample.rf88.Rf88MenuActivity;

/**
 * Menu only. Pick a reader, then open the screens for it.
 *
 * <p>This screen issues no reader commands. The two reader packages are independent of each other and
 * of this class, so any screen can be copied out on its own — an RF88 integrator never has to read the
 * built-in reader's code, and the other way round.
 */
public class MainActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Draws behind the system bars on every version. From Android 15 the platform does this
        // whether asked or not; below it the bars would otherwise take a strip of their own and the
        // red header would stop short of the top edge.
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_main);

        // Gives the SDK a Context for audio. The built-in reader has no buzzer of its own, so without
        // this it stays silent when the buzzer setting is on. The RF88 beeps in hardware and needs
        // nothing here. Only the application Context is kept, so this cannot leak the Activity.
        RfidManager.init(this);

        // SDK logging, on in a debug build only. It is what turns a bare result code into a reason:
        // the SDK says in logcat why it refused, which the code alone never carries.
        RfidManager.setDebugMode(BuildConfig.DEBUG);

        TextView version = findViewById(R.id.sdkVersion);
        version.setText(getString(R.string.sdk_version, RfidManager.getInstance().getSdkVersion()));

        findViewById(R.id.mf52).setOnClickListener(
                v -> startActivity(new Intent(this, Mf52MenuActivity.class)));
        findViewById(R.id.rf88).setOnClickListener(
                v -> startActivity(new Intent(this, Rf88MenuActivity.class)));

        // The red header spans the full width and reaches under the status bar, so the top and side
        // insets go on it. Everything below takes the side and bottom insets instead — otherwise a
        // grey strip shows beside the red where the navigation bar sits.
        View header = findViewById(R.id.header);
        View content = findViewById(R.id.content);
        int headerTop = header.getPaddingTop();
        int headerLeft = header.getPaddingLeft();
        int headerRight = header.getPaddingRight();
        int contentTop = content.getPaddingTop();
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            header.setPadding(headerLeft + bars.left, headerTop + bars.top,
                    headerRight + bars.right, header.getPaddingBottom());
            content.setPadding(bars.left, contentTop, bars.right, bars.bottom);
            return insets;
        });
    }
}
