package kz.pricechecker;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.hardware.Camera;
import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.Surface;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.Result;
import com.google.zxing.common.HybridBinarizer;

import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Собственный экран сканирования штрихкодов: камера (android.hardware.Camera) + декодер ZXing.
 * Не зависит от Google Play Services и сторонних экранов сканера.
 * Возвращает результат в EXTRA_CODE.
 */
@SuppressWarnings("deprecation") // android.hardware.Camera работает на всех версиях Android
public class ScanActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    public static final String EXTRA_CODE = "code";
    private static final int REQ_CAMERA = 1;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private HandlerThread decodeThread;
    private Handler decoder;
    private final MultiFormatReader reader = new MultiFormatReader();

    private FrameLayout preview;
    private SurfaceView surfaceView;
    private SurfaceHolder holder;
    private boolean surfaceReady = false;
    private Camera camera;
    private int previewW, previewH;
    private volatile boolean decoding = false;
    private volatile boolean finished = false;
    private boolean torchOn = false;
    private boolean useAutoFocusLoop = false;
    private Button btnTorch;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (getActionBar() != null) getActionBar().hide();

        Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
        hints.put(DecodeHintType.POSSIBLE_FORMATS, Arrays.asList(
                BarcodeFormat.EAN_13, BarcodeFormat.EAN_8, BarcodeFormat.UPC_A, BarcodeFormat.UPC_E,
                BarcodeFormat.CODE_128, BarcodeFormat.CODE_39, BarcodeFormat.CODE_93, BarcodeFormat.ITF,
                BarcodeFormat.QR_CODE, BarcodeFormat.DATA_MATRIX));
        hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
        reader.setHints(hints);

        buildUi();

        decodeThread = new HandlerThread("decoder");
        decodeThread.start();
        decoder = new Handler(decodeThread.getLooper());

        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.CAMERA}, REQ_CAMERA);
        }
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);

        preview = new FrameLayout(this);
        surfaceView = new SurfaceView(this);
        preview.addView(surfaceView, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT, Gravity.CENTER));
        root.addView(preview, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        root.addView(new Overlay(this), new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));

        TextView hint = new TextView(this);
        hint.setText("Наведите камеру на штрихкод");
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(TypedValue.COMPLEX_UNIT_SP, 17);
        hint.setGravity(Gravity.CENTER);
        hint.setShadowLayer(4, 0, 0, Color.BLACK);
        FrameLayout.LayoutParams hp = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.TOP);
        hp.topMargin = dp(48);
        root.addView(hint, hp);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER);
        bar.setPadding(dp(16), dp(16), dp(16), dp(32));
        btnTorch = new Button(this);
        btnTorch.setText("Фонарик");
        btnTorch.setOnClickListener(v -> toggleTorch());
        Button cancel = new Button(this);
        cancel.setText("Отмена");
        cancel.setOnClickListener(v -> { setResult(RESULT_CANCELED); finish(); });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        bp.setMargins(dp(8), 0, dp(8), 0);
        bar.addView(btnTorch, bp);
        bar.addView(cancel, new LinearLayout.LayoutParams(bp));
        root.addView(bar, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));

        setContentView(root);
        holder = surfaceView.getHolder();
        holder.addCallback(this);
    }

    // ---------- жизненный цикл ----------

    @Override
    protected void onResume() {
        super.onResume();
        finished = false;
        if (surfaceReady) startCamera();
    }

    @Override
    protected void onPause() {
        super.onPause();
        stopCamera();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        stopCamera();
        if (decodeThread != null) decodeThread.quitSafely();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode != REQ_CAMERA) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            if (surfaceReady) startCamera();
        } else {
            Toast.makeText(this, "Без доступа к камере сканирование невозможно. Разрешите камеру в настройках телефона.",
                    Toast.LENGTH_LONG).show();
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    @Override public void surfaceCreated(SurfaceHolder h) {
        surfaceReady = true;
        startCamera();
    }

    @Override public void surfaceChanged(SurfaceHolder h, int format, int width, int height) { }

    @Override public void surfaceDestroyed(SurfaceHolder h) {
        surfaceReady = false;
        stopCamera();
    }

    // ---------- камера ----------

    private void startCamera() {
        if (camera != null || finished) return;
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) return;
        try {
            int id = -1;
            Camera.CameraInfo info = new Camera.CameraInfo();
            for (int i = 0; i < Camera.getNumberOfCameras(); i++) {
                Camera.getCameraInfo(i, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) { id = i; break; }
            }
            if (id < 0 && Camera.getNumberOfCameras() > 0) { id = 0; Camera.getCameraInfo(0, info); }
            if (id < 0) { fail("Камера не найдена"); return; }

            camera = Camera.open(id);
            Camera.Parameters p = camera.getParameters();

            Camera.Size best = choosePreviewSize(p.getSupportedPreviewSizes());
            p.setPreviewSize(best.width, best.height);
            previewW = best.width;
            previewH = best.height;

            List<String> focus = p.getSupportedFocusModes();
            useAutoFocusLoop = false;
            if (focus != null && focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            } else if (focus != null && focus.contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_VIDEO);
            } else if (focus != null && focus.contains(Camera.Parameters.FOCUS_MODE_AUTO)) {
                p.setFocusMode(Camera.Parameters.FOCUS_MODE_AUTO);
                useAutoFocusLoop = true;
            }
            try {
                camera.setParameters(p);
            } catch (RuntimeException e) {
                // некоторые камеры не принимают режим фокуса — оставляем только размер
                p = camera.getParameters();
                p.setPreviewSize(best.width, best.height);
                camera.setParameters(p);
            }

            int rotation = getWindowManager().getDefaultDisplay().getRotation();
            int degrees = 0;
            switch (rotation) {
                case Surface.ROTATION_90: degrees = 90; break;
                case Surface.ROTATION_180: degrees = 180; break;
                case Surface.ROTATION_270: degrees = 270; break;
            }
            int result = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT
                    ? (360 - (info.orientation + degrees) % 360) % 360
                    : (info.orientation - degrees + 360) % 360;
            camera.setDisplayOrientation(result);
            fitPreview(result == 90 || result == 270);

            camera.setPreviewDisplay(holder);
            int bufSize = previewW * previewH * 3 / 2;
            camera.addCallbackBuffer(new byte[bufSize]);
            camera.addCallbackBuffer(new byte[bufSize]);
            camera.setPreviewCallbackWithBuffer(this);
            camera.startPreview();
            if (useAutoFocusLoop) ui.postDelayed(autoFocusLoop, 300);

            String flash = p.getFlashMode();
            List<String> flashModes = p.getSupportedFlashModes();
            btnTorch.setVisibility(flashModes != null && flashModes.contains(Camera.Parameters.FLASH_MODE_TORCH)
                    ? View.VISIBLE : View.GONE);
            torchOn = Camera.Parameters.FLASH_MODE_TORCH.equals(flash);
        } catch (Exception e) {
            stopCamera();
            fail("Не удалось открыть камеру: " + e.getMessage());
        }
    }

    private void stopCamera() {
        ui.removeCallbacks(autoFocusLoop);
        if (camera != null) {
            try {
                camera.setPreviewCallbackWithBuffer(null);
                camera.stopPreview();
            } catch (Exception ignored) { }
            try { camera.release(); } catch (Exception ignored) { }
            camera = null;
        }
    }

    private final Runnable autoFocusLoop = new Runnable() {
        @Override public void run() {
            if (camera == null) return;
            try {
                camera.autoFocus((success, cam) -> { });
            } catch (Exception ignored) { }
            ui.postDelayed(this, 2000);
        }
    };

    private static Camera.Size choosePreviewSize(List<Camera.Size> sizes) {
        Camera.Size best = sizes.get(0);
        long bestScore = Long.MAX_VALUE;
        for (Camera.Size s : sizes) {
            long pixels = (long) s.width * s.height;
            // цель — около 1280x720: достаточно для штрихкода и быстро декодируется
            long score = Math.abs(pixels - 1280L * 720L);
            if (Math.max(s.width, s.height) > 1920) score += 10_000_000L;
            if (score < bestScore) { bestScore = score; best = s; }
        }
        return best;
    }

    /** Подгоняет SurfaceView под пропорции превью (заполнение с обрезкой краёв, без искажений). */
    private void fitPreview(final boolean rotated) {
        preview.post(() -> {
            int cw = preview.getWidth(), ch = preview.getHeight();
            if (cw == 0 || ch == 0 || previewW == 0) return;
            float pw = rotated ? previewH : previewW;
            float ph = rotated ? previewW : previewH;
            float scale = Math.max(cw / pw, ch / ph);
            FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                    Math.round(pw * scale), Math.round(ph * scale), Gravity.CENTER);
            surfaceView.setLayoutParams(lp);
        });
    }

    private void toggleTorch() {
        if (camera == null) return;
        try {
            Camera.Parameters p = camera.getParameters();
            torchOn = !torchOn;
            p.setFlashMode(torchOn ? Camera.Parameters.FLASH_MODE_TORCH : Camera.Parameters.FLASH_MODE_OFF);
            camera.setParameters(p);
        } catch (Exception e) {
            Toast.makeText(this, "Фонарик недоступен", Toast.LENGTH_SHORT).show();
        }
    }

    // ---------- распознавание ----------

    @Override
    public void onPreviewFrame(final byte[] data, final Camera cam) {
        if (finished || data == null) return;
        if (decoding) { returnBuffer(cam, data); return; }
        decoding = true;
        final int w = previewW, h = previewH;
        decoder.post(() -> {
            String code = null;
            try {
                code = decode(data, w, h);
            } catch (Throwable ignored) { }
            final String found = code;
            ui.post(() -> {
                decoding = false;
                if (found != null && !finished) {
                    onFound(found);
                } else if (camera == cam) {
                    returnBuffer(cam, data);
                }
            });
        });
    }

    private static void returnBuffer(Camera cam, byte[] data) {
        try { cam.addCallbackBuffer(data); } catch (Exception ignored) { }
    }

    /** Декодирует центральную область кадра; пробует обычную и повёрнутую на 90° ориентацию. */
    private String decode(byte[] yuv, int w, int h) {
        int cw = w * 9 / 10, ch = h * 8 / 10;
        int left = (w - cw) / 2, top = (h - ch) / 2;

        Result r = tryDecode(new PlanarYUVLuminanceSource(yuv, w, h, left, top, cw, ch, false));
        if (r != null) return r.getText();

        // поворот области на 90°: для штрихкода, расположенного «вдоль» экрана в портретном режиме
        byte[] rot = new byte[cw * ch];
        for (int y = 0; y < ch; y++) {
            int src = (top + y) * w + left;
            for (int x = 0; x < cw; x++) {
                rot[x * ch + (ch - 1 - y)] = yuv[src + x];
            }
        }
        r = tryDecode(new PlanarYUVLuminanceSource(rot, ch, cw, 0, 0, ch, cw, false));
        return r != null ? r.getText() : null;
    }

    private Result tryDecode(PlanarYUVLuminanceSource src) {
        try {
            return reader.decodeWithState(new BinaryBitmap(new HybridBinarizer(src)));
        } catch (Exception e) {
            return null;
        } finally {
            reader.reset();
        }
    }

    private void onFound(String code) {
        finished = true;
        try {
            ToneGenerator tg = new ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80);
            tg.startTone(ToneGenerator.TONE_PROP_BEEP, 150);
            ui.postDelayed(tg::release, 300);
        } catch (Exception ignored) { }
        stopCamera();
        setResult(RESULT_OK, new Intent().putExtra(EXTRA_CODE, code.trim()));
        finish();
    }

    private void fail(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_LONG).show();
        setResult(RESULT_CANCELED);
        finish();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    /** Затемнение по краям и рамка-прицел. */
    private static class Overlay extends View {
        private final Paint dim = new Paint();
        private final Paint frame = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint laser = new Paint();

        Overlay(Activity a) {
            super(a);
            dim.setColor(0x66000000);
            frame.setColor(Color.WHITE);
            frame.setStyle(Paint.Style.STROKE);
            frame.setStrokeWidth(4f);
            laser.setColor(0xCCFF3B30);
        }

        @Override
        protected void onDraw(Canvas c) {
            int w = getWidth(), h = getHeight();
            float fw = w * 0.84f, fh = Math.min(h * 0.28f, fw * 0.6f);
            float l = (w - fw) / 2f, t = (h - fh) / 2f;
            RectF r = new RectF(l, t, l + fw, t + fh);
            c.drawRect(0, 0, w, r.top, dim);
            c.drawRect(0, r.bottom, w, h, dim);
            c.drawRect(0, r.top, r.left, r.bottom, dim);
            c.drawRect(r.right, r.top, w, r.bottom, dim);
            c.drawRoundRect(r, 16f, 16f, frame);
            c.drawRect(r.left + 12, r.centerY() - 1.5f, r.right - 12, r.centerY() + 1.5f, laser);
        }
    }
}
