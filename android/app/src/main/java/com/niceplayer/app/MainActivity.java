package com.niceplayer.app;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import androidx.activity.OnBackPressedCallback;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(VideoLibraryPlugin.class);
        super.onCreate(savedInstanceState);
        requestVideoPermissionIfNeeded();
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() {
                getBridge().getWebView().evaluateJavascript(
                    "window.handleAndroidBack ? window.handleAndroidBack() : window.VideoLibrary.exitApp()", null);
            }
        });
    }

    private void requestVideoPermissionIfNeeded() {
        String permission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                ? Manifest.permission.READ_MEDIA_VIDEO
                : Manifest.permission.READ_EXTERNAL_STORAGE;
        if (ContextCompat.checkSelfPermission(this, permission) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{permission}, 7001);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 7001 && grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            getBridge().getWebView().evaluateJavascript("window.scanDeviceVideos && window.scanDeviceVideos()", null);
        }
    }
}
