package kz.pricechecker;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Одна позиция прайс-листа. */
public class Product {
    public final String name;
    public final String folder;
    /** Штрихкоды как в файле (без пробелов). Может быть пустым. */
    public final List<String> barcodes;
    /** Нормализованные штрихкоды (только цифры, без ведущих нулей) для сравнения. */
    final List<String> barcodeKeys = new ArrayList<>();
    public final BigDecimal price;   // null, если цена не указана
    public final BigDecimal stock;   // null, если остаток не указан
    final String searchName;

    public Product(String name, String folder, List<String> barcodes, BigDecimal price, BigDecimal stock) {
        this.name = name;
        this.folder = folder == null ? "" : folder;
        this.barcodes = barcodes;
        this.price = price;
        this.stock = stock;
        this.searchName = normalizeText(name);
        for (String b : barcodes) {
            String k = barcodeKey(b);
            if (!k.isEmpty()) barcodeKeys.add(k);
        }
    }

    public String mainBarcode() {
        return barcodes.isEmpty() ? "" : barcodes.get(0);
    }

    public String barcodesText() {
        return String.join(", ", barcodes);
    }

    @Override
    public String toString() {
        return name;
    }

    // ---------- вспомогательные функции ----------

    static String normalizeText(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replace('ё', 'е').replaceAll("\\s+", " ").trim();
    }

    /** Только цифры, без ведущих нулей: "0085246342978" и "085246342978" дают одинаковый ключ. */
    static String barcodeKey(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') sb.append(c);
            else if (Character.isLetter(c)) sb.append(Character.toUpperCase(c));
        }
        int i = 0;
        while (i < sb.length() - 1 && sb.charAt(i) == '0') i++;
        return sb.substring(i);
    }

    /** Формат цены: 4200 -> "4 200", 750.5 -> "750,50". */
    public static String formatMoney(BigDecimal v) {
        if (v == null) return "—";
        BigDecimal s = v.stripTrailingZeros();
        boolean whole = s.scale() <= 0;
        String pattern = whole ? "%,d" : "%,.2f";
        String out = whole ? String.format(Locale.ROOT, pattern, v.toBigInteger())
                           : String.format(Locale.ROOT, pattern, v);
        // разделитель тысяч - неразрывный пробел, дробная часть - запятая
        return out.replace(',', ' ').replace('.', ',');
    }

    public static String formatQty(BigDecimal v) {
        if (v == null) return "—";
        return v.stripTrailingZeros().toPlainString().replace('.', ',');
    }
}
