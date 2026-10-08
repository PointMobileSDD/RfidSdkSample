package ex.dev.sdk.tool.rfidsample.rf88;

import android.animation.ValueAnimator;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.SpannedString;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
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
import com.google.android.material.progressindicator.LinearProgressIndicator;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import ex.dev.sdk.rfid.RfidManager;
import ex.dev.sdk.rfid.domain.constants.RfidReader;
import ex.dev.sdk.rfid.domain.constants.ConfigValue;
import ex.dev.sdk.rfid.domain.constants.ResultCode;
import ex.dev.sdk.rfid.domain.reader.LockActionMask;
import ex.dev.sdk.rfid.domain.reader.LockMemoryMask;
import ex.dev.sdk.tool.rfidsample.R;

/**
 * Reads, writes, locks and kills one tag.
 *
 * <p><b>Reader:</b> RF88. Connect on one of the three connect screens first, and stop any scan —
 * an access command while inventory is running comes back {@code BUSY}.
 *
 * <p><b>SDK calls demonstrated</b>
 * <ul>
 *   <li>{@link RfidManager#read(String, String, String, String)} — and how to split the code from the
 *       data it carries</li>
 *   <li>{@link RfidManager#write(String, String, String, String)}</li>
 *   <li>{@link RfidManager#lock(LockMemoryMask, LockActionMask, String)} — the typed form, which is
 *       the one to prefer over the raw hex masks</li>
 *   <li>{@link RfidManager#kill(String, String)}</li>
 *   <li>{@link RfidManager#setAccessPassword(String)} — needed before touching a secured tag</li>
 *   <li>{@link ResultCode#codeOf(String)} / {@link ResultCode#dataOf(String)} /
 *       {@link ResultCode#isSuccessful(String)}</li>
 * </ul>
 *
 * <p><b>To copy this into your own app</b>, take:
 * <ol>
 *   <li>the {@code rfidManager} and {@code executor} fields,</li>
 *   <li>{@code inBackground()} and {@code showProgress()} with the {@code progress} bar — or call
 *       {@code executor.execute} directly if you show progress your own way,</li>
 *   <li>{@code read()} and {@code write()}, including the {@code ResultCode} handling,</li>
 *   <li>{@code lock()} and {@code kill()} with the confirmation in front of them,</li>
 *   <li>{@code applyAccessPassword()}, which has to run before any of them.</li>
 * </ol>
 *
 * <p><b>Lock and kill cannot be taken back.</b> PERMA_LOCK and PERMA_OPEN are permanent — OPEN does not
 * undo them — and a killed tag never answers again. Both sit behind a confirmation here, and a
 * device may refuse them outright, which comes back as an ordinary failure code.
 *
 * <p><b>Two pointers, two units.</b> The <i>pointer</i> field below counts 16-bit <b>words</b> into
 * the chosen bank. The Select mask is compared from a <b>bit</b> offset set separately with
 * {@code setPointer()}, counted with the PC word included. Mixing them up reads the wrong words or
 * matches nothing at all, with no error to say so.
 *
 * <p><b>Threading.</b> Both calls block until the tag answers, so they run on {@link #executor}.
 */
public class Rf88AccessActivity extends AppCompatActivity {

    /** Bank values are fixed by Gen2 and identical on every reader. */
    private static final String[] BANK_NAMES = {"Reserved", "EPC", "TID", "User"};
    /** Short for the strip at the top; the settings screen spells each one out. */
    private static final String[] ACTION_NAMES = {
            "match", "match only", "not match", "toggle",
            "≠ match", "≠ match only", "the rest", "toggle rest"};
    private static final String[] TARGET_NAMES = {"S0", "S1", "S2", "S3", "SL"};

    private static final String[] BANK_LABELS = {
            "0 · Reserved (passwords)", "1 · EPC", "2 · TID", "3 · User"};

    private static final String[] LOCK_AREAS = {"USER", "TID", "EPC", "ACCESS", "KILL"};
    private static final LockMemoryMask[] LOCK_AREA_VALUES = {
            LockMemoryMask.USER, LockMemoryMask.TID, LockMemoryMask.EPC,
            LockMemoryMask.ACCESS, LockMemoryMask.KILL};

    private static final String[] LOCK_ACTIONS = {"OPEN", "LOCK", "PERMA_OPEN", "PERMA_LOCK"};
    private static final LockActionMask[] LOCK_ACTION_VALUES = {
            LockActionMask.OPEN, LockActionMask.LOCK,
            LockActionMask.PERMA_OPEN, LockActionMask.PERMA_LOCK};

    private final RfidManager rfidManager = RfidManager.getInstance();
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    /** SDK calls handed to {@link #executor} that have not returned yet. The progress bar shows while any do. */
    private final AtomicInteger callsRunning = new AtomicInteger();
    private LinearProgressIndicator progress;
    private final SimpleDateFormat clock = new SimpleDateFormat("HH:mm:ss.SSS", Locale.US);
    private final SpannableStringBuilder logBuffer = new SpannableStringBuilder();

    private EditText mask, pointer, length, data, password, killPassword;
    private MaterialAutoCompleteTextView bank, lockArea, lockAction;
    private TextView selectBank, selectBit, selectAction, selectTarget, selectHint;
    private int lockAreaIndex, lockActionIndex = 1;

    /** Index into {@link #BANK_LABELS}, which is also the value the SDK takes. */
    private int bankIndex = 1;
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
        setContentView(R.layout.activity_rf88_access);
        progress = findViewById(R.id.progress);

        mask = findViewById(R.id.mask);
        pointer = findViewById(R.id.pointer);
        length = findViewById(R.id.length);
        data = findViewById(R.id.data);
        password = findViewById(R.id.password);
        bank = findViewById(R.id.bank);
        selectBank = findViewById(R.id.selectBank);
        selectBit = findViewById(R.id.selectBit);
        selectAction = findViewById(R.id.selectAction);
        selectTarget = findViewById(R.id.selectTarget);
        selectHint = findViewById(R.id.selectHint);
        findViewById(R.id.selectChange).setOnClickListener(
                v -> startActivity(new Intent(this, Rf88SettingsActivity.class)));
        logText = findViewById(R.id.log);
        logScroll = findViewById(R.id.logScroll);

        bank.setAdapter(new ArrayAdapter<>(this, R.layout.item_dropdown, BANK_LABELS));
        bank.setText(BANK_LABELS[bankIndex], false);
        bank.setOnItemClickListener((parent, view, position, id) -> bankIndex = position);

        killPassword = findViewById(R.id.killPassword);
        lockArea = findViewById(R.id.lockArea);
        lockAction = findViewById(R.id.lockAction);
        lockArea.setAdapter(new ArrayAdapter<>(this, R.layout.item_dropdown, LOCK_AREAS));
        lockArea.setText(LOCK_AREAS[lockAreaIndex], false);
        lockArea.setOnItemClickListener((parent, view, position, id) -> lockAreaIndex = position);
        lockAction.setAdapter(new ArrayAdapter<>(this, R.layout.item_dropdown, LOCK_ACTIONS));
        lockAction.setText(LOCK_ACTIONS[lockActionIndex], false);
        lockAction.setOnItemClickListener((parent, view, position, id) -> lockActionIndex = position);
        // The header's own back arrow. Goes through the dispatcher rather than finish() so a screen
        // that later needs to intervene on back is handled the same from both routes.
        findViewById(R.id.back).setOnClickListener(
                v -> getOnBackPressedDispatcher().onBackPressed());

        findViewById(R.id.read).setOnClickListener(v -> read());
        findViewById(R.id.write).setOnClickListener(v -> write());
        findViewById(R.id.lock).setOnClickListener(v -> confirmLock());
        findViewById(R.id.kill).setOnClickListener(v -> confirmKill());
        applySystemBarInsets();
        setUpLogSheet();

        if (!connected()) logError("no reader connected — connect on the connect screen first");
    }

    @Override
    protected void onResume() {
        super.onResume();
        showSelectInForce();
    }

    /**
     * Reads back the Select the mask above will be interpreted with. These four are set on the
     * settings screen, not here, and a mask means nothing without them — the bank decides what it is
     * compared against, the bit where the comparison starts, and the action whether the matching tag
     * or every other tag is acted on.
     *
     * <p>Read on every return to this screen rather than held in a listener: the settings screen is
     * what changes them, and the reader is the only place both screens agree on.
     */
    private void showSelectInForce() {
        if (!readerReady()) {
            showSelect("—", "—", "—", "—");
            selectHint.setText("");
            return;
        }
        inBackground(() -> {
            String bank = rfidManager.getMemoryBank();
            String bit = rfidManager.getPointer();
            String action = rfidManager.getAction();
            String target = rfidManager.getTarget();
            runOnUiThread(() -> {
                showSelect(name(bank, BANK_NAMES), or(bit), name(action, ACTION_NAMES),
                        name(target, TARGET_NAMES));
                selectHint.setText(maskShape(bank, bit));
            });
        });
    }

    /**
     * What the mask field has to contain for the bit offset in force.
     *
     * <p><b>The mask is not an EPC.</b> It is compared against whichever bank the Select
     * names — reserved, EPC, TID or user — and the offset counts from the start of that bank with the
     * PC word included. In the EPC bank that puts the PC word at bit 16 and the EPC itself at bit 32. At 16 a mask holding the
     * EPC alone lines up against the PC word instead and matches nothing — the operation then runs
     * until its watchdog ends it, several seconds later, and reports a timeout rather than a miss.
     * Nothing in the code that comes back says the mask was the wrong shape, so the screen says it
     * here instead.
     */
    private String maskShape(String bank, String bit) {
        if (TextUtils.isEmpty(bit)) return "";
        if (ConfigValue.MemoryBank.EPC.equals(bank)) {
            if ("16".equals(bit)) return getString(R.string.access_mask_pc);
            if ("32".equals(bit)) return getString(R.string.access_mask_epc);
        }
        return getString(R.string.access_mask_other, name(bank, BANK_NAMES), bit);
    }

    private void showSelect(String bank, String bit, String action, String target) {
        selectBank.setText(bank);
        selectBit.setText(bit);
        selectAction.setText(action);
        selectTarget.setText(target);
    }

    /** Turns a wire value into the word the settings screen shows for it, or leaves it as it came. */
    private String name(String value, String[] names) {
        try {
            int index = Integer.parseInt(value.trim());
            if (index >= 0 && index < names.length) return names[index];
        } catch (NumberFormatException ignored) {
            // Not a number this screen knows — show whatever the reader said.
        }
        return or(value);
    }

    private String or(String value) {
        return TextUtils.isEmpty(value) ? "—" : value;
    }

    /**
     * Whether the RF88 is the reader answering right now.
     *
     * <p><b>Not {@code isConnected()} with no argument.</b> That one answers about whichever
     * reader holds the link, so with the MF52 connected it is true here too, and every command
     * below would go to that reader instead.
     */
    private boolean readerReady() {
        return rfidManager.isConnected(RfidReader.RF88);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    // ── SDK calls ────────────────────────────────────────────────────────────

    private void read() {
        if (!connected()) return;
        String bankValue = bankValue();
        String from = text(pointer, "0");
        String words = text(length, "1");
        String target = maskOrNull();

        inBackground(() -> {
            applyAccessPassword();
            log("read(bank " + bankValue + ", word " + from + ", " + words + " words"
                    + (target == null ? ", any tag)" : ", mask " + target + ")"));

            // ─────────────────────────── SDK ───────────────────────────
            // A null mask means "whichever tag answers first". Passing the EPC of one tag is how a
            // command is aimed when several are in range.
            String result = rfidManager.read(bankValue, from, words, target);
            // ───────────────────────────────────────────────────────────

            // A successful read carries its data in the same string — "01 A5A5". Comparing the
            // whole string against SUCCESS never matches a read that worked.
            if (ResultCode.isSuccessful(result)) {
                log("read ok · " + ResultCode.dataOf(result));
            } else {
                String code = ResultCode.codeOf(result);
                logError("read failed · " + code + explain(code));
                hintWrongPassword(code);
            }
        });
    }

    private void write() {
        if (!connected()) return;
        String value = text(data, "");
        if (TextUtils.isEmpty(value)) {
            logError("nothing to write — fill the data field with hex, four digits per word");
            return;
        }
        String bankValue = bankValue();
        String from = text(pointer, "0");
        String target = maskOrNull();

        inBackground(() -> {
            applyAccessPassword();
            log("write(bank " + bankValue + ", word " + from + ", " + value + ")");

            // ─────────────────────────── SDK ───────────────────────────
            // Bank 0 holds the kill and access passwords. A wrong word there can leave the tag
            // unusable for good.
            String result = rfidManager.write(bankValue, from, value, target);
            // ───────────────────────────────────────────────────────────

            if (ResultCode.isSuccessful(result)) {
                log("write ok · read the words back to be sure");
            } else {
                // A failure code does not prove nothing was written. A write can reach the tag and
                // still be reported as failed, so read it back before deciding.
                String code = ResultCode.codeOf(result);
                logError("write failed · " + code + explain(code)
                        + " — read the words back before assuming the tag is unchanged");
                hintWrongPassword(code);
            }
        });
    }

    private void confirmLock() {
        if (!connected()) return;
        LockActionMask action = LOCK_ACTION_VALUES[lockActionIndex];
        String area = LOCK_AREAS[lockAreaIndex];
        // Only the permanent actions are one-way, so only they are worth stopping the user for.
        if (action != LockActionMask.PERMA_LOCK && action != LockActionMask.PERMA_OPEN) {
            lock();
            return;
        }
        confirm(getString(R.string.access_confirm_lock, action.name(), area), this::lock);
    }

    private void lock() {
        LockMemoryMask area = LOCK_AREA_VALUES[lockAreaIndex];
        LockActionMask action = LOCK_ACTION_VALUES[lockActionIndex];
        String target = maskOrNull();

        inBackground(() -> {
            applyAccessPassword();
            log("lock(" + area.name() + ", " + action.name() + ")");

            // ─────────────────────────── SDK ───────────────────────────
            // The typed form is the one to prefer: the raw hex form leaves the bit layout to the
            // caller, and getting it wrong locks something other than what was meant.
            String result = rfidManager.lock(area, action, target);
            // ───────────────────────────────────────────────────────────

            if (ResultCode.isSuccessful(result)) {
                // A lock reported as applied is still worth reading back — a tag can answer 01 and
                // leave the bits as they were.
                log("lock ok · read the bank back to be sure it took");
            } else {
                String code = ResultCode.codeOf(result);
                logError("lock failed · " + code + explain(code));
                // A tag only takes a lock while it is in the secured state, which it enters when a
                // non-zero access password is presented. On a tag that still has the factory
                // all-zero password there is no secured state to enter, so every lock is refused —
                // and the code that comes back says nothing about why.
                if (TextUtils.isEmpty(text(password, ""))) {
                    logError("  a lock needs the tag in its secured state — write a non-zero access"
                            + " password into bank 0 word 2 first, then enter it above");
                } else {
                    hintWrongPassword(code);
                }
            }
        });
    }

    private void confirmKill() {
        if (!connected()) return;
        String pw = text(killPassword, "");
        if (TextUtils.isEmpty(pw)) {
            logError("fill the kill password field — a kill is never sent without one");
            return;
        }
        // An all-zero password is not stopped here. The reader answers it with a code of its own,
        // and a sample that pre-empts that teaches a check the SDK already makes.
        confirm(getString(R.string.access_confirm_kill), () -> kill(pw));
    }

    private void kill(String pw) {
        String target = maskOrNull();

        inBackground(() -> {
            applyAccessPassword();
            log("kill(" + (target == null ? "first tag that answers" : "mask " + target) + ")");

            // ─────────────────────────── SDK ───────────────────────────
            String result = rfidManager.kill(pw, target);
            // ───────────────────────────────────────────────────────────

            if (ResultCode.isSuccessful(result)) {
                log("kill ok · that tag will never answer again");
            } else {
                String code = ResultCode.codeOf(result);
                logError("kill failed · " + code + explain(code));
            }
        });
    }

    /** One dialog in front of anything that cannot be taken back. */
    private void confirm(String message, Runnable action) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.access_danger_section)
                .setMessage(message)
                .setNegativeButton(R.string.access_cancel, null)
                .setPositiveButton(R.string.access_confirm, (d, which) -> action.run())
                .show();
    }

    /**
     * Sends the access password ahead of the operation. A tag in the secured state refuses a read or
     * write without it; a blank field sends all zeroes, which is what an unsecured tag expects.
     */
    private void applyAccessPassword() {
        String value = text(password, "00000000");
        String result = rfidManager.setAccessPassword(value);
        if (!ResultCode.isSuccessful(result)) {
            logError("setAccessPassword failed · " + ResultCode.codeOf(result));
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

    private boolean connected() {
        if (readerReady()) return true;
        String reader = rfidManager.getActiveReader();
        logError(reader == null
                ? "no reader connected"
                : reader + " holds the link, not the RF88 — disconnect it first");
        return false;
    }

    private String bankValue() {
        return String.valueOf(bankIndex);
    }

    /** Blank means "whichever tag answers first", which the SDK expects as null rather than "". */
    @Nullable
    private String maskOrNull() {
        String value = text(mask, "");
        return TextUtils.isEmpty(value) ? null : value;
    }

    private String text(EditText field, String fallback) {
        String value = field.getText().toString().trim();
        return value.isEmpty() ? fallback : value;
    }

    /**
     * The one a code alone cannot tell you: an access password that does not match the tag's fails
     * the whole operation, and it comes back as {@link ResultCode#OTHER_ERROR} rather than as a
     * password error. Measured on the MF52 — same tag, same spot, blank password five
     * writes in a row succeed, a wrong one five in a row fails. The RF88 was not measured; the Gen2
     * rule behind it is the same on any reader.
     */
    private void hintWrongPassword(String code) {
        if (!ResultCode.OTHER_ERROR.equals(code)) return;
        if (TextUtils.isEmpty(text(password, ""))) return;
        logError("  the access password may not match this tag's — a wrong one fails the operation"
                + " and reports " + ResultCode.OTHER_ERROR + ", not a password error. Leave it blank"
                + " for a tag that still has the factory all-zero password");
    }

    /** Turns the codes this screen runs into most often into something a reader can act on. */
    private String explain(String code) {
        switch (code) {
            case ResultCode.BUSY:
                return " (something else is running — stop the scan first)";
            case ResultCode.NO_TAG_RESPONSE:
                return " (no tag matched, or it moved out of range)";
            case ResultCode.MEMORY_LOCKED:
                return " (that bank is locked on this tag)";
            case ResultCode.INVALID_PASSWORD_ERROR:
                return " (wrong access password)";
            case ResultCode.MEMORY_OVERRUN:
                return " (the tag has no such words)";
            case ResultCode.INSUFFICIENT_POWER:
                // The one that looks like a logic fault and is not. A tag needs more energy to be
                // written than to be read, so the same tag at the same spot can answer a read and
                // refuse a write. Move it closer or raise the power before suspecting the arguments.
                return " (the tag has power enough to be read but not written — move it closer,"
                        + " or raise the power on the settings screen)";
            case ResultCode.KILL_PASSWORD_ZERO:
                return " (the kill password on the tag is all zeroes, which no tag accepts)";
            case ResultCode.UNSUPPORTED:
                return " (this reader or this device image does not allow it)";
            case ResultCode.INVALID_PARAM:
                return " (the SDK refused the arguments — check the fields above)";
            case ResultCode.TIMEOUT:
                // The command ran until a watchdog stopped it because nothing answered. With a mask
                // in play the mask is the first thing to check — see the strip at the top for the
                // shape it has to have.
                return TextUtils.isEmpty(text(mask, ""))
                        ? " (no answer came back)"
                        : " (" + getString(R.string.access_ff_masked) + ")";
            default:
                return "";
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
