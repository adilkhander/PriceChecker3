package kz.pricechecker;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Стартовый экран: выбор режима работы. */
public class LauncherActivity extends Activity {
    // несколько потоков: загрузка прайса по сети не должна задерживать подсчёт чеков
    private final ExecutorService bg = Executors.newCachedThreadPool();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Settings settings;
    private TextView tvPriceStatus, tvUnpaid, tvKassa;
    private boolean loading = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_launcher);
        settings = new Settings(this);
        tvPriceStatus = findViewById(R.id.tvPriceStatus);
        tvUnpaid = findViewById(R.id.tvUnpaid);
        tvKassa = findViewById(R.id.tvKassa);

        findViewById(R.id.cardTsd).setOnClickListener(v -> startActivity(new Intent(this, MainActivity.class)));
        findViewById(R.id.cardSales).setOnClickListener(v -> startActivity(new Intent(this, SalesActivity.class)));
        findViewById(R.id.cardInventory).setOnClickListener(v -> startActivity(new Intent(this, InventoryActivity.class)));
        findViewById(R.id.btnReceipts).setOnClickListener(v -> startActivity(new Intent(this, ReceiptsActivity.class)));
        findViewById(R.id.btnSettings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        tvUnpaid.setOnClickListener(v -> startActivity(new Intent(this, ReceiptsActivity.class)
                .putExtra(ReceiptsActivity.EXTRA_UNPAID_ONLY, true)));

        loadPrice();
    }

    private void loadPrice() {
        final PriceRepository repo = PriceRepository.get();
        loading = true;
        refreshPriceStatus();
        bg.execute(() -> {
            if (!repo.isLoaded()) PriceLoader.loadLocal(this);
            ui.post(this::refreshPriceStatus);
            boolean hasPath = !settings.priceUnc().trim().isEmpty();
            if (!PriceLoader.autoUpdateDone && settings.autoUpdate() && hasPath) {
                PriceLoader.autoUpdateDone = true;
                ui.post(() -> tvPriceStatus.setText("Обновление прайса по сети…"));
                String err = null;
                try {
                    PriceLoader.loadFromNetwork(this);
                } catch (Exception e) {
                    err = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final String error = err;
                ui.post(() -> {
                    loading = false;
                    refreshPriceStatus();
                    if (error != null) {
                        tvPriceStatus.setText(tvPriceStatus.getText() + "\n⚠ Прайс не обновлён: " + error);
                    }
                });
            } else {
                ui.post(() -> { loading = false; refreshPriceStatus(); });
            }
        });
    }

    private void refreshPriceStatus() {
        PriceRepository repo = PriceRepository.get();
        if (repo.isLoaded()) {
            String last = settings.lastLoadInfo();
            tvPriceStatus.setText("Прайс: " + (last.isEmpty() ? "товаров " + repo.size() : last));
            tvPriceStatus.setTextColor(getColor(R.color.text_muted));
        } else if (loading) {
            tvPriceStatus.setText("Прайс загружается…");
        } else {
            tvPriceStatus.setText("Прайс не загружен — укажите путь к файлу в настройках");
            tvPriceStatus.setTextColor(getColor(R.color.bad));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!loading) refreshPriceStatus();
        String cashier = settings.cashier().trim();
        tvKassa.setText("Касса № " + settings.kassaNumber() + (cashier.isEmpty() ? "" : " · Продавец: " + cashier));
        bg.execute(() -> {
            List<Receipt> unpaid = SalesDb.get(this).listUnpaid();
            BigDecimal sum = Money.ZERO;
            for (Receipt r : unpaid) sum = sum.add(r.total());
            final int n = unpaid.size();
            final BigDecimal total = sum;
            ui.post(() -> {
                if (n == 0) {
                    tvUnpaid.setVisibility(View.GONE);
                } else {
                    tvUnpaid.setText("Неоплаченных чеков: " + n + " на сумму " + Money.tg(total) + "  ›");
                    tvUnpaid.setVisibility(View.VISIBLE);
                }
            });
        });
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        bg.shutdown();
    }
}
