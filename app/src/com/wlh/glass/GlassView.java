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
    static final int Z_NAV = 0, Z_TOP = 1, Z_HERO = 2, Z_APPS = 3, Z_QUICK = 4;
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
    boolean pickMode = false;
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
        if (bgMode == ROTATE && bgCount() > 1) { bgIdx = (bgIdx + 1) % bgCount(); prefs.edit().putInt("bgIdx", bgIdx).apply(); dropBitmaps(); invalidate(); }
        ui.postDelayed(this, 15 * 60 * 1000L);
    } };
    void nextBackground() {
        scanBackgrounds();
        if (bgCount() < 2) { flash("Add photos to Android/data/com.wlh.glass/files/backgrounds"); return; }
        bgIdx = (bgIdx + 1) % bgCount();
        if (bgMode != ROTATE) bgMode = bgIdx;
        flash("Background: " + bgName(bgIdx));
        prefs.edit().putInt("bgMode", bgMode).putInt("bgIdx", bgIdx).apply();
        dropBitmaps();
    }
    boolean dreamMode = false;
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
    final int ACCENT = 0xFF9FD6FF;

    final String[] NAV = { "Home", "Apps", "Live TV", "Media", "Settings" };
    final String[] QUICK = { "Bluetooth", "Sound", "Picture", "Sleep", "Background", "TV settings" };
    final String[] TOP = { "Search", "Voice", "Wi-Fi" };

    final Runnable tick = new Runnable() { public void run() { invalidate(); scheduleTick(); } };

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
        loadFavList();
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
        if (!dreamMode) updater.checkIfDue();
        scanBackgrounds();
        requestFocus();
        invalidate();
    }
    void onHidden() { ui.removeCallbacks(tick); ui.removeCallbacks(wxTick); ui.removeCallbacks(bgTick); }
    void release() { ui.removeCallbacksAndMessages(null); if (wall != null) wall.recycle(); if (blur != null) blur.recycle(); wall = blur = null; }
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
        showSettings = false; pickMode = true; showAll = true; allIdx = 0; allScroll = 0;
    }
    void togglePick(String pkg) {
        if (favPkgs.remove(pkg)) return;
        if (favPkgs.size() >= 8) { flash("You already have 8. Remove one first."); return; }
        favPkgs.add(pkg);
    }
    void finishPicker() {
        pickMode = false; showAll = false;
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
        all.clear();
        HashSet<String> seen = new HashSet<String>();
        String[] cats = { Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER };
        for (String cat : cats) {
            Intent q = new Intent(Intent.ACTION_MAIN).addCategory(cat);
            List<ResolveInfo> res = pm.queryIntentActivities(q, 0);
            for (ResolveInfo ri : res) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getContext().getPackageName()) || !seen.add(pkg)) continue;
                App a = app(pm, pkg, false);
                if (a != null) all.add(a);
            }
        }
        Collections.sort(all, new Comparator<App>() {
            public int compare(App x, App y) { return x.label.compareToIgnoreCase(y.label); }
        });
        // Fewer than 8 of the listed favorites installed (e.g. someone else's TV)? Fill the row from the app list.
        for (App a : all) {
            if (favCustom || favs.size() >= 8) break;
            boolean have = false; for (App f : favs) if (f.pkg.equals(a.pkg)) { have = true; break; }
            if (!have && !a.pkg.startsWith("com.android.") && !a.pkg.equals("com.google.android.tv.remote.service")) { App f = new App(); f.pkg = a.pkg; f.label = a.label; f.icon = loadIcon(pm, safeInfo(pm, a.pkg)); favs.add(f); }
        }
        if (appIdx >= favs.size()) appIdx = Math.max(0, favs.size() - 1);
    }

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
        if (i != null) start(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
    }

    void start(Intent i) {
        try { getContext().startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (ActivityNotFoundException e) {
            try { getContext().startActivity(new Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (Throwable t) { }
        } catch (Throwable t) { }
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
    String placeKey() { return placeName + "|" + placeLat + "|" + placeLon; }
    String placeShort() { String n = placeName == null ? "" : placeName; return n.length() > 18 ? n.substring(0, 17) + "…" : n; }

    static class Place { String label, name, cc, tz; double lat, lon; }

    /** Opens the TV keyboard; the viewer types a city or a country, then picks from the matches. */
    void askPlace() {
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
                .setTitle("Weather location").setView(box)
                .setPositiveButton("Search", null).setNegativeButton("Cancel", null).create();
        final Runnable go = new Runnable() { public void run() {
            String q = in.getText().toString().trim();
            if (q.length() < 2) return;
            d.dismiss(); searchPlace(q);
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

    void searchPlace(final String query) {
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
                        if (cap != null) { out.add(0, cap); continue; }
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
            ui.post(new Runnable() { public void run() { showPlaces(query, out); } });
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

    void showPlaces(String query, final List<Place> list) {
        if (list.isEmpty()) { flash("No place found for \"" + query + "\". Try another spelling."); return; }
        if (!(getContext() instanceof android.app.Activity) || ((android.app.Activity) getContext()).isFinishing()) return;
        String[] labels = new String[list.size()];
        for (int i = 0; i < labels.length; i++) labels[i] = list.get(i).label;
        new android.app.AlertDialog.Builder(getContext(), android.R.style.Theme_DeviceDefault_Dialog_Alert)
                .setTitle("Pick your location")
                .setItems(labels, new android.content.DialogInterface.OnClickListener() { public void onClick(android.content.DialogInterface di, int i) {
                    Place p = list.get(i); setPlace(p.name, p.lat, p.lon, p.cc, p.tz);
                } })
                .setNegativeButton("Search again", new android.content.DialogInterface.OnClickListener() { public void onClick(android.content.DialogInterface di, int i) { askPlace(); } })
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
            case Z_TOP:
                if (dx != 0) { int n = topIdx + dx; if (n < 0) zone = Z_HERO; else topIdx = clamp(n, 0, TOP.length - 1); }
                else if (dy > 0) zone = Z_HERO;
                else if (ok) topAction(topIdx);
                break;
            case Z_HERO:
                if (dx < 0) { if (heroIdx > 0) heroIdx--; else zone = Z_NAV; }
                else if (dx > 0) { if (heroIdx < 1) heroIdx++; else zone = Z_TOP; }
                else if (dy > 0) zone = Z_APPS;
                else if (dy < 0) zone = Z_TOP;
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

    void topAction(int i) {
        if (i == 0) start(new Intent("android.search.action.GLOBAL_SEARCH"));
        else if (i == 1) start(new Intent(Intent.ACTION_ASSIST));
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
        if (back) { if (pickMode) finishPicker(); else closeAll(); return; }
        int n = all.size(); if (n == 0) return;
        if (dx != 0) allIdx = clamp(allIdx + dx, 0, n - 1);
        if (dy != 0) { int t = allIdx + dy * ALL_COLS; if (t >= 0 && t < n) allIdx = t; else if (dy > 0) allIdx = n - 1; }
        if (ok) { if (pickMode) togglePick(all.get(allIdx).pkg); else { launch(all.get(allIdx).pkg); closeAll(); } }
        int row = allIdx / ALL_COLS;
        if (row < allScroll) allScroll = row;
        if (row > allScroll + 2) allScroll = row - 2;
    }

    static final String[] SET_LABELS = { "Home button", "Favorites", "Background", "Clock format", "Clock time zone", "Low power mode", "Glass blur", "Temperature", "Weather location", "Open when TV starts", "App update", "Android TV settings" };
    void settingsKey(boolean ok, boolean back, int dx, int dy) {
        if (back) { showSettings = false; return; }
        if (dy != 0) { setIdx = clamp(setIdx + dy, 0, SET_LABELS.length - 1); return; }
        if (!ok && dx == 0) return;
        int step = dx == 0 ? 1 : dx;
        SharedPreferences.Editor e = prefs.edit();
        switch (setIdx) {
            case 0: if (ok) { if (homeServiceOn() && HomeKeyService.switchToGoogleTv(getContext())) showSettings = false; else start(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)); } break;
            case 1: if (ok) { e.apply(); openPicker(); return; } break;
            case 2: { scanBackgrounds(); int n = bgCount() + 1, cur = bgMode == ROTATE ? n - 1 : bgMode; cur = (cur + step + n) % n;
                      bgMode = cur == n - 1 ? ROTATE : cur; if (bgMode != ROTATE) bgIdx = bgMode; e.putInt("bgMode", bgMode).putInt("bgIdx", bgIdx); dropBitmaps(); break; }
            case 3: clockMode = (clockMode + step + 3) % 3; e.putInt("clock", clockMode); break;
            case 4: tzFromPlace = !tzFromPlace; e.putBoolean("tzFromPlace", tzFromPlace); break;
            case 5: lowPower = !lowPower; e.putBoolean("lowPower", lowPower); dropBitmaps(); break;
            case 6: blurLevel = (blurLevel + step + 3) % 3; e.putInt("blur", blurLevel); dropBitmaps(); break;
            case 7: celsius = !celsius; e.putBoolean("celsius", celsius); clearWeather(); fetchWeather(); break;
            case 8: if (ok) { e.apply(); askPlace(); } break;
            case 9: bootStart = !bootStart; e.putBoolean("bootStart", bootStart); break;
            case 10: if (ok) { if (updater.updateReady()) updater.install(); else updater.check(); } break;
            case 11: if (ok) start(new Intent(Settings.ACTION_SETTINGS)); break;
        }
        e.apply();
    }

    String setValue(int i) {
        switch (i) {
            case 0: return homeServiceOn() ? "WLH Glass — OK switches to Google TV" : "Google TV — OK, then turn on WLH Glass Home button";
            case 1: return (favCustom ? favPkgs.size() + " picked" : "Default") + " — OK to pick";
            case 2: return bgMode != ROTATE ? bgName(bgMode) : "Rotate every 15 min (now " + bgName(bgIdx) + ")";
            case 3: return clockMode == 0 ? "24-hour" : clockMode == 1 ? "12-hour" : "Follow TV (" + (DateFormat.is24HourFormat(getContext()) ? "24-hour" : "12-hour") + ")";
            case 4: return tzFromPlace ? "Weather location (" + (placeTZ.length() > 0 ? placeTZ.replace('_', ' ') : "loading") + ")" : "TV time";
            case 5: return lowPower ? "On" : "Off";
            case 6: return blurLevel == 0 ? "Light" : blurLevel == 1 ? "Medium" : "Strong";
            case 7: return celsius ? "°C" : "°F";
            case 8: return placeShort() + " — OK to change";
            case 9: return bootStart ? "On" : "Off";
            case 10: {
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
            sp.setColor(0x449FD6FF); sp.setStrokeWidth(16f);
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
        float tx = 1848;
        float wifiW = 26 * 2 + 26 + 12 + measure("Wi-Fi", 22, med);
        float[] topX = { tx - wifiW - 20 - 68 - 20 - 68, tx - wifiW - 20 - 68, tx - wifiW };
        float[] topW = { 68, 68, wifiW };
        for (int i = 0; i < 3; i++) {
            glass(c, topX[i], 64, topW[i], 68, 34, false);
            if (zone == Z_TOP && topIdx == i) focusRing(c, topX[i], 64, topW[i], 68, 34);
            if (i < 2) glyph(c, TOP[i], topX[i] + 34, 98, 28, Color.WHITE);
            else { glyph(c, "Wi-Fi", topX[i] + 26 + 13, 98, 26, Color.WHITE); text(c, "Wi-Fi", topX[i] + 26 + 26 + 12, 106, 22, med, Color.WHITE, Paint.Align.LEFT); }
        }

        // hero
        glass(c, 440, 190, 1000, 400, 44, false);
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
        if (pickMode) {
            text(c, "Pick favorites  " + favPkgs.size() + "/8", 180, 170, 44, bold, Color.WHITE, Paint.Align.LEFT);
            text(c, "OK adds or removes  ·  Order = the order you pick  ·  Back saves", 1740, 170, 22, reg, 0xCCFFFFFF, Paint.Align.RIGHT);
        } else {
            text(c, "All apps", 180, 170, 44, bold, Color.WHITE, Paint.Align.LEFT);
            text(c, all.size() + " apps  ·  Back to close", 1740, 170, 22, reg, 0xCCFFFFFF, Paint.Align.RIGHT);
        }
        float tile = 160, gapX = 66, gapY = 92;
        for (int i = 0; i < all.size(); i++) {
            int row = i / ALL_COLS - allScroll, col = i % ALL_COLS;
            if (row < 0 || row > 2) continue;
            float x = 180 + col * (tile + gapX), y = 230 + row * (tile + gapY);
            glass(c, x, y, tile, tile, 38, false);
            if (i == allIdx) focusRing(c, x, y, tile, tile, 38);
            icon(c, iconFor(all.get(i)), x + 28, y + 28, tile - 56);
            if (pickMode) {
                int pos = favPkgs.indexOf(all.get(i).pkg);
                if (pos >= 0) {
                    p.setColor(0xFF2F7BFF); c.drawCircle(x + tile - 14, y + 14, 22, p);
                    text(c, String.valueOf(pos + 1), x + tile - 14, y + 23, 24, bold, Color.WHITE, Paint.Align.CENTER);
                }
            }
            String lab = all.get(i).label;
            if (measure(lab, 20, med) > tile + 50) lab = lab.substring(0, Math.min(lab.length(), 14)) + "…";
            text(c, lab, x + tile / 2, y + tile + 34, 20, med, Color.WHITE, Paint.Align.CENTER);
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
        for (int i = 0; i < SET_LABELS.length; i++) {
            float y = 230 + i * 60;
            if (i == setIdx) { p.setColor(0x2EFFFFFF); r.set(500, y, 1420, y + 52); c.drawRoundRect(r, 18, 18, p); focusRing(c, 500, y, 920, 52, 18); }
            text(c, SET_LABELS[i], 540, y + 34, 22, med, Color.WHITE, Paint.Align.LEFT);
            text(c, setValue(i), 1384, y + 34, 20, reg, 0xE0FFFFFF, Paint.Align.RIGHT);
        }
    }
}
