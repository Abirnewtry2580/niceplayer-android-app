package com.niceplayer.app;

import android.content.ContentValues;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;
import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackParameters;
import androidx.media3.common.Player;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.AspectRatioFrameLayout;
import androidx.media3.ui.PlayerView;
import java.io.OutputStream;

@UnstableApi
public class PlayerActivity extends AppCompatActivity {
    private static final long SEEK_MS = 10_000L;
    private ExoPlayer player;
    private PlayerView playerView;
    private Uri videoUri;
    private long resumePosition;
    private boolean resumePlay = true;
    private int resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT;
    private float gestureStartX;
    private long gestureStartPosition;
    private boolean seekingByGesture;
    private boolean gestureMoved;
    private float gestureStartY;
    private float startBrightness;
    private int startVolume;
    private AudioManager audioManager;
    private long lastTapAt;
    private float lastTapX;

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        videoUri = getIntent().getData();
        if (videoUri == null) { finish(); return; }
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        resumePosition = getPreferences(MODE_PRIVATE).getLong(videoUri.toString(), 0L);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        hideSystemBars();

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        playerView = new PlayerView(this);
        playerView.setBackgroundColor(Color.BLACK);
        playerView.setUseController(true);
        playerView.setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS);
        root.addView(playerView, new FrameLayout.LayoutParams(-1, -1));

        TextView seekHint = button("", 18);
        seekHint.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        seekHint.setBackgroundColor(0xBB111827);
        seekHint.setPadding(dp(18), dp(10), dp(18), dp(10));
        seekHint.setVisibility(View.GONE);
        FrameLayout.LayoutParams hintParams = new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER);
        root.addView(seekHint, hintParams);

        View gestureArea = new View(this);
        gestureArea.setBackgroundColor(Color.TRANSPARENT);
        gestureArea.setContentDescription("Swipe horizontally to seek or vertically for brightness and volume");
        gestureArea.setOnTouchListener((v, event) -> handleSeekGesture(event, seekHint));
        FrameLayout.LayoutParams gestureParams = new FrameLayout.LayoutParams(-1, -1);
        gestureParams.topMargin = dp(64);
        gestureParams.bottomMargin = dp(110);
        root.addView(gestureArea, gestureParams);

        LinearLayout bar = new LinearLayout(this);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(8), dp(6), dp(8), dp(6));
        bar.setBackgroundColor(0x99000000);
        TextView back = button("‹", 34); back.setContentDescription("Back");
        back.setOnClickListener(v -> finish());
        bar.addView(back, new LinearLayout.LayoutParams(dp(52), dp(48)));
        TextView title = button(getIntent().getStringExtra("title"), 15);
        if (title.getText().toString().trim().isEmpty()) title.setText("Video");
        title.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
        bar.addView(title, new LinearLayout.LayoutParams(0, dp(48), 1));
        TextView speed = button("1×", 16); speed.setContentDescription("Playback speed");
        speed.setOnClickListener(v -> showSpeedMenu(speed));
        bar.addView(speed, new LinearLayout.LayoutParams(dp(54), dp(48)));
        TextView aspect = button("FIT", 12); aspect.setContentDescription("Aspect ratio");
        aspect.setOnClickListener(v -> cycleAspect(aspect));
        bar.addView(aspect, new LinearLayout.LayoutParams(dp(58), dp(48)));
        TextView shot = button("▣", 24); shot.setContentDescription("Save screenshot");
        shot.setOnClickListener(v -> captureScreenshot());
        bar.addView(shot, new LinearLayout.LayoutParams(dp(50), dp(48)));
        TextView preview = button("▦", 24); preview.setContentDescription("Create preview sheet");
        preview.setOnClickListener(v -> createPreviewSheet());
        bar.addView(preview, new LinearLayout.LayoutParams(dp(50), dp(48)));
        TextView rotate = button("↻", 25); rotate.setContentDescription("Rotate screen");
        rotate.setOnClickListener(v -> rotateScreen());
        bar.addView(rotate, new LinearLayout.LayoutParams(dp(50), dp(48)));
        root.addView(bar, new FrameLayout.LayoutParams(-1, dp(60), Gravity.TOP));
        setContentView(root);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { finish(); }
        });
    }

    private TextView button(String label, int size) {
        TextView v = new TextView(this); v.setText(label == null ? "" : label);
        v.setTextColor(Color.WHITE); v.setTextSize(size); v.setGravity(Gravity.CENTER);
        v.setBackgroundColor(Color.TRANSPARENT); v.setPadding(dp(5), 0, dp(5), 0); return v;
    }

    @Override protected void onStart() {
        super.onStart();
        if (player == null) {
            player = new ExoPlayer.Builder(this).setSeekBackIncrementMs(SEEK_MS)
                    .setSeekForwardIncrementMs(SEEK_MS).build();
            playerView.setPlayer(player);
            player.setMediaItem(MediaItem.fromUri(videoUri)); player.prepare();
            if (resumePosition > 0) player.seekTo(resumePosition);
            player.setPlayWhenReady(resumePlay);
            player.addListener(new Player.Listener() {
                @Override public void onPlayerError(@NonNull androidx.media3.common.PlaybackException error) {
                    Toast.makeText(PlayerActivity.this, "This video format could not be played", Toast.LENGTH_LONG).show();
                }
            });
        }
    }

    @Override protected void onStop() {
        if (player != null) {
            resumePosition = player.getCurrentPosition(); resumePlay = player.getPlayWhenReady();
            if (resumePosition > 5_000L) {
                getPreferences(MODE_PRIVATE).edit().putLong(videoUri.toString(), resumePosition).apply();
            }
            playerView.setPlayer(null); player.release(); player = null;
        }
        super.onStop();
    }

    private void showSpeedMenu(TextView anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        float[] speeds = {0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f};
        for (int i = 0; i < speeds.length; i++) menu.getMenu().add(0, i, i, speeds[i] + "×");
        menu.setOnMenuItemClickListener(item -> {
            float value = speeds[item.getItemId()];
            if (player != null) player.setPlaybackParameters(new PlaybackParameters(value));
            anchor.setText(value == 1f ? "1×" : value + "×"); return true;
        }); menu.show();
    }

    private void cycleAspect(TextView label) {
        if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_FIT) {
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_ZOOM; label.setText("ZOOM");
        } else if (resizeMode == AspectRatioFrameLayout.RESIZE_MODE_ZOOM) {
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FILL; label.setText("FILL");
        } else { resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT; label.setText("FIT"); }
        playerView.setResizeMode(resizeMode);
    }

    private boolean handleSeekGesture(MotionEvent event, TextView hint) {
        if (player == null) return true;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                gestureStartX = event.getX();
                gestureStartY = event.getY();
                gestureStartPosition = player.getCurrentPosition();
                WindowManager.LayoutParams window = getWindow().getAttributes();
                startBrightness = window.screenBrightness < 0 ? 0.5f : window.screenBrightness;
                startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                seekingByGesture = false;
                gestureMoved = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float distance = event.getX() - gestureStartX;
                float vertical = gestureStartY - event.getY();
                if (Math.abs(distance) < dp(18) && Math.abs(vertical) < dp(18) && !gestureMoved) return true;
                if (!gestureMoved) {
                    gestureMoved = true;
                    seekingByGesture = Math.abs(distance) >= Math.abs(vertical);
                }
                if (!seekingByGesture) {
                    float fraction = vertical / Math.max(1f, playerView.getHeight());
                    if (gestureStartX < playerView.getWidth() / 2f) {
                        WindowManager.LayoutParams params = getWindow().getAttributes();
                        params.screenBrightness = Math.max(0.05f, Math.min(1f, startBrightness + fraction));
                        getWindow().setAttributes(params);
                        hint.setText("Brightness  " + Math.round(params.screenBrightness * 100) + "%");
                    } else {
                        int maximum = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                        int level = Math.max(0, Math.min(maximum, startVolume + Math.round(fraction * maximum)));
                        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, level, 0);
                        hint.setText("Volume  " + Math.round(level * 100f / Math.max(1, maximum)) + "%");
                    }
                    hint.setVisibility(View.VISIBLE);
                    return true;
                }
                long duration = player.getDuration();
                if (duration <= 0) return true;
                long change = (long) ((distance / Math.max(1f, playerView.getWidth())) * duration);
                long target = Math.max(0, Math.min(duration, gestureStartPosition + change));
                player.seekTo(target);
                hint.setText((change >= 0 ? "+ " : "− ") + formatTime(Math.abs(change))
                        + "   " + formatTime(target) + " / " + formatTime(duration));
                hint.setVisibility(View.VISIBLE);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                hint.setVisibility(View.GONE);
                if (!gestureMoved) {
                    long now = android.os.SystemClock.elapsedRealtime();
                    if (now - lastTapAt < 350 && Math.abs(event.getX() - lastTapX) < dp(100)) {
                        long delta = event.getX() < playerView.getWidth() / 2f ? -SEEK_MS : SEEK_MS;
                        long duration = player.getDuration();
                        long target = Math.max(0, player.getCurrentPosition() + delta);
                        if (duration > 0) target = Math.min(duration, target);
                        player.seekTo(target);
                        hint.setText(delta < 0 ? "− 10 seconds" : "+ 10 seconds");
                        hint.setVisibility(View.VISIBLE);
                        hint.postDelayed(() -> hint.setVisibility(View.GONE), 550);
                        lastTapAt = 0;
                    } else {
                        lastTapAt = now;
                        lastTapX = event.getX();
                        if (playerView.isControllerFullyVisible()) playerView.hideController();
                        else playerView.showController();
                    }
                }
                return true;
            default:
                return true;
        }
    }

    private String formatTime(long millis) {
        long total = Math.max(0, millis / 1000);
        long hours = total / 3600;
        long minutes = (total % 3600) / 60;
        long seconds = total % 60;
        return hours > 0 ? String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
                : String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds);
    }

    private void rotateScreen() {
        int o = getResources().getConfiguration().orientation;
        setRequestedOrientation(o == android.content.res.Configuration.ORIENTATION_LANDSCAPE
                ? ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
    }

    private void captureScreenshot() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            Toast.makeText(this, "Screenshot requires Android 8 or newer", Toast.LENGTH_SHORT).show(); return;
        }
        Bitmap bitmap = Bitmap.createBitmap(playerView.getWidth(), playerView.getHeight(), Bitmap.Config.ARGB_8888);
        int[] xy = new int[2]; playerView.getLocationInWindow(xy);
        android.graphics.Rect area = new android.graphics.Rect(xy[0], xy[1], xy[0] + playerView.getWidth(), xy[1] + playerView.getHeight());
        PixelCopy.request(getWindow(), area, bitmap, result -> {
            if (result == PixelCopy.SUCCESS) saveBitmap(bitmap,
                    "NicePlayer_" + System.currentTimeMillis() + ".jpg", "image/jpeg");
            else { bitmap.recycle(); Toast.makeText(this, "Screenshot failed", Toast.LENGTH_SHORT).show(); }
        }, new android.os.Handler(getMainLooper()));
    }

    private void createPreviewSheet() {
        Toast.makeText(this, "Creating 25-frame preview…", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            Bitmap sheet = null;
            try {
                retriever.setDataSource(this, videoUri);
                String durationText = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
                long durationMs = durationText == null ? 0L : Long.parseLong(durationText);
                if (durationMs <= 0) throw new IllegalStateException("Video duration unavailable");

                final int columns = 5, rows = 5, frameWidth = 240, frameHeight = 135;
                final int gap = 6, header = 86;
                int width = columns * frameWidth + (columns + 1) * gap;
                int height = header + rows * frameHeight + (rows + 1) * gap;
                sheet = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
                Canvas canvas = new Canvas(sheet);
                canvas.drawColor(0xFF0F172A);
                Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
                paint.setColor(Color.WHITE); paint.setTextSize(25); paint.setTypeface(Typeface.DEFAULT_BOLD);
                String titleText = getIntent().getStringExtra("title");
                if (titleText == null || titleText.trim().isEmpty()) titleText = "Video preview";
                if (titleText.length() > 55) titleText = titleText.substring(0, 52) + "…";
                canvas.drawText(titleText, gap + 5, 32, paint);
                paint.setColor(0xFF94A3B8); paint.setTextSize(18); paint.setTypeface(Typeface.DEFAULT);
                canvas.drawText("Duration " + formatTime(durationMs) + "  •  25 frames  •  NicePlayer", gap + 5, 62, paint);

                for (int i = 0; i < columns * rows; i++) {
                    long timeMs = durationMs * (i + 1L) / (columns * rows + 1L);
                    Bitmap frame = retriever.getFrameAtTime(timeMs * 1000L,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    int row = i / columns, column = i % columns;
                    int left = gap + column * (frameWidth + gap);
                    int top = header + gap + row * (frameHeight + gap);
                    paint.setColor(0xFF1E293B);
                    canvas.drawRect(left, top, left + frameWidth, top + frameHeight, paint);
                    if (frame != null) {
                        Bitmap scaled = Bitmap.createScaledBitmap(frame, frameWidth, frameHeight, true);
                        canvas.drawBitmap(scaled, left, top, null);
                        if (scaled != frame) scaled.recycle();
                        frame.recycle();
                    }
                    String stamp = formatTime(timeMs);
                    paint.setTextSize(16); paint.setTypeface(Typeface.DEFAULT_BOLD);
                    float textWidth = paint.measureText(stamp);
                    paint.setColor(0xCC000000);
                    canvas.drawRect(left + frameWidth - textWidth - 12, top + frameHeight - 25,
                            left + frameWidth, top + frameHeight, paint);
                    paint.setColor(Color.WHITE);
                    canvas.drawText(stamp, left + frameWidth - textWidth - 6, top + frameHeight - 7, paint);
                }
                Bitmap completed = sheet;
                runOnUiThread(() -> saveBitmap(completed,
                        "NicePlayer_Preview_" + System.currentTimeMillis() + ".jpg", "image/jpeg"));
            } catch (Exception error) {
                if (sheet != null) sheet.recycle();
                runOnUiThread(() -> Toast.makeText(PlayerActivity.this,
                        "Could not create preview for this video", Toast.LENGTH_LONG).show());
            } finally {
                try { retriever.release(); } catch (Exception ignored) { }
            }
        }).start();
    }

    private void saveBitmap(Bitmap bitmap, String fileName, String mimeType) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, mimeType);
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/NicePlayer");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri output = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (output == null) throw new IllegalStateException();
            try (OutputStream stream = getContentResolver().openOutputStream(output)) {
                if (stream == null || !bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) throw new IllegalStateException();
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Images.Media.IS_PENDING, 0);
                getContentResolver().update(output, ready, null, null);
            }
            Toast.makeText(this, fileName.startsWith("NicePlayer_Preview_")
                    ? "Preview saved to Pictures/NicePlayer"
                    : "Screenshot saved to Pictures/NicePlayer", Toast.LENGTH_LONG).show();
        } catch (Exception e) { Toast.makeText(this, "Could not save image", Toast.LENGTH_LONG).show(); }
        finally { bitmap.recycle(); }
    }

    private void hideSystemBars() {
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
}
