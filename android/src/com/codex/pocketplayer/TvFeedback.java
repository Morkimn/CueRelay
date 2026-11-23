package com.codex.pocketplayer;

import android.content.Context;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.provider.Settings;

final class TvFeedback {
    static void pulse(Context context) {
        Vibrator vibrator = context.getSystemService(Vibrator.class);
        if (vibrator == null || !vibrator.hasVibrator() ||
            Settings.System.getInt(context.getContentResolver(), Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) == 0) return;
        try {
            vibrator.vibrate(VibrationEffect.createOneShot(14, vibrator.hasAmplitudeControl() ? 68 : VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (RuntimeException ignored) { }
    }

    private TvFeedback() { }
}
