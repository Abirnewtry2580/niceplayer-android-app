package com.niceplayer.app;

import android.Manifest;
import android.content.ContentObserver;
import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.provider.MediaStore;
import androidx.core.content.ContextCompat;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Persistent app-private cache for completed waveform analyses. */
final class WaveformCache {
    private static final int MAGIC = 0x4E505746;
    private static final int VERSION = 2;
    private static final int LEGACY_VERSION = 1;
    private static final int MAX_BUCKETS = 72_000;
    private static final int FINGERPRINT_BYTES = 32;
    private static HandlerThread observerThread;
    private static Handler observerHandler;
    private static ContentObserver mediaObserver;
    private static Runnable cleanupRunnable;

    private WaveformCache() {}

    static void startWatching(Context context) {
        synchronized(WaveformCache.class){
            if(mediaObserver!=null)return;
            Context app=context.getApplicationContext();
            observerThread=new HandlerThread("NicePlayerWaveformCache");
            observerThread.start();
            observerHandler=new Handler(observerThread.getLooper());
            cleanupRunnable=()->cleanup(app);
            mediaObserver=new ContentObserver(observerHandler){
                @Override public void onChange(boolean selfChange,Uri uri){
                    observerHandler.removeCallbacks(cleanupRunnable);
                    observerHandler.postDelayed(cleanupRunnable,700);
                }
            };
            app.getContentResolver().registerContentObserver(
                    MediaStore.Video.Media.EXTERNAL_CONTENT_URI,true,mediaObserver);
            observerHandler.postDelayed(cleanupRunnable,500);
        }
    }

    private static boolean hasVideoPermission(Context context) {
        String permission=Build.VERSION.SDK_INT>=Build.VERSION_CODES.TIRAMISU
                ?Manifest.permission.READ_MEDIA_VIDEO:Manifest.permission.READ_EXTERNAL_STORAGE;
        return ContextCompat.checkSelfPermission(context,permission)==android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

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

    static final class Stats {
        final int count;
        final long bytes;
        Stats(int count,long bytes){this.count=count;this.bytes=bytes;}
    }

    static final class Entry {
        final String key;
        final String name;
        final String folder;
        final long bytes;
        Entry(String key,String name,String folder,long bytes){this.key=key;this.name=name;this.folder=folder;this.bytes=bytes;}
    }

    private static boolean isValidCacheFile(File candidate) {
        if(candidate==null||!candidate.isFile())return false;
        try(DataInputStream input=new DataInputStream(new BufferedInputStream(new FileInputStream(candidate)))){
            if(input.readInt()!=MAGIC)return false;
            int version=input.readInt();
            if(version!=VERSION&&version!=LEGACY_VERSION)return false;
            input.readLong();
            int samples=input.readInt();
            if(samples<1||samples>MAX_BUCKETS)return false;
            long expected=20L+5L*samples+(version==VERSION?FINGERPRINT_BYTES:0);
            return candidate.length()==expected;
        }catch(Exception ignored){return false;}
    }

    private static byte[] mediaFingerprint(Cursor cursor,int nameColumn,int bucketIdColumn,int folderColumn,int pathColumn) {
        String name=nameColumn<0?"":cursor.getString(nameColumn);
        String bucketId=bucketIdColumn<0?"":cursor.getString(bucketIdColumn);
        String folder=folderColumn<0?"":cursor.getString(folderColumn);
        String path=pathColumn<0?"":cursor.getString(pathColumn);
        String n=name==null?"":name;
        String bucket=bucketId==null?"":bucketId;
        String folderName=folder==null?"":folder;
        String pathName=path==null?"":path;
        String value=n.length()+":"+n+bucket.length()+":"+bucket+folderName.length()+":"+folderName
                +pathName.length()+":"+pathName;
        return sha256Bytes(value);
    }

    private static byte[] currentFingerprint(Context context,Uri uri) {
        if(uri==null)return null;
        String[] projection=Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q
                ?new String[]{MediaStore.Video.Media.DISPLAY_NAME,MediaStore.Video.Media.BUCKET_ID,
                        MediaStore.Video.Media.BUCKET_DISPLAY_NAME,MediaStore.Video.Media.RELATIVE_PATH}
                :new String[]{MediaStore.Video.Media.DISPLAY_NAME,MediaStore.Video.Media.BUCKET_ID,
                        MediaStore.Video.Media.BUCKET_DISPLAY_NAME};
        try(Cursor cursor=context.getContentResolver().query(uri,projection,null,null,null)){
            if(cursor==null||!cursor.moveToFirst())return null;
            return mediaFingerprint(cursor,
                    cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME),
                    cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_ID),
                    cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME),
                    cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH));
        }catch(Exception ignored){return null;}
    }

    private static Map<String,byte[]> currentMedia(Context context) {
        Map<String,byte[]> result=new HashMap<>();
        String[] projection=Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q
                ?new String[]{MediaStore.Video.Media._ID,MediaStore.Video.Media.DISPLAY_NAME,
                        MediaStore.Video.Media.BUCKET_ID,MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
                        MediaStore.Video.Media.RELATIVE_PATH}
                :new String[]{MediaStore.Video.Media._ID,MediaStore.Video.Media.DISPLAY_NAME,
                        MediaStore.Video.Media.BUCKET_ID,MediaStore.Video.Media.BUCKET_DISPLAY_NAME};
        try(Cursor cursor=context.getContentResolver().query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,projection,null,null,null)){
            if(cursor==null)return null;
            int idColumn=cursor.getColumnIndex(MediaStore.Video.Media._ID);
            if(idColumn<0)return null;
            int nameColumn=cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME);
            int bucketIdColumn=cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_ID);
            int folderColumn=cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
            int pathColumn=cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH);
            while(cursor.moveToNext()){
                Uri uri=ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,cursor.getLong(idColumn));
                String key=sha256(uri.toString());
                if(key!=null)result.put(key,mediaFingerprint(cursor,nameColumn,bucketIdColumn,folderColumn,pathColumn));
            }
            return result;
        }catch(Exception ignored){return null;}
    }

    private static boolean isEmptyFingerprint(byte[] value) {
        if(value==null)return true;
        for(byte part:value)if(part!=0)return false;
        return true;
    }

    static synchronized void cleanup(Context context) {
        if(!hasVideoPermission(context))return;
        Map<String,byte[]> media=currentMedia(context);
        if(media==null)return; // A failed scan must never erase caches.
        File dir=directory(context);
        File[] files=dir.listFiles((parent,name)->name.endsWith(".npwf"));
        if(files==null)return;
        for(File candidate:files){
            if(!isValidCacheFile(candidate))continue;
            String filename=candidate.getName();
            if(!filename.matches("[0-9a-f]{64}\\.npwf"))continue;
            String key=filename.substring(0,64);
            byte[] current=media.get(key);
            try(DataInputStream input=new DataInputStream(new BufferedInputStream(new FileInputStream(candidate)))){
                input.readInt();
                int version=input.readInt();
                long duration=input.readLong();
                int count=input.readInt();
                if(current==null){
                    if(version==VERSION){
                        byte[] saved=new byte[FINGERPRINT_BYTES];
                        input.readFully(saved);
                        if(!isEmptyFingerprint(saved))candidate.delete();
                    }else{
                        candidate.delete();
                    }
                    continue;
                }
                if(version==VERSION){
                    byte[] saved=new byte[FINGERPRINT_BYTES];
                    input.readFully(saved);
                    if(isEmptyFingerprint(saved)){
                        float[] levels=new float[count];
                        boolean[] speech=new boolean[count];
                        for(int i=0;i<count;i++)levels[i]=input.readFloat();
                        for(int i=0;i<count;i++)speech[i]=input.readBoolean();
                        writeFile(candidate,duration,new AudioWaveformExtractor.Result(levels,speech),current);
                    }else if(!Arrays.equals(saved,current)){
                        candidate.delete();
                    }
                }else if(version==LEGACY_VERSION){
                    float[] levels=new float[count];
                    boolean[] speech=new boolean[count];
                    for(int i=0;i<count;i++)levels[i]=input.readFloat();
                    for(int i=0;i<count;i++)speech[i]=input.readBoolean();
                    writeFile(candidate,duration,new AudioWaveformExtractor.Result(levels,speech),current);
                }
            }catch(Exception ignored){}
        }
        File[] temporary=dir.listFiles((parent,name)->name.endsWith(".npwf.tmp"));
        if(temporary!=null){
            long staleBefore=System.currentTimeMillis()-24L*60L*60L*1000L;
            for(File candidate:temporary)if(candidate.lastModified()<staleBefore)candidate.delete();
        }
    }

    static synchronized List<Entry> entries(Context context) {
        cleanup(context);
        File dir=directory(context);
        File[] files=dir.listFiles((parent,name)->name.endsWith(".npwf"));
        List<Entry> result=new ArrayList<>();
        if(files==null)return result;
        Map<String,File> validFiles=new HashMap<>();
        for(File candidate:files){
            String filename=candidate.getName();
            if(!isValidCacheFile(candidate)||!filename.matches("[0-9a-f]{64}\\.npwf"))continue;
            validFiles.put(filename.substring(0,64),candidate);
        }
        Map<String,String[]> mediaNames=new HashMap<>();
        String[] projection=Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q
                ?new String[]{MediaStore.Video.Media._ID,MediaStore.Video.Media.DISPLAY_NAME,
                        MediaStore.Video.Media.BUCKET_DISPLAY_NAME,MediaStore.Video.Media.RELATIVE_PATH}
                :new String[]{MediaStore.Video.Media._ID,MediaStore.Video.Media.DISPLAY_NAME,
                        MediaStore.Video.Media.BUCKET_DISPLAY_NAME};
        try(Cursor cursor=context.getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,projection,null,null,null)){
            if(cursor!=null){
                int idColumn=cursor.getColumnIndex(MediaStore.Video.Media._ID);
                int nameColumn=cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME);
                int folderColumn=cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
                int pathColumn=cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH);
                while(cursor.moveToNext()){
                    if(idColumn<0||nameColumn<0)break;
                    Uri uri=ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,cursor.getLong(idColumn));
                    String key=sha256(uri.toString());
                    if(key!=null&&validFiles.containsKey(key)){
                        String name=cursor.getString(nameColumn);
                        String folder=folderColumn<0?null:cursor.getString(folderColumn);
                        String relativePath=pathColumn<0?null:cursor.getString(pathColumn);
                        if(isHiddenLocation(folder,relativePath)){
                            mediaNames.put(key,new String[]{"Hidden video · "+key.substring(0,8),""});
                        }else{
                            mediaNames.put(key,new String[]{name==null||name.isEmpty()?"Video":name,folder==null?"":folder});
                        }
                    }
                }
            }
        }catch(Exception ignored){}
        for(Map.Entry<String,File> item:validFiles.entrySet()){
            String key=item.getKey();
            String[] label=mediaNames.get(key);
            String name=label==null?"Unavailable video · "+key.substring(0,8):label[0];
            String folder=label==null?"":label[1];
            result.add(new Entry(key,name,folder,item.getValue().length()));
        }
        result.sort((a,b)->{
            int bySize=Long.compare(b.bytes,a.bytes);
            return bySize!=0?bySize:a.name.compareToIgnoreCase(b.name);
        });
        return result;
    }

    private static boolean isHiddenLocation(String bucketName,String relativePath) {
        if(bucketName!=null&&bucketName.trim().startsWith("."))return true;
        if(relativePath==null||relativePath.trim().isEmpty())return false;
        for(String part:relativePath.replace('\\','/').split("/")){
            String name=part.trim();
            if(name.length()>1&&name.startsWith("."))return true;
        }
        return false;
    }

    static synchronized Stats stats(Context context) {
        File dir=directory(context);
        File[] files=dir.listFiles((parent,name)->name.endsWith(".npwf"));
        if(files==null)return new Stats(0,0L);
        int count=0;long bytes=0L;
        for(File candidate:files){
            if(isValidCacheFile(candidate)){count++;bytes+=candidate.length();}
        }
        return new Stats(count,bytes);
    }

    static synchronized Stats deleteAll(Context context) {
        File dir=directory(context);
        File[] files=dir.listFiles((parent,name)->name.endsWith(".npwf")||name.endsWith(".npwf.tmp"));
        if(files==null)return new Stats(0,0L);
        int count=0;long bytes=0L;
        for(File candidate:files){
            if(!candidate.isFile())continue;
            long size=candidate.length();
            if(candidate.delete()){
                bytes+=size;
                if(candidate.getName().endsWith(".npwf"))count++;
            }
        }
        return new Stats(count,bytes);
    }

    static synchronized long deleteByKey(Context context,String key) {
        if(key==null||!key.matches("[0-9a-f]{64}"))return 0L;
        File candidate=new File(directory(context),key+".npwf");
        if(!candidate.isFile())return 0L;
        long bytes=candidate.length();
        return candidate.delete()?bytes:0L;
    }

    static synchronized long delete(Context context, Uri uri) {
        File file = file(context, uri, false);
        if (file == null || !file.isFile()) return 0L;
        long bytes = file.length();
        return file.delete() ? bytes : 0L;
    }

    static synchronized AudioWaveformExtractor.Result read(Context context, Uri uri, int expectedBuckets, long expectedDurationMs) {
        File file = file(context, uri, false);
        if (file == null || !file.isFile() || expectedBuckets < 1 || expectedBuckets > MAX_BUCKETS) return null;
        byte[] current=currentFingerprint(context,uri);
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) {
            if (input.readInt() != MAGIC) return null;
            int version=input.readInt();
            if(version!=VERSION&&version!=LEGACY_VERSION)return null;
            if (input.readLong() != expectedDurationMs) return null;
            int count = input.readInt();
            if (count != expectedBuckets || count < 1 || count > MAX_BUCKETS) return null;
            if(version==VERSION){
                byte[] saved=new byte[FINGERPRINT_BYTES];
                input.readFully(saved);
                if(current!=null&&!Arrays.equals(saved,current)){
                    file.delete();
                    return null;
                }
            }
            float[] levels = new float[count];
            boolean[] speech = new boolean[count];
            for (int i = 0; i < count; i++) levels[i] = input.readFloat();
            for (int i = 0; i < count; i++) speech[i] = input.readBoolean();
            if (input.read() != -1) return null;
            AudioWaveformExtractor.Result result=new AudioWaveformExtractor.Result(levels, speech);
            if(version==LEGACY_VERSION&&current!=null)write(context,uri,expectedDurationMs,result);
            return result;
        } catch (Exception error) {
            return null;
        }
    }

    static synchronized void write(Context context, Uri uri, long durationMs, AudioWaveformExtractor.Result result) {
        if (result == null || result.levels == null || result.speech == null
                || result.levels.length == 0 || result.levels.length != result.speech.length
                || result.levels.length > MAX_BUCKETS) return;
        File target = file(context, uri, true);
        if (target == null) return;
        writeFile(target,durationMs,result,currentFingerprint(context,uri));
    }

    private static void writeFile(File target,long durationMs,AudioWaveformExtractor.Result result,byte[] fingerprint) {
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(temporary)))) {
            output.writeInt(MAGIC);
            output.writeInt(VERSION);
            output.writeLong(durationMs);
            output.writeInt(result.levels.length);
            byte[] saved=fingerprint==null?new byte[FINGERPRINT_BYTES]:fingerprint;
            output.write(saved);
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

    private static byte[] sha256Bytes(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) {
            return null;
        }
    }

    private static String sha256(String value) {
        byte[] digest=sha256Bytes(value);
        if(digest==null)return null;
        StringBuilder result = new StringBuilder(digest.length * 2);
        for (byte part : digest) result.append(String.format(Locale.US, "%02x", part & 0xff));
        return result.toString();
    }
}
