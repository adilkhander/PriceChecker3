package kz.pricechecker;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Хранилище инвентаризации (SQLite в памяти приложения): сеансы и посчитанные позиции. */
public class InventoryDb extends SQLiteOpenHelper {
    private static InventoryDb instance;

    public static synchronized InventoryDb get(Context c) {
        if (instance == null) instance = new InventoryDb(c.getApplicationContext());
        return instance;
    }

    private InventoryDb(Context c) {
        super(c, "inventory.db", null, 1);
    }

    /** Сеанс инвентаризации. ended_at = 0 — сеанс идёт. */
    public static class Session {
        public long id;
        public String seller = "";
        public long startedAt;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE inv_session (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "started_at INTEGER NOT NULL," +
                "ended_at INTEGER NOT NULL DEFAULT 0," +
                "seller TEXT NOT NULL DEFAULT '')");
        db.execSQL("CREATE TABLE inv_count (" +
                "_id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "session_id INTEGER NOT NULL," +
                "pkey TEXT NOT NULL," +
                "folder TEXT NOT NULL DEFAULT ''," +
                "name TEXT NOT NULL," +
                "barcode TEXT NOT NULL DEFAULT ''," +
                "price TEXT NOT NULL DEFAULT ''," +
                "system_stock TEXT NOT NULL DEFAULT ''," +
                "counted TEXT NOT NULL," +
                "counted_at INTEGER NOT NULL," +
                "synced INTEGER NOT NULL DEFAULT 0," +
                "UNIQUE(session_id, pkey))");
        db.execSQL("CREATE INDEX idx_inv_count_session ON inv_count(session_id, synced)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) { }

    // ================= сеансы =================

    /** Текущий незавершённый сеанс или null. */
    public synchronized Session activeSession() {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery(
                "SELECT _id, started_at, seller FROM inv_session WHERE ended_at=0 ORDER BY _id DESC LIMIT 1", null)) {
            if (!c.moveToFirst()) return null;
            Session s = new Session();
            s.id = c.getLong(0);
            s.startedAt = c.getLong(1);
            s.seller = c.getString(2);
            return s;
        }
    }

    public synchronized Session startSession(String seller) {
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        long now = System.currentTimeMillis();
        v.put("started_at", now);
        v.put("seller", seller);
        long id = db.insertOrThrow("inv_session", null, v);
        Session s = new Session();
        s.id = id;
        s.startedAt = now;
        s.seller = seller;
        return s;
    }

    public synchronized void endSession(long id, long endedAt) {
        ContentValues v = new ContentValues();
        v.put("ended_at", endedAt);
        getWritableDatabase().update("inv_session", v, "_id=?", new String[]{String.valueOf(id)});
    }

    /** Полностью удаляет сеанс и его данные (отмена инвентаризации). */
    public synchronized void discardSession(long id) {
        SQLiteDatabase db = getWritableDatabase();
        String[] a = new String[]{String.valueOf(id)};
        db.beginTransaction();
        try {
            db.delete("inv_count", "session_id=?", a);
            db.delete("inv_session", "_id=?", a);
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    // ================= позиции =================

    /** Записывает остаток товара; повторный ввод заменяет прежний и снова ставит позицию в очередь отправки. */
    public synchronized void saveCount(long sessionId, InventoryItem it) {
        ContentValues v = new ContentValues();
        v.put("session_id", sessionId);
        v.put("pkey", it.key);
        v.put("folder", it.folder);
        v.put("name", it.name);
        v.put("barcode", it.barcode);
        v.put("price", it.price == null ? "" : Money.store(it.price));
        v.put("system_stock", it.system == null ? "" : Money.store(it.system));
        v.put("counted", Money.store(it.counted));
        v.put("counted_at", it.countedAt);
        v.put("synced", 0);
        getWritableDatabase().insertWithOnConflict("inv_count", null, v, SQLiteDatabase.CONFLICT_REPLACE);
    }

    /** Ключи посчитанных товаров сеанса и их остатки. */
    public synchronized Map<String, BigDecimal> counted(long sessionId) {
        Map<String, BigDecimal> m = new HashMap<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT pkey, counted FROM inv_count WHERE session_id=?",
                new String[]{String.valueOf(sessionId)})) {
            while (c.moveToNext()) m.put(c.getString(0), Money.load(c.getString(1)));
        }
        return m;
    }

    public synchronized List<InventoryItem> items(long sessionId) {
        return query("session_id=?", new String[]{String.valueOf(sessionId)}, "counted_at", 0);
    }

    /** Позиции, ещё не отправленные в Google-таблицу. */
    public synchronized List<InventoryItem> pending(long sessionId, int limit) {
        return query("session_id=? AND synced=0", new String[]{String.valueOf(sessionId)}, "counted_at", limit);
    }

    public synchronized int pendingCount(long sessionId) {
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.rawQuery("SELECT COUNT(*) FROM inv_count WHERE session_id=? AND synced=0",
                new String[]{String.valueOf(sessionId)})) {
            return c.moveToFirst() ? c.getInt(0) : 0;
        }
    }

    /** Помечает позицию отправленной, если за время отправки её не успели изменить. */
    public synchronized void markSynced(long id, long countedAt) {
        ContentValues v = new ContentValues();
        v.put("synced", 1);
        getWritableDatabase().update("inv_count", v, "_id=? AND counted_at=?",
                new String[]{String.valueOf(id), String.valueOf(countedAt)});
    }

    /** Ставит все позиции сеанса в очередь на повторную отправку. */
    public synchronized void resetSynced(long sessionId) {
        ContentValues v = new ContentValues();
        v.put("synced", 0);
        getWritableDatabase().update("inv_count", v, "session_id=?", new String[]{String.valueOf(sessionId)});
    }

    private List<InventoryItem> query(String where, String[] args, String orderBy, int limit) {
        List<InventoryItem> out = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        try (Cursor c = db.query("inv_count",
                new String[]{"_id", "pkey", "folder", "name", "barcode", "price", "system_stock", "counted", "counted_at"},
                where, args, null, null, orderBy, limit > 0 ? String.valueOf(limit) : null)) {
            while (c.moveToNext()) {
                InventoryItem it = new InventoryItem();
                it.id = c.getLong(0);
                it.key = c.getString(1);
                it.folder = c.getString(2);
                it.name = c.getString(3);
                it.barcode = c.getString(4);
                it.price = Money.parse(c.getString(5));
                it.system = Money.parse(c.getString(6));
                it.counted = Money.load(c.getString(7));
                it.countedAt = c.getLong(8);
                out.add(it);
            }
        }
        return out;
    }
}
