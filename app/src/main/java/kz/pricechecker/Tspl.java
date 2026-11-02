package kz.pricechecker;

import android.graphics.Bitmap;
import android.graphics.Color;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/**
 * Формирует задание печати на языке TSPL (Xprinter XP-365B в режиме этикеток, TSC и совместимые).
 * Этикетка передаётся как растровое изображение (команда BITMAP), поэтому кириллица и знак ₸
 * печатаются без шрифтов принтера.
 */
public final class Tspl {
    private Tspl() { }

    public static byte[] build(Bitmap label, Settings s, int copies) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String mmW = num(s.labelWidthMm()), mmH = num(s.labelHeightMm()), gap = num(s.labelGapMm());
        cmd(out, "SIZE " + mmW + " mm," + mmH + " mm");
        cmd(out, "GAP " + gap + " mm,0 mm");
        cmd(out, "DIRECTION " + (s.direction() == 0 ? 0 : 1) + ",0");
        cmd(out, "REFERENCE 0,0");
        cmd(out, "SPEED " + s.speed());
        cmd(out, "DENSITY " + s.density());
        cmd(out, "SET TEAR ON");
        cmd(out, "CLS");

        int w = label.getWidth(), h = label.getHeight();
        int bytesPerRow = (w + 7) / 8;
        byte[] data = new byte[bytesPerRow * h];
        int[] row = new int[w];
        boolean invert = s.invertBitmap();
        for (int y = 0; y < h; y++) {
            label.getPixels(row, 0, w, 0, y, w, 1);
            for (int xb = 0; xb < bytesPerRow; xb++) {
                int b = 0;
                for (int bit = 0; bit < 8; bit++) {
                    int x = xb * 8 + bit;
                    boolean black = false;
                    if (x < w) {
                        int c = row[x];
                        int lum = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000;
                        black = Color.alpha(c) > 128 && lum < 128;
                    }
                    // В TSPL бит 0 = печать (чёрный), бит 1 = пусто (белый)
                    boolean setBit = invert ? black : !black;
                    if (setBit) b |= (0x80 >> bit);
                }
                data[y * bytesPerRow + xb] = (byte) b;
            }
        }
        String head = "BITMAP " + Math.max(0, s.offsetXDots()) + "," + Math.max(0, s.offsetYDots()) + ","
                + bytesPerRow + "," + h + ",0,";
        out.write(head.getBytes(StandardCharsets.US_ASCII), 0, head.length());
        out.write(data, 0, data.length);
        cmd(out, "");
        cmd(out, "PRINT 1," + Math.max(1, copies));
        return out.toByteArray();
    }

    private static void cmd(ByteArrayOutputStream out, String c) {
        byte[] b = (c + "\r\n").getBytes(StandardCharsets.US_ASCII);
        out.write(b, 0, b.length);
    }

    private static String num(float v) {
        if (v == Math.round(v)) return String.valueOf(Math.round(v));
        return String.format(Locale.ROOT, "%.1f", v);
    }
}
