package kz.pricechecker;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** Выгрузка результатов инвентаризации в Excel. Без зависимостей от Android. */
public final class InventoryExporter {
    private InventoryExporter() { }

    /** Имя файла: «Остатки 2026-11-02 18-45.xlsx» (дата и время окончания сеанса). */
    public static String fileName(long endedAt) {
        return "Остатки " + new SimpleDateFormat("yyyy-MM-dd HH-mm", Locale.ROOT).format(new Date(endedAt)) + ".xlsx";
    }

    /** Итоги сеанса. */
    public static class Summary {
        public int counted, equal, surplusCount, shortageCount, noSystemCount;
        public BigDecimal surplusSum = Money.ZERO, shortageSum = Money.ZERO;
        public int groupsStarted, groupsDone, groupsTotal;

        public BigDecimal totalDiffSum() { return surplusSum.add(shortageSum); }
    }

    public static Summary summarize(List<InventoryItem> items, Map<String, List<Product>> groups) {
        Summary s = new Summary();
        Set<String> keys = new HashSet<>();
        for (InventoryItem it : items) keys.add(it.key);
        for (InventoryItem it : items) {
            if (it.counted == null) continue;
            s.counted++;
            BigDecimal d = it.diff();
            if (d == null) { s.noSystemCount++; continue; }
            int sg = d.signum();
            if (sg == 0) s.equal++;
            else if (sg > 0) { s.surplusCount++; s.surplusSum = s.surplusSum.add(it.diffSum()); }
            else { s.shortageCount++; s.shortageSum = s.shortageSum.add(it.diffSum()); }
        }
        s.groupsTotal = groups.size();
        for (List<Product> g : groups.values()) {
            int c = InventoryLogic.countedIn(g, keys);
            if (c > 0) s.groupsStarted++;
            if (c > 0 && c >= g.size()) s.groupsDone++;
        }
        return s;
    }

    public static void export(List<InventoryItem> items, Map<String, List<Product>> groups, String seller,
                              String shopName, long startedAt, long endedAt, File out) throws IOException {
        SimpleDateFormat dt = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.ROOT);
        SimpleDateFormat tm = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);

        // порядок как в прайс-листе: сначала по группам, затем по товарам
        final Map<String, Integer> pos = new HashMap<>();
        int n = 0;
        for (List<Product> g : groups.values()) {
            for (Product p : g) {
                String k = InventoryLogic.keyOf(p);
                if (!pos.containsKey(k)) pos.put(k, n);
                n++;
            }
        }
        List<InventoryItem> list = new ArrayList<>(items);
        list.sort((a, b) -> {
            int pa = pos.containsKey(a.key) ? pos.get(a.key) : Integer.MAX_VALUE;
            int pb = pos.containsKey(b.key) ? pos.get(b.key) : Integer.MAX_VALUE;
            if (pa != pb) return Integer.compare(pa, pb);
            return Long.compare(a.countedAt, b.countedAt);
        });

        XlsxWriter x = new XlsxWriter();

        // ---- Остатки ----
        XlsxWriter.Sheet s = x.sheet("Остатки").widths(26, 46, 18, 11, 14, 14, 11, 16, 16, 12);
        s.row("Папка (группа)", "Наименование", "Штрихкод", "Цена, ₸", "Остаток в учёте",
                "Фактический остаток", "Разница", "Сумма разницы, ₸", "Продавец", "Время ввода");
        for (InventoryItem it : list) {
            if (it.counted == null) continue;
            s.row(it.folder, it.name, it.barcode, it.price, it.system, it.counted, it.diff(),
                    it.diff() == null ? null : it.diffSum(), seller, tm.format(new Date(it.countedAt)));
        }

        // ---- Сводка ----
        Summary sum = summarize(list, groups);
        XlsxWriter.Sheet t = x.sheet("Сводка").widths(40, 22).headerRows(0);
        t.row(new XlsxWriter.Bold("Инвентаризация — " + shopName));
        t.row("Продавец", seller);
        t.row("Начало", dt.format(new Date(startedAt)));
        t.row("Окончание", dt.format(new Date(endedAt)));
        t.row();
        t.row("Посчитано позиций", sum.counted);
        t.row("Совпало с учётом", sum.equal);
        t.row("Излишки: позиций", sum.surplusCount);
        t.row("Излишки: сумма, ₸", sum.surplusSum);
        t.row("Недостача: позиций", sum.shortageCount);
        t.row("Недостача: сумма, ₸", sum.shortageSum);
        t.row(new XlsxWriter.Bold("Итого разница, ₸"), new XlsxWriter.Bold(sum.totalDiffSum()));
        if (sum.noSystemCount > 0) t.row("Без остатка в учёте (разница не считалась)", sum.noSystemCount);
        t.row();
        t.row("Групп в прайсе", sum.groupsTotal);
        t.row("Групп начато", sum.groupsStarted);
        t.row("Групп завершено полностью", sum.groupsDone);

        // ---- Группы и непосчитанное ----
        Set<String> keys = new HashSet<>();
        for (InventoryItem it : list) if (it.counted != null) keys.add(it.key);

        XlsxWriter.Sheet g = x.sheet("Группы").widths(34, 16, 12, 14, 22);
        g.row("Папка (группа)", "Товаров в группе", "Посчитано", "Не посчитано", "Статус");
        XlsxWriter.Sheet u = x.sheet("Не посчитано").widths(26, 46, 18, 11, 14);
        u.row("Папка (группа)", "Наименование", "Штрихкод", "Цена, ₸", "Остаток в учёте");
        for (Map.Entry<String, List<Product>> e : groups.entrySet()) {
            List<Product> gp = e.getValue();
            int c = InventoryLogic.countedIn(gp, keys);
            if (c == 0) continue;
            g.row(e.getKey(), gp.size(), c, gp.size() - c, c >= gp.size() ? "Готово" : "Не завершена");
            if (c < gp.size()) {
                for (Product p : gp) {
                    if (keys.contains(InventoryLogic.keyOf(p))) continue;
                    u.row(e.getKey(), p.name, p.mainBarcode(), p.price, p.stock);
                }
            }
        }
        x.write(out);
    }
}
