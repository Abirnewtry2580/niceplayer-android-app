package com.niceplayer.app;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(VideoLibraryPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
