package kz.pricechecker;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Отправка остатков в общую Google-таблицу через веб-приложение Google Apps Script.
 * Каждый телефон добавляет в таблицу свой столбец «Остатки <имя продавца>».
 */
public final class SheetSync {
    private SheetSync() { }

    public static class Result {
        public final boolean ok;
        public final String message;

        Result(boolean ok, String message) {
            this.ok = ok;
            this.message = message;
        }
    }

    public static boolean configured(Settings s) {
        return !s.sheetUrl().trim().isEmpty();
    }

    /** Проверка связи и ключа. */
    public static Result ping(Settings s, String seller) {
        return post(s, "ping", seller, null);
    }

    /** Отправка позиций. Позиции без остатка (counted == null) только создают строку в таблице. */
    public static Result send(Settings s, String seller, List<InventoryItem> items) {
        return post(s, "upsert", seller, items);
    }

    private static Result post(Settings s, String action, String seller, List<InventoryItem> items) {
        String url = s.sheetUrl().trim();
        if (url.isEmpty()) return new Result(false, "Адрес Google-таблицы не указан в настройках");
        if (!url.startsWith("https://")) return new Result(false, "Адрес должен начинаться с https://");
        try {
            JSONObject body = new JSONObject();
            body.put("action", action);
            body.put("secret", s.sheetSecret().trim());
            body.put("seller", seller);
            if (items != null) {
                JSONArray arr = new JSONArray();
                for (InventoryItem it : items) arr.put(toJson(it));
                body.put("items", arr);
            }
            String resp = request(url, body.toString());
            JSONObject o;
            try {
                o = new JSONObject(resp);
            } catch (Exception e) {
                return new Result(false, "Скрипт вернул не тот ответ. Проверьте, что у веб-приложения Google "
                        + "доступ «Все» (Anyone), и что вставлен адрес, оканчивающийся на /exec");
            }
            if (o.optBoolean("ok", false)) return new Result(true, o.optString("message", "OK"));
            return new Result(false, o.optString("error", "Неизвестная ошибка скрипта"));
        } catch (Exception e) {
            String m = e.getMessage();
            return new Result(false, m == null || m.isEmpty() ? e.toString() : m);
        }
    }

    static JSONObject toJson(InventoryItem it) throws Exception {
        JSONObject o = new JSONObject();
        o.put("key", it.key);
        o.put("folder", it.folder);
        o.put("name", it.name);
        o.put("barcode", it.barcode);
        o.put("price", it.price == null ? JSONObject.NULL : (Object) it.price.doubleValue());
        o.put("system", it.system == null ? JSONObject.NULL : (Object) it.system.doubleValue());
        o.put("counted", it.counted == null ? JSONObject.NULL : (Object) it.counted.doubleValue());
        o.put("ts", it.countedAt);
        return o;
    }

    /**
     * POST с ручным следованием перенаправлениям: веб-приложение Google отвечает на POST
     * перенаправлением (302), результат нужно забрать обычным GET по новому адресу.
     */
    private static String request(String urlStr, String payload) throws IOException {
        String cur = urlStr;
        String method = "POST";
        for (int hop = 0; hop < 6; hop++) {
            HttpURLConnection c = (HttpURLConnection) new URL(cur).openConnection();
            try {
                c.setInstanceFollowRedirects(false);
                c.setConnectTimeout(15000);
                c.setReadTimeout(60000);
                c.setRequestMethod(method);
                if (method.equals("POST")) {
                    c.setDoOutput(true);
                    c.setRequestProperty("Content-Type", "text/plain; charset=utf-8");
                    try (OutputStream os = c.getOutputStream()) {
                        os.write(payload.getBytes(StandardCharsets.UTF_8));
                    }
                }
                int code = c.getResponseCode();
                if (code >= 300 && code < 400) {
                    String loc = c.getHeaderField("Location");
                    if (loc == null || loc.isEmpty()) throw new IOException("Пустое перенаправление (HTTP " + code + ")");
                    cur = new URL(new URL(cur), loc).toString();
                    method = "GET";
                    continue;
                }
                InputStream is = code >= 400 ? c.getErrorStream() : c.getInputStream();
                String text = is == null ? "" : readAll(is);
                if (code >= 400) throw new IOException("Ошибка сервера Google: HTTP " + code);
                return text;
            } finally {
                c.disconnect();
            }
        }
        throw new IOException("Слишком много перенаправлений");
    }

    private static String readAll(InputStream is) throws IOException {
        try (InputStream in = is; ByteArrayOutputStream bo = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        }
    }
}
