package com.wlh.glass;

import android.app.ActivityManager;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.format.DateFormat;
import android.view.KeyEvent;
import android.view.View;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;

public class GlassView extends View {
    // ---------- data ----------
    static class App { String pkg, label; Drawable icon; }

    static final String[] FAV_PKGS = {
        "com.google.android.youtube.tv", "com.fctv77.tv", "ar.tvplayer.tv", "ch.protonvpn.android",
        "com.aos.aosoptv", "com.esaba.downloader", "org.videolan.vlc", "net.easyjoin.applauncher" };
    static final String[] FAV_BLURB = {
        "Videos, music and live streams.", "Live channels and on-demand TV.", "Your live TV guide and playlists.",
        "Connect securely before you stream.", "Live channels and sports.", "Get files and apps by link.",
        "Play any video or music file.", "All your side-loaded apps." };

    static final String[] CITY_NAME = { "Houston", "Dallas", "Austin", "San Antonio", "Miami", "New York", "Los Angeles", "Medellín", "Bogotá", "Mexico City" };
    static final double[] CITY_LAT = { 29.7604, 32.7767, 30.2672, 29.4241, 25.7617, 40.7128, 34.0522, 6.2442, 4.7110, 19.4326 };
    static final double[] CITY_LON = { -95.3698, -96.7970, -97.7431, -98.4936, -80.1918, -74.0060, -118.2437, -75.5812, -74.0721, -99.1332 };

    final List<App> favs = new ArrayList<App>();
    final List<App> all = new ArrayList<App>();
    final Handler ui = new Handler(Looper.getMainLooper());
    final SharedPreferences prefs;

    // ---------- state ----------
    static final int Z_NAV = 0, Z_TOP = 1, Z_HERO = 2, Z_APPS = 3, Z_QUICK = 4, Z_RECENT = 5;
    int zone = Z_APPS, navIdx = 0, topIdx = 0, heroIdx = 0, appIdx = 0, quickIdx = 0;
    boolean showAll = false, showSettings = false;
    int allIdx = 0, allScroll = 0, setIdx = 0;
    float[] lift = new float[16];

    boolean lowPower; int blurLevel; boolean celsius; boolean bootStart;
    // Weather location: any city or country the viewer types in (Open-Meteo geocoding).
    String placeName; double placeLat, placeLon; String placeCC; int placeGen = 0;
    // Clock time zone: the weather location's zone (default) or the TV's own.
    String placeTZ = ""; boolean tzFromPlace = true;
    java.util.TimeZone clockZone() {
        if (tzFromPlace && placeTZ != null && placeTZ.length() > 0) return java.util.TimeZone.getTimeZone(placeTZ);
        return java.util.TimeZone.getDefault();
    }
    SimpleDateFormat fmt(String pattern) { SimpleDateFormat f = new SimpleDateFormat(pattern, Locale.getDefault()); f.setTimeZone(clockZone()); return f; }
    /** True when the clock shows a different time than the TV's own zone. */
    boolean otherZone() { long t = System.currentTimeMillis(); return clockZone().getOffset(t) != java.util.TimeZone.getDefault().getOffset(t); }
    // Favorites: picked on the TV (Settings > Favorites). Empty = defaults below + auto-fill.
    final List<String> favPkgs = new ArrayList<String>(); boolean favCustom = false;
    // All apps screen: 0 = open apps, 1 = pick favorites, 2 = hide apps
    int allMode = 0;
    final HashSet<String> hidden = new HashSet<String>();
    final List<App> everything = new ArrayList<App>();   // all apps, hidden included
    List<App> cur() { return allMode == 2 ? everything : all; }
    // Recently opened (kept by WLH Glass, since Google TV keeps "Continue watching" to itself)
    final List<String> recentPkgs = new ArrayList<String>();
    final List<App> recent = new ArrayList<App>();
    int recentIdx = 0;
    // World clocks: up to 3 extra cities next to the main clock
    final List<String[]> clocks = new ArrayList<String[]>();
    // Network pill: VPN on/off and where your connection appears to come from
    boolean vpnOn = false, vpnSeen = false; String netWhere = null; long netAt = 0; boolean netBusy = false;
    // HDMI / TV inputs (only on Google TV sets that have them)
    final List<String[]> inputs = new ArrayList<String[]>();
    // Phone upload screen
    boolean showPhone = false; PhotoServer server; QR qr; String phoneUrl; String phoneLast = null; int phoneCount = 0;
    int setScroll = 0;
    // Amazon Fire TV: same app, but Amazon's own home, search and voice.
    boolean fireTv = false;
    String homeName() { return fireTv ? "Fire TV" : "Google TV"; }
    String wTemp = null, wFeels = "", wCond = "Loading weather…", wHiLo = ""; int wCode = -1; long wAt = 0; boolean wStale = false; boolean wBusy = false;
    String[] fDay = new String[3], fHi = new String[3], fLo = new String[3]; int[] fCode = { -1, -1, -1 };
    // Backgrounds: built-in "Sunset" plus any .jpg/.png/.webp you put in
    // /sdcard/Android/data/com.wlh.glass/files/backgrounds/ (adb push, or a file manager).
    // Clock: 0 = 24-hour (default), 1 = 12-hour, 2 = follow the TV's setting
    int clockMode;
    boolean use24() { return clockMode == 0 || (clockMode == 2 && DateFormat.is24HourFormat(getContext())); }
    static final int ROTATE = 999;
    java.io.File[] bgFiles = new java.io.File[0];
    int bgMode, bgIdx; // bgMode: index of a fixed picture, or ROTATE
    void scanBackgrounds() {
        try {
            java.io.File d = new java.io.File(getContext().getExternalFilesDir(null), "backgrounds");
            if (!d.exists()) d.mkdirs();
            java.io.File[] f = d.listFiles(new java.io.FileFilter() { public boolean accept(java.io.File x) {
                String n = x.getName().toLowerCase(Locale.US); return x.isFile() && (n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp")); } });
            if (f == null) f = new java.io.File[0];
            java.util.Arrays.sort(f);
            bgFiles = f;
        } catch (Throwable t) { bgFiles = new java.io.File[0]; }
        if (bgIdx >= bgCount()) { bgIdx = 0; if (bgMode != ROTATE) bgMode = 0; dropBitmaps(); }
    }
    int bgCount() { return 1 + bgFiles.length; }
    String bgName(int i) {
        if (i <= 0 || i > bgFiles.length) return "Sunset";
        String n = bgFiles[i - 1].getName(); int dot = n.lastIndexOf('.');
        n = dot > 0 ? n.substring(0, dot) : n;
        return n.length() > 0 ? Character.toUpperCase(n.charAt(0)) + n.substring(1) : n;
    }
    final Runnable bgTick = new Runnable() { public void run() {
        if (!dreamMode && bgMode == ROTATE && bgCount() > 1) { bgIdx = (bgIdx + 1) % bgCount(); prefs.edit().putInt("bgIdx", bgIdx).apply(); dropBitmaps(); invalidate(); }
        ui.postDelayed(this, 15 * 60 * 1000L);
    } };
    void nextBackground() {
        scanBackgrounds();
        if (bgCount() < 2) { openPhone(); return; }
        bgIdx = (bgIdx + 1) % bgCount();
        if (bgMode != ROTATE) bgMode = bgIdx;
        flash("Background: " + bgName(bgIdx));
        prefs.edit().putInt("bgMode", bgMode).putInt("bgIdx", bgIdx).apply();
        dropBitmaps();
    }
    boolean dreamMode = false;
    // Screensaver: shows every background in turn (2 minutes each). Does not change the home screen's choice.
    static final long DREAM_STEP = 2 * 60 * 1000L;
    final Runnable dreamTick = new Runnable() { public void run() {
        scanBackgrounds();
        if (bgCount() > 1) { bgIdx = (bgIdx + 1) % bgCount(); dropBitmaps(); invalidate(); }
        ui.postDelayed(this, DREAM_STEP);
    } };
    Updater updater;
    String wAlert = null;
    String msg = null; long msgUntil = 0;
    static class WItem { String title, sub, intentUri, pkg, poster; Bitmap img; }
    final List<WItem> watch = new ArrayList<WItem>();
    boolean showWatch = false, watchLoading = false; int watchIdx = 0, watchScroll = 0; String watchMsg = "";
    static final String EPG = "com.android.providers.tv.permission.READ_EPG_DATA";
    static final int[] SLEEP_STEPS = { 0, 15, 30, 60, 90, 120 };
    void flash(String m) { msg = m; msgUntil = System.currentTimeMillis() + 5000; invalidate(); ui.postDelayed(new Runnable() { public void run() { invalidate(); } }, 5100); }
    final Runnable wxTick = new Runnable() { public void run() { fetchWeather(); ui.postDelayed(this, 30 * 60 * 1000L); } };

    // ---------- drawing ----------
    Bitmap wall, blur;
    final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    final Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
    final RectF screen = new RectF(0, 0, 1920, 1080);
    final Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Paint sp = new Paint(Paint.ANTI_ALIAS_FLAG);
    final Path path = new Path();
    final RectF r = new RectF();
    final Rect full = new Rect();
    final Typeface light = Typeface.create("sans-serif-light", Typeface.NORMAL);
    final Typeface reg = Typeface.create("sans-serif", Typeface.NORMAL);
    final Typeface med = Typeface.create("sans-serif-medium", Typeface.NORMAL);
    final Typeface bold = Typeface.create("sans-serif", Typeface.BOLD);
    float k = 1f; // screen px per design px
    static final String[] ACCENT_NAME = { "Blue", "Gold", "Green", "Pink", "Purple", "White" };
    static final int[] ACCENT_COLOR = { 0xFF9FD6FF, 0xFFFFD27A, 0xFF8CF0B4, 0xFFFF9FD0, 0xFFC3A6FF, 0xFFFFFFFF };
    int accentIdx = 0;
    int ACCENT = 0xFF9FD6FF;

    final String[] NAV = { "Home", "Apps", "Live TV", "Media", "Settings" };
    final String[] QUICK = { "Bluetooth", "Sound", "Picture", "Sleep", "Background", "TV settings" };
    final String[] TOP = { "Search", "Voice", "Wi-Fi" };

    final Runnable tick = new Runnable() { public void run() { if (!dreamMode) checkNet(false); invalidate(); scheduleTick(); } };

    public GlassView(Context c) {
        super(c);
        setFocusable(true);
        setFocusableInTouchMode(true);
        prefs = c.getSharedPreferences("glass", Context.MODE_PRIVATE);
        lowPower = prefs.getBoolean("lowPower", false);
        blurLevel = prefs.getInt("blur", 1);
        celsius = prefs.getBoolean("celsius", false);
        loadPlace();
        placeTZ = prefs.getString("placeTZ", "");
        tzFromPlace = prefs.getBoolean("tzFromPlace", true);
        try { fireTv = c.getPackageManager().hasSystemFeature("amazon.hardware.fire_tv") || "Amazon".equalsIgnoreCase(android.os.Build.MANUFACTURER); } catch (Throwable t) { }
        loadFavList();
        accentIdx = Math.max(0, Math.min(ACCENT_COLOR.length - 1, prefs.getInt("accent", 0))); ACCENT = ACCENT_COLOR[accentIdx];
        for (String x : prefs.getString("hidden", "").split(",")) if (x.length() > 0) hidden.add(x);
        for (String x : prefs.getString("recent", "").split(",")) if (x.length() > 0) recentPkgs.add(x);
        for (String x : prefs.getString("clocks", "").split(";;")) { String[] a = x.split("\\|"); if (a.length == 2) clocks.add(a); }
        bootStart = prefs.getBoolean("bootStart", true);
        clockMode = prefs.getInt("clock", 0);
        bgMode = prefs.getInt("bgMode", 0);
        if (bgMode != ROTATE && bgMode > 50) bgMode = 0;
        bgIdx = bgMode == ROTATE ? prefs.getInt("bgIdx", 0) : bgMode;
        scanBackgrounds();
        sp.setStyle(Paint.Style.STROKE);
        loadSavedWeather();
        loadApps();
        updater = new Updater(c, new Updater.Listener() { public void changed() { invalidate(); } });
    }

    // ---------- lifecycle ----------
    boolean appsDirty = false, alwaysReload = false;
    void onShown() {
        if (appsDirty || alwaysReload || favs.isEmpty()) { appsDirty = false; loadApps(); }
        scheduleTick();
        if (wStale || System.currentTimeMillis() - wAt > 15 * 60 * 1000L) fetchWeather();
        ui.removeCallbacks(wxTick); ui.postDelayed(wxTick, 30 * 60 * 1000L);
        ui.removeCallbacks(bgTick); ui.postDelayed(bgTick, 15 * 60 * 1000L);
        if (!dreamMode) { updater.checkIfDue(); loadInputs(); watchNetwork(); checkNet(System.currentTimeMillis() - netAt > 60 * 1000L); }
        else { ui.removeCallbacks(dreamTick); ui.postDelayed(dreamTick, DREAM_STEP); }
        scanBackgrounds();
        requestFocus();
        invalidate();
    }
    void onHidden() { if (showPhone) closePhone(); ui.removeCallbacks(dreamTick); ui.removeCallbacks(tick); ui.removeCallbacks(wxTick); ui.removeCallbacks(bgTick); }
    void release() { if (server != null) { server.stop(); server = null; } ui.removeCallbacksAndMessages(null); if (wall != null) wall.recycle(); if (blur != null) blur.recycle(); wall = blur = null; }
    void trim(int level) {
        if (level >= 20) { for (App a : all) if (!isFav(a.pkg)) a.icon = null; }
        if (level >= 20 && !isShown()) dropBitmaps();
    }
    boolean isFav(String pkg) { for (App f : favs) if (f.pkg.equals(pkg)) return true; return false; }

    void loadFavList() {
        favPkgs.clear();
        String s = prefs.getString("favs", "");
        for (String x : s.split(",")) if (x.trim().length() > 0) favPkgs.add(x.trim());
        favCustom = !favPkgs.isEmpty();
        if (!favCustom) for (String x : FAV_PKGS) favPkgs.add(x);
    }
    void saveFavList() {
        StringBuilder b = new StringBuilder();
        for (String x : favPkgs) { if (b.length() > 0) b.append(','); b.append(x); }
        prefs.edit().putString("favs", b.toString()).apply();
        favCustom = !favPkgs.isEmpty();
        if (!favCustom) loadFavList();
    }
    void openPicker() {
        // Start from what is on the row now, so the picker shows the current favorites ticked.
        favPkgs.clear(); for (App f : favs) favPkgs.add(f.pkg);
        showSettings = false; allMode = 1; showAll = true; allIdx = 0; allScroll = 0;
    }
    void togglePick(String pkg) {
        if (favPkgs.remove(pkg)) return;
        if (favPkgs.size() >= 8) { flash("You already have 8. Remove one first."); return; }
        favPkgs.add(pkg);
    }
    void finishPicker() {
        allMode = 0; showAll = false;
        if (favPkgs.isEmpty()) { prefs.edit().remove("favs").apply(); loadFavList(); flash("Favorites reset to default"); }
        else { saveFavList(); flash("Favorites saved (" + favPkgs.size() + ")"); }
        loadApps(); appIdx = 0;
    }

    void scheduleTick() {
        ui.removeCallbacks(tick);
        long now = System.currentTimeMillis();
        ui.postDelayed(tick, 60000 - (now % 60000) + 200);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        k = w / 1920f;
        full.set(0, 0, w, h);
        if (wall != null) { wall.recycle(); wall = null; }
        if (blur != null) { blur.recycle(); blur = null; }
    }

    // ---------- apps ----------
    void loadApps() {
        PackageManager pm = getContext().getPackageManager();
        favs.clear();
        for (String pkg : favPkgs) {
            App a = app(pm, pkg, true);
            if (a != null) favs.add(a);
        }
        all.clear(); everything.clear();
        HashSet<String> seen = new HashSet<String>();
        String[] cats = { Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER };
        for (String cat : cats) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            List<ResolveInfo> res = pm.queryIntentActivities(q, 0);
            for (ResolveInfo ri : res) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getContext().getPackageName()) || !seen.add(pkg)) continue;
                App a = app(pm, pkg, false);
                if (a != null) { everything.add(a); if (!hidden.contains(pkg)) all.add(a); }
            }
        }
        Comparator<App> byName = new Comparator<App>() {
            public int compare(App x, App y) { return x.label.compareToIgnoreCase(y.label); }
        };
        Collections.sort(all, byName); Collections.sort(everything, byName);
        // Fewer than 8 of the listed favorites installed (e.g. someone else's TV)? Fill the row from the app list.
        for (App a : all) {
            if (favCustom || favs.size() >= 8) break;
            boolean have = false; for (App f : favs) if (f.pkg.equals(a.pkg)) { have = true; break; }
            if (!have && !a.pkg.startsWith("com.android.") && !a.pkg.equals("com.google.android.tv.remote.service")) { App f = new App(); f.pkg = a.pkg; f.label = a.label; f.icon = loadIcon(pm, safeInfo(pm, a.pkg)); favs.add(f); }
        }
        if (appIdx >= favs.size()) appIdx = Math.max(0, favs.size() - 1);
        buildRecent();
    }

    void buildRecent() {
        recent.clear();
        for (String pkg : recentPkgs) {
            for (App a : everything) if (a.pkg.equals(pkg)) { App r = new App(); r.pkg = a.pkg; r.label = a.label; recent.add(r); break; }
            if (recent.size() >= 6) break;
        }
        if (recentIdx >= recent.size()) recentIdx = Math.max(0, recent.size() - 1);
    }

    void noteLaunch(String pkg) {
        if (pkg == null || pkg.equals(getContext().getPackageName()) || pkg.startsWith("com.android.settings") || pkg.equals("com.android.tv.settings")) return;
        recentPkgs.remove(pkg); recentPkgs.add(0, pkg);
        while (recentPkgs.size() > 12) recentPkgs.remove(recentPkgs.size() - 1);
        StringBuilder b = new StringBuilder();
        for (String x : recentPkgs) { if (b.length() > 0) b.append(','); b.append(x); }
        prefs.edit().putString("recent", b.toString()).apply();
        buildRecent();
    }

    void saveHidden() {
        StringBuilder b = new StringBuilder();
        for (String x : hidden) { if (b.length() > 0) b.append(','); b.append(x); }
        prefs.edit().putString("hidden", b.toString()).apply();
    }
    void openHider() { showSettings = false; allMode = 2; showAll = true; allIdx = 0; allScroll = 0; }
    void finishHider() { allMode = 0; showAll = false; saveHidden(); loadApps(); flash(hidden.isEmpty() ? "No hidden apps" : hidden.size() + " app" + (hidden.size() == 1 ? "" : "s") + " hidden from All apps"); }

    App app(PackageManager pm, String pkg, boolean withIcon) {
        try {
            ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            if (!ai.enabled) return null;
            if (launchIntent(pm, pkg) == null) return null;
            App a = new App();
            a.pkg = pkg;
            a.label = String.valueOf(pm.getApplicationLabel(ai));
            if (withIcon) a.icon = loadIcon(pm, ai);
            return a;
        } catch (PackageManager.NameNotFoundException e) { return null; }
    }

    ApplicationInfo safeInfo(PackageManager pm, String pkg) { try { return pm.getApplicationInfo(pkg, 0); } catch (Throwable t) { return null; } }

    Drawable loadIcon(PackageManager pm, ApplicationInfo ai) {
        if (ai == null) return null;
        try { return pm.getApplicationIcon(ai); } catch (Throwable t) { return null; }
    }

    Drawable iconFor(App a) {
        if (a.icon == null) {
            for (App f : favs) if (f.pkg.equals(a.pkg)) { a.icon = f.icon; break; }
            if (a.icon == null) try { a.icon = loadIcon(getContext().getPackageManager(), getContext().getPackageManager().getApplicationInfo(a.pkg, 0)); } catch (Throwable t) { }
        }
        return a.icon;
    }

    Intent launchIntent(PackageManager pm, String pkg) {
        Intent i = pm.getLeanbackLaunchIntentForPackage(pkg);
        if (i == null) i = pm.getLaunchIntentForPackage(pkg);
        return i;
    }

    void launch(String pkg) {
        Intent i = launchIntent(getContext().getPackageManager(), pkg);
        if (i != null) { start(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); noteLaunch(pkg); }
        else flash(pkg.equals("ar.tvplayer.tv") ? "TiviMate isn't installed" : pkg.equals("org.videolan.vlc") ? "VLC isn't installed" : "That app isn't installed");
    }

    void start(Intent i) {
        try { getContext().startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (ActivityNotFoundException e) {
            try { getContext().startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Throwable t) { }
        } catch (Throwable t) { }
    }

    // ---------- HDMI / TV inputs ----------
    void loadInputs() {
        inputs.clear();
        try {
            android.media.tv.TvInputManager tm = (android.media.tv.TvInputManager) getContext().getSystemService(Context.TV_INPUT_SERVICE);
            if (tm == null) return;
            for (android.media.tv.TvInputInfo ti : tm.getTvInputList()) {
                if (!ti.isPassthroughInput()) continue;
                CharSequence lab = null;
                try { lab = (CharSequence) ti.getClass().getMethod("loadCustomLabel", Context.class).invoke(ti, getContext()); } catch (Throwable t) { }
                if (lab == null || lab.length() == 0) lab = ti.loadLabel(getContext());
                inputs.add(new String[] { ti.getId(), String.valueOf(lab) });
            }
        } catch (Throwable t) { inputs.clear(); }
    }
    void openInput(String id) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, android.media.tv.TvContract.buildChannelUriForPassthroughInput(id));
            getContext().startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable t) { flash("This TV can't switch inputs from here"); }
    }
    void showInputs() {
        loadInputs();
        if (inputs.isEmpty()) { flash("No HDMI inputs on this device"); return; }
        if (inputs.size() == 1) { openInput(inputs.get(0)[0]); return; }
        String[] labels = new String[inputs.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = inputs.get(i)[1];
        new android.app.AlertDialog.Builder(getContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Inputs")
                .setItems(labels, new android.content.DialogInterface.OnClickListener() { public void onClick(android.content.DialogInterface di, int i) { openInput(inputs.get(i)[0]); } })
                .show();
    }

    // ---------- network pill: VPN + where you appear to be ----------
    boolean vpnActive() {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            for (android.net.Network n : cm.getAllNetworks()) {
                android.net.NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
        } catch (Throwable t) { }
        // Some VPN apps only show up as a tunnel interface (tun0 / ppp0 / wg0).
        try {
            for (java.util.Enumeration<java.net.NetworkInterface> en = java.net.NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                java.net.NetworkInterface ni = en.nextElement();
                String n = ni.getName().toLowerCase(Locale.US);
                if (ni.isUp() && (n.startsWith("tun") || n.startsWith("ppp") || n.startsWith("wg") || n.startsWith("ipsec")) && ni.getInetAddresses().hasMoreElements()) return true;
            }
        } catch (Throwable t) { }
        return false;
    }
    void checkNet(boolean force) {
        boolean v = vpnActive();
        boolean changed = !vpnSeen || v != vpnOn;
        vpnOn = v; vpnSeen = true;
        if (netBusy || (!force && !changed && System.currentTimeMillis() - netAt < 10 * 60 * 1000L)) return;
        netBusy = true;
        new Thread(new Runnable() { public void run() {
            String where = null;
            try {
                JSONObject j = new JSONObject(Updater.get("https://ipwho.is/?fields=success,city,country_code,connection"));
                if (j.optBoolean("success", false)) {
                    where = j.optString("city", "") + ", " + j.optString("country_code", "");
                    JSONObject cn = j.optJSONObject("connection");
                    String isp = cn == null ? "" : cn.optString("isp", cn.optString("org", ""));
                    if (isp.length() > 0) where += " · " + shortIsp(isp);
                }
            } catch (Throwable t) { }
            if (where == null) try {
                JSONObject j = new JSONObject(Updater.get("https://ipapi.co/json/"));
                if (j.has("city")) { where = j.optString("city", "") + ", " + j.optString("country_code", ""); String org = j.optString("org", ""); if (org.length() > 0) where += " · " + shortIsp(org); }
            } catch (Throwable t) { }
            final String w = where;
            ui.post(new Runnable() { public void run() { netBusy = false; netAt = System.currentTimeMillis(); if (w != null) netWhere = w; invalidate(); } });
        } }).start();
    }
    static String shortIsp(String isp) {
        String s = isp.replaceAll("(?i)\\b(AS\\d+|inc\\.?|llc|ltd\\.?|ag|gmbh|s\\.a\\.|corporation|corp\\.?|communications?|holdings?|networks?|services|limited)\\b", "").replaceAll("[,.]", " ").trim().replaceAll("\\s+", " ");
        if (s.length() == 0) s = isp;
        return s.length() > 14 ? s.substring(0, 13) + "…" : s;
    }
    // Re-check whenever the TV's networks change (VPN connects or drops).
    boolean netWatch = false;
    void watchNetwork() {
        if (netWatch || dreamMode) return;
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
            cm.registerNetworkCallback(new android.net.NetworkRequest.Builder().removeCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build(),
                new android.net.ConnectivityManager.NetworkCallback() {
                    void again() { ui.removeCallbacks(netAgain); ui.postDelayed(netAgain, 3000); }
                    @Override public void onAvailable(android.net.Network n) { again(); }
                    @Override public void onLost(android.net.Network n) { again(); }
                });
            netWatch = true;
        } catch (Throwable t) { }
    }
    final Runnable netAgain = new Runnable() { public void run() { netAt = 0; checkNet(true); } };
    String netText() {
        String w = netWhere == null ? "Checking…" : netWhere;
        if (w.startsWith(", ")) w = w.substring(2);
        if (w.length() > 34) w = w.substring(0, 33) + "…";
        return (vpnOn ? "VPN · " : "") + w;
    }

    // ---------- add photos from a phone ----------
    java.io.File bgDir() { return new java.io.File(getContext().getExternalFilesDir(null), "backgrounds"); }
    void openPhone() {
        if (server == null) {
            server = new PhotoServer(bgDir(), new PhotoServer.Listener() {
                public void received(final String name) { ui.post(new Runnable() { public void run() { phoneCount++; phoneLast = name; scanBackgrounds(); invalidate(); } }); }
                public void removed(final String name) { ui.post(new Runnable() { public void run() { phoneLast = "Removed " + name; scanBackgrounds(); invalidate(); } }); }
            });
            if (!server.start()) { server = null; flash("Couldn't start the photo receiver. Try again in a minute."); return; }
        }
        phoneUrl = server.url();
        if (phoneUrl == null) { closePhone(); flash("Connect the TV to Wi-Fi or Ethernet first"); return; }
        qr = QR.encode(phoneUrl);
        phoneCount = 0; phoneLast = null; showPhone = true;
        invalidate();
    }
    void closePhone() {
        showPhone = false;
        if (server != null) { server.stop(); server = null; }
        qr = null;
        scanBackgrounds(); dropBitmaps(); invalidate();
        if (phoneCount > 0) flash(phoneCount + " photo" + (phoneCount == 1 ? "" : "s") + " added. Press Background to see them.");
    }
    void drawPhone(Canvas c) {
        p.setColor(0x99060816); c.drawRect(0, 0, 1920, 1080, p);
        glass(c, 260, 150, 1400, 780, 48, true);
        text(c, "Add photos from your phone", 330, 250, 44, bold, Color.WHITE, Paint.Align.LEFT);
        String[] steps = { "1.  Put your phone on the same Wi-Fi as the TV.", "2.  Point the phone camera at the code.", "3.  Tap the link and pick your photos.",
                           "    They become backgrounds and screensaver pictures." };
        for (int i = 0; i < steps.length; i++) text(c, steps[i], 330, 340 + i * 50, 26, reg, 0xE6FFFFFF, Paint.Align.LEFT);
        text(c, "Or type this in the phone's browser:", 330, 590, 22, reg, 0xB3FFFFFF, Paint.Align.LEFT);
        text(c, phoneUrl == null ? "" : phoneUrl, 330, 632, 30, med, ACCENT, Paint.Align.LEFT);
        String st = phoneLast == null ? "Waiting for photos…" : phoneCount > 0 && !phoneLast.startsWith("Removed") ? "Received " + phoneCount + "  ·  last: " + phoneLast : phoneLast;
        text(c, st, 330, 712, 24, med, phoneLast == null ? 0xB3FFFFFF : 0xFF8CF0B4, Paint.Align.LEFT);
        text(c, (bgCount() - 1) + " photos on the TV  ·  Back when you're done", 330, 860, 22, reg, 0xB3FFFFFF, Paint.Align.LEFT);
        if (qr != null) {
            float box = 520, x0 = 1080, y0 = 270;
            p.setColor(Color.WHITE); r.set(x0, y0, x0 + box, y0 + box); c.drawRoundRect(r, 28, 28, p);
            float cell = (box - 56) / qr.size, ox = x0 + 28 + (box - 56 - cell * qr.size) / 2, oy = y0 + 28 + (box - 56 - cell * qr.size) / 2;
            p.setColor(0xFF0B0D1A);
            for (int yy = 0; yy < qr.size; yy++) for (int xx = 0; xx < qr.size; xx++)
                if (qr.mod[yy][xx]) c.drawRect(ox + xx * cell, oy + yy * cell, ox + (xx + 1) * cell + 0.5f, oy + (yy + 1) * cell + 0.5f, p);
        }
    }

    // ---------- weather location ----------
    void loadPlace() {
        placeName = prefs.getString("placeName", null);
        if (placeName != null) {
            placeLat = Double.longBitsToDouble(prefs.getLong("placeLat", 0));
            placeLon = Double.longBitsToDouble(prefs.getLong("placeLon", 0));
            placeCC = prefs.getString("placeCC", "");
        } else {
            // Older versions stored an index into a fixed city list.
            int i = Math.max(0, Math.min(CITY_NAME.length - 1, prefs.getInt("city", 0)));
            placeName = CITY_NAME[i]; placeLat = CITY_LAT[i]; placeLon = CITY_LON[i];
            placeCC = i < 7 ? "US" : i < 9 ? "CO" : "MX";
        }
    }
    void setPlace(String name, double lat, double lon, String cc, String tz) {
        placeName = name; placeLat = lat; placeLon = lon; placeCC = cc == null ? "" : cc; placeGen++;
        placeTZ = tz == null ? "" : tz; prefs.edit().putString("placeTZ", placeTZ).apply();
        prefs.edit().putString("placeName", name).putLong("placeLat", Double.doubleToLongBits(lat))
                .putLong("placeLon", Double.doubleToLongBits(lon)).putString("placeCC", placeCC).apply();
        wAlert = null; clearWeather(); fetchWeather();
        flash("Weather: " + name);
        invalidate();
    }
    void setPlaceTZ(String tz) {
        if (tz == null || tz.equals(placeTZ)) return;
        placeTZ = tz; prefs.edit().putString("placeTZ", tz).apply(); invalidate();
    }
    void saveClocks() {
        StringBuilder b = new StringBuilder();
        for (String[] w : clocks) { if (b.length() > 0) b.append(";;"); b.append(w[0].replace("|", " ").replace(";", " ")).append('|').append(w[1]); }
        prefs.edit().putString("clocks", b.toString()).apply();
    }
    void addClock(final Place p) {
        if (p.tz == null || p.tz.length() == 0) {
            new Thread(new Runnable() { public void run() { p.tz = tzFor(p.lat, p.lon); ui.post(new Runnable() { public void run() {
                if (p.tz.length() == 0) flash("Couldn't find the time zone for " + p.name); else addClock(p); } }); } }).start();
            return;
        }
        if (clocks.size() >= 3) clocks.remove(0);
        String n = p.name.length() > 12 ? p.name.substring(0, 11) + "…" : p.name;
        clocks.add(new String[] { n, p.tz });
        saveClocks(); flash("Clock added: " + p.name); invalidate();
    }
    void manageClocks() {
        if (!(getContext() instanceof android.app.Activity)) return;
        final List<String> items = new ArrayList<String>();
        for (String[] w : clocks) items.add("Remove " + w[0]);
        if (clocks.size() < 3) items.add("+ Add a city");
        new android.app.AlertDialog.Builder(getContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("World clocks (up to 3)")
                .setItems(items.toArray(new String[0]), new android.content.DialogInterface.OnClickListener() { public void onClick(android.content.DialogInterface di, int i) {
                    if (i < clocks.size()) { clocks.remove(i); saveClocks(); invalidate(); } else askPlace(1);
                } })
                .setNegativeButton("Done", null).show();
    }

    String placeKey() { return placeName + "|" + placeLat + "|" + placeLon; }
    String placeShort() { String n = placeName == null ? "" : placeName; return n.length() > 18 ? n.substring(0, 17) + "…" : n; }

    static class Place { String label, name, cc, tz; double lat, lon; }

    /** Opens the TV keyboard; the viewer types a city or a country, then picks from the matches. */
    void askPlace(final int target) { // 0 = weather location, 1 = add a world clock
        if (!(getContext() instanceof android.app.Activity)) return;
        final android.widget.EditText in = new android.widget.EditText(getContext());
        in.setSingleLine(true);
        in.setHint("City or country, e.g. Paris or Japan");
        in.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
        in.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        int pad = (int) (24 * getResources().getDisplayMetrics().density);
        android.widget.FrameLayout box = new android.widget.FrameLayout(getContext());
        box.setPadding(pad, pad / 2, pad, 0); box.addView(in);
        final android.app.AlertDialog d = new android.app.AlertDialog.Builder(getContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(target == 1 ? "Add a world clock" : "Weather location").setView(box)
                .setPositiveButton("Search", null).setNegativeButton("Cancel", null).create();
        final Runnable go = new Runnable() { public void run() {
            String q = in.getText().toString().trim();
            if (q.length() < 2) return;
            d.dismiss(); searchPlace(q, target);
        } };
        in.setOnEditorActionListener(new android.widget.TextView.OnEditorActionListener() {
            public boolean onEditorAction(android.widget.TextView v, int a, KeyEvent e) { go.run(); return true; } });
        d.setOnShowListener(new android.content.DialogInterface.OnShowListener() { public void onShow(android.content.DialogInterface di) {
            d.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener() { public void onClick(View v) { go.run(); } });
            in.requestFocus();
        } });
        d.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        d.show();
    }

    void searchPlace(final String query, final int target) {
        flash("Searching " + query + "…");
        new Thread(new Runnable() { public void run() {
            final List<Place> out = new ArrayList<Place>();
            try {
                // "Paris, France" -> search "Paris", keep matches whose region or country contains "France".
                String name = query, filter = null;
                int comma = query.indexOf(',');
                if (comma > 0) { name = query.substring(0, comma).trim(); filter = query.substring(comma + 1).trim().toLowerCase(Locale.ROOT); }
                String u = "https://geocoding-api.open-meteo.com/v1/search?count=10&language=en&format=json&name=" + java.net.URLEncoder.encode(name, "UTF-8");
                org.json.JSONArray r = new JSONObject(Updater.get(u)).optJSONArray("results");
                if (r != null) for (int i = 0; i < r.length(); i++) {
                    JSONObject o = r.getJSONObject(i);
                    String nm = o.optString("name"), a1 = o.optString("admin1", ""), co = o.optString("country", ""), cc = o.optString("country_code", ""), fc = o.optString("feature_code", "");
                    if (filter != null && filter.length() > 0 && !(a1.toLowerCase(Locale.ROOT).contains(filter) || co.toLowerCase(Locale.ROOT).contains(filter) || cc.equalsIgnoreCase(filter))) continue;
                    if (fc.startsWith("PCL")) {
                        // A country: use its capital for the weather.
                        Place cap = capitalOf(cc, co);
                        if (cap != null) { if (cap.tz == null || cap.tz.length() == 0) cap.tz = tzFor(cap.lat, cap.lon); out.add(0, cap); continue; }
                    }
                    Place p = new Place();
                    p.name = nm; p.cc = cc; p.tz = o.optString("timezone", ""); p.lat = o.getDouble("latitude"); p.lon = o.getDouble("longitude");
                    StringBuilder lb = new StringBuilder(nm);
                    if (a1.length() > 0 && !a1.equals(nm)) lb.append(", ").append(a1);
                    if (co.length() > 0 && !co.equals(nm)) lb.append(", ").append(co);
                    p.label = lb.toString();
                    out.add(p);
                    if (out.size() >= 8) break;
                }
            } catch (Throwable t) { }
            ui.post(new Runnable() { public void run() { showPlaces(query, out, target); } });
        } }).start();
    }

    Place capitalOf(String cc, String country) {
        try {
            org.json.JSONArray a = new org.json.JSONArray(Updater.get("https://restcountries.com/v3.1/alpha/" + cc + "?fields=capital,capitalInfo"));
            JSONObject o = a.length() > 0 ? a.getJSONObject(0) : null;
            if (o == null) return null;
            String cap = o.getJSONArray("capital").getString(0);
            org.json.JSONArray ll = o.getJSONObject("capitalInfo").getJSONArray("latlng");
            Place p = new Place(); p.name = cap; p.cc = cc; p.lat = ll.getDouble(0); p.lon = ll.getDouble(1);
            p.label = cap + ", " + country + " (capital)";
            return p;
        } catch (Throwable t) {
            try {
                JSONObject o = new JSONObject(Updater.get("https://restcountries.com/v3.1/alpha/" + cc + "?fields=capital,capitalInfo"));
                String cap = o.getJSONArray("capital").getString(0);
                org.json.JSONArray ll = o.getJSONObject("capitalInfo").getJSONArray("latlng");
                Place p = new Place(); p.name = cap; p.cc = cc; p.lat = ll.getDouble(0); p.lon = ll.getDouble(1);
                p.label = cap + ", " + country + " (capital)";
                return p;
            } catch (Throwable t2) { return null; }
        }
    }

    /** Time zone for a spot on the map (Open-Meteo works it out). */
    static String tzFor(double lat, double lon) {
        try { return new JSONObject(Updater.get("https://api.open-meteo.com/v1/forecast?latitude=" + lat + "&longitude=" + lon + "&current=temperature_2m&timezone=auto")).optString("timezone", ""); }
        catch (Throwable t) { return ""; }
    }

    void showPlaces(String query, final List<Place> list, final int target) {
        if (list.isEmpty()) { flash("No place found for \"" + query + "\". Try another spelling."); return; }
        if (!(getContext() instanceof android.app.Activity) || ((android.app.Activity) getContext()).isFinishing()) return;
        String[] labels = new String[list.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = list.get(i).label;
        new android.app.AlertDialog.Builder(getContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle(target == 1 ? "Pick the city for the clock" : "Pick your location")
                .setItems(labels, new android.content.DialogInterface.OnClickListener() { public void onClick(android.content.DialogInterface di, int i) {
                    Place p = list.get(i); if (target == 1) addClock(p); else setPlace(p.name, p.lat, p.lon, p.cc, p.tz);
                } })
                .setNegativeButton("Search again", new android.content.DialogInterface.OnClickListener() { public void onClick(android.content.DialogInterface di, int i) { askPlace(target); } })
                .show();
    }

    // ---------- weather (Open-Meteo, no key) ----------
    void fetchWeather() {
        if (wBusy) return;
        wBusy = true;
        final int c = placeGen; final double la = placeLat, lo = placeLon; final String key = placeKey(), cc = placeCC; final boolean cel = celsius;
        new Thread(new Runnable() { public void run() {
            String body = null;
            try {
                String u = "https://api.open-meteo.com/v1/forecast?latitude=" + la + "&longitude=" + lo
                        + "&current=temperature_2m,apparent_temperature,weather_code"
                        + "&daily=weather_code,temperature_2m_max,temperature_2m_min&forecast_days=3&timezone=auto"
                        + (cel ? "" : "&temperature_unit=fahrenheit");
                HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
                h.setConnectTimeout(8000); h.setReadTimeout(8000);
                BufferedReader br = new BufferedReader(new InputStreamReader(h.getInputStream(), "UTF-8"));
                StringBuilder sb = new StringBuilder(); String line;
                while ((line = br.readLine()) != null) sb.append(line);
                br.close(); h.disconnect();
                body = sb.toString();
                new JSONObject(body).getJSONObject("current");
            } catch (Throwable e) { body = null; }
            final String b = body;
            ui.post(new Runnable() { public void run() {
                wBusy = false;
                if (b != null && c == placeGen && cel == celsius && applyWeather(b, System.currentTimeMillis())) {
                    prefs.edit().putString("wx", b).putLong("wxAt", wAt).putString("wxPlace", key).putBoolean("wxCel", cel).apply();
                    wStale = false;
                    fetchAlerts(c, la, lo, cc);
                } else if (b == null) {
                    if (wTemp == null) wCond = "Weather unavailable";
                    wStale = true;
                }
                invalidate();
            } });
        } }).start();
    }

    // The National Weather Service only covers the US.
    void fetchAlerts(final int c, final double la, final double lo, String cc) {
        if (!"US".equalsIgnoreCase(cc)) { wAlert = null; return; }
        new Thread(new Runnable() { public void run() {
            String a = null;
            try {
                JSONObject j = new JSONObject(Updater.get("https://api.weather.gov/alerts/active?point=" + String.format(Locale.US, "%.4f,%.4f", la, lo)));
                org.json.JSONArray f = j.getJSONArray("features");
                if (f.length() > 0) {
                    JSONObject pr = f.getJSONObject(0).getJSONObject("properties");
                    a = pr.optString("event", "Weather alert");
                    String end = pr.isNull("ends") ? pr.optString("expires", "") : pr.optString("ends", "");
                    if (end.length() >= 16) {
                        try {
                            Date d = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(end);
                            a += " until " + fmt(use24() ? "EEE HH:mm" : "EEE h a").format(d);
                        } catch (Throwable ignored) { }
                    }
                    if (f.length() > 1) a += "  (+" + (f.length() - 1) + " more)";
                }
            } catch (Throwable t) { a = wAlert; }
            final String fa = a;
            ui.post(new Runnable() { public void run() { if (c == placeGen) { wAlert = fa; invalidate(); } } });
        } }).start();
    }

    void loadSavedWeather() {
        String b = prefs.getString("wx", null);
        if (b != null && placeKey().equals(prefs.getString("wxPlace", "")) && prefs.getBoolean("wxCel", false) == celsius) {
            if (applyWeather(b, prefs.getLong("wxAt", 0))) wStale = true;
        }
    }

    boolean applyWeather(String body, long at) {
        try {
            JSONObject j = new JSONObject(body);
            JSONObject cur = j.getJSONObject("current");
            String tz = j.optString("timezone", "");
            if (tz.length() > 0 && !tz.equals("GMT")) setPlaceTZ(tz);
            JSONObject d = j.getJSONObject("daily");
            wTemp = Math.round(cur.getDouble("temperature_2m")) + "°";
            wFeels = "Feels " + Math.round(cur.getDouble("apparent_temperature")) + "°";
            wCode = cur.getInt("weather_code");
            wCond = condition(wCode);
            wHiLo = "H " + Math.round(d.getJSONArray("temperature_2m_max").getDouble(0)) + "°  L "
                    + Math.round(d.getJSONArray("temperature_2m_min").getDouble(0)) + "°";
            SimpleDateFormat in = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            SimpleDateFormat out = new SimpleDateFormat("EEE", Locale.getDefault());
            for (int i = 0; i < 3 && i < d.getJSONArray("time").length(); i++) {
                fDay[i] = i == 0 ? "Today" : out.format(in.parse(d.getJSONArray("time").getString(i)));
                fHi[i] = Math.round(d.getJSONArray("temperature_2m_max").getDouble(i)) + "°";
                fLo[i] = Math.round(d.getJSONArray("temperature_2m_min").getDouble(i)) + "°";
                fCode[i] = d.getJSONArray("weather_code").getInt(i);
            }
            wAt = at;
            return true;
        } catch (Throwable t) { return false; }
    }

    static String condition(int c) {
        if (c == 0) return "Clear";
        if (c <= 2) return "Partly cloudy";
        if (c == 3) return "Cloudy";
        if (c == 45 || c == 48) return "Fog";
        if (c >= 51 && c <= 57) return "Drizzle";
        if (c >= 61 && c <= 67) return "Rain";
        if (c >= 71 && c <= 77) return "Snow";
        if (c >= 80 && c <= 82) return "Showers";
        if (c >= 85 && c <= 86) return "Snow showers";
        if (c >= 95) return "Thunderstorm";
        return "—";
    }

    // ---------- keys ----------
    boolean handleKey(int code) {
        boolean ok = code == KeyEvent.KEYCODE_DPAD_CENTER || code == KeyEvent.KEYCODE_ENTER || code == KeyEvent.KEYCODE_NUMPAD_ENTER;
        boolean back = code == KeyEvent.KEYCODE_BACK || code == KeyEvent.KEYCODE_ESCAPE;
        int dx = code == KeyEvent.KEYCODE_DPAD_LEFT ? -1 : code == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : 0;
        int dy = code == KeyEvent.KEYCODE_DPAD_UP ? -1 : code == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : 0;
        if (!ok && !back && dx == 0 && dy == 0) return false;

        if (showPhone) { if (back || ok) closePhone(); return true; }
        if (showSettings) { settingsKey(ok, back, dx, dy); invalidate(); return true; }
        if (showAll) { allKey(ok, back, dx, dy); invalidate(); return true; }
        if (showWatch) { watchKey(ok, back, dx, dy); invalidate(); return true; }
        if (back) { zone = Z_APPS; invalidate(); return true; }

        switch (zone) {
            case Z_NAV:
                if (dy != 0) navIdx = clamp(navIdx + dy, 0, NAV.length - 1);
                else if (dx > 0) zone = Z_APPS;
                else if (ok) navAction(navIdx);
                break;
            case Z_TOP: {
                List<String> T = topItems();
                if (dx != 0) { int n = topIdx + dx; if (n < 0) zone = recent.isEmpty() ? Z_HERO : Z_RECENT; else topIdx = clamp(n, 0, T.size() - 1); }
                else if (dy > 0) zone = recent.isEmpty() ? Z_HERO : Z_RECENT;
                else if (ok) topAction(T.get(clamp(topIdx, 0, T.size() - 1)));
                break;
            }
            case Z_RECENT:
                if (recent.isEmpty()) { zone = Z_HERO; break; }
                if (dx < 0) { if (recentIdx > 0) recentIdx--; else zone = Z_NAV; }
                else if (dx > 0) recentIdx = clamp(recentIdx + 1, 0, recent.size() - 1);
                else if (dy > 0) zone = Z_HERO;
                else if (dy < 0) zone = Z_TOP;
                else if (ok) launch(recent.get(recentIdx).pkg);
                break;
            case Z_HERO:
                if (dx < 0) { if (heroIdx > 0) heroIdx--; else zone = Z_NAV; }
                else if (dx > 0) { if (heroIdx < 1) heroIdx++; else { zone = Z_TOP; topIdx = 0; } }
                else if (dy > 0) zone = Z_APPS;
                else if (dy < 0) zone = recent.isEmpty() ? Z_TOP : Z_RECENT;
                else if (ok) { if (heroIdx == 0) openSelected(); else openAll(); }
                break;
            case Z_APPS:
                if (dx < 0) { if (appIdx > 0) appIdx--; else zone = Z_NAV; }
                else if (dx > 0) appIdx = clamp(appIdx + 1, 0, Math.max(0, favs.size() - 1));
                else if (dy < 0) { zone = Z_HERO; heroIdx = 0; }
                else if (dy > 0) zone = Z_QUICK;
                else if (ok) openSelected();
                break;
            case Z_QUICK:
                if (dx < 0) { if (quickIdx > 0) quickIdx--; else zone = Z_NAV; }
                else if (dx > 0) quickIdx = clamp(quickIdx + 1, 0, QUICK.length - 1);
                else if (dy < 0) zone = Z_APPS;
                else if (ok) quickAction(quickIdx);
                break;
        }
        invalidate();
        return true;
    }

    void openSelected() { if (appIdx < favs.size()) launch(favs.get(appIdx).pkg); }
    void openAll() { showAll = true; allIdx = 0; allScroll = 0; }
    void closeAll() { showAll = false; for (App a : all) if (!isFav(a.pkg)) a.icon = null; }

    void navAction(int i) {
        if (i == 0) { zone = Z_APPS; }
        else if (i == 1) openAll();
        else if (i == 2) launch("ar.tvplayer.tv");
        else if (i == 3) launch("org.videolan.vlc");
        else { showSettings = true; setIdx = 0; }
    }

    List<String> topItems() {
        List<String> t = new ArrayList<String>();
        if (!fireTv) { t.add("Search"); t.add("Voice"); } // Fire TV: search and Alexa live on the remote
        if (!inputs.isEmpty()) t.add("Inputs");
        t.add("Net"); t.add("Wi-Fi");
        return t;
    }
    void topAction(String n) {
        if (n.equals("Search")) start(new Intent("android.search.action.GLOBAL_SEARCH"));
        else if (n.equals("Voice")) start(new Intent(Intent.ACTION_ASSIST));
        else if (n.equals("Inputs")) showInputs();
        else if (n.equals("Net")) {
            PackageManager pm = getContext().getPackageManager();
            if (launchIntent(pm, "ch.protonvpn.android") != null) launch("ch.protonvpn.android");
            else { flash("Checking your connection…"); checkNet(true); }
        }
        else start(new Intent(Settings.ACTION_WIFI_SETTINGS));
    }

    void quickAction(int i) {
        if (i == 0) start(new Intent(Settings.ACTION_BLUETOOTH_SETTINGS));
        else if (i == 1) start(new Intent(Settings.ACTION_SOUND_SETTINGS));
        else if (i == 2) start(new Intent(Settings.ACTION_DISPLAY_SETTINGS));
        else if (i == 3) cycleSleep();
        else if (i == 4) nextBackground();
        else start(new Intent(Settings.ACTION_SETTINGS));
    }

    void cycleSleep() {
        if (HomeKeyService.instance == null) { flash("Turn on the WLH Glass Home button to use the sleep timer"); return; }
        int left = HomeKeyService.sleepMinutesLeft(), next = 0;
        for (int st : SLEEP_STEPS) if (st > left) { next = st; break; }
        if (left >= SLEEP_STEPS[SLEEP_STEPS.length - 1]) next = 0;
        HomeKeyService.setSleepMinutes(next);
        flash(next == 0 ? "Sleep timer off" : "TV will sleep in " + next + " minutes");
    }

    String quickLabel(int i) {
        if (i == 3) { int m = HomeKeyService.sleepMinutesLeft(); return m == 0 ? "Sleep timer" : "Sleep " + m + "m"; }
        return QUICK[i];
    }

    // ---------- continue watching (Android TV "watch next" list) ----------
    void openWatching() {
        showWatch = true; watchIdx = 0; watchScroll = 0;
        android.app.Activity a = getContext() instanceof android.app.Activity ? (android.app.Activity) getContext() : null;
        if (a != null && a.checkSelfPermission(EPG) != PackageManager.PERMISSION_GRANTED) {
            try { a.requestPermissions(new String[] { EPG }, 42); watchMsg = "Allow access on the next screen"; return; } catch (Throwable t) { }
        }
        loadWatching();
    }

    void loadWatching() {
        if (watchLoading) return;
        watchLoading = true; watchMsg = "Loading…"; invalidate();
        new Thread(new Runnable() { public void run() {
            final List<WItem> out = new ArrayList<WItem>(); String m = "";
            android.database.Cursor cur = null;
            try {
                cur = getContext().getContentResolver().query(android.net.Uri.parse("content://android.media.tv/watch_next_program"),
                        null, null, null, null);
                if (cur == null) m = "Android didn't return a watch list.";
                else {
                    int ti = cur.getColumnIndex("title"), ei = cur.getColumnIndex("episode_title"), se = cur.getColumnIndex("season_display_number"),
                        ep = cur.getColumnIndex("episode_display_number"), iu = cur.getColumnIndex("intent_uri"), pk = cur.getColumnIndex("package_name"),
                        po = cur.getColumnIndex("poster_art_uri"), le = cur.getColumnIndex("last_engagement_time_utc_millis");
                    while (cur.moveToNext() && out.size() < 24) {
                        WItem w = new WItem();
                        w.title = ti >= 0 ? cur.getString(ti) : null;
                        if (w.title == null) continue;
                        String sub = "";
                        if (se >= 0 && ep >= 0 && cur.getString(se) != null && cur.getString(ep) != null) sub = "S" + cur.getString(se) + " E" + cur.getString(ep) + "  ";
                        if (ei >= 0 && cur.getString(ei) != null) sub += cur.getString(ei);
                        w.sub = sub.trim();
                        w.intentUri = iu >= 0 ? cur.getString(iu) : null;
                        w.pkg = pk >= 0 ? cur.getString(pk) : null;
                        w.poster = po >= 0 ? cur.getString(po) : null;
                        out.add(w);
                    }
                    if (out.isEmpty()) m = "Nothing to continue yet — or this TV only shares the list with Google TV.";
                }
            } catch (SecurityException se) {
                m = "Android TV doesn't let other home screens read your watch list on this box.";
            } catch (Throwable t) {
                m = "Couldn't read the watch list (" + t.getClass().getSimpleName() + ").";
            } finally { if (cur != null) cur.close(); }
            for (WItem w : out) w.img = poster(w.poster);
            android.util.Log.i("WLHGlass", "watch next: " + out.size() + " items; " + m);
            final String fm = m;
            ui.post(new Runnable() { public void run() { watch.clear(); watch.addAll(out); watchMsg = fm; watchLoading = false; invalidate(); } });
        } }).start();
    }

    Bitmap poster(String uri) {
        if (uri == null) return null;
        try {
            java.io.InputStream in;
            if (uri.startsWith("http")) in = Updater.open(uri).getInputStream();
            else in = getContext().getContentResolver().openInputStream(android.net.Uri.parse(uri));
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inPreferredConfig = Bitmap.Config.RGB_565; o.inSampleSize = 2;
            Bitmap b = android.graphics.BitmapFactory.decodeStream(in, null, o);
            in.close();
            if (b != null && b.getWidth() > 400) { Bitmap sc = Bitmap.createScaledBitmap(b, 400, Math.max(1, 400 * b.getHeight() / b.getWidth()), true); if (sc != b) b.recycle(); b = sc; }
            return b;
        } catch (Throwable t) { return null; }
    }

    static final int W_COLS = 4;
    void watchKey(boolean ok, boolean back, int dx, int dy) {
        if (back) { closeWatch(); return; }
        int n = watch.size(); if (n == 0) return;
        if (dx != 0) watchIdx = clamp(watchIdx + dx, 0, n - 1);
        if (dy != 0) { int t = watchIdx + dy * W_COLS; if (t >= 0 && t < n) watchIdx = t; }
        if (ok) {
            WItem w = watch.get(watchIdx);
            try { start(Intent.parseUri(w.intentUri, Intent.URI_INTENT_SCHEME)); } catch (Throwable t) { if (w.pkg != null) launch(w.pkg); }
            closeWatch();
        }
        int row = watchIdx / W_COLS;
        if (row < watchScroll) watchScroll = row;
        if (row > watchScroll + 1) watchScroll = row - 1;
    }

    void closeWatch() { showWatch = false; for (WItem w : watch) if (w.img != null) { w.img.recycle(); w.img = null; } watch.clear(); }

    static final int ALL_COLS = 7;
    void allKey(boolean ok, boolean back, int dx, int dy) {
        if (back) { if (allMode == 1) finishPicker(); else if (allMode == 2) finishHider(); else closeAll(); return; }
        List<App> L = cur();
        int n = L.size(); if (n == 0) return;
        if (dx != 0) allIdx = clamp(allIdx + dx, 0, n - 1);
        if (dy != 0) { int t = allIdx + dy * ALL_COLS; if (t >= 0 && t < n) allIdx = t; else if (dy > 0) allIdx = n - 1; }
        if (ok) {
            String pkg = L.get(allIdx).pkg;
            if (allMode == 1) togglePick(pkg);
            else if (allMode == 2) { if (!hidden.remove(pkg)) hidden.add(pkg); }
            else { launch(pkg); closeAll(); }
        }
        int row = allIdx / ALL_COLS;
        if (row < allScroll) allScroll = row;
        if (row > allScroll + 2) allScroll = row - 2;
    }
    static final String[] SET_LABELS = { "Home button", "Favorites", "Hide apps", "Background", "Add photos from phone", "Clock format", "Clock time zone", "World clocks",
            "Accent color", "Low power mode", "Glass blur", "Temperature", "Weather location", "Open when TV starts", "App update", "Android TV settings" };
    static final int SET_ROWS = 12;
    void settingsKey(boolean ok, boolean back, int dx, int dy) {
        if (back) { showSettings = false; return; }
        if (dy != 0) {
            setIdx = clamp(setIdx + dy, 0, SET_LABELS.length - 1);
            if (setIdx < setScroll) setScroll = setIdx;
            if (setIdx >= setScroll + SET_ROWS) setScroll = setIdx - SET_ROWS + 1;
            return;
        }
        if (!ok && dx == 0) return;
        int step = dx == 0 ? 1 : dx;
        SharedPreferences.Editor e = prefs.edit();
        switch (SET_LABELS[setIdx]) {
            case "Home button": if (ok) { if (homeServiceOn() && HomeKeyService.switchToGoogleTv(getContext())) showSettings = false; else start(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } break;
            case "Favorites": if (ok) { e.apply(); openPicker(); return; } break;
            case "Hide apps": if (ok) { e.apply(); openHider(); return; } break;
            case "Background": { scanBackgrounds(); int n = bgCount() + 1, cur = bgMode == ROTATE ? n - 1 : bgMode; cur = (cur + step + n) % n;
                      bgMode = cur == n - 1 ? ROTATE : cur; if (bgMode != ROTATE) bgIdx = bgMode; e.putInt("bgMode", bgMode).putInt("bgIdx", bgIdx); dropBitmaps(); break; }
            case "Add photos from phone": if (ok) { e.apply(); showSettings = false; openPhone(); return; } break;
            case "Clock format": clockMode = (clockMode + step + 3) % 3; e.putInt("clock", clockMode); break;
            case "Clock time zone": tzFromPlace = !tzFromPlace; e.putBoolean("tzFromPlace", tzFromPlace); break;
            case "World clocks": if (ok) { e.apply(); manageClocks(); return; } break;
            case "Accent color": accentIdx = (accentIdx + step + ACCENT_COLOR.length) % ACCENT_COLOR.length; ACCENT = ACCENT_COLOR[accentIdx]; e.putInt("accent", accentIdx); break;
            case "Low power mode": lowPower = !lowPower; e.putBoolean("lowPower", lowPower); dropBitmaps(); break;
            case "Glass blur": blurLevel = (blurLevel + step + 3) % 3; e.putInt("blur", blurLevel); dropBitmaps(); break;
            case "Temperature": celsius = !celsius; e.putBoolean("celsius", celsius); clearWeather(); fetchWeather(); break;
            case "Weather location": if (ok) { e.apply(); askPlace(0); } break;
            case "Open when TV starts": bootStart = !bootStart; e.putBoolean("bootStart", bootStart); break;
            case "App update": if (ok) { if (updater.updateReady()) updater.install(); else updater.check(); } break;
            case "Android TV settings": if (ok) start(new Intent(Settings.ACTION_SETTINGS)); break;
        }
        e.apply();
    }

    String setValue(int i) {
        switch (SET_LABELS[i]) {
            case "Home button":
                if (homeServiceOn()) return "WLH Glass — OK switches to " + homeName();
                return fireTv ? "Fire TV home — OK to try the switch (newer Fire TVs may block it)" : "Google TV — OK, then turn on WLH Glass Home button";
            case "Favorites": return (favCustom ? favPkgs.size() + " picked" : "Default") + " — OK to pick";
            case "Hide apps": return (hidden.isEmpty() ? "None hidden" : hidden.size() + " hidden") + " — OK to choose";
            case "Background": return bgMode != ROTATE ? bgName(bgMode) : "Rotate every 15 min (now " + bgName(bgIdx) + ")";
            case "Add photos from phone": return (bgCount() - 1) + " photo" + (bgCount() == 2 ? "" : "s") + " — OK to show QR code";
            case "Clock format": return clockMode == 0 ? "24-hour" : clockMode == 1 ? "12-hour" : "Follow TV (" + (DateFormat.is24HourFormat(getContext()) ? "24-hour" : "12-hour") + ")";
            case "Clock time zone": return tzFromPlace ? "Weather location (" + (placeTZ.length() > 0 ? placeTZ.replace('_', ' ') : "loading") + ")" : "TV time";
            case "World clocks": {
                if (clocks.isEmpty()) return "None — OK to add";
                StringBuilder b = new StringBuilder();
                for (String[] w : clocks) { if (b.length() > 0) b.append(", "); b.append(w[0]); }
                return b + " — OK to change";
            }
            case "Accent color": return ACCENT_NAME[accentIdx];
            case "Low power mode": return lowPower ? "On" : "Off";
            case "Glass blur": return blurLevel == 0 ? "Light" : blurLevel == 1 ? "Medium" : "Strong";
            case "Temperature": return celsius ? "°C" : "°F";
            case "Weather location": return placeShort() + " — OK to change";
            case "Open when TV starts": return bootStart ? "On" : "Off";
            case "App update": {
                if (updater.state.length() > 0) return updater.state;
                if (updater.updateReady()) return "Version " + updater.latestName() + " ready — OK to install";
                return "Up to date (" + updater.myName() + ") — OK to check";
            }
            default: return "›";
        }
    }

    boolean homeServiceOn() {
        try {
            String s = Settings.Secure.getString(getContext().getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            return s != null && s.contains(getContext().getPackageName() + "/");
        } catch (Throwable t) { return false; }
    }

    void clearWeather() { wTemp = null; wFeels = ""; wHiLo = ""; wCode = -1; wCond = "Loading weather…"; wStale = false; wAt = 0; fDay = new String[3]; }

    static int clamp(int v, int lo, int hi) { return v < lo ? lo : v > hi ? hi : v; }

    void dropBitmaps() { if (wall != null) wall.recycle(); if (blur != null) blur.recycle(); wall = blur = null; }

    // ---------- wallpaper ----------
    void ensureBitmaps() {
        if (getWidth() == 0) return;
        if (wall == null) {
            wall = Bitmap.createBitmap(960, 540, Bitmap.Config.RGB_565);
            Canvas c = new Canvas(wall);
            c.scale(0.5f, 0.5f);
            if (bgIdx >= 1 && bgIdx <= bgFiles.length) paintPhoto(c, bgFiles[bgIdx - 1]); else paintWallpaper(c);
        }
        if (blur == null && !lowPower) {
            Bitmap small = Bitmap.createScaledBitmap(wall, 240, 135, true);
            int rad = blurLevel == 0 ? 2 : blurLevel == 1 ? 4 : 6;
            boxBlur(small, rad); boxBlur(small, rad); boxBlur(small, rad);
            blur = small;
        }
    }

    void paintPhoto(Canvas c, java.io.File f) {
        try {
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            int ss = 1; while (o.outWidth / (ss * 2) >= 960 && o.outHeight / (ss * 2) >= 540) ss *= 2;
            o = new android.graphics.BitmapFactory.Options();
            o.inSampleSize = ss; o.inPreferredConfig = Bitmap.Config.RGB_565;
            Bitmap ph = android.graphics.BitmapFactory.decodeFile(f.getAbsolutePath(), o);
            if (ph == null) throw new Exception("bad image");
            // center-crop to 16:9
            float sr = ph.getWidth() / (float) ph.getHeight(), dr = 16f / 9f;
            Rect src = sr > dr ? new Rect((int) ((ph.getWidth() - ph.getHeight() * dr) / 2), 0, (int) ((ph.getWidth() + ph.getHeight() * dr) / 2), ph.getHeight())
                               : new Rect(0, (int) ((ph.getHeight() - ph.getWidth() / dr) / 2), ph.getWidth(), (int) ((ph.getHeight() + ph.getWidth() / dr) / 2));
            c.drawBitmap(ph, src, new RectF(0, 0, 1920, 1080), new Paint(Paint.FILTER_BITMAP_FLAG));
            ph.recycle();
        } catch (Throwable t) { paintWallpaper(c); return; }
        Paint w = new Paint(Paint.ANTI_ALIAS_FLAG);
        w.setColor(0x26000000); c.drawRect(0, 0, 1920, 1080, w);
        overlays(c, w);
    }

    void overlays(Canvas c, Paint w) {
        w.setColor(0xFF000000);
        w.setShader(new LinearGradient(0, 0, 1920, 0, new int[] { 0x8C060816, 0x1A060816, 0x00060816 }, new float[] { 0f, 0.4f, 0.7f }, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 1920, 1080, w);
        w.setShader(new LinearGradient(0, 1080, 0, 594, new int[] { 0xB3060816, 0x00060816 }, null, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 1920, 1080, w);
        w.setShader(null);
    }

    void paintWallpaper(Canvas c) {
        Paint w = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.DITHER_FLAG);
        w.setShader(new LinearGradient(0, 0, 0, 800,
                new int[] { 0xFF0D1338, 0xFF3A2A6E, 0xFFB8506A, 0xFFF08A4B, 0xFFFFC07A },
                new float[] { 0f, 0.34f, 0.56f, 0.68f, 0.74f }, Shader.TileMode.CLAMP));
        c.drawRect(0, 0, 1920, 800, w);
        w.setShader(new RadialGradient(1255, 45, 560, new int[] { 0xFFF3D9E6, 0xFF9A7FB8, 0xFF2A2458 }, new float[] { 0f, 0.45f, 1f }, Shader.TileMode.CLAMP));
        w.setAlpha(217); c.drawCircle(1360, 150, 300, w);
        w.setShader(new RadialGradient(1624, 234, 70, new int[] { 0xFFF3D9E6, 0xFF9A7FB8, 0xFF2A2458 }, new float[] { 0f, 0.45f, 1f }, Shader.TileMode.CLAMP));
        w.setAlpha(178); c.drawCircle(1640, 250, 46, w);
        w.setAlpha(255);
        w.setShader(new RadialGradient(1180, 760, 260, new int[] { 0xF2FFF1C8, 0x00FF9A55 }, null, Shader.TileMode.CLAMP));
        c.drawCircle(1180, 760, 260, w);
        w.setShader(null);
        float[] m1 = { 0,640, 180,560, 300,600, 460,470, 600,580, 760,520, 900,610, 1040,430, 1120,500, 1260,380, 1400,540, 1520,500, 1700,600, 1920,520, 1920,800, 0,800 };
        w.setColor(0xEB2A1A3E); c.drawPath(poly(m1), w);
        float[] m2 = { 0,720, 220,650, 380,700, 560,630, 760,700, 980,660, 1160,720, 1380,640, 1600,710, 1920,650, 1920,800, 0,800 };
        w.setColor(0xFF150F2A); c.drawPath(poly(m2), w);
        w.setShader(new LinearGradient(0, 790, 0, 1080, new int[] { 0xFFE98A5A, 0xFF5A3566, 0xFF0A0C22 }, new float[] { 0f, 0.35f, 1f }, Shader.TileMode.CLAMP));
        c.drawRect(0, 790, 1920, 1080, w);
        w.setShader(null);
        float[] m3 = { 0,800, 220,860, 380,830, 560,880, 760,830, 980,870, 1160,820, 1380,880, 1600,830, 1920,870, 1920,800 };
        w.setColor(0x8C150F2A); c.drawPath(poly(m3), w);
        w.setColor(0x99FFD7A1); c.drawRoundRect(new RectF(1060, 820, 1300, 824), 2, 2, w);
        w.setColor(0x59FFD7A1); c.drawRoundRect(new RectF(1010, 850, 1350, 853), 2, 2, w);
        w.setColor(0x40FFD7A1); c.drawRoundRect(new RectF(1100, 880, 1260, 883), 2, 2, w);
        overlays(c, w);
    }

    Path poly(float[] pts) {
        Path pa = new Path();
        pa.moveTo(pts[0], pts[1]);
        for (int i = 2; i < pts.length; i += 2) pa.lineTo(pts[i], pts[i + 1]);
        pa.close();
        return pa;
    }

    static void boxBlur(Bitmap b, int rad) {
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h], tmp = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        pass(px, tmp, w, h, rad, true);
        pass(tmp, px, w, h, rad, false);
        b.setPixels(px, 0, w, 0, 0, w, h);
    }

    static void pass(int[] src, int[] dst, int w, int h, int rad, boolean horiz) {
        int len = horiz ? w : h, lines = horiz ? h : w, div = rad * 2 + 1;
        for (int l = 0; l < lines; l++) {
            int rs = 0, gs = 0, bs = 0;
            for (int i = -rad; i <= rad; i++) {
                int c = src[idx(l, clamp(i, 0, len - 1), w, horiz)];
                rs += (c >> 16) & 255; gs += (c >> 8) & 255; bs += c & 255;
            }
            for (int i = 0; i < len; i++) {
                dst[idx(l, i, w, horiz)] = 0xFF000000 | ((rs / div) << 16) | ((gs / div) << 8) | (bs / div);
                int out = src[idx(l, clamp(i - rad, 0, len - 1), w, horiz)];
                int in = src[idx(l, clamp(i + rad + 1, 0, len - 1), w, horiz)];
                rs += ((in >> 16) & 255) - ((out >> 16) & 255);
                gs += ((in >> 8) & 255) - ((out >> 8) & 255);
                bs += (in & 255) - (out & 255);
            }
        }
    }

    static int idx(int line, int i, int w, boolean horiz) { return horiz ? line * w + i : i * w + line; }

    // ---------- draw helpers (design coordinates, 1920x1080) ----------
    void glass(Canvas c, float x, float y, float w, float h, float rad, boolean dark) {
        r.set(x, y, x + w, y + h);
        if (!lowPower && blur != null) {
            c.save();
            path.reset(); path.addRoundRect(r, rad, rad, Path.Direction.CW);
            c.clipPath(path);
            c.drawBitmap(blur, null, screen, bp);
            c.restore();
            p.setColor(dark ? 0x6B0C0E1E : 0x24FFFFFF);
        } else {
            p.setColor(dark ? 0xC70C0E1E : 0x66262A44);
        }
        c.drawRoundRect(r, rad, rad, p);
        if (!lowPower) {
            p.setShader(new LinearGradient(x, y, x + w * 0.45f, y + h * 0.9f,
                    new int[] { 0x52FFFFFF, 0x0FFFFFFF, 0x00FFFFFF }, new float[] { 0f, 0.38f, 0.6f }, Shader.TileMode.CLAMP));
            c.drawRoundRect(r, rad, rad, p);
            p.setShader(null);
        }
        sp.setStrokeWidth(1.5f); sp.setColor(dark ? 0x2EFFFFFF : 0x42FFFFFF);
        c.drawRoundRect(r, rad, rad, sp);
        sp.setColor(0x73FFFFFF); sp.setStrokeWidth(1.5f);
        c.drawLine(x + rad, y + 1, x + w - rad, y + 1, sp);
    }

    void focusRing(Canvas c, float x, float y, float w, float h, float rad) {
        r.set(x - 4, y - 4, x + w + 4, y + h + 4);
        if (!lowPower) {
            sp.setColor((ACCENT & 0x00FFFFFF) | 0x44000000); sp.setStrokeWidth(16f);
            c.drawRoundRect(r, rad + 4, rad + 4, sp);
        }
        sp.setColor(ACCENT); sp.setStrokeWidth(4f);
        c.drawRoundRect(r, rad + 4, rad + 4, sp);
    }

    void text(Canvas c, String s, float x, float y, float size, Typeface tf, int color, Paint.Align al) {
        tp.setTypeface(tf); tp.setTextSize(size); tp.setColor(color); tp.setTextAlign(al);
        tp.setShadowLayer(lowPower ? 0 : 6, 0, 2, 0x55000000);
        c.drawText(s, x, y, tp);
    }

    float measure(String s, float size, Typeface tf) { tp.setTypeface(tf); tp.setTextSize(size); return tp.measureText(s); }

    void icon(Canvas c, Drawable d, float x, float y, float s) {
        if (d == null) return;
        d.setBounds(Math.round(x), Math.round(y), Math.round(x + s), Math.round(y + s));
        d.draw(c);
    }

    void glyph(Canvas c, String name, float cx, float cy, float s, int color) {
        sp.setColor(color); sp.setStrokeWidth(s * 0.075f); sp.setStrokeCap(Paint.Cap.ROUND); sp.setStrokeJoin(Paint.Join.ROUND);
        float u = s / 24f, ox = cx - s / 2, oy = cy - s / 2;
        Path g = new Path();
        if (name.equals("Home")) { g.moveTo(ox+3*u, oy+11*u); g.lineTo(ox+12*u, oy+4*u); g.lineTo(ox+21*u, oy+11*u); g.moveTo(ox+5*u, oy+10*u); g.lineTo(ox+5*u, oy+20*u); g.lineTo(ox+19*u, oy+20*u); g.lineTo(ox+19*u, oy+10*u); }
        else if (name.equals("Apps")) { for (int i = 0; i < 4; i++) { float a = ox + (i % 2 == 0 ? 4 : 14) * u, b = oy + (i < 2 ? 4 : 14) * u; g.addRoundRect(new RectF(a, b, a + 6*u, b + 6*u), 1.5f*u, 1.5f*u, Path.Direction.CW); } }
        else if (name.equals("Live TV")) { g.addRoundRect(new RectF(ox+3*u, oy+6*u, ox+21*u, oy+18*u), 2*u, 2*u, Path.Direction.CW); g.moveTo(ox+8*u, oy+3*u); g.lineTo(ox+12*u, oy+6*u); g.lineTo(ox+16*u, oy+3*u); }
        else if (name.equals("Media") || name.equals("Background")) { g.addRoundRect(new RectF(ox+3*u, oy+5*u, ox+21*u, oy+19*u), 2*u, 2*u, Path.Direction.CW); g.addCircle(ox+9*u, oy+10*u, 2*u, Path.Direction.CW); g.moveTo(ox+21*u, oy+16*u); g.lineTo(ox+16*u, oy+11*u); g.lineTo(ox+8*u, oy+19*u); }
        else if (name.equals("Settings") || name.equals("TV settings")) { g.addCircle(cx, cy, 3*u, Path.Direction.CW); g.addCircle(cx, cy, 8*u, Path.Direction.CW); for (int i = 0; i < 8; i++) { double a = i * Math.PI / 4; g.moveTo(cx + (float)Math.cos(a)*8*u, cy + (float)Math.sin(a)*8*u); g.lineTo(cx + (float)Math.cos(a)*10.5f*u, cy + (float)Math.sin(a)*10.5f*u); } }
        else if (name.equals("Bluetooth")) { g.moveTo(ox+7*u, oy+7*u); g.lineTo(ox+17*u, oy+17*u); g.lineTo(ox+12*u, oy+22*u); g.lineTo(ox+12*u, oy+2*u); g.lineTo(ox+17*u, oy+7*u); g.lineTo(ox+7*u, oy+17*u); }
        else if (name.equals("Sound")) { g.moveTo(ox+4*u, oy+10*u); g.lineTo(ox+4*u, oy+14*u); g.lineTo(ox+8*u, oy+14*u); g.lineTo(ox+13*u, oy+18*u); g.lineTo(ox+13*u, oy+6*u); g.lineTo(ox+8*u, oy+10*u); g.close(); g.addArc(new RectF(ox+12*u, oy+9*u, ox+18*u, oy+15*u), -60, 120); }
        else if (name.equals("Picture")) { g.addCircle(cx, cy, 8*u, Path.Direction.CW); g.moveTo(cx, cy - 8*u); g.lineTo(cx, cy + 8*u); }
        else if (name.equals("Sleep")) { g.addArc(new RectF(ox+4*u, oy+4*u, ox+20*u, oy+20*u), 60, 250); g.addArc(new RectF(ox+9*u, oy+2*u, ox+21*u, oy+14*u), 120, 150); }
        else if (name.equals("Watching")) { g.addCircle(cx, cy, 9*u, Path.Direction.CW); g.moveTo(cx-2.5f*u, cy-4*u); g.lineTo(cx+4.5f*u, cy); g.lineTo(cx-2.5f*u, cy+4*u); g.close(); }
        else if (name.equals("Inputs")) { g.addRoundRect(new RectF(ox+3*u, oy+5*u, ox+21*u, oy+17*u), 2*u, 2*u, Path.Direction.CW); g.moveTo(ox+8*u, oy+21*u); g.lineTo(ox+16*u, oy+21*u); g.moveTo(ox+8*u, oy+11*u); g.lineTo(ox+15*u, oy+11*u); g.moveTo(ox+12*u, oy+8*u); g.lineTo(ox+15*u, oy+11*u); g.lineTo(ox+12*u, oy+14*u); }
        else if (name.equals("Search")) { g.addCircle(ox+11*u, oy+11*u, 7*u, Path.Direction.CW); g.moveTo(ox+16*u, oy+16*u); g.lineTo(ox+20*u, oy+20*u); }
        else if (name.equals("Voice")) { g.addRoundRect(new RectF(ox+9*u, oy+3*u, ox+15*u, oy+14*u), 3*u, 3*u, Path.Direction.CW); g.addArc(new RectF(ox+5*u, oy+4*u, ox+19*u, oy+18*u), 0, 180); g.moveTo(cx, oy+18*u); g.lineTo(cx, oy+21*u); }
        else if (name.equals("Wi-Fi")) { g.addArc(new RectF(ox+2*u, oy+4*u, ox+22*u, oy+24*u), 225, 90); g.addArc(new RectF(ox+5.5f*u, oy+8*u, ox+18.5f*u, oy+21*u), 225, 90); g.addArc(new RectF(ox+9*u, oy+12*u, ox+15*u, oy+18*u), 225, 90); g.addCircle(cx, oy+19.5f*u, 0.8f*u, Path.Direction.CW); }
        c.drawPath(g, sp);
    }

    // ---------- draw ----------
    @Override protected void onDraw(Canvas c) {
        ensureBitmaps();
        if (wall != null) c.drawBitmap(wall, null, full, bp); else c.drawColor(0xFF0B0D1A);
        if (dreamMode) { drawDream(c); return; }
        c.save();
        c.scale(k, k);
        boolean animating = false;

        // clock
        Date now = new Date();
        boolean h24 = use24();
        text(c, fmt(h24 ? "HH:mm" : "h:mm").format(now), 72, 140, 88, light, Color.WHITE, Paint.Align.LEFT);
        text(c, (fmt("EEEE, MMMM d").format(now) + (otherZone() ? "  ·  " + placeShort() + " time" : "")), 74, 184, 26, reg, 0xD1FFFFFF, Paint.Align.LEFT);

        // side menu
        glass(c, 64, 250, 320, 18 * 2 + NAV.length * 72 + (NAV.length - 1) * 8, 36, true);
        for (int i = 0; i < NAV.length; i++) {
            float y = 268 + i * 80;
            boolean cur = i == navIdx;
            if (cur && zone == Z_NAV) { p.setColor(0x38FFFFFF); r.set(82, y, 366, y + 72); c.drawRoundRect(r, 22, 22, p); focusRing(c, 82, y, 284, 72, 22); }
            else if (cur) { p.setColor(0x1FFFFFFF); r.set(82, y, 366, y + 72); c.drawRoundRect(r, 22, 22, p); }
            glyph(c, NAV[i], 128, y + 36, 30, cur ? Color.WHITE : 0xDBFFFFFF);
            text(c, NAV[i], 166, y + 45, 26, med, cur ? Color.WHITE : 0xDBFFFFFF, Paint.Align.LEFT);
        }

        // top right: search, voice, wi-fi
        List<String> T = topItems();
        float[] tw = new float[T.size()];
        float total = 0;
        for (int i = 0; i < T.size(); i++) {
            String n = T.get(i);
            if (n.equals("Search") || n.equals("Voice")) tw[i] = 68;
            else if (n.equals("Net")) tw[i] = 26 * 2 + 14 + 12 + measure(netText(), 22, med);
            else tw[i] = 26 * 2 + 26 + 12 + measure(n, 22, med);
            total += tw[i] + (i > 0 ? 20 : 0);
        }
        float tx = 1848 - total;
        for (int i = 0; i < T.size(); i++) {
            String n = T.get(i);
            glass(c, tx, 64, tw[i], 68, 34, false);
            if (zone == Z_TOP && topIdx == i) focusRing(c, tx, 64, tw[i], 68, 34);
            if (tw[i] == 68) glyph(c, n, tx + 34, 98, 28, Color.WHITE);
            else if (n.equals("Net")) {
                p.setColor(vpnOn ? 0xFF8CF0B4 : 0x99FFFFFF); c.drawCircle(tx + 26 + 7, 98, 7, p);
                text(c, netText(), tx + 26 + 14 + 12, 106, 22, med, Color.WHITE, Paint.Align.LEFT);
            } else { glyph(c, n, tx + 26 + 13, 98, 26, Color.WHITE); text(c, n, tx + 26 + 26 + 12, 106, 22, med, Color.WHITE, Paint.Align.LEFT); }
            tx += tw[i] + 20;
        }

        // world clocks
        for (int i = 0; i < clocks.size(); i++) {
            float x = 440 + i * 190;
            SimpleDateFormat wf = new SimpleDateFormat(h24 ? "HH:mm" : "h:mm a", Locale.getDefault());
            wf.setTimeZone(java.util.TimeZone.getTimeZone(clocks.get(i)[1]));
            text(c, clocks.get(i)[0], x, 92, 18, med, 0xCCFFFFFF, Paint.Align.LEFT);
            text(c, wf.format(now), x, 134, 36, light, Color.WHITE, Paint.Align.LEFT);
        }

        // hero
        glass(c, 440, 190, 1000, 400, 44, false);
        if (!recent.isEmpty()) {
            text(c, "Recently opened", 496, 236, 18, med, 0xCCFFFFFF, Paint.Align.LEFT);
            for (int i = 0; i < recent.size(); i++) {
                float rx = 496 + i * 96, ry = 250;
                glass(c, rx, ry, 76, 76, 22, true);
                if (zone == Z_RECENT && recentIdx == i) focusRing(c, rx, ry, 76, 76, 22);
                icon(c, iconFor(recent.get(i)), rx + 12, ry + 12, 52);
            }
            if (zone == Z_RECENT && recentIdx < recent.size()) text(c, recent.get(recentIdx).label, 496, 360, 20, med, Color.WHITE, Paint.Align.LEFT);
        }
        App sel = appIdx < favs.size() ? favs.get(appIdx) : null;
        if (sel != null) {
            glass(c, 1216, 246, 168, 168, 42, true);
            icon(c, sel.icon, 1240, 270, 120);
            text(c, "SELECTED", 496, 400, 20, med, 0xCCFFFFFF, Paint.Align.LEFT);
            text(c, sel.label, 494, 470, 72, bold, Color.WHITE, Paint.Align.LEFT);
            text(c, blurb(sel.pkg), 496, 516, 28, reg, 0xDBFFFFFF, Paint.Align.LEFT);
        } else {
            text(c, "Welcome", 494, 470, 72, bold, Color.WHITE, Paint.Align.LEFT);
        }
        float bx = 496, by = 538;
        float openW = 34 * 2 + 24 + 14 + measure("Open", 24, bold);
        p.setColor(Color.WHITE); r.set(bx, by, bx + openW, by + 68); c.drawRoundRect(r, 34, 34, p);
        if (zone == Z_HERO && heroIdx == 0) focusRing(c, bx, by, openW, 68, 34);
        path.reset(); path.moveTo(bx + 36, by + 22); path.lineTo(bx + 56, by + 34); path.lineTo(bx + 36, by + 46); path.close();
        p.setColor(0xFF12142A); c.drawPath(path, p);
        text(c, "Open", bx + 34 + 24 + 14, by + 43, 24, bold, 0xFF12142A, Paint.Align.LEFT);
        float ax = bx + openW + 18, allW = 34 * 2 + measure("All apps", 24, bold);
        glass(c, ax, by, allW, 68, 34, false);
        if (zone == Z_HERO && heroIdx == 1) focusRing(c, ax, by, allW, 68, 34);
        text(c, "All apps", ax + 34, by + 43, 24, bold, Color.WHITE, Paint.Align.LEFT);

        // weather
        glass(c, 1480, 190, 368, 400, 40, false);
        text(c, placeShort(), 1514, 238, 22, med, 0xE0FFFFFF, Paint.Align.LEFT);
        if (wAt > 0) {
            String when = fmt(h24 ? "HH:mm" : "h:mm a").format(new Date(wAt));
            text(c, (wStale ? "Outdated · " : "Updated ") + when, 1814, 238, 16, med, wStale ? 0xFFFFC27A : 0xB3FFFFFF, Paint.Align.RIGHT);
        }
        weatherGlyph(c, 1546, 300, wCode);
        text(c, wTemp == null ? "--°" : wTemp, 1600, 328, 76, light, wStale ? 0xCCFFFFFF : Color.WHITE, Paint.Align.LEFT);
        text(c, wFeels.length() > 0 ? wCond + "  ·  " + wFeels : wCond, 1514, 372, 22, med, 0xE6FFFFFF, Paint.Align.LEFT);
        if (wAlert != null) {
            String al = wAlert; while (al.length() > 8 && measure("ALERT  " + al, 19, med) > 300) al = al.substring(0, al.length() - 2);
            if (!al.equals(wAlert)) al += "…";
            p.setColor(0xFFFF8A3D); r.set(1514, 388, 1576, 412); c.drawRoundRect(r, 8, 8, p);
            text(c, "ALERT", 1545, 406, 15, bold, 0xFF1A1020, Paint.Align.CENTER);
            text(c, al, 1586, 407, 19, med, 0xFFFFC27A, Paint.Align.LEFT);
        } else text(c, wHiLo, 1514, 404, 20, reg, 0xD1FFFFFF, Paint.Align.LEFT);
        sp.setColor(0x33FFFFFF); sp.setStrokeWidth(1.5f); c.drawLine(1514, 428, 1814, 428, sp);
        for (int i = 0; i < 3; i++) {
            if (fDay[i] == null) continue;
            float y = 466 + i * 42;
            text(c, fDay[i], 1514, y, 21, med, Color.WHITE, Paint.Align.LEFT);
            text(c, condition(fCode[i]), 1604, y, 19, reg, 0xC7FFFFFF, Paint.Align.LEFT);
            text(c, fHi[i] + "  " + fLo[i], 1814, y, 21, med, Color.WHITE, Paint.Align.RIGHT);
        }
        text(c, "Free memory " + freeMem(), 74, 222, 18, reg, 0xB3FFFFFF, Paint.Align.LEFT);
        if (updater.updateReady()) text(c, "Update " + updater.latestName() + " ready · Settings › App update", 74, 244, 18, med, ACCENT, Paint.Align.LEFT);

        // apps row
        text(c, "Your apps", 440, 668, 30, med, Color.WHITE, Paint.Align.LEFT);
        for (int i = 0; i < favs.size() && i < 8; i++) {
            boolean on = zone == Z_APPS && i == appIdx;
            float target = on ? 1f : 0f;
            if (lowPower) lift[i] = target; else { lift[i] += (target - lift[i]) * 0.35f; if (Math.abs(target - lift[i]) > 0.01f) animating = true; else lift[i] = target; }
            float s = 148 * (1 + 0.08f * lift[i]);
            float x = 440 + i * 178 + (148 - s) / 2, y = 700 - 10 * lift[i] + (148 - s) / 2;
            glass(c, x, y, s, s, 38 * s / 148, false);
            if (on) focusRing(c, x, y, s, s, 38 * s / 148);
            icon(c, favs.get(i).icon, x + s * 0.17f, y + s * 0.17f, s * 0.66f);
            String lab = favs.get(i).label;
            if (measure(lab, 21, med) > 170) lab = lab.substring(0, Math.min(lab.length(), 12)) + "…";
            text(c, lab, 440 + i * 178 + 74, 884, 21, med, on ? Color.WHITE : 0xEBFFFFFF, Paint.Align.CENTER);
        }
        if (favs.isEmpty()) text(c, "None of your favorite apps were found.", 440, 760, 24, reg, Color.WHITE, Paint.Align.LEFT);

        // quick settings
        float qx = 440;
        for (int i = 0; i < QUICK.length; i++) {
            String ql = quickLabel(i);
            float w = 26 * 2 + 28 + 14 + measure(ql, 22, med);
            glass(c, qx, 914, w, 84, 24, false);
            if (zone == Z_QUICK && quickIdx == i) focusRing(c, qx, 914, w, 84, 24);
            glyph(c, QUICK[i], qx + 26 + 14, 956, 28, Color.WHITE);
            text(c, ql, qx + 26 + 28 + 14, 964, 22, med, Color.WHITE, Paint.Align.LEFT);
            qx += w + 20;
        }

        if (showAll) drawAll(c);
        if (showWatch) drawWatch(c);
        if (showSettings) drawSettings(c);
        if (showPhone) drawPhone(c);
        if (msg != null && System.currentTimeMillis() < msgUntil) {
            float mw = measure(msg, 24, med) + 72;
            glass(c, 960 - mw / 2, 40, mw, 64, 32, true);
            text(c, msg, 960, 82, 24, med, Color.WHITE, Paint.Align.CENTER);
        }
        c.restore();
        if (animating) postInvalidateOnAnimation();
    }

    void drawDream(Canvas c) {
        c.save(); c.scale(k, k);
        long min = System.currentTimeMillis() / 60000;
        float ox = 120 + (min % 7) * 40, oy = 620 + (min % 5) * 30; // drift slowly
        Date now = new Date();
        boolean h24 = use24();
        glass(c, ox - 40, oy - 230, 760, 330, 48, true);
        text(c, fmt(h24 ? "HH:mm" : "h:mm").format(now), ox, oy - 40, 160, light, Color.WHITE, Paint.Align.LEFT);
        String line = fmt("EEEE, MMMM d").format(now);
        if (wTemp != null) line += "   ·   " + placeShort() + " " + wTemp + " " + wCond;
        text(c, line, ox + 6, oy + 30, 28, reg, 0xE6FFFFFF, Paint.Align.LEFT);
        c.restore();
    }

    String blurb(String pkg) {
        for (int i = 0; i < FAV_PKGS.length; i++) if (FAV_PKGS[i].equals(pkg)) return FAV_BLURB[i];
        return "Press OK to open.";
    }

    String freeMem() {
        try {
            ActivityManager am = (ActivityManager) getContext().getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return (mi.availMem / (1024 * 1024)) + " MB of " + (mi.totalMem / (1024 * 1024)) + " MB";
        } catch (Throwable t) { return "—"; }
    }

    void weatherGlyph(Canvas c, float cx, float cy, int code) {
        sp.setStrokeWidth(3.5f); sp.setStrokeCap(Paint.Cap.ROUND);
        boolean sun = code == 0 || code == 1 || code == 2 || code < 0;
        boolean cloud = code != 0;
        if (sun) {
            sp.setColor(0xFFFFD58A);
            float sx = cloud ? cx - 8 : cx, sy = cloud ? cy - 8 : cy;
            c.drawCircle(sx, sy, 11, sp);
            for (int i = 0; i < 8; i++) { double a = i * Math.PI / 4; c.drawLine(sx + (float) Math.cos(a) * 17, sy + (float) Math.sin(a) * 17, sx + (float) Math.cos(a) * 22, sy + (float) Math.sin(a) * 22, sp); }
        }
        if (cloud) {
            sp.setColor(Color.WHITE);
            path.reset();
            path.addCircle(cx + 2, cy + 8, 11, Path.Direction.CW);
            path.addCircle(cx + 16, cy + 12, 8, Path.Direction.CW);
            path.addRoundRect(new RectF(cx - 12, cy + 10, cx + 26, cy + 22), 6, 6, Path.Direction.CW);
            c.drawPath(path, sp);
        }
        if (code >= 51 && code < 95) { sp.setColor(0xFF9FD6FF); for (int i = 0; i < 3; i++) c.drawLine(cx - 6 + i * 12, cy + 28, cx - 10 + i * 12, cy + 36, sp); }
    }

    void drawAll(Canvas c) {
        p.setColor(0x99060816); c.drawRect(0, 0, 1920, 1080, p);
        glass(c, 120, 90, 1680, 900, 48, true);
        List<App> L = cur();
        if (allMode == 1) {
            text(c, "Pick favorites  " + favPkgs.size() + "/8", 180, 170, 44, bold, Color.WHITE, Paint.Align.LEFT);
            text(c, "OK adds or removes  ·  Order = the order you pick  ·  Back saves", 1740, 170, 22, reg, 0xCCFFFFFF, Paint.Align.RIGHT);
        } else if (allMode == 2) {
            text(c, "Hide apps  " + hidden.size() + " hidden", 180, 170, 44, bold, Color.WHITE, Paint.Align.LEFT);
            text(c, "OK hides or shows  ·  Apps stay installed  ·  Back saves", 1740, 170, 22, reg, 0xCCFFFFFF, Paint.Align.RIGHT);
        } else {
            text(c, "All apps", 180, 170, 44, bold, Color.WHITE, Paint.Align.LEFT);
            text(c, L.size() + " apps  ·  Back to close", 1740, 170, 22, reg, 0xCCFFFFFF, Paint.Align.RIGHT);
        }
        float tile = 160, gapX = 66, gapY = 92;
        for (int i = 0; i < L.size(); i++) {
            int row = i / ALL_COLS - allScroll, col = i % ALL_COLS;
            if (row < 0 || row > 2) continue;
            float x = 180 + col * (tile + gapX), y = 230 + row * (tile + gapY);
            boolean hid = allMode == 2 && hidden.contains(L.get(i).pkg);
            glass(c, x, y, tile, tile, 38, false);
            if (i == allIdx) focusRing(c, x, y, tile, tile, 38);
            icon(c, iconFor(L.get(i)), x + 28, y + 28, tile - 56);
            if (hid) {
                p.setColor(0xB3060816); r.set(x, y, x + tile, y + tile); c.drawRoundRect(r, 38, 38, p);
                text(c, "Hidden", x + tile / 2, y + tile / 2 + 9, 24, bold, 0xFFFFC27A, Paint.Align.CENTER);
            }
            if (allMode == 1) {
                int pos = favPkgs.indexOf(L.get(i).pkg);
                if (pos >= 0) {
                    p.setColor(0xFF2F7BFF); c.drawCircle(x + tile - 14, y + 14, 22, p);
                    text(c, String.valueOf(pos + 1), x + tile - 14, y + 23, 24, bold, Color.WHITE, Paint.Align.CENTER);
                }
            }
            String lab = L.get(i).label;
            if (measure(lab, 20, med) > tile + 50) lab = lab.substring(0, Math.min(lab.length(), 14)) + "…";
            text(c, lab, x + tile / 2, y + tile + 34, 20, med, hid ? 0x99FFFFFF : Color.WHITE, Paint.Align.CENTER);
        }
    }
    void drawWatch(Canvas c) {
        p.setColor(0x99060816); c.drawRect(0, 0, 1920, 1080, p);
        glass(c, 120, 90, 1680, 900, 48, true);
        text(c, "Continue watching", 180, 170, 44, bold, Color.WHITE, Paint.Align.LEFT);
        text(c, "Back to close", 1740, 170, 22, reg, 0xCCFFFFFF, Paint.Align.RIGHT);
        if (watch.isEmpty()) { text(c, watchLoading ? "Loading…" : watchMsg, 180, 300, 28, reg, 0xE6FFFFFF, Paint.Align.LEFT); return; }
        float cw = 360, ch = 203, gx = 40, gy = 120;
        for (int i = 0; i < watch.size(); i++) {
            int row = i / W_COLS - watchScroll, col = i % W_COLS;
            if (row < 0 || row > 1) continue;
            float x = 180 + col * (cw + gx), y = 230 + row * (ch + gy);
            glass(c, x, y, cw, ch, 28, false);
            WItem w = watch.get(i);
            if (w.img != null) {
                c.save(); path.reset(); r.set(x + 6, y + 6, x + cw - 6, y + ch - 6); path.addRoundRect(r, 22, 22, Path.Direction.CW); c.clipPath(path);
                c.drawBitmap(w.img, null, r, bp); c.restore();
            } else text(c, w.title, x + cw / 2, y + ch / 2 + 10, 26, bold, Color.WHITE, Paint.Align.CENTER);
            if (i == watchIdx) focusRing(c, x, y, cw, ch, 28);
            String t = w.title; while (t.length() > 4 && measure(t, 22, med) > cw) t = t.substring(0, t.length() - 2);
            text(c, t.equals(w.title) ? t : t + "…", x + 4, y + ch + 34, 22, med, Color.WHITE, Paint.Align.LEFT);
            if (w.sub != null && w.sub.length() > 0) text(c, w.sub.length() > 34 ? w.sub.substring(0, 33) + "…" : w.sub, x + 4, y + ch + 62, 18, reg, 0xC7FFFFFF, Paint.Align.LEFT);
        }
    }

    void drawSettings(Canvas c) {
        p.setColor(0x99060816); c.drawRect(0, 0, 1920, 1080, p);
        glass(c, 460, 120, 1000, 840, 48, true);
        text(c, "WLH Glass settings", 520, 200, 40, bold, Color.WHITE, Paint.Align.LEFT);
        text(c, "OK or ← → to change  ·  Back to close", 1400, 200, 20, reg, 0xCCFFFFFF, Paint.Align.RIGHT);
        for (int i = setScroll; i < SET_LABELS.length && i < setScroll + SET_ROWS; i++) {
            float y = 230 + (i - setScroll) * 60;
            if (i == setIdx) { p.setColor(0x2EFFFFFF); r.set(500, y, 1420, y + 52); c.drawRoundRect(r, 18, 18, p); focusRing(c, 500, y, 920, 52, 18); }
            text(c, SET_LABELS[i], 540, y + 34, 22, med, Color.WHITE, Paint.Align.LEFT);
            String v = setValue(i);
            while (v.length() > 10 && measure(v, 20, reg) > 600) v = v.substring(0, v.length() - 2);
            if (!v.equals(setValue(i))) v += "…";
            text(c, v, 1384, y + 34, 20, reg, 0xE0FFFFFF, Paint.Align.RIGHT);
        }
        if (setScroll > 0) text(c, "▲ more", 960, 226, 18, med, 0xB3FFFFFF, Paint.Align.CENTER);
        if (setScroll + SET_ROWS < SET_LABELS.length) text(c, "▼ more", 960, 948, 18, med, 0xB3FFFFFF, Paint.Align.CENTER);
    }
}
