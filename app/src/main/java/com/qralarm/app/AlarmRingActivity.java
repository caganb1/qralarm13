package com.qralarm.app;

import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.google.zxing.integration.android.IntentIntegrator;
import com.google.zxing.integration.android.IntentResult;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AlarmRingActivity extends AppCompatActivity {

    public static final String EXTRA_ALARM_ID = "extra_alarm_id";

    private int alarmId = -1;
    private Alarm currentAlarm;
    private TextView tvTime, tvLabel, tvInstruction, tvQrLabel;
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // Show over lock screen / turn on screen
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true);
            setTurnScreenOn(true);
        } else {
            getWindow().addFlags(
                    WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED |
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON |
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            );
        }

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_alarm_ring);

        tvTime = findViewById(R.id.tv_ring_time);
        tvLabel = findViewById(R.id.tv_ring_label);
        tvInstruction = findViewById(R.id.tv_ring_instruction);
        tvQrLabel = findViewById(R.id.tv_ring_qr_label);

        alarmId = getIntent().getIntExtra(EXTRA_ALARM_ID, -1);
        if (alarmId == -1) {
            finish();
            return;
        }

        loadAlarm();

        findViewById(R.id.btn_scan_qr).setOnClickListener(v -> startQrScan());
    }

    private void loadAlarm() {
        executor.execute(() -> {
            currentAlarm = AlarmDatabase.getInstance(this).alarmDao().getAlarmById(alarmId);
            runOnUiThread(() -> {
                if (currentAlarm == null) {
                    finish();
                    return;
                }
                tvTime.setText(currentAlarm.getTimeString());
                tvLabel.setText(currentAlarm.label != null ? currentAlarm.label : "");

                if (currentAlarm.hasQrCode()) {
                    String qrLabel = currentAlarm.qrCodeLabel != null
                            ? currentAlarm.qrCodeLabel
                            : getString(R.string.your_qr_code);
                    tvInstruction.setText(R.string.scan_to_dismiss);
                    tvQrLabel.setText(getString(R.string.required_code, qrLabel));
                    tvQrLabel.setVisibility(View.VISIBLE);
                } else {
                    // No QR configured — can dismiss freely (shouldn't happen in normal use)
                    tvInstruction.setText(R.string.no_qr_configured);
                    tvQrLabel.setVisibility(View.GONE);
                    // Show a dismiss button instead
                    View btnDismiss = findViewById(R.id.btn_dismiss_no_qr);
                    if (btnDismiss != null) {
                        btnDismiss.setVisibility(View.VISIBLE);
                        btnDismiss.setOnClickListener(v -> dismissAlarm());
                    }
                }
            });
        });
    }

    private void startQrScan() {
        IntentIntegrator integrator = new IntentIntegrator(this);
        integrator.setDesiredBarcodeFormats(IntentIntegrator.ALL_CODE_TYPES);
        integrator.setPrompt(getString(R.string.scan_prompt));
        integrator.setCameraId(0);
        integrator.setBeepEnabled(false);
        integrator.setOrientationLocked(true);
        integrator.initiateScan();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        IntentResult result = IntentIntegrator.parseActivityResult(requestCode, resultCode, data);
        if (result != null) {
            String scannedValue = result.getContents();
            if (scannedValue == null) {
                Toast.makeText(this, R.string.scan_cancelled, Toast.LENGTH_SHORT).show();
                return;
            }
            validateScan(scannedValue);
        } else {
            super.onActivityResult(requestCode, resultCode, data);
        }
    }

    private void validateScan(String scannedValue) {
        if (currentAlarm == null) return;

        if (!currentAlarm.hasQrCode()) {
            dismissAlarm();
            return;
        }

        if (scannedValue.trim().equals(currentAlarm.qrCodeValue.trim())) {
            dismissAlarm();
        } else {
            Toast.makeText(this, R.string.wrong_code, Toast.LENGTH_LONG).show();
        }
    }

    private void dismissAlarm() {
        // Stop the alarm sound
        AlarmService.stop(this);

        // If this was a one-time alarm, disable it
        if (currentAlarm != null && !currentAlarm.repeats()) {
            executor.execute(() -> {
                AlarmDatabase.getInstance(this).alarmDao().setEnabled(alarmId, false);
            });
        } else if (currentAlarm != null && currentAlarm.repeats()) {
            // Reschedule for the next week occurrence
            executor.execute(() -> {
                AlarmScheduler.schedule(this, currentAlarm);
            });
        }

        finish();
    }

    // Block back button — user MUST scan QR
    @Override
    public void onBackPressed() {
        // Do nothing — cannot dismiss without QR
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_BACK) {
            return true; // block
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }
}
