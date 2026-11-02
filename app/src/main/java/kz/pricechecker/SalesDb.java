package kz.pricechecker;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Хранилище чеков (SQLite в памяти приложения). */
public class SalesDb extends SQLiteOpenHelper {
    private static SalesDb instance;

    public static synchronized SalesDb get(Context c) {
        if (instance == null) instance = new SalesDb(c.getApplicationContext());
        return instance;
    }

    private SalesDb(Context c) {
        super(c, "sales.db", null, 1);
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE receipts (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "kassa INTEGER NOT NULL," +
                "seq INTEGER NOT NULL," +
                "number TEXT NOT NULL," +
                "created_at INTEGER NOT NULL," +
                "status INTEGER NOT NULL," +
                "paid_at INTEGER NOT NULL DEFAULT 0," +
                "cancelled_at INTEGER NOT NULL DEFAULT 0," +
                "total TEXT NOT NULL," +
                "cash TEXT NOT NULL DEFAULT '0'," +
                "kaspi TEXT NOT NULL DEFAULT '0'," +
                "halyk TEXT NOT NULL DEFAULT '0'," +
                "cash_received TEXT NOT NULL DEFAULT '0'," +
                "change_amount TEXT NOT NULL DEFAULT '0'," +
                "cashier TEXT NOT NULL DEFAULT ''," +
                "comment TEXT NOT NULL DEFAULT ''," +
                "UNIQUE(kassa, seq))");
        db.execSQL("CREATE TABLE receipt_items (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "receipt_id INTEGER NOT NULL," +
                "line INTEGER NOT NULL," +
                "name TEXT NOT NULL," +
                "barcode TEXT NOT NULL DEFAULT ''," +
                "qty TEXT NOT NULL," +
                "price TEXT NOT NULL," +
                "sum TEXT NOT NULL)");
        db.execSQL("CREATE INDEX idx_receipts_created ON receipts(created_at)");
        db.execSQL("CREATE INDEX idx_items_receipt ON receipt_items(receipt_id)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    public static String formatNumber(int kassa, int seq) {
        return kassa + "-" + String.format(Locale.ROOT, "%06d", seq);
    }

    /** Сохраняет новый чек: присваивает номер (касса-порядковый) и время. */
    public synchronized void insert(Receipt r) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            int seq = 1;
            try (Cursor c = db.rawQuery("SELECT MAX(seq) FROM receipts WHERE kassa=?",
                    new String[]{String.valueOf(r.kassa)})) {
                if (c.moveToFirst() && !c.isNull(0)) seq = c.getInt(0) + 1;
            }
            r.seq = seq;
            r.number = formatNumber(r.kassa, seq);
            if (r.createdAt == 0) r.createdAt = System.currentTimeMillis();
            ContentValues v = values(r);
            v.put("kassa", r.kassa);
            v.put("seq", r.seq);
            v.put("number", r.number);
            v.put("created_at", r.createdAt);
            r.id = db.insertOrThrow("receipts", null, v);
            int line = 1;
            for (Receipt.Item i : r.items) {
                ContentValues iv = new ContentValues();
                iv.put("receipt_id", r.id);
                iv.put("line", line++);
                iv.put("name", i.name);
                iv.put("barcode", i.barcode);
                iv.put("qty", Money.store(i.qty));
                iv.put("price", Money.store(i.price));
                iv.put("sum", Money.store(i.sum()));
                db.insertOrThrow("receipt_items", null, iv);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** Обновляет статус и оплату (оплата позже, аннулирование). */
    public synchronized void updatePayment(Receipt r) {
        getWritableDatabase().update("receipts", values(r), "_id=?", new String[]{String.valueOf(r.id)});
    }

    private static ContentValues values(Receipt r) {
        ContentValues v = new ContentValues();
        v.put("status", r.status);
        v.put("paid_at", r.paidAt);
        v.put("cancelled_at", r.cancelledAt);
        v.put("total", Money.store(r.total()));
        v.put("cash", Money.store(r.cash));
        v.put("kaspi", Money.store(r.kaspi));
        v.put("halyk", Money.store(r.halyk));
        v.put("cash_received", Money.store(r.cashReceived));
        v.put("change_amount", Money.store(r.change));
        v.put("cashier", r.cashier == null ? "" : r.cashier);
        v.put("comment", r.comment == null ? "" : r.comment);
        return v;
    }

    public Receipt load(long id) {
        List<Receipt> l = query("_id=?", new String[]{String.valueOf(id)}, true);
        return l.isEmpty() ? null : l.get(0);
    }

    /** Чеки за период [from, to) по времени создания; новые сверху. */
    public List<Receipt> list(long from, long to, boolean withItems) {
        return query("created_at>=? AND created_at<?", new String[]{String.valueOf(from), String.valueOf(to)}, withItems);
    }

    public List<Receipt> listUnpaid() {
        return query("status=?", new String[]{String.valueOf(Receipt.UNPAID)}, false);
    }

    private List<Receipt> query(String where, String[] args, boolean withItems) {
        SQLiteDatabase db = getReadableDatabase();
        List<Receipt> out = new ArrayList<>();
        try (Cursor c = db.query("receipts", null, where, args, null, null, "created_at DESC, _id DESC")) {
            while (c.moveToNext()) {
                Receipt r = new Receipt();
                r.id = c.getLong(c.getColumnIndexOrThrow("_id"));
                r.kassa = c.getInt(c.getColumnIndexOrThrow("kassa"));
                r.seq = c.getInt(c.getColumnIndexOrThrow("seq"));
                r.number = c.getString(c.getColumnIndexOrThrow("number"));
                r.createdAt = c.getLong(c.getColumnIndexOrThrow("created_at"));
                r.status = c.getInt(c.getColumnIndexOrThrow("status"));
                r.paidAt = c.getLong(c.getColumnIndexOrThrow("paid_at"));
                r.cancelledAt = c.getLong(c.getColumnIndexOrThrow("cancelled_at"));
                r.cash = Money.load(c.getString(c.getColumnIndexOrThrow("cash")));
                r.kaspi = Money.load(c.getString(c.getColumnIndexOrThrow("kaspi")));
                r.halyk = Money.load(c.getString(c.getColumnIndexOrThrow("halyk")));
                r.cashReceived = Money.load(c.getString(c.getColumnIndexOrThrow("cash_received")));
                r.change = Money.load(c.getString(c.getColumnIndexOrThrow("change_amount")));
                r.cashier = c.getString(c.getColumnIndexOrThrow("cashier"));
                r.comment = c.getString(c.getColumnIndexOrThrow("comment"));
                out.add(r);
            }
        }
        // позиции нужны для итоговой суммы, поэтому загружаются всегда
        for (Receipt r : out) loadItems(db, r);
        return out;
    }

    private static void loadItems(SQLiteDatabase db, Receipt r) {
        r.items.clear();
        try (Cursor c = db.query("receipt_items", new String[]{"name", "barcode", "qty", "price"},
                "receipt_id=?", new String[]{String.valueOf(r.id)}, null, null, "line")) {
            while (c.moveToNext()) {
                r.items.add(new Receipt.Item(c.getString(0), c.getString(1),
                        Money.load(c.getString(2)), Money.load(c.getString(3))));
            }
        }
    }
}
