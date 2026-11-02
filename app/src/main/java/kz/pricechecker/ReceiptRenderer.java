package kz.pricechecker;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.text.Layout;
import android.text.StaticLayout;
import android.text.TextPaint;

import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Рисует товарный чек картинкой (для галереи и WhatsApp). */
public class ReceiptRenderer {
    private static final int W = 720;
    private static final int PAD = 36;
    private static Typeface regular, bold, mono;

    private final Settings s;
    private Canvas c;     // null на первом проходе (только измерение)
    private float y;

    public ReceiptRenderer(Context ctx) {
        s = new Settings(ctx);
        if (regular == null) {
            try {
                regular = Typeface.createFromAsset(ctx.getAssets(), "fonts/DejaVuSans.ttf");
                bold = Typeface.createFromAsset(ctx.getAssets(), "fonts/DejaVuSans-Bold.ttf");
                mono = Typeface.createFromAsset(ctx.getAssets(), "fonts/DejaVuSansMono.ttf");
            } catch (Exception e) {
                regular = Typeface.DEFAULT;
                bold = Typeface.DEFAULT_BOLD;
                mono = Typeface.MONOSPACE;
            }
        }
    }

    public Bitmap render(Receipt r) {
        c = null;
        y = 0;
        draw(r);
        int h = (int) Math.ceil(y);
        Bitmap bmp = Bitmap.createBitmap(W, h, Bitmap.Config.ARGB_8888);
        c = new Canvas(bmp);
        c.drawColor(Color.WHITE);
        y = 0;
        draw(r);
        c = null;
        return bmp;
    }

    private void draw(Receipt r) {
        SimpleDateFormat d = new SimpleDateFormat("dd.MM.yyyy", Locale.ROOT);
        SimpleDateFormat t = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
        SimpleDateFormat dt = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.ROOT);
        y = PAD;

        // ---- шапка магазина ----
        text(s.shopName(), bold, 34, Layout.Alignment.ALIGN_CENTER, Color.BLACK);
        if (!s.shopBin().trim().isEmpty()) text("БИН/ИИН: " + s.shopBin().trim(), regular, 22, Layout.Alignment.ALIGN_CENTER, Color.DKGRAY);
        if (!s.shopAddress().trim().isEmpty()) text(s.shopAddress().trim(), regular, 22, Layout.Alignment.ALIGN_CENTER, Color.DKGRAY);
        if (!s.shopPhone().trim().isEmpty()) text("Тел.: " + s.shopPhone().trim(), regular, 22, Layout.Alignment.ALIGN_CENTER, Color.DKGRAY);
        gap(10);
        separator();

        text("ТОВАРНЫЙ ЧЕК № " + r.number, bold, 30, Layout.Alignment.ALIGN_CENTER, Color.BLACK);
        gap(6);
        Date created = new Date(r.createdAt);
        row("Дата: " + d.format(created), "Время: " + t.format(created), regular, 24, Color.BLACK);
        String cashier = r.cashier == null ? "" : r.cashier.trim();
        row("Касса № " + r.kassa, cashier.isEmpty() ? "" : "Продавец: " + cashier, regular, 22, Color.DKGRAY);
        separator();

        // ---- позиции ----
        int n = 1;
        for (Receipt.Item i : r.items) {
            text(n++ + ". " + i.name, regular, 24, Layout.Alignment.ALIGN_NORMAL, Color.BLACK);
            String bc = i.barcode == null || i.barcode.isEmpty() ? "" : i.barcode;
            String calc = Product.formatQty(i.qty) + " × " + Money.fmt(i.price) + " = ";
            row(bc, calc + Money.fmt(i.sum()), regular, 22, Color.DKGRAY, mono, bold, Color.BLACK);
            gap(8);
        }
        separator();

        // ---- итог ----
        row("ИТОГО:", Money.tg(r.total()), bold, 38, Color.BLACK);
        row("Позиций: " + r.items.size(), "", regular, 20, Color.GRAY);
        gap(4);
        separator();

        // ---- оплата ----
        if (r.status == Receipt.PAID) {
            text("Оплата:", bold, 24, Layout.Alignment.ALIGN_NORMAL, Color.BLACK);
            if (Money.isPositive(r.cash)) row("  Наличные", Money.tg(r.cash), regular, 24, Color.BLACK);
            if (Money.isPositive(r.kaspi)) row("  Kaspi", Money.tg(r.kaspi), regular, 24, Color.BLACK);
            if (Money.isPositive(r.halyk)) row("  Halyk", Money.tg(r.halyk), regular, 24, Color.BLACK);
            if (Money.isPositive(r.cashReceived)) {
                gap(4);
                row("  Получено наличными", Money.tg(r.cashReceived), regular, 22, Color.DKGRAY);
                row("  Сдача", Money.tg(r.change), regular, 22, Color.DKGRAY);
            }
            if (r.paidAt > 0 && Math.abs(r.paidAt - r.createdAt) > 60_000) {
                gap(4);
                row("  Оплачен", dt.format(new Date(r.paidAt)), regular, 22, Color.DKGRAY);
            }
            gap(10);
        }

        int stampColor = r.status == Receipt.PAID ? Color.rgb(30, 123, 60)
                : r.status == Receipt.CANCELLED ? Color.GRAY : Color.rgb(180, 35, 24);
        stamp(r.statusText(), stampColor);
        if (r.status == Receipt.CANCELLED && r.cancelledAt > 0) {
            text("Аннулирован " + dt.format(new Date(r.cancelledAt)), regular, 20, Layout.Alignment.ALIGN_CENTER, Color.GRAY);
        }
        if (r.comment != null && !r.comment.trim().isEmpty()) {
            gap(6);
            text("Примечание: " + r.comment.trim(), regular, 22, Layout.Alignment.ALIGN_NORMAL, Color.BLACK);
        }
        gap(10);
        separator();

        if (!s.receiptFooter().trim().isEmpty()) text(s.receiptFooter().trim(), bold, 24, Layout.Alignment.ALIGN_CENTER, Color.BLACK);
        gap(4);
        text("Документ не является фискальным чеком", regular, 18, Layout.Alignment.ALIGN_CENTER, Color.GRAY);
        y += PAD;
    }

    // ---------- примитивы ----------

    private void text(String str, Typeface tf, float size, Layout.Alignment align, int color) {
        TextPaint p = new TextPaint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(tf);
        p.setTextSize(size);
        p.setColor(color);
        StaticLayout l = StaticLayout.Builder.obtain(str, 0, str.length(), p, W - 2 * PAD)
                .setAlignment(align).setIncludePad(false).setLineSpacing(2f, 1f).build();
        if (c != null) {
            c.save();
            c.translate(PAD, y);
            l.draw(c);
            c.restore();
        }
        y += l.getHeight() + 4;
    }

    private void row(String left, String right, Typeface tf, float size, int color) {
        row(left, right, tf, size, color, tf, tf, color);
    }

    /** Строка «слева … справа». Левый текст обрезается, если не помещается. */
    private void row(String left, String right, Typeface tf, float size, int color,
                     Typeface leftTf, Typeface rightTf, int rightColor) {
        Paint lp = new Paint(Paint.ANTI_ALIAS_FLAG);
        lp.setTypeface(leftTf);
        lp.setTextSize(size);
        lp.setColor(color);
        Paint rp = new Paint(Paint.ANTI_ALIAS_FLAG);
        rp.setTypeface(rightTf);
        rp.setTextSize(size);
        rp.setColor(rightColor);
        rp.setTextAlign(Paint.Align.RIGHT);
        Paint.FontMetrics fm = rp.getFontMetrics();
        float lineH = fm.descent - fm.ascent;
        float rightW = rp.measureText(right);
        float maxLeft = W - 2 * PAD - rightW - 16;
        String l = left;
        while (!l.isEmpty() && lp.measureText(l) > maxLeft) l = l.substring(0, l.length() - 1);
        if (c != null) {
            float base = y - fm.ascent;
            c.drawText(l, PAD, base, lp);
            c.drawText(right, W - PAD, base, rp);
        }
        y += lineH + 4;
    }

    private void separator() {
        y += 8;
        if (c != null) {
            Paint p = new Paint();
            p.setColor(Color.GRAY);
            p.setStrokeWidth(2f);
            p.setPathEffect(new DashPathEffect(new float[]{10f, 8f}, 0f));
            c.drawLine(PAD, y, W - PAD, y, p);
        }
        y += 14;
    }

    private void stamp(String str, int color) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setTypeface(bold);
        p.setTextSize(34);
        p.setColor(color);
        p.setTextAlign(Paint.Align.CENTER);
        Paint.FontMetrics fm = p.getFontMetrics();
        float tw = p.measureText(str);
        float bh = (fm.descent - fm.ascent) + 20;
        if (c != null) {
            RectF box = new RectF(W / 2f - tw / 2f - 24, y, W / 2f + tw / 2f + 24, y + bh);
            Paint b = new Paint(Paint.ANTI_ALIAS_FLAG);
            b.setStyle(Paint.Style.STROKE);
            b.setStrokeWidth(4f);
            b.setColor(color);
            c.drawRoundRect(box, 10f, 10f, b);
            c.drawText(str, W / 2f, y + 10 - fm.ascent, p);
        }
        y += bh + 8;
    }

    private void gap(float px) { y += px; }

    /** Краткий текст для подписи в мессенджере. */
    public static String caption(Receipt r) {
        SimpleDateFormat dt = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT);
        BigDecimal total = r.total();
        return "Товарный чек № " + r.number + " от " + dt.format(new Date(r.createdAt))
                + "\nСумма: " + Money.tg(total) + " — " + r.statusText().toLowerCase(Locale.ROOT);
    }
}
