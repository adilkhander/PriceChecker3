package kz.pricechecker;

import com.hierynomus.msdtyp.AccessMask;
import com.hierynomus.msfscc.FileAttributes;
import com.hierynomus.msfscc.fileinformation.FileIdBothDirectoryInformation;
import com.hierynomus.mssmb2.SMB2CreateDisposition;
import com.hierynomus.mssmb2.SMB2ShareAccess;
import com.hierynomus.security.bc.BCSecurityProvider;
import com.hierynomus.smbj.SMBClient;
import com.hierynomus.smbj.SmbConfig;
import com.hierynomus.smbj.auth.AuthenticationContext;
import com.hierynomus.smbj.auth.NtlmAuthenticator;
import com.hierynomus.smbj.connection.Connection;
import com.hierynomus.smbj.session.Session;
import com.hierynomus.smbj.share.DiskShare;
import com.hierynomus.smbj.share.PrinterShare;
import com.hierynomus.smbj.share.Share;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/** Работа с общими папками и принтерами Windows по протоколу SMB2/3. */
public class SmbHelper {

    /** Разобранный сетевой путь \\host\share\path\file */
    public static class Unc {
        public final String host, share, path; // path без ведущего "\", может быть пустым

        Unc(String host, String share, String path) {
            this.host = host; this.share = share; this.path = path;
        }

        public static Unc parse(String s) throws IOException {
            if (s == null) s = "";
            String t = s.trim();
            if (t.toLowerCase(Locale.ROOT).startsWith("smb://")) t = t.substring(6);
            t = t.replace('/', '\\');
            while (t.startsWith("\\")) t = t.substring(1);
            List<String> parts = new ArrayList<>();
            for (String x : t.split("\\\\")) if (!x.trim().isEmpty()) parts.add(x.trim());
            if (parts.size() < 2) {
                throw new IOException("Укажите сетевой путь в виде \\\\компьютер\\папка… (например \\\\192.168.1.10\\Общая\\прайс.xlsx)");
            }
            String path = String.join("\\", parts.subList(2, parts.size()));
            return new Unc(parts.get(0), parts.get(1), path);
        }

        public String fileName() {
            int i = path.lastIndexOf('\\');
            return i >= 0 ? path.substring(i + 1) : path;
        }

        @Override public String toString() {
            return "\\\\" + host + "\\" + share + (path.isEmpty() ? "" : "\\" + path);
        }
    }

    public static class Entry {
        public final String name;
        public final boolean dir;
        public final long size;
        Entry(String name, boolean dir, long size) { this.name = name; this.dir = dir; this.size = size; }
    }

    private final String user, password, domain;

    public SmbHelper(Settings s) {
        this.user = s.smbUser().trim();
        this.password = s.smbPassword();
        this.domain = s.smbDomain().trim();
    }

    public SmbHelper(String user, String password, String domain) {
        this.user = user == null ? "" : user.trim();
        this.password = password == null ? "" : password;
        this.domain = domain == null ? "" : domain.trim();
    }

    @SuppressWarnings("unchecked")
    private static SMBClient newClient() {
        SmbConfig cfg = SmbConfig.builder()
                .withSecurityProvider(new BCSecurityProvider())
                .withAuthenticators(new NtlmAuthenticator.Factory())
                .withMultiProtocolNegotiate(true)
                .withTimeout(30, TimeUnit.SECONDS)
                .withSoTimeout(35, TimeUnit.SECONDS)
                .build();
        return new SMBClient(cfg);
    }

    private AuthenticationContext auth() {
        String u = user, d = domain;
        // поддержка ввода в виде DOMAIN\\user или user@domain
        int bs = u.indexOf('\\');
        if (bs > 0 && d.isEmpty()) { d = u.substring(0, bs); u = u.substring(bs + 1); }
        if (u.isEmpty()) return AuthenticationContext.guest();
        return new AuthenticationContext(u, password.toCharArray(), d.isEmpty() ? null : d);
    }

    private interface ShareAction<T> { T run(Share share) throws Exception; }

    private <T> T withShare(Unc unc, ShareAction<T> action) throws Exception {
        try (SMBClient client = newClient();
             Connection conn = client.connect(unc.host)) {
            Session session = conn.authenticate(auth());
            try (Share share = session.connectShare(unc.share)) {
                return action.run(share);
            } finally {
                try { session.close(); } catch (Exception ignored) { }
            }
        } catch (Exception e) {
            throw friendly(e, unc);
        }
    }

    /** Скачивает файл по сети во временный файл, затем атомарно заменяет target. */
    public void download(final Unc unc, final File target) throws Exception {
        if (unc.path.isEmpty()) throw new IOException("В пути не указан файл");
        withShare(unc, share -> {
            if (!(share instanceof DiskShare)) throw new IOException("«" + unc.share + "» не является общей папкой");
            DiskShare disk = (DiskShare) share;
            File tmp = new File(target.getParentFile(), target.getName() + ".part");
            try (com.hierynomus.smbj.share.File f = disk.openFile(unc.path,
                    EnumSet.of(AccessMask.GENERIC_READ), null,
                    SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null);
                 InputStream in = f.getInputStream();
                 OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            if (target.exists() && !target.delete()) throw new IOException("Не удалось заменить файл");
            if (!tmp.renameTo(target)) throw new IOException("Не удалось сохранить файл");
            return null;
        });
    }

    /** Список папок и файлов .xlsx в каталоге общей папки. */
    public List<Entry> list(final Unc unc) throws Exception {
        return withShare(unc, share -> {
            if (!(share instanceof DiskShare)) throw new IOException("«" + unc.share + "» не является общей папкой");
            List<Entry> out = new ArrayList<>();
            for (FileIdBothDirectoryInformation fi : ((DiskShare) share).list(unc.path)) {
                String n = fi.getFileName();
                if (n.equals(".") || n.equals("..")) continue;
                long attrs = fi.getFileAttributes();
                boolean dir = (attrs & FileAttributes.FILE_ATTRIBUTE_DIRECTORY.getValue()) != 0;
                boolean hidden = (attrs & FileAttributes.FILE_ATTRIBUTE_HIDDEN.getValue()) != 0;
                if (hidden || n.startsWith("~$")) continue;
                String ln = n.toLowerCase(Locale.ROOT);
                if (dir || ln.endsWith(".xlsx") || ln.endsWith(".xls")) {
                    out.add(new Entry(n, dir, fi.getEndOfFile()));
                }
            }
            Collections.sort(out, (a, b) -> {
                if (a.dir != b.dir) return a.dir ? -1 : 1;
                return a.name.compareToIgnoreCase(b.name);
            });
            return out;
        });
    }

    /** Отправляет «сырые» данные (команды TSPL) на общий принтер Windows. */
    public void print(final Unc printerUnc, final byte[] data) throws Exception {
        withShare(printerUnc, share -> {
            if (!(share instanceof PrinterShare)) {
                throw new IOException("«" + printerUnc.share + "» не является общим принтером. Проверьте имя общего ресурса принтера.");
            }
            ((PrinterShare) share).print(new ByteArrayInputStream(data));
            return null;
        });
    }

    /** Переводит технические ошибки SMB в понятные сообщения. */
    private static Exception friendly(Exception e, Unc unc) {
        String m = String.valueOf(e.getMessage());
        Throwable root = e;
        while (root.getCause() != null) root = root.getCause();
        String rm = String.valueOf(root.getMessage());
        String all = m + " " + rm + " " + root.getClass().getSimpleName();
        String msg;
        if (all.contains("STATUS_LOGON_FAILURE")) {
            msg = "Неверный логин или пароль для " + unc.host;
        } else if (all.contains("STATUS_ACCESS_DENIED")) {
            msg = "Доступ запрещён. Проверьте права пользователя на общую папку/принтер.";
        } else if (all.contains("STATUS_BAD_NETWORK_NAME")) {
            msg = "На компьютере " + unc.host + " нет общего ресурса «" + unc.share + "»";
        } else if (all.contains("STATUS_OBJECT_NAME_NOT_FOUND") || all.contains("STATUS_OBJECT_PATH_NOT_FOUND")) {
            msg = "Файл или папка не найдены: " + unc;
        } else if (all.contains("STATUS_SHARING_VIOLATION")) {
            msg = "Файл занят другой программой. Закройте его на компьютере и повторите.";
        } else if (all.contains("STATUS_ACCOUNT_DISABLED") || all.contains("STATUS_ACCOUNT_RESTRICTION")) {
            msg = "Учётная запись ограничена (пустой пароль или гость запрещён). Укажите пользователя Windows с паролем.";
        } else if (root instanceof java.net.UnknownHostException) {
            msg = "Компьютер «" + unc.host + "» не найден в сети. Укажите IP-адрес вместо имени.";
        } else if (root instanceof java.net.SocketTimeoutException || root instanceof java.net.ConnectException
                || root instanceof java.net.NoRouteToHostException) {
            msg = "Нет связи с " + unc.host + " (порт 445). Проверьте Wi-Fi, IP-адрес и брандмауэр Windows.";
        } else if (e instanceof IOException && !m.equals("null") && !m.startsWith("STATUS")) {
            return e;
        } else {
            msg = "Ошибка сети: " + (rm.equals("null") ? root.getClass().getSimpleName() : rm);
        }
        return new IOException(msg, e);
    }
}
