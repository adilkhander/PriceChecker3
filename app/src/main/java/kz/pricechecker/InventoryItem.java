package kz.pricechecker;

import java.math.BigDecimal;

/** Одна посчитанная позиция инвентаризации. */
public class InventoryItem {
    public long id;
    /** Устойчивый ключ товара (штрихкод + наименование) — по нему строка находится в Google-таблице. */
    public String key = "";
    public String folder = "";
    public String name = "";
    public String barcode = "";
    /** Цена из прайса, может быть null. */
    public BigDecimal price;
    /** Остаток в учётной системе на момент подсчёта, может быть null. */
    public BigDecimal system;
    /** Фактический остаток. В режиме «подготовки таблицы» бывает null. */
    public BigDecimal counted;
    public long countedAt;

    /** Фактический минус учётный; null, если остаток в учёте не указан. */
    public BigDecimal diff() {
        if (counted == null || system == null) return null;
        return counted.subtract(system);
    }

    /** Сумма разницы по цене; 0, если цена или остаток неизвестны. */
    public BigDecimal diffSum() {
        BigDecimal d = diff();
        if (d == null || price == null) return Money.ZERO;
        return Money.round(d.multiply(price));
    }

    public static InventoryItem of(Product p, BigDecimal counted, long countedAt) {
        InventoryItem it = new InventoryItem();
        it.key = InventoryLogic.keyOf(p);
        it.folder = InventoryLogic.groupName(p);
        it.name = p.name;
        it.barcode = p.mainBarcode();
        it.price = p.price;
        it.system = p.stock;
        it.counted = counted;
        it.countedAt = countedAt;
        return it;
    }
}
