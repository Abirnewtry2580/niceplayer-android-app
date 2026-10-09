package com.niceplayer.app;

import android.content.Context;
import android.net.Uri;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/** Persistent app-private cache for completed waveform analyses. */
final class WaveformCache {
    private static final int MAGIC = 0x4E505746;
    private static final int VERSION = 1;
    private static final int MAX_BUCKETS = 72_000;

    private WaveformCache() {}

    private static File directory(Context context) {
        return new File(context.getFilesDir(), "waveforms");
    }

    private static File file(Context context, Uri uri, boolean createDirectory) {
        if (uri == null) return null;
        File dir = directory(context);
        if (createDirectory && !dir.exists() && !dir.mkdirs()) return null;
        String key = sha256(uri.toString());
        return key == null ? null : new File(dir, key + ".npwf");
    }

    static long size(Context context, Uri uri) {
        File file = file(context, uri, false);
        return file != null && file.isFile() ? file.length() : 0L;
    }

    static long delete(Context context, Uri uri) {
        File file = file(context, uri, false);
        if (file == null || !file.isFile()) return 0L;
        long bytes = file.length();
        return file.delete() ? bytes : 0L;
    }

    static AudioWaveformExtractor.Result read(Context context, Uri uri, int expectedBuckets, long expectedDurationMs) {
        File file = file(context, uri, false);
        if (file == null || !file.isFile() || expectedBuckets < 1 || expectedBuckets > MAX_BUCKETS) return null;
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            if (input.readInt() != MAGIC || input.readInt() != VERSION) return null;
            if (input.readLong() != expectedDurationMs) return null;
            int count = input.readInt();
            if (count != expectedBuckets || count < 1 || count > MAX_BUCKETS) return null;
            float[] levels = new float[count];
            boolean[] speech = new boolean[count];
            for (int i = 0; i < count; i++) levels[i] = input.readFloat();
            for (int i = 0; i < count; i++) speech[i] = input.readBoolean();
            if (input.read() != -1) return null;
            return new AudioWaveformExtractor.Result(levels, speech);
        } catch (Exception error) {
            return null;
        }
    }

    static void write(Context context, Uri uri, long durationMs, AudioWaveformExtractor.Result result) {
        if (result == null || result.levels == null || result.speech == null
                || result.levels.length == 0 || result.levels.length != result.speech.length
                || result.levels.length > MAX_BUCKETS) return;
        File target = file(context, uri, true);
        if (target == null) return;
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(temporary)))) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeLong(durationMs);
            output.writeInt(result.levels.length);
            for (float level : result.levels) output.writeFloat(level);
            for (boolean spoken : result.speech) output.writeBoolean(spoken);
        } catch (Exception error) {
            temporary.delete();
            return;
        }
        if (target.exists() && !target.delete()) {
            temporary.delete();
            return;
        }
        if (!temporary.renameTo(target)) temporary.delete();
    }

    private static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder result = new StringBuilder(digest.length * 2);
            for (byte part : digest) result.append(String.format(Locale.US, "%02x", part & 0xff));
            return result.toString();
        } catch (Exception error) {
            return null;
        }
    }
}
