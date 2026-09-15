package com.niceplayer.app;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.app.Activity;
import android.app.PendingIntent;
import android.app.RecoverableSecurityException;
import android.graphics.Bitmap;
import android.util.Size;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;
import androidx.activity.ComponentActivity;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.IntentSenderRequest;
import androidx.activity.result.contract.ActivityResultContracts;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.io.OutputStream;
import java.io.ByteArrayOutputStream;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

@CapacitorPlugin(
    name = "VideoLibrary",
    permissions = {
        @Permission(alias = "video13", strings = { Manifest.permission.READ_MEDIA_VIDEO }),
        @Permission(alias = "storage", strings = { Manifest.permission.READ_EXTERNAL_STORAGE })
    }
)
public class VideoLibraryPlugin extends Plugin {
    private final ThreadPoolExecutor thumbnailWorker = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    private final ThreadPoolExecutor scanWorker = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<>());
    private ActivityResultLauncher<IntentSenderRequest> mutationLauncher;
    private PluginCall pendingMutationCall;
    private String pendingMutationType;
    private String pendingMutationName;
    private String pendingRelativePath;
    private List<Uri> pendingMutationUris;
    private int pendingMutationIndex;

    @Override
    public void load() {
        mutationLauncher = ((ComponentActivity) getActivity()).registerForActivityResult(
                new ActivityResultContracts.StartIntentSenderForResult(), result -> {
                    PluginCall call = pendingMutationCall;
                    if (call == null) return;
                    if (result.getResultCode() != Activity.RESULT_OK) {
                        clearPendingMutation(); call.reject("Operation cancelled"); return;
                    }
                    try {
                        if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) { continueAndroid10Mutation(); return; }
                        if (!"deleteVideo".equals(pendingMutationType) && !"deleteFolder".equals(pendingMutationType)) performMutation();
                        JSObject response = new JSObject(); response.put("success", true); call.resolve(response);
                    } catch (Exception error) { call.reject("Could not update media", error); }
                    clearPendingMutation();
                });
    }

    @PluginMethod
    public void renameVideo(PluginCall call) { startFileMutation(call, "renameVideo"); }

    @PluginMethod
    public void deleteVideo(PluginCall call) { startFileMutation(call, "deleteVideo"); }

    private void startFileMutation(PluginCall call, String type) {
        String rawUri = call.getString("uri"), name = call.getString("name");
        if (rawUri == null) { call.reject("uri is required"); return; }
        if ("renameVideo".equals(type) && !validName(name)) { call.reject("A valid name is required"); return; }
        requestMutation(call, type, Collections.singletonList(Uri.parse(rawUri)), name, null);
    }

    @PluginMethod
    public void renameFolder(PluginCall call) {
        String bucketId = call.getString("bucketId"), name = call.getString("name");
        if (bucketId == null || !validName(name)) { call.reject("Folder and a valid name are required"); return; }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) { call.reject("Folder rename requires Android 10 or newer"); return; }
        FolderMedia media = folderMedia(bucketId);
        if (media.uris.isEmpty() || media.relativePath == null) { call.reject("Folder is empty or unavailable"); return; }
        String path = media.relativePath.endsWith("/") ? media.relativePath.substring(0, media.relativePath.length()-1) : media.relativePath;
        int slash = path.lastIndexOf('/');
        String renamedPath = (slash >= 0 ? path.substring(0, slash + 1) : "") + name.trim() + "/";
        requestMutation(call, "renameFolder", media.uris, name.trim(), renamedPath);
    }

    @PluginMethod
    public void deleteFolder(PluginCall call) {
        String bucketId = call.getString("bucketId");
        if (bucketId == null) { call.reject("bucketId is required"); return; }
        List<Uri> uris = folderMedia(bucketId).uris;
        if (uris.isEmpty()) { call.reject("Folder is empty or unavailable"); return; }
        requestMutation(call, "deleteFolder", uris, null, null);
    }

    private boolean validName(String name) { return name != null && !name.trim().isEmpty() && !name.contains("/") && !name.contains("\\"); }

    private void requestMutation(PluginCall call, String type, List<Uri> uris, String name, String relativePath) {
        if (pendingMutationCall != null) { call.reject("Another file operation is in progress"); return; }
        pendingMutationCall=call;pendingMutationType=type;pendingMutationUris=uris;pendingMutationName=name;pendingRelativePath=relativePath;pendingMutationIndex=0;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                PendingIntent consent = (type.startsWith("delete"))
                        ? MediaStore.createDeleteRequest(getContext().getContentResolver(), uris)
                        : MediaStore.createWriteRequest(getContext().getContentResolver(), uris);
                mutationLauncher.launch(new IntentSenderRequest.Builder(consent.getIntentSender()).build());
            } else if (Build.VERSION.SDK_INT == Build.VERSION_CODES.Q) {
                continueAndroid10Mutation();
            } else {
                performMutation(); JSObject response=new JSObject();response.put("success",true);call.resolve(response);clearPendingMutation();
            }
        } catch (Exception error) { clearPendingMutation(); call.reject("Could not request media permission", error); }
    }

    private void performMutation() {
        for(Uri uri:pendingMutationUris) performMutationForUri(uri);
    }

    private void performMutationForUri(Uri uri) {
        ContentResolver resolver=getContext().getContentResolver();
        if (pendingMutationType.startsWith("delete")) { resolver.delete(uri,null,null); return; }
        ContentValues values=new ContentValues();
        if ("renameVideo".equals(pendingMutationType)) values.put(MediaStore.Video.Media.DISPLAY_NAME,pendingMutationName.trim());
        else values.put(MediaStore.Video.Media.RELATIVE_PATH,pendingRelativePath);
        resolver.update(uri,values,null,null);
    }

    private void continueAndroid10Mutation() {
        PluginCall call=pendingMutationCall;if(call==null)return;
        try {
            while(pendingMutationIndex<pendingMutationUris.size()){
                performMutationForUri(pendingMutationUris.get(pendingMutationIndex));pendingMutationIndex++;
            }
            JSObject response=new JSObject();response.put("success",true);call.resolve(response);clearPendingMutation();
        } catch (RecoverableSecurityException permission) {
            mutationLauncher.launch(new IntentSenderRequest.Builder(permission.getUserAction().getActionIntent().getIntentSender()).build());
        } catch (Exception error) { clearPendingMutation();call.reject("Could not update media",error); }
    }

    private FolderMedia folderMedia(String bucketId) {
        FolderMedia result=new FolderMedia();String[] projection={MediaStore.Video.Media._ID,MediaStore.Video.Media.RELATIVE_PATH};
        try(Cursor cursor=getContext().getContentResolver().query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,projection,MediaStore.Video.Media.BUCKET_ID+"=?",new String[]{bucketId},null)){
            if(cursor!=null){int id=cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID),path=cursor.getColumnIndex(MediaStore.Video.Media.RELATIVE_PATH);while(cursor.moveToNext()){result.uris.add(Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,String.valueOf(cursor.getLong(id))));if(result.relativePath==null&&path>=0)result.relativePath=cursor.getString(path);}}
        } catch(Exception ignored){}
        return result;
    }

    private void clearPendingMutation(){pendingMutationCall=null;pendingMutationType=null;pendingMutationUris=null;pendingMutationName=null;pendingRelativePath=null;pendingMutationIndex=0;}

    private static class FolderMedia { final List<Uri> uris=new ArrayList<>(); String relativePath; }

    @PluginMethod
    public void playVideo(PluginCall call) {
        String uri = call.getString("uri");
        if (uri == null || uri.trim().isEmpty()) { call.reject("uri is required"); return; }
        // Do not let queued thumbnail decoding compete with the video decoder.
        thumbnailWorker.getQueue().clear();
        Intent intent = new Intent(getContext(), PlayerActivity.class);
        intent.setData(Uri.parse(uri));
        intent.putExtra("title", call.getString("title", "Video"));
        intent.putExtra("index", call.getInt("index", 0));
        intent.putExtra("privateMode", call.getBoolean("privateMode", false));
        JSArray uriArray = call.getArray("uris");
        JSArray titleArray = call.getArray("titles");
        ArrayList<String> uris = new ArrayList<>();
        ArrayList<String> titles = new ArrayList<>();
        if (uriArray != null) for (int i = 0; i < uriArray.length(); i++) uris.add(uriArray.optString(i));
        if (titleArray != null) for (int i = 0; i < titleArray.length(); i++) titles.add(titleArray.optString(i));
        intent.putStringArrayListExtra("uris", uris);
        intent.putStringArrayListExtra("titles", titles);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        getActivity().startActivity(intent);
        call.resolve();
    }

    @PluginMethod
    public void exitApp(PluginCall call) {
        getActivity().finish();
        call.resolve();
    }

    @PluginMethod
    public void getHistory(PluginCall call) {
        android.content.SharedPreferences preferences=getContext().getSharedPreferences("playback",android.content.Context.MODE_PRIVATE);
        JSArray items=new JSArray();
        for(int i=0;i<12;i++){String uri=preferences.getString("history_uri_"+i,null);if(uri==null)continue;JSObject item=new JSObject();item.put("uri",uri);item.put("name",preferences.getString("history_name_"+i,"Video"));item.put("position",preferences.getLong("position_"+uri,0));addHistoryMediaInfo(item,Uri.parse(uri));items.put(item);}
        JSObject result=new JSObject();result.put("items",items);call.resolve(result);
    }

    @PluginMethod
    public void removeHistory(PluginCall call) {
        JSArray requested=call.getArray("uris");if(requested==null){call.reject("uris are required");return;}
        java.util.HashSet<String> removed=new java.util.HashSet<>();for(int i=0;i<requested.length();i++){String uri=requested.optString(i);if(uri!=null)removed.add(uri);}
        android.content.SharedPreferences preferences=getContext().getSharedPreferences("playback",android.content.Context.MODE_PRIVATE);
        ArrayList<String> uris=new ArrayList<>(),names=new ArrayList<>();
        for(int i=0;i<12;i++){String uri=preferences.getString("history_uri_"+i,null);if(uri!=null&&!removed.contains(uri)){uris.add(uri);names.add(preferences.getString("history_name_"+i,"Video"));}}
        android.content.SharedPreferences.Editor edit=preferences.edit();for(String uri:removed)edit.remove("position_"+uri);
        for(int i=0;i<12;i++){if(i<uris.size()){edit.putString("history_uri_"+i,uris.get(i));edit.putString("history_name_"+i,names.get(i));}else{edit.remove("history_uri_"+i);edit.remove("history_name_"+i);}}
        edit.apply();JSObject result=new JSObject();result.put("success",true);call.resolve(result);
    }

    private void addHistoryMediaInfo(JSObject item,Uri uri){String[] projection={MediaStore.Video.Media.BUCKET_ID,MediaStore.Video.Media.BUCKET_DISPLAY_NAME,MediaStore.Video.Media.DISPLAY_NAME};try(Cursor cursor=getContext().getContentResolver().query(uri,projection,null,null,null)){if(cursor!=null&&cursor.moveToFirst()){int bucketId=cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_ID),bucketName=cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME),name=cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME);if(bucketId>=0)item.put("bucketId",cursor.getString(bucketId));if(bucketName>=0)item.put("bucketName",cursor.getString(bucketName));if(name>=0)item.put("fileName",cursor.getString(name));}}catch(Exception ignored){}}

    @PluginMethod
    public void saveImage(PluginCall call) {
        String dataUrl = call.getString("dataUrl");
        String requestedName = call.getString("fileName", "NicePlayer_Image.png");
        if (dataUrl == null || dataUrl.trim().isEmpty()) {
            call.reject("dataUrl is required");
            return;
        }

        try {
            int comma = dataUrl.indexOf(',');
            String encoded = comma >= 0 ? dataUrl.substring(comma + 1) : dataUrl;
            byte[] image = Base64.decode(encoded, Base64.DEFAULT);
            String fileName = requestedName.toLowerCase().endsWith(".png")
                    ? requestedName : requestedName + ".png";

            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, fileName);
            values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.put(MediaStore.Images.Media.RELATIVE_PATH,
                        Environment.DIRECTORY_PICTURES + "/NicePlayer");
                values.put(MediaStore.Images.Media.IS_PENDING, 1);
            }

            ContentResolver resolver = getContext().getContentResolver();
            Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("Could not create image file");

            try (OutputStream stream = resolver.openOutputStream(uri)) {
                if (stream == null) throw new IllegalStateException("Could not open image file");
                stream.write(image);
                stream.flush();
            } catch (Exception error) {
                resolver.delete(uri, null, null);
                throw error;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues ready = new ContentValues();
                ready.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(uri, ready, null, null);
            }

            JSObject result = new JSObject();
            result.put("uri", uri.toString());
            result.put("fileName", fileName);
            call.resolve(result);
        } catch (Exception error) {
            call.reject("Could not save image", error);
        }
    }

    @PluginMethod
    public void getFolders(PluginCall call) {
        if (!hasVideoPermission()) {
            requestPermissionForAlias(Build.VERSION.SDK_INT >= 33 ? "video13" : "storage", call, "permissionResult");
            return;
        }
        scanWorker.execute(() -> queryFolders(call));
    }

    @PermissionCallback
    private void permissionResult(PluginCall call) {
        if (hasVideoPermission()) scanWorker.execute(() -> queryFolders(call));
        else call.reject("Video permission denied");
    }

    private boolean hasVideoPermission() {
        String alias = Build.VERSION.SDK_INT >= 33 ? "video13" : "storage";
        return getPermissionState(alias) == PermissionState.GRANTED;
    }

    private void queryFolders(PluginCall call) {
        ContentResolver resolver = getContext().getContentResolver();
        Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] projection = Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q
                ? new String[]{MediaStore.Video.Media.BUCKET_ID,MediaStore.Video.Media.BUCKET_DISPLAY_NAME,MediaStore.Video.Media.DISPLAY_NAME,MediaStore.Video.Media.IS_PENDING}
                : new String[]{MediaStore.Video.Media.BUCKET_ID,MediaStore.Video.Media.BUCKET_DISPLAY_NAME,MediaStore.Video.Media.DISPLAY_NAME};
        Map<String, Folder> grouped = new LinkedHashMap<>();

        try (Cursor cursor = resolver.query(collection, projection, null, null,
                MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            if (cursor != null) {
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID);
                int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
                int fileNameColumn=cursor.getColumnIndex(MediaStore.Video.Media.DISPLAY_NAME),pendingColumn=cursor.getColumnIndex(MediaStore.Video.Media.IS_PENDING);
                while (cursor.moveToNext()) {
                    String id = cursor.getString(idColumn);
                    String name = cursor.getString(nameColumn);
                    if (name == null || name.trim().isEmpty()) name = "Videos";
                    Folder folder = grouped.get(id);
                    if (folder == null) { folder = new Folder(id, name); grouped.put(id, folder); }
                    folder.count++;
                    String fileName=fileNameColumn>=0?cursor.getString(fileNameColumn):null;
                    if((pendingColumn>=0&&cursor.getInt(pendingColumn)!=0)||looksIncomplete(fileName))folder.hasRunningDownloads=true;
                }
            }
            JSArray folders = new JSArray();
            for (Folder folder : grouped.values()) {
                JSObject item = new JSObject();
                item.put("bucketId", folder.id);
                item.put("name", folder.name);
                item.put("count", folder.count);
                item.put("hasRunningDownloads",folder.hasRunningDownloads);
                folders.put(item);
            }
            JSObject result = new JSObject(); result.put("folders", folders); call.resolve(result);
        } catch (Exception error) { call.reject("Could not scan video folders", error); }
    }

    @PluginMethod
    public void getVideos(PluginCall call) {
        if (!hasVideoPermission()) { call.reject("Video permission denied"); return; }
        String bucketId = call.getString("bucketId");
        if (bucketId == null) { call.reject("bucketId is required"); return; }
        scanWorker.execute(() -> queryVideos(call, bucketId));
    }

    @PluginMethod
    public void getAllVideos(PluginCall call) {
        if (!hasVideoPermission()) { call.reject("Video permission denied"); return; }
        scanWorker.execute(() -> queryVideos(call, null));
    }

    private void queryVideos(PluginCall call, String bucketId) {
        String[] projection = Build.VERSION.SDK_INT>=Build.VERSION_CODES.Q
                ? new String[]{MediaStore.Video.Media._ID,MediaStore.Video.Media.DISPLAY_NAME,MediaStore.Video.Media.SIZE,MediaStore.Video.Media.DURATION,MediaStore.Video.Media.IS_PENDING,MediaStore.Video.Media.BUCKET_ID,MediaStore.Video.Media.BUCKET_DISPLAY_NAME}
                : new String[]{MediaStore.Video.Media._ID,MediaStore.Video.Media.DISPLAY_NAME,MediaStore.Video.Media.SIZE,MediaStore.Video.Media.DURATION,MediaStore.Video.Media.BUCKET_ID,MediaStore.Video.Media.BUCKET_DISPLAY_NAME};
        String selection = bucketId == null ? null : MediaStore.Video.Media.BUCKET_ID + "=?";
        String[] selectionArgs = bucketId == null ? null : new String[]{bucketId};
        JSArray videos = new JSArray();

        try (Cursor cursor = getContext().getContentResolver().query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, selection,
                selectionArgs, MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            if (cursor != null) {
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
                int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
                int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
                int durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION);
                int pendingColumn=cursor.getColumnIndex(MediaStore.Video.Media.IS_PENDING);
                int bucketColumn=cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_ID),bucketNameColumn=cursor.getColumnIndex(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idColumn);
                    JSObject item = new JSObject();
                    String displayName=cursor.getString(nameColumn);item.put("name",displayName);
                    item.put("size", cursor.getLong(sizeColumn));
                    item.put("duration", cursor.getLong(durationColumn));
                    if(bucketColumn>=0)item.put("bucketId",cursor.getString(bucketColumn));
                    if(bucketNameColumn>=0)item.put("bucketName",cursor.getString(bucketNameColumn));
                    item.put("isDownloading",(pendingColumn>=0&&cursor.getInt(pendingColumn)!=0)||looksIncomplete(displayName));
                    Uri videoUri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, String.valueOf(id));
                    item.put("uri", videoUri.toString());
                    videos.put(item);
                }
            }
            JSObject result = new JSObject(); result.put("videos", videos); call.resolve(result);
        } catch (Exception error) { call.reject("Could not read videos", error); }
    }

    private boolean looksIncomplete(String name){if(name==null)return false;String value=name.toLowerCase(java.util.Locale.US);return value.endsWith(".part")||value.endsWith(".download")||value.endsWith(".crdownload")||value.endsWith(".tmp")||value.endsWith(".partial");}

    @PluginMethod
    public void getThumbnail(PluginCall call) {
        String rawUri=call.getString("uri");
        if(rawUri==null){call.reject("uri is required");return;}
        thumbnailWorker.execute(()->{
            try{
                Uri uri=Uri.parse(rawUri);long id=-1;
                try{id=Long.parseLong(uri.getLastPathSegment());}catch(Exception ignored){}
                String thumbnail=makeThumbnail(uri,id);JSObject result=new JSObject();
                if(thumbnail!=null)result.put("thumbnail",thumbnail);call.resolve(result);
            }catch(Exception error){call.reject("Could not create thumbnail",error);}
        });
    }

    private String makeThumbnail(Uri uri, long id) {
        try {
            Bitmap bitmap;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                bitmap = getContext().getContentResolver().loadThumbnail(uri, new Size(240, 135), null);
            } else {
                bitmap = MediaStore.Video.Thumbnails.getThumbnail(
                    getContext().getContentResolver(), id, MediaStore.Video.Thumbnails.MINI_KIND, null);
            }
            if (bitmap == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            bitmap.compress(Bitmap.CompressFormat.JPEG, 58, out);
            bitmap.recycle();
            return "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP);
        } catch (Exception ignored) { return null; }
    }

    private static class Folder {
        final String id; final String name; int count = 0; boolean hasRunningDownloads;
        Folder(String id, String name) { this.id = id; this.name = name; }
    }
}
