package com.wlh.glass;

import android.app.Activity;
import android.os.Bundle;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;

public class HomeActivity extends Activity {
    private GlassView view;
    private final android.content.BroadcastReceiver pkgs = new android.content.BroadcastReceiver() {
        @Override public void onReceive(android.content.Context c, android.content.Intent i) { view.appsDirty = true; if (view.isShown()) view.onShown(); }
    };

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        view = new GlassView(this);
        setContentView(view);
        android.content.IntentFilter f = new android.content.IntentFilter();
        f.addAction(android.content.Intent.ACTION_PACKAGE_ADDED);
        f.addAction(android.content.Intent.ACTION_PACKAGE_REMOVED);
        f.addAction(android.content.Intent.ACTION_PACKAGE_CHANGED);
        f.addDataScheme("package");
        try { registerReceiver(pkgs, f); } catch (Throwable t) { view.alwaysReload = true; }
        view.setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
    }

    @Override protected void onResume() { super.onResume(); view.onShown(); }

    @Override public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == 42) view.loadWatching();
    }

    @Override protected void onPause() { super.onPause(); view.onHidden(); }
    @Override protected void onDestroy() { try { unregisterReceiver(pkgs); } catch (Throwable t) { } view.release(); super.onDestroy(); }

    @Override public boolean onKeyDown(int code, KeyEvent e) {
        if (view.handleKey(code)) return true;
        return super.onKeyDown(code, e);
    }

    @Override public void onBackPressed() {
        // A home screen stays put; Back only closes our own panels.
        view.handleKey(KeyEvent.KEYCODE_BACK);
    }

    @Override public void onTrimMemory(int level) { super.onTrimMemory(level); view.trim(level); }
}
