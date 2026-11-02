package kz.pricechecker;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Журнал чеков: период, итоги по способам оплаты, неоплаченные, выгрузка в Excel. */
public class ReceiptsActivity extends Activity {
    public static final String EXTRA_UNPAID_ONLY = "unpaid_only";
    private static final int REQ_STORAGE = 1;

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat dFmt = new SimpleDateFormat("dd.MM.yyyy", Locale.ROOT);

    private final Calendar from = Calendar.getInstance();
    private final Calendar to = Calendar.getInstance();
    private List<Receipt> receipts = new ArrayList<>();

    private Button btnFrom, btnTo;
    private CheckBox cbUnpaid;
    private TextView tvSummary, tvNoReceipts;
    private ReceiptAdapter adapter;
    private File pendingExport;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_receipts);
        setTitle("Журнал чеков");
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);

        btnFrom = findViewById(R.id.btnFrom);
        btnTo = findViewById(R.id.btnTo);
        cbUnpaid = findViewById(R.id.cbUnpaidOnly);
        tvSummary = findViewById(R.id.tvSummary);
        tvNoReceipts = findViewById(R.id.tvNoReceipts);
        ListView lv = findViewById(R.id.lvReceipts);
        adapter = new ReceiptAdapter(this);
        lv.setAdapter(adapter);
        lv.setOnItemClickListener((p, v, pos, id) -> startActivity(new Intent(this, ReceiptActivity.class)
                .putExtra(ReceiptActivity.EXTRA_ID, receipts.get(pos).id)));

        btnFrom.setOnClickListener(v -> pickDate(from));
        btnTo.setOnClickListener(v -> pickDate(to));
        findViewById(R.id.btnToday).setOnClickListener(v -> {
            from.setTimeInMillis(System.currentTimeMillis());
            to.setTimeInMillis(System.currentTimeMillis());
            cbUnpaid.setChecked(false);
            reload();
        });
        cbUnpaid.setChecked(getIntent().getBooleanExtra(EXTRA_UNPAID_ONLY, false));
        cbUnpaid.setOnCheckedChangeListener((b, c) -> reload());
        findViewById(R.id.btnExport).setOnClickListener(v -> export());
    }

    @Override
    protected void onResume() {
        super.onResume();
        reload();
    }

    private void pickDate(final Calendar target) {
        new DatePickerDialog(this, (view, y, m, d) -> {
            target.set(y, m, d);
            if (from.after(to)) {
                if (target == from) to.setTimeInMillis(from.getTimeInMillis());
                else from.setTimeInMillis(to.getTimeInMillis());
            }
            cbUnpaid.setChecked(false);
            reload();
        }, target.get(Calendar.YEAR), target.get(Calendar.MONTH), target.get(Calendar.DAY_OF_MONTH)).show();
    }

    private static long startOfDay(Calendar c) {
        Calendar x = (Calendar) c.clone();
        x.set(Calendar.HOUR_OF_DAY, 0);
        x.set(Calendar.MINUTE, 0);
        x.set(Calendar.SECOND, 0);
        x.set(Calendar.MILLISECOND, 0);
        return x.getTimeInMillis();
    }

    private static long endOfDayExclusive(Calendar c) {
        Calendar x = (Calendar) c.clone();
        x.setTimeInMillis(startOfDay(c));
        x.add(Calendar.DAY_OF_MONTH, 1);
        return x.getTimeInMillis();
    }

    private void reload() {
        btnFrom.setText("С " + dFmt.format(from.getTime()));
        btnTo.setText("По " + dFmt.format(to.getTime()));
        final boolean unpaidOnly = cbUnpaid.isChecked();
        btnFrom.setEnabled(!unpaidOnly);
        btnTo.setEnabled(!unpaidOnly);
        final long f = startOfDay(from), t = endOfDayExclusive(to);
        bg.execute(() -> {
            final List<Receipt> list = unpaidOnly ? SalesDb.get(this).listUnpaid() : SalesDb.get(this).list(f, t, true);
            ui.post(() -> {
                receipts = list;
                adapter.notifyDataSetChanged();
                tvNoReceipts.setVisibility(list.isEmpty() ? View.VISIBLE : View.GONE);
                tvNoReceipts.setText(unpaidOnly ? "Неоплаченных чеков нет" : "Чеков за выбранный период нет");
                showSummary(list, unpaidOnly);
            });
        });
    }

    private void showSummary(List<Receipt> list, boolean unpaidOnly) {
        SalesExporter.Totals t = SalesExporter.Totals.of(list);
        StringBuilder sb = new StringBuilder();
        if (unpaidOnly) {
            sb.append("Неоплаченных: ").append(t.unpaidCount).append(" на ").append(Money.tg(t.unpaid));
        } else {
            sb.append("Чеков: ").append(t.count)
              .append("   Оплачено: ").append(Money.tg(t.paid))
              .append("\nНаличные ").append(Money.tg(t.cash))
              .append(" · Kaspi ").append(Money.tg(t.kaspi))
              .append(" · Halyk ").append(Money.tg(t.halyk));
            if (t.unpaidCount > 0) sb.append("\nНе оплачено: ").append(t.unpaidCount).append(" на ").append(Money.tg(t.unpaid));
            if (t.cancelledCount > 0) sb.append("\nАннулировано: ").append(t.cancelledCount);
        }
        tvSummary.setText(sb.toString());
    }

    // ---------- выгрузка ----------

    private void export() {
        final boolean unpaidOnly = cbUnpaid.isChecked();
        final long f = startOfDay(from), t = endOfDayExclusive(to);
        final Date dFrom = new Date(f), dTo = new Date(t - 1);
        final String shop = new Settings(this).shopName();
        final int kassa = new Settings(this).kassaNumber();
        bg.execute(() -> {
            try {
                List<Receipt> list = unpaidOnly ? SalesDb.get(this).listUnpaid() : SalesDb.get(this).list(f, t, true);
                if (list.isEmpty()) throw new Exception("Нет чеков для выгрузки");
                SimpleDateFormat n = new SimpleDateFormat("yyyyMMdd", Locale.ROOT);
                String name = unpaidOnly
                        ? "neoplachennye_kassa" + kassa + "_" + n.format(new Date()) + ".xlsx"
                        : "prodazhi_kassa" + kassa + "_" + n.format(dFrom) + "-" + n.format(dTo) + ".xlsx";
                File out = new File(ShareFiles.dir(this), name);
                Date expFrom = dFrom, expTo = dTo;
                if (unpaidOnly) {
                    long min = Long.MAX_VALUE, max = 0;
                    for (Receipt r : list) { min = Math.min(min, r.createdAt); max = Math.max(max, r.createdAt); }
                    expFrom = new Date(min);
                    expTo = new Date(max);
                }
                SalesExporter.export(list, expFrom, expTo, shop, out);
                ui.post(() -> exported(out));
            } catch (final Exception e) {
                ui.post(() -> Toast.makeText(this, "Выгрузка не выполнена: " + e.getMessage(), Toast.LENGTH_LONG).show());
            }
        });
    }

    private void exported(final File out) {
        new AlertDialog.Builder(this)
                .setTitle("Файл Excel готов")
                .setMessage(out.getName() + "\n\nСохранить в «Загрузки» телефона или отправить (WhatsApp, Telegram, почта, Диск)?")
                .setPositiveButton("Отправить…", (d, w) -> ShareFiles.share(this, out, "Продажи: " + out.getName(), "Отправить выгрузку"))
                .setNeutralButton("В Загрузки", (d, w) -> saveToDownloads(out))
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void saveToDownloads(final File out) {
        if (ShareFiles.needsStoragePermission(this)) {
            pendingExport = out;
            requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        bg.execute(() -> {
            String msg;
            try {
                ShareFiles.copyToDownloads(this, out, ShareFiles.MIME_XLSX);
                msg = "Сохранено: Загрузки / Чеки / " + out.getName();
            } catch (Exception e) {
                msg = "Не удалось сохранить: " + e.getMessage();
            }
            final String m = msg;
            ui.post(() -> Toast.makeText(this, m, Toast.LENGTH_LONG).show());
        });
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQ_STORAGE && pendingExport != null) {
            File f = pendingExport;
            pendingExport = null;
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) saveToDownloads(f);
            else Toast.makeText(this, "Нет разрешения на запись в память телефона", Toast.LENGTH_LONG).show();
        }
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

    private class ReceiptAdapter extends BaseAdapter {
        private final LayoutInflater inflater;
        private final SimpleDateFormat dt = new SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.ROOT);

        ReceiptAdapter(Context c) { inflater = LayoutInflater.from(c); }

        @Override public int getCount() { return receipts.size(); }
        @Override public Object getItem(int position) { return receipts.get(position); }
        @Override public long getItemId(int position) { return receipts.get(position).id; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView : inflater.inflate(R.layout.item_receipt, parent, false);
            Receipt r = receipts.get(position);
            ((TextView) v.findViewById(R.id.tvRcNumber)).setText("№ " + r.number + "   " + dt.format(new Date(r.createdAt)));
            String info = "Позиций: " + r.items.size();
            if (r.status == Receipt.PAID) info += " · " + r.paymentMethodsText();
            if (r.comment != null && !r.comment.isEmpty()) info += " · " + r.comment;
            ((TextView) v.findViewById(R.id.tvRcInfo)).setText(info);
            ((TextView) v.findViewById(R.id.tvRcSum)).setText(Money.tg(r.total()));
            TextView st = v.findViewById(R.id.tvRcStatus);
            st.setText(r.statusText());
            st.setTextColor(getColor(r.status == Receipt.PAID ? R.color.ok
                    : r.status == Receipt.UNPAID ? R.color.bad : R.color.text_muted));
            return v;
        }
    }
}
