package kz.pricechecker;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.database.MatrixCursor;
import android.graphics.Bitmap;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.provider.OpenableColumns;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/**
 * Файлы для отправки (чеки, выгрузки) лежат в памяти приложения (files/share)
 * и отдаются другим приложениям через этот ContentProvider (аналог FileProvider без AndroidX).
 * Также — сохранение копий в Галерею и «Загрузки».
 */
public class ShareFiles extends ContentProvider {
    public static final String AUTHORITY = "kz.pricechecker.files";
    public static final String MIME_XLSX = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";

    public static File dir(Context c) {
        File d = new File(c.getFilesDir(), "share");
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    public static Uri uriFor(File f) {
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath(f.getName()).build();
    }

    private File fileFor(Uri uri) throws FileNotFoundException {
        String name = uri.getLastPathSegment();
        if (name == null || name.contains("/") || name.contains("..")) throw new FileNotFoundException();
        File f = new File(dir(getContext()), name);
        if (!f.exists()) throw new FileNotFoundException(name);
        return f;
    }

    static String mimeOf(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".xlsx")) return MIME_XLSX;
        return "application/octet-stream";
    }

    @Override public boolean onCreate() { return true; }

    @Override public String getType(Uri uri) {
        String n = uri.getLastPathSegment();
        return n == null ? null : mimeOf(n);
    }

    @Override
    public ParcelFileDescriptor openFile(Uri uri, String mode) throws FileNotFoundException {
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY);
    }

    @Override
    public Cursor query(Uri uri, String[] projection, String selection, String[] selectionArgs, String sortOrder) {
        File f;
        try { f = fileFor(uri); } catch (FileNotFoundException e) { return null; }
        String[] cols = projection != null ? projection : new String[]{OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE};
        MatrixCursor mc = new MatrixCursor(cols, 1);
        Object[] row = new Object[cols.length];
        for (int i = 0; i < cols.length; i++) {
            if (OpenableColumns.DISPLAY_NAME.equals(cols[i])) row[i] = f.getName();
            else if (OpenableColumns.SIZE.equals(cols[i])) row[i] = f.length();
        }
        mc.addRow(row);
        return mc;
    }

    @Override public Uri insert(Uri uri, ContentValues values) { return null; }
    @Override public int delete(Uri uri, String selection, String[] selectionArgs) { return 0; }
    @Override public int update(Uri uri, ContentValues values, String selection, String[] selectionArgs) { return 0; }

    // ================= сохранение =================

    public static File savePng(Context c, Bitmap bmp, String fileName) throws IOException {
        File f = new File(dir(c), fileName);
        try (OutputStream os = new FileOutputStream(f)) {
            bmp.compress(Bitmap.CompressFormat.PNG, 100, os);
        }
        return f;
    }

    /** На Android 9 и ниже для записи в общие папки нужно разрешение. */
    public static boolean needsStoragePermission(Context c) {
        return Build.VERSION.SDK_INT < 29
                && c.checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                != android.content.pm.PackageManager.PERMISSION_GRANTED;
    }

    /** Копия картинки в Галерею (альбом «Чеки»). */
    public static void copyToGallery(Context c, File src, String displayName) throws IOException {
        copyToPublic(c, src, displayName, "image/png", true, "Чеки");
    }

    /** Копия файла в «Загрузки» (папка «Чеки»). */
    public static void copyToDownloads(Context c, File src, String mime) throws IOException {
        copyToPublic(c, src, src.getName(), mime, false, "Чеки");
    }

    /** Копия файла в «Загрузки» в указанную подпапку (например, «Остатки»). */
    public static void copyToDownloads(Context c, File src, String mime, String folder) throws IOException {
        copyToPublic(c, src, src.getName(), mime, false, folder);
    }

    @SuppressWarnings("deprecation") // путь к общей папке на Android 9 и ниже
    private static void copyToPublic(Context c, File src, String displayName, String mime, boolean image, String folder) throws IOException {
        if (Build.VERSION.SDK_INT >= 29) {
            ContentValues v = new ContentValues();
            v.put(MediaStore.MediaColumns.DISPLAY_NAME, displayName);
            v.put(MediaStore.MediaColumns.MIME_TYPE, mime);
            v.put(MediaStore.MediaColumns.RELATIVE_PATH,
                    (image ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_DOWNLOADS) + "/" + folder);
            v.put(MediaStore.MediaColumns.IS_PENDING, 1);
            Uri collection = image ? MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    : MediaStore.Downloads.EXTERNAL_CONTENT_URI;
            Uri uri = c.getContentResolver().insert(collection, v);
            if (uri == null) throw new IOException("Не удалось создать файл в общей папке");
            try (OutputStream os = c.getContentResolver().openOutputStream(uri);
                 InputStream is = new FileInputStream(src)) {
                if (os == null) throw new IOException("Нет доступа к общей папке");
                copy(is, os);
            }
            v.clear();
            v.put(MediaStore.MediaColumns.IS_PENDING, 0);
            c.getContentResolver().update(uri, v, null, null);
        } else {
            File base = Environment.getExternalStoragePublicDirectory(
                    image ? Environment.DIRECTORY_PICTURES : Environment.DIRECTORY_DOWNLOADS);
            File d = new File(base, folder);
            //noinspection ResultOfMethodCallIgnored
            d.mkdirs();
            File out = new File(d, displayName);
            try (InputStream is = new FileInputStream(src); OutputStream os = new FileOutputStream(out)) {
                copy(is, os);
            }
            MediaScannerConnection.scanFile(c, new String[]{out.getAbsolutePath()}, new String[]{mime}, null);
        }
    }

    private static void copy(InputStream is, OutputStream os) throws IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = is.read(buf)) > 0) os.write(buf, 0, n);
    }

    // ================= отправка =================

    /** Отправка картинки в WhatsApp (обычный или Business) с выбором чата. */
    public static void sendToWhatsApp(Activity a, File f, String caption) {
        Intent i = sendIntent(f, caption);
        for (String pkg : new String[]{"com.whatsapp", "com.whatsapp.w4b"}) {
            try {
                i.setPackage(pkg);
                a.startActivity(i);
                return;
            } catch (ActivityNotFoundException ignored) {
                // пробуем следующий вариант
            }
        }
        i.setPackage(null);
        a.startActivity(Intent.createChooser(i, "WhatsApp не найден — отправить через…"));
    }

    /** Отправка через любое приложение (Telegram, почта, Диск…). */
    public static void share(Activity a, File f, String caption, String title) {
        a.startActivity(Intent.createChooser(sendIntent(f, caption), title));
    }

    private static Intent sendIntent(File f, String caption) {
        Uri uri = uriFor(f);
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType(mimeOf(f.getName()));
        i.putExtra(Intent.EXTRA_STREAM, uri);
        if (caption != null) i.putExtra(Intent.EXTRA_TEXT, caption);
        i.setClipData(android.content.ClipData.newRawUri(f.getName(), uri));
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return i;
    }
}
