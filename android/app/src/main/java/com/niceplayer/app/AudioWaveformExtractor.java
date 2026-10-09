package com.niceplayer.app;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.os.SystemClock;
import android.util.Log;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Creates a waveform even when Android lacks a decoder for VLC-playable audio. */
final class AudioWaveformExtractor {
    private static final String TAG = "NicePlayerWaveform";
    static final class Result {
        final float[] levels;
        final boolean[] speech;
        Result(float[] levels, boolean[] speech) { this.levels = levels; this.speech = speech; }
    }
    interface Callback {
        void progress(int percent, long remainingMs);
        void complete(Result result);
        void failed();
    }

    static void extract(Context context, Uri uri, int buckets, Callback callback) {
        if (uri == null || buckets < 1) { callback.failed(); return; }
        Progress progress = new Progress(callback);
        try {
            callback.complete(decodePcm(context, uri, buckets, progress));
            progress.complete();
            return;
        } catch (Exception decodeError) {
            if (Thread.currentThread().isInterrupted()) return;
            Log.w(TAG, "PCM waveform unavailable; using encoded-audio fallback", decodeError);
            progress.beginStage();
            try { callback.complete(envelopeOnly(readEncodedEnvelope(context, uri, buckets, progress))); progress.complete(); return; }
            catch (Exception packetError) {
                if (Thread.currentThread().isInterrupted()) return;
                // Some containers are playable by VLC but not understood by MediaExtractor.
                // A final streaming envelope keeps the timeline usable without loading the
                // whole movie into memory.
                Log.w(TAG, "Audio packets unavailable; using container fallback", packetError);
                progress.beginStage();
                try { callback.complete(envelopeOnly(readContainerEnvelope(context, uri, buckets, progress))); progress.complete(); return; }
                catch (Exception fallbackError) {
                    if (Thread.currentThread().isInterrupted()) return;
                    Log.e(TAG, "Waveform extraction failed for " + uri, fallbackError);
                    callback.failed();
                }
            }
        }
    }

    private static Result decodePcm(Context context, Uri uri, int buckets, Progress progress) throws Exception {
        MediaExtractor extractor = new MediaExtractor(); MediaCodec codec = null;
        try {
            Track track = selectAudioTrack(extractor, context, uri);
            codec = MediaCodec.createDecoderByType(track.mime);
            codec.configure(track.format, null, null, 0); codec.start();
            float[] peaks = new float[buckets], rms = new float[buckets], zeroCrossing = new float[buckets];
            int[] frames = new int[buckets]; boolean inputDone = false, outputDone = false;
            int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (!outputDone) {
                checkCancelled();
                if (!inputDone) {
                    int index = codec.dequeueInputBuffer(10_000);
                    if (index >= 0) {
                        ByteBuffer input = codec.getInputBuffer(index);
                        if (input == null) throw new IllegalStateException("Missing codec input buffer");
                        input.clear(); int size = extractor.readSampleData(input, 0);
                        if (size < 0) { codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true; }
                        else { codec.queueInputBuffer(index, 0, size, Math.max(0, extractor.getSampleTime()), 0); extractor.advance(); }
                    }
                }
                int index = codec.dequeueOutputBuffer(info, 10_000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat output = codec.getOutputFormat();
                    if (android.os.Build.VERSION.SDK_INT >= 24 && output.containsKey(MediaFormat.KEY_PCM_ENCODING)) pcmEncoding = output.getInteger(MediaFormat.KEY_PCM_ENCODING);
                } else if (index >= 0) {
                    ByteBuffer pcm = codec.getOutputBuffer(index);
                    if (pcm != null && info.size > 0) {
                        pcm.position(info.offset); pcm.limit(info.offset + info.size); pcm.order(ByteOrder.LITTLE_ENDIAN);
                        int bucket = bucket(info.presentationTimeUs, track.durationUs, buckets);
                        AudioStats stats = audioStats(pcm, pcmEncoding);
                        peaks[bucket] = Math.max(peaks[bucket], stats.peak);
                        rms[bucket] = Math.max(rms[bucket], stats.rms);
                        zeroCrossing[bucket] += stats.zeroCrossingRate;
                        frames[bucket]++;
                    }
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    progress.report(info.presentationTimeUs, track.durationUs);
                    codec.releaseOutputBuffer(index, false);
                }
            }
            float[] normalized = normalize(peaks);
            return new Result(normalized, detectSpeech(normalized, rms, zeroCrossing, frames));
        } finally {
            extractor.release();
            if (codec != null) { try { codec.stop(); } catch (Exception ignored) {} try { codec.release(); } catch (Exception ignored) {} }
        }
    }

    private static float[] readEncodedEnvelope(Context context, Uri uri, int buckets, Progress progress) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            Track track = selectAudioTrack(extractor, context, uri); float[] peaks = new float[buckets];
            ByteBuffer packet = ByteBuffer.allocateDirect(256 * 1024);
            while (true) {
                checkCancelled();
                packet.clear(); int size = extractor.readSampleData(packet, 0); if (size < 0) break;
                int limit = Math.min(size, packet.capacity()), step = Math.max(1, limit / 2048), count = 0; long sum = 0;
                for (int i = 0; i < limit; i += step) { sum += Math.abs((int)packet.get(i)); count++; }
                float level = count == 0 ? 0 : sum / (count * 128f);
                int bucket = bucket(Math.max(0, extractor.getSampleTime()), track.durationUs, buckets);
                peaks[bucket] = Math.max(peaks[bucket], level);
                progress.report(Math.max(0, extractor.getSampleTime()), track.durationUs);
                if (!extractor.advance()) break;
            }
            return normalize(peaks);
        } finally { extractor.release(); }
    }

    private static float[] readContainerEnvelope(Context context, Uri uri, int buckets, Progress progress) throws Exception {
        float[] peaks = new float[buckets]; byte[] buffer = new byte[64 * 1024];
        java.util.ArrayList<Float> unknownSizeLevels = null;
        long totalBytes = -1;
        try (android.database.Cursor cursor = context.getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.SIZE}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst() && !cursor.isNull(0)) totalBytes = cursor.getLong(0);
        } catch (Exception ignored) {}
        if (totalBytes <= 0) {
            try (android.content.res.AssetFileDescriptor descriptor = context.getContentResolver().openAssetFileDescriptor(uri, "r")) {
                if (descriptor != null && descriptor.getLength() > 0) totalBytes = descriptor.getLength();
            } catch (Exception ignored) {}
        }
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalStateException("Cannot open media stream");
            int read; long bytesRead = 0;
            while ((read = input.read(buffer)) >= 0) {
                checkCancelled();
                if (read == 0) continue;
                long sum = 0; int step = Math.max(1, read / 1024), count = 0;
                for (int i = 0; i < read; i += step) { sum += Math.abs((int)buffer[i]); count++; }
                float level = count == 0 ? 0 : sum / (count * 128f);
                if (totalBytes > 0) {
                    long sampleOffset = bytesRead + read / 2L;
                    int targetBucket = (int)Math.min(buckets - 1L,
                            sampleOffset * buckets / totalBytes);
                    peaks[targetBucket] = Math.max(peaks[targetBucket], level);
                } else {
                    if (unknownSizeLevels == null) unknownSizeLevels = new java.util.ArrayList<>();
                    unknownSizeLevels.add(level);
                }
                bytesRead += read;
                if (totalBytes > 0) progress.report(bytesRead, totalBytes);
            }
        }
        if (bytesRead == 0) throw new IllegalStateException("Empty media stream");
        if (unknownSizeLevels != null) {
            int samples = unknownSizeLevels.size();
            for (int i = 0; i < samples; i++) {
                int targetBucket = (int)Math.min(buckets - 1L,
                        (2L * i + 1L) * buckets / (2L * samples));
                peaks[targetBucket] = Math.max(peaks[targetBucket], unknownSizeLevels.get(i));
            }
        }
        return normalize(peaks);
    }

    private static void checkCancelled() throws InterruptedException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException("Waveform cancelled");
    }

    private static final class Progress {
        private final Callback callback;
        private long lastUpdate;
        private int lastPercent = -1;
        private long lastRateAt = SystemClock.elapsedRealtime();
        private double lastRateFraction;
        private double fractionPerMs;
        Progress(Callback callback) { this.callback = callback; }
        void beginStage() { lastRateAt=SystemClock.elapsedRealtime();lastUpdate=0;lastPercent=-1;lastRateFraction=0;fractionPerMs=0;callback.progress(0,-1); }
        void report(long position, long duration) { if (duration > 0) report(position / (double) duration); }
        void report(double fraction) {
            if (callback == null) return;
            double safe = Math.max(0, Math.min(.99, fraction));
            int percent = (int) Math.floor(safe * 100);
            long now = SystemClock.elapsedRealtime();
            long rateWindow=Math.max(1,now-lastRateAt);
            if(safe>lastRateFraction){
                double measured=(safe-lastRateFraction)/rateWindow;
                fractionPerMs=fractionPerMs==0?measured:(fractionPerMs*.35)+(measured*.65);
                lastRateFraction=safe;lastRateAt=now;
            }
            if (percent == lastPercent && now - lastUpdate < 400) return;
            long remaining = fractionPerMs > 0 ? (long) ((1.0 - safe) / fractionPerMs) : -1;
            lastPercent = percent; lastUpdate = now;
            callback.progress(percent, remaining);
        }
        void complete() { if (callback != null) callback.progress(100, 0); }
    }

    private static Track selectAudioTrack(MediaExtractor extractor, Context context, Uri uri) throws Exception {
        extractor.setDataSource(context, uri, null);
        for (int i = 0; i < extractor.getTrackCount(); i++) {
            MediaFormat format = extractor.getTrackFormat(i); String mime = format.getString(MediaFormat.KEY_MIME);
            if (mime != null && mime.startsWith("audio/")) {
                extractor.selectTrack(i);
                long duration = format.containsKey(MediaFormat.KEY_DURATION) ? format.getLong(MediaFormat.KEY_DURATION) : 1;
                return new Track(format, mime, Math.max(1, duration));
            }
        }
        throw new IllegalStateException("No audio track");
    }

    private static AudioStats audioStats(ByteBuffer pcm, int encoding) {
        float peak = 0, previous = 0; double sumSquares = 0; int samples = 0, crossings = 0;
        while ((encoding == AudioFormat.ENCODING_PCM_FLOAT && pcm.remaining() >= 4)
                || (encoding == AudioFormat.ENCODING_PCM_8BIT && pcm.hasRemaining())
                || (encoding != AudioFormat.ENCODING_PCM_FLOAT && encoding != AudioFormat.ENCODING_PCM_8BIT && pcm.remaining() >= 2)) {
            float sample;
            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) sample = Math.max(-1f, Math.min(1f, pcm.getFloat()));
            else if (encoding == AudioFormat.ENCODING_PCM_8BIT) sample = ((pcm.get() & 0xff) - 128) / 128f;
            else sample = pcm.getShort() / 32768f;
            peak = Math.max(peak, Math.abs(sample));
            sumSquares += sample * sample;
            if (samples > 0 && ((sample >= 0) != (previous >= 0))) crossings++;
            previous = sample; samples++;
        }
        return new AudioStats(peak, samples == 0 ? 0 : (float)Math.sqrt(sumSquares / samples),
                samples < 2 ? 0 : crossings / (float)(samples - 1));
    }

    /** Lightweight VAD: highlights probable dialogue without running a second player decoder. */
    private static boolean[] detectSpeech(float[] levels, float[] rms, float[] crossings, int[] frames) {
        boolean[] speech = new boolean[levels.length];
        float maxRms = .0001f;
        for (float value : rms) maxRms = Math.max(maxRms, value);
        for (int i = 0; i < speech.length; i++) {
            float zcr = frames[i] == 0 ? 0 : crossings[i] / frames[i];
            float relativeEnergy = rms[i] / maxRms;
            speech[i] = levels[i] > .07f && relativeEnergy > .045f && zcr > .012f && zcr < .42f;
        }
        // Bridge tiny gaps so spoken syllables form readable coloured regions.
        boolean[] smoothed = speech.clone();
        for (int i = 1; i < speech.length - 1; i++)
            if (!speech[i] && speech[i - 1] && speech[i + 1] && levels[i] > .035f) smoothed[i] = true;
        return smoothed;
    }

    private static Result envelopeOnly(float[] levels) { return new Result(levels, new boolean[levels.length]); }

    private static int bucket(long timeUs, long durationUs, int buckets) { return (int)Math.min(buckets - 1, Math.max(0, timeUs * (long)buckets / Math.max(1, durationUs))); }
    private static float[] normalize(float[] peaks) { float max = .001f; for (float v : peaks) max = Math.max(max, v); for (int i=0;i<peaks.length;i++) peaks[i]=(float)Math.sqrt(peaks[i]/max); return peaks; }
    private static final class Track {
        final MediaFormat format; final String mime; final long durationUs;
        Track(MediaFormat format, String mime, long durationUs) { this.format=format; this.mime=mime; this.durationUs=durationUs; }
    }
    private static final class AudioStats {
        final float peak, rms, zeroCrossingRate;
        AudioStats(float peak, float rms, float zeroCrossingRate) { this.peak=peak; this.rms=rms; this.zeroCrossingRate=zeroCrossingRate; }
    }
}
