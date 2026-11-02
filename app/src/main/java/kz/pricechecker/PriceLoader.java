package kz.pricechecker;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Загрузка прайса из сети / с телефона / из локальной копии. Все методы — для фонового потока. */
public final class PriceLoader {
    private PriceLoader() { }

    /** Автообновление при запуске выполняется один раз за сеанс работы приложения. */
    public static volatile boolean autoUpdateDone = false;

    /** Скачать по сетевому пути из настроек. Возвращает строку статуса. */
    public static String loadFromNetwork(Context c) throws Exception {
        Settings s = new Settings(c);
        String path = s.priceUnc().trim();
        if (path.isEmpty()) throw new IOException("Не указан сетевой путь к прайсу (Настройки)");
        SmbHelper.Unc unc = SmbHelper.Unc.parse(path);
        File tmp = new File(c.getFilesDir(), "price_new.xlsx");
        new SmbHelper(s).download(unc, tmp);
        return install(c, tmp, unc.fileName());
    }

    /** Загрузить файл, выбранный в проводнике телефона. */
    public static String loadFromUri(Context c, Uri uri) throws Exception {
        File tmp = new File(c.getFilesDir(), "price_new.xlsx");
        try (InputStream in = c.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(tmp)) {
            if (in == null) throw new IOException("Не удалось открыть файл");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        return install(c, tmp, displayName(c, uri));
    }

    /** Прочитать ранее сохранённую копию (при запуске, без сети). */
    public static boolean loadLocal(Context c) {
        File f = PriceRepository.localFile(c);
        if (!f.exists()) return false;
        try {
            PriceRepository.get().load(f);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /** Проверяет новый файл и, если он читается, заменяет им текущую копию. */
    private static String install(Context c, File tmp, String sourceName) throws Exception {
        XlsxReader.Result r;
        try {
            r = PriceRepository.get().load(tmp);
        } catch (Exception e) {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
            throw e;
        }
        File target = PriceRepository.localFile(c);
        //noinspection ResultOfMethodCallIgnored
        target.delete();
        if (!tmp.renameTo(target)) throw new IOException("Не удалось сохранить копию прайса");

        String when = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT).format(new Date());
        String status = "Загружен " + when + " · товаров: " + r.products.size() + " · " + sourceName;
        new Settings(c).edit().putString("last_load", status).putString("source_name", sourceName).apply();
        return status;
    }

    private static String displayName(Context c, Uri uri) {
        try (Cursor cur = c.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cur != null && cur.moveToFirst()) {
                String n = cur.getString(0);
                if (n != null) return n;
            }
        } catch (Exception ignored) { }
        return "файл с телефона";
    }
}
