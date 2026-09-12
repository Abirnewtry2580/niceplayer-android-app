package com.niceplayer.app;

import android.content.ContentValues;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Typeface;
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

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        videoUri = getIntent().getData();
        if (videoUri == null) { finish(); return; }
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
        gestureArea.setContentDescription("Swipe left or right to seek");
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
                gestureStartPosition = player.getCurrentPosition();
                seekingByGesture = false;
                return true;
            case MotionEvent.ACTION_MOVE:
                float distance = event.getX() - gestureStartX;
                if (Math.abs(distance) < dp(18) && !seekingByGesture) return true;
                seekingByGesture = true;
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
                if (!seekingByGesture) {
                    if (playerView.isControllerFullyVisible()) playerView.hideController();
                    else playerView.showController();
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
            if (result == PixelCopy.SUCCESS) saveBitmap(bitmap);
            else { bitmap.recycle(); Toast.makeText(this, "Screenshot failed", Toast.LENGTH_SHORT).show(); }
        }, new android.os.Handler(getMainLooper()));
    }

    private void saveBitmap(Bitmap bitmap) {
        try {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, "NicePlayer_" + System.currentTimeMillis() + ".jpg");
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                values.put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/NicePlayer");
            Uri output = getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (output == null) throw new IllegalStateException();
            try (OutputStream stream = getContentResolver().openOutputStream(output)) {
                if (stream == null || !bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream)) throw new IllegalStateException();
            }
            Toast.makeText(this, "Screenshot saved to Pictures/NicePlayer", Toast.LENGTH_LONG).show();
        } catch (Exception e) { Toast.makeText(this, "Could not save screenshot", Toast.LENGTH_LONG).show(); }
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
