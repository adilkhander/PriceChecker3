package kz.pricechecker;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;
import android.text.TextUtils;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatWriter;
import com.google.zxing.common.BitMatrix;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Рисует ценник по шаблону:
 *   наименование (до 3 строк)
 *   крупная цена «4 200 ₸»
 *   штрихкод с цифрами            дата печати
 */
public class LabelRenderer {
    private static Typeface bold, mono;

    private final int width, height; // в точках принтера

    public LabelRenderer(Context c, Settings s) {
        if (bold == null) {
            try {
                bold = Typeface.createFromAsset(c.getAssets(), "fonts/DejaVuSans-Bold.ttf");
                mono = Typeface.createFromAsset(c.getAssets(), "fonts/DejaVuSansMono.ttf");
            } catch (Exception e) {
                bold = Typeface.DEFAULT_BOLD;
                mono = Typeface.MONOSPACE;
            }
        }
        float dotsPerMm = s.dpi() / 25.4f;
        int w = Math.round(s.labelWidthMm() * dotsPerMm);
        width = Math.max(64, w - (w % 8));   // ширина кратна 8 (байт растра)
        height = Math.max(40, Math.round(s.labelHeightMm() * dotsPerMm));
    }

    public static String today() {
        return new SimpleDateFormat("dd.MM.yyyy", Locale.ROOT).format(new Date());
    }

    public Bitmap render(Product p) {
        return render(p.name, p.price == null ? "—" : Product.formatMoney(p.price) + " ₸",
                p.mainBarcode(), today());
    }

    public Bitmap render(String name, String priceText, String barcode, String date) {
        Bitmap bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        Canvas cv = new Canvas(bmp);
        cv.drawColor(Color.WHITE);

        final float W = width, H = height;
        final float m = Math.max(8, W * 0.035f);
        final float innerW = W - 2 * m;

        // ---------- 1. Наименование ----------
        TextPaint np = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        np.setColor(Color.BLACK);
        np.setTypeface(bold);
        float nameSize = H * 0.075f;
        StaticLayout nameLayout;
        while (true) {
            np.setTextSize(nameSize);
            nameLayout = makeLayout(name, np, (int) innerW, Integer.MAX_VALUE);
            if (nameLayout.getLineCount() <= 3 || nameSize <= H * 0.048f) break;
            nameSize *= 0.93f;
        }
        if (nameLayout.getLineCount() > 3) nameLayout = makeLayout(name, np, (int) innerW, 3);
        float nameTop = m * 0.8f;
        cv.save();
        cv.translate(m, nameTop);
        nameLayout.draw(cv);
        cv.restore();
        float nameBottom = nameTop + nameLayout.getHeight();

        // ---------- 2. Нижняя строка: штрихкод + дата ----------
        Paint ink = new Paint();
        ink.setColor(Color.BLACK);
        ink.setStyle(Paint.Style.FILL);

        Paint dp = new Paint(Paint.ANTI_ALIAS_FLAG);
        dp.setColor(Color.BLACK);
        dp.setTypeface(bold);
        dp.setTextSize(H * 0.058f);
        dp.setTextAlign(Paint.Align.RIGHT);

        float bottomBase = H - m * 0.7f;                 // базовая линия цифр и даты
        float dateWidth = dp.measureText(date);
        float digitsSize = H * 0.058f;
        float barH = H * 0.17f;
        float barTop = bottomBase - digitsSize - H * 0.012f - barH;
        float priceAreaBottom;

        BitMatrix code = encodeBarcode(barcode);
        if (code != null) {
            int modules = code.getWidth();
            float maxBarW = W - 2 * m - dateWidth - m;      // место левее даты
            int module = Math.max(1, (int) Math.floor(maxBarW / modules));
            float barX = m;
            for (int x = 0; x < modules; x++) {
                if (code.get(x, 0)) {
                    cv.drawRect(barX + x * module, barTop, barX + (x + 1) * module, barTop + barH, ink);
                }
            }
            Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
            tp.setColor(Color.BLACK);
            tp.setTypeface(mono);
            tp.setTextSize(digitsSize);
            tp.setTextAlign(Paint.Align.CENTER);
            float barW = modules * module;
            String human = barcode.trim();
            // если цифры шире штрихкода — уменьшаем шрифт
            while (tp.measureText(human) > Math.max(barW, maxBarW) && tp.getTextSize() > 8) {
                tp.setTextSize(tp.getTextSize() * 0.92f);
            }
            cv.drawText(human, barX + barW / 2f, bottomBase, tp);
            priceAreaBottom = barTop - H * 0.02f;
        } else {
            priceAreaBottom = bottomBase - dp.getTextSize() - H * 0.02f;
        }
        cv.drawText(date, W - m, bottomBase, dp);

        // ---------- 3. Цена ----------
        Paint pp = new Paint(Paint.ANTI_ALIAS_FLAG);
        pp.setColor(Color.BLACK);
        pp.setTypeface(bold);
        float areaTop = nameBottom + H * 0.02f;
        float areaH = Math.max(H * 0.1f, priceAreaBottom - areaTop);
        float priceSize = Math.min(H * 0.27f, areaH / 0.75f);
        pp.setTextSize(priceSize);
        while (pp.measureText(priceText) > innerW && priceSize > 10) {
            priceSize *= 0.95f;
            pp.setTextSize(priceSize);
        }
        float capH = priceSize * 0.73f;                     // высота цифр DejaVu ≈ 0.73 em
        float baseline = areaTop + (areaH + capH) / 2f;
        cv.drawText(priceText, m, baseline, pp);

        return bmp;
    }

    private static StaticLayout makeLayout(String text, TextPaint tp, int w, int maxLines) {
        StaticLayout.Builder b = StaticLayout.Builder.obtain(text, 0, text.length(), tp, w)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setLineSpacing(0f, 1.0f)
                .setIncludePad(false);
        if (maxLines != Integer.MAX_VALUE) {
            b.setMaxLines(maxLines).setEllipsize(TextUtils.TruncateAt.END);
        }
        return b.build();
    }

    /** Подбирает формат: EAN-13 / EAN-8 / UPC-A по длине и контрольной цифре, иначе Code 128. */
    static BitMatrix encodeBarcode(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        for (int i = 0; i < s.length(); i++) if (s.charAt(i) < 32 || s.charAt(i) > 126) return null;

        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 0);
        MultiFormatWriter w = new MultiFormatWriter();
        boolean digits = s.matches("[0-9]+");
        BarcodeFormat[] tries;
        if (digits && s.length() == 13) tries = new BarcodeFormat[]{BarcodeFormat.EAN_13, BarcodeFormat.CODE_128};
        else if (digits && s.length() == 12) tries = new BarcodeFormat[]{BarcodeFormat.UPC_A, BarcodeFormat.CODE_128};
        else if (digits && s.length() == 8) tries = new BarcodeFormat[]{BarcodeFormat.EAN_8, BarcodeFormat.CODE_128};
        else tries = new BarcodeFormat[]{BarcodeFormat.CODE_128};
        for (BarcodeFormat f : tries) {
            try {
                return w.encode(s, f, 1, 1, hints);
            } catch (Exception ignored) {
                // неверная контрольная цифра — пробуем следующий формат
            }
        }
        return null;
    }

    public int getWidth() { return width; }
    public int getHeight() { return height; }
}
