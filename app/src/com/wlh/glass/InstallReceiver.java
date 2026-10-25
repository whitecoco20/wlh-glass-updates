package com.wlh.glass;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;

/** Receives Android's install status; when Android needs the user's OK it hands us its confirm screen to show. */
public class InstallReceiver extends BroadcastReceiver {
    @Override public void onReceive(Context c, Intent i) {
        int status = i.getIntExtra(PackageInstaller.EXTRA_STATUS, -999);
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            Intent confirm = i.getParcelableExtra(Intent.EXTRA_INTENT);
            if (confirm != null) {
                confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                try { c.startActivity(confirm); } catch (Throwable ignored) { }
            }
        } else if (status != PackageInstaller.STATUS_SUCCESS) {
            c.getSharedPreferences("glass", Context.MODE_PRIVATE).edit()
                    .putString("updLastError", String.valueOf(i.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE))).apply();
        }
    }
}
