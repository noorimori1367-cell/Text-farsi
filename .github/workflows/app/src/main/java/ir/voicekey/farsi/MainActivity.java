package ir.voicekey.farsi;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.speech.SpeechRecognizer;
import android.view.View;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.TextView;

/** Setup screen: microphone permission → enable keyboard → select keyboard → test. */
public class MainActivity extends Activity {

    private static final int REQ_MIC = 1;

    private Button btnPerm, btnEnable, btnChoose;
    private TextView st1, st2, st3, warning;
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        prefs = getSharedPreferences("settings", MODE_PRIVATE);

        btnPerm = findViewById(R.id.btn_perm);
        btnEnable = findViewById(R.id.btn_enable);
        btnChoose = findViewById(R.id.btn_choose);
        st1 = findViewById(R.id.st1);
        st2 = findViewById(R.id.st2);
        st3 = findViewById(R.id.st3);
        warning = findViewById(R.id.warning);

        btnPerm.setOnClickListener(v -> requestMic());
        btnEnable.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)));
        btnChoose.setOnClickListener(v -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
            if (imm != null) imm.showInputMethodPicker();
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) refresh(); // the keyboard picker is a dialog, so onResume isn't called
    }

    private boolean hasMic() {
        return Build.VERSION.SDK_INT < 23
                || checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    private void requestMic() {
        if (Build.VERSION.SDK_INT < 23) return;
        boolean askedBefore = prefs.getBoolean("asked_mic", false);
        if (askedBefore && !shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
            // permanently denied → send user to the app's settings page
            Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", getPackageName(), null));
            startActivity(i);
            return;
        }
        prefs.edit().putBoolean("asked_mic", true).apply();
        requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        refresh();
    }

    private boolean isEnabled() {
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm == null) return false;
        for (InputMethodInfo info : imm.getEnabledInputMethodList()) {
            if (getPackageName().equals(info.getPackageName())) return true;
        }
        return false;
    }

    private boolean isSelected() {
        String id = Settings.Secure.getString(getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        return id != null && id.startsWith(getPackageName() + "/");
    }

    private void refresh() {
        boolean mic = hasMic();
        boolean enabled = isEnabled();
        boolean selected = isSelected();

        setStep(st1, btnPerm, mic, true);
        setStep(st2, btnEnable, enabled, true);
        setStep(st3, btnChoose, selected, enabled);

        warning.setVisibility(SpeechRecognizer.isRecognitionAvailable(this) ? View.GONE : View.VISIBLE);
    }

    @SuppressWarnings("deprecation")
    private void setStep(TextView st, Button btn, boolean done, boolean available) {
        if (done) {
            st.setText(R.string.step_done);
            st.setTextColor(getResources().getColor(R.color.ok));
            btn.setVisibility(View.GONE);
        } else {
            st.setText(available ? R.string.step_todo : R.string.step_wait);
            st.setTextColor(getResources().getColor(R.color.text_secondary));
            btn.setVisibility(View.VISIBLE);
            btn.setEnabled(available);
        }
    }
}
