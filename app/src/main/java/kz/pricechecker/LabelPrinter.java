package kz.pricechecker;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.pdf.PdfDocument;
import android.os.Bundle;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.print.PageRange;
import android.print.PrintAttributes;
import android.print.PrintDocumentAdapter;
import android.print.PrintDocumentInfo;
import android.print.PrintManager;

import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;

/** Отправка этикеток на принтер выбранным способом. */
public class LabelPrinter {

    /** Отправка «сырых» TSPL-команд (SMB-принтер Windows или TCP 9100). Вызывать в фоне. */
    public static void printRaw(Settings s, Bitmap label, int copies) throws Exception {
        byte[] job = Tspl.build(label, s, copies);
        String mode = s.printMode();
        if (Settings.PRINT_TCP.equals(mode)) {
            String ip = s.printerIp().trim();
            if (ip.isEmpty()) throw new IOException("Не указан IP-адрес принтера (Настройки)");
            try (Socket sock = new Socket()) {
                sock.connect(new InetSocketAddress(ip, s.printerPort()), 7000);
                sock.setSoTimeout(15000);
                OutputStream os = sock.getOutputStream();
                os.write(job);
                os.flush();
            } catch (IOException e) {
                throw new IOException("Нет связи с принтером " + ip + ":" + s.printerPort() + " — " + e.getMessage(), e);
            }
        } else {
            String unc = s.printerUnc().trim();
            if (unc.isEmpty()) throw new IOException("Не указан сетевой путь к принтеру (Настройки), например \\\\192.168.1.10\\XP-365B");
            new SmbHelper(s).print(SmbHelper.Unc.parse(unc), job);
        }
    }

    /** Печать через системный диалог Android (нужна служба печати, поддерживающая ваш принтер). */
    public static void printSystem(Activity activity, Settings s, final Bitmap label, final int copies) {
        PrintManager pm = (PrintManager) activity.getSystemService(Context.PRINT_SERVICE);
        final int wMils = Math.round(s.labelWidthMm() / 25.4f * 1000f);
        final int hMils = Math.round(s.labelHeightMm() / 25.4f * 1000f);
        PrintAttributes.MediaSize media = new PrintAttributes.MediaSize(
                "label_" + wMils + "x" + hMils, "Этикетка " + Math.round(s.labelWidthMm()) + "×" + Math.round(s.labelHeightMm()) + " мм",
                wMils, hMils);
        PrintAttributes attrs = new PrintAttributes.Builder()
                .setMediaSize(media)
                .setColorMode(PrintAttributes.COLOR_MODE_MONOCHROME)
                .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
                .build();

        pm.print("Ценник", new PrintDocumentAdapter() {
            @Override
            public void onLayout(PrintAttributes oldA, PrintAttributes newA, CancellationSignal cancel,
                                 LayoutResultCallback cb, Bundle extras) {
                if (cancel.isCanceled()) { cb.onLayoutCancelled(); return; }
                cb.onLayoutFinished(new PrintDocumentInfo.Builder("label.pdf")
                        .setContentType(PrintDocumentInfo.CONTENT_TYPE_DOCUMENT)
                        .setPageCount(copies).build(), true);
            }

            @Override
            public void onWrite(PageRange[] pages, ParcelFileDescriptor dest, CancellationSignal cancel,
                                WriteResultCallback cb) {
                PdfDocument doc = new PdfDocument();
                try {
                    // размеры PDF в пунктах (1/72 дюйма)
                    int pw = Math.round(wMils * 72f / 1000f), ph = Math.round(hMils * 72f / 1000f);
                    Paint paint = new Paint();
                    paint.setFilterBitmap(false);
                    for (int i = 0; i < copies; i++) {
                        PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder(pw, ph, i + 1).create());
                        Canvas c = page.getCanvas();
                        c.drawBitmap(label, null, new Rect(0, 0, pw, ph), paint);
                        doc.finishPage(page);
                    }
                    try (FileOutputStream fos = new FileOutputStream(dest.getFileDescriptor())) {
                        doc.writeTo(fos);
                    }
                    cb.onWriteFinished(new PageRange[]{PageRange.ALL_PAGES});
                } catch (Exception e) {
                    cb.onWriteFailed(e.getMessage());
                } finally {
                    doc.close();
                }
            }
        }, attrs);
    }
}
