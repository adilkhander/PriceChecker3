package kz.pricechecker;

import android.content.Context;
import android.content.SharedPreferences;

/** Настройки приложения (хранятся в SharedPreferences). */
public class Settings {
    public static final String PRINT_SMB = "smb";
    public static final String PRINT_TCP = "tcp";
    public static final String PRINT_SYSTEM = "system";

    private final SharedPreferences p;

    public Settings(Context c) {
        p = c.getApplicationContext().getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    // ---- Прайс-лист ----
    /** Сетевой путь к файлу, например \\192.168.1.10\Общая\прайс.xlsx */
    public String priceUnc() { return p.getString("price_unc", ""); }
    public String smbUser() { return p.getString("smb_user", ""); }
    public String smbPassword() { return p.getString("smb_password", ""); }
    public String smbDomain() { return p.getString("smb_domain", ""); }
    public boolean autoUpdate() { return p.getBoolean("auto_update", true); }

    // ---- Принтер ----
    public String printMode() { return p.getString("print_mode", PRINT_SMB); }
    /** Сетевой путь к общему принтеру, например \\192.168.1.10\XP-365B */
    public String printerUnc() { return p.getString("printer_unc", ""); }
    public String printerIp() { return p.getString("printer_ip", ""); }
    public int printerPort() { return p.getInt("printer_port", 9100); }

    // ---- Этикетка ----
    public float labelWidthMm() { return p.getFloat("label_w", 58f); }
    public float labelHeightMm() { return p.getFloat("label_h", 40f); }
    public float labelGapMm() { return p.getFloat("label_gap", 2f); }
    public int dpi() { return p.getInt("dpi", 203); }
    public int density() { return p.getInt("density", 8); }
    public int speed() { return p.getInt("speed", 4); }
    public int direction() { return p.getInt("direction", 1); }
    public int offsetXDots() { return p.getInt("offset_x", 0); }
    public int offsetYDots() { return p.getInt("offset_y", 0); }
    public boolean invertBitmap() { return p.getBoolean("invert", false); }

    // ---- Режим продаж / чек ----
    public int kassaNumber() { return p.getInt("kassa", 1); }
    public String cashier() { return p.getString("cashier", ""); }
    public String shopName() { return p.getString("shop_name", "Магазин"); }
    public String shopBin() { return p.getString("shop_bin", ""); }
    public String shopAddress() { return p.getString("shop_address", ""); }
    public String shopPhone() { return p.getString("shop_phone", ""); }
    public String receiptFooter() { return p.getString("receipt_footer", "Спасибо за покупку!"); }

    // ---- Режим инвентаризации (Google-таблица) ----
    /** Адрес веб-приложения Google Apps Script (оканчивается на /exec). Пусто — только Excel-файл. */
    public String sheetUrl() { return p.getString("sheet_url", ""); }
    public String sheetSecret() { return p.getString("sheet_secret", ""); }

    // ---- Служебное ----
    public String lastLoadInfo() { return p.getString("last_load", ""); }
    public String priceSourceName() { return p.getString("source_name", ""); }

    public SharedPreferences.Editor edit() { return p.edit(); }
}
