package kz.pricechecker;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Правила инвентаризации по группам (папкам прайса). Без зависимостей от Android. */
public final class InventoryLogic {
    private InventoryLogic() { }

    public static final String NO_GROUP = "Без группы";

    /** Устойчивый ключ товара: нормализованный штрихкод + наименование. */
    public static String keyOf(Product p) {
        String bc = p.barcodeKeys.isEmpty() ? "" : p.barcodeKeys.get(0);
        return bc + "|" + p.searchName;
    }

    public static String groupName(Product p) {
        String f = p.folder == null ? "" : p.folder.trim();
        return f.isEmpty() ? NO_GROUP : f;
    }

    /** Группы в том порядке, в котором они идут в прайс-листе; товары — тоже в порядке прайса. */
    public static Map<String, List<Product>> groups(List<Product> all) {
        Map<String, List<Product>> m = new LinkedHashMap<>();
        for (Product p : all) {
            String g = groupName(p);
            List<Product> list = m.get(g);
            if (list == null) {
                list = new ArrayList<>();
                m.put(g, list);
            }
            list.add(p);
        }
        return m;
    }

    /** Позиция товара в группе (по ключу), -1 если не найден. */
    public static int indexOf(List<Product> group, Product p) {
        if (p == null) return -1;
        String k = keyOf(p);
        for (int i = 0; i < group.size(); i++) {
            if (keyOf(group.get(i)).equals(k)) return i;
        }
        return -1;
    }

    /**
     * Следующий ещё не посчитанный товар той же группы: сначала идущие после текущего,
     * затем (по кругу) стоящие до него. Сам текущий товар пропускается.
     * null — в группе не осталось непосчитанных товаров.
     */
    public static Product next(List<Product> group, Product current, Set<String> countedKeys) {
        int n = group.size();
        if (n == 0) return null;
        int start = indexOf(group, current);
        String curKey = current == null ? null : keyOf(current);
        for (int step = 1; step <= n; step++) {
            Product c = group.get(((start + step) % n + n) % n);
            String k = keyOf(c);
            if (k.equals(curKey)) continue;
            if (!countedKeys.contains(k)) return c;
        }
        return null;
    }

    /** Сколько товаров группы уже посчитано. */
    public static int countedIn(List<Product> group, Set<String> countedKeys) {
        int c = 0;
        for (Product p : group) {
            if (countedKeys.contains(keyOf(p))) c++;
        }
        return c;
    }
}
