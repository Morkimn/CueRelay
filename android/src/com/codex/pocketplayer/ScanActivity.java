package com.codex.pocketplayer;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.GradientDrawable;
import android.hardware.Camera;
import android.os.Bundle;
import android.view.Gravity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.WindowInsets;
import android.view.Surface;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.PlanarYUVLuminanceSource;
import com.google.zxing.ReaderException;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeReader;

@SuppressWarnings("deprecation")
public final class ScanActivity extends Activity implements SurfaceHolder.Callback, Camera.PreviewCallback {
    private Camera camera;
    private SurfaceHolder surface;
    private SurfaceView preview;
    private FrameLayout root;
    private int width, height, rotation;
    private boolean resumed, finished;
    private final QRCodeReader reader = new QRCodeReader();

    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        root = new FrameLayout(this);
        root.setBackgroundColor(Color.BLACK);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        preview = new SurfaceView(this);
        root.addView(preview, new FrameLayout.LayoutParams(-1, -1, Gravity.CENTER));
        root.addOnLayoutChangeListener((view, left, top, right, bottom, oldLeft, oldTop, oldRight, oldBottom) -> {
            if (right - left != oldRight - oldLeft || bottom - top != oldBottom - oldTop) fitPreview();
        });
        preview.getHolder().addCallback(this);
        root.addView(new ScanFrame(), new FrameLayout.LayoutParams(-1, -1));
        TextView heading = new TextView(this);
        heading.setText(UiText.text(this, "Сканирование QR-кода"));
        heading.setTextColor(Color.WHITE);
        heading.setTextSize(23);
        heading.setTypeface(getResources().getFont(R.font.google_sans_medium));
        heading.setGravity(Gravity.CENTER_HORIZONTAL);
        FrameLayout.LayoutParams header = new FrameLayout.LayoutParams(-1, -2, Gravity.TOP);
        header.setMargins(dp(52), dp(23), dp(52), 0);
        root.addView(heading, header);
        android.widget.ImageView cancel = new android.widget.ImageView(this);
        cancel.setImageResource(R.drawable.ic_close);
        cancel.setColorFilter(Color.WHITE);
        cancel.setContentDescription(UiText.text(this, "Закрыть сканер"));
        cancel.setPadding(dp(13), dp(13), dp(13), dp(13));
        cancel.setBackground(pill(0xcc292c34));
        cancel.setOnClickListener(v -> finish());
        FrameLayout.LayoutParams exit = new FrameLayout.LayoutParams(dp(46), dp(46), Gravity.TOP | Gravity.START);
        exit.setMarginStart(dp(10));
        exit.topMargin = dp(13);
        root.addView(cancel, exit);
        TextView hint = new TextView(this);
        hint.setText(UiText.text(this, "Помести QR-код из окна CueRelay в рамку"));
        hint.setTextColor(Color.WHITE);
        hint.setTextSize(16);
        hint.setTypeface(getResources().getFont(R.font.google_sans_regular));
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(dp(16), dp(12), dp(16), dp(12));
        hint.setBackground(pill(0xe521232b));
        FrameLayout.LayoutParams footer = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
        footer.setMargins(dp(32), 0, dp(32), dp(35));
        root.addView(hint, footer);
        setContentView(root);
        if (android.os.Build.VERSION.SDK_INT >= 35) {
            root.setOnApplyWindowInsetsListener((view, insets) -> {
                android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
                root.setPadding(bars.left, bars.top, bars.right, bars.bottom);
                return insets;
            });
        }
    }

    @Override protected void onResume() { super.onResume(); resumed = true; open(); }
    @Override protected void onPause() { resumed = false; close(); super.onPause(); }
    @Override public void surfaceCreated(SurfaceHolder holder) { surface = holder; open(); }
    @Override public void surfaceDestroyed(SurfaceHolder holder) { surface = null; close(); }
    @Override public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) { }

    private void open() {
        if (!resumed || surface == null || camera != null) return;
        try {
            Camera.CameraInfo info = new Camera.CameraInfo();
            int id = 0;
            for (; id < Camera.getNumberOfCameras(); id++) {
                Camera.getCameraInfo(id, info);
                if (info.facing == Camera.CameraInfo.CAMERA_FACING_BACK) break;
            }
            camera = Camera.open(id < Camera.getNumberOfCameras() ? id : 0);
            Camera.Parameters params = camera.getParameters();
            Camera.Size preferred = params.getPreviewSize();
            for (Camera.Size size : params.getSupportedPreviewSizes()) {
                if (size.width <= 1280 && size.width >= 640 && Math.abs(size.width - 960) < Math.abs(preferred.width - 960))
                    preferred = size;
            }
            params.setPreviewSize(preferred.width, preferred.height);
            params.setPreviewFormat(android.graphics.ImageFormat.NV21);
            if (params.getSupportedFocusModes().contains(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE))
                params.setFocusMode(Camera.Parameters.FOCUS_MODE_CONTINUOUS_PICTURE);
            camera.setParameters(params);
            width = preferred.width;
            height = preferred.height;
            int angle = getWindowManager().getDefaultDisplay().getRotation();
            int degrees = angle == Surface.ROTATION_90 ? 90 : angle == Surface.ROTATION_180 ? 180 :
                angle == Surface.ROTATION_270 ? 270 : 0;
            rotation = info.facing == Camera.CameraInfo.CAMERA_FACING_FRONT ?
                (360 - (info.orientation + degrees) % 360) % 360 : (info.orientation - degrees + 360) % 360;
            camera.setDisplayOrientation(rotation);
            fitPreview();
            camera.setPreviewDisplay(surface);
            camera.startPreview();
            camera.setOneShotPreviewCallback(this);
        } catch (Exception error) {
            close();
            Toast.makeText(this, UiText.text(this, "Не удалось открыть камеру. Код можно вставить вручную."), Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override public void onPreviewFrame(byte[] frame, Camera source) {
        if (!resumed || source != camera || finished || frame == null) return;
        float side = frameSide(root.getWidth() - root.getPaddingLeft() - root.getPaddingRight(),
            root.getHeight() - root.getPaddingTop() - root.getPaddingBottom());
        if (side <= 0 || preview.getWidth() == 0 || preview.getHeight() == 0) {
            camera.setOneShotPreviewCallback(this);
            return;
        }
        float columns = side / (rotation % 180 == 0 ? preview.getWidth() : preview.getHeight());
        float rows = side / (rotation % 180 == 0 ? preview.getHeight() : preview.getWidth());
        int cropWidth = Math.max(1, Math.min(width, Math.round(width * columns)));
        int cropHeight = Math.max(1, Math.min(height, Math.round(height * rows)));
        Rect crop = new Rect((width - cropWidth) / 2, (height - cropHeight) / 2,
            (width - cropWidth) / 2 + cropWidth, (height - cropHeight) / 2 + cropHeight);
        PlanarYUVLuminanceSource image = new PlanarYUVLuminanceSource(frame, width, height,
            crop.left, crop.top, crop.width(), crop.height(), false);
        try {
            String code = reader.decode(new BinaryBitmap(new HybridBinarizer(image))).getText();
            Pairing.fromQr(code);
            finished = true;
            setResult(RESULT_OK, new Intent().putExtra("code", code));
            finish();
        } catch (ReaderException | IllegalArgumentException ignored) {
            if (camera != null) camera.setOneShotPreviewCallback(this);
        } finally { reader.reset(); }
    }

    private void close() {
        if (camera == null) return;
        camera.setPreviewCallback(null);
        camera.stopPreview();
        camera.release();
        camera = null;
    }

    private void fitPreview() {
        int areaWidth = root.getWidth() - root.getPaddingLeft() - root.getPaddingRight();
        int areaHeight = root.getHeight() - root.getPaddingTop() - root.getPaddingBottom();
        if (width <= 0 || areaWidth <= 0 || areaHeight <= 0) return;
        int orientedWidth = rotation % 180 == 0 ? width : height;
        int orientedHeight = rotation % 180 == 0 ? height : width;
        float scale = Math.max((float) areaWidth / orientedWidth, (float) areaHeight / orientedHeight);
        preview.setLayoutParams(new FrameLayout.LayoutParams(Math.round(orientedWidth * scale),
            Math.round(orientedHeight * scale), Gravity.CENTER));
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }

    private float frameSide(int w, int h) { return Math.min(dp(310), Math.min(w * .77f, h * .62f)); }

    private GradientDrawable pill(int color) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(24));
        return shape;
    }

    private final class ScanFrame extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path mask = new Path();
        private final RectF target = new RectF();

        ScanFrame() { super(ScanActivity.this); }

        @Override protected void onDraw(Canvas canvas) {
            float side = frameSide(getWidth(), getHeight());
            float left = (getWidth() - side) / 2, top = (getHeight() - side) / 2;
            target.set(left, top, left + side, top + side);
            mask.reset();
            mask.setFillType(Path.FillType.EVEN_ODD);
            mask.addRect(0, 0, getWidth(), getHeight(), Path.Direction.CW);
            mask.addRoundRect(target, dp(15), dp(15), Path.Direction.CW);
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xbb101219);
            canvas.drawPath(mask, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(dp(3));
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setColor(Color.WHITE);
            float radius = dp(15), length = dp(36);
            for (int i = 0; i < 4; ++i) {
                canvas.save();
                canvas.rotate(i * 90f, target.centerX(), target.centerY());
                Path corner = new Path();
                corner.moveTo(left, top + length);
                corner.lineTo(left, top + radius);
                corner.quadTo(left, top, left + radius, top);
                corner.lineTo(left + length, top);
                canvas.drawPath(corner, paint);
                canvas.restore();
            }
        }
    }
}
