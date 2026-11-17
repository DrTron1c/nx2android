package dev.nxnative.android;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.Spatializer;
import android.media.audiofx.AudioEffect;
import java.util.List;
import java.util.Locale;

/**
 * nx2android: surround sound. A game that plays six channels (Pokopia does once the host sets its sound engine to 5.1: dm/host/android/pkp_sound.c) has them sent to
 * Android as a 5.1 stream (hle_audio.c, DM_AUDIO_SURROUND=1) instead of folded to stereo; Android's mixer, and an effect such as the tablet's Dolby Atmos, then place them
 * on the device's speakers. An app cannot drive a speaker of its own, so what the speakers get is the tablet's to decide: status() says what the tablet reports.
 * On by default; it takes effect when the game next starts (the engine reads its channel count once, at boot).
 */
final class Surround {
    static final String PREF = "audio_surround";

    static boolean on(Context c) { return Prefs.of(c).getBoolean(PREF, true); }
    static void on(Context c, boolean v) { Prefs.of(c).edit().putBoolean(PREF, v).apply(); }

    /** The host's DM_AUDIO_SURROUND for a start */
    static void env(Context c, List<String> env) { if (on(c)) env.add("DM_AUDIO_SURROUND=1"); }

    /** What the game and the tablet do with the sound now, for the in-game panel */
    static String status(Context c) {
        StringBuilder b = new StringBuilder();
        int[] h = new int[7];
        if (Native.audioInfo(h)) {
            if (h[0] == 0) b.append("Off this time: the game's sound goes to the tablet as stereo.");
            else {
                if (h[1] >= 0) b.append(h[2] > 2 ? "The game mixes 5.1 (its own setting is " + h[1] + " channels). " : "The game mixes " + h[2] + " channels. ");
                if (h[3] == 6 && h[4] == 6) b.append("Android gets all six channels" + (h[6] == 0 ? "" : " (low latency)") + ".");
                else if (h[3] == 6) b.append("Android would not take 5.1 here: the six channels are folded to stereo.");
                else if (h[3] > 0) b.append("The game's output is " + h[3] + " channels, so there is nothing to spread.");
                else b.append("No sound stream yet.");
            }
        } else b.append("This build's engine does not report its sound.");
        AudioManager am = c.getSystemService(AudioManager.class);
        if (am != null) {
            for (AudioDeviceInfo d : am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)) {
                if (d.getType() != AudioDeviceInfo.TYPE_BUILTIN_SPEAKER) continue;
                int most = 0;
                for (int n : d.getChannelCounts()) most = Math.max(most, n);
                b.append(most > 0 ? String.format(Locale.US, " The speakers take up to %d channels from Android.", most) : " The speakers take any channel count from Android.");
                break;
            }
            b.append(dolby() ? " Dolby audio processing is on this tablet (its Dolby Atmos switch is in the tablet's sound settings)." : "");
            try {
                Spatializer s = am.getSpatializer();
                AudioFormat f = new AudioFormat.Builder().setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48000).setChannelMask(AudioFormat.CHANNEL_OUT_5POINT1).build();
                AudioAttributes a = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_GAME).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build();
                if (s.getImmersiveAudioLevel() != Spatializer.SPATIALIZER_IMMERSIVE_LEVEL_NONE && s.isAvailable())
                    b.append(s.isEnabled() && s.canBeSpatialized(a, f) ? " Android's spatial audio is on for it." : " Android's spatial audio is available but not on for it.");
            } catch (RuntimeException e) { /* no spatializer */ }
        }
        return b.toString().trim();
    }

    /** Whether the system has a Dolby audio effect (Dolby Atmos on Xiaomi tablets) */
    static boolean dolby() {
        try {
            for (AudioEffect.Descriptor d : AudioEffect.queryEffects()) {
                String s = (d.name + " " + d.implementor).toLowerCase(Locale.ROOT);
                if (s.contains("dolby")) return true;
            }
        } catch (RuntimeException e) { /* none listed */ }
        return false;
    }
}
