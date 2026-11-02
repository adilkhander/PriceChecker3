package kz.pricechecker;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.Toast;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Просмотр чека: картинка, отправка в WhatsApp, оплата позже, аннулирование. */
@SuppressWarnings("deprecation") // onActivityResult
public class ReceiptActivity extends Activity {
    public static final String EXTRA_ID = "id";
    public static final String EXTRA_NEW = "new";
    private static final int REQ_PAY = 1;
    private static final int REQ_STORAGE = 2;
    private static final int MENU_CANCEL = 1, MENU_GALLERY = 2;

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Receipt receipt;
    private File imageFile;
    private boolean pendingGallery = false;
    private String pendingGallerySuffix = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_receipt);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);

        findViewById(R.id.btnWhatsApp).setOnClickListener(v -> {
            if (imageFile != null) {
                try {
                    ShareFiles.sendToWhatsApp(this, imageFile, ReceiptRenderer.caption(receipt));
                } catch (Exception e) {
                    Toast.makeText(this, "Не удалось открыть WhatsApp: " + e.getMessage(), Toast.LENGTH_LONG).show();
                }
            }
        });
        findViewById(R.id.btnShare).setOnClickListener(v -> {
            if (imageFile != null) ShareFiles.share(this, imageFile, ReceiptRenderer.caption(receipt), "Отправить чек");
        });
        findViewById(R.id.btnPayLater).setOnClickListener(v -> payLater());
        findViewById(R.id.btnNewSale).setOnClickListener(v -> finish());

        boolean isNew = getIntent().getBooleanExtra(EXTRA_NEW, false);
        findViewById(R.id.btnNewSale).setVisibility(isNew ? View.VISIBLE : View.GONE);
        // в галерею — только при первом открытии нового чека (не при повороте экрана)
        load(isNew && savedInstanceState == null, "");
    }

    /** Загрузка чека из базы, рисование картинки; при необходимости — копия в галерею. */
    private void load(final boolean toGallery, final String gallerySuffix) {
        final long id = getIntent().getLongExtra(EXTRA_ID, -1);
        findViewById(R.id.rcProgress).setVisibility(View.VISIBLE);
        bg.execute(() -> {
            try {
                final Receipt r = SalesDb.get(this).load(id);
                if (r == null) throw new Exception("Чек не найден");
                Bitmap bmp = new ReceiptRenderer(this).render(r);
                final File f = ShareFiles.savePng(this, bmp, "check_" + r.number + ".png");
                ui.post(() -> {
                    findViewById(R.id.rcProgress).setVisibility(View.INVISIBLE);
                    receipt = r;
                    imageFile = f;
                    setTitle("Чек № " + r.number);
                    ((ImageView) findViewById(R.id.ivReceipt)).setImageBitmap(bmp);
                    findViewById(R.id.btnPayLater).setVisibility(r.status == Receipt.UNPAID ? View.VISIBLE : View.GONE);
                    invalidateOptionsMenu();
                    if (toGallery) saveToGallery(gallerySuffix);
                });
            } catch (final Exception e) {
                ui.post(() -> {
                    findViewById(R.id.rcProgress).setVisibility(View.INVISIBLE);
                    Toast.makeText(this, "Ошибка: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    // ---------- галерея ----------

    private void saveToGallery(String suffix) {
        if (imageFile == null) return;
        if (ShareFiles.needsStoragePermission(this)) {
            pendingGallery = true;
            pendingGallerySuffix = suffix;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        final File src = imageFile;
        final String name = "Чек_" + receipt.number + suffix + ".png";
        bg.execute(() -> {
            String msg;
            try {
                ShareFiles.copyToGallery(this, src, name);
                msg = "Чек сохранён в галерею (альбом «Чеки»)";
            } catch (Exception e) {
                msg = "Не удалось сохранить в галерею: " + e.getMessage();
            }
            final String m = msg;
            ui.post(() -> Toast.makeText(this, m, Toast.LENGTH_SHORT).show());
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_STORAGE && pendingGallery) {
            pendingGallery = false;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                saveToGallery(pendingGallerySuffix);
            } else {
                Toast.makeText(this, "Без разрешения чек сохранён только в приложении", Toast.LENGTH_LONG).show();
            }
        }
    }

    // ---------- оплата позже / аннулирование ----------

    private void payLater() {
        if (receipt == null || receipt.status != Receipt.UNPAID) return;
        Intent i = new Intent(this, PaymentActivity.class);
        i.putExtra(PaymentActivity.EXTRA_TOTAL, Money.store(receipt.total()));
        i.putExtra(PaymentActivity.EXTRA_TITLE, "Оплата чека № " + receipt.number);
        startActivityForResult(i, REQ_PAY);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PAY || resultCode != RESULT_OK || data == null || receipt == null) return;
        final Receipt r = receipt;
        Receipt.Payment p = PaymentActivity.readResult(data);
        if (p.validate(r.total()) != null) return;
        p.applyTo(r);
        r.status = Receipt.PAID;
        r.paidAt = System.currentTimeMillis();
        bg.execute(() -> {
            SalesDb.get(this).updatePayment(r);
            ui.post(() -> {
                Toast.makeText(this, "Оплата принята", Toast.LENGTH_SHORT).show();
                load(true, "_оплачен");
            });
        });
    }

    private void cancelReceipt() {
        if (receipt == null || receipt.status == Receipt.CANCELLED) return;
        new AlertDialog.Builder(this)
                .setTitle("Аннулировать чек № " + receipt.number + "?")
                .setMessage("Чек останется в журнале с пометкой «АННУЛИРОВАН» и не войдёт в итоги продаж.")
                .setPositiveButton("Аннулировать", (d, w) -> {
                    final Receipt r = receipt;
                    r.status = Receipt.CANCELLED;
                    r.cancelledAt = System.currentTimeMillis();
                    bg.execute(() -> {
                        SalesDb.get(this).updatePayment(r);
                        ui.post(() -> load(true, "_аннулирован"));
                    });
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_GALLERY, 0, "Сохранить в галерею");
        if (receipt == null || receipt.status != Receipt.CANCELLED) menu.add(0, MENU_CANCEL, 1, "Аннулировать чек");
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_CANCEL) { cancelReceipt(); return true; }
        if (item.getItemId() == MENU_GALLERY) { saveToGallery(""); return true; }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public boolean onNavigateUp() {
        finish();
        return true;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bg.shutdown();
    }
}
