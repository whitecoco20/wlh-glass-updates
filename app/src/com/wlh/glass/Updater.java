package com.wlh.glass;

import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Checks a public version.json and installs newer signed APKs through Android's own installer. */
public class Updater {
    static final String FEED = "https://raw.githubusercontent.com/whitecoco20/wlh-glass-updates/main/version.json";

    interface Listener { void changed(); }

    final Context ctx;
    final SharedPreferences prefs;
    final Handler ui = new Handler(Looper.getMainLooper());
    final Listener listener;
    volatile String state = "";      // shown in Settings
    volatile boolean busy = false;

    Updater(Context c, Listener l) {
        ctx = c.getApplicationContext();
        prefs = ctx.getSharedPreferences("glass", Context.MODE_PRIVATE);
        listener = l;
    }

    int myCode() {
        try { return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionCode; } catch (Throwable t) { return 0; }
    }
    String myName() {
        try { return ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0).versionName; } catch (Throwable t) { return "?"; }
    }

    int latestCode() { return prefs.getInt("updCode", 0); }
    String latestName() { return prefs.getString("updName", ""); }
    boolean updateReady() { return latestCode() > myCode() && prefs.getString("updApk", "").length() > 0; }

    void checkIfDue() {
        if (System.currentTimeMillis() - prefs.getLong("updChecked", 0) > 6 * 3600 * 1000L) check();
    }

    void check() {
        if (busy) return;
        busy = true; state = "Checking…"; notifyUi();
        new Thread(new Runnable() { public void run() {
            try {
                JSONObject j = new JSONObject(get(FEED));
                prefs.edit().putInt("updCode", j.getInt("versionCode")).putString("updName", j.optString("versionName", ""))
                        .putString("updApk", j.getString("apk")).putString("updNotes", j.optString("notes", ""))
                        .putLong("updChecked", System.currentTimeMillis()).apply();
                state = "";
            } catch (Throwable t) {
                prefs.edit().putLong("updChecked", System.currentTimeMillis()).apply();
                state = "Couldn't reach the update server";
            }
            busy = false; notifyUi();
        } }).start();
    }

    /** Step 1: Android must allow WLH Glass to install apps (asked once). Step 2: download. Step 3: Android's install prompt. */
    void install() {
        if (busy || !updateReady()) return;
        if (Build.VERSION.SDK_INT >= 26 && !canInstall()) {
            try {
                Intent i = new Intent("android.settings.MANAGE_UNKNOWN_APP_SOURCES", Uri.parse("package:" + ctx.getPackageName()));
                ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                state = "Allow WLH Glass to install apps, then press OK again";
            } catch (Throwable t) { state = "Open Settings › Apps › WLH Glass › Install unknown apps"; }
            notifyUi();
            return;
        }
        busy = true; state = "Downloading…"; notifyUi();
        final String url = prefs.getString("updApk", "");
        new Thread(new Runnable() { public void run() {
            PackageInstaller.Session s = null;
            try {
                PackageInstaller pi = ctx.getPackageManager().getPackageInstaller();
                PackageInstaller.SessionParams sp = new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
                sp.setAppPackageName(ctx.getPackageName());
                int id = pi.createSession(sp);
                s = pi.openSession(id);
                HttpURLConnection h = open(url);
                long total = h.getContentLength(), done = 0;
                InputStream in = h.getInputStream();
                OutputStream out = s.openWrite("wlh-glass.apk", 0, total > 0 ? total : -1);
                byte[] buf = new byte[64 * 1024]; int n; int lastPct = -1;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n); done += n;
                    if (total > 0) { int pct = (int) (done * 100 / total); if (pct != lastPct && pct % 10 == 0) { lastPct = pct; state = "Downloading " + pct + "%"; notifyUi(); } }
                }
                s.fsync(out); out.close(); in.close(); h.disconnect();
                Intent cb = new Intent(ctx, InstallReceiver.class);
                int flags = PendingIntent.FLAG_UPDATE_CURRENT | (Build.VERSION.SDK_INT >= 31 ? 0x02000000 /* FLAG_MUTABLE */ : 0);
                PendingIntent piCb = PendingIntent.getBroadcast(ctx, 7, cb, flags);
                state = "Confirm the install on screen";
                s.commit(piCb.getIntentSender());
                s.close();
            } catch (Throwable t) {
                if (s != null) try { s.abandon(); } catch (Throwable ignored) { }
                state = "Update failed: " + t.getClass().getSimpleName();
            }
            busy = false; notifyUi();
        } }).start();
    }

    boolean canInstall() {
        try { return (Boolean) PackageManager.class.getMethod("canRequestPackageInstalls").invoke(ctx.getPackageManager()); }
        catch (Throwable t) { return true; }
    }

    static HttpURLConnection open(String u) throws Exception {
        for (int i = 0; i < 5; i++) {
            HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
            h.setConnectTimeout(10000); h.setReadTimeout(20000); h.setInstanceFollowRedirects(true);
            h.setRequestProperty("User-Agent", "WLHGlass");
            int code = h.getResponseCode();
            if (code >= 300 && code < 400 && h.getHeaderField("Location") != null) { u = h.getHeaderField("Location"); h.disconnect(); continue; }
            if (code != 200) throw new Exception("HTTP " + code);
            return h;
        }
        throw new Exception("Too many redirects");
    }

    static String get(String u) throws Exception {
        HttpURLConnection h = open(u);
        BufferedReader br = new BufferedReader(new InputStreamReader(h.getInputStream(), "UTF-8"));
        StringBuilder sb = new StringBuilder(); String line;
        while ((line = br.readLine()) != null) sb.append(line).append('\n');
        br.close(); h.disconnect();
        return sb.toString();
    }

    void notifyUi() { ui.post(new Runnable() { public void run() { if (listener != null) listener.changed(); } }); }
}
