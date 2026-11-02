package kz.pricechecker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.util.TypedValue;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AutoCompleteTextView;
import android.widget.BaseAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Режим продаж: формирование чека, оплата, сохранение. */
@SuppressWarnings("deprecation") // onActivityResult
public class SalesActivity extends Activity {
    private static final int REQ_SCAN = 1;
    private static final int REQ_PAY = 2;

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final List<Receipt.Item> cart = new ArrayList<>();
    private Settings settings;
    private PriceRepository repo;

    private AutoCompleteTextView etSearch;
    private TextView tvTotal, tvCartTitle, tvEmpty;
    private CartAdapter cartAdapter;
    private boolean saving = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sales);
        setTitle("Режим продаж");
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        settings = new Settings(this);
        repo = PriceRepository.get();

        etSearch = findViewById(R.id.etSearch);
        tvTotal = findViewById(R.id.tvTotal);
        tvCartTitle = findViewById(R.id.tvCartTitle);
        tvEmpty = findViewById(R.id.tvEmpty);
        ListView lv = findViewById(R.id.lvCart);
        cartAdapter = new CartAdapter(this);
        lv.setAdapter(cartAdapter);
        lv.setOnItemClickListener((p, v, pos, id) -> editItem(pos));

        final MainActivity.ProductAdapter adapter = new MainActivity.ProductAdapter(this);
        etSearch.setAdapter(adapter);
        etSearch.setOnItemClickListener((parent, view, position, id) -> {
            Product p = adapter.getItem(position);
            if (p != null) askQuantity(p);
        });
        etSearch.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER;
            if (actionId == EditorInfo.IME_ACTION_SEARCH || actionId == EditorInfo.IME_ACTION_DONE || enter) {
                if (event == null || event.getAction() == KeyEvent.ACTION_DOWN) handleQuery(etSearch.getText().toString());
                return true;
            }
            return false;
        });

        findViewById(R.id.btnScan).setOnClickListener(v ->
                startActivityForResult(new Intent(this, ScanActivity.class), REQ_SCAN));
        findViewById(R.id.btnFree).setOnClickListener(v -> askFreeItem("", ""));
        findViewById(R.id.btnClearCart).setOnClickListener(v -> {
            if (cart.isEmpty()) return;
            new AlertDialog.Builder(this).setMessage("Очистить текущий чек?")
                    .setPositiveButton("Очистить", (d, w) -> { cart.clear(); cartChanged(); })
                    .setNegativeButton("Отмена", null).show();
        });
        findViewById(R.id.btnPay).setOnClickListener(v -> pay());
        findViewById(R.id.btnUnpaid).setOnClickListener(v -> saveUnpaid());

        restoreDraft();
        cartChanged();

        if (!repo.isLoaded()) {
            bg.execute(() -> {
                boolean ok = PriceLoader.loadLocal(this);
                ui.post(() -> {
                    if (!ok) Toast.makeText(this, "Прайс не загружен — товары можно добавлять вручную", Toast.LENGTH_LONG).show();
                });
            });
        }
    }

    // ================= поиск и добавление =================

    private void handleQuery(String raw) {
        String q = raw == null ? "" : raw.trim();
        if (q.isEmpty()) return;
        etSearch.dismissDropDown();
        if (q.matches("[0-9]{4,}")) {
            List<Product> byCode = repo.findByBarcode(q);
            if (!byCode.isEmpty()) { choose(byCode); return; }
        }
        List<Product> res = repo.search(q, 100);
        if (res.isEmpty()) {
            notFound(q);
        } else {
            choose(res);
        }
    }

    private void onScanned(String code) {
        List<Product> found = repo.findByBarcode(code);
        if (found.isEmpty()) notFound(code);
        else choose(found);
    }

    private void notFound(final String q) {
        final boolean digits = q.matches("[0-9]+");
        new AlertDialog.Builder(this)
                .setTitle("Товар не найден")
                .setMessage("«" + q + "» нет в прайс-листе. Добавить позицию вручную?")
                .setPositiveButton("Добавить вручную", (d, w) -> askFreeItem(digits ? "" : q, digits ? q : ""))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void choose(final List<Product> list) {
        if (list.size() == 1) { askQuantity(list.get(0)); return; }
        String[] items = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Product p = list.get(i);
            items[i] = p.name + "\n" + Money.fmt(p.price) + " ₸ · ост. " + Product.formatQty(p.stock);
        }
        new AlertDialog.Builder(this)
                .setTitle("Выберите товар")
                .setItems(items, (d, which) -> askQuantity(list.get(which)))
                .setNegativeButton("Отмена", null)
                .show();
    }

    /** Диалог количества для товара из прайса. */
    private void askQuantity(final Product p) {
        hideKeyboard();
        etSearch.dismissDropDown();
        etSearch.setText("", false);
        String info = (p.barcodes.isEmpty() ? "" : "Штрихкод: " + p.mainBarcode() + "\n")
                + "Остаток: " + Product.formatQty(p.stock);
        itemDialog("Добавить в чек", p.name, false, info, p.mainBarcode(), p.price, BigDecimal.ONE, -1);
    }

    /** Свободная позиция (товара нет в прайсе). */
    private void askFreeItem(String name, String barcode) {
        hideKeyboard();
        itemDialog("Позиция вручную", name, true, barcode.isEmpty() ? null : "Штрихкод: " + barcode,
                barcode, null, BigDecimal.ONE, -1);
    }

    private void editItem(final int index) {
        if (index < 0 || index >= cart.size()) return;
        Receipt.Item it = cart.get(index);
        itemDialog("Изменить позицию", it.name, false, it.barcode.isEmpty() ? null : "Штрихкод: " + it.barcode,
                it.barcode, it.price, it.qty, index);
    }

    /**
     * Универсальный диалог позиции: количество (с клавиатурой), цена, при необходимости — название.
     * editIndex = -1 — добавление, иначе — изменение строки чека.
     */
    private void itemDialog(String title, final String name, final boolean nameEditable, String info,
                            final String barcode, BigDecimal price, BigDecimal qty, final int editIndex) {
        int pad = dp(20);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(pad, dp(8), pad, 0);

        final EditText etName = new EditText(this);
        if (nameEditable) {
            box.addView(label("Наименование"));
            etName.setText(name);
            etName.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            box.addView(etName);
        } else {
            TextView tvName = new TextView(this);
            tvName.setText(name);
            tvName.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
            tvName.setTextColor(getColor(R.color.text_main));
            box.addView(tvName);
        }
        if (info != null) {
            TextView tvInfo = new TextView(this);
            tvInfo.setText(info);
            tvInfo.setTextColor(getColor(R.color.text_muted));
            tvInfo.setPadding(0, dp(4), 0, 0);
            box.addView(tvInfo);
        }

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout colQ = new LinearLayout(this);
        colQ.setOrientation(LinearLayout.VERTICAL);
        colQ.addView(label("Количество"));
        final EditText etQty = new EditText(this);
        etQty.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etQty.setText(Money.plain(qty));
        etQty.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        etQty.setSelectAllOnFocus(true);
        colQ.addView(etQty);
        LinearLayout colP = new LinearLayout(this);
        colP.setOrientation(LinearLayout.VERTICAL);
        colP.addView(label("Цена, ₸"));
        final EditText etPrice = new EditText(this);
        etPrice.setInputType(InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        etPrice.setText(price == null ? "" : Money.plain(price));
        etPrice.setTextSize(TypedValue.COMPLEX_UNIT_SP, 24);
        etPrice.setSelectAllOnFocus(true);
        colP.addView(etPrice);
        LinearLayout.LayoutParams half = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(colQ, half);
        LinearLayout.LayoutParams half2 = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        half2.leftMargin = dp(12);
        row.addView(colP, half2);
        box.addView(row);

        final TextView tvSum = new TextView(this);
        tvSum.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        tvSum.setTextColor(getColor(R.color.primary));
        tvSum.setPadding(0, dp(6), 0, dp(4));
        box.addView(tvSum);
        Runnable updateSum = () -> {
            BigDecimal q = Money.parse(etQty.getText().toString());
            BigDecimal pr = Money.parse(etPrice.getText().toString());
            tvSum.setText(q != null && pr != null ? "Сумма: " + Money.tg(Money.lineSum(q, pr)) : "");
        };
        android.text.TextWatcher tw = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(android.text.Editable s) { updateSum.run(); }
        };
        etQty.addTextChangedListener(tw);
        etPrice.addTextChangedListener(tw);
        updateSum.run();

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(editIndex >= 0 ? "Сохранить" : "Добавить", null)
                .setNegativeButton("Отмена", null);
        if (editIndex >= 0) {
            b.setNeutralButton("Удалить", (d, w) -> {
                cart.remove(editIndex);
                cartChanged();
            });
        }
        final AlertDialog dlg = b.create();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
        }
        dlg.show();

        final Runnable confirm = () -> {
            String n = nameEditable ? etName.getText().toString().trim() : name;
            BigDecimal q = Money.parse(etQty.getText().toString());
            BigDecimal pr = Money.parse(etPrice.getText().toString());
            if (n.isEmpty()) { etName.setError("Введите наименование"); etName.requestFocus(); return; }
            if (q == null || q.signum() <= 0) { etQty.setError("Введите количество"); etQty.requestFocus(); return; }
            if (q.compareTo(new BigDecimal("100000")) > 0) { etQty.setError("Слишком большое количество"); etQty.requestFocus(); return; }
            if (pr == null || pr.signum() < 0) { etPrice.setError("Введите цену"); etPrice.requestFocus(); return; }
            if (editIndex >= 0) {
                Receipt.Item it = cart.get(editIndex);
                it.qty = q;
                it.price = pr;
            } else {
                addToCart(new Receipt.Item(n, barcode, q, pr));
            }
            cartChanged();
            dlg.dismiss();
        };
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> confirm.run());
        etPrice.setImeOptions(EditorInfo.IME_ACTION_DONE);
        etPrice.setOnEditorActionListener((v, id, e) -> { confirm.run(); return true; });
        etQty.setImeOptions(EditorInfo.IME_ACTION_DONE);
        etQty.setOnEditorActionListener((v, id, e) -> { confirm.run(); return true; });

        // фокус: на цену, если её нет; на название для ручной позиции; иначе — на количество
        final EditText focus = nameEditable && name.isEmpty() ? etName : price == null ? etPrice : etQty;
        focus.requestFocus();
        focus.selectAll();
        focus.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showSoftInput(focus, InputMethodManager.SHOW_IMPLICIT);
        }, 150);
    }

    /** Одинаковый товар по той же цене — увеличиваем количество. */
    private void addToCart(Receipt.Item item) {
        for (Receipt.Item it : cart) {
            if (it.name.equals(item.name) && it.barcode.equals(item.barcode) && it.price.compareTo(item.price) == 0) {
                it.qty = it.qty.add(item.qty);
                Toast.makeText(this, "Количество увеличено: " + Product.formatQty(it.qty), Toast.LENGTH_SHORT).show();
                return;
            }
        }
        cart.add(item);
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        t.setTextColor(getColor(R.color.text_muted));
        t.setPadding(0, dp(10), 0, 0);
        return t;
    }

    // ================= чек =================

    private BigDecimal total() {
        BigDecimal t = Money.ZERO;
        for (Receipt.Item i : cart) t = t.add(i.sum());
        return Money.round(t);
    }

    private void cartChanged() {
        cartAdapter.notifyDataSetChanged();
        tvTotal.setText(Money.tg(total()));
        tvEmpty.setVisibility(cart.isEmpty() ? View.VISIBLE : View.GONE);
        BigDecimal units = Money.ZERO;
        for (Receipt.Item i : cart) units = units.add(i.qty);
        tvCartTitle.setText(cart.isEmpty() ? "Чек пуст"
                : "В чеке позиций: " + cart.size() + " (кол-во " + Product.formatQty(units) + ")");
        saveDraft();
    }

    private Receipt buildReceipt() {
        Receipt r = new Receipt();
        r.kassa = settings.kassaNumber();
        r.cashier = settings.cashier().trim();
        for (Receipt.Item i : cart) r.items.add(new Receipt.Item(i.name, i.barcode, i.qty, i.price));
        return r;
    }

    private void pay() {
        if (cart.isEmpty()) { Toast.makeText(this, "Добавьте товары в чек", Toast.LENGTH_SHORT).show(); return; }
        Intent i = new Intent(this, PaymentActivity.class);
        i.putExtra(PaymentActivity.EXTRA_TOTAL, Money.store(total()));
        startActivityForResult(i, REQ_PAY);
    }

    private void saveUnpaid() {
        if (cart.isEmpty()) { Toast.makeText(this, "Добавьте товары в чек", Toast.LENGTH_SHORT).show(); return; }
        final EditText et = new EditText(this);
        et.setHint("Покупатель, телефон (необязательно)");
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(et, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        new AlertDialog.Builder(this)
                .setTitle("Сохранить чек как НЕ ОПЛАЧЕННЫЙ")
                .setMessage("Сумма " + Money.tg(total()) + ". Оплатить его можно будет позже в журнале чеков.")
                .setView(box)
                .setPositiveButton("Сохранить", (d, w) -> {
                    Receipt r = buildReceipt();
                    r.status = Receipt.UNPAID;
                    r.comment = et.getText().toString().trim();
                    save(r);
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void save(final Receipt r) {
        if (saving) return;
        saving = true;
        bg.execute(() -> {
            try {
                r.createdAt = System.currentTimeMillis();
                if (r.status == Receipt.PAID) r.paidAt = r.createdAt;
                SalesDb.get(this).insert(r);
                ui.post(() -> {
                    saving = false;
                    cart.clear();
                    cartChanged();
                    startActivity(new Intent(this, ReceiptActivity.class)
                            .putExtra(ReceiptActivity.EXTRA_ID, r.id)
                            .putExtra(ReceiptActivity.EXTRA_NEW, true));
                });
            } catch (final Exception e) {
                ui.post(() -> {
                    saving = false;
                    new AlertDialog.Builder(this).setTitle("Чек не сохранён")
                            .setMessage(String.valueOf(e.getMessage())).setPositiveButton("OK", null).show();
                });
            }
        });
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_SCAN) {
            String code = data.getStringExtra(ScanActivity.EXTRA_CODE);
            if (code != null && !code.trim().isEmpty()) onScanned(code.trim());
        } else if (requestCode == REQ_PAY) {
            Receipt r = buildReceipt();
            Receipt.Payment p = PaymentActivity.readResult(data);
            if (p.validate(r.total()) != null) return;
            p.applyTo(r);
            r.status = Receipt.PAID;
            save(r);
        }
    }

    // ================= черновик (на случай закрытия приложения) =================

    private SharedPreferences draftPrefs() {
        return getSharedPreferences("sales_draft", MODE_PRIVATE);
    }

    private void saveDraft() {
        try {
            JSONArray a = new JSONArray();
            for (Receipt.Item i : cart) {
                JSONObject o = new JSONObject();
                o.put("n", i.name);
                o.put("b", i.barcode);
                o.put("q", Money.store(i.qty));
                o.put("p", Money.store(i.price));
                a.put(o);
            }
            draftPrefs().edit().putString("items", a.toString()).apply();
        } catch (Exception ignored) { }
    }

    private void restoreDraft() {
        try {
            String s = draftPrefs().getString("items", "");
            if (s.isEmpty()) return;
            JSONArray a = new JSONArray(s);
            for (int k = 0; k < a.length(); k++) {
                JSONObject o = a.getJSONObject(k);
                cart.add(new Receipt.Item(o.getString("n"), o.optString("b", ""),
                        Money.load(o.getString("q")), Money.load(o.getString("p"))));
            }
            if (!cart.isEmpty()) Toast.makeText(this, "Восстановлен незавершённый чек", Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) { }
    }

    // ================= прочее =================

    private void hideKeyboard() {
        InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
        imm.hideSoftInputFromWindow(etSearch.getWindowToken(), 0);
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
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

    private class CartAdapter extends BaseAdapter {
        private final LayoutInflater inflater;

        CartAdapter(Context c) { inflater = LayoutInflater.from(c); }

        @Override public int getCount() { return cart.size(); }
        @Override public Object getItem(int position) { return cart.get(position); }
        @Override public long getItemId(int position) { return position; }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View v = convertView != null ? convertView : inflater.inflate(R.layout.item_cart, parent, false);
            Receipt.Item it = cart.get(position);
            ((TextView) v.findViewById(R.id.tvItemName)).setText((position + 1) + ". " + it.name);
            ((TextView) v.findViewById(R.id.tvItemCalc)).setText(
                    Product.formatQty(it.qty) + " × " + Money.tg(it.price));
            ((TextView) v.findViewById(R.id.tvItemSum)).setText(Money.tg(it.sum()));
            return v;
        }
    }
}
