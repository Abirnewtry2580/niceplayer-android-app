package com.niceplayer.app;

import android.content.ContentValues;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.app.PictureInPictureParams;
import android.graphics.drawable.GradientDrawable;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.media.AudioManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFocusRequest;
import android.media.AudioAttributes;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;
import android.util.Rational;
import android.view.*;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import org.videolan.libvlc.LibVLC;
import org.videolan.libvlc.Media;
import org.videolan.libvlc.MediaPlayer;
import org.videolan.libvlc.util.VLCVideoLayout;
import org.videolan.libvlc.interfaces.IMedia;
import java.io.OutputStream;
import java.io.FileNotFoundException;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Native VLC player: bundled decoders, no WebView playback dependency. */
public class PlayerActivity extends AppCompatActivity {
    private static final String TAG = "NicePlayer";
    private static final long TEN_SECONDS = 10_000L;
    private LibVLC vlc;
    private MediaPlayer player;
    private VLCVideoLayout video;
    private FrameLayout root;
    private LinearLayout top, bottom, quickTools;
    private SeekBar seek;
    private WaveformView waveform;
    private GestureLevelView gestureLevel;
    private TextView play, currentTime, totalTime, remainingTime, hint, lock, title, screenshotButton;
    private TextView rotationLockControl;
    private boolean dragging, controls = true, locked, orientationLocked;
    private boolean privateMode;
    private boolean softwareRetryAttempted;
    private boolean directUriRetryAttempted;
    private Uri sourceUri;
    private ParcelFileDescriptor sourceDescriptor;
    private ArrayList<String> playlistUris = new ArrayList<>(), playlistTitles = new ArrayList<>();
    private int playlistIndex;
    private float downX, downY, startBrightness;
    private float zoomScale=1f,videoPanX,videoPanY,lastPanTouchX,lastPanTouchY;
    private ScaleGestureDetector scaleDetector;
    private int startVolume;
    private AudioManager audioManager;
    private SharedPreferences preferences;
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private long lastPositionSave;
    private long pendingSeek = -1;
    private int soundProtection;
    private int audioCleanup;
    private int subtitleStyle;
    private MediaPlayer.Equalizer cleanupEqualizer;
    private boolean equalizerEnabled;
    private float equalizerPreamp;
    private float[] equalizerGains;
    private boolean headphoneSafety = true, pausedByFocus, autoPip = true;
    private boolean waveformEnabled, pipTransitionPending;
    private long pipPlaybackPosition = -1;
    private boolean pipWasPlaying;
    private float waveformDragStartX, waveformDragStartY, waveformStartX, waveformStartY;
    private long pointA = -1, pointB = -1, audioDelay, subtitleDelay;
    private boolean resumeAfterWaveform;
    private boolean surfaceRefreshPending;
    private boolean positionRestoredForItem;
    private float selectedRate = 1f;
    private AudioFocusRequest focusRequest;
    private final ActivityResultLauncher<String[]> subtitlePicker = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), this::attachExternalSubtitle);
    private final BroadcastReceiver noisyReceiver = new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){if(AudioManager.ACTION_AUDIO_BECOMING_NOISY.equals(intent.getAction())&&player!=null&&player.isPlaying()){player.pause();Toast.makeText(PlayerActivity.this,"Playback paused: headphones disconnected",Toast.LENGTH_LONG).show();}}};
    private final BroadcastReceiver playbackReceiver=new BroadcastReceiver(){@Override public void onReceive(Context context,Intent intent){if(player==null)return;String a=intent.getAction();if(PlaybackService.PLAY_PAUSE.equals(a)){if(player.isPlaying())player.pause();else player.play();}else if(PlaybackService.PREVIOUS.equals(a))playAt(playlistIndex-1);else if(PlaybackService.NEXT.equals(a))playAt(playlistIndex+1);}};
    private long downTime;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable hideLockButton = () -> {
        if (locked && lock != null) lock.setVisibility(View.GONE);
    };
    private final Runnable hideControlsAfterDelay = () -> {
        if (!locked && player != null && player.isPlaying()) setControlsVisible(false);
    };

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (player != null && !dragging) {
                long length = Math.max(0, player.getLength()), now = Math.max(0, player.getTime());
                seek.setMax((int)Math.min(Integer.MAX_VALUE, length));
                seek.setProgress((int)Math.min(Integer.MAX_VALUE, now));
                if(waveform!=null&&waveformEnabled&&length>0)waveform.setTimeline(now,length);
                updateTimeLabels(now, length);
                play.setText(player.isPlaying() ? "Ⅱ" : "▶");
                if (player.isPlaying() && now - lastPositionSave > 5000) {
                    savePosition();
                    lastPositionSave = now;
                }
                if(pointA>=0&&pointB>pointA&&now>=pointB)player.setTime(pointA);
            }
            handler.postDelayed(this, 300);
        }
    };

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        playlistUris = getIntent().getStringArrayListExtra("uris");
        playlistTitles = getIntent().getStringArrayListExtra("titles");
        if (playlistUris == null) playlistUris = new ArrayList<>();
        if (playlistTitles == null) playlistTitles = new ArrayList<>();
        playlistIndex = Math.max(0, getIntent().getIntExtra("index", 0));
        sourceUri = getIntent().getData();
        if (sourceUri == null) { finish(); return; }
        if (playlistUris.isEmpty()) playlistUris.add(sourceUri.toString());
        if (playlistTitles.isEmpty()) playlistTitles.add(getIntent().getStringExtra("title"));
        playlistIndex = Math.min(playlistIndex, playlistUris.size() - 1);
        preferences = getSharedPreferences("playback", MODE_PRIVATE);
        privateMode = getIntent().getBooleanExtra("privateMode", false);
        soundProtection = preferences.getInt("sound_protection", 0);
        audioCleanup = preferences.getInt("audio_cleanup", 0);
        equalizerEnabled = preferences.getBoolean("equalizer_enabled", audioCleanup > 0);
        equalizerPreamp = preferences.getFloat("equalizer_preamp", 0f);
        subtitleStyle = preferences.getInt("subtitle_style", 0);
        headphoneSafety = preferences.getBoolean("headphone_safety", true);
        autoPip = preferences.getBoolean("auto_pip", true);
        waveformEnabled = preferences.getBoolean("waveform_enabled", false);
        orientationLocked = preferences.getBoolean("orientation_locked", false);
        selectedRate = preferences.getFloat("playback_rate", 1f);
        ratioMode = preferences.getInt("ratio_mode", 0);
        audioManager = (AudioManager)getSystemService(Context.AUDIO_SERVICE);
        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS},91);
        IntentFilter noisyFilter=new IntentFilter(AudioManager.ACTION_AUDIO_BECOMING_NOISY);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(noisyReceiver,noisyFilter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(noisyReceiver,noisyFilter);
        IntentFilter playbackFilter=new IntentFilter();playbackFilter.addAction(PlaybackService.PLAY_PAUSE);playbackFilter.addAction(PlaybackService.PREVIOUS);playbackFilter.addAction(PlaybackService.NEXT);
        if(Build.VERSION.SDK_INT>=33)registerReceiver(playbackReceiver,playbackFilter,Context.RECEIVER_NOT_EXPORTED);else registerReceiver(playbackReceiver,playbackFilter);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        immersive();
        ArrayList<String> options = new ArrayList<>();
        options.add("--audio-time-stretch");
        options.add("--avcodec-fast");
        options.add("--drop-late-frames");
        options.add("--skip-frames");
        options.add("--network-caching=1500");
        vlc = new LibVLC(this, options);
        player = new MediaPlayer(vlc);
        makeUi();
        player.attachViews(video, null, false, false);
        player.setEventListener(e -> runOnUiThread(() -> {
            if (e.type == MediaPlayer.Event.EncounteredError) handlePlaybackError();
            else if (e.type == MediaPlayer.Event.Playing) { restorePosition();if(!privateMode)recordHistory();else forgetCurrentVideo();player.setRate(selectedRate);applyStoredRatio();applyAudioCleanup();applySafeStart();startPlaybackService();scheduleControlsHide(); }
            else if (e.type == MediaPlayer.Event.Paused) showControls(false);
            else if (e.type == MediaPlayer.Event.EndReached) {
                if (player.getLength() <= 0 || player.getTime() < 1000) handlePlaybackError();
                else playAt(playlistIndex + 1);
            }
        }));
        if (!startPlayback(true)) {
            Toast.makeText(this, "Could not open this video", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if(orientationLocked)applyOrientationLock();
        if(waveform!=null)waveform.clearAnalysis();
        if(waveformEnabled)handler.postDelayed(this::loadWaveform,500);
        handler.post(ticker);
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { finish(); }
        });
    }

    /**
     * MediaStore content URIs are opened as a file descriptor. Passing the URI directly to
     * LibVLC works on some phones, but fails on others even though this app owns permission.
     */
    private boolean startPlayback(boolean hardwareDecoder) {
        return startPlayback(hardwareDecoder, false);
    }

    private boolean startPlayback(boolean hardwareDecoder, boolean directUri) {
        closeSourceDescriptor();
        try {
            Media media;
            if ("content".equalsIgnoreCase(sourceUri.getScheme()) && !directUri) {
                sourceDescriptor = getContentResolver().openFileDescriptor(sourceUri, "r");
                if (sourceDescriptor == null) throw new FileNotFoundException("Null video descriptor");
                media = new Media(vlc, sourceDescriptor.getFileDescriptor());
            } else {
                media = new Media(vlc, sourceUri);
            }
            media.setHWDecoderEnabled(hardwareDecoder, hardwareDecoder);
            if (soundProtection > 0) {
                media.addOption(":audio-filter=compressor,normvol");
                media.addOption(":compressor-attack=" + (soundProtection == 1 ? "25" : soundProtection == 2 ? "10" : "5"));
                media.addOption(":compressor-release=" + (soundProtection == 1 ? "250" : soundProtection == 2 ? "350" : "500"));
                media.addOption(":compressor-threshold=" + (soundProtection == 1 ? "-12" : soundProtection == 2 ? "-18" : "-24"));
                media.addOption(":compressor-ratio=" + (soundProtection == 1 ? "2" : soundProtection == 2 ? "4" : "8"));
                media.addOption(":compressor-knee=" + (soundProtection == 1 ? "2" : soundProtection == 2 ? "3" : "5"));
                media.addOption(":compressor-makeup-gain=" + (soundProtection == 1 ? "2" : soundProtection == 2 ? "4" : "6"));
                media.addOption(":norm-max-level=1.0");
            }
            if(subtitleStyle==1)media.addOption(":freetype-fontsize=18");
            else if(subtitleStyle==2){media.addOption(":freetype-fontsize=28");media.addOption(":freetype-color=16776960");}
            else if(subtitleStyle==3){media.addOption(":freetype-fontsize=32");media.addOption(":sub-margin=80");}
            player.setMedia(media);
            media.release();
            requestAudioFocus();
            player.play();
            return true;
        } catch (Exception error) {
            Log.e(TAG, "Unable to open video " + sourceUri, error);
            closeSourceDescriptor();
            return false;
        }
    }

    private void handlePlaybackError() {
        if (!softwareRetryAttempted) {
            softwareRetryAttempted = true;
            Toast.makeText(this, "Hardware decoder failed. Trying compatible mode…", Toast.LENGTH_SHORT).show();
            player.stop();
            if (startPlayback(false)) return;
        }
        if (!directUriRetryAttempted && "content".equalsIgnoreCase(sourceUri.getScheme())) {
            directUriRetryAttempted = true;
            Toast.makeText(this, "Trying alternate storage access…", Toast.LENGTH_SHORT).show();
            player.stop();
            if (startPlayback(false, true)) return;
        }
        Toast.makeText(this, "This video could not be opened. Android may be blocking access to this hidden folder.", Toast.LENGTH_LONG).show();
    }

    private void closeSourceDescriptor() {
        if (sourceDescriptor == null) return;
        try { sourceDescriptor.close(); } catch (Exception ignored) {}
        sourceDescriptor = null;
    }

    private String currentTitle() {
        return playlistIndex < playlistTitles.size() && playlistTitles.get(playlistIndex) != null
                ? playlistTitles.get(playlistIndex) : "Video";
    }

    private String positionKey() { return "position_" + sourceUri; }

    private void restorePosition() {
        if(positionRestoredForItem)return;
        positionRestoredForItem=true;
        if (pendingSeek >= 0) {
            player.setTime(pendingSeek);
            pendingSeek = -1;
            return;
        }
        long saved = preferences.getLong(positionKey(), 0);
        long length = player.getLength();
        if (saved > 5000 && (length <= 0 || saved < length - 10000)) player.setTime(saved);
    }

    private void requestAudioFocus(){
        AudioManager.OnAudioFocusChangeListener listener=change->{if(player==null)return;if(change<=AudioManager.AUDIOFOCUS_LOSS_TRANSIENT){pausedByFocus=player.isPlaying();player.pause();}else if(change==AudioManager.AUDIOFOCUS_LOSS){pausedByFocus=false;player.pause();}else if(change==AudioManager.AUDIOFOCUS_GAIN&&pausedByFocus){pausedByFocus=false;player.play();}};
        if(Build.VERSION.SDK_INT>=26){focusRequest=new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN).setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build()).setOnAudioFocusChangeListener(listener).build();audioManager.requestAudioFocus(focusRequest);}else audioManager.requestAudioFocus(listener,AudioManager.STREAM_MUSIC,AudioManager.AUDIOFOCUS_GAIN);
    }
    private void startPlaybackService(){Intent service=new Intent(this,PlaybackService.class).putExtra("title",currentTitle());if(Build.VERSION.SDK_INT>=26)startForegroundService(service);else startService(service);}
    private boolean headphonesActive(){for(AudioDeviceInfo d:audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS)){int t=d.getType();if(t==AudioDeviceInfo.TYPE_WIRED_HEADPHONES||t==AudioDeviceInfo.TYPE_WIRED_HEADSET||t==AudioDeviceInfo.TYPE_USB_HEADSET||t==AudioDeviceInfo.TYPE_BLUETOOTH_A2DP||(Build.VERSION.SDK_INT>=31&&t==AudioDeviceInfo.TYPE_BLE_HEADSET))return true;}return false;}
    private void applySafeStart(){
        if(!headphoneSafety||!headphonesActive())return;int max=audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),safe=Math.max(1,Math.round(max*.7f)),now=audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);if(now>safe)audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,safe,0);
        player.setVolume(35);for(int i=1;i<=5;i++){int volume=35+i*13;handler.postDelayed(()->{if(player!=null)player.setVolume(Math.min(100,volume));},i*120L);}
    }
    private void attachExternalSubtitle(Uri uri){if(uri==null||player==null)return;try{getContentResolver().takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);boolean ok=player.addSlave(IMedia.Slave.Type.Subtitle,uri,true);Toast.makeText(this,ok?"External subtitle added":"Could not add subtitle",Toast.LENGTH_LONG).show();}catch(Exception e){Log.e(TAG,"Subtitle attach failed",e);Toast.makeText(this,"Could not add subtitle",Toast.LENGTH_LONG).show();}}

    private void savePosition() {
        if (player == null || sourceUri == null) return;
        long at = player.getTime(), length = player.getLength();
        SharedPreferences.Editor edit = preferences.edit();
        if (at > 5000 && (length <= 0 || at < length - 10000)) edit.putLong(positionKey(), at);
        else edit.remove(positionKey());
        edit.apply();
    }
    private void savePositionImmediately() {
        if (player == null || sourceUri == null) return;
        long at = player.getTime(), length = player.getLength();
        SharedPreferences.Editor edit = preferences.edit();
        if (at > 0 && (length <= 0 || at < length - 10000)) edit.putLong(positionKey(), at);
        else edit.remove(positionKey());
        edit.commit();
    }
    private void recordHistory(){String uri=sourceUri.toString(),name=currentTitle();ArrayList<String> uris=new ArrayList<>(),names=new ArrayList<>();uris.add(uri);names.add(name);for(int i=0;i<12;i++){String old=preferences.getString("history_uri_"+i,null);if(old!=null&&!old.equals(uri)){uris.add(old);names.add(preferences.getString("history_name_"+i,"Video"));if(uris.size()==12)break;}}SharedPreferences.Editor e=preferences.edit();for(int i=0;i<12;i++){if(i<uris.size()){e.putString("history_uri_"+i,uris.get(i));e.putString("history_name_"+i,names.get(i));}else{e.remove("history_uri_"+i);e.remove("history_name_"+i);}}e.apply();}
    private void forgetCurrentVideo(){if(preferences==null||sourceUri==null)return;String hiddenUri=sourceUri.toString();ArrayList<String> uris=new ArrayList<>(),names=new ArrayList<>();for(int i=0;i<12;i++){String uri=preferences.getString("history_uri_"+i,null);if(uri!=null&&!uri.equals(hiddenUri)){uris.add(uri);names.add(preferences.getString("history_name_"+i,"Video"));}}SharedPreferences.Editor e=preferences.edit();for(int i=0;i<12;i++){if(i<uris.size()){e.putString("history_uri_"+i,uris.get(i));e.putString("history_name_"+i,names.get(i));}else{e.remove("history_uri_"+i);e.remove("history_name_"+i);}}e.apply();}

    private void playAt(int index) {
        if (index < 0 || index >= playlistUris.size()) {
            Toast.makeText(this, index < 0 ? "This is the first video" : "Playlist finished", Toast.LENGTH_SHORT).show();
            return;
        }
        savePosition();
        playlistIndex = index;
        sourceUri = Uri.parse(playlistUris.get(index));
        positionRestoredForItem=false;
        softwareRetryAttempted = false;
        directUriRetryAttempted = false;
        title.setText(currentTitle());
        player.stop();
        resetVideoZoom(false);
        startPlayback(true);
        if(waveform!=null)waveform.clearAnalysis();
        if(waveformEnabled)handler.postDelayed(this::loadWaveform,500);
    }

    private void makeUi() {
        root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        video = new VLCVideoLayout(this); root.addView(video, new FrameLayout.LayoutParams(-1, -1));
        hint = label("", 17); hint.setBackgroundColor(0xCC111827); hint.setPadding(dp(18),dp(10),dp(18),dp(10)); hint.setVisibility(View.GONE);
        root.addView(hint, new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));
        gestureLevel=new GestureLevelView(this);gestureLevel.setVisibility(View.GONE);
        FrameLayout.LayoutParams levelParams=new FrameLayout.LayoutParams(dp(34),dp(200),Gravity.START|Gravity.CENTER_VERTICAL);levelParams.leftMargin=dp(28);root.addView(gestureLevel,levelParams);

        GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { toggleControls(); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) { if (!locked){if(zoomScale>1.01f)resetVideoZoom(true);else jump(e.getX() < root.getWidth()/2f ? -TEN_SECONDS : TEN_SECONDS);} return true; }
        });
        scaleDetector=new ScaleGestureDetector(this,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector d){
                zoomScale=Math.max(1f,Math.min(4f,zoomScale*d.getScaleFactor()));
                if(zoomScale<=1.01f){zoomScale=1f;videoPanX=videoPanY=0;}applyVideoZoom();
                hint.setText("Zoom  "+Math.round(zoomScale*100)+"%");hint.setVisibility(View.VISIBLE);return true;
            }
            @Override public void onScaleEnd(ScaleGestureDetector d){handler.postDelayed(()->hint.setVisibility(View.GONE),500);}
        });
        View gestures = new View(this);
        gestures.setOnTouchListener((v,e) -> gesture(e, detector));
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(-1,-1); gp.topMargin=dp(134); gp.bottomMargin=dp(118); root.addView(gestures,gp);
        makeTop();makeQuickTools();makeBottom();createFloatingWaveform();
        lock = label("🔓", 17);GradientDrawable lockBackground=new GradientDrawable();lockBackground.setShape(GradientDrawable.OVAL);lockBackground.setColor(Color.TRANSPARENT);lock.setBackground(lockBackground);lock.setOnClickListener(v -> toggleLock());
        lock.setOnTouchListener((view,event)->{int action=event.getActionMasked();if(action==MotionEvent.ACTION_DOWN)lockBackground.setColor(0x88111827);else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL)lockBackground.setColor(Color.TRANSPARENT);view.invalidate();return false;});
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(40),dp(40),Gravity.START|Gravity.BOTTOM); lp.leftMargin=dp(10); lp.bottomMargin=dp(13); root.addView(lock,lp);
        screenshotButton = topCircle("📷", 23);
        screenshotButton.setBackgroundColor(Color.TRANSPARENT);screenshotButton.setAlpha(.5f);screenshotButton.setContentDescription("Take screenshot");screenshotButton.setOnClickListener(v->screenshot());
        FrameLayout.LayoutParams screenshotParams=new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.END|Gravity.CENTER_VERTICAL);screenshotParams.rightMargin=dp(18);root.addView(screenshotButton,screenshotParams);
        setContentView(root);
    }

    private void makeTop() {
        top = new LinearLayout(this); top.setGravity(Gravity.CENTER_VERTICAL); top.setPadding(dp(5),dp(5),dp(5),dp(5)); top.setBackgroundColor(Color.TRANSPARENT);
        TextView back=topCircle("‹",34); back.setOnClickListener(v->finish()); top.addView(back,topButtonParams(46));
        title=label(currentTitle(),15); if(title.getText().toString().trim().isEmpty())title.setText("Video");
        title.setShadowLayer(dp(3),0,dp(1),Color.BLACK);title.setGravity(Gravity.START|Gravity.CENTER_VERTICAL); title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END); top.addView(title,new LinearLayout.LayoutParams(0,dp(50),1));
        TextView speed=topCircle("1×",15); speed.setOnClickListener(v->speedMenu(speed)); top.addView(speed,topButtonParams(46));
        TextView ratio=topCircle("FIT",11); ratio.setOnClickListener(v->ratio(ratio)); top.addView(ratio,topButtonParams(46));
        TextView rotate=topCircle("↻",25); rotate.setOnClickListener(v->rotate()); top.addView(rotate,topButtonParams(46));
        TextView more=topCircle("⋮",26); more.setOnClickListener(v->moreMenu(more)); top.addView(more,topButtonParams(46));
        root.addView(top,new FrameLayout.LayoutParams(-1,dp(60),Gravity.TOP));
    }
    private TextView topCircle(String text,int size){TextView view=label(text,size);view.setShadowLayer(dp(3),0,dp(1),Color.BLACK);view.setBackgroundColor(Color.TRANSPARENT);view.setOnTouchListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_DOWN)showControls(false);else if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL)scheduleControlsHide();return false;});return view;}
    private LinearLayout.LayoutParams topButtonParams(int size){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(dp(size),dp(size));params.setMargins(dp(3),0,dp(3),0);return params;}

    private void makeQuickTools(){android.widget.HorizontalScrollView scroll=new android.widget.HorizontalScrollView(this);scroll.setHorizontalScrollBarEnabled(false);scroll.setBackgroundColor(Color.TRANSPARENT);quickTools=new LinearLayout(this);quickTools.setGravity(Gravity.CENTER_VERTICAL);quickTools.setPadding(dp(8),dp(5),dp(8),dp(5));
        addQuick("A↔B\nREPEAT",v->abRepeatMenu());addQuick("⊕\nPINCH",v->zoomMenu());addQuick("▣\nPOP-UP",v->enterPip());addQuick("≋\nCLEANUP",v->audioCleanupMenu());
        rotationLockControl=addQuick(orientationLocked?"ROTATION\nLOCKED":"ROTATION\nLOCK",null);rotationLockControl.setOnClickListener(v->toggleOrientationLock(rotationLockControl));
        addQuick("VOL\nMUTE",v->{boolean mute=player.getVolume()>0;player.setVolume(mute?0:100);});addQuick("SAFE\nAUDIO",v->toggleHeadphoneSafety());TextView speed=addQuick("1×\nSPEED",null);speed.setOnClickListener(v->speedMenu(speed));
        scroll.addView(quickTools,new android.widget.HorizontalScrollView.LayoutParams(-2,-1));FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(-1,dp(70),Gravity.TOP);params.topMargin=dp(60);root.addView(scroll,params);quickTools.setTag(scroll);
    }
    private TextView addQuick(String text,View.OnClickListener click){TextView v=label(text,9);v.setLines(2);GradientDrawable background=new GradientDrawable();background.setShape(GradientDrawable.OVAL);background.setColor(0x7A111827);v.setBackground(background);v.setOnTouchListener((view,event)->{if(event.getActionMasked()==MotionEvent.ACTION_DOWN)showControls(false);else if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL)scheduleControlsHide();return false;});if(click!=null)v.setOnClickListener(click);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(58),dp(58));p.setMargins(dp(4),0,dp(4),0);quickTools.addView(v,p);return v;}

    private void makeBottom() {
        bottom=new LinearLayout(this); bottom.setOrientation(LinearLayout.VERTICAL); bottom.setPadding(dp(10),0,dp(10),dp(6)); bottom.setBackgroundColor(Color.TRANSPARENT);
        LinearLayout timeRow=new LinearLayout(this);timeRow.setGravity(Gravity.CENTER_VERTICAL);
        currentTime=timeLabel("00:00",Gravity.START|Gravity.CENTER_VERTICAL);
        totalTime=timeLabel("00:00",Gravity.CENTER);
        remainingTime=timeLabel("-00:00",Gravity.END|Gravity.CENTER_VERTICAL);
        timeRow.addView(totalTime,new LinearLayout.LayoutParams(0,dp(24),1));
        timeRow.addView(currentTime,new LinearLayout.LayoutParams(0,dp(24),1));
        timeRow.addView(remainingTime,new LinearLayout.LayoutParams(0,dp(24),1));
        bottom.addView(timeRow,new LinearLayout.LayoutParams(-1,dp(24)));
        seek=new SeekBar(this); seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar b){dragging=true;handler.removeCallbacks(hideControlsAfterDelay);} public void onProgressChanged(SeekBar b,int n,boolean from){if(from)updateTimeLabels(n,Math.max(0,player.getLength()));}
            public void onStopTrackingTouch(SeekBar b){player.setTime(b.getProgress());dragging=false;scheduleControlsHide();}
        }); bottom.addView(seek,new LinearLayout.LayoutParams(-1,dp(30)));
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER);
        addControl(row,"|◀",21,v->playAt(playlistIndex-1),48); addControl(row,"↶ 10",18,v->jump(-TEN_SECONDS),48);
        play=addControl(row,"▶",31,v->{if(player.isPlaying())player.pause();else player.play();},58);
        addControl(row,"10 ↷",18,v->jump(TEN_SECONDS),48);
        addControl(row,"▶|",21,v->playAt(playlistIndex+1),48);
        FrameLayout controlsLayer=new FrameLayout(this);controlsLayer.addView(row,new FrameLayout.LayoutParams(-2,dp(55),Gravity.CENTER));
        TextView subtitles=label("CC",14);subtitles.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);subtitles.setContentDescription("Subtitles");subtitles.setOnTouchListener((v,e)->{if(e.getActionMasked()==MotionEvent.ACTION_DOWN)showControls(false);else if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL)scheduleControlsHide();return false;});subtitles.setOnClickListener(v->subtitleTracks());
        FrameLayout.LayoutParams subtitleParams=new FrameLayout.LayoutParams(dp(48),dp(48),Gravity.END|Gravity.CENTER_VERTICAL);subtitleParams.rightMargin=dp(8);controlsLayer.addView(subtitles,subtitleParams);
        bottom.addView(controlsLayer,new LinearLayout.LayoutParams(-1,dp(55)));
        root.addView(bottom,new FrameLayout.LayoutParams(-1,dp(115),Gravity.BOTTOM));
    }

    private TextView timeLabel(String text,int gravity){TextView view=label(text,12);view.setShadowLayer(dp(3),0,dp(1),Color.BLACK);view.setGravity(gravity);return view;}
    private void updateTimeLabels(long position,long duration){
        long safeDuration=Math.max(0,duration),safePosition=Math.max(0,Math.min(position,safeDuration));
        currentTime.setText(clock(safePosition));
        totalTime.setText(clock(safeDuration));
        remainingTime.setText("-"+clock(Math.max(0,safeDuration-safePosition)));
    }

    private void loadWaveform(){
        if(waveform==null||!waveformEnabled)return;
        waveform.clearAnalysis();
        Uri uri=sourceUri;
        long duration=Math.max(0,player.getLength());
        int buckets=(int)Math.max(600,Math.min(72_000,duration/100L));
        worker.execute(()->AudioWaveformExtractor.extract(this,uri,buckets,new AudioWaveformExtractor.Callback(){
            public void complete(AudioWaveformExtractor.Result result){runOnUiThread(()->{if(uri.equals(sourceUri)&&waveform!=null&&waveformEnabled)waveform.setAnalysis(result);finishWaveformAnalysis();});}
            public void failed(){runOnUiThread(()->{Toast.makeText(PlayerActivity.this,"Waveform analysis failed",Toast.LENGTH_SHORT).show();finishWaveformAnalysis();});}
        }));
    }
    private void finishWaveformAnalysis(){if(resumeAfterWaveform&&player!=null&&!player.isPlaying())player.play();resumeAfterWaveform=false;}

    private void createFloatingWaveform(){
        waveform=new WaveformView(this);
        GradientDrawable background=new GradientDrawable();
        background.setColor(0xCC111827);
        background.setCornerRadius(dp(14));
        waveform.setBackground(background);
        waveform.setPadding(dp(8),dp(7),dp(8),dp(7));
        waveform.setAlpha(1f);
        waveform.setElevation(dp(30));
        waveform.setVisibility(waveformEnabled?View.VISIBLE:View.GONE);
        int waveformWidth=Math.min(dp(360),getResources().getDisplayMetrics().widthPixels-dp(32));
        FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(waveformWidth,dp(76),Gravity.TOP|Gravity.START);
        root.addView(waveform,params);
        root.post(()->positionWaveformFromPreferences());
        waveform.setOnTouchListener((view,event)->{
            if(event.getActionMasked()==MotionEvent.ACTION_DOWN){
                waveformDragStartX=event.getRawX();waveformDragStartY=event.getRawY();
                waveformStartX=view.getX();waveformStartY=view.getY();view.bringToFront();return true;
            }
            if(event.getActionMasked()==MotionEvent.ACTION_MOVE){
                float x=waveformStartX+event.getRawX()-waveformDragStartX;
                float y=waveformStartY+event.getRawY()-waveformDragStartY;
                x=Math.max(0,Math.min(root.getWidth()-view.getWidth(),x));
                y=Math.max(0,Math.min(root.getHeight()-view.getHeight(),y));
                view.setX(x);view.setY(y);return true;
            }
            if(event.getActionMasked()==MotionEvent.ACTION_UP||event.getActionMasked()==MotionEvent.ACTION_CANCEL){
                float maxX=Math.max(1,root.getWidth()-view.getWidth());
                float maxY=Math.max(1,root.getHeight()-view.getHeight());
                preferences.edit().putFloat("waveform_x_fraction",view.getX()/maxX).putFloat("waveform_y_fraction",view.getY()/maxY).apply();
                return true;
            }
            return false;
        });
    }

    private void positionWaveformFromPreferences(){
        if(waveform==null||root.getWidth()==0||root.getHeight()==0)return;
        float maxX=Math.max(0,root.getWidth()-waveform.getWidth());
        float maxY=Math.max(0,root.getHeight()-waveform.getHeight());
        float savedX=Math.max(0f,Math.min(1f,preferences.getFloat("waveform_x_fraction",.5f)));
        float savedY=Math.max(0f,Math.min(1f,preferences.getFloat("waveform_y_fraction",.55f)));
        waveform.setX(savedX*maxX);waveform.setY(savedY*maxY);
    }

    private void showWaveformOverlay(){
        if(waveform==null)return;
        waveform.setAlpha(1f);
        waveform.setVisibility(View.VISIBLE);
        waveform.bringToFront();
        waveform.requestLayout();
        root.post(()->{positionWaveformFromPreferences();waveform.bringToFront();waveform.invalidate();});
    }

    private TextView addControl(LinearLayout row,String text,int size,View.OnClickListener click,int slotWidth){
        FrameLayout slot=new FrameLayout(this);TextView v=new PlaybackControlView(this,text);
        GradientDrawable circle=new GradientDrawable();circle.setShape(GradientDrawable.OVAL);circle.setColor(Color.TRANSPARENT);circle.setStroke(dp(1),Color.TRANSPARENT);v.setBackground(circle);
        v.setOnTouchListener((buttonView,event)->{
            int action=event.getActionMasked();
            if(action==MotionEvent.ACTION_DOWN){
                showControls(false);
                circle.setColor(0xCC172554);circle.setStroke(dp(2),0xFF7C6CFF);
                buttonView.animate().scaleX(.96f).scaleY(.96f).setDuration(70).start();buttonView.invalidate();
            }else if(action==MotionEvent.ACTION_UP||action==MotionEvent.ACTION_CANCEL){
                circle.setColor(Color.TRANSPARENT);circle.setStroke(dp(1),Color.TRANSPARENT);
                buttonView.animate().scaleX(1f).scaleY(1f).setDuration(120).start();buttonView.invalidate();
                scheduleControlsHide();
            }
            return false;
        });
        if(click!=null)v.setOnClickListener(click);
        int diameter=slotWidth>50?54:48;
        FrameLayout.LayoutParams button=new FrameLayout.LayoutParams(dp(diameter),dp(diameter),Gravity.CENTER);slot.addView(v,button);
        LinearLayout.LayoutParams slotParams=new LinearLayout.LayoutParams(dp(slotWidth),dp(58));slotParams.setMargins(dp(1),0,dp(1),0);row.addView(slot,slotParams);return v;
    }

    private boolean gesture(MotionEvent e, GestureDetector detector) {
        scaleDetector.onTouchEvent(e);if(locked){detector.onTouchEvent(e);return true;}
        if(e.getPointerCount()>1||scaleDetector.isInProgress()){
            if(e.getActionMasked()==MotionEvent.ACTION_POINTER_DOWN){lastPanTouchX=e.getX(0);lastPanTouchY=e.getY(0);}return true;
        }
        if(zoomScale>1.01f){
            if(e.getActionMasked()==MotionEvent.ACTION_DOWN){lastPanTouchX=e.getX();lastPanTouchY=e.getY();}
            else if(e.getActionMasked()==MotionEvent.ACTION_MOVE){videoPanX+=e.getX()-lastPanTouchX;videoPanY+=e.getY()-lastPanTouchY;lastPanTouchX=e.getX();lastPanTouchY=e.getY();applyVideoZoom();hint.setText("Zoom  "+Math.round(zoomScale*100)+"%  ·  Drag to inspect");hint.setVisibility(View.VISIBLE);}
            else if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){hint.setVisibility(View.GONE);}
            detector.onTouchEvent(e);return true;
        }
        detector.onTouchEvent(e);
        if(e.getActionMasked()==MotionEvent.ACTION_DOWN){
            downX=e.getX(); downY=e.getY(); downTime=Math.max(0,player.getTime()); dragging=false;
            WindowManager.LayoutParams p=getWindow().getAttributes(); startBrightness=p.screenBrightness<0?.5f:p.screenBrightness;
            startVolume=audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
        }
        else if(e.getActionMasked()==MotionEvent.ACTION_MOVE){
            float dx=e.getX()-downX,dy=e.getY()-downY;
            if(Math.abs(dx)>Math.abs(dy)&&Math.abs(dx)>dp(20)){
                dragging=true;long len=player.getLength();if(len>0){long delta=(long)(dx/root.getWidth()*len),target=Math.max(0,Math.min(len,downTime+delta));player.setTime(target);hint.setText((delta>=0?"+ ":"− ")+clock(Math.abs(delta))+"   "+clock(target));hint.setVisibility(View.VISIBLE);}
            } else if(Math.abs(dy)>dp(20)) {
                dragging=true; float change=-dy/root.getHeight();
                if(downX<root.getWidth()/2f){
                    WindowManager.LayoutParams p=getWindow().getAttributes();p.screenBrightness=Math.max(.02f,Math.min(1f,startBrightness+change));getWindow().setAttributes(p);
                    hint.setText("Brightness  "+Math.round(p.screenBrightness*100)+"%");showGestureLevel(p.screenBrightness,false);
                }else{
                    int max=audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),limit=headphoneSafety&&headphonesActive()?Math.max(1,Math.round(max*.7f)):max,n=Math.max(0,Math.min(limit,startVolume+Math.round(change*max)));
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,n,0);hint.setText("Volume  "+Math.round(n*100f/max)+"%");showGestureLevel(n/(float)max,true);
                }
                hint.setVisibility(View.VISIBLE);
            }
        }
        else if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){hint.setVisibility(View.GONE);if(gestureLevel!=null)gestureLevel.setVisibility(View.GONE);dragging=false;}
        return true;
    }

    private void showGestureLevel(float level,boolean volumeSide){
        if(gestureLevel==null)return;
        gestureLevel.setLevel(Math.max(0f,Math.min(1f,level)),volumeSide);
        FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)gestureLevel.getLayoutParams();
        params.gravity=(volumeSide?Gravity.START:Gravity.END)|Gravity.CENTER_VERTICAL;
        params.leftMargin=volumeSide?dp(28):0;params.rightMargin=volumeSide?0:dp(28);
        gestureLevel.setLayoutParams(params);gestureLevel.setVisibility(View.VISIBLE);gestureLevel.bringToFront();
    }

    private void applyVideoZoom(){
        float maxX=root.getWidth()*(zoomScale-1f)/2f,maxY=root.getHeight()*(zoomScale-1f)/2f;
        videoPanX=Math.max(-maxX,Math.min(maxX,videoPanX));videoPanY=Math.max(-maxY,Math.min(maxY,videoPanY));
        video.setScaleX(zoomScale);video.setScaleY(zoomScale);video.setTranslationX(videoPanX);video.setTranslationY(videoPanY);
    }
    private void resetVideoZoom(boolean notify){zoomScale=1f;videoPanX=videoPanY=0;if(video!=null)applyVideoZoom();if(notify)Toast.makeText(this,"Zoom reset",Toast.LENGTH_SHORT).show();}

    private void zoomMenu(){
        String[] levels={"Reset (1×)","2× zoom","3× zoom","4× zoom","How to use"};
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Pinch zoom · up to 4×").setItems(levels,(dialog,index)->{
            if(index<4){zoomScale=index+1f;if(zoomScale<=1f)videoPanX=videoPanY=0;applyVideoZoom();Toast.makeText(this,"Zoom "+(index+1)+"×",Toast.LENGTH_SHORT).show();}
            else Toast.makeText(this,"Pinch with two fingers to zoom. Drag while zoomed to inspect.",Toast.LENGTH_LONG).show();
        }).show();
    }

    private void jump(long amount){long len=Math.max(0,player.getLength());player.setTime(Math.max(0,Math.min(len,player.getTime()+amount)));hint.setText(amount>0?"+10 seconds":"−10 seconds");hint.setVisibility(View.VISIBLE);handler.postDelayed(()->hint.setVisibility(View.GONE),550);}
    private void speedMenu(TextView anchor){android.view.ContextThemeWrapper popupContext=new android.view.ContextThemeWrapper(this,R.style.NicePlayerPopupTheme);
        PopupMenu m=new PopupMenu(popupContext,anchor);float[] s={.25f,.5f,.75f,1f,1.25f,1.5f,2f};for(int i=0;i<s.length;i++)m.getMenu().add(0,i,i,s[i]+"×");m.setOnMenuItemClickListener(x->{float n=s[x.getItemId()];selectedRate=n;preferences.edit().putFloat("playback_rate",n).apply();player.setRate(n);anchor.setText(n==1f?"1×":n+"×");return true;});m.show();}
    private int ratioMode;
    private void ratio(TextView v){ratioMode=(ratioMode+1)%3;preferences.edit().putInt("ratio_mode",ratioMode).apply();applyStoredRatio();v.setText(ratioMode==0?"FIT":ratioMode==1?"16:9":"ZOOM");}
    private void applyStoredRatio(){if(ratioMode==0){player.setAspectRatio(null);player.setScale(0);}else if(ratioMode==1){player.setAspectRatio("16:9");player.setScale(0);}else{player.setAspectRatio(null);player.setScale(1.35f);}}
    private void toggleControls(){
        if(locked){
            lock.setVisibility(lock.getVisibility()==View.VISIBLE?View.GONE:View.VISIBLE);
            handler.removeCallbacks(hideLockButton);
            if(lock.getVisibility()==View.VISIBLE)handler.postDelayed(hideLockButton,2200);
            return;
        }
        showControls(true);
    }
    private void setControlsVisible(boolean visible){
        controls=visible;int visibility=visible?View.VISIBLE:View.GONE;
        top.setVisibility(visibility);bottom.setVisibility(visibility);((View)quickTools.getTag()).setVisibility(visibility);lock.setVisibility(visibility);screenshotButton.setVisibility(visibility);
        if(waveform!=null)waveform.setVisibility(visible&&waveformEnabled?View.VISIBLE:View.GONE);
    }
    private void showControls(boolean restartTimer){
        if(locked)return;
        handler.removeCallbacks(hideControlsAfterDelay);setControlsVisible(true);
        if(restartTimer)scheduleControlsHide();
    }
    private void scheduleControlsHide(){
        handler.removeCallbacks(hideControlsAfterDelay);
        if(!locked&&player!=null&&player.isPlaying())handler.postDelayed(hideControlsAfterDelay,3000);
    }
    private void toggleLock(){
        locked=!locked;handler.removeCallbacks(hideLockButton);handler.removeCallbacks(hideControlsAfterDelay);lock.setText(locked?"🔒":"🔓");
        if(locked){setControlsVisible(false);lock.setVisibility(View.VISIBLE);handler.postDelayed(hideLockButton,2200);}
        else{setControlsVisible(true);scheduleControlsHide();}
    }
    private void moreMenu(TextView anchor){
        android.view.ContextThemeWrapper popupContext=new android.view.ContextThemeWrapper(this,R.style.NicePlayerPopupTheme);
        PopupMenu m=new PopupMenu(popupContext,anchor);
        m.getMenu().add(0,1,0,"Audio track");
        m.getMenu().add(0,2,1,"Subtitles");
        m.getMenu().add(0,3,2,"Sleep timer");
        m.getMenu().add(0,4,3,"Create preview sheet");
        m.getMenu().add(0,7,5,"Sudden sound protection");
        m.getMenu().add(0,14,6,"Audio cleanup");
        m.getMenu().add(0,8,7,"Headphone safety: "+(headphoneSafety?"On":"Off"));
        m.getMenu().add(0,9,8,"Add external subtitle");
        m.getMenu().add(0,10,9,"Audio sync");
        m.getMenu().add(0,19,10,"Subtitle sync");
        m.getMenu().add(0,11,11,"A–B repeat");
        m.getMenu().add(0,12,12,"Next frame");
        m.getMenu().add(0,13,13,"Playback diagnostics");
        m.getMenu().add(0,16,14,"Subtitle appearance");
        m.getMenu().add(0,17,15,"Playlist");
        m.getMenu().add(0,18,16,"Audio waveform: "+(waveformEnabled?"On":"Off"));
        if(Build.VERSION.SDK_INT>=26){m.getMenu().add(0,6,17,"Picture in picture");m.getMenu().add(0,15,18,"Auto pop-up: "+(autoPip?"On":"Off"));}
        m.setOnMenuItemClickListener(x->{switch(x.getItemId()){case 1:audioTracks();break;case 2:subtitleTracks();break;case 3:sleepTimer();break;case 4:createPreviewSheet();break;case 6:enterPip();break;case 7:soundProtectionMenu();break;case 8:toggleHeadphoneSafety();break;case 9:subtitlePicker.launch(new String[]{"application/x-subrip","text/*","application/octet-stream"});break;case 10:audioSyncMenu();break;case 19:subtitleSyncMenu();break;case 11:abRepeatMenu();break;case 12:stepFrame();break;case 13:showDiagnostics();break;case 14:audioCleanupMenu();break;case 15:autoPip=!autoPip;preferences.edit().putBoolean("auto_pip",autoPip).apply();break;case 16:subtitleStyleMenu();break;case 17:playlistMenu();break;case 18:analyzeWaveform();break;}return true;});m.show();
    }
    private void analyzeWaveform(){
        waveformEnabled=!waveformEnabled;
        preferences.edit().putBoolean("waveform_enabled",waveformEnabled).apply();
        if(waveform==null)return;
        if(!waveformEnabled){waveform.setVisibility(View.GONE);waveform.clearAnalysis();Toast.makeText(this,"Audio waveform off",Toast.LENGTH_SHORT).show();return;}
        showWaveformOverlay();
        Toast.makeText(this,"Audio waveform on",Toast.LENGTH_SHORT).show();
        resumeAfterWaveform=player.isPlaying();
        if(resumeAfterWaveform)player.pause();
        Toast.makeText(this,"Analyzing audio waveform…",Toast.LENGTH_SHORT).show();
        loadWaveform();
    }
    private void soundProtectionMenu(){
        String[] modes={"Off","Low","Medium","Strong"};
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Sudden sound protection").setSingleChoiceItems(modes,soundProtection,(dialog,which)->{
            long position=Math.max(0,player.getTime());soundProtection=which;preferences.edit().putInt("sound_protection",which).apply();pendingSeek=position;
            player.stop();softwareRetryAttempted=false;startPlayback(true);dialog.dismiss();Toast.makeText(this,"Sound protection: "+modes[which],Toast.LENGTH_SHORT).show();
        }).show();
    }
    private void audioCleanupMenu(){
        ensureEqualizerSettings();
        final int surface=0xFF0B1220, textPrimary=0xFFF8FAFC, textSecondary=0xFF94A3B8, accent=0xFF22D3EE;

        TextView dialogTitle=label("Audio equalizer",20);
        dialogTitle.setTextColor(textPrimary);
        dialogTitle.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD);
        dialogTitle.setPadding(dp(22),dp(20),dp(22),dp(12));
        dialogTitle.setBackgroundColor(surface);

        LinearLayout panel=new LinearLayout(this);
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(18),dp(6),dp(18),dp(28));
        panel.setBackgroundColor(surface);

        TextView note=label("Adjust each frequency separately (−20 to +20 dB)",13);
        note.setTextColor(textSecondary);
        note.setGravity(Gravity.CENTER_VERTICAL);
        panel.addView(note,new LinearLayout.LayoutParams(-1,dp(42)));

        SeekBar[] sliders=new SeekBar[equalizerGains.length+1];
        TextView[] values=new TextView[sliders.length];
        addEqualizerSlider(panel,"PREAMP",equalizerPreamp,sliders,values,0);
        for(int i=0;i<equalizerGains.length;i++){
            addEqualizerSlider(panel,formatFrequency(MediaPlayer.Equalizer.getBandFrequency(i)),equalizerGains[i],sliders,values,i+1);
        }

        android.widget.ScrollView scroll=new android.widget.ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(surface);
        scroll.addView(panel,new android.widget.ScrollView.LayoutParams(-1,-2));

        androidx.appcompat.app.AlertDialog dialog=new androidx.appcompat.app.AlertDialog.Builder(this)
                .setCustomTitle(dialogTitle).setView(scroll)
                .setNegativeButton("OFF",null).setNeutralButton("RESET",null).setPositiveButton("APPLY",null).create();

        dialog.setOnShowListener(ignored->{
            android.view.Window window=dialog.getWindow();
            if(window!=null){
                android.graphics.drawable.GradientDrawable background=new android.graphics.drawable.GradientDrawable();
                background.setColor(surface);
                background.setCornerRadius(dp(18));
                window.setBackgroundDrawable(background);
                android.util.DisplayMetrics metrics=getResources().getDisplayMetrics();
                int maxWidth=Math.min(metrics.widthPixels-dp(28),dp(620));
                int maxHeight=Math.min(metrics.heightPixels-dp(28),(int)(metrics.heightPixels*0.88f));
                window.setLayout(maxWidth,maxHeight);
            }

            int[] buttonIds={
                    androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE,
                    androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL,
                    androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE
            };
            for(int buttonId:buttonIds){
                android.widget.Button button=dialog.getButton(buttonId);
                button.setTextColor(accent);
                button.setAllCaps(true);
                button.setMinHeight(dp(52));
            }

            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                equalizerPreamp=progressToGain(sliders[0].getProgress());
                for(int i=0;i<equalizerGains.length;i++)equalizerGains[i]=progressToGain(sliders[i+1].getProgress());
                equalizerEnabled=true;saveEqualizerSettings();applyAudioCleanup();dialog.dismiss();
            });
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->{
                equalizerEnabled=false;saveEqualizerSettings();applyAudioCleanup();dialog.dismiss();
            });
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{
                for(SeekBar slider:sliders)slider.setProgress(200);
            });
        });
        dialog.show();
    }
    private void addEqualizerSlider(LinearLayout panel,String name,float gain,SeekBar[] sliders,TextView[] values,int index){
        LinearLayout heading=new LinearLayout(this);
        heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView band=label(name,13);
        band.setTextColor(0xFFF8FAFC);
        band.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);
        heading.addView(band,new LinearLayout.LayoutParams(0,dp(30),1));
        TextView value=label(gainLabel(gain),13);
        value.setTextColor(0xFF22D3EE);
        value.setGravity(Gravity.END|Gravity.CENTER_VERTICAL);
        heading.addView(value,new LinearLayout.LayoutParams(dp(82),dp(30)));
        panel.addView(heading);

        SeekBar slider=new SeekBar(this);
        slider.setMax(400);
        slider.setProgress(gainToProgress(gain));
        if(android.os.Build.VERSION.SDK_INT>=21){
            slider.setProgressTintList(android.content.res.ColorStateList.valueOf(0xFF14B8A6));
            slider.setThumbTintList(android.content.res.ColorStateList.valueOf(0xFF22D3EE));
            slider.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(0xFF334155));
        }
        slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onProgressChanged(SeekBar s,int p,boolean from){value.setText(gainLabel(progressToGain(p)));}
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){}
        });
        LinearLayout.LayoutParams sliderParams=new LinearLayout.LayoutParams(-1,dp(42));
        sliderParams.setMargins(0,0,0,dp(6));
        panel.addView(slider,sliderParams);
        sliders[index]=slider;
        values[index]=value;
    }
    private void ensureEqualizerSettings(){int count=MediaPlayer.Equalizer.getBandCount();if(equalizerGains!=null&&equalizerGains.length==count)return;equalizerGains=new float[count];for(int i=0;i<count;i++)equalizerGains[i]=preferences.getFloat("equalizer_band_"+i,0f);}
    private void saveEqualizerSettings(){SharedPreferences.Editor editor=preferences.edit().putBoolean("equalizer_enabled",equalizerEnabled).putFloat("equalizer_preamp",equalizerPreamp).putInt("audio_cleanup",equalizerEnabled?1:0);for(int i=0;i<equalizerGains.length;i++)editor.putFloat("equalizer_band_"+i,equalizerGains[i]);editor.apply();audioCleanup=equalizerEnabled?1:0;}
    private int gainToProgress(float gain){return Math.max(0,Math.min(400,Math.round((gain+20f)*10f)));}
    private float progressToGain(int progress){return progress/10f-20f;}
    private String gainLabel(float gain){return String.format(Locale.US,"%+.1f dB",gain);}
    private String formatFrequency(float hz){return hz>=1000?String.format(Locale.US,"%.1f kHz",hz/1000f):String.format(Locale.US,"%.0f Hz",hz);}
    private void applyAudioCleanup(){
        if(cleanupEqualizer!=null){player.setEqualizer(null);cleanupEqualizer=null;}
        if(!equalizerEnabled){player.setEqualizer(null);return;}
        ensureEqualizerSettings();cleanupEqualizer=MediaPlayer.Equalizer.create();cleanupEqualizer.setPreAmp(equalizerPreamp);
        for(int i=0;i<equalizerGains.length;i++)cleanupEqualizer.setAmp(i,equalizerGains[i]);
        player.setEqualizer(cleanupEqualizer);
    }
    private void toggleHeadphoneSafety(){headphoneSafety=!headphoneSafety;preferences.edit().putBoolean("headphone_safety",headphoneSafety).apply();Toast.makeText(this,"Headphone safety: "+(headphoneSafety?"On":"Off"),Toast.LENGTH_SHORT).show();if(headphoneSafety)applySafeStart();}
    private void stepFrame(){if(player.isPlaying())player.pause();player.setTime(Math.max(0,player.getTime()+40));}
    private void audioSyncMenu(){
        String[] items={"− 50 ms","+ 50 ms","Reset to 0 ms"};
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Audio sync · "+signedMs(audioDelay)).setItems(items,(d,n)->{if(n==0)audioDelay-=50000;else if(n==1)audioDelay+=50000;else audioDelay=0;player.setAudioDelay(audioDelay);Toast.makeText(this,"Audio sync "+signedMs(audioDelay),Toast.LENGTH_SHORT).show();}).show();
    }
    private void subtitleSyncMenu(){
        String[] items={"− 50 ms","+ 50 ms","Reset to 0 ms"};
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Subtitle sync · "+signedMs(subtitleDelay)).setItems(items,(d,n)->{if(n==0)subtitleDelay-=50000;else if(n==1)subtitleDelay+=50000;else subtitleDelay=0;player.setSpuDelay(subtitleDelay);Toast.makeText(this,"Subtitle sync "+signedMs(subtitleDelay),Toast.LENGTH_SHORT).show();}).show();
    }
    private String signedMs(long microseconds){long milliseconds=microseconds/1000;return (milliseconds>0?"+":"")+milliseconds+" ms";}
    private void abRepeatMenu(){String[] items={pointA<0?"Set point A":"Reset point A ("+clock(pointA)+")",pointA>=0?"Set point B":"Set point A first","Clear A–B repeat"};new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("A–B repeat").setItems(items,(d,n)->{if(n==0){pointA=player.getTime();pointB=-1;Toast.makeText(this,"Point A: "+clock(pointA),Toast.LENGTH_SHORT).show();}else if(n==1&&pointA>=0){pointB=player.getTime();if(pointB<=pointA){pointA=pointB;pointB=-1;Toast.makeText(this,"Point A moved. Now set point B",Toast.LENGTH_SHORT).show();}else Toast.makeText(this,"A–B repeat active",Toast.LENGTH_SHORT).show();}else if(n==2){pointA=pointB=-1;Toast.makeText(this,"A–B repeat cleared",Toast.LENGTH_SHORT).show();}}).show();}
    private void showDiagnostics(){String message="File: "+currentTitle()+"\nURI: "+sourceUri+"\nDuration: "+clock(player.getLength())+"\nPosition: "+clock(player.getTime())+"\nDecoder fallback: "+(softwareRetryAttempted?"Software":"Hardware")+"\nAudio protection: "+soundProtection+"\nAudio cleanup: "+audioCleanup+"\nAudio delay: "+audioDelay/1000+" ms\nSubtitle delay: "+subtitleDelay/1000+" ms";new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Playback diagnostics").setMessage(message).setPositiveButton("Copy",(d,n)->{android.content.ClipboardManager c=(android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE);c.setPrimaryClip(android.content.ClipData.newPlainText("NicePlayer diagnostics",message));}).setNegativeButton("Close",null).show();}
    private void audioTracks(){
        MediaPlayer.TrackDescription[] tracks=player.getAudioTracks();
        if(tracks==null||tracks.length==0){Toast.makeText(this,"No audio tracks found",Toast.LENGTH_SHORT).show();return;}
        String[] names=new String[tracks.length];for(int i=0;i<tracks.length;i++)names[i]=tracks[i].name;
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Audio track").setItems(names,(d,n)->player.setAudioTrack(tracks[n].id)).show();
    }
    private void subtitleStyleMenu(){String[] styles={"Default white","Small white","Large yellow","Extra large + raised"};new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Subtitle appearance").setSingleChoiceItems(styles,subtitleStyle,(d,n)->{subtitleStyle=n;preferences.edit().putInt("subtitle_style",n).apply();pendingSeek=Math.max(0,player.getTime());player.stop();startPlayback(true);d.dismiss();}).show();}
    private void playlistMenu(){String[] names=new String[playlistUris.size()];for(int i=0;i<names.length;i++)names[i]=i<playlistTitles.size()?playlistTitles.get(i):"Video "+(i+1);new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Playlist").setSingleChoiceItems(names,playlistIndex,(d,n)->{playAt(n);d.dismiss();}).show();}
    private void subtitleTracks(){
        MediaPlayer.TrackDescription[] tracks=player.getSpuTracks();
        int count=tracks==null?0:tracks.length;String[] names=new String[count+1];names[0]="Off";
        for(int i=0;i<count;i++)names[i+1]=tracks[i].name;
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Subtitles").setItems(names,(d,n)->{if(n==0)player.setSpuTrack(-1);else player.setSpuTrack(tracks[n-1].id);}).show();
    }
    private void sleepTimer(){
        String[] names={"15 minutes","30 minutes","60 minutes","90 minutes","Cancel timer"};int[] minutes={15,30,60,90,0};
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Sleep timer").setItems(names,(d,n)->{handler.removeCallbacksAndMessages("sleep");if(minutes[n]>0){Runnable stop=()->{if(player!=null)player.pause();Toast.makeText(this,"Sleep timer finished",Toast.LENGTH_LONG).show();};handler.postAtTime(stop,"sleep",SystemClock.uptimeMillis()+minutes[n]*60000L);Toast.makeText(this,"Timer set for "+names[n],Toast.LENGTH_SHORT).show();}else Toast.makeText(this,"Sleep timer cancelled",Toast.LENGTH_SHORT).show();}).show();
    }
    private void setPipOverlayState(View view,boolean hidden){
        if(view==null)return;
        view.animate().cancel();
        view.setAlpha(hidden?0f:1f);
        view.setVisibility(hidden?View.INVISIBLE:View.VISIBLE);
    }
    private void hidePlayerOverlaysForPip(){
        setPipOverlayState(top,true);
        setPipOverlayState(bottom,true);
        setPipOverlayState((View)quickTools.getTag(),true);
        setPipOverlayState(lock,true);
        setPipOverlayState(screenshotButton,true);
        setPipOverlayState(hint,true);
        if(waveform!=null)setPipOverlayState(waveform,true);
        root.requestLayout();
        root.invalidate();
    }
    private void restorePlayerOverlaysAfterPipFailure(){
        if(locked)return;
        int visibility=controls?View.VISIBLE:View.GONE;
        top.setAlpha(1f);bottom.setAlpha(1f);((View)quickTools.getTag()).setAlpha(1f);lock.setAlpha(1f);
        screenshotButton.setAlpha(.5f);
        top.setVisibility(visibility);bottom.setVisibility(visibility);
        ((View)quickTools.getTag()).setVisibility(visibility);
        lock.setVisibility(visibility);screenshotButton.setVisibility(visibility);
        if(waveform!=null){waveform.setAlpha(1f);waveform.setVisibility(waveformEnabled?View.VISIBLE:View.GONE);}
    }
    private void enterPip(){
        if(Build.VERSION.SDK_INT<26||pipTransitionPending||isInPictureInPictureMode())return;
        pipPlaybackPosition=player==null?-1:Math.max(0,player.getTime());
        pipWasPlaying=player!=null&&player.isPlaying();
        savePositionImmediately();
        pipTransitionPending=true;
        hidePlayerOverlaysForPip();
        final android.view.ViewTreeObserver.OnPreDrawListener[] listener=new android.view.ViewTreeObserver.OnPreDrawListener[1];
        listener[0]=()->{
            if(root.getViewTreeObserver().isAlive())root.getViewTreeObserver().removeOnPreDrawListener(listener[0]);
            root.postOnAnimation(()->root.postOnAnimation(()->{
                PictureInPictureParams.Builder builder=new PictureInPictureParams.Builder().setAspectRatio(new Rational(16,9));
                if(Build.VERSION.SDK_INT>=31)builder.setSeamlessResizeEnabled(false);
                boolean entered=enterPictureInPictureMode(builder.build());
                if(!entered){pipTransitionPending=false;restorePlayerOverlaysAfterPipFailure();}
            }));
            return true;
        };
        root.getViewTreeObserver().addOnPreDrawListener(listener[0]);
    }
    private void createPreviewSheet(){
        Toast.makeText(this,"Creating preview sheet…",Toast.LENGTH_SHORT).show();Uri uri=sourceUri;String displayName=currentTitle();
        worker.execute(()->{Bitmap sheet=null;try(ParcelFileDescriptor fd=getContentResolver().openFileDescriptor(uri,"r")){
            if(fd==null)throw new FileNotFoundException();MediaMetadataRetriever r=new MediaMetadataRetriever();r.setDataSource(fd.getFileDescriptor());
            long durationMs=Long.parseLong(Objects.requireNonNull(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION))),durationUs=durationMs*1000L;
            String width=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH),height=r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            long fileSize=queryFileSize(uri);String safeName=displayName.length()>68?displayName.substring(0,65)+"…":displayName;
            sheet=Bitmap.createBitmap(960,640,Bitmap.Config.ARGB_8888);Canvas c=new Canvas(sheet);c.drawColor(Color.BLACK);Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);paint.setColor(Color.WHITE);
            paint.setTextSize(25);c.drawText(safeName,22,34,paint);paint.setTextSize(20);paint.setColor(0xFFB8C4D8);
            c.drawText("Resolution: "+(width==null?"Unknown":width+" × "+height)+"     Duration: "+clock(durationMs)+"     Size: "+formatBytes(fileSize),22,70,paint);
            paint.setColor(Color.WHITE);paint.setTextSize(22);
            for(int i=0;i<9;i++){long at=durationUs*(i+1)/10;Bitmap frame=r.getFrameAtTime(at,MediaMetadataRetriever.OPTION_CLOSEST_SYNC);if(frame!=null){int x=(i%3)*320,y=100+(i/3)*180;c.drawBitmap(frame,null,new android.graphics.Rect(x,y,x+320,y+180),paint);c.drawText(clock(at/1000),x+8,y+170,paint);frame.recycle();}}
            r.release();Bitmap result=sheet;runOnUiThread(()->save(result));
        }catch(Exception error){Log.e(TAG,"Preview failed",error);if(sheet!=null)sheet.recycle();runOnUiThread(()->Toast.makeText(this,"Could not create preview sheet",Toast.LENGTH_LONG).show());}});
    }
    private long queryFileSize(Uri uri){try(android.database.Cursor c=getContentResolver().query(uri,new String[]{OpenableColumns.SIZE},null,null,null)){if(c!=null&&c.moveToFirst())return c.getLong(0);}catch(Exception ignored){}return -1;}
    private String formatBytes(long bytes){if(bytes<0)return "Unknown";if(bytes<1024)return bytes+" B";double value=bytes;String[] units={"B","KB","MB","GB"};int unit=0;while(value>=1024&&unit<units.length-1){value/=1024;unit++;}return String.format(Locale.US,"%.1f %s",value,units[unit]);}
    private TextView label(String s,int size){TextView v=new TextView(this);v.setText(s==null?"":s);v.setTextColor(Color.WHITE);v.setTextSize(size);v.setGravity(Gravity.CENTER);v.setPadding(dp(3),0,dp(3),0);return v;}
    private String clock(long ms){long t=Math.max(0,ms/1000),h=t/3600,m=(t%3600)/60,s=t%60;return h>0?String.format(Locale.US,"%d:%02d:%02d",h,m,s):String.format(Locale.US,"%02d:%02d",m,s);}
    private void rotate(){int o=getResources().getConfiguration().orientation;setRequestedOrientation(o==android.content.res.Configuration.ORIENTATION_LANDSCAPE?ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT:ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);}
    private void toggleOrientationLock(TextView control){
        orientationLocked=!orientationLocked;
        preferences.edit().putBoolean("orientation_locked",orientationLocked).apply();
        if(orientationLocked){
            applyOrientationLock();
            control.setText("ROTATION\nLOCKED");Toast.makeText(this,"Screen rotation locked",Toast.LENGTH_SHORT).show();
        }else{
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
            control.setText("ROTATION\nLOCK");Toast.makeText(this,"Auto-rotate enabled",Toast.LENGTH_SHORT).show();
        }
    }
    private void applyOrientationLock(){
        int orientation=getResources().getConfiguration().orientation;
        setRequestedOrientation(orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE?ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        if(rotationLockControl!=null)rotationLockControl.setText("ROTATION\nLOCKED");
    }
    private void screenshot(){
        View videoSurface=findVideoSurface(video);
        if(videoSurface==null||videoSurface.getWidth()<=0||videoSurface.getHeight()<=0){Toast.makeText(this,"Video frame is not ready",Toast.LENGTH_SHORT).show();return;}
        Bitmap bitmap=Bitmap.createBitmap(videoSurface.getWidth(),videoSurface.getHeight(),Bitmap.Config.ARGB_8888);
        if(videoSurface instanceof TextureView){
            Bitmap frame=((TextureView)videoSurface).getBitmap(bitmap);
            if(frame!=null)saveVideoFrame(frame);else{bitmap.recycle();Toast.makeText(this,"Screenshot failed",Toast.LENGTH_SHORT).show();}
            return;
        }
        if(Build.VERSION.SDK_INT>=24&&videoSurface instanceof SurfaceView){
            PixelCopy.request((SurfaceView)videoSurface,bitmap,result->{if(result==PixelCopy.SUCCESS)saveVideoFrame(bitmap);else{bitmap.recycle();Toast.makeText(this,"Screenshot failed",Toast.LENGTH_SHORT).show();}},handler);
            return;
        }
        bitmap.recycle();Toast.makeText(this,"Video-only screenshot is unavailable on this device",Toast.LENGTH_SHORT).show();
    }
    private View findVideoSurface(View view){
        if(view instanceof SurfaceView||view instanceof TextureView)return view;
        if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++){View found=findVideoSurface(group.getChildAt(i));if(found!=null)return found;}}
        return null;
    }
    private void saveVideoFrame(Bitmap surfaceBitmap){
        Bitmap frame=cropToVideoContent(surfaceBitmap);
        if(frame!=surfaceBitmap)surfaceBitmap.recycle();
        save(frame);
    }
    private Bitmap cropToVideoContent(Bitmap bitmap){
        MediaMetadataRetriever retriever=new MediaMetadataRetriever();
        try{
            if("content".equalsIgnoreCase(sourceUri.getScheme())){
                try(ParcelFileDescriptor descriptor=getContentResolver().openFileDescriptor(sourceUri,"r")){
                    if(descriptor==null)return bitmap;
                    retriever.setDataSource(descriptor.getFileDescriptor());
                    return cropToMediaAspect(bitmap,retriever);
                }
            }
            retriever.setDataSource(this,sourceUri);
            return cropToMediaAspect(bitmap,retriever);
        }catch(Exception error){Log.w(TAG,"Could not crop screenshot to video frame",error);return bitmap;}
        finally{try{retriever.release();}catch(Exception ignored){}}
    }
    private Bitmap cropToMediaAspect(Bitmap bitmap,MediaMetadataRetriever retriever){
        String widthText=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
        String heightText=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
        String rotationText=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
        if(widthText==null||heightText==null)return bitmap;
        int mediaWidth=Integer.parseInt(widthText),mediaHeight=Integer.parseInt(heightText);
        int rotation=rotationText==null?0:Integer.parseInt(rotationText);
        if(rotation==90||rotation==270){int swap=mediaWidth;mediaWidth=mediaHeight;mediaHeight=swap;}
        if(mediaWidth<=0||mediaHeight<=0)return bitmap;
        float mediaAspect=mediaWidth/(float)mediaHeight,surfaceAspect=bitmap.getWidth()/(float)bitmap.getHeight();
        int cropWidth=bitmap.getWidth(),cropHeight=bitmap.getHeight();
        if(surfaceAspect>mediaAspect)cropWidth=Math.round(cropHeight*mediaAspect);
        else cropHeight=Math.round(cropWidth/mediaAspect);
        int left=Math.max(0,(bitmap.getWidth()-cropWidth)/2),top=Math.max(0,(bitmap.getHeight()-cropHeight)/2);
        if(cropWidth==bitmap.getWidth()&&cropHeight==bitmap.getHeight())return bitmap;
        return Bitmap.createBitmap(bitmap,left,top,cropWidth,cropHeight);
    }
    private void save(Bitmap b){try{ContentValues v=new ContentValues();v.put(MediaStore.Images.Media.DISPLAY_NAME,"NicePlayer_"+System.currentTimeMillis()+".jpg");v.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");if(Build.VERSION.SDK_INT>=29)v.put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/NicePlayer");Uri u=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,v);if(u==null)throw new Exception();try(OutputStream s=getContentResolver().openOutputStream(u)){if(s==null||!b.compress(Bitmap.CompressFormat.JPEG,94,s))throw new Exception();}Toast.makeText(this,"Screenshot saved",Toast.LENGTH_SHORT).show();}catch(Exception e){Toast.makeText(this,"Could not save screenshot",Toast.LENGTH_LONG).show();}finally{b.recycle();}}
    private void immersive(){getWindow().getDecorView().setSystemUiVisibility(5894|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);}
    @Override protected void onPause(){savePositionImmediately();surfaceRefreshPending=true;super.onPause();}
    @Override protected void onResume(){
        super.onResume();
        if(!surfaceRefreshPending||player==null||video==null)return;
        surfaceRefreshPending=false;
        handler.postDelayed(()->{
            if(player==null||video==null||isFinishing())return;
            long position=Math.max(0,player.getTime());boolean wasPlaying=player.isPlaying();
            try{player.detachViews();player.attachViews(video,null,false,false);player.setTime(position);if(wasPlaying&&!player.isPlaying())player.play();video.requestLayout();video.invalidate();}
            catch(Exception error){Log.w(TAG,"Video surface refresh failed",error);}
        },120);
    }
    @Override protected void onUserLeaveHint(){super.onUserLeaveHint();if(autoPip&&Build.VERSION.SDK_INT>=26&&player!=null&&player.isPlaying()&&!isInPictureInPictureMode())enterPip();}
    @Override public void onPictureInPictureModeChanged(boolean inPictureInPictureMode, android.content.res.Configuration newConfig){
        super.onPictureInPictureModeChanged(inPictureInPictureMode,newConfig);
        pipTransitionPending=false;
        if(inPictureInPictureMode){
            hidePlayerOverlaysForPip();
            if(player!=null&&pipPlaybackPosition>0&&player.getTime()+1500<pipPlaybackPosition){
                player.setTime(pipPlaybackPosition);
            }
            if(player!=null&&pipWasPlaying&&!player.isPlaying())player.play();
        }else if(!locked){
            restorePlayerOverlaysAfterPipFailure();
        }
    }
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);worker.shutdownNow();try{unregisterReceiver(noisyReceiver);}catch(Exception ignored){}try{unregisterReceiver(playbackReceiver);}catch(Exception ignored){}stopService(new Intent(this,PlaybackService.class));if(Build.VERSION.SDK_INT>=26&&focusRequest!=null)audioManager.abandonAudioFocusRequest(focusRequest);if(cleanupEqualizer!=null&&player!=null){player.setEqualizer(null);cleanupEqualizer=null;}if(player!=null){player.stop();player.detachViews();player.release();player=null;}closeSourceDescriptor();if(vlc!=null){vlc.release();vlc=null;}super.onDestroy();}
    private class GestureLevelView extends View{
        private final Paint levelPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
        private float level;
        private boolean volume;
        GestureLevelView(Context context){super(context);levelPaint.setStrokeCap(Paint.Cap.ROUND);levelPaint.setStrokeJoin(Paint.Join.ROUND);}
        void setLevel(float value,boolean volumeIndicator){level=value;volume=volumeIndicator;invalidate();}
        @Override protected void onDraw(Canvas canvas){
            float cx=getWidth()/2f,barHalf=dp(4),barBottom=getHeight()-dp(34),radius=barHalf;
            levelPaint.setStyle(Paint.Style.FILL);levelPaint.setColor(0x55FFFFFF);
            canvas.drawRoundRect(cx-barHalf,0,cx+barHalf,barBottom,radius,radius,levelPaint);
            levelPaint.setColor(0xE6FFFFFF);float top=barBottom-(barBottom*level);
            canvas.drawRoundRect(cx-barHalf,top,cx+barHalf,barBottom,radius,radius,levelPaint);
            float iy=getHeight()-dp(15);levelPaint.setColor(0xEFFFFFFF);levelPaint.setStrokeWidth(dp(2));levelPaint.setStyle(Paint.Style.STROKE);
            if(volume){
                android.graphics.Path speaker=new android.graphics.Path();
                speaker.moveTo(cx-dp(10),iy-dp(3));speaker.lineTo(cx-dp(6),iy-dp(3));speaker.lineTo(cx-dp(1),iy-dp(8));
                speaker.lineTo(cx-dp(1),iy+dp(8));speaker.lineTo(cx-dp(6),iy+dp(3));speaker.lineTo(cx-dp(10),iy+dp(3));speaker.close();
                levelPaint.setStyle(Paint.Style.FILL);canvas.drawPath(speaker,levelPaint);levelPaint.setStyle(Paint.Style.STROKE);
                canvas.drawArc(cx-dp(2),iy-dp(7),cx+dp(8),iy+dp(7),-55,110,false,levelPaint);
                canvas.drawArc(cx-dp(2),iy-dp(11),cx+dp(14),iy+dp(11),-48,96,false,levelPaint);
            }else{
                canvas.drawCircle(cx,iy,dp(5),levelPaint);
                for(int i=0;i<8;i++){double a=i*Math.PI/4;float x1=cx+(float)Math.cos(a)*dp(8),y1=iy+(float)Math.sin(a)*dp(8);float x2=cx+(float)Math.cos(a)*dp(11),y2=iy+(float)Math.sin(a)*dp(11);canvas.drawLine(x1,y1,x2,y2,levelPaint);}
            }
        }
    }

    private class PlaybackControlView extends TextView{
        private final Paint iconPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
        PlaybackControlView(Context context,String action){
            super(context);setText(action);setGravity(Gravity.CENTER);setContentDescription(action);
            iconPaint.setColor(Color.WHITE);iconPaint.setStrokeCap(Paint.Cap.ROUND);iconPaint.setStrokeJoin(Paint.Join.ROUND);
        }
        @Override protected void onDraw(Canvas canvas){
            float cx=getWidth()/2f,cy=getHeight()/2f,u=Math.min(getWidth(),getHeight())/48f;
            String action=getText().toString();
            if(action.equals("▶")||action.equals("Ⅱ"))u*=1.15f;
            iconPaint.setColor(Color.WHITE);iconPaint.setStyle(Paint.Style.STROKE);iconPaint.setStrokeWidth(3.1f*u);
            if(action.contains("10")){
                boolean backward=action.startsWith("↶");
                float radius=12*u;
                canvas.drawArc(cx-radius,cy-radius,cx+radius,cy+radius,backward?220f:-40f,backward?-280f:280f,false,iconPaint);
                android.graphics.Path arrow=new android.graphics.Path();
                if(backward){
                    arrow.moveTo(cx-14*u,cy-7*u);arrow.lineTo(cx-6.5f*u,cy-10.5f*u);arrow.lineTo(cx-9*u,cy-2.5f*u);
                }else{
                    arrow.moveTo(cx+14*u,cy-7*u);arrow.lineTo(cx+6.5f*u,cy-10.5f*u);arrow.lineTo(cx+9*u,cy-2.5f*u);
                }
                arrow.close();iconPaint.setStyle(Paint.Style.FILL);canvas.drawPath(arrow,iconPaint);
                iconPaint.setTextAlign(Paint.Align.CENTER);iconPaint.setTextSize(15.5f*u);
                iconPaint.setTypeface(android.graphics.Typeface.create(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD));
                canvas.drawText("10",cx,cy+5.5f*u,iconPaint);
            }else if(action.equals("▶")||action.equals("Ⅱ")){
                iconPaint.setStyle(Paint.Style.FILL);
                if(action.equals("▶")){
                    android.graphics.Path p=new android.graphics.Path();
                    p.moveTo(cx-8*u,cy-13*u);p.lineTo(cx+12*u,cy);p.lineTo(cx-8*u,cy+13*u);p.close();canvas.drawPath(p,iconPaint);
                }else{
                    canvas.drawRoundRect(cx-9*u,cy-13*u,cx-2.5f*u,cy+13*u,2.5f*u,2.5f*u,iconPaint);
                    canvas.drawRoundRect(cx+2.5f*u,cy-13*u,cx+9*u,cy+13*u,2.5f*u,2.5f*u,iconPaint);
                }
            }else{
                boolean previous=action.startsWith("|");
                iconPaint.setStyle(Paint.Style.FILL);
                float barX=previous?cx-11*u:cx+11*u;
                canvas.drawRoundRect(barX-1.8f*u,cy-12*u,barX+1.8f*u,cy+12*u,1.8f*u,1.8f*u,iconPaint);
                android.graphics.Path p=new android.graphics.Path();
                if(previous){p.moveTo(cx+9*u,cy-12*u);p.lineTo(cx-7*u,cy);p.lineTo(cx+9*u,cy+12*u);}
                else{p.moveTo(cx-9*u,cy-12*u);p.lineTo(cx+7*u,cy);p.lineTo(cx-9*u,cy+12*u);}
                p.close();canvas.drawPath(p,iconPaint);
            }
        }
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
