package kz.pricechecker;

import android.content.Context;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Хранит загруженный прайс в памяти и выполняет поиск. */
public class PriceRepository {
    private static volatile PriceRepository instance;

    private volatile List<Product> products = Collections.emptyList();
    private volatile String info = "";

    public static PriceRepository get() {
        if (instance == null) {
            synchronized (PriceRepository.class) {
                if (instance == null) instance = new PriceRepository();
            }
        }
        return instance;
    }

    /** Локальная копия прайса в памяти приложения. */
    public static File localFile(Context c) {
        return new File(c.getFilesDir(), "price.xlsx");
    }

    public boolean isLoaded() { return !products.isEmpty(); }
    public int size() { return products.size(); }
    public List<Product> all() { return products; }
    public String info() { return info; }

    /** Читает файл и подменяет данные. Вызывать в фоновом потоке. */
    public XlsxReader.Result load(File f) throws Exception {
        XlsxReader.Result r = XlsxReader.read(f);
        if (r.products.isEmpty()) throw new Exception("В файле не найдено ни одного товара. " + r.columnsInfo);
        products = Collections.unmodifiableList(r.products);
        info = r.columnsInfo;
        return r;
    }

    /** Точный поиск по штрихкоду (учитывает ведущие нули: UPC/EAN). */
    public List<Product> findByBarcode(String code) {
        List<Product> out = new ArrayList<>();
        String key = Product.barcodeKey(code);
        if (key.isEmpty()) return out;
        for (Product p : products) {
            if (p.barcodeKeys.contains(key)) out.add(p);
        }
        return out;
    }

    /**
     * Поиск для выпадающего списка: все слова запроса должны встречаться в наименовании
     * (в любом порядке), либо цифры запроса — в штрихкоде.
     */
    public List<Product> search(String query, int limit) {
        List<Product> starts = new ArrayList<>();
        List<Product> contains = new ArrayList<>();
        String q = Product.normalizeText(query);
        if (q.isEmpty()) return starts;
        String[] words = q.split(" ");
        String digits = q.replaceAll("[^0-9]", "");
        boolean numeric = digits.length() >= 3 && digits.length() == q.replace(" ", "").length();
        String digitKey = numeric ? Product.barcodeKey(digits) : "";

        for (Product p : products) {
            boolean match = true;
            for (String w : words) {
                if (!p.searchName.contains(w)) { match = false; break; }
            }
            if (!match && numeric) {
                for (String k : p.barcodeKeys) {
                    if (k.contains(digitKey)) { match = true; break; }
                }
            }
            if (!match) continue;
            if (p.searchName.startsWith(q) || (numeric && p.barcodeKeys.contains(digitKey))) starts.add(p);
            else contains.add(p);
            if (starts.size() >= limit) break;
        }
        List<Product> out = new ArrayList<>(starts);
        for (Product p : contains) {
            if (out.size() >= limit) break;
            out.add(p);
        }
        return out;
    }
}
