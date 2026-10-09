package com.niceplayer.app;

import android.content.Context;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
            if(input.readInt()!=MAGIC||input.readInt()!=VERSION)return false;
            input.readLong();
            int samples=input.readInt();
            return samples>=1&&samples<=MAX_BUCKETS&&candidate.length()==20L+5L*samples;
        }catch(Exception ignored){return false;}
    }

    static List<Entry> entries(Context context) {
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
                ?new String[]{MediaStore.Video.Media._ID,MediaStore.Video.Media.DISPLAY_NAME,MediaStore.Video.Media.BUCKET_DISPLAY_NAME,MediaStore.Video.Media.RELATIVE_PATH}
                :new String[]{MediaStore.Video.Media._ID,MediaStore.Video.Media.DISPLAY_NAME,MediaStore.Video.Media.BUCKET_DISPLAY_NAME};
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
        Set<String> found=new HashSet<>();
        for(Map.Entry<String,File> item:validFiles.entrySet()){
            String key=item.getKey();
            String[] label=mediaNames.get(key);
            String name=label==null?"Unavailable video · "+key.substring(0,8):label[0];
            String folder=label==null?"":label[1];
            result.add(new Entry(key,name,folder,item.getValue().length()));
            found.add(key);
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

    static Stats stats(Context context) {
        File dir=directory(context);
        File[] files=dir.listFiles((parent,name)->name.endsWith(".npwf"));
        if(files==null)return new Stats(0,0L);
        int count=0;long bytes=0L;
        for(File candidate:files){
            if(isValidCacheFile(candidate)){count++;bytes+=candidate.length();}
        }
        return new Stats(count,bytes);
    }

    static Stats deleteAll(Context context) {
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

    static long deleteByKey(Context context,String key) {
        if(key==null||!key.matches("[0-9a-f]{64}"))return 0L;
        File candidate=new File(directory(context),key+".npwf");
        if(!candidate.isFile())return 0L;
        long bytes=candidate.length();
        return candidate.delete()?bytes:0L;
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
