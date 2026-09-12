package com.niceplayer.app;

import android.Manifest;
import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.provider.MediaStore;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.util.LinkedHashMap;
import java.util.Map;

@CapacitorPlugin(
    name = "VideoLibrary",
    permissions = {
        @Permission(alias = "video13", strings = { Manifest.permission.READ_MEDIA_VIDEO }),
        @Permission(alias = "storage", strings = { Manifest.permission.READ_EXTERNAL_STORAGE })
    }
)
public class VideoLibraryPlugin extends Plugin {

    @PluginMethod
    public void getFolders(PluginCall call) {
        if (!hasVideoPermission()) {
            requestPermissionForAlias(Build.VERSION.SDK_INT >= 33 ? "video13" : "storage", call, "permissionResult");
            return;
        }
        queryFolders(call);
    }

    @PermissionCallback
    private void permissionResult(PluginCall call) {
        if (hasVideoPermission()) queryFolders(call);
        else call.reject("Video permission denied");
    }

    private boolean hasVideoPermission() {
        String alias = Build.VERSION.SDK_INT >= 33 ? "video13" : "storage";
        return getPermissionState(alias) == PermissionState.GRANTED;
    }

    private void queryFolders(PluginCall call) {
        ContentResolver resolver = getContext().getContentResolver();
        Uri collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI;
        String[] projection = {
            MediaStore.Video.Media.BUCKET_ID,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        };
        Map<String, Folder> grouped = new LinkedHashMap<>();

        try (Cursor cursor = resolver.query(collection, projection, null, null,
                MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            if (cursor != null) {
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_ID);
                int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME);
                while (cursor.moveToNext()) {
                    String id = cursor.getString(idColumn);
                    String name = cursor.getString(nameColumn);
                    if (name == null || name.trim().isEmpty()) name = "Videos";
                    Folder folder = grouped.get(id);
                    if (folder == null) { folder = new Folder(id, name); grouped.put(id, folder); }
                    folder.count++;
                }
            }
            JSArray folders = new JSArray();
            for (Folder folder : grouped.values()) {
                JSObject item = new JSObject();
                item.put("bucketId", folder.id);
                item.put("name", folder.name);
                item.put("count", folder.count);
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

        String[] projection = {
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DURATION
        };
        String selection = MediaStore.Video.Media.BUCKET_ID + "=?";
        JSArray videos = new JSArray();

        try (Cursor cursor = getContext().getContentResolver().query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, selection,
                new String[]{bucketId}, MediaStore.Video.Media.DATE_ADDED + " DESC")) {
            if (cursor != null) {
                int idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID);
                int nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME);
                int sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE);
                int durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION);
                while (cursor.moveToNext()) {
                    long id = cursor.getLong(idColumn);
                    JSObject item = new JSObject();
                    item.put("name", cursor.getString(nameColumn));
                    item.put("size", cursor.getLong(sizeColumn));
                    item.put("duration", cursor.getLong(durationColumn));
                    item.put("uri", Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, String.valueOf(id)).toString());
                    videos.put(item);
                }
            }
            JSObject result = new JSObject(); result.put("videos", videos); call.resolve(result);
        } catch (Exception error) { call.reject("Could not read folder", error); }
    }

    private static class Folder {
        final String id; final String name; int count = 0;
        Folder(String id, String name) { this.id = id; this.name = name; }
    }
}
