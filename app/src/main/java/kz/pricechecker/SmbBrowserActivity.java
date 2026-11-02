package kz.pricechecker;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Простой обозреватель общей папки Windows для выбора файла прайса. */
public class SmbBrowserActivity extends Activity {
    public static final String EXTRA_START = "start";
    public static final String EXTRA_RESULT = "result";

    private final ExecutorService bg = Executors.newSingleThreadExecutor();
    private final Handler ui = new Handler(Looper.getMainLooper());

    private TextView tvPath;
    private ProgressBar progress;
    private ArrayAdapter<String> adapter;
    private final List<SmbHelper.Entry> entries = new ArrayList<>();
    private String host, share, path = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (getActionBar() != null) getActionBar().setDisplayHomeAsUpEnabled(true);

        int pad = dp(12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);

        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.INVISIBLE);
        root.addView(progress, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)));

        tvPath = new TextView(this);
        tvPath.setPadding(pad, pad, pad, pad);
        tvPath.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        tvPath.setTextColor(getColor(R.color.primary));
        root.addView(tvPath);

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, new ArrayList<>());
        list.setAdapter(adapter);
        list.setOnItemClickListener((p, v, pos, id) -> onItem(pos));
        root.addView(list, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);

        String start = getIntent().getStringExtra(EXTRA_START);
        if (!applyStart(start)) askLocation(start);
    }

    private boolean applyStart(String start) {
        try {
            SmbHelper.Unc u = SmbHelper.Unc.parse(start);
            host = u.host;
            share = u.share;
            path = u.path;
            String low = path.toLowerCase(Locale.ROOT);
            if (low.endsWith(".xlsx") || low.endsWith(".xls")) {
                int i = path.lastIndexOf('\\');
                path = i >= 0 ? path.substring(0, i) : "";
            }
            load();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void askLocation(String current) {
        final EditText et = new EditText(this);
        et.setHint("\\\\192.168.1.10\\Общая");
        et.setText(current == null ? "" : current);
        new AlertDialog.Builder(this)
                .setTitle("Компьютер и общая папка")
                .setMessage("Введите адрес в виде \\\\IP-адрес\\ИмяОбщейПапки")
                .setView(et)
                .setPositiveButton("Открыть", (d, w) -> {
                    if (!applyStart(et.getText().toString())) askLocation(et.getText().toString());
                })
                .setNegativeButton("Отмена", (d, w) -> finish())
                .setCancelable(false)
                .show();
    }

    private String currentUnc() {
        return "\\\\" + host + "\\" + share + (path.isEmpty() ? "" : "\\" + path);
    }

    private void load() {
        tvPath.setText(currentUnc());
        progress.setVisibility(View.VISIBLE);
        final SmbHelper helper = new SmbHelper(new Settings(this));
        final String h = host, sh = share, p = path;
        bg.execute(() -> {
            try {
                final List<SmbHelper.Entry> res = helper.list(SmbHelper.Unc.parse("\\\\" + h + "\\" + sh + (p.isEmpty() ? "" : "\\" + p)));
                ui.post(() -> show(res));
            } catch (final Exception e) {
                ui.post(() -> {
                    progress.setVisibility(View.INVISIBLE);
                    if (isFinishing()) return;
                    new AlertDialog.Builder(this)
                            .setTitle("Ошибка доступа")
                            .setMessage(e.getMessage() == null ? e.toString() : e.getMessage())
                            .setPositiveButton("Изменить адрес", (d, w) -> askLocation(currentUnc()))
                            .setNegativeButton("Закрыть", (d, w) -> finish())
                            .setCancelable(false)
                            .show();
                });
            }
        });
    }

    private void show(List<SmbHelper.Entry> res) {
        progress.setVisibility(View.INVISIBLE);
        entries.clear();
        adapter.clear();
        if (!path.isEmpty()) {
            entries.add(null);
            adapter.add("⬆  ..  (вверх)");
        }
        for (SmbHelper.Entry e : res) {
            entries.add(e);
            adapter.add(e.dir ? "📁  " + e.name : "📄  " + e.name + "   (" + (e.size / 1024) + " КБ)");
        }
        if (res.isEmpty()) adapter.add("(нет папок и файлов Excel)");
        adapter.notifyDataSetChanged();
    }

    private void onItem(int pos) {
        if (pos >= entries.size()) return;
        SmbHelper.Entry e = entries.get(pos);
        if (e == null) {
            int i = path.lastIndexOf('\\');
            path = i >= 0 ? path.substring(0, i) : "";
            load();
        } else if (e.dir) {
            path = path.isEmpty() ? e.name : path + "\\" + e.name;
            load();
        } else {
            String full = "\\\\" + host + "\\" + share + "\\" + (path.isEmpty() ? "" : path + "\\") + e.name;
            setResult(RESULT_OK, new Intent().putExtra(EXTRA_RESULT, full));
            finish();
        }
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
}
