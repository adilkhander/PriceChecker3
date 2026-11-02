package kz.pricechecker;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Денежные и количественные вычисления (BigDecimal, без ошибок округления). */
public final class Money {
    private Money() { }

    public static final BigDecimal ZERO = BigDecimal.ZERO;

    /** "1 500,50" / "1500.5" / "" -> BigDecimal или null. */
    public static BigDecimal parse(String s) {
        if (s == null) return null;
        String t = s.trim().replace(' ', ' ').replace(" ", "").replace(',', '.');
        if (t.isEmpty() || t.equals(".")) return null;
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    public static BigDecimal orZero(BigDecimal v) {
        return v == null ? ZERO : v;
    }

    public static BigDecimal round(BigDecimal v) {
        return orZero(v).setScale(2, RoundingMode.HALF_UP);
    }

    /** Сумма строки: количество × цена, округление до тиын. */
    public static BigDecimal lineSum(BigDecimal qty, BigDecimal price) {
        return round(orZero(qty).multiply(orZero(price)));
    }

    /** "4 200" или "750,50" (без знака валюты). */
    public static String fmt(BigDecimal v) {
        return Product.formatMoney(orZero(v));
    }

    /** "4 200 ₸" */
    public static String tg(BigDecimal v) {
        return fmt(v) + " ₸";
    }

    /** Значение для поля ввода: "4200" / "750.5" -> "750,5". */
    public static String plain(BigDecimal v) {
        if (v == null) return "";
        return v.stripTrailingZeros().toPlainString().replace('.', ',');
    }

    /** Для хранения в базе. */
    public static String store(BigDecimal v) {
        return v == null ? "0" : v.stripTrailingZeros().toPlainString();
    }

    public static BigDecimal load(String s) {
        BigDecimal v = parse(s);
        return v == null ? ZERO : v;
    }

    public static boolean isPositive(BigDecimal v) {
        return v != null && v.signum() > 0;
    }
}
