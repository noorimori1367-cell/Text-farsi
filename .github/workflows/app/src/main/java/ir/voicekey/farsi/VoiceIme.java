package ir.voicekey.farsi;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.inputmethodservice.InputMethodService;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputMethodManager;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * Voice keyboard: tap the mic, speak, and the recognised text is typed
 * straight into whatever field is focused (Telegram, WhatsApp, SMS, ...).
 */
public class VoiceIme extends InputMethodService implements RecognitionListener {

    private static final String LANG_FA = "fa-IR";
    private static final String LANG_EN = "en-US";

    private final Handler handler = new Handler(Looper.getMainLooper());

    private SpeechRecognizer recognizer;
    private boolean listening = false;
    private boolean continuous = false;
    private boolean cancelledByUs = false;
    private String lang = LANG_FA;

    // live (partial) text currently shown as "composing" in the field
    private boolean hasComposing = false;
    private String composingPrefix = "";

    // views
    private TextView status;
    private ImageView mic;
    private View micRing;
    private TextView langBtn;
    private ImageView enterBtn;
    private TextView[] punctKeys;

    private SharedPreferences prefs;

    // ---------------------------------------------------------------- setup

    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences("settings", MODE_PRIVATE);
        lang = prefs.getString("lang", LANG_FA);
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false; // keep the small keyboard even in landscape
    }

    @Override
    public View onCreateInputView() {
        View v = getLayoutInflater().inflate(R.layout.keyboard, null);

        status = v.findViewById(R.id.status);
        mic = v.findViewById(R.id.mic);
        micRing = v.findViewById(R.id.mic_ring);
        langBtn = v.findViewById(R.id.lang);
        enterBtn = v.findViewById(R.id.enter);
        punctKeys = new TextView[]{
                v.findViewById(R.id.p1), v.findViewById(R.id.p2),
                v.findViewById(R.id.p3), v.findViewById(R.id.p4)
        };

        mic.setOnClickListener(x -> { haptic(x); onMicClick(); });
        mic.setOnLongClickListener(x -> {
            haptic(x);
            if (!hasMicPermission()) { openSetup(); return true; }
            if (listening) cancelRecognition();
            continuous = true;
            startListening();
            return true;
        });

        View globe = v.findViewById(R.id.globe);
        globe.setOnClickListener(x -> { haptic(x); switchToPreviousKeyboard(); });
        globe.setOnLongClickListener(x -> { showPicker(); return true; });

        langBtn.setOnClickListener(x -> {
            haptic(x);
            lang = LANG_FA.equals(lang) ? LANG_EN : LANG_FA;
            prefs.edit().putString("lang", lang).apply();
            boolean wasListening = listening;
            if (wasListening) cancelRecognition();
            updateLangUi();
            if (wasListening) startListening();
            else setStatus(LANG_FA.equals(lang) ? R.string.lang_now_fa : R.string.lang_now_en);
        });

        View backspace = v.findViewById(R.id.backspace);
        backspace.setOnTouchListener(this::onBackspaceTouch);

        enterBtn.setOnClickListener(x -> { haptic(x); onEnter(); });

        v.findViewById(R.id.space).setOnClickListener(x -> { haptic(x); commit(" "); });

        for (TextView t : punctKeys) {
            t.setOnClickListener(x -> { haptic(x); commit(((TextView) x).getText().toString()); });
        }

        updateLangUi();
        updateMicUi();
        return v;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        updateEnterIcon(info);
        if (!listening) setIdleStatus();
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        cancelRecognition();
        super.onFinishInputView(finishingInput);
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
        super.onDestroy();
    }

    // ---------------------------------------------------------------- speech

    private boolean hasMicPermission() {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void onMicClick() {
        if (!hasMicPermission()) {
            openSetup();
            return;
        }
        if (listening) {
            continuous = false;
            if (recognizer != null) recognizer.stopListening();
            setStatus(R.string.status_processing);
        } else {
            continuous = false;
            startListening();
        }
    }

    private void startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            setStatus(R.string.err_no_service);
            continuous = false;
            return;
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
            recognizer.setRecognitionListener(this);
        }
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
        i.putExtra(RecognizerIntent.EXTRA_ONLY_RETURN_LANGUAGE_PREFERENCE, true);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());

        cancelledByUs = false;
        hasComposing = false;
        listening = true;
        updateMicUi();
        setStatus(R.string.status_starting);
        try {
            recognizer.startListening(i);
        } catch (Exception e) {
            listening = false;
            continuous = false;
            updateMicUi();
            setStatus(R.string.err_generic);
        }
    }

    private void cancelRecognition() {
        handler.removeCallbacks(restartRunnable);
        continuous = false;
        if (listening && recognizer != null) {
            cancelledByUs = true;
            recognizer.cancel();
        }
        listening = false;
        finishComposing();
        updateMicUi();
    }

    private final Runnable restartRunnable = () -> {
        if (continuous && !listening) startListening();
    };

    @Override
    public void onReadyForSpeech(Bundle params) {
        setStatus(continuous ? R.string.status_listening_cont : R.string.status_listening);
    }

    @Override
    public void onBeginningOfSpeech() { }

    @Override
    public void onRmsChanged(float rmsdB) {
        if (micRing == null || !listening) return;
        float level = Math.max(0f, Math.min(rmsdB, 10f)) / 10f;
        float s = 1f + level * 0.45f;
        micRing.animate().scaleX(s).scaleY(s).setDuration(90).start();
    }

    @Override
    public void onBufferReceived(byte[] buffer) { }

    @Override
    public void onEndOfSpeech() {
        setStatus(R.string.status_processing);
    }

    @Override
    public void onPartialResults(Bundle partialResults) {
        String t = firstResult(partialResults);
        if (TextUtils.isEmpty(t)) return;
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        if (!hasComposing) {
            composingPrefix = spacePrefix();
            hasComposing = true;
        }
        ic.setComposingText(composingPrefix + t, 1);
    }

    @Override
    public void onResults(Bundle results) {
        String t = firstResult(results);
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) {
            if (!TextUtils.isEmpty(t)) {
                String prefix = hasComposing ? composingPrefix : spacePrefix();
                ic.commitText(prefix + t, 1);
            } else if (hasComposing) {
                ic.finishComposingText();
            }
        }
        hasComposing = false;
        listening = false;
        updateMicUi();

        if (continuous) {
            handler.postDelayed(restartRunnable, 250);
        } else {
            setStatus(TextUtils.isEmpty(t) ? R.string.err_no_match : R.string.status_done);
        }
    }

    @Override
    public void onError(int error) {
        finishComposing();
        listening = false;
        updateMicUi();

        if (cancelledByUs) { // we cancelled on purpose; not a real error
            cancelledByUs = false;
            return;
        }
        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
            if (recognizer != null) { recognizer.destroy(); recognizer = null; }
        }
        boolean silence = error == SpeechRecognizer.ERROR_NO_MATCH
                || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT;
        if (continuous && silence) {
            handler.postDelayed(restartRunnable, 250);
            return;
        }
        continuous = false;
        setStatus(errorMessage(error));
    }

    @Override
    public void onEvent(int eventType, Bundle params) { }

    private int errorMessage(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_SERVER:
            case 11: // ERROR_SERVER_DISCONNECTED (Android 12+)
                return R.string.err_network;
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return R.string.err_no_match;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return R.string.err_permission;
            case SpeechRecognizer.ERROR_AUDIO:
                return R.string.err_audio;
            case 12: // ERROR_LANGUAGE_NOT_SUPPORTED
            case 13: // ERROR_LANGUAGE_UNAVAILABLE
                return R.string.err_language;
            default:
                return R.string.err_generic;
        }
    }

    private static String firstResult(Bundle b) {
        if (b == null) return null;
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty()) return null;
        String s = list.get(0);
        return s == null ? null : s.trim();
    }

    // ---------------------------------------------------------------- typing

    /** Adds a space before new text unless the cursor is at the start or after whitespace. */
    private String spacePrefix() {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return "";
        CharSequence before = ic.getTextBeforeCursor(1, 0);
        if (before == null || before.length() == 0) return "";
        return Character.isWhitespace(before.charAt(0)) ? "" : " ";
    }

    private void finishComposing() {
        if (hasComposing) {
            InputConnection ic = getCurrentInputConnection();
            if (ic != null) ic.finishComposingText();
            hasComposing = false;
        }
    }

    private void commit(String text) {
        finishComposing();
        InputConnection ic = getCurrentInputConnection();
        if (ic != null) ic.commitText(text, 1);
    }

    private void deleteOne() {
        finishComposing();
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        CharSequence sel = ic.getSelectedText(0);
        if (!TextUtils.isEmpty(sel)) {
            ic.commitText("", 1);
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL);
        }
    }

    private final Runnable repeatDelete = new Runnable() {
        @Override
        public void run() {
            deleteOne();
            handler.postDelayed(this, 55);
        }
    };

    private boolean onBackspaceTouch(View v, MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                v.setPressed(true);
                haptic(v);
                deleteOne();
                handler.postDelayed(repeatDelete, 400);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                v.setPressed(false);
                handler.removeCallbacks(repeatDelete);
                return true;
        }
        return false;
    }

    private void onEnter() {
        finishComposing();
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;
        EditorInfo ei = getCurrentInputEditorInfo();
        int action = ei == null ? EditorInfo.IME_ACTION_NONE : (ei.imeOptions & EditorInfo.IME_MASK_ACTION);
        boolean noEnterAction = ei != null && (ei.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
        if (!noEnterAction && action != EditorInfo.IME_ACTION_NONE
                && action != EditorInfo.IME_ACTION_UNSPECIFIED) {
            ic.performEditorAction(action);
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER);
        }
    }

    // ---------------------------------------------------------------- keyboard switching

    private void switchToPreviousKeyboard() {
        cancelRecognition();
        boolean ok = false;
        if (Build.VERSION.SDK_INT >= 28) {
            ok = switchToPreviousInputMethod();
        } else {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            IBinder token = getWindow() != null && getWindow().getWindow() != null
                    ? getWindow().getWindow().getAttributes().token : null;
            if (imm != null && token != null) {
                //noinspection deprecation
                ok = imm.switchToLastInputMethod(token);
            }
        }
        if (!ok) showPicker();
    }

    private void showPicker() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.showInputMethodPicker();
    }

    private void openSetup() {
        Intent i = new Intent(this, MainActivity.class);
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    // ---------------------------------------------------------------- UI helpers

    private void haptic(View v) {
        v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP);
    }

    private void setStatus(int resId) {
        if (status != null) status.setText(resId);
    }

    private void setIdleStatus() {
        setStatus(hasMicPermission() ? R.string.status_idle : R.string.status_need_permission);
    }

    private void updateMicUi() {
        if (mic == null) return;
        mic.setImageResource(listening ? R.drawable.ic_stop : R.drawable.ic_mic);
        mic.setBackgroundResource(listening ? R.drawable.mic_bg_active : R.drawable.mic_bg);
        micRing.setVisibility(listening ? View.VISIBLE : View.INVISIBLE);
        if (!listening) {
            micRing.animate().cancel();
            micRing.setScaleX(1f);
            micRing.setScaleY(1f);
        }
    }

    private void updateLangUi() {
        if (langBtn == null) return;
        boolean fa = LANG_FA.equals(lang);
        langBtn.setText(fa ? "فا" : "EN");
        String[] p = fa ? new String[]{"،", ".", "؟", "!"} : new String[]{",", ".", "?", "!"};
        for (int i = 0; i < punctKeys.length; i++) punctKeys[i].setText(p[i]);
    }

    private void updateEnterIcon(EditorInfo info) {
        if (enterBtn == null || info == null) return;
        int action = info.imeOptions & EditorInfo.IME_MASK_ACTION;
        boolean noEnter = (info.imeOptions & EditorInfo.IME_FLAG_NO_ENTER_ACTION) != 0;
        enterBtn.setImageResource(!noEnter && action == EditorInfo.IME_ACTION_SEND
                ? R.drawable.ic_send : R.drawable.ic_enter);
    }
}
