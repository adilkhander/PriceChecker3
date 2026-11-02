package kz.pricechecker;

import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Выгрузка чеков за период в Excel — для последующего ввода в учётную систему. */
public final class SalesExporter {
    private SalesExporter() { }

    /** Итоги по чекам (используются и на экране журнала). */
    public static class Totals {
        public int count, paidCount, unpaidCount, cancelledCount;
        public BigDecimal paid = Money.ZERO, cash = Money.ZERO, kaspi = Money.ZERO, halyk = Money.ZERO;
        public BigDecimal unpaid = Money.ZERO, cancelled = Money.ZERO;

        public static Totals of(List<Receipt> list) {
            Totals t = new Totals();
            for (Receipt r : list) {
                t.count++;
                BigDecimal sum = r.total();
                if (r.status == Receipt.PAID) {
                    t.paidCount++;
                    t.paid = t.paid.add(sum);
                    t.cash = t.cash.add(r.cash);
                    t.kaspi = t.kaspi.add(r.kaspi);
                    t.halyk = t.halyk.add(r.halyk);
                } else if (r.status == Receipt.UNPAID) {
                    t.unpaidCount++;
                    t.unpaid = t.unpaid.add(sum);
                } else {
                    t.cancelledCount++;
                    t.cancelled = t.cancelled.add(sum);
                }
            }
            return t;
        }
    }

    /** Список чеков ожидается в любом порядке; в файле — по возрастанию времени. */
    public static void export(List<Receipt> receipts, Date from, Date toInclusive, String shopName, File out) throws IOException {
        List<Receipt> list = new ArrayList<>(receipts);
        list.sort((a, b) -> Long.compare(a.createdAt, b.createdAt));
        SimpleDateFormat d = new SimpleDateFormat("dd.MM.yyyy", Locale.ROOT);
        SimpleDateFormat t = new SimpleDateFormat("HH:mm:ss", Locale.ROOT);
        SimpleDateFormat dt = new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.ROOT);

        XlsxWriter x = new XlsxWriter();
        Totals tot = Totals.of(list);

        // ---- Сводка ----
        XlsxWriter.Sheet s = x.sheet("Сводка").widths(34, 14, 18).headerRows(0);
        s.row(new XlsxWriter.Bold("Продажи в автономном режиме — " + shopName));
        s.row("Период", d.format(from) + " — " + d.format(toInclusive));
        s.row("Сформировано", dt.format(new Date()));
        s.row();
        s.row(new XlsxWriter.Bold("Показатель"), new XlsxWriter.Bold("Чеков"), new XlsxWriter.Bold("Сумма, ₸"));
        s.row("Оплачено", tot.paidCount, tot.paid);
        s.row("   в т.ч. наличные", null, tot.cash);
        s.row("   в т.ч. Kaspi", null, tot.kaspi);
        s.row("   в т.ч. Halyk", null, tot.halyk);
        s.row("Не оплачено (долг)", tot.unpaidCount, tot.unpaid);
        s.row("Аннулировано", tot.cancelledCount, tot.cancelled);
        s.row(new XlsxWriter.Bold("Всего (без аннулированных)"),
                new XlsxWriter.Bold(tot.paidCount + tot.unpaidCount),
                new XlsxWriter.Bold(tot.paid.add(tot.unpaid)));

        // ---- Чеки ----
        XlsxWriter.Sheet c = x.sheet("Чеки").widths(12, 7, 11, 9, 14, 12, 12, 12, 12, 13, 10, 19, 14, 9, 24);
        c.row("№ чека", "Касса", "Дата", "Время", "Статус", "Сумма", "Наличные", "Kaspi", "Halyk",
                "Получено наличными", "Сдача", "Оплачен (дата, время)", "Продавец", "Позиций", "Примечание");
        for (Receipt r : list) {
            Date cr = new Date(r.createdAt);
            c.row(r.number, r.kassa, d.format(cr), t.format(cr), r.statusText(), r.total(),
                    r.cash, r.kaspi, r.halyk, r.cashReceived, r.change,
                    r.paidAt > 0 ? dt.format(new Date(r.paidAt)) : "",
                    r.cashier, r.items.size(), r.comment);
        }

        // ---- Товары в чеках ----
        XlsxWriter.Sheet it = x.sheet("Товары в чеках").widths(12, 11, 9, 14, 5, 50, 16, 9, 11, 12);
        it.row("№ чека", "Дата", "Время", "Статус", "№", "Наименование", "Штрихкод", "Кол-во", "Цена", "Сумма");
        for (Receipt r : list) {
            Date cr = new Date(r.createdAt);
            int n = 1;
            for (Receipt.Item i : r.items) {
                it.row(r.number, d.format(cr), t.format(cr), r.statusText(), n++, i.name, i.barcode, i.qty, i.price, i.sum());
            }
        }

        // ---- Итого по товарам (без аннулированных) ----
        Map<String, Object[]> agg = new LinkedHashMap<>();
        for (Receipt r : list) {
            if (r.status == Receipt.CANCELLED) continue;
            for (Receipt.Item i : r.items) {
                String key = i.name + "\u0001" + i.barcode + "\u0001" + Money.store(i.price);
                Object[] a = agg.get(key);
                if (a == null) {
                    a = new Object[]{i.name, i.barcode, i.price, Money.ZERO, Money.ZERO, Money.ZERO};
                    agg.put(key, a);
                }
                a[3] = ((BigDecimal) a[3]).add(i.qty);
                a[4] = ((BigDecimal) a[4]).add(i.sum());
                if (r.status == Receipt.UNPAID) a[5] = ((BigDecimal) a[5]).add(i.qty);
            }
        }
        XlsxWriter.Sheet g = x.sheet("Итого по товарам").widths(50, 16, 11, 10, 13, 16);
        g.row("Наименование", "Штрихкод", "Цена", "Кол-во", "Сумма", "из них не оплачено, шт");
        List<Object[]> rows = new ArrayList<>(agg.values());
        rows.sort((a, b) -> String.valueOf(a[0]).compareToIgnoreCase(String.valueOf(b[0])));
        BigDecimal sumAll = Money.ZERO;
        for (Object[] a : rows) {
            g.row(a[0], a[1], a[2], a[3], a[4], ((BigDecimal) a[5]).signum() == 0 ? null : a[5]);
            sumAll = sumAll.add((BigDecimal) a[4]);
        }
        g.row(new XlsxWriter.Bold("ИТОГО"), null, null, null, new XlsxWriter.Bold(sumAll));

        x.write(out);
    }
}
