package com.local.protonawg;

import android.os.Bundle;
import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(WarpHttpPlugin.class);
        super.onCreate(savedInstanceState);
    }
}
