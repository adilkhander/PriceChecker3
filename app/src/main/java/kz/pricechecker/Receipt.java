package kz.pricechecker;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Товарный чек. */
public class Receipt {
    public static final int UNPAID = 0;
    public static final int PAID = 1;
    public static final int CANCELLED = 2;

    public static class Item {
        public String name;
        public String barcode;
        public BigDecimal qty;
        public BigDecimal price;

        public Item(String name, String barcode, BigDecimal qty, BigDecimal price) {
            this.name = name;
            this.barcode = barcode == null ? "" : barcode;
            this.qty = qty;
            this.price = price;
        }

        public BigDecimal sum() {
            return Money.lineSum(qty, price);
        }
    }

    public long id;
    public int kassa;
    public int seq;
    public String number = "";
    public long createdAt;
    public int status = UNPAID;
    public long paidAt;
    public long cancelledAt;
    public BigDecimal cash = Money.ZERO;          // наличные, зачтённые в оплату (получено − сдача)
    public BigDecimal kaspi = Money.ZERO;
    public BigDecimal halyk = Money.ZERO;
    public BigDecimal cashReceived = Money.ZERO;  // получено наличными от покупателя
    public BigDecimal change = Money.ZERO;        // сдача
    public String cashier = "";
    public String comment = "";
    public final List<Item> items = new ArrayList<>();

    public BigDecimal total() {
        BigDecimal t = Money.ZERO;
        for (Item i : items) t = t.add(i.sum());
        return Money.round(t);
    }

    public String statusText() {
        switch (status) {
            case PAID: return "ОПЛАЧЕН";
            case CANCELLED: return "АННУЛИРОВАН";
            default: return "НЕ ОПЛАЧЕН";
        }
    }

    public String paymentMethodsText() {
        List<String> m = new ArrayList<>();
        if (Money.isPositive(cash)) m.add("Наличные");
        if (Money.isPositive(kaspi)) m.add("Kaspi");
        if (Money.isPositive(halyk)) m.add("Halyk");
        return String.join(" + ", m);
    }

    /** Результат экрана оплаты. */
    public static class Payment {
        public BigDecimal cashReceived = Money.ZERO, kaspi = Money.ZERO, halyk = Money.ZERO;
        public BigDecimal change = Money.ZERO;

        /** Проверка: безнал не больше суммы, всего внесено не меньше суммы. Возвращает текст ошибки или null. */
        public String validate(BigDecimal total) {
            BigDecimal card = kaspi.add(halyk);
            if (cashReceived.signum() < 0 || kaspi.signum() < 0 || halyk.signum() < 0) return "Суммы не могут быть отрицательными";
            if (card.compareTo(total) > 0) return "Оплата Kaspi + Halyk больше суммы чека";
            BigDecimal paid = card.add(cashReceived);
            if (paid.compareTo(total) < 0) return "Не хватает " + Money.tg(total.subtract(paid));
            return null;
        }

        public void applyTo(Receipt r) {
            BigDecimal total = r.total();
            change = Money.round(cashReceived.add(kaspi).add(halyk).subtract(total).max(Money.ZERO));
            r.cashReceived = Money.round(cashReceived);
            r.kaspi = Money.round(kaspi);
            r.halyk = Money.round(halyk);
            r.change = change;
            r.cash = Money.round(cashReceived.subtract(change));
        }
    }
}
