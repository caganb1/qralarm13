package com.qralarm.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;
import android.provider.Settings;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class AlarmService extends Service {

    private static final String TAG = "AlarmService";
    public static final String EXTRA_ALARM_ID = "extra_alarm_id";
    public static final String CHANNEL_ID = "alarm_channel";
    public static final int NOTIFICATION_ID = 1001;

    private MediaPlayer mediaPlayer;
    private Vibrator vibrator;
    private PowerManager.WakeLock wakeLock;
    private int currentAlarmId = -1;

    @Override
    public void onCreate() {
        super.onCreate();
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        currentAlarmId = intent.getIntExtra(EXTRA_ALARM_ID, -1);

        // Acquire wake lock to keep CPU awake
        PowerManager pm = (PowerManager) getSystemService(POWER_SERVICE);
        if (pm != null) {
            wakeLock = pm.newWakeLock(
                    PowerManager.PARTIAL_WAKE_LOCK,
                    "QRAlarm::AlarmWakeLock"
            );
            wakeLock.acquire(10 * 60 * 1000L); // max 10 min
        }

        // Build foreground notification
        Intent ringIntent = new Intent(this, AlarmRingActivity.class);
        ringIntent.putExtra(AlarmRingActivity.EXTRA_ALARM_ID, currentAlarmId);
        ringIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP);

        int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) piFlags |= PendingIntent.FLAG_IMMUTABLE;

        PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, ringIntent, piFlags);

        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_alarm)
                .setContentTitle(getString(R.string.alarm_ringing))
                .setContentText(getString(R.string.scan_qr_to_dismiss))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setAutoCancel(false)
                .build();

        startForeground(NOTIFICATION_ID, notification);

        // Load alarm data and start playing
        ExecutorService executor = Executors.newSingleThreadExecutor();
        executor.execute(() -> {
            AlarmDatabase db = AlarmDatabase.getInstance(this);
            Alarm alarm = db.alarmDao().getAlarmById(currentAlarmId);
            if (alarm != null) {
                playAlarm(alarm);
                if (alarm.vibrate) startVibration();
            }
        });

        return START_STICKY;
    }

    private void playAlarm(Alarm alarm) {
        try {
            if (mediaPlayer != null) {
                mediaPlayer.release();
                mediaPlayer = null;
            }

            mediaPlayer = new MediaPlayer();
            mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ALARM)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());

            Uri soundUri = null;
            if (alarm.soundUri != null && !alarm.soundUri.isEmpty()) {
                soundUri = Uri.parse(alarm.soundUri);
            }
            if (soundUri == null) {
                soundUri = Settings.System.DEFAULT_ALARM_ALERT_URI;
            }

            mediaPlayer.setDataSource(this, soundUri);
            mediaPlayer.setLooping(true);
            mediaPlayer.setVolume(1.0f, 1.0f);
            mediaPlayer.prepare();
            mediaPlayer.start();
        } catch (IOException e) {
            Log.e(TAG, "Error playing alarm sound", e);
            // Fallback: try default alarm sound
            try {
                if (mediaPlayer != null) mediaPlayer.release();
                mediaPlayer = new MediaPlayer();
                mediaPlayer.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build());
                mediaPlayer.setDataSource(this, Settings.System.DEFAULT_ALARM_ALERT_URI);
                mediaPlayer.setLooping(true);
                mediaPlayer.prepare();
                mediaPlayer.start();
            } catch (IOException ex) {
                Log.e(TAG, "Fallback alarm also failed", ex);
            }
        }
    }

    private void startVibration() {
        long[] pattern = {0, 500, 500};
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager vm = (VibratorManager) getSystemService(VIBRATOR_MANAGER_SERVICE);
            if (vm != null) {
                vibrator = vm.getDefaultVibrator();
            }
        } else {
            vibrator = (Vibrator) getSystemService(VIBRATOR_SERVICE);
        }
        if (vibrator == null) return;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, 0));
        } else {
            vibrator.vibrate(pattern, 0);
        }
    }

    public static void stop(android.content.Context context) {
        Intent intent = new Intent(context, AlarmService.class);
        context.stopService(intent);
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (mediaPlayer != null) {
            mediaPlayer.stop();
            mediaPlayer.release();
            mediaPlayer = null;
        }
        if (vibrator != null) {
            vibrator.cancel();
        }
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.alarm_channel_name),
                    NotificationManager.IMPORTANCE_HIGH
            );
            channel.setDescription(getString(R.string.alarm_channel_desc));
            channel.setBypassDnd(true);
            channel.enableVibration(true);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(channel);
        }
    }
}
