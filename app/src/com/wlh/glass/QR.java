package com.wlh.glass;

/**
 * Minimal QR code encoder: byte mode, error correction level L, versions 1-10.
 * Enough for a short URL. Port of the approach in Project Nayuki's QR library (MIT).
 */
final class QR {
    final int size;
    final boolean[][] mod;
    private final boolean[][] fn;

    private static final int[] ECC_PER_BLOCK = { -1, 7, 10, 15, 20, 26, 18, 20, 24, 30, 18 };
    private static final int[] NUM_BLOCKS    = { -1, 1, 1, 1, 1, 1, 2, 2, 2, 2, 4 };

    static QR encode(String text) {
        byte[] data;
        try { data = text.getBytes("UTF-8"); } catch (Exception e) { data = text.getBytes(); }
        for (int v = 1; v <= 10; v++) {
            int cap = dataCodewords(v) * 8;
            int ccBits = v <= 9 ? 8 : 16;
            int need = 4 + ccBits + data.length * 8;
            if (need <= cap) return new QR(v, data, ccBits, cap);
        }
        return null;
    }

    private QR(int ver, byte[] data, int ccBits, int capBits) {
        size = ver * 4 + 17;
        mod = new boolean[size][size];
        fn = new boolean[size][size];
        // --- bit stream
        BitBuf bb = new BitBuf();
        bb.put(4, 4);
        bb.put(data.length, ccBits);
        for (byte b : data) bb.put(b & 0xFF, 8);
        bb.put(0, Math.min(4, capBits - bb.len));
        bb.put(0, (8 - bb.len % 8) % 8);
        for (int pad = 0xEC; bb.len < capBits; pad ^= 0xEC ^ 0x11) bb.put(pad, 8);
        byte[] dataCw = new byte[bb.len / 8];
        for (int i = 0; i < bb.len; i++) if (bb.get(i)) dataCw[i >>> 3] |= 1 << (7 - (i & 7));
        byte[] all = addEcc(ver, dataCw);
        // --- draw
        drawFunction(ver);
        drawCodewords(all);
        int best = 0; long bestPen = Long.MAX_VALUE;
        for (int m = 0; m < 8; m++) {
            applyMask(m); drawFormat(m);
            long pen = penalty();
            if (pen < bestPen) { bestPen = pen; best = m; }
            applyMask(m);
        }
        applyMask(best); drawFormat(best);
    }

    private static int rawModules(int ver) {
        int r = (16 * ver + 128) * ver + 64;
        if (ver >= 2) { int na = ver / 7 + 2; r -= (25 * na - 10) * na - 55; if (ver >= 7) r -= 36; }
        return r;
    }
    private static int dataCodewords(int ver) { return rawModules(ver) / 8 - ECC_PER_BLOCK[ver] * NUM_BLOCKS[ver]; }

    private static byte[] addEcc(int ver, byte[] data) {
        int nb = NUM_BLOCKS[ver], ecl = ECC_PER_BLOCK[ver];
        int raw = rawModules(ver) / 8;
        int nShort = nb - raw % nb, shortLen = raw / nb;
        byte[] div = rsDivisor(ecl);
        byte[][] blocks = new byte[nb][];
        for (int i = 0, k = 0; i < nb; i++) {
            int dl = shortLen - ecl + (i < nShort ? 0 : 1);
            byte[] dat = new byte[dl]; System.arraycopy(data, k, dat, 0, dl); k += dl;
            byte[] ecc = rsRemainder(dat, div);
            byte[] blk = new byte[shortLen + 1];
            System.arraycopy(dat, 0, blk, 0, dl);
            System.arraycopy(ecc, 0, blk, blk.length - ecl, ecl);
            blocks[i] = blk;
        }
        byte[] out = new byte[raw]; int o = 0;
        for (int i = 0; i < blocks[0].length; i++)
            for (int j = 0; j < nb; j++)
                if (i != shortLen - ecl || j >= nShort) out[o++] = blocks[j][i];
        return out;
    }

    private static byte[] rsDivisor(int deg) {
        byte[] r = new byte[deg]; r[deg - 1] = 1; int root = 1;
        for (int i = 0; i < deg; i++) {
            for (int j = 0; j < r.length; j++) { r[j] = (byte) mul(r[j] & 0xFF, root); if (j + 1 < r.length) r[j] ^= r[j + 1]; }
            root = mul(root, 0x02);
        }
        return r;
    }
    private static byte[] rsRemainder(byte[] data, byte[] div) {
        byte[] r = new byte[div.length];
        for (byte b : data) {
            int f = (b ^ r[0]) & 0xFF;
            System.arraycopy(r, 1, r, 0, r.length - 1); r[r.length - 1] = 0;
            for (int i = 0; i < r.length; i++) r[i] ^= mul(div[i] & 0xFF, f);
        }
        return r;
    }
    private static int mul(int x, int y) {
        int z = 0;
        for (int i = 7; i >= 0; i--) { z = (z << 1) ^ ((z >>> 7) * 0x11D); z ^= ((y >>> i) & 1) * x; }
        return z & 0xFF;
    }

    private void set(int x, int y, boolean d) { mod[y][x] = d; fn[y][x] = true; }

    private void drawFunction(int ver) {
        for (int i = 0; i < size; i++) { set(6, i, i % 2 == 0); set(i, 6, i % 2 == 0); }
        finder(3, 3); finder(size - 4, 3); finder(3, size - 4);
        int[] al = alignPos(ver);
        for (int i = 0; i < al.length; i++) for (int j = 0; j < al.length; j++) {
            if ((i == 0 && j == 0) || (i == 0 && j == al.length - 1) || (i == al.length - 1 && j == 0)) continue;
            for (int dy = -2; dy <= 2; dy++) for (int dx = -2; dx <= 2; dx++)
                set(al[i] + dx, al[j] + dy, Math.max(Math.abs(dx), Math.abs(dy)) != 1);
        }
        drawFormat(0);
        if (ver >= 7) {
            int rem = ver; for (int i = 0; i < 12; i++) rem = (rem << 1) ^ ((rem >>> 11) * 0x1F25);
            int bits = ver << 12 | rem;
            for (int i = 0; i < 18; i++) {
                boolean b = ((bits >>> i) & 1) != 0; int a = size - 11 + i % 3, c = i / 3;
                set(a, c, b); set(c, a, b);
            }
        }
    }
    private void finder(int cx, int cy) {
        for (int dy = -4; dy <= 4; dy++) for (int dx = -4; dx <= 4; dx++) {
            int d = Math.max(Math.abs(dx), Math.abs(dy)), x = cx + dx, y = cy + dy;
            if (x >= 0 && x < size && y >= 0 && y < size) set(x, y, d != 2 && d != 4);
        }
    }
    private int[] alignPos(int ver) {
        if (ver == 1) return new int[0];
        int n = ver / 7 + 2;
        int step = (ver * 8 + n * 3 + 5) / (n * 4 - 4) * 2;
        int[] r = new int[n]; r[0] = 6;
        for (int i = r.length - 1, pos = size - 7; i >= 1; i--, pos -= step) r[i] = pos;
        return r;
    }
    private void drawFormat(int mask) {
        int data = 1 << 3 | mask; // ECC level L = 01
        int rem = data; for (int i = 0; i < 10; i++) rem = (rem << 1) ^ ((rem >>> 9) * 0x537);
        int bits = (data << 10 | rem) ^ 0x5412;
        for (int i = 0; i <= 5; i++) set(8, i, bit(bits, i));
        set(8, 7, bit(bits, 6)); set(8, 8, bit(bits, 7)); set(7, 8, bit(bits, 8));
        for (int i = 9; i < 15; i++) set(14 - i, 8, bit(bits, i));
        for (int i = 0; i < 8; i++) set(size - 1 - i, 8, bit(bits, i));
        for (int i = 8; i < 15; i++) set(8, size - 15 + i, bit(bits, i));
        set(8, size - 8, true);
    }
    private static boolean bit(int x, int i) { return ((x >>> i) & 1) != 0; }

    private void drawCodewords(byte[] data) {
        int i = 0;
        for (int right = size - 1; right >= 1; right -= 2) {
            if (right == 6) right = 5;
            for (int vert = 0; vert < size; vert++) for (int j = 0; j < 2; j++) {
                int x = right - j; boolean up = ((right + 1) & 2) == 0;
                int y = up ? size - 1 - vert : vert;
                if (!fn[y][x] && i < data.length * 8) { mod[y][x] = bit(data[i >>> 3], 7 - (i & 7)); i++; }
            }
        }
    }
    private void applyMask(int m) {
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            boolean inv;
            switch (m) {
                case 0: inv = (x + y) % 2 == 0; break;
                case 1: inv = y % 2 == 0; break;
                case 2: inv = x % 3 == 0; break;
                case 3: inv = (x + y) % 3 == 0; break;
                case 4: inv = (x / 3 + y / 2) % 2 == 0; break;
                case 5: inv = x * y % 2 + x * y % 3 == 0; break;
                case 6: inv = (x * y % 2 + x * y % 3) % 2 == 0; break;
                default: inv = ((x + y) % 2 + x * y % 3) % 2 == 0; break;
            }
            if (!fn[y][x] && inv) mod[y][x] = !mod[y][x];
        }
    }
    /** Simplified penalty (runs + 2x2 blocks + balance); good enough to pick a readable mask. */
    private long penalty() {
        long p = 0; int dark = 0;
        for (int y = 0; y < size; y++) {
            int run = 1, runC = 1;
            for (int x = 0; x < size; x++) {
                if (mod[y][x]) dark++;
                if (x > 0) { if (mod[y][x] == mod[y][x - 1]) { run++; if (run == 5) p += 3; else if (run > 5) p++; } else run = 1; }
                if (x > 0) { if (mod[x][y] == mod[x - 1][y]) { runC++; if (runC == 5) p += 3; else if (runC > 5) p++; } else runC = 1; }
                if (x > 0 && y > 0 && mod[y][x] == mod[y][x - 1] && mod[y][x] == mod[y - 1][x] && mod[y][x] == mod[y - 1][x - 1]) p += 3;
            }
        }
        int total = size * size; int k = (Math.abs(dark * 20 - total * 10) + total - 1) / total - 1;
        p += Math.max(0, k) * 10;
        return p;
    }

    private static final class BitBuf {
        int len = 0; byte[] b = new byte[512];
        void put(int val, int n) { for (int i = n - 1; i >= 0; i--) { if (((val >>> i) & 1) != 0) b[len >>> 3] |= 1 << (7 - (len & 7)); len++; } }
        boolean get(int i) { return ((b[i >>> 3] >>> (7 - (i & 7))) & 1) != 0; }
    }
}
