package com.niceplayer.app;

import android.content.Context;
import android.media.AudioFormat;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.net.Uri;
import android.util.Log;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/** Creates a waveform even when Android lacks a decoder for VLC-playable audio. */
final class AudioWaveformExtractor {
    private static final String TAG = "NicePlayerWaveform";
    interface Callback { void complete(float[] levels); void failed(); }

    static void extract(Context context, Uri uri, int buckets, Callback callback) {
        if (uri == null || buckets < 1) { callback.failed(); return; }
        try {
            callback.complete(decodePcm(context, uri, buckets));
        } catch (Exception decodeError) {
            Log.w(TAG, "PCM waveform unavailable; using encoded-audio fallback", decodeError);
            try { callback.complete(readEncodedEnvelope(context, uri, buckets)); }
            catch (Exception packetError) {
                // Some containers are playable by VLC but not understood by MediaExtractor.
                // A final streaming envelope keeps the timeline usable without loading the
                // whole movie into memory.
                Log.w(TAG, "Audio packets unavailable; using container fallback", packetError);
                try { callback.complete(readContainerEnvelope(context, uri, buckets)); }
                catch (Exception fallbackError) {
                    Log.e(TAG, "Waveform extraction failed for " + uri, fallbackError);
                    callback.failed();
                }
            }
        }
    }

    private static float[] decodePcm(Context context, Uri uri, int buckets) throws Exception {
        MediaExtractor extractor = new MediaExtractor(); MediaCodec codec = null;
        try {
            Track track = selectAudioTrack(extractor, context, uri);
            codec = MediaCodec.createDecoderByType(track.mime);
            codec.configure(track.format, null, null, 0); codec.start();
            float[] peaks = new float[buckets]; boolean inputDone = false, outputDone = false;
            int pcmEncoding = AudioFormat.ENCODING_PCM_16BIT;
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (!outputDone) {
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
                        peaks[bucket] = Math.max(peaks[bucket], peak(pcm, pcmEncoding));
                    }
                    outputDone = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    codec.releaseOutputBuffer(index, false);
                }
            }
            return normalize(peaks);
        } finally {
            extractor.release();
            if (codec != null) { try { codec.stop(); } catch (Exception ignored) {} try { codec.release(); } catch (Exception ignored) {} }
        }
    }

    private static float[] readEncodedEnvelope(Context context, Uri uri, int buckets) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        try {
            Track track = selectAudioTrack(extractor, context, uri); float[] peaks = new float[buckets];
            ByteBuffer packet = ByteBuffer.allocateDirect(256 * 1024);
            while (true) {
                packet.clear(); int size = extractor.readSampleData(packet, 0); if (size < 0) break;
                int limit = Math.min(size, packet.capacity()), step = Math.max(1, limit / 2048), count = 0; long sum = 0;
                for (int i = 0; i < limit; i += step) { sum += Math.abs((int)packet.get(i)); count++; }
                float level = count == 0 ? 0 : sum / (count * 128f);
                int bucket = bucket(Math.max(0, extractor.getSampleTime()), track.durationUs, buckets);
                peaks[bucket] = Math.max(peaks[bucket], level);
                if (!extractor.advance()) break;
            }
            return normalize(peaks);
        } finally { extractor.release(); }
    }

    private static float[] readContainerEnvelope(Context context, Uri uri, int buckets) throws Exception {
        float[] peaks = new float[buckets]; byte[] buffer = new byte[64 * 1024]; int bucket = 0;
        try (InputStream input = context.getContentResolver().openInputStream(uri)) {
            if (input == null) throw new IllegalStateException("Cannot open media stream");
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read == 0) continue;
                long sum = 0; int step = Math.max(1, read / 1024), count = 0;
                for (int i = 0; i < read; i += step) { sum += Math.abs((int)buffer[i]); count++; }
                peaks[bucket % buckets] = Math.max(peaks[bucket % buckets], count == 0 ? 0 : sum / (count * 128f));
                bucket++;
            }
        }
        if (bucket == 0) throw new IllegalStateException("Empty media stream");
        return normalize(peaks);
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

    private static float peak(ByteBuffer pcm, int encoding) {
        float value = 0;
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) while (pcm.remaining() >= 4) value = Math.max(value, Math.abs(pcm.getFloat()));
        else if (encoding == AudioFormat.ENCODING_PCM_8BIT) while (pcm.hasRemaining()) value = Math.max(value, Math.abs((pcm.get() & 0xff) - 128) / 128f);
        else while (pcm.remaining() >= 2) value = Math.max(value, Math.abs(pcm.getShort()) / 32768f);
        return value;
    }

    private static int bucket(long timeUs, long durationUs, int buckets) { return (int)Math.min(buckets - 1, Math.max(0, timeUs * (long)buckets / Math.max(1, durationUs))); }
    private static float[] normalize(float[] peaks) { float max = .001f; for (float v : peaks) max = Math.max(max, v); for (int i=0;i<peaks.length;i++) peaks[i]=(float)Math.sqrt(peaks[i]/max); return peaks; }
    private static final class Track {
        final MediaFormat format; final String mime; final long durationUs;
        Track(MediaFormat format, String mime, long durationUs) { this.format=format; this.mime=mime; this.durationUs=durationUs; }
    }
}
