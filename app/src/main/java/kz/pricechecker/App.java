package kz.pricechecker;

import android.app.Application;
import android.os.Build;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Записывает текст ошибки при аварийном закрытии, чтобы показать его при следующем запуске. */
public class App extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        final Thread.UncaughtExceptionHandler prev = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            try {
                StringWriter sw = new StringWriter();
                PrintWriter pw = new PrintWriter(sw);
                String version = "";
                try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
                pw.println("Время: " + new SimpleDateFormat("dd.MM.yyyy HH:mm:ss", Locale.ROOT).format(new Date()));
                pw.println("Версия приложения: " + version);
                pw.println("Телефон: " + Build.MANUFACTURER + " " + Build.MODEL + ", Android " + Build.VERSION.RELEASE
                        + " (API " + Build.VERSION.SDK_INT + ")");
                pw.println("Поток: " + thread.getName());
                pw.println();
                ex.printStackTrace(pw);
                pw.flush();
                try (FileOutputStream fos = new FileOutputStream(crashFile(this))) {
                    fos.write(sw.toString().getBytes(StandardCharsets.UTF_8));
                }
            } catch (Throwable ignored) { }
            if (prev != null) prev.uncaughtException(thread, ex);
        });
    }

    static File crashFile(android.content.Context c) {
        return new File(c.getFilesDir(), "last_crash.txt");
    }
}
