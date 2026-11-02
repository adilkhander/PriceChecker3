package kz.pricechecker;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.math.BigDecimal;

/** Приём оплаты: наличные / Kaspi / Halyk, в том числе смешанная оплата, расчёт сдачи. */
public class PaymentActivity extends Activity {
    public static final String EXTRA_TOTAL = "total";
    public static final String EXTRA_TITLE = "title";
    private static final String R_CASH = "cash", R_KASPI = "kaspi", R_HALYK = "halyk";

    private BigDecimal total;
    private EditText etCash, etKaspi, etHalyk;
    private TextView tvStatus;
    private Button btnConfirm;

    public static Receipt.Payment readResult(Intent data) {
        Receipt.Payment p = new Receipt.Payment();
        p.cashReceived = Money.load(data.getStringExtra(R_CASH));
        p.kaspi = Money.load(data.getStringExtra(R_KASPI));
        p.halyk = Money.load(data.getStringExtra(R_HALYK));
        return p;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_payment);
        String title = getIntent().getStringExtra(EXTRA_TITLE);
        setTitle(title != null ? title : "Приём оплаты");

        total = Money.load(getIntent().getStringExtra(EXTRA_TOTAL));
        ((TextView) findViewById(R.id.tvPayTotal)).setText(Money.tg(total));
        etCash = findViewById(R.id.etCash);
        etKaspi = findViewById(R.id.etKaspi);
        etHalyk = findViewById(R.id.etHalyk);
        tvStatus = findViewById(R.id.tvPayStatus);
        btnConfirm = findViewById(R.id.btnPayConfirm);

        TextWatcher tw = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { update(); }
        };
        etCash.addTextChangedListener(tw);
        etKaspi.addTextChangedListener(tw);
        etHalyk.addTextChangedListener(tw);

        findViewById(R.id.btnAllCash).setOnClickListener(v -> fillRest(etCash));
        findViewById(R.id.btnAllKaspi).setOnClickListener(v -> fillRest(etKaspi));
        findViewById(R.id.btnAllHalyk).setOnClickListener(v -> fillRest(etHalyk));

        LinearLayout quick = findViewById(R.id.quickCash);
        for (final int note : new int[]{1000, 2000, 5000, 10000, 20000}) {
            Button b = new Button(this, null, android.R.attr.buttonStyleSmall);
            b.setText(note >= 1000 ? (note / 1000) + "к" : String.valueOf(note));
            b.setOnClickListener(v -> {
                BigDecimal cur = Money.orZero(Money.parse(etCash.getText().toString()));
                etCash.setText(Money.plain(cur.add(BigDecimal.valueOf(note))));
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
            quick.addView(b, lp);
        }

        findViewById(R.id.btnPayCancel).setOnClickListener(v -> { setResult(RESULT_CANCELED); finish(); });
        btnConfirm.setOnClickListener(v -> confirm());

        update();
        etCash.requestFocus();
        etCash.postDelayed(() -> {
            InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
            imm.showSoftInput(etCash, InputMethodManager.SHOW_IMPLICIT);
        }, 200);
    }

    private Receipt.Payment current() {
        Receipt.Payment p = new Receipt.Payment();
        p.cashReceived = Money.orZero(Money.parse(etCash.getText().toString()));
        p.kaspi = Money.orZero(Money.parse(etKaspi.getText().toString()));
        p.halyk = Money.orZero(Money.parse(etHalyk.getText().toString()));
        return p;
    }

    /** В выбранное поле — оставшаяся к оплате сумма (с учётом других полей). */
    private void fillRest(EditText target) {
        Receipt.Payment p = current();
        BigDecimal others = Money.ZERO;
        if (target != etCash) others = others.add(p.cashReceived);
        if (target != etKaspi) others = others.add(p.kaspi);
        if (target != etHalyk) others = others.add(p.halyk);
        BigDecimal rest = total.subtract(others).max(Money.ZERO);
        target.setText(Money.plain(Money.round(rest)));
        target.requestFocus();
        target.setSelection(target.getText().length());
    }

    private void update() {
        Receipt.Payment p = current();
        BigDecimal paid = p.cashReceived.add(p.kaspi).add(p.halyk);
        String err = p.validate(total);
        if (err == null) {
            BigDecimal change = paid.subtract(total);
            if (change.signum() > 0) {
                tvStatus.setText("Сдача: " + Money.tg(change));
            } else {
                tvStatus.setText("Оплачено полностью");
            }
            tvStatus.setTextColor(getColor(R.color.ok));
            btnConfirm.setEnabled(true);
        } else {
            tvStatus.setText((paid.signum() > 0 ? "Внесено: " + Money.tg(paid) + "\n" : "") + err);
            tvStatus.setTextColor(getColor(R.color.bad));
            btnConfirm.setEnabled(false);
        }
    }

    private void confirm() {
        Receipt.Payment p = current();
        if (p.validate(total) != null) { update(); return; }
        Intent data = new Intent();
        data.putExtra(R_CASH, Money.store(p.cashReceived));
        data.putExtra(R_KASPI, Money.store(p.kaspi));
        data.putExtra(R_HALYK, Money.store(p.halyk));
        setResult(RESULT_OK, data);
        finish();
    }
}
