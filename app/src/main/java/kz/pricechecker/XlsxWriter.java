package kz.pricechecker;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Минимальный генератор .xlsx (без сторонних библиотек). Открывается в Excel, 1С, Google Таблицах. */
public class XlsxWriter {

    public static class Sheet {
        final String name;
        final List<Object[]> rows = new ArrayList<>();
        int[] widths;
        int headerRows = 1;

        Sheet(String name) { this.name = name; }

        public Sheet row(Object... cells) { rows.add(cells); return this; }
        public Sheet widths(int... w) { widths = w; return this; }
        public Sheet headerRows(int n) { headerRows = n; return this; }
    }

    /** Пометка «жирная ячейка». */
    public static class Bold {
        final Object value;
        public Bold(Object value) { this.value = value; }
    }

    private final List<Sheet> sheets = new ArrayList<>();

    public Sheet sheet(String name) {
        Sheet s = new Sheet(name);
        sheets.add(s);
        return s;
    }

    public void write(File f) throws IOException {
        try (OutputStream fos = new FileOutputStream(f); ZipOutputStream z = new ZipOutputStream(fos)) {
            put(z, "[Content_Types].xml", contentTypes());
            put(z, "_rels/.rels", "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                    + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">"
                    + "<Relationship Id=\"rId1\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument\" Target=\"xl/workbook.xml\"/>"
                    + "</Relationships>");
            put(z, "xl/workbook.xml", workbook());
            put(z, "xl/_rels/workbook.xml.rels", workbookRels());
            put(z, "xl/styles.xml", styles());
            for (int i = 0; i < sheets.size(); i++) {
                put(z, "xl/worksheets/sheet" + (i + 1) + ".xml", sheetXml(sheets.get(i)));
            }
        }
    }

    private static void put(ZipOutputStream z, String name, String content) throws IOException {
        z.putNextEntry(new ZipEntry(name));
        z.write(content.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    private String contentTypes() {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">"
                + "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>"
                + "<Default Extension=\"xml\" ContentType=\"application/xml\"/>"
                + "<Override PartName=\"/xl/workbook.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>"
                + "<Override PartName=\"/xl/styles.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml\"/>");
        for (int i = 0; i < sheets.size(); i++) {
            sb.append("<Override PartName=\"/xl/worksheets/sheet").append(i + 1)
              .append(".xml\" ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>");
        }
        return sb.append("</Types>").toString();
    }

    private String workbook() {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<workbook xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" "
                + "xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\"><sheets>");
        for (int i = 0; i < sheets.size(); i++) {
            sb.append("<sheet name=\"").append(esc(safeSheetName(sheets.get(i).name)))
              .append("\" sheetId=\"").append(i + 1).append("\" r:id=\"rId").append(i + 1).append("\"/>");
        }
        return sb.append("</sheets></workbook>").toString();
    }

    private String workbookRels() {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<Relationships xmlns=\"http://schemas.openxmlformats.org/package/2006/relationships\">");
        for (int i = 0; i < sheets.size(); i++) {
            sb.append("<Relationship Id=\"rId").append(i + 1)
              .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet\" Target=\"worksheets/sheet")
              .append(i + 1).append(".xml\"/>");
        }
        int n = sheets.size() + 1;
        sb.append("<Relationship Id=\"rId").append(n)
          .append("\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles\" Target=\"styles.xml\"/>");
        return sb.append("</Relationships>").toString();
    }

    /** Стили: 0 — обычный, 1 — жирный заголовок с заливкой, 2 — число 0.00, 3 — жирное число, 4 — жирный текст. */
    private static String styles() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<styleSheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">"
                + "<numFmts count=\"1\"><numFmt numFmtId=\"164\" formatCode=\"#,##0.##\"/></numFmts>"
                + "<fonts count=\"2\"><font><sz val=\"11\"/><name val=\"Calibri\"/></font>"
                + "<font><b/><sz val=\"11\"/><name val=\"Calibri\"/></font></fonts>"
                + "<fills count=\"3\"><fill><patternFill patternType=\"none\"/></fill><fill><patternFill patternType=\"gray125\"/></fill>"
                + "<fill><patternFill patternType=\"solid\"><fgColor rgb=\"FFDCE3EC\"/><bgColor indexed=\"64\"/></patternFill></fill></fills>"
                + "<borders count=\"1\"><border><left/><right/><top/><bottom/><diagonal/></border></borders>"
                + "<cellStyleXfs count=\"1\"><xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\"/></cellStyleXfs>"
                + "<cellXfs count=\"5\">"
                + "<xf numFmtId=\"0\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\"/>"
                + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"2\" borderId=\"0\" xfId=\"0\" applyFont=\"1\" applyFill=\"1\"><alignment wrapText=\"1\" vertical=\"center\"/></xf>"
                + "<xf numFmtId=\"164\" fontId=\"0\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\"/>"
                + "<xf numFmtId=\"164\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyNumberFormat=\"1\" applyFont=\"1\"/>"
                + "<xf numFmtId=\"0\" fontId=\"1\" fillId=\"0\" borderId=\"0\" xfId=\"0\" applyFont=\"1\"/>"
                + "</cellXfs>"
                + "<cellStyles count=\"1\"><cellStyle name=\"Normal\" xfId=\"0\" builtinId=\"0\"/></cellStyles>"
                + "</styleSheet>";
    }

    private static String sheetXml(Sheet s) {
        StringBuilder sb = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
                + "<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\">");
        if (s.headerRows > 0) {
            sb.append("<sheetViews><sheetView workbookViewId=\"0\"><pane ySplit=\"").append(s.headerRows)
              .append("\" topLeftCell=\"A").append(s.headerRows + 1)
              .append("\" activePane=\"bottomLeft\" state=\"frozen\"/></sheetView></sheetViews>");
        }
        if (s.widths != null && s.widths.length > 0) {
            sb.append("<cols>");
            for (int i = 0; i < s.widths.length; i++) {
                sb.append("<col min=\"").append(i + 1).append("\" max=\"").append(i + 1)
                  .append("\" width=\"").append(s.widths[i]).append("\" customWidth=\"1\"/>");
            }
            sb.append("</cols>");
        }
        sb.append("<sheetData>");
        for (int r = 0; r < s.rows.size(); r++) {
            Object[] row = s.rows.get(r);
            boolean header = r < s.headerRows;
            sb.append("<row r=\"").append(r + 1).append("\">");
            for (int ci = 0; ci < row.length; ci++) {
                Object v = row[ci];
                boolean bold = false;
                if (v instanceof Bold) { bold = true; v = ((Bold) v).value; }
                if (v == null) continue;
                String ref = colName(ci) + (r + 1);
                if (v instanceof Number) {
                    String num = v instanceof BigDecimal ? ((BigDecimal) v).stripTrailingZeros().toPlainString() : v.toString();
                    int style = bold ? 3 : 2;
                    sb.append("<c r=\"").append(ref).append("\" s=\"").append(style).append("\"><v>").append(num).append("</v></c>");
                } else {
                    int style = header ? 1 : bold ? 4 : 0;
                    sb.append("<c r=\"").append(ref).append("\" t=\"inlineStr\"");
                    if (style != 0) sb.append(" s=\"").append(style).append("\"");
                    sb.append("><is><t xml:space=\"preserve\">").append(esc(v.toString())).append("</t></is></c>");
                }
            }
            sb.append("</row>");
        }
        sb.append("</sheetData></worksheet>");
        return sb.toString();
    }

    static String colName(int i) {
        StringBuilder sb = new StringBuilder();
        i++;
        while (i > 0) {
            int m = (i - 1) % 26;
            sb.insert(0, (char) ('A' + m));
            i = (i - 1) / 26;
        }
        return sb.toString();
    }

    private static String safeSheetName(String n) {
        String s = n.replaceAll("[\\\\/?*\\[\\]:]", " ");
        return s.length() > 31 ? s.substring(0, 31) : s;
    }

    private static String esc(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '&': sb.append("&amp;"); break;
                case '<': sb.append("&lt;"); break;
                case '>': sb.append("&gt;"); break;
                case '"': sb.append("&quot;"); break;
                default:
                    if (ch < 0x20 && ch != '\t' && ch != '\n' && ch != '\r') sb.append(' ');
                    else sb.append(ch);
            }
        }
        return sb.toString();
    }
}
