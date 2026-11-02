package kz.pricechecker;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.TextView;
import android.widget.Toast;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressWarnings("deprecation") // ProgressDialog — простой и достаточный здесь
public class SettingsActivity extends Activity {
    private static final int REQ_BROWSE = 10;
    private static final int REQ_LOCAL_FILE = 11;

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Settings s;

    private EditText etPriceUnc, etUser, etPassword, etDomain, etPrinterUnc, etPrinterIp, etPrinterPort;
    private EditText etW, etH, etGap, etDpi, etDensity, etSpeed, etOffX, etOffY;
    private EditText etKassa, etCashier, etShopName, etShopBin, etShopAddress, etShopPhone, etFooter;
    private EditText etSheetUrl, etSheetSecret;
    private CheckBox cbAuto, cbRotate, cbInvert;
    private RadioGroup rgMode;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        s = new Settings(this);

        etPriceUnc = findViewById(R.id.etPriceUnc);
        etUser = findViewById(R.id.etUser);
        etPassword = findViewById(R.id.etPassword);
        etDomain = findViewById(R.id.etDomain);
        etPrinterUnc = findViewById(R.id.etPrinterUnc);
        etPrinterIp = findViewById(R.id.etPrinterIp);
        etPrinterPort = findViewById(R.id.etPrinterPort);
        etW = findViewById(R.id.etW);
        etH = findViewById(R.id.etH);
        etGap = findViewById(R.id.etGap);
        etDpi = findViewById(R.id.etDpi);
        etDensity = findViewById(R.id.etDensity);
        etSpeed = findViewById(R.id.etSpeed);
        etOffX = findViewById(R.id.etOffX);
        etOffY = findViewById(R.id.etOffY);
        cbAuto = findViewById(R.id.cbAuto);
        cbRotate = findViewById(R.id.cbRotate);
        cbInvert = findViewById(R.id.cbInvert);
        rgMode = findViewById(R.id.rgMode);
        etKassa = findViewById(R.id.etKassa);
        etCashier = findViewById(R.id.etCashier);
        etShopName = findViewById(R.id.etShopName);
        etShopBin = findViewById(R.id.etShopBin);
        etShopAddress = findViewById(R.id.etShopAddress);
        etShopPhone = findViewById(R.id.etShopPhone);
        etFooter = findViewById(R.id.etFooter);
        etSheetUrl = findViewById(R.id.etSheetUrl);
        etSheetSecret = findViewById(R.id.etSheetSecret);

        fill();

        rgMode.setOnCheckedChangeListener((g, id) -> updateModeVisibility());
        updateModeVisibility();

        findViewById(R.id.btnBrowse).setOnClickListener(v -> browse());
        findViewById(R.id.btnLoadNow).setOnClickListener(v -> loadNow());
        findViewById(R.id.btnLocalFile).setOnClickListener(v -> pickLocalFile());
        findViewById(R.id.btnTest).setOnClickListener(v -> testPrint());
        findViewById(R.id.btnSheetTest).setOnClickListener(v -> testSheet());

        String version = "";
        try { version = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; } catch (Exception ignored) { }
        ((TextView) findViewById(R.id.tvAbout)).setText("Прайс-чекер " + version
                + "\nТоваров в памяти: " + PriceRepository.get().size());
    }

    private void fill() {
        etPriceUnc.setText(s.priceUnc());
        etUser.setText(s.smbUser());
        etPassword.setText(s.smbPassword());
        etDomain.setText(s.smbDomain());
        cbAuto.setChecked(s.autoUpdate());
        String mode = s.printMode();
        rgMode.check(Settings.PRINT_TCP.equals(mode) ? R.id.rbTcp
                : Settings.PRINT_SYSTEM.equals(mode) ? R.id.rbSystem : R.id.rbSmb);
        etPrinterUnc.setText(s.printerUnc());
        etPrinterIp.setText(s.printerIp());
        etPrinterPort.setText(String.valueOf(s.printerPort()));
        etW.setText(fmt(s.labelWidthMm()));
        etH.setText(fmt(s.labelHeightMm()));
        etGap.setText(fmt(s.labelGapMm()));
        etDpi.setText(String.valueOf(s.dpi()));
        etDensity.setText(String.valueOf(s.density()));
        etSpeed.setText(String.valueOf(s.speed()));
        etOffX.setText(String.valueOf(s.offsetXDots()));
        etOffY.setText(String.valueOf(s.offsetYDots()));
        cbRotate.setChecked(s.direction() == 0);
        cbInvert.setChecked(s.invertBitmap());
        etKassa.setText(String.valueOf(s.kassaNumber()));
        etCashier.setText(s.cashier());
        etShopName.setText(s.shopName());
        etShopBin.setText(s.shopBin());
        etShopAddress.setText(s.shopAddress());
        etShopPhone.setText(s.shopPhone());
        etFooter.setText(s.receiptFooter());
        etSheetUrl.setText(s.sheetUrl());
        etSheetSecret.setText(s.sheetSecret());
    }

    /** Проверка адреса скрипта и секретного ключа. */
    private void testSheet() {
        save();
        if (!SheetSync.configured(s)) {
            Toast.makeText(this, "Введите адрес веб-приложения Google", Toast.LENGTH_LONG).show();
            return;
        }
        Toast.makeText(this, "Проверка соединения…", Toast.LENGTH_SHORT).show();
        final String who = s.cashier().trim();
        bg.execute(() -> {
            final SheetSync.Result r = SheetSync.ping(s, who);
            ui.post(() -> new AlertDialog.Builder(this)
                    .setTitle(r.ok ? "Соединение установлено" : "Нет соединения")
                    .setMessage(r.message)
                    .setPositiveButton("OK", null)
                    .show());
        });
    }

    private void save() {
        SharedPreferences.Editor e = s.edit();
        e.putString("price_unc", etPriceUnc.getText().toString().trim());
        e.putString("smb_user", etUser.getText().toString().trim());
        e.putString("smb_password", etPassword.getText().toString());
        e.putString("smb_domain", etDomain.getText().toString().trim());
        e.putBoolean("auto_update", cbAuto.isChecked());
        int id = rgMode.getCheckedRadioButtonId();
        e.putString("print_mode", id == R.id.rbTcp ? Settings.PRINT_TCP
                : id == R.id.rbSystem ? Settings.PRINT_SYSTEM : Settings.PRINT_SMB);
        e.putString("printer_unc", etPrinterUnc.getText().toString().trim());
        e.putString("printer_ip", etPrinterIp.getText().toString().trim());
        e.putInt("printer_port", intOf(etPrinterPort, 9100, 1, 65535));
        e.putFloat("label_w", floatOf(etW, 58f, 10f, 120f));
        e.putFloat("label_h", floatOf(etH, 40f, 10f, 300f));
        e.putFloat("label_gap", floatOf(etGap, 2f, 0f, 20f));
        e.putInt("dpi", intOf(etDpi, 203, 100, 600));
        e.putInt("density", intOf(etDensity, 8, 0, 15));
        e.putInt("speed", intOf(etSpeed, 4, 1, 12));
        e.putInt("offset_x", intOf(etOffX, 0, 0, 1000));
        e.putInt("offset_y", intOf(etOffY, 0, 0, 1000));
        e.putInt("direction", cbRotate.isChecked() ? 0 : 1);
        e.putBoolean("invert", cbInvert.isChecked());
        e.putInt("kassa", intOf(etKassa, 1, 1, 999));
        e.putString("cashier", etCashier.getText().toString().trim());
        String shop = etShopName.getText().toString().trim();
        e.putString("shop_name", shop.isEmpty() ? "Магазин" : shop);
        e.putString("shop_bin", etShopBin.getText().toString().trim());
        e.putString("shop_address", etShopAddress.getText().toString().trim());
        e.putString("shop_phone", etShopPhone.getText().toString().trim());
        e.putString("receipt_footer", etFooter.getText().toString().trim());
        e.putString("sheet_url", etSheetUrl.getText().toString().trim());
        e.putString("sheet_secret", etSheetSecret.getText().toString().trim());
        e.apply();
    }

    private void updateModeVisibility() {
        int id = rgMode.getCheckedRadioButtonId();
        findViewById(R.id.groupSmb).setVisibility(id == R.id.rbSmb ? View.VISIBLE : View.GONE);
        findViewById(R.id.groupTcp).setVisibility(id == R.id.rbTcp ? View.VISIBLE : View.GONE);
    }

    // ---------- действия ----------

    private void browse() {
        save();
        String start = etPriceUnc.getText().toString().trim();
        Intent i = new Intent(this, SmbBrowserActivity.class);
        i.putExtra(SmbBrowserActivity.EXTRA_START, start);
        startActivityForResult(i, REQ_BROWSE);
    }

    private void loadNow() {
        save();
        final ProgressDialog pd = ProgressDialog.show(this, null, "Загрузка прайса…", true, false);
        bg.execute(() -> {
            try {
                final String status = PriceLoader.loadFromNetwork(this);
                ui.post(() -> { pd.dismiss(); done(status); });
            } catch (final Exception e) {
                ui.post(() -> { pd.dismiss(); error("Не удалось загрузить прайс", e); });
            }
        });
    }

    private void pickLocalFile() {
        save();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{
                "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                "application/vnd.ms-excel", "application/octet-stream"});
        try {
            startActivityForResult(i, REQ_LOCAL_FILE);
        } catch (Exception e) {
            Toast.makeText(this, "Нет приложения для выбора файлов", Toast.LENGTH_LONG).show();
        }
    }

    private void testPrint() {
        save();
        final Bitmap label = new LabelRenderer(this, s).render(
                "Тестовая этикетка Прайс-чекер. Проверка печати кириллицы и знака тенге",
                "12 345 ₸", "4607029055194", LabelRenderer.today());
        if (Settings.PRINT_SYSTEM.equals(s.printMode())) {
            LabelPrinter.printSystem(this, s, label, 1);
            return;
        }
        final ProgressDialog pd = ProgressDialog.show(this, null, "Отправка на принтер…", true, false);
        bg.execute(() -> {
            try {
                LabelPrinter.printRaw(s, label, 1);
                ui.post(() -> { pd.dismiss(); Toast.makeText(this, "Тестовая этикетка отправлена", Toast.LENGTH_SHORT).show(); });
            } catch (final Exception e) {
                ui.post(() -> { pd.dismiss(); error("Ошибка печати", e); });
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_BROWSE) {
            String path = data.getStringExtra(SmbBrowserActivity.EXTRA_RESULT);
            if (path != null) {
                etPriceUnc.setText(path);
                save();
                new AlertDialog.Builder(this)
                        .setMessage("Файл выбран:\n" + path + "\n\nЗагрузить его сейчас?")
                        .setPositiveButton("Загрузить", (d, w) -> loadNow())
                        .setNegativeButton("Позже", null)
                        .show();
            }
        } else if (requestCode == REQ_LOCAL_FILE) {
            final Uri uri = data.getData();
            if (uri == null) return;
            final ProgressDialog pd = ProgressDialog.show(this, null, "Чтение файла…", true, false);
            bg.execute(() -> {
                try {
                    final String status = PriceLoader.loadFromUri(this, uri);
                    ui.post(() -> { pd.dismiss(); done(status); });
                } catch (final Exception e) {
                    ui.post(() -> { pd.dismiss(); error("Не удалось прочитать файл", e); });
                }
            });
        }
    }

    private void done(String status) {
        ((TextView) findViewById(R.id.tvAbout)).setText("Товаров в памяти: " + PriceRepository.get().size());
        new AlertDialog.Builder(this).setTitle("Прайс загружен").setMessage(status)
                .setPositiveButton("OK", null).show();
    }

    private void error(String title, Exception e) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this).setTitle(title)
                .setMessage(e.getMessage() == null ? e.toString() : e.getMessage())
                .setPositiveButton("OK", null).show();
    }

    @Override
    protected void onPause() {
        super.onPause();
        save();
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

    // ---------- утилиты ----------

    private static String fmt(float v) {
        return v == Math.round(v) ? String.valueOf(Math.round(v)) : String.valueOf(v);
    }

    private static int intOf(EditText e, int def, int min, int max) {
        try {
            int v = Integer.parseInt(e.getText().toString().trim());
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException ex) {
            return def;
        }
    }

    private static float floatOf(EditText e, float def, float min, float max) {
        try {
            float v = Float.parseFloat(e.getText().toString().trim().replace(',', '.'));
            return Math.max(min, Math.min(max, v));
        } catch (NumberFormatException ex) {
            return def;
        }
    }
}
