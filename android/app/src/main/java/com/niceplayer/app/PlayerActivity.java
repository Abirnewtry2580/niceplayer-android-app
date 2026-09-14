package com.niceplayer.app;

import android.content.ContentValues;
import android.content.Context;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.app.PictureInPictureParams;
import android.app.RemoteAction;
import android.app.PendingIntent;
import android.graphics.drawable.Icon;
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
    private TextView play, time, hint, lock, title, screenshotButton;
    private boolean dragging, controls = true, locked, orientationLocked;
    private boolean softwareRetryAttempted;
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
    private float waveformDragStartX, waveformDragStartY, waveformStartX, waveformStartY;
    private long pointA = -1, pointB = -1, audioDelay, subtitleDelay;
    private boolean resumeAfterWaveform;
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

    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (player != null && !dragging) {
                long length = Math.max(0, player.getLength()), now = Math.max(0, player.getTime());
                seek.setMax((int)Math.min(Integer.MAX_VALUE, length));
                seek.setProgress((int)Math.min(Integer.MAX_VALUE, now));
                if(waveform!=null&&waveformEnabled&&length>0)waveform.setTimeline(now,length);
                time.setText(clock(now) + "  /  " + clock(length));
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
        soundProtection = preferences.getInt("sound_protection", 0);
        audioCleanup = preferences.getInt("audio_cleanup", 0);
        equalizerEnabled = preferences.getBoolean("equalizer_enabled", audioCleanup > 0);
        equalizerPreamp = preferences.getFloat("equalizer_preamp", 0f);
        subtitleStyle = preferences.getInt("subtitle_style", 0);
        headphoneSafety = preferences.getBoolean("headphone_safety", true);
        autoPip = preferences.getBoolean("auto_pip", true);
        waveformEnabled = preferences.getBoolean("waveform_enabled", false);
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
            else if (e.type == MediaPlayer.Event.Playing) { restorePosition();recordHistory();player.setRate(selectedRate);applyStoredRatio();applyAudioCleanup();applySafeStart();startPlaybackService(); }
            else if (e.type == MediaPlayer.Event.EndReached) playAt(playlistIndex + 1);
        }));
        if (!startPlayback(true)) {
            Toast.makeText(this, "Could not open this video", Toast.LENGTH_LONG).show();
            finish();
            return;
        }
        if(waveform!=null)waveform.setLevels(null);
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
        closeSourceDescriptor();
        try {
            Media media;
            if ("content".equalsIgnoreCase(sourceUri.getScheme())) {
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
        Toast.makeText(this, "This video is damaged or uses an unsupported codec", Toast.LENGTH_LONG).show();
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
        if (pendingSeek >= 0) {
            player.setTime(pendingSeek);
            pendingSeek = -1;
            return;
        }
        long saved = preferences.getLong(positionKey(), 0);
        long length = player.getLength();
        if (saved > 5000 && (length <= 0 || saved < length - 10000)) player.setTime(saved);
        preferences.edit().remove(positionKey()).apply();
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
    private void recordHistory(){String uri=sourceUri.toString(),name=currentTitle();ArrayList<String> uris=new ArrayList<>(),names=new ArrayList<>();uris.add(uri);names.add(name);for(int i=0;i<12;i++){String old=preferences.getString("history_uri_"+i,null);if(old!=null&&!old.equals(uri)){uris.add(old);names.add(preferences.getString("history_name_"+i,"Video"));if(uris.size()==12)break;}}SharedPreferences.Editor e=preferences.edit();for(int i=0;i<12;i++){if(i<uris.size()){e.putString("history_uri_"+i,uris.get(i));e.putString("history_name_"+i,names.get(i));}else{e.remove("history_uri_"+i);e.remove("history_name_"+i);}}e.apply();}

    private void playAt(int index) {
        if (index < 0 || index >= playlistUris.size()) {
            Toast.makeText(this, index < 0 ? "This is the first video" : "Playlist finished", Toast.LENGTH_SHORT).show();
            return;
        }
        savePosition();
        playlistIndex = index;
        sourceUri = Uri.parse(playlistUris.get(index));
        softwareRetryAttempted = false;
        title.setText(currentTitle());
        player.stop();
        resetVideoZoom(false);
        startPlayback(true);
        if(waveform!=null)waveform.setLevels(null);
        if(waveformEnabled)handler.postDelayed(this::loadWaveform,500);
    }

    private void makeUi() {
        root = new FrameLayout(this); root.setBackgroundColor(Color.BLACK);
        video = new VLCVideoLayout(this); root.addView(video, new FrameLayout.LayoutParams(-1, -1));
        hint = label("", 17); hint.setBackgroundColor(0xCC111827); hint.setPadding(dp(18),dp(10),dp(18),dp(10)); hint.setVisibility(View.GONE);
        root.addView(hint, new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER));

        GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onSingleTapConfirmed(MotionEvent e) { toggleControls(); return true; }
            @Override public boolean onDoubleTap(MotionEvent e) { if (!locked){if(zoomScale>1.01f)resetVideoZoom(true);else jump(e.getX() < root.getWidth()/2f ? -TEN_SECONDS : TEN_SECONDS);} return true; }
        });
        scaleDetector=new ScaleGestureDetector(this,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector d){
                zoomScale=Math.max(1f,Math.min(5f,zoomScale*d.getScaleFactor()));
                if(zoomScale<=1.01f){zoomScale=1f;videoPanX=videoPanY=0;}applyVideoZoom();
                hint.setText("Zoom  "+Math.round(zoomScale*100)+"%");hint.setVisibility(View.VISIBLE);return true;
            }
            @Override public void onScaleEnd(ScaleGestureDetector d){handler.postDelayed(()->hint.setVisibility(View.GONE),500);}
        });
        View gestures = new View(this);
        gestures.setOnTouchListener((v,e) -> gesture(e, detector));
        FrameLayout.LayoutParams gp = new FrameLayout.LayoutParams(-1,-1); gp.topMargin=dp(134); gp.bottomMargin=dp(118); root.addView(gestures,gp);
        makeTop();makeQuickTools();makeBottom();createFloatingWaveform();
        lock = label("🔓", 17);GradientDrawable lockBackground=new GradientDrawable();lockBackground.setShape(GradientDrawable.OVAL);lockBackground.setColor(0xCC111827);lock.setBackground(lockBackground);lock.setOnClickListener(v -> toggleLock());
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(dp(40),dp(40),Gravity.START|Gravity.BOTTOM); lp.leftMargin=dp(10); lp.bottomMargin=dp(38); root.addView(lock,lp);
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
    private TextView topCircle(String text,int size){TextView view=label(text,size);view.setShadowLayer(dp(3),0,dp(1),Color.BLACK);view.setBackgroundColor(Color.TRANSPARENT);return view;}
    private LinearLayout.LayoutParams topButtonParams(int size){LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(dp(size),dp(size));params.setMargins(dp(3),0,dp(3),0);return params;}

    private void makeQuickTools(){android.widget.HorizontalScrollView scroll=new android.widget.HorizontalScrollView(this);scroll.setHorizontalScrollBarEnabled(false);scroll.setBackgroundColor(Color.TRANSPARENT);quickTools=new LinearLayout(this);quickTools.setGravity(Gravity.CENTER_VERTICAL);quickTools.setPadding(dp(8),dp(5),dp(8),dp(5));
        addQuick("A↔B\nREPEAT",v->abRepeatMenu());addQuick("▣\nPOP-UP",v->enterPip());addQuick("≋\nCLEANUP",v->audioCleanupMenu());addQuick("↻\nROTATE",v->rotate());
        TextView rotationLock=addQuick("ROTATION\nLOCK",null);rotationLock.setOnClickListener(v->toggleOrientationLock(rotationLock));
        addQuick("VOL\nMUTE",v->{boolean mute=player.getVolume()>0;player.setVolume(mute?0:100);});addQuick("SAFE\nAUDIO",v->toggleHeadphoneSafety());TextView speed=addQuick("1×\nSPEED",null);speed.setOnClickListener(v->speedMenu(speed));
        scroll.addView(quickTools,new android.widget.HorizontalScrollView.LayoutParams(-2,-1));FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(-1,dp(70),Gravity.TOP);params.topMargin=dp(60);root.addView(scroll,params);quickTools.setTag(scroll);
    }
    private TextView addQuick(String text,View.OnClickListener click){TextView v=label(text,9);v.setLines(2);GradientDrawable background=new GradientDrawable();background.setShape(GradientDrawable.OVAL);background.setColor(0x7A111827);v.setBackground(background);if(click!=null)v.setOnClickListener(click);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(dp(58),dp(58));p.setMargins(dp(4),0,dp(4),0);quickTools.addView(v,p);return v;}

    private void makeBottom() {
        bottom=new LinearLayout(this); bottom.setOrientation(LinearLayout.VERTICAL); bottom.setPadding(dp(10),0,dp(10),dp(6)); bottom.setBackgroundColor(Color.TRANSPARENT);
        seek=new SeekBar(this); seek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar b){dragging=true;} public void onProgressChanged(SeekBar b,int n,boolean from){if(from)time.setText(clock(n)+"  /  "+clock(player.getLength()));}
            public void onStopTrackingTouch(SeekBar b){player.setTime(b.getProgress());dragging=false;}
        }); bottom.addView(seek,new LinearLayout.LayoutParams(-1,dp(30)));
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER);
        addControl(row,"|◀",21,v->playAt(playlistIndex-1)); addControl(row,"↶ 10",18,v->jump(-TEN_SECONDS));
        play=addControl(row,"▶",31,v->{if(player.isPlaying())player.pause();else player.play();});
        addControl(row,"10 ↷",18,v->jump(TEN_SECONDS));
        addControl(row,"▶|",21,v->playAt(playlistIndex+1));
        bottom.addView(row,new LinearLayout.LayoutParams(-1,dp(55)));
        time=label("00:00  /  00:00",12); time.setShadowLayer(dp(3),0,dp(1),Color.BLACK);time.setGravity(Gravity.START|Gravity.CENTER_VERTICAL); bottom.addView(time,new LinearLayout.LayoutParams(-1,dp(24)));
        root.addView(bottom,new FrameLayout.LayoutParams(-1,dp(115),Gravity.BOTTOM));
    }

    private void loadWaveform(){
        if(waveform==null||!waveformEnabled)return;
        waveform.setLevels(null);
        Uri uri=sourceUri;
        long duration=Math.max(0,player.getLength());
        int buckets=(int)Math.max(600,Math.min(72_000,duration/100L));
        worker.execute(()->AudioWaveformExtractor.extract(this,uri,buckets,new AudioWaveformExtractor.Callback(){
            public void complete(float[] levels){runOnUiThread(()->{if(uri.equals(sourceUri)&&waveform!=null&&waveformEnabled)waveform.setLevels(levels);finishWaveformAnalysis();});}
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

    private TextView addControl(LinearLayout row,String text,int size,View.OnClickListener click){
        FrameLayout slot=new FrameLayout(this);TextView v=label(text,size);v.setShadowLayer(dp(4),0,dp(1),Color.BLACK);
        GradientDrawable circle=new GradientDrawable();circle.setShape(GradientDrawable.OVAL);circle.setColor(Color.TRANSPARENT);circle.setStroke(dp(1),Color.TRANSPARENT);v.setBackground(circle);
        v.setOnTouchListener((buttonView,event)->{if(event.getActionMasked()==MotionEvent.ACTION_DOWN){circle.setColor(0x66000000);circle.setStroke(dp(1),0xCCFFFFFF);buttonView.invalidate();handler.postDelayed(()->{circle.setColor(Color.TRANSPARENT);circle.setStroke(dp(1),Color.TRANSPARENT);buttonView.invalidate();},500);}return false;});
        if(click!=null)v.setOnClickListener(click);FrameLayout.LayoutParams button=new FrameLayout.LayoutParams(dp(50),dp(50),Gravity.CENTER);slot.addView(v,button);row.addView(slot,new LinearLayout.LayoutParams(0,dp(55),1));return v;
    }

    private boolean gesture(MotionEvent e, GestureDetector detector) {
        scaleDetector.onTouchEvent(e);if(locked)return true;
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
                    hint.setText("Brightness  "+Math.round(p.screenBrightness*100)+"%");
                }else{
                    int max=audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC),limit=headphoneSafety&&headphonesActive()?Math.max(1,Math.round(max*.7f)):max,n=Math.max(0,Math.min(limit,startVolume+Math.round(change*max)));
                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC,n,0);hint.setText("Volume  "+Math.round(n*100f/max)+"%");
                }
                hint.setVisibility(View.VISIBLE);
            }
        }
        else if(e.getActionMasked()==MotionEvent.ACTION_UP||e.getActionMasked()==MotionEvent.ACTION_CANCEL){hint.setVisibility(View.GONE);dragging=false;}
        return true;
    }

    private void applyVideoZoom(){
        float maxX=root.getWidth()*(zoomScale-1f)/2f,maxY=root.getHeight()*(zoomScale-1f)/2f;
        videoPanX=Math.max(-maxX,Math.min(maxX,videoPanX));videoPanY=Math.max(-maxY,Math.min(maxY,videoPanY));
        video.setScaleX(zoomScale);video.setScaleY(zoomScale);video.setTranslationX(videoPanX);video.setTranslationY(videoPanY);
    }
    private void resetVideoZoom(boolean notify){zoomScale=1f;videoPanX=videoPanY=0;if(video!=null)applyVideoZoom();if(notify)Toast.makeText(this,"Zoom reset",Toast.LENGTH_SHORT).show();}

    private void jump(long amount){long len=Math.max(0,player.getLength());player.setTime(Math.max(0,Math.min(len,player.getTime()+amount)));hint.setText(amount>0?"+10 seconds":"−10 seconds");hint.setVisibility(View.VISIBLE);handler.postDelayed(()->hint.setVisibility(View.GONE),550);}
    private void speedMenu(TextView anchor){PopupMenu m=new PopupMenu(this,anchor);float[] s={.25f,.5f,.75f,1f,1.25f,1.5f,2f};for(int i=0;i<s.length;i++)m.getMenu().add(0,i,i,s[i]+"×");m.setOnMenuItemClickListener(x->{float n=s[x.getItemId()];selectedRate=n;preferences.edit().putFloat("playback_rate",n).apply();player.setRate(n);anchor.setText(n==1f?"1×":n+"×");return true;});m.show();}
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
        controls=!controls;
        int visibility=controls?View.VISIBLE:View.GONE;
        top.setVisibility(visibility);bottom.setVisibility(visibility);((View)quickTools.getTag()).setVisibility(visibility);lock.setVisibility(visibility);screenshotButton.setVisibility(visibility);
    }
    private void toggleLock(){
        locked=!locked;handler.removeCallbacks(hideLockButton);lock.setText(locked?"🔒":"🔓");
        if(locked){top.setVisibility(View.GONE);bottom.setVisibility(View.GONE);((View)quickTools.getTag()).setVisibility(View.GONE);screenshotButton.setVisibility(View.GONE);lock.setVisibility(View.VISIBLE);controls=false;handler.postDelayed(hideLockButton,2200);}
        else{top.setVisibility(View.VISIBLE);bottom.setVisibility(View.VISIBLE);((View)quickTools.getTag()).setVisibility(View.VISIBLE);screenshotButton.setVisibility(View.VISIBLE);lock.setVisibility(View.VISIBLE);controls=true;}
    }
    private void moreMenu(TextView anchor){
        PopupMenu m=new PopupMenu(this,anchor);
        m.getMenu().add(0,1,0,"Audio track");
        m.getMenu().add(0,2,1,"Subtitles");
        m.getMenu().add(0,3,2,"Sleep timer");
        m.getMenu().add(0,4,3,"Create preview sheet");
        m.getMenu().add(0,7,5,"Sudden sound protection");
        m.getMenu().add(0,14,6,"Audio cleanup");
        m.getMenu().add(0,8,7,"Headphone safety: "+(headphoneSafety?"On":"Off"));
        m.getMenu().add(0,9,8,"Add external subtitle");
        m.getMenu().add(0,10,9,"Audio / subtitle sync");
        m.getMenu().add(0,11,10,"A–B repeat");
        m.getMenu().add(0,12,11,"Next frame");
        m.getMenu().add(0,13,12,"Playback diagnostics");
        m.getMenu().add(0,16,13,"Subtitle appearance");
        m.getMenu().add(0,17,14,"Playlist");
        m.getMenu().add(0,18,15,"Audio waveform: "+(waveformEnabled?"On":"Off"));
        if(Build.VERSION.SDK_INT>=26){m.getMenu().add(0,6,16,"Picture in picture");m.getMenu().add(0,15,17,"Auto pop-up: "+(autoPip?"On":"Off"));}
        m.setOnMenuItemClickListener(x->{switch(x.getItemId()){case 1:audioTracks();break;case 2:subtitleTracks();break;case 3:sleepTimer();break;case 4:createPreviewSheet();break;case 6:enterPip();break;case 7:soundProtectionMenu();break;case 8:toggleHeadphoneSafety();break;case 9:subtitlePicker.launch(new String[]{"application/x-subrip","text/*","application/octet-stream"});break;case 10:syncMenu();break;case 11:abRepeatMenu();break;case 12:stepFrame();break;case 13:showDiagnostics();break;case 14:audioCleanupMenu();break;case 15:autoPip=!autoPip;preferences.edit().putBoolean("auto_pip",autoPip).apply();break;case 16:subtitleStyleMenu();break;case 17:playlistMenu();break;case 18:analyzeWaveform();break;}return true;});m.show();
    }
    private void analyzeWaveform(){
        waveformEnabled=!waveformEnabled;
        preferences.edit().putBoolean("waveform_enabled",waveformEnabled).apply();
        if(waveform==null)return;
        if(!waveformEnabled){waveform.setVisibility(View.GONE);waveform.setLevels(null);Toast.makeText(this,"Audio waveform off",Toast.LENGTH_SHORT).show();return;}
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
        LinearLayout panel=new LinearLayout(this);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(dp(18),dp(8),dp(18),dp(8));
        TextView note=label("Adjust each frequency separately (−20 to +20 dB)",13);note.setTextColor(0xFF222222);panel.addView(note,new LinearLayout.LayoutParams(-1,dp(38)));
        SeekBar[] sliders=new SeekBar[equalizerGains.length+1];TextView[] values=new TextView[sliders.length];
        addEqualizerSlider(panel,"PREAMP",equalizerPreamp,sliders,values,0);
        for(int i=0;i<equalizerGains.length;i++)addEqualizerSlider(panel,formatFrequency(MediaPlayer.Equalizer.getBandFrequency(i)),equalizerGains[i],sliders,values,i+1);
        android.widget.ScrollView scroll=new android.widget.ScrollView(this);scroll.addView(panel);
        androidx.appcompat.app.AlertDialog dialog=new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Audio equalizer").setView(scroll)
                .setNegativeButton("OFF",null).setNeutralButton("RESET",null).setPositiveButton("APPLY",null).create();
        dialog.setOnShowListener(ignored->{
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                equalizerPreamp=progressToGain(sliders[0].getProgress());
                for(int i=0;i<equalizerGains.length;i++)equalizerGains[i]=progressToGain(sliders[i+1].getProgress());
                equalizerEnabled=true;saveEqualizerSettings();applyAudioCleanup();dialog.dismiss();
            });
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->{equalizerEnabled=false;saveEqualizerSettings();applyAudioCleanup();dialog.dismiss();});
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{for(SeekBar slider:sliders)slider.setProgress(200);});
        });dialog.show();
    }
    private void addEqualizerSlider(LinearLayout panel,String name,float gain,SeekBar[] sliders,TextView[] values,int index){
        LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);
        TextView band=label(name,13);band.setTextColor(0xFF222222);band.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);heading.addView(band,new LinearLayout.LayoutParams(0,dp(28),1));
        TextView value=label(gainLabel(gain),13);value.setTextColor(0xFF006A72);heading.addView(value,new LinearLayout.LayoutParams(dp(72),dp(28)));panel.addView(heading);
        SeekBar slider=new SeekBar(this);slider.setMax(400);slider.setProgress(gainToProgress(gain));slider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar s,int p,boolean from){value.setText(gainLabel(progressToGain(p)));}public void onStartTrackingTouch(SeekBar s){}public void onStopTrackingTouch(SeekBar s){}});panel.addView(slider,new LinearLayout.LayoutParams(-1,dp(38)));sliders[index]=slider;values[index]=value;
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
    private void syncMenu(){
        String[] items={"Audio −50 ms","Audio +50 ms","Subtitle −100 ms","Subtitle +100 ms","Reset synchronization"};
        new androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Audio / subtitle sync").setItems(items,(d,n)->{if(n==0)audioDelay-=50000;else if(n==1)audioDelay+=50000;else if(n==2)subtitleDelay-=100000;else if(n==3)subtitleDelay+=100000;else{audioDelay=0;subtitleDelay=0;}player.setAudioDelay(audioDelay);player.setSpuDelay(subtitleDelay);Toast.makeText(this,"Audio "+audioDelay/1000+" ms · Subtitle "+subtitleDelay/1000+" ms",Toast.LENGTH_SHORT).show();}).show();
    }
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
        top.setVisibility(visibility);bottom.setVisibility(visibility);
        ((View)quickTools.getTag()).setVisibility(visibility);
        lock.setVisibility(visibility);screenshotButton.setVisibility(visibility);
        if(waveform!=null)waveform.setVisibility(waveformEnabled?View.VISIBLE:View.GONE);
    }
    private void enterPip(){
        if(Build.VERSION.SDK_INT<26||pipTransitionPending||isInPictureInPictureMode())return;
        pipTransitionPending=true;
        hidePlayerOverlaysForPip();
        final android.view.ViewTreeObserver.OnPreDrawListener[] listener=new android.view.ViewTreeObserver.OnPreDrawListener[1];
        listener[0]=()->{
            if(root.getViewTreeObserver().isAlive())root.getViewTreeObserver().removeOnPreDrawListener(listener[0]);
            root.postOnAnimation(()->root.postOnAnimation(()->{
                ArrayList<RemoteAction> actions=new ArrayList<>();
                actions.add(pipAction(PlaybackService.PREVIOUS,"Previous",android.R.drawable.ic_media_previous,21));
                actions.add(pipAction(PlaybackService.PLAY_PAUSE,"Play/Pause",android.R.drawable.ic_media_play,22));
                actions.add(pipAction(PlaybackService.NEXT,"Next",android.R.drawable.ic_media_next,23));
                PictureInPictureParams.Builder builder=new PictureInPictureParams.Builder().setAspectRatio(new Rational(16,9)).setActions(actions);
                if(Build.VERSION.SDK_INT>=31)builder.setSeamlessResizeEnabled(false);
                boolean entered=enterPictureInPictureMode(builder.build());
                if(!entered){pipTransitionPending=false;restorePlayerOverlaysAfterPipFailure();}
            }));
            return true;
        };
        root.getViewTreeObserver().addOnPreDrawListener(listener[0]);
    }
    private RemoteAction pipAction(String action,String label,int icon,int request){Intent i=new Intent(action).setPackage(getPackageName());PendingIntent p=PendingIntent.getBroadcast(this,request,i,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);return new RemoteAction(Icon.createWithResource(this,icon),label,label,p);}
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
        if(orientationLocked){
            int orientation=getResources().getConfiguration().orientation;
            setRequestedOrientation(orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE?ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE:ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
            control.setText("ROTATION\nLOCKED");Toast.makeText(this,"Screen rotation locked",Toast.LENGTH_SHORT).show();
        }else{
            setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR);
            control.setText("ROTATION\nLOCK");Toast.makeText(this,"Auto-rotate enabled",Toast.LENGTH_SHORT).show();
        }
    }
    private void screenshot(){if(Build.VERSION.SDK_INT<26){Toast.makeText(this,"Android 8 or newer required",Toast.LENGTH_SHORT).show();return;}Bitmap b=Bitmap.createBitmap(root.getWidth(),root.getHeight(),Bitmap.Config.ARGB_8888);PixelCopy.request(getWindow(),b,r->{if(r==PixelCopy.SUCCESS)save(b);else{b.recycle();Toast.makeText(this,"Screenshot failed",Toast.LENGTH_SHORT).show();}},handler);}
    private void save(Bitmap b){try{ContentValues v=new ContentValues();v.put(MediaStore.Images.Media.DISPLAY_NAME,"NicePlayer_"+System.currentTimeMillis()+".jpg");v.put(MediaStore.Images.Media.MIME_TYPE,"image/jpeg");if(Build.VERSION.SDK_INT>=29)v.put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/NicePlayer");Uri u=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,v);if(u==null)throw new Exception();try(OutputStream s=getContentResolver().openOutputStream(u)){if(s==null||!b.compress(Bitmap.CompressFormat.JPEG,94,s))throw new Exception();}Toast.makeText(this,"Screenshot saved",Toast.LENGTH_SHORT).show();}catch(Exception e){Toast.makeText(this,"Could not save screenshot",Toast.LENGTH_LONG).show();}finally{b.recycle();}}
    private void immersive(){getWindow().getDecorView().setSystemUiVisibility(5894|View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);}
    @Override protected void onPause(){savePosition();super.onPause();}
    @Override protected void onUserLeaveHint(){super.onUserLeaveHint();if(autoPip&&Build.VERSION.SDK_INT>=26&&player!=null&&player.isPlaying()&&!isInPictureInPictureMode())enterPip();}
    @Override public void onPictureInPictureModeChanged(boolean inPictureInPictureMode, android.content.res.Configuration newConfig){
        super.onPictureInPictureModeChanged(inPictureInPictureMode,newConfig);
        pipTransitionPending=false;
        if(inPictureInPictureMode){
            hidePlayerOverlaysForPip();
        }else if(!locked){
            int visibility=controls?View.VISIBLE:View.GONE;top.setVisibility(visibility);bottom.setVisibility(visibility);((View)quickTools.getTag()).setVisibility(visibility);lock.setVisibility(visibility);screenshotButton.setVisibility(visibility);if(waveform!=null)waveform.setVisibility(waveformEnabled?View.VISIBLE:View.GONE);
        }
    }
    @Override protected void onDestroy(){handler.removeCallbacksAndMessages(null);worker.shutdownNow();try{unregisterReceiver(noisyReceiver);}catch(Exception ignored){}try{unregisterReceiver(playbackReceiver);}catch(Exception ignored){}stopService(new Intent(this,PlaybackService.class));if(Build.VERSION.SDK_INT>=26&&focusRequest!=null)audioManager.abandonAudioFocusRequest(focusRequest);if(cleanupEqualizer!=null&&player!=null){player.setEqualizer(null);cleanupEqualizer=null;}if(player!=null){player.stop();player.detachViews();player.release();player=null;}closeSourceDescriptor();if(vlc!=null){vlc.release();vlc=null;}super.onDestroy();}
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
