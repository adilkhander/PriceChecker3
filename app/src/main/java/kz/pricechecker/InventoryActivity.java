package kz.pricechecker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.AutoCompleteTextView;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Режим инвентаризации: пересчёт остатков по группам (папкам прайса).
 * Данные сохраняются в памяти телефона, по окончании — файл «Остатки …xlsx»;
 * при настроенной Google-таблице каждый остаток сразу уходит в столбец продавца.
 */
@SuppressWarnings("deprecation") // onActivityResult
public class InventoryActivity extends Activity {
    private static final int REQ_SCAN = 1;
    private static final int REQ_STORAGE = 2;
    private static final int CHUNK = 400;

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final ExecutorService net = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final AtomicBoolean syncQueued = new AtomicBoolean(false);
    private volatile boolean destroyed = false;

    private Settings settings;
    private PriceRepository repo;
    private InventoryDb db;

    private long sessionId = -1;
    private String seller = "";
    private long startedAt = 0;
    private final Map<String, BigDecimal> counted = new HashMap<>();

    private Map<String, List<Product>> groups = new LinkedHashMap<>();
    private List<Product> groupsSource = null;

    private Product current;
    private List<Product> currentGroup = new ArrayList<>();
    private boolean saving = false;
    private boolean finishing = false;

    private AutoCompleteTextView etSearch;
    private EditText etQty;
    private TextView tvSession, tvSync, tvHint, tvGroup, tvName, tvInfo, tvSystem, tvPrevious;
    private View cardProduct, barSave, btnSame;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_inventory);
        setTitle("Режим инвентаризации");
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);
        settings = new Settings(this);
        repo = PriceRepository.get();
        db = InventoryDb.get(this);

        etSearch = findViewById(R.id.etSearch);
        etQty = findViewById(R.id.etQty);
        tvSession = findViewById(R.id.tvSession);
        tvSync = findViewById(R.id.tvSync);
        tvHint = findViewById(R.id.tvHint);
        tvGroup = findViewById(R.id.tvGroup);
        tvName = findViewById(R.id.tvName);
        tvInfo = findViewById(R.id.tvInfo);
        tvSystem = findViewById(R.id.tvSystem);
        tvPrevious = findViewById(R.id.tvPrevious);
        cardProduct = findViewById(R.id.cardProduct);
        barSave = findViewById(R.id.barSave);
        btnSame = findViewById(R.id.btnSame);

        final MainActivity.ProductAdapter adapter = new MainActivity.ProductAdapter(this);
        etSearch.setAdapter(adapter);
        etSearch.setOnItemClickListener((parent, view, position, id) -> {
            Product p = adapter.getItem(position);
            if (p != null) showProduct(p);
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
        findViewById(R.id.btnMore).setOnClickListener(v -> showMore());
        findViewById(R.id.btnFinish).setOnClickListener(v -> confirmFinish());
        findViewById(R.id.btnSaveNext).setOnClickListener(v -> saveCurrent(true));
        findViewById(R.id.btnSkip).setOnClickListener(v -> skipCurrent());
        btnSame.setOnClickListener(v -> {
            if (current != null && current.stock != null) {
                etQty.setText(Money.plain(current.stock));
                etQty.setSelection(etQty.getText().length());
            }
        });
        tvGroup.setOnClickListener(v -> showGroupItems());
        etQty.setImeOptions(EditorInfo.IME_ACTION_DONE);
        etQty.setOnEditorActionListener((v, id, e) -> {
            saveCurrent(true);
            return true;
        });

        loadAll();
    }

    /** Загружает прайс (если ещё не в памяти) и поднимает текущий сеанс. */
    private void loadAll() {
        bg.execute(() -> {
            if (!repo.isLoaded()) PriceLoader.loadLocal(this);
            final InventoryDb.Session s = db.activeSession();
            final Map<String, BigDecimal> map = s == null ? new HashMap<String, BigDecimal>() : db.counted(s.id);
            ui.post(() -> {
                if (destroyed) return;
                ensureGroups();
                if (!repo.isLoaded()) {
                    tvHint.setText("Прайс не загружен — укажите путь к файлу в настройках и вернитесь на главный экран.");
                }
                if (s == null) {
                    askSellerAndStart();
                } else {
                    attach(s, map);
                }
            });
        });
    }

    private void attach(InventoryDb.Session s, Map<String, BigDecimal> map) {
        sessionId = s.id;
        seller = s.seller;
        startedAt = s.startedAt;
        counted.clear();
        counted.putAll(map);
        updateSessionLine();
        triggerSync();
    }

    private void askSellerAndStart() {
        final EditText et = new EditText(this);
        et.setHint("Ваше имя");
        et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS);
        et.setText(settings.cashier());
        et.setSelectAllOnFocus(true);
        LinearLayout box = new LinearLayout(this);
        box.setPadding(dp(20), dp(8), dp(20), 0);
        box.addView(et, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Начать инвентаризацию")
                .setMessage("Введите имя продавца. Под этим именем остатки попадут в Excel-файл и в отдельный столбец Google-таблицы.")
                .setView(box)
                .setCancelable(false)
                .setPositiveButton("Начать", null)
                .setNegativeButton("Назад", (d, w) -> finish())
                .create();
        dlg.show();
        dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            final String name = et.getText().toString().trim();
            if (name.isEmpty()) {
                et.setError("Введите имя");
                return;
            }
            settings.edit().putString("cashier", name).apply();
            dlg.dismiss();
            bg.execute(() -> {
                final InventoryDb.Session s = db.startSession(name);
                ui.post(() -> {
                    if (!destroyed) attach(s, new HashMap<String, BigDecimal>());
                });
            });
        });
    }

    private void ensureGroups() {
        List<Product> all = repo.all();
        if (groupsSource != all) {
            groups = InventoryLogic.groups(all);
            groupsSource = all;
        }
    }

    private void updateSessionLine() {
        String when = new SimpleDateFormat("dd.MM HH:mm", Locale.ROOT).format(new Date(startedAt));
        tvSession.setText("Продавец: " + seller + " · начато " + when + " · посчитано товаров: " + counted.size());
    }

    // ================= поиск =================

    private void handleQuery(String raw) {
        String q = raw == null ? "" : raw.trim();
        if (q.isEmpty()) return;
        etSearch.dismissDropDown();
        if (q.matches("[0-9]{4,}")) {
            List<Product> byCode = repo.findByBarcode(q);
            if (!byCode.isEmpty()) { choose(byCode); return; }
        }
        List<Product> res = repo.search(q, 100);
        if (res.isEmpty()) notFound(q);
        else choose(res);
    }

    private void onScanned(String code) {
        List<Product> found = repo.findByBarcode(code);
        if (found.isEmpty()) notFound(code);
        else choose(found);
    }

    private void notFound(String q) {
        new AlertDialog.Builder(this)
                .setTitle("Товар не найден")
                .setMessage("«" + q + "» нет в прайс-листе. Если товар новый, обновите прайс (главный экран → Настройки).")
                .setPositiveButton("OK", null)
                .show();
    }

    private void choose(final List<Product> list) {
        if (list.size() == 1) { showProduct(list.get(0)); return; }
        String[] items = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Product p = list.get(i);
            items[i] = p.name + "\n" + Money.fmt(p.price) + " ₸ · ост. " + Product.formatQty(p.stock);
        }
        new AlertDialog.Builder(this)
                .setTitle("Выберите товар")
                .setItems(items, (d, which) -> showProduct(list.get(which)))
                .setNegativeButton("Отмена", null)
                .show();
    }

    // ================= карточка товара =================

    private void showProduct(Product p) {
        ensureGroups();
        current = p;
        List<Product> g = groups.get(InventoryLogic.groupName(p));
        currentGroup = g == null ? new ArrayList<Product>() : g;

        tvHint.setVisibility(View.GONE);
        cardProduct.setVisibility(View.VISIBLE);
        barSave.setVisibility(View.VISIBLE);
        etSearch.dismissDropDown();
        etSearch.setText("", false);

        tvName.setText(p.name);
        String bc = p.barcodes.isEmpty() ? "нет" : p.barcodesText();
        tvInfo.setText("Штрихкод: " + bc + "\nЦена: " + (p.price == null ? "—" : Money.tg(p.price)));
        tvSystem.setText("Остаток в учёте: " + (p.stock == null ? "не указан" : Product.formatQty(p.stock)));
        btnSame.setVisibility(p.stock == null ? View.GONE : View.VISIBLE);

        BigDecimal prev = counted.get(InventoryLogic.keyOf(p));
        if (prev != null) {
            etQty.setText(Money.plain(prev));
            tvPrevious.setText("Уже посчитан: " + Product.formatQty(prev) + ". Можно исправить и сохранить.");
            tvPrevious.setVisibility(View.VISIBLE);
        } else {
            etQty.setText("");
            tvPrevious.setVisibility(View.GONE);
        }
        etQty.setError(null);
        updateGroupLine();

        etQty.requestFocus();
        etQty.selectAll();
        etQty.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showSoftInput(etQty, InputMethodManager.SHOW_IMPLICIT);
        }, 150);
    }

    private void updateGroupLine() {
        if (current == null) return;
        int total = currentGroup.size();
        int done = InventoryLogic.countedIn(currentGroup, counted.keySet());
        tvGroup.setText("Группа: " + InventoryLogic.groupName(current) + " · посчитано " + done + " из " + total + "  ›");
    }

    private void clearProduct() {
        current = null;
        currentGroup = new ArrayList<>();
        cardProduct.setVisibility(View.GONE);
        barSave.setVisibility(View.GONE);
        tvHint.setVisibility(View.VISIBLE);
        hideKeyboard();
        etSearch.requestFocus();
    }

    // ================= сохранение =================

    private void saveCurrent(final boolean goNext) {
        if (current == null || saving || sessionId < 0) return;
        BigDecimal q = Money.parse(etQty.getText().toString());
        if (q == null || q.signum() < 0) {
            etQty.setError("Введите остаток (0 или больше)");
            etQty.requestFocus();
            return;
        }
        if (q.compareTo(new BigDecimal("1000000")) > 0) {
            etQty.setError("Слишком большое число");
            etQty.requestFocus();
            return;
        }
        saving = true;
        final Product saved = current;
        final InventoryItem it = InventoryItem.of(saved, q, System.currentTimeMillis());
        final long sid = sessionId;
        counted.put(it.key, q);
        bg.execute(() -> {
            try {
                db.saveCount(sid, it);
                ui.post(() -> {
                    saving = false;
                    if (destroyed) return;
                    updateSessionLine();
                    afterSaved(saved, goNext);
                    triggerSync();
                });
            } catch (final Exception e) {
                ui.post(() -> {
                    saving = false;
                    counted.remove(it.key);
                    if (destroyed) return;
                    new AlertDialog.Builder(this).setTitle("Остаток не сохранён")
                            .setMessage(String.valueOf(e.getMessage())).setPositiveButton("OK", null).show();
                });
            }
        });
    }

    private void afterSaved(Product saved, boolean goNext) {
        if (!goNext) {
            Toast.makeText(this, "Сохранено", Toast.LENGTH_SHORT).show();
            updateGroupLine();
            return;
        }
        Product nxt = InventoryLogic.next(currentGroup, saved, counted.keySet());
        if (nxt != null) {
            showProduct(nxt);
        } else {
            groupDone(saved);
        }
    }

    private void groupDone(Product saved) {
        final String name = InventoryLogic.groupName(saved);
        int total = currentGroup.size();
        updateGroupLine();
        new AlertDialog.Builder(this)
                .setTitle("Группа готова ✓")
                .setMessage("ВСЕ ТОВАРЫ группы «" + name + "» ИНВЕНТАРИЗИРОВАНЫ\n\nПосчитано позиций: " + total)
                .setCancelable(false)
                .setPositiveButton("OK", (d, w) -> clearProduct())
                .setNeutralButton("Другая группа…", (d, w) -> {
                    clearProduct();
                    selectGroup();
                })
                .show();
    }

    private void skipCurrent() {
        if (current == null) return;
        Product nxt = InventoryLogic.next(currentGroup, current, counted.keySet());
        if (nxt == null) {
            Toast.makeText(this, "В группе нет других непосчитанных товаров", Toast.LENGTH_LONG).show();
        } else {
            showProduct(nxt);
        }
    }

    // ================= группы =================

    private void selectGroup() {
        ensureGroups();
        if (groups.isEmpty()) {
            Toast.makeText(this, "Прайс не загружен", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<String> names = new ArrayList<>(groups.keySet());
        String[] labels = new String[names.size()];
        for (int i = 0; i < names.size(); i++) {
            List<Product> g = groups.get(names.get(i));
            int done = InventoryLogic.countedIn(g, counted.keySet());
            String mark = done >= g.size() ? "✓ " : done > 0 ? "◐ " : "○ ";
            labels[i] = mark + names.get(i) + " — " + done + "/" + g.size();
        }
        new AlertDialog.Builder(this)
                .setTitle("Выберите группу")
                .setItems(labels, (d, which) -> {
                    List<Product> g = groups.get(names.get(which));
                    Product first = InventoryLogic.next(g, null, counted.keySet());
                    if (first == null) {
                        Toast.makeText(this, "Все товары этой группы уже посчитаны", Toast.LENGTH_LONG).show();
                    } else {
                        showProduct(first);
                    }
                })
                .setNegativeButton("Отмена", null)
                .show();
    }

    /** Список товаров текущей группы с отметками «посчитано». */
    private void showGroupItems() {
        if (current == null || currentGroup.isEmpty()) return;
        final List<Product> list = new ArrayList<>(currentGroup);
        String[] labels = new String[list.size()];
        for (int i = 0; i < list.size(); i++) {
            Product p = list.get(i);
            BigDecimal c = counted.get(InventoryLogic.keyOf(p));
            labels[i] = (c != null ? "✓ " : "○ ") + p.name + (c != null ? " — " + Product.formatQty(c) : "");
        }
        new AlertDialog.Builder(this)
                .setTitle(InventoryLogic.groupName(current))
                .setItems(labels, (d, which) -> showProduct(list.get(which)))
                .setNegativeButton("Закрыть", null)
                .show();
    }

    // ================= меню «Ещё» =================

    private void showMore() {
        final boolean configured = SheetSync.configured(settings);
        final List<String> labels = new ArrayList<>();
        labels.add("Выбрать группу");
        if (configured) {
            labels.add("Отправить в Google-таблицу сейчас");
            labels.add("Подготовить Google-таблицу (список всех товаров)");
        }
        labels.add("Отменить инвентаризацию (удалить данные)");
        new AlertDialog.Builder(this)
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    String l = labels.get(which);
                    if (l.startsWith("Выбрать")) selectGroup();
                    else if (l.startsWith("Отправить")) manualSync();
                    else if (l.startsWith("Подготовить")) prepareSheet();
                    else discard();
                })
                .setNegativeButton("Закрыть", null)
                .show();
    }

    private void discard() {
        if (sessionId < 0) return;
        new AlertDialog.Builder(this)
                .setTitle("Отменить инвентаризацию?")
                .setMessage("Все посчитанные остатки этого сеанса будут удалены с телефона. "
                        + "Данные, уже отправленные в Google-таблицу, там останутся.")
                .setPositiveButton("Удалить", (d, w) -> {
                    final long sid = sessionId;
                    bg.execute(() -> {
                        db.discardSession(sid);
                        ui.post(this::finish);
                    });
                })
                .setNegativeButton("Нет", null)
                .show();
    }

    // ================= Google-таблица =================

    /** Ставит отправку накопленных остатков в очередь (одновременно выполняется только одна). */
    private void triggerSync() {
        if (sessionId < 0) return;
        if (!SheetSync.configured(settings)) {
            showSync(null, 0);
            return;
        }
        if (syncQueued.getAndSet(true)) return;
        final long sid = sessionId;
        final String sel = seller;
        net.execute(() -> {
            syncQueued.set(false);
            String err = syncLoop(sid, sel);
            int pend = db.pendingCount(sid);
            final String e = err;
            final int p = pend;
            ui.post(() -> showSync(e, p));
        });
    }

    private void manualSync() {
        Toast.makeText(this, "Отправка…", Toast.LENGTH_SHORT).show();
        triggerSync();
    }

    /** Отправляет все неотправленные позиции пачками. Возвращает текст ошибки или null. */
    private String syncLoop(long sid, String sel) {
        while (true) {
            List<InventoryItem> batch = db.pending(sid, 100);
            if (batch.isEmpty()) return null;
            SheetSync.Result r = SheetSync.send(settings, sel, batch);
            if (!r.ok) return r.message;
            for (InventoryItem it : batch) db.markSynced(it.id, it.countedAt);
        }
    }

    private void showSync(String err, int pending) {
        if (destroyed) return;
        if (!SheetSync.configured(settings)) {
            tvSync.setText("Google-таблица не подключена: остатки сохраняются на телефоне и в Excel-файл");
            tvSync.setTextColor(getColor(R.color.text_muted));
        } else if (err != null) {
            tvSync.setText("☁ Нет отправки в Google-таблицу, в очереди: " + pending + ". " + err);
            tvSync.setTextColor(getColor(R.color.bad));
        } else if (pending > 0) {
            tvSync.setText("☁ Отправка в Google-таблицу… в очереди: " + pending);
            tvSync.setTextColor(getColor(R.color.text_muted));
        } else {
            tvSync.setText("☁ Все остатки отправлены в Google-таблицу");
            tvSync.setTextColor(getColor(R.color.ok));
        }
    }

    /** Загружает в таблицу весь список товаров прайса (без остатков), чтобы строки шли в порядке прайса. */
    private void prepareSheet() {
        if (sessionId < 0 || !repo.isLoaded()) {
            Toast.makeText(this, "Прайс не загружен", Toast.LENGTH_SHORT).show();
            return;
        }
        final List<Product> all = new ArrayList<>(repo.all());
        new AlertDialog.Builder(this)
                .setTitle("Подготовить Google-таблицу?")
                .setMessage("В таблицу будет добавлен список всех товаров прайса (" + all.size()
                        + " шт.) в порядке прайс-листа, чтобы продавцы вносили остатки в готовые строки. "
                        + "Делать это достаточно один раз за инвентаризацию, на одном телефоне.")
                .setPositiveButton("Загрузить", (d, w) -> runPrepare(all))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void runPrepare(final List<Product> all) {
        final TextView tv = new TextView(this);
        tv.setPadding(dp(20), dp(16), dp(20), dp(8));
        tv.setText("Загрузка списка товаров… 0 из " + all.size());
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Подготовка таблицы")
                .setView(tv)
                .setCancelable(false)
                .create();
        dlg.show();
        final String sel = seller;
        net.execute(() -> {
            String err = null;
            int sent = 0;
            for (int i = 0; i < all.size(); i += CHUNK) {
                List<InventoryItem> batch = new ArrayList<>();
                for (int k = i; k < Math.min(all.size(), i + CHUNK); k++) {
                    batch.add(InventoryItem.of(all.get(k), null, 0L));
                }
                SheetSync.Result r = SheetSync.send(settings, sel, batch);
                if (!r.ok) { err = r.message; break; }
                sent += batch.size();
                final int s = sent;
                ui.post(() -> tv.setText("Загрузка списка товаров… " + s + " из " + all.size()));
            }
            final String e = err;
            final int total = sent;
            ui.post(() -> {
                if (destroyed) return;
                dlg.dismiss();
                new AlertDialog.Builder(this)
                        .setTitle(e == null ? "Таблица подготовлена" : "Не удалось загрузить")
                        .setMessage(e == null ? "Добавлено товаров: " + total : e)
                        .setPositiveButton("OK", null)
                        .show();
            });
        });
    }

    // ================= завершение =================

    private void confirmFinish() {
        if (sessionId < 0) return;
        if (counted.isEmpty()) {
            Toast.makeText(this, "Пока нет ни одного посчитанного товара", Toast.LENGTH_LONG).show();
            return;
        }
        ensureGroups();
        int started = 0, done = 0;
        for (List<Product> g : groups.values()) {
            int c = InventoryLogic.countedIn(g, counted.keySet());
            if (c > 0) started++;
            if (c > 0 && c >= g.size()) done++;
        }
        new AlertDialog.Builder(this)
                .setTitle("Завершить инвентаризацию?")
                .setMessage("Посчитано товаров: " + counted.size()
                        + "\nГрупп начато: " + started + ", завершено полностью: " + done
                        + "\n\nБудет создан файл «Остатки» с датой и временем окончания, и сеанс закроется.")
                .setPositiveButton("Завершить", (d, w) -> beforeFinish())
                .setNegativeButton("Продолжить", null)
                .show();
    }

    /** Перед закрытием сеанса досылаем в Google-таблицу всё, что ещё не отправлено. */
    private void beforeFinish() {
        if (finishing) return;
        if (!SheetSync.configured(settings)) {
            startFinish();
            return;
        }
        Toast.makeText(this, "Отправка остатков в Google-таблицу…", Toast.LENGTH_SHORT).show();
        final long sid = sessionId;
        final String sel = seller;
        net.execute(() -> {
            String err = syncLoop(sid, sel);
            int pend = db.pendingCount(sid);
            final String e = err;
            final int p = pend;
            ui.post(() -> {
                if (destroyed) return;
                if (p > 0) {
                    new AlertDialog.Builder(this)
                            .setTitle("Не всё отправлено в Google-таблицу")
                            .setMessage("Не отправлено позиций: " + p + (e == null ? "" : "\n" + e)
                                    + "\n\nВ Excel-файл попадут все остатки. Завершить без отправки?")
                            .setPositiveButton("Завершить", (d, w) -> startFinish())
                            .setNegativeButton("Вернуться", null)
                            .show();
                } else {
                    startFinish();
                }
            });
        });
    }

    private void startFinish() {
        if (ShareFiles.needsStoragePermission(this)) {
            requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, REQ_STORAGE);
            return;
        }
        doFinish();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQ_STORAGE) doFinish(); // без разрешения копия в «Загрузки» не создастся, но файл будет доступен для отправки
    }

    private void doFinish() {
        if (finishing || sessionId < 0) return;
        finishing = true;
        final long sid = sessionId;
        final long ended = System.currentTimeMillis();
        final long started = startedAt;
        final String sel = seller;
        final String shop = settings.shopName();
        final List<Product> all = new ArrayList<>(repo.all());
        bg.execute(() -> {
            try {
                List<InventoryItem> items = db.items(sid);
                if (items.isEmpty()) {
                    ui.post(() -> {
                        finishing = false;
                        Toast.makeText(this, "Нет посчитанных товаров", Toast.LENGTH_LONG).show();
                    });
                    return;
                }
                Map<String, List<Product>> g = InventoryLogic.groups(all);
                File f = new File(ShareFiles.dir(this), InventoryExporter.fileName(ended));
                InventoryExporter.export(items, g, sel, shop, started, ended, f);
                db.endSession(sid, ended);
                String savedTo = null;
                String copyErr = null;
                try {
                    ShareFiles.copyToDownloads(this, f, ShareFiles.MIME_XLSX, "Остатки");
                    savedTo = "Загрузки / Остатки / " + f.getName();
                } catch (Exception e) {
                    copyErr = e.getMessage();
                }
                final InventoryExporter.Summary sm = InventoryExporter.summarize(items, g);
                final File file = f;
                final String path = savedTo;
                final String cerr = copyErr;
                ui.post(() -> showFinished(file, sm, path, cerr));
            } catch (final Exception e) {
                ui.post(() -> {
                    finishing = false;
                    new AlertDialog.Builder(this).setTitle("Файл не создан")
                            .setMessage(String.valueOf(e.getMessage())).setPositiveButton("OK", null).show();
                });
            }
        });
    }

    private void showFinished(final File f, InventoryExporter.Summary s, String savedTo, String copyErr) {
        if (destroyed) return;
        StringBuilder sb = new StringBuilder();
        sb.append("Файл: ").append(f.getName()).append('\n');
        if (savedTo != null) sb.append("Сохранён: ").append(savedTo).append('\n');
        else sb.append("Копия в «Загрузки» не создана").append(copyErr == null ? "" : " (" + copyErr + ")")
                .append(". Файл можно отправить кнопкой «Поделиться».\n");
        sb.append("\nПосчитано позиций: ").append(s.counted);
        sb.append("\nСовпало с учётом: ").append(s.equal);
        sb.append("\nИзлишки: ").append(s.surplusCount).append(" поз., ").append(Money.tg(s.surplusSum));
        sb.append("\nНедостача: ").append(s.shortageCount).append(" поз., ").append(Money.tg(s.shortageSum));
        sb.append("\nИтого разница: ").append(Money.tg(s.totalDiffSum()));
        new AlertDialog.Builder(this)
                .setTitle("Инвентаризация завершена")
                .setMessage(sb.toString())
                .setCancelable(false)
                .setPositiveButton("Поделиться", (d, w) -> {
                    ShareFiles.share(this, f, null, "Отправить файл остатков");
                    finish();
                })
                .setNegativeButton("Закрыть", (d, w) -> finish())
                .show();
    }

    // ================= прочее =================

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        if (requestCode == REQ_SCAN) {
            String code = data.getStringExtra(ScanActivity.EXTRA_CODE);
            if (code != null && !code.trim().isEmpty()) onScanned(code.trim());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (sessionId >= 0) triggerSync();
    }

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
        destroyed = true;
        bg.shutdown();
        net.shutdown();
    }
}
