package kz.pricechecker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AutoCompleteTextView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Filter;
import android.widget.Filterable;
import android.widget.ImageView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;


import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@SuppressWarnings("deprecation") // onActivityResult — работает на всех версиях Android
public class MainActivity extends Activity {
    private static final int MENU_SETTINGS = 1;
    private static final int MENU_UPDATE = 2;
    private static final int REQ_SCAN = 100;

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private Settings settings;
    private PriceRepository repo;

    private ProgressBar progress;
    private TextView tvStatus, tvName, tvFolder, tvBarcode, tvPrice, tvStock;
    private AutoCompleteTextView etSearch;
    private View resultCard, printCard;
    private ImageView ivLabel;
    private EditText etCopies;
    private Button btnPrint;

    private Product current;
    private Bitmap currentLabel;
    private int busy = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        settings = new Settings(this);
        repo = PriceRepository.get();

        progress = findViewById(R.id.progress);
        tvStatus = findViewById(R.id.tvStatus);
        tvName = findViewById(R.id.tvName);
        tvFolder = findViewById(R.id.tvFolder);
        tvBarcode = findViewById(R.id.tvBarcode);
        tvPrice = findViewById(R.id.tvPrice);
        tvStock = findViewById(R.id.tvStock);
        etSearch = findViewById(R.id.etSearch);
        resultCard = findViewById(R.id.resultCard);
        printCard = findViewById(R.id.printCard);
        ivLabel = findViewById(R.id.ivLabel);
        etCopies = findViewById(R.id.etCopies);
        btnPrint = findViewById(R.id.btnPrint);

        final ProductAdapter adapter = new ProductAdapter(this);
        etSearch.setAdapter(adapter);
        etSearch.setOnItemClickListener((parent, view, position, id) -> {
            Product p = adapter.getItem(position);
            if (p != null) showProduct(p);
        });
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                if (event == null || event.getAction() == KeyEvent.ACTION_DOWN) {
                    handleQuery(etSearch.getText().toString());
                }
                return true;
            }
            return false;
        });

        findViewById(R.id.btnScan).setOnClickListener(v -> startScan());
        findViewById(R.id.btnClear).setOnClickListener(v -> {
            etSearch.setText("");
            etSearch.requestFocus();
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showSoftInput(etSearch, InputMethodManager.SHOW_IMPLICIT);
        });
        findViewById(R.id.btnUpdate).setOnClickListener(v -> updateFromNetwork(true));
        findViewById(R.id.btnMinus).setOnClickListener(v -> setCopies(getCopies() - 1));
        findViewById(R.id.btnPlus).setOnClickListener(v -> setCopies(getCopies() + 1));
        btnPrint.setOnClickListener(v -> print());

        showLastCrash();
        startupLoad();
    }

    /** Если приложение в прошлый раз закрылось с ошибкой — показать её текст (для передачи разработчику). */
    private void showLastCrash() {
        final java.io.File f = App.crashFile(this);
        if (!f.exists()) return;
        String text;
        try {
            text = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        } catch (Exception e) {
            text = String.valueOf(e);
        }
        //noinspection ResultOfMethodCallIgnored
        f.delete();
        final String report = text;
        TextView tv = new TextView(this);
        tv.setText(report);
        tv.setTextIsSelectable(true);
        tv.setTextSize(11);
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad / 2, pad, 0);
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        sv.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle("Приложение было закрыто из-за ошибки")
                .setView(sv)
                .setPositiveButton("Копировать текст", (d, w) -> {
                    android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                    cm.setPrimaryClip(android.content.ClipData.newPlainText("Ошибка Прайс-чекер", report));
                    Toast.makeText(this, "Текст ошибки скопирован", Toast.LENGTH_SHORT).show();
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    // ---------------- Загрузка прайса ----------------

    private void startupLoad() {
        if (repo.isLoaded()) {
            refreshStatus();
            return;
        }
        setBusy(true);
        bg.execute(() -> {
            final boolean ok = PriceLoader.loadLocal(this);
            ui.post(() -> {
                setBusy(false);
                refreshStatus();
                boolean hasPath = !settings.priceUnc().trim().isEmpty();
                if (!PriceLoader.autoUpdateDone && settings.autoUpdate() && hasPath) {
                    PriceLoader.autoUpdateDone = true;
                    updateFromNetwork(false);
                } else if (!ok && !hasPath) {
                    new AlertDialog.Builder(this)
                            .setTitle("Добро пожаловать")
                            .setMessage("Укажите в настройках сетевой путь к прайс-листу (.xlsx) и принтер этикеток.")
                            .setPositiveButton("Открыть настройки", (d, w) -> openSettings())
                            .setNegativeButton("Позже", null)
                            .show();
                }
            });
        });
    }

    private void updateFromNetwork(final boolean userInitiated) {
        if (settings.priceUnc().trim().isEmpty()) {
            Toast.makeText(this, "Сначала укажите путь к прайсу в настройках", Toast.LENGTH_LONG).show();
            openSettings();
            return;
        }
        setBusy(true);
        tvStatus.setText("Загрузка прайса по сети…");
        bg.execute(() -> {
            try {
                final String status = PriceLoader.loadFromNetwork(this);
                ui.post(() -> {
                    setBusy(false);
                    refreshStatus();
                    Toast.makeText(this, "Прайс обновлён", Toast.LENGTH_SHORT).show();
                    if (current != null) refreshCurrent();
                });
            } catch (final Exception e) {
                ui.post(() -> {
                    setBusy(false);
                    refreshStatus();
                    String msg = e.getMessage() == null ? e.toString() : e.getMessage();
                    if (userInitiated || !repo.isLoaded()) {
                        showError("Не удалось загрузить прайс", msg
                                + (repo.isLoaded() ? "\n\nИспользуется сохранённая копия." : ""));
                    } else {
                        Toast.makeText(this, "Прайс не обновлён: " + msg + "\nИспользуется сохранённая копия.", Toast.LENGTH_LONG).show();
                    }
                });
            }
        });
    }

    private void refreshStatus() {
        if (repo.isLoaded()) {
            String last = settings.lastLoadInfo();
            tvStatus.setText(last.isEmpty() ? "Товаров: " + repo.size() : last);
            tvStatus.setTextColor(getColor(R.color.text_muted));
        } else {
            tvStatus.setText("Прайс не загружен. Нажмите «Обновить» или укажите путь в настройках.");
            tvStatus.setTextColor(getColor(R.color.bad));
        }
    }

    /** После обновления прайса — перечитать цену/остаток показанного товара. */
    private void refreshCurrent() {
        Product old = current;
        List<Product> found = old.barcodes.isEmpty() ? new ArrayList<>() : repo.findByBarcode(old.mainBarcode());
        for (Product p : found) {
            if (p.name.equals(old.name)) { showProduct(p); return; }
        }
        for (Product p : repo.all()) {
            if (p.name.equals(old.name)) { showProduct(p); return; }
        }
        if (!found.isEmpty()) showProduct(found.get(0));
    }

    // ---------------- Поиск ----------------

    private void handleQuery(String raw) {
        String q = raw == null ? "" : raw.trim();
        if (q.isEmpty()) return;
        if (!repo.isLoaded()) {
            Toast.makeText(this, "Прайс не загружен", Toast.LENGTH_SHORT).show();
            return;
        }
        etSearch.dismissDropDown();
        if (q.matches("[0-9]{4,}")) {
            List<Product> byCode = repo.findByBarcode(q);
            if (!byCode.isEmpty()) { choose(byCode, "Найдено по штрихкоду " + q); return; }
        }
        List<Product> res = repo.search(q, 100);
        if (res.isEmpty()) {
            showNotFound(q);
        } else {
            choose(res, "Найдено: " + res.size() + (res.size() >= 100 ? "+" : ""));
        }
    }

    private void lookupBarcode(String code) {
        if (!repo.isLoaded()) {
            Toast.makeText(this, "Прайс не загружен", Toast.LENGTH_SHORT).show();
            return;
        }
        List<Product> found = repo.findByBarcode(code);
        if (found.isEmpty()) showNotFound(code);
        else choose(found, "Найдено по штрихкоду " + code);
    }

    private void choose(final List<Product> list, String title) {
        if (list.size() == 1) { showProduct(list.get(0)); return; }
        String[] items = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Product p = list.get(i);
            items[i] = p.name + "\n" + Product.formatMoney(p.price) + " ₸ · ост. " + Product.formatQty(p.stock);
        }
        new AlertDialog.Builder(this)
                .setTitle(title)
                .setItems(items, (d, which) -> showProduct(list.get(which)))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void showNotFound(String q) {
        current = null;
        resultCard.setVisibility(View.GONE);
        printCard.setVisibility(View.GONE);
        new AlertDialog.Builder(this)
                .setTitle("Товар не найден")
                .setMessage("По запросу «" + q + "» в прайс-листе ничего нет.")
                .setPositiveButton("OK", null)
                .show();
    }

    private void showProduct(Product p) {
        current = p;
        hideKeyboard();
        etSearch.dismissDropDown();
        tvName.setText(p.name);
        tvFolder.setText(p.folder.isEmpty() ? "" : "Папка: " + p.folder);
        tvFolder.setVisibility(p.folder.isEmpty() ? View.GONE : View.VISIBLE);
        tvBarcode.setText(p.barcodes.isEmpty() ? "нет штрихкода" : p.barcodesText());
        tvPrice.setText(p.price == null ? "нет цены" : Product.formatMoney(p.price) + " ₸");
        tvStock.setText(Product.formatQty(p.stock));
        boolean positive = p.stock != null && p.stock.compareTo(BigDecimal.ZERO) > 0;
        tvStock.setTextColor(getColor(positive ? R.color.ok : R.color.bad));
        resultCard.setVisibility(View.VISIBLE);

        try {
            currentLabel = new LabelRenderer(this, settings).render(p);
            ivLabel.setImageBitmap(currentLabel);
            printCard.setVisibility(View.VISIBLE);
        } catch (Exception e) {
            currentLabel = null;
            printCard.setVisibility(View.GONE);
            Toast.makeText(this, "Ошибка подготовки этикетки: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
        setCopies(1);
        findViewById(R.id.scroll).post(() -> ((android.widget.ScrollView) findViewById(R.id.scroll))
                .smoothScrollTo(0, resultCard.getTop()));
    }

    // ---------------- Сканер ----------------

    private void startScan() {
        try {
            startActivityForResult(new Intent(this, ScanActivity.class), REQ_SCAN);
        } catch (Exception e) {
            showError("Сканер недоступен", String.valueOf(e.getMessage()));
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        if (requestCode == REQ_SCAN) {
            String code = resultCode == RESULT_OK && data != null ? data.getStringExtra(ScanActivity.EXTRA_CODE) : null;
            if (code != null && !code.trim().isEmpty()) {
                code = code.trim();
                etSearch.setText(code, false);
                etSearch.setSelection(etSearch.getText().length());
                lookupBarcode(code);
            }
            return;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    // ---------------- Печать ----------------

    private int getCopies() {
        try {
            return Math.max(1, Integer.parseInt(etCopies.getText().toString().trim()));
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    private void setCopies(int n) {
        n = Math.max(1, Math.min(999, n));
        etCopies.setText(String.valueOf(n));
    }

    private void print() {
        if (current == null) return;
        // перерисовываем: дата всегда текущая, параметры этикетки — из настроек
        final Bitmap label = new LabelRenderer(this, settings).render(current);
        currentLabel = label;
        ivLabel.setImageBitmap(label);
        final int copies = getCopies();

        if (Settings.PRINT_SYSTEM.equals(settings.printMode())) {
            LabelPrinter.printSystem(this, settings, label, copies);
            return;
        }
        setBusy(true);
        btnPrint.setEnabled(false);
        bg.execute(() -> {
            try {
                LabelPrinter.printRaw(settings, label, copies);
                ui.post(() -> {
                    setBusy(false);
                    btnPrint.setEnabled(true);
                    Toast.makeText(this, "Отправлено на печать: " + copies + " шт.", Toast.LENGTH_SHORT).show();
                });
            } catch (final Exception e) {
                ui.post(() -> {
                    setBusy(false);
                    btnPrint.setEnabled(true);
                    showError("Ошибка печати", e.getMessage() == null ? e.toString() : e.getMessage());
                });
            }
        });
    }

    // ---------------- Прочее ----------------

    private void setBusy(boolean b) {
        busy += b ? 1 : -1;
        if (busy < 0) busy = 0;
        progress.setVisibility(busy > 0 ? View.VISIBLE : View.INVISIBLE);
    }

    private void showError(String title, String msg) {
        if (isFinishing()) return;
        new AlertDialog.Builder(this).setTitle(title).setMessage(msg).setPositiveButton("OK", null).show();
    }

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
    }

    private void openSettings() {
        startActivity(new Intent(this, SettingsActivity.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
        if (current != null) {
            // настройки этикетки могли измениться
            try {
                currentLabel = new LabelRenderer(this, settings).render(current);
                ivLabel.setImageBitmap(currentLabel);
            } catch (Exception ignored) { }
        }
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        menu.add(0, MENU_UPDATE, 0, "Обновить прайс");
        menu.add(0, MENU_SETTINGS, 1, "Настройки")
                .setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == MENU_SETTINGS) { openSettings(); return true; }
        if (item.getItemId() == MENU_UPDATE) { updateFromNetwork(true); return true; }
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

    // ---------------- Адаптер выпадающего списка ----------------

    static class ProductAdapter extends BaseAdapter implements Filterable {
        private final LayoutInflater inflater;
        private List<Product> items = new ArrayList<>();

        ProductAdapter(Context c) { inflater = LayoutInflater.from(c); }

        @Override public int getCount() { return items.size(); }
        @Override public Product getItem(int position) {
            return position >= 0 && position < items.size() ? items.get(position) : null;
        }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView
                    : inflater.inflate(android.R.layout.simple_list_item_2, parent, false);
            Product p = items.get(position);
            TextView t1 = v.findViewById(android.R.id.text1);
            TextView t2 = v.findViewById(android.R.id.text2);
            t1.setText(p.name);
            t1.setTextSize(15);
            t1.setMaxLines(2);
            String code = p.barcodes.isEmpty() ? "" : " · " + p.mainBarcode();
            t2.setText(Product.formatMoney(p.price) + " ₸ · ост. " + Product.formatQty(p.stock) + code);
            return v;
        }

        @Override
        public Filter getFilter() {
            return new Filter() {
                @Override
                protected FilterResults performFiltering(CharSequence constraint) {
                    FilterResults fr = new FilterResults();
                    List<Product> res = constraint == null ? new ArrayList<>()
                            : PriceRepository.get().search(constraint.toString(), 50);
                    fr.values = res;
                    fr.count = res.size();
                    return fr;
                }

                @Override
                @SuppressWarnings("unchecked")
                protected void publishResults(CharSequence constraint, FilterResults results) {
                    items = results != null && results.values != null
                            ? (List<Product>) results.values : new ArrayList<>();
                    if (results != null && results.count > 0) notifyDataSetChanged();
                    else notifyDataSetInvalidated();
                }

                @Override
                public CharSequence convertResultToString(Object resultValue) {
                    return resultValue instanceof Product ? ((Product) resultValue).name : "";
                }
            };
        }
    }
}
