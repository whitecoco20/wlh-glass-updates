package com.wlh.glass;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Enumeration;
import java.util.Locale;

/**
 * Tiny web page on the home network so a phone can send photos to the TV.
 * Runs only while the "Add photos from your phone" screen is open, and every
 * request must carry a random token that is shown only on the TV (in the QR code).
 */
final class PhotoServer {
    interface Listener { void received(String name); void removed(String name); }

    static final int PORT = 8765;
    final File dir;
    final String token;
    final Listener listener;
    private ServerSocket ss;
    private volatile boolean running;

    PhotoServer(File dir, Listener l) {
        this.dir = dir; this.listener = l;
        String abc = "abcdefghjkmnpqrstuvwxyz23456789";
        java.security.SecureRandom r = new java.security.SecureRandom();
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < 6; i++) b.append(abc.charAt(r.nextInt(abc.length())));
        token = b.toString();
    }

    String url() { String ip = localIp(); return ip == null ? null : "http://" + ip + ":" + PORT + "/" + token; }

    static String localIp() {
        try {
            String fallback = null;
            for (Enumeration<NetworkInterface> en = NetworkInterface.getNetworkInterfaces(); en.hasMoreElements(); ) {
                NetworkInterface ni = en.nextElement();
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName().toLowerCase(Locale.US);
                if (n.startsWith("tun") || n.startsWith("ppp") || n.startsWith("ipsec")) continue; // VPN tunnels
                for (Enumeration<InetAddress> a = ni.getInetAddresses(); a.hasMoreElements(); ) {
                    InetAddress ad = a.nextElement();
                    if (ad instanceof Inet4Address && ad.isSiteLocalAddress()) {
                        if (n.startsWith("wlan") || n.startsWith("eth")) return ad.getHostAddress();
                        if (fallback == null) fallback = ad.getHostAddress();
                    }
                }
            }
            return fallback;
        } catch (Throwable t) { return null; }
    }

    boolean start() {
        try {
            ss = new ServerSocket(PORT);
            running = true;
            new Thread(new Runnable() { public void run() {
                while (running) {
                    try {
                        final Socket s = ss.accept();
                        new Thread(new Runnable() { public void run() { handle(s); } }).start();
                    } catch (Throwable t) { if (!running) break; }
                }
            } }).start();
            return true;
        } catch (Throwable t) { return false; }
    }

    void stop() { running = false; try { if (ss != null) ss.close(); } catch (Throwable t) { } ss = null; }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(30000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            String head = readHead(in);
            if (head == null) { s.close(); return; }
            String[] lines = head.split("\r\n");
            String[] rl = lines[0].split(" ");
            if (rl.length < 2) { s.close(); return; }
            String method = rl[0], target = rl[1];
            long len = 0;
            for (String h : lines) if (h.toLowerCase(Locale.US).startsWith("content-length:")) len = Long.parseLong(h.substring(15).trim());
            String path = target, query = "";
            int q = target.indexOf('?'); if (q >= 0) { path = target.substring(0, q); query = target.substring(q + 1); }
            String tok = param(query, "t");
            if (method.equals("GET") && path.equals("/" + token)) {
                send(out, 200, "text/html; charset=utf-8", page().getBytes("UTF-8"));
            } else if (!token.equals(tok)) {
                send(out, 403, "text/plain", "This link has expired. Open the QR code on your TV again.".getBytes("UTF-8"));
            } else if (method.equals("GET") && path.equals("/list")) {
                StringBuilder b = new StringBuilder("[");
                File[] f = dir.listFiles();
                if (f != null) { java.util.Arrays.sort(f); for (File x : f) if (x.isFile()) { if (b.length() > 1) b.append(','); b.append('"').append(x.getName().replace("\"", "")).append('"'); } }
                b.append(']');
                send(out, 200, "application/json", b.toString().getBytes("UTF-8"));
            } else if (method.equals("POST") && path.equals("/up")) {
                if (len <= 0 || len > 15L * 1024 * 1024) { send(out, 413, "text/plain", "Too big".getBytes()); s.close(); return; }
                if (!dir.exists()) dir.mkdirs();
                String base = clean(param(query, "n"));
                File f = new File(dir, base + ".jpg");
                for (int i = 2; f.exists(); i++) f = new File(dir, base + " " + i + ".jpg");
                FileOutputStream fo = new FileOutputStream(f);
                byte[] buf = new byte[16384]; long left = len;
                while (left > 0) { int r = in.read(buf, 0, (int) Math.min(buf.length, left)); if (r < 0) break; fo.write(buf, 0, r); left -= r; }
                fo.close();
                if (left > 0) { f.delete(); send(out, 400, "text/plain", "Upload cut off".getBytes()); }
                else { send(out, 200, "text/plain", f.getName().getBytes("UTF-8")); if (listener != null) listener.received(f.getName()); }
            } else if (method.equals("POST") && path.equals("/del")) {
                String n = param(query, "n");
                File f = new File(dir, n);
                boolean ok = f.getParentFile() != null && f.getParentFile().getCanonicalPath().equals(dir.getCanonicalPath()) && f.isFile() && f.delete();
                send(out, ok ? 200 : 404, "text/plain", (ok ? "Deleted" : "Not found").getBytes());
                if (ok && listener != null) listener.removed(n);
            } else {
                send(out, 404, "text/plain", "Not found".getBytes());
            }
            s.close();
        } catch (Throwable t) { try { s.close(); } catch (Throwable ignored) { } }
    }

    private static String readHead(InputStream in) throws Exception {
        StringBuilder b = new StringBuilder(); int c, n = 0;
        while ((c = in.read()) >= 0) {
            b.append((char) c);
            if (b.length() > 8192) return null;
            int L = b.length();
            if (L >= 4 && b.charAt(L - 4) == '\r' && b.charAt(L - 3) == '\n' && b.charAt(L - 2) == '\r' && b.charAt(L - 1) == '\n') return b.substring(0, L - 4);
        }
        return null;
    }

    private static String param(String q, String k) {
        for (String kv : q.split("&")) {
            int e = kv.indexOf('=');
            if (e > 0 && kv.substring(0, e).equals(k)) {
                try { return java.net.URLDecoder.decode(kv.substring(e + 1), "UTF-8"); } catch (Throwable t) { return kv.substring(e + 1); }
            }
        }
        return "";
    }

    static String clean(String n) {
        int dot = n.lastIndexOf('.'); if (dot > 0) n = n.substring(0, dot);
        StringBuilder b = new StringBuilder();
        for (char ch : n.toCharArray()) if (Character.isLetterOrDigit(ch) || ch == ' ' || ch == '-' || ch == '_') b.append(ch);
        String r = b.toString().trim();
        if (r.length() > 40) r = r.substring(0, 40).trim();
        return r.length() == 0 ? "Photo" : r;
    }

    private static void send(OutputStream out, int code, String type, byte[] body) throws Exception {
        String st = code == 200 ? "OK" : code == 403 ? "Forbidden" : code == 404 ? "Not Found" : code == 413 ? "Payload Too Large" : "Bad Request";
        String h = "HTTP/1.1 " + code + " " + st + "\r\nContent-Type: " + type + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes("UTF-8")); out.write(body); out.flush();
    }

    private String page() {
        return "<!doctype html><html><head><meta charset=utf-8><meta name=viewport content='width=device-width,initial-scale=1'>"
            + "<title>WLH Glass photos</title><style>"
            + "body{margin:0;font:16px system-ui,sans-serif;background:#0b0d1a;color:#fff;padding:20px}"
            + "h1{font-size:22px;margin:0 0 6px}p{color:#c9d3e6;margin:0 0 16px}"
            + "label.b{display:block;text-align:center;background:#2f7bff;color:#fff;padding:16px;border-radius:14px;font-weight:600;font-size:18px}"
            + "input{display:none}#st{margin:16px 0;min-height:22px;color:#9fd6ff}"
            + "ul{list-style:none;padding:0;margin:0}li{display:flex;justify-content:space-between;align-items:center;background:#ffffff14;border-radius:12px;padding:12px 14px;margin:8px 0}"
            + "button{background:#ffffff22;color:#fff;border:0;border-radius:10px;padding:8px 12px;font-size:14px}"
            + "</style></head><body><h1>Send photos to your TV</h1>"
            + "<p>Pick one or more photos. They become backgrounds and screensaver pictures on WLH Glass.</p>"
            + "<label class=b>Choose photos<input id=f type=file accept='image/*' multiple></label><div id=st></div>"
            + "<h1 style='font-size:18px;margin-top:24px'>On the TV now</h1><ul id=l></ul><script>"
            + "var T='" + token + "',st=document.getElementById('st');"
            + "function list(){fetch('/list?t='+T).then(function(r){return r.json()}).then(function(a){var l=document.getElementById('l');l.innerHTML='';"
            + "if(!a.length){l.innerHTML='<li>No photos yet</li>';return}"
            + "a.forEach(function(n){var li=document.createElement('li');var s=document.createElement('span');s.textContent=n;var b=document.createElement('button');b.textContent='Remove';"
            + "b.onclick=function(){if(confirm('Remove '+n+' from the TV?'))fetch('/del?t='+T+'&n='+encodeURIComponent(n),{method:'POST'}).then(list)};li.appendChild(s);li.appendChild(b);l.appendChild(li)})})}"
            + "function shrink(file){return new Promise(function(ok,bad){var u=URL.createObjectURL(file),i=new Image();i.onload=function(){"
            + "var s=Math.min(1,1920/Math.max(i.width,i.height));var c=document.createElement('canvas');c.width=Math.round(i.width*s);c.height=Math.round(i.height*s);"
            + "c.getContext('2d').drawImage(i,0,0,c.width,c.height);URL.revokeObjectURL(u);c.toBlob(function(b){b?ok(b):bad()},'image/jpeg',0.9)};i.onerror=bad;i.src=u})}"
            + "document.getElementById('f').onchange=async function(){var fs=[].slice.call(this.files),done=0;"
            + "for(var k=0;k<fs.length;k++){st.textContent='Sending '+(k+1)+' of '+fs.length+'…';"
            + "try{var b=await shrink(fs[k]);var r=await fetch('/up?t='+T+'&n='+encodeURIComponent(fs[k].name),{method:'POST',body:b});if(r.ok)done++}catch(e){}}"
            + "st.textContent=done+' of '+fs.length+' sent. Look at your TV.';this.value='';list()};list();"
            + "</script></body></html>";
    }
}
