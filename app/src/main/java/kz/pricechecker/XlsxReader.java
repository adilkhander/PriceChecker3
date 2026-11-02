package kz.pricechecker;

import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

/**
 * Лёгкий читатель .xlsx без сторонних библиотек (xlsx = zip + XML).
 * Читает первый лист, сам находит строку заголовков и колонки
 * «Товар / Наименование», «Штрихкод», «Цена», «Остаток».
 * Строки без штрихкода, цены и остатка считаются папками (группами) — как в выгрузке из 1С.
 */
public class XlsxReader {

    public static class Result {
        public final List<Product> products = new ArrayList<>();
        public int folders;
        public String columnsInfo = "";
    }

    private final Map<String, ZipEntry> entries = new HashMap<>();
    private ZipFile zip;

    public static Result read(File file) throws Exception {
        XlsxReader r = new XlsxReader();
        try (ZipFile z = new ZipFile(file)) {
            r.zip = z;
            java.util.Enumeration<? extends ZipEntry> en = z.entries();
            while (en.hasMoreElements()) {
                ZipEntry e = en.nextElement();
                r.entries.put(normPath(e.getName()), e);
            }
            return r.parse();
        } catch (java.util.zip.ZipException ze) {
            throw new IOException("Файл не является .xlsx (если это старый .xls — пересохраните его в Excel как .xlsx)", ze);
        }
    }

    private static String normPath(String p) {
        p = p.replace('\\', '/');
        while (p.startsWith("/")) p = p.substring(1);
        return p.toLowerCase(Locale.ROOT);
    }

    private InputStream open(String path) throws IOException {
        ZipEntry e = entries.get(normPath(path));
        if (e == null) return null;
        return zip.getInputStream(e);
    }

    /** Разрешает путь из .rels относительно папки xl/. */
    private static String resolve(String base, String target) {
        if (target.startsWith("/")) return target.substring(1);
        String path = base + target;
        // убираем "../"
        List<String> parts = new ArrayList<>();
        for (String s : path.split("/")) {
            if (s.equals("..")) { if (!parts.isEmpty()) parts.remove(parts.size() - 1); }
            else if (!s.equals(".") && !s.isEmpty()) parts.add(s);
        }
        return String.join("/", parts);
    }

    private static SAXParser newParser() throws Exception {
        SAXParserFactory f = SAXParserFactory.newInstance();
        f.setNamespaceAware(false);
        return f.newSAXParser();
    }

    private static String local(String qName) {
        int i = qName.indexOf(':');
        return i >= 0 ? qName.substring(i + 1) : qName;
    }

    private static String attr(Attributes a, String name) {
        for (int i = 0; i < a.getLength(); i++) {
            if (local(a.getQName(i)).equals(name)) return a.getValue(i);
        }
        return null;
    }

    private void parseXml(String path, DefaultHandler h) throws Exception {
        try (InputStream in = open(path)) {
            if (in == null) return;
            newParser().parse(new InputSource(in), h);
        }
    }

    private Result parse() throws Exception {
        // 1. Связи книги: какой файл — первый лист, где общие строки
        final Map<String, String> rels = new HashMap<>();
        final String[] sharedTarget = {null};
        parseXml("xl/_rels/workbook.xml.rels", new DefaultHandler() {
            @Override public void startElement(String u, String l, String q, Attributes a) {
                if (local(q).equals("Relationship")) {
                    String id = attr(a, "Id"), target = attr(a, "Target"), type = attr(a, "Type");
                    if (id != null && target != null) rels.put(id, target);
                    if (type != null && type.endsWith("/sharedStrings") && target != null) sharedTarget[0] = target;
                }
            }
        });
        final String[] firstSheetRid = {null};
        parseXml("xl/workbook.xml", new DefaultHandler() {
            @Override public void startElement(String u, String l, String q, Attributes a) {
                if (local(q).equals("sheet") && firstSheetRid[0] == null) firstSheetRid[0] = attr(a, "id");
            }
        });

        String sheetPath = null;
        if (firstSheetRid[0] != null && rels.containsKey(firstSheetRid[0])) {
            sheetPath = resolve("xl/", rels.get(firstSheetRid[0]));
        }
        if (sheetPath == null || !entries.containsKey(normPath(sheetPath))) {
            // запасной вариант — первый попавшийся лист
            sheetPath = null;
            for (String k : new TreeMap<>(entries).keySet()) {
                if (k.startsWith("xl/worksheets/") && k.endsWith(".xml")) { sheetPath = k; break; }
            }
        }
        if (sheetPath == null) throw new IOException("В файле не найден лист с данными");

        String sstPath = sharedTarget[0] != null ? resolve("xl/", sharedTarget[0]) : "xl/sharedStrings.xml";

        // 2. Общие строки
        final List<String> shared = new ArrayList<>();
        parseXml(sstPath, new DefaultHandler() {
            final StringBuilder sb = new StringBuilder();
            boolean inSi, inT, inRph;
            @Override public void startElement(String u, String l, String q, Attributes a) {
                String n = local(q);
                if (n.equals("si")) { inSi = true; sb.setLength(0); }
                else if (n.equals("rPh")) inRph = true;
                else if (n.equals("t") && inSi && !inRph) inT = true;
            }
            @Override public void endElement(String u, String l, String q) {
                String n = local(q);
                if (n.equals("si")) { shared.add(sb.toString()); inSi = false; }
                else if (n.equals("rPh")) inRph = false;
                else if (n.equals("t")) inT = false;
            }
            @Override public void characters(char[] ch, int s, int len) {
                if (inT) sb.append(ch, s, len);
            }
        });

        // 3. Строки листа
        final List<Map<Integer, String>> rows = new ArrayList<>();
        parseXml(sheetPath, new DefaultHandler() {
            Map<Integer, String> row;
            int col; String type;
            final StringBuilder val = new StringBuilder();
            boolean inV, inIsT;
            int nextCol;
            @Override public void startElement(String u, String l, String q, Attributes a) {
                switch (local(q)) {
                    case "row": row = new HashMap<>(); nextCol = 0; break;
                    case "c":
                        String ref = attr(a, "r");
                        col = ref != null ? colIndex(ref) : nextCol;
                        nextCol = col + 1;
                        type = attr(a, "t");
                        val.setLength(0);
                        break;
                    case "v": inV = true; break;
                    case "t": inIsT = true; break; // inlineStr: <is><t>
                }
            }
            @Override public void endElement(String u, String l, String q) {
                switch (local(q)) {
                    case "v": inV = false; break;
                    case "t": inIsT = false; break;
                    case "c":
                        if (row != null) {
                            String v = val.toString();
                            if ("s".equals(type)) {
                                try {
                                    int idx = Integer.parseInt(v.trim());
                                    v = idx >= 0 && idx < shared.size() ? shared.get(idx) : "";
                                } catch (NumberFormatException e) { v = ""; }
                            } else if ("e".equals(type)) {
                                v = "";
                            } else if ("b".equals(type)) {
                                v = "1".equals(v.trim()) ? "ИСТИНА" : "ЛОЖЬ";
                            }
                            if (!v.isEmpty()) row.put(col, v);
                        }
                        break;
                    case "row":
                        if (row != null) rows.add(row);
                        row = null;
                        break;
                }
            }
            @Override public void characters(char[] ch, int s, int len) {
                if (inV || inIsT) val.append(ch, s, len);
            }
        });

        return buildProducts(rows);
    }

    static int colIndex(String ref) {
        int n = 0;
        for (int i = 0; i < ref.length(); i++) {
            char c = ref.charAt(i);
            if (c >= 'A' && c <= 'Z') n = n * 26 + (c - 'A' + 1);
            else if (c >= 'a' && c <= 'z') n = n * 26 + (c - 'a' + 1);
            else break;
        }
        return n - 1;
    }

    private static boolean has(String s, String... keys) {
        String l = s.toLowerCase(Locale.ROOT);
        for (String k : keys) if (l.contains(k)) return true;
        return false;
    }

    private static Result buildProducts(List<Map<Integer, String>> rows) {
        Result res = new Result();
        int headerRow = -1, cName = 0, cBar = 1, cPrice = 2, cStock = 3;

        // Поиск строки заголовков в первых 30 строках
        for (int r = 0; r < Math.min(rows.size(), 30) && headerRow < 0; r++) {
            int n = -1, b = -1, p = -1, s = -1;
            for (Map.Entry<Integer, String> e : rows.get(r).entrySet()) {
                String v = e.getValue().trim();
                if (v.length() > 60) continue;
                if (b < 0 && has(v, "штрих", "barcode", "ean")) b = e.getKey();
                else if (p < 0 && has(v, "цена", "price", "стоимость", "баға")) p = e.getKey();
                else if (s < 0 && has(v, "остат", "кол-во", "количество", "stock", "қалдық")) s = e.getKey();
                else if (n < 0 && has(v, "товар", "наимен", "номенклат", "название", "папка", "name", "атауы")) n = e.getKey();
            }
            if (n >= 0 && (b >= 0 || p >= 0)) {
                headerRow = r; cName = n; cBar = b; cPrice = p; cStock = s;
            }
        }
        res.columnsInfo = headerRow >= 0
                ? "Заголовки в строке " + (headerRow + 1)
                : "Заголовки не найдены, используются колонки A-D";

        String folder = "";
        for (int r = headerRow + 1; r < rows.size(); r++) {
            Map<Integer, String> row = rows.get(r);
            String name = get(row, cName).trim();
            if (name.isEmpty()) continue;
            String bar = get(row, cBar);
            String priceS = get(row, cPrice);
            String stockS = get(row, cStock);
            if (bar.trim().isEmpty() && priceS.trim().isEmpty() && stockS.trim().isEmpty()) {
                folder = name;           // строка-папка
                res.folders++;
                continue;
            }
            res.products.add(new Product(name, folder, splitBarcodes(bar), toNumber(priceS), toNumber(stockS)));
        }
        return res;
    }

    private static String get(Map<Integer, String> row, int col) {
        if (col < 0) return "";
        String v = row.get(col);
        return v == null ? "" : v;
    }

    /** Числа в xlsx могут быть записаны как "3083680579599", "4.6070290551940E12" и т.п. */
    static BigDecimal toNumber(String s) {
        if (s == null) return null;
        String t = s.trim().replace(' ', ' ').replace(" ", "").replace(',', '.');
        if (t.isEmpty()) return null;
        try {
            return new BigDecimal(t);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    static List<String> splitBarcodes(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) return out;
        for (String part : raw.split("[,;/\\s]+")) {
            String p = part.trim();
            if (p.isEmpty()) continue;
            // число в научной записи -> целое
            if (p.matches("[0-9.]+[eE][+-]?[0-9]+") || p.matches("[0-9]+\\.0+")) {
                try { p = new BigDecimal(p).toBigInteger().toString(); } catch (Exception ignored) { }
            }
            if (!out.contains(p)) out.add(p);
        }
        return out;
    }
}
