package com.tyranor.runtime.systemnnn;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/**
 * Pure Android SDK (API 26+) dark Emberfall UI toolkit.
 * Zero AndroidX/Material dependencies and zero XML layout requirements.
 */
final class EmberfallUi {
    static final int BG_WINDOW = 0xFF0E1016;
    static final int BG_TOPBAR = 0xFF13161F;
    static final int BG_CARD = 0xFF181C27;
    static final int BG_CARD_PRESSED = 0xFF202636;
    static final int BG_SURFACE = 0xFF151923;
    static final int BG_INPUT = 0xFF10131C;
    static final int BG_CHIP = 0xFF202534;
    static final int BORDER_SUBTLE = 0xFF262C3D;
    static final int BORDER_CARD = 0xFF2A3144;
    static final int ACCENT = 0xFFFF6400;
    static final int ACCENT_DARK = 0xFFD93C00;
    static final int ACCENT_SOFT = 0x26FF6400;
    static final int VIOLET_BADGE = 0xFF4A236B;
    static final int TEXT_PRIMARY = 0xFFF3F5FA;
    static final int TEXT_SECONDARY = 0xFF9AA3B8;
    static final int TEXT_MUTED = 0xFF6E778D;
    static final int DANGER = 0xFFE5484D;
    static final int DANGER_BG = 0x22E5484D;

    private static final int RIPPLE_LIGHT = 0x26FFFFFF;
    private static final int RIPPLE_CARD = 0x1FFFFFFF;
    private static final int RIPPLE_ACCENT = 0x40FFFFFF;
    private static final int RIPPLE_DANGER = 0x33E5484D;
    private static final int BORDER_ACCENT_RIM = 0x55FFA64D;
    private static final int BORDER_DANGER = 0x55E5484D;
    private static final int DANGER_TEXT = 0xFFFCA5A5;

    interface SizeListener {
        void onSizeSelected(int size);
    }

    private EmberfallUi() {}

    // =========================================================================
    // 1. Dimension & Drawable Helpers
    // =========================================================================

    static int dp(Context c, float dp) {
        return Math.round(dp * c.getResources().getDisplayMetrics().density);
    }

    private static float dpF(Context c, float dp) {
        return dp * c.getResources().getDisplayMetrics().density;
    }

    static Drawable roundedRect(int fillColor, int strokeColor, int strokePx, float radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(fillColor);
        d.setCornerRadius(radiusPx);
        if (strokePx > 0 && strokeColor != 0) {
            d.setStroke(strokePx, strokeColor);
        }
        return d;
    }

    static Drawable rippleRounded(int fillColor, int rippleColor, int strokeColor, int strokePx, float radiusPx) {
        Drawable content = (Color.alpha(fillColor) == 0 && (strokePx <= 0 || strokeColor == 0))
            ? null
            : roundedRect(fillColor, strokeColor, strokePx, radiusPx);
        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(radiusPx);
        return new RippleDrawable(ColorStateList.valueOf(rippleColor), content, mask);
    }

    static Drawable accentButtonBg(Context c, float radiusDp) {
        float radiusPx = dpF(c, radiusDp);
        GradientDrawable gradient = new GradientDrawable(
            GradientDrawable.Orientation.TL_BR,
            new int[]{ACCENT, ACCENT_DARK}
        );
        gradient.setShape(GradientDrawable.RECTANGLE);
        gradient.setCornerRadius(radiusPx);
        gradient.setStroke(dp(c, 1), BORDER_ACCENT_RIM);

        GradientDrawable mask = new GradientDrawable();
        mask.setShape(GradientDrawable.RECTANGLE);
        mask.setColor(Color.WHITE);
        mask.setCornerRadius(radiusPx);
        return new RippleDrawable(ColorStateList.valueOf(RIPPLE_ACCENT), gradient, mask);
    }

    static Drawable secondaryButtonBg(Context c, float radiusDp) {
        return rippleRounded(BG_CHIP, RIPPLE_LIGHT, BORDER_CARD, dp(c, 1), dpF(c, radiusDp));
    }

    static Drawable dangerButtonBg(Context c, float radiusDp) {
        return rippleRounded(DANGER_BG, RIPPLE_DANGER, BORDER_DANGER, dp(c, 1), dpF(c, radiusDp));
    }

    static Drawable cardBg(Context c, float radiusDp) {
        return rippleRounded(BG_CARD, RIPPLE_CARD, BORDER_CARD, dp(c, 1), dpF(c, radiusDp));
    }

    static Drawable iconButtonBg(Context c, float radiusDp) {
        return rippleRounded(Color.TRANSPARENT, RIPPLE_LIGHT, 0, 0, dpF(c, radiusDp));
    }

    static Drawable floatingPillBg(Context c) {
        return rippleRounded(0xE6151923, 0x40FF6400, 0x44FFFFFF, dp(c, 1), dpF(c, 20));
    }

    // =========================================================================
    // 2. Custom Canvas-Drawn Vector Drawables
    // =========================================================================

    static Drawable emberLogoIcon(Context c, int sizeDp) {
        final int sizePx = Math.max(1, dp(c, sizeDp));
        return new VectorDrawable(sizePx, sizePx) {
            private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint flamePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF box = new RectF();
            private final Path outerFlame = buildOuterFlamePath();
            private final Path midFlame = buildMidFlamePath();
            private final Path coreFlame = buildCoreFlamePath();
            private float cachedLeft = Float.NaN, cachedTop, cachedSide;
            private Shader cachedBgShader;

            @Override
            public void draw(Canvas canvas) {
                Rect b = getBounds();
                if (b.isEmpty()) return;
                float side = Math.min(b.width(), b.height());
                float left = b.exactCenterX() - side * 0.5f;
                float top = b.exactCenterY() - side * 0.5f;
                float stroke = Math.max(1f, side * (1f / 26f));
                float inset = stroke * 0.5f;
                box.set(left + inset, top + inset, left + side - inset, top + side - inset);
                float radius = side * 0.27f;

                if (cachedBgShader == null || cachedLeft != box.left || cachedTop != box.top || cachedSide != side) {
                    cachedLeft = box.left;
                    cachedTop = box.top;
                    cachedSide = side;
                    cachedBgShader = new LinearGradient(
                        box.left, box.top, box.right, box.bottom,
                        0xFF251536, 0xFF171824, Shader.TileMode.CLAMP
                    );
                }
                bgPaint.setShader(cachedBgShader);
                bgPaint.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(box, radius, radius, bgPaint);

                strokePaint.setStyle(Paint.Style.STROKE);
                strokePaint.setStrokeWidth(stroke);
                strokePaint.setColor(VIOLET_BADGE);
                canvas.drawRoundRect(box, radius, radius, strokePaint);

                float pad = side * 0.16f;
                float inner = side - pad * 2f;
                float scale = inner / 24f;

                canvas.save();
                canvas.translate(left + pad, top + pad);
                canvas.scale(scale, scale);
                flamePaint.setStyle(Paint.Style.FILL);
                flamePaint.setColor(0xFFFF8C24);
                canvas.drawPath(outerFlame, flamePaint);
                flamePaint.setColor(ACCENT);
                canvas.drawPath(midFlame, flamePaint);
                flamePaint.setColor(0xFFFFC247);
                canvas.drawPath(coreFlame, flamePaint);
                canvas.restore();
            }
        };
    }

    static Drawable gamepadPlaceholderIcon(Context c, int sizeDp) {
        final int sizePx = Math.max(1, dp(c, sizeDp));
        return new VectorDrawable(sizePx, sizePx) {
            private final Paint fillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint strokePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final RectF box = new RectF();
            private final Path bodyPath = buildGamepadBodyPath();
            private float cachedLeft = Float.NaN, cachedTop, cachedSide;
            private Shader cachedBgShader;

            @Override
            public void draw(Canvas canvas) {
                Rect b = getBounds();
                if (b.isEmpty()) return;
                float side = Math.min(b.width(), b.height());
                float left = b.exactCenterX() - side * 0.5f;
                float top = b.exactCenterY() - side * 0.5f;
                float scale = side / 48f;
                float stroke = Math.max(1f, 1.1f * scale);
                float inset = stroke * 0.5f;
                box.set(left + inset, top + inset, left + side - inset, top + side - inset);
                float radius = side * (11f / 48f);

                if (cachedBgShader == null || cachedLeft != box.left || cachedTop != box.top || cachedSide != side) {
                    cachedLeft = box.left;
                    cachedTop = box.top;
                    cachedSide = side;
                    cachedBgShader = new LinearGradient(
                        box.left, box.top, box.right, box.bottom,
                        0xFF242B3D, 0xFF1A1F2D, Shader.TileMode.CLAMP
                    );
                }
                fillPaint.setShader(cachedBgShader);
                fillPaint.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(box, radius, radius, fillPaint);
                fillPaint.setShader(null);

                strokePaint.setStyle(Paint.Style.STROKE);
                strokePaint.setStrokeWidth(stroke);
                strokePaint.setColor(0xFF323B52);
                canvas.drawRoundRect(box, radius, radius, strokePaint);

                canvas.save();
                canvas.translate(left, top);
                canvas.scale(scale, scale);

                // Subtle controller interior tint
                fillPaint.setStyle(Paint.Style.FILL);
                fillPaint.setColor(0xFF283044);
                canvas.drawPath(bodyPath, fillPaint);

                // Crisp controller contour
                strokePaint.setStyle(Paint.Style.STROKE);
                strokePaint.setStrokeCap(Paint.Cap.ROUND);
                strokePaint.setStrokeJoin(Paint.Join.ROUND);
                strokePaint.setStrokeWidth(1.65f);
                strokePaint.setColor(0xFF94A1BA);
                canvas.drawPath(bodyPath, strokePaint);

                // Center guide bar
                strokePaint.setStrokeWidth(1.2f);
                strokePaint.setColor(0xFF6E778D);
                canvas.drawLine(22.2f, 20.6f, 25.8f, 20.6f, strokePaint);

                // Left D-pad
                strokePaint.setStrokeWidth(1.75f);
                strokePaint.setColor(0xFFC4CEE0);
                canvas.drawLine(14.0f, 22.8f, 18.6f, 22.8f, strokePaint);
                canvas.drawLine(16.3f, 20.5f, 16.3f, 25.1f, strokePaint);

                // Right 4 action dots
                fillPaint.setColor(0xFFC4CEE0);
                canvas.drawCircle(31.6f, 20.4f, 1.05f, fillPaint);
                canvas.drawCircle(29.2f, 22.8f, 1.05f, fillPaint);
                canvas.drawCircle(31.6f, 25.2f, 1.05f, fillPaint);
                fillPaint.setColor(0xDDFF7A29);
                canvas.drawCircle(34.0f, 22.8f, 1.05f, fillPaint);

                canvas.restore();
            }
        };
    }

    static Drawable gearIcon(Context c, int sizeDp, final int color) {
        final int sizePx = Math.max(1, dp(c, sizeDp));
        return new VectorDrawable(sizePx, sizePx) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path gearPath = buildGearPath();

            @Override
            public void draw(Canvas canvas) {
                Rect b = getBounds();
                if (b.isEmpty()) return;
                float side = Math.min(b.width(), b.height());
                float left = b.exactCenterX() - side * 0.5f;
                float top = b.exactCenterY() - side * 0.5f;
                float scale = side / 24f;

                canvas.save();
                canvas.translate(left, top);
                canvas.scale(scale, scale);

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.85f);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setColor(color);
                canvas.drawPath(gearPath, paint);
                canvas.drawCircle(12f, 12f, 2.9f, paint);

                canvas.restore();
            }
        };
    }

    static Drawable plusIcon(Context c, int sizeDp, final int color) {
        final int sizePx = Math.max(1, dp(c, sizeDp));
        return new VectorDrawable(sizePx, sizePx) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            public void draw(Canvas canvas) {
                Rect b = getBounds();
                if (b.isEmpty()) return;
                float side = Math.min(b.width(), b.height());
                float left = b.exactCenterX() - side * 0.5f;
                float top = b.exactCenterY() - side * 0.5f;
                float scale = side / 24f;

                canvas.save();
                canvas.translate(left, top);
                canvas.scale(scale, scale);

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2.4f);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setColor(color);
                canvas.drawLine(12f, 5f, 12f, 19f, paint);
                canvas.drawLine(5f, 12f, 19f, 12f, paint);

                canvas.restore();
            }
        };
    }

    static Drawable closeIcon(Context c, int sizeDp, final int color) {
        final int sizePx = Math.max(1, dp(c, sizeDp));
        return new VectorDrawable(sizePx, sizePx) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

            @Override
            public void draw(Canvas canvas) {
                Rect b = getBounds();
                if (b.isEmpty()) return;
                float side = Math.min(b.width(), b.height());
                float left = b.exactCenterX() - side * 0.5f;
                float top = b.exactCenterY() - side * 0.5f;
                float scale = side / 24f;

                canvas.save();
                canvas.translate(left, top);
                canvas.scale(scale, scale);

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(2.2f);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setColor(color);
                canvas.drawLine(6f, 6f, 18f, 18f, paint);
                canvas.drawLine(18f, 6f, 6f, 18f, paint);

                canvas.restore();
            }
        };
    }

    static Drawable folderLockIcon(Context c, int sizeDp, final int color) {
        final int sizePx = Math.max(1, dp(c, sizeDp));
        return new VectorDrawable(sizePx, sizePx) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Path folderPath = buildFolderPath();
            private final RectF shackleArc = new RectF(10.1f, 9.8f, 13.9f, 13.6f);
            private final RectF lockBody = new RectF(9.2f, 12.2f, 14.8f, 16.5f);

            @Override
            public void draw(Canvas canvas) {
                Rect b = getBounds();
                if (b.isEmpty()) return;
                float side = Math.min(b.width(), b.height());
                float left = b.exactCenterX() - side * 0.5f;
                float top = b.exactCenterY() - side * 0.5f;
                float scale = side / 24f;

                canvas.save();
                canvas.translate(left, top);
                canvas.scale(scale, scale);

                paint.setStyle(Paint.Style.FILL);
                paint.setColor((color & 0x00FFFFFF) | 0x1A000000);
                canvas.drawPath(folderPath, paint);

                paint.setStyle(Paint.Style.STROKE);
                paint.setStrokeWidth(1.8f);
                paint.setStrokeCap(Paint.Cap.ROUND);
                paint.setStrokeJoin(Paint.Join.ROUND);
                paint.setColor(color);
                canvas.drawPath(folderPath, paint);

                paint.setStrokeWidth(1.5f);
                canvas.drawArc(shackleArc, 180f, 180f, false, paint);

                paint.setStyle(Paint.Style.FILL);
                canvas.drawRoundRect(lockBody, 1.1f, 1.1f, paint);

                canvas.restore();
            }
        };
    }

    static Drawable fontIcon(Context c, int sizeDp, final int color) {
        final int sizePx = Math.max(1, dp(c, sizeDp));
        return new VectorDrawable(sizePx, sizePx) {
            private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
            private final Rect textBounds = new Rect();

            {
                paint.setColor(color);
                paint.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
                paint.setTextAlign(Paint.Align.LEFT);
            }

            @Override
            public void draw(Canvas canvas) {
                Rect b = getBounds();
                if (b.isEmpty()) return;
                float side = Math.min(b.width(), b.height());
                paint.setTextSize(side * 0.64f);
                paint.getTextBounds("Aa", 0, 2, textBounds);
                float x = b.exactCenterX() - textBounds.exactCenterX();
                float y = b.exactCenterY() - textBounds.exactCenterY();
                canvas.drawText("Aa", x, y, paint);
            }
        };
    }

    static Drawable roundedIconDrawable(Context c, final Bitmap bitmap, final float radiusDp) {
        if (bitmap == null || bitmap.isRecycled()) {
            return gamepadPlaceholderIcon(c, 56);
        }
        final int defaultSizePx = Math.max(1, dp(c, 56));
        final float radiusPx = dpF(c, radiusDp);
        final float strokePx = Math.max(1f, dpF(c, 1));
        return new VectorDrawable(defaultSizePx, defaultSizePx) {
            private final Paint bgPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint borderPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
            private final Paint bmpPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            private final BitmapShader shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            private final Matrix matrix = new Matrix();
            private final RectF frameRect = new RectF();
            private final RectF iconRect = new RectF();

            @Override
            public void draw(Canvas canvas) {
                Rect b = getBounds();
                if (b.isEmpty() || bitmap.isRecycled()) return;
                float side = Math.min(b.width(), b.height());
                float left = b.exactCenterX() - side * 0.5f;
                float top = b.exactCenterY() - side * 0.5f;
                float inset = strokePx * 0.5f;
                frameRect.set(left + inset, top + inset, left + side - inset, top + side - inset);

                bgPaint.setStyle(Paint.Style.FILL);
                bgPaint.setColor(0xFF12151E);
                canvas.drawRoundRect(frameRect, radiusPx, radiusPx, bgPaint);

                float pad = side * 0.08f;
                float innerSide = Math.max(1f, side - pad * 2f);
                int bw = bitmap.getWidth();
                int bh = bitmap.getHeight();
                if (bw > 0 && bh > 0) {
                    float scale = Math.min(innerSide / bw, innerSide / bh);
                    float drawW = bw * scale;
                    float drawH = bh * scale;
                    float drawLeft = b.exactCenterX() - drawW * 0.5f;
                    float drawTop = b.exactCenterY() - drawH * 0.5f;

                    matrix.reset();
                    matrix.setScale(scale, scale);
                    matrix.postTranslate(drawLeft, drawTop);
                    shader.setLocalMatrix(matrix);
                    bmpPaint.setShader(shader);

                    iconRect.set(drawLeft, drawTop, drawLeft + drawW, drawTop + drawH);
                    float innerRadius = Math.max(0f, radiusPx - pad * 0.5f);
                    canvas.drawRoundRect(iconRect, innerRadius, innerRadius, bmpPaint);
                }

                borderPaint.setStyle(Paint.Style.STROKE);
                borderPaint.setStrokeWidth(strokePx);
                borderPaint.setColor(0xFF2B3144);
                canvas.drawRoundRect(frameRect, radiusPx, radiusPx, borderPaint);
            }
        };
    }

    // =========================================================================
    // 3. Reusable Modern UI Builders
    // =========================================================================

    static TextView text(Context c, String content, float sp, int color, boolean bold) {
        TextView tv = new TextView(c);
        tv.setText(content);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        tv.setTextColor(color);
        tv.setTypeface(Typeface.create(Typeface.SANS_SERIF, bold ? Typeface.BOLD : Typeface.NORMAL));
        tv.setIncludeFontPadding(false);
        return tv;
    }

    static TextView primaryButton(Context c, String label, Drawable leadingIcon, View.OnClickListener onClick) {
        return actionButton(c, label, leadingIcon, accentButtonBg(c, 10f), Color.WHITE, 13.5f, 16, onClick);
    }

    static TextView secondaryButton(Context c, String label, Drawable leadingIcon, View.OnClickListener onClick) {
        return actionButton(c, label, leadingIcon, secondaryButtonBg(c, 10f), TEXT_PRIMARY, 13f, 14, onClick);
    }

    static TextView dangerButton(Context c, String label, View.OnClickListener onClick) {
        return actionButton(c, label, null, dangerButtonBg(c, 10f), DANGER_TEXT, 13f, 14, onClick);
    }

    private static TextView actionButton(
        Context c,
        String label,
        Drawable leadingIcon,
        Drawable bg,
        int textColor,
        float sp,
        int horizPadDp,
        View.OnClickListener onClick
    ) {
        TextView btn = new TextView(c);
        btn.setText(label);
        btn.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        btn.setTextColor(textColor);
        btn.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        btn.setIncludeFontPadding(false);
        btn.setAllCaps(false);
        btn.setSingleLine(true);
        btn.setEllipsize(TextUtils.TruncateAt.END);
        btn.setGravity(Gravity.CENTER);
        btn.setMinHeight(dp(c, 40));
        btn.setMinimumHeight(dp(c, 40));
        btn.setPadding(dp(c, horizPadDp), dp(c, 8), dp(c, horizPadDp), dp(c, 8));
        btn.setBackground(bg);
        btn.setSoundEffectsEnabled(false);
        btn.setClickable(true);
        btn.setFocusable(true);
        if (leadingIcon != null) {
            btn.setCompoundDrawablesRelativeWithIntrinsicBounds(leadingIcon, null, null, null);
            btn.setCompoundDrawablePadding(dp(c, 6));
        }
        if (onClick != null) {
            btn.setOnClickListener(onClick);
        }
        return btn;
    }

    static ImageButton iconButton(
        Context c,
        Drawable icon,
        String contentDescription,
        int sizeDp,
        View.OnClickListener onClick
    ) {
        ImageButton btn = new ImageButton(c);
        int sizePx = dp(c, sizeDp);
        btn.setLayoutParams(new ViewGroup.LayoutParams(sizePx, sizePx));
        btn.setMinimumWidth(sizePx);
        btn.setMinimumHeight(sizePx);
        btn.setPadding(0, 0, 0, 0);
        btn.setScaleType(ImageView.ScaleType.CENTER);
        btn.setImageDrawable(icon);
        btn.setBackground(iconButtonBg(c, 9f));
        btn.setContentDescription(contentDescription);
        btn.setSoundEffectsEnabled(false);
        if (onClick != null) {
            btn.setOnClickListener(onClick);
        }
        return btn;
    }

    static void styleFloatingActionButton(Button button) {
        Context c = button.getContext();
        button.setBackground(floatingPillBg(c));
        button.setTextColor(TEXT_PRIMARY);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        button.setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
        button.setAllCaps(false);
        button.setIncludeFontPadding(false);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setMinWidth(0);
        button.setMinimumWidth(0);
        button.setMinHeight(dp(c, 36));
        button.setMinimumHeight(dp(c, 36));
        button.setPadding(dp(c, 13), dp(c, 7), dp(c, 15), dp(c, 7));
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(fontIcon(c, 16, ACCENT), null, null, null);
        button.setCompoundDrawablePadding(dp(c, 7));
        button.setElevation(dpF(c, 4));
        button.setStateListAnimator(null);
        button.setSoundEffectsEnabled(false);
        ViewGroup.LayoutParams lp = button.getLayoutParams();
        if (lp != null && lp.height > 0) {
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            button.setLayoutParams(lp);
        }
    }

    @SuppressWarnings("deprecation")
    static AlertDialog createModalDialog(
        final Activity activity,
        View contentView,
        boolean cancelable,
        final int maxWidthDp
    ) {
        FrameLayout card = new FrameLayout(activity);
        card.setBackground(roundedRect(BG_SURFACE, BORDER_SUBTLE, dp(activity, 1), dpF(activity, 16)));
        int padH = dp(activity, 18);
        int padV = dp(activity, 16);
        card.setPadding(padH, padV, padH, padV);

        ScrollView scroll = new ScrollView(activity);
        scroll.setFillViewport(true);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.addView(contentView, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        card.addView(scroll, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        final AlertDialog dialog = new AlertDialog.Builder(activity)
            .setView(card)
            .setCancelable(cancelable)
            .create();
        dialog.setCanceledOnTouchOutside(cancelable);

        Window preWindow = dialog.getWindow();
        if (preWindow != null) {
            preWindow.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
            preWindow.setDimAmount(0.72f);
            preWindow.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            preWindow.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }

        dialog.setOnShowListener(d -> {
            Window window = dialog.getWindow();
            if (window == null) return;
            window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));

            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            View activityDecor = activity.getWindow() != null ? activity.getWindow().getDecorView() : null;
            int screenW = activityDecor != null && activityDecor.getWidth() > 0 ? activityDecor.getWidth() : dm.widthPixels;
            int availW = Math.max(dp(activity, 240), screenW - dp(activity, 32));
            int targetW = Math.min(dp(activity, maxWidthDp), availW);
            window.setLayout(targetW, ViewGroup.LayoutParams.WRAP_CONTENT);

            window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            View dialogDecor = window.getDecorView();
            if (Build.VERSION.SDK_INT >= 30) {
                window.setDecorFitsSystemWindows(false);
                WindowInsetsController controller = dialogDecor.getWindowInsetsController();
                if (controller != null) {
                    controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
                    controller.hide(WindowInsets.Type.systemBars());
                }
            }
            dialogDecor.setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY | View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            );
        });

        return dialog;
    }

    static LinearLayout buildModalHeader(Context c, String title, final Runnable onClose) {
        LinearLayout header = new LinearLayout(c);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView titleView = text(c, title, 17f, TEXT_PRIMARY, true);
        titleView.setSingleLine(true);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f
        );
        if (onClose != null) {
            titleLp.setMarginEnd(dp(c, 10));
        }
        header.addView(titleView, titleLp);

        if (onClose != null) {
            ImageButton closeBtn = iconButton(c, closeIcon(c, 15, TEXT_SECONDARY), "Close", 30, v -> onClose.run());
            closeBtn.setBackground(rippleRounded(BG_CHIP, RIPPLE_LIGHT, BORDER_SUBTLE, dp(c, 1), dpF(c, 8)));
            header.addView(closeBtn);
        }
        return header;
    }

    static LinearLayout buildReadingSizeSelector(
        final Context c,
        int currentSize,
        final SizeListener onSelected
    ) {
        final int[] sizes = PlayerSettings.READING_SIZES;
        final LinearLayout row = new LinearLayout(c);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setLayoutParams(new LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        final LinearLayout[] chips = new LinearLayout[sizes.length];
        final TextView[] primaryLabels = new TextView[sizes.length];
        final TextView[] subLabels = new TextView[sizes.length];

        for (int i = 0; i < sizes.length; i++) {
            final int size = sizes[i];
            LinearLayout chip = new LinearLayout(c);
            chip.setOrientation(LinearLayout.VERTICAL);
            chip.setGravity(Gravity.CENTER);
            chip.setPadding(dp(c, 4), dp(c, 6), dp(c, 4), dp(c, 6));
            chip.setClickable(true);
            chip.setFocusable(true);
            chip.setSoundEffectsEnabled(false);

            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, dp(c, 44), 1f);
            if (i < sizes.length - 1) {
                lp.setMarginEnd(dp(c, 6));
            }
            chip.setLayoutParams(lp);

            TextView pctView = text(c, size + "%", 13f, TEXT_SECONDARY, false);
            pctView.setGravity(Gravity.CENTER);
            pctView.setSingleLine(true);
            chip.addView(pctView);

            TextView subView = null;
            if (size == PlayerSettings.DEFAULT_READING_SIZE) {
                subView = text(c, "Default", 9.5f, TEXT_MUTED, false);
            } else if (size == 100) {
                subView = text(c, "Original", 9.5f, TEXT_MUTED, false);
            }
            if (subView != null) {
                subView.setGravity(Gravity.CENTER);
                subView.setSingleLine(true);
                LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                );
                subLp.topMargin = dp(c, 2);
                chip.addView(subView, subLp);
            }

            chips[i] = chip;
            primaryLabels[i] = pctView;
            subLabels[i] = subView;

            final int index = i;
            chip.setOnClickListener(v -> {
                applyChipSelection(c, sizes, chips, primaryLabels, subLabels, index);
                if (onSelected != null) {
                    onSelected.onSizeSelected(size);
                }
            });

            row.addView(chip);
        }

        int initialIndex = 0;
        for (int i = 0; i < sizes.length; i++) {
            if (sizes[i] == currentSize) {
                initialIndex = i;
                break;
            }
        }
        applyChipSelection(c, sizes, chips, primaryLabels, subLabels, initialIndex);

        return row;
    }

    private static void applyChipSelection(
        Context c,
        int[] sizes,
        LinearLayout[] chips,
        TextView[] primaryLabels,
        TextView[] subLabels,
        int activeIndex
    ) {
        float radiusPx = dpF(c, 9f);
        int strokePx = dp(c, 1);
        for (int i = 0; i < sizes.length; i++) {
            boolean active = i == activeIndex;
            if (active) {
                chips[i].setBackground(rippleRounded(ACCENT, RIPPLE_ACCENT, BORDER_ACCENT_RIM, strokePx, radiusPx));
                primaryLabels[i].setTextColor(Color.WHITE);
                primaryLabels[i].setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD));
                if (subLabels[i] != null) {
                    subLabels[i].setTextColor(0xE6FFFFFF);
                }
            } else {
                chips[i].setBackground(rippleRounded(BG_CHIP, RIPPLE_LIGHT, BORDER_CARD, strokePx, radiusPx));
                primaryLabels[i].setTextColor(TEXT_SECONDARY);
                primaryLabels[i].setTypeface(Typeface.create(Typeface.SANS_SERIF, Typeface.NORMAL));
                if (subLabels[i] != null) {
                    subLabels[i].setTextColor(TEXT_MUTED);
                }
            }
        }
    }

    // =========================================================================
    // Vector Path Builders & Base Drawable
    // =========================================================================

    private static Path buildOuterFlamePath() {
        Path p = new Path();
        p.moveTo(13.8f, 2.2f);
        p.cubicTo(13.8f, 2.2f, 15.1f, 6.1f, 12.2f, 9.4f);
        p.cubicTo(10.9f, 10.9f, 10.7f, 12.5f, 11.4f, 13.7f);
        p.cubicTo(12.3f, 12.4f, 13.8f, 11.8f, 14.9f, 11.4f);
        p.cubicTo(14.7f, 13.4f, 15.8f, 14.9f, 16.9f, 16.3f);
        p.cubicTo(18.2f, 17.9f, 18.2f, 20.6f, 15.6f, 21.8f);
        p.cubicTo(18.8f, 21.2f, 20.8f, 18.3f, 20.2f, 14.7f);
        p.cubicTo(19.7f, 11.7f, 17.2f, 9.9f, 17.2f, 7.2f);
        p.cubicTo(15.5f, 8.1f, 14.5f, 9.1f, 14.5f, 9.1f);
        p.cubicTo(15.7f, 6.5f, 14.8f, 3.6f, 13.8f, 2.2f);
        p.close();
        return p;
    }

    private static Path buildMidFlamePath() {
        Path p = new Path();
        p.moveTo(12.5f, 4.2f);
        p.cubicTo(9.2f, 7.4f, 5.5f, 10.5f, 5.5f, 15.1f);
        p.cubicTo(5.5f, 19.1f, 8.4f, 22.0f, 12.2f, 22.0f);
        p.cubicTo(15.2f, 22.0f, 17.5f, 20.1f, 17.5f, 17.3f);
        p.cubicTo(17.5f, 14.9f, 15.9f, 13.5f, 14.6f, 12.1f);
        p.cubicTo(13.6f, 14.2f, 11.7f, 14.9f, 10.7f, 13.7f);
        p.cubicTo(9.6f, 12.3f, 11.6f, 8.8f, 12.5f, 4.2f);
        p.close();
        return p;
    }

    private static Path buildCoreFlamePath() {
        Path p = new Path();
        p.moveTo(12.1f, 15.2f);
        p.cubicTo(10.3f, 16.3f, 9.7f, 18.3f, 10.6f, 19.9f);
        p.cubicTo(11.4f, 21.2f, 13.4f, 21.6f, 14.7f, 20.6f);
        p.cubicTo(15.9f, 19.6f, 15.9f, 17.6f, 14.6f, 16.6f);
        p.cubicTo(13.9f, 16.1f, 13.8f, 15.1f, 14.1f, 14.3f);
        p.cubicTo(13.3f, 14.6f, 12.6f, 14.9f, 12.1f, 15.2f);
        p.close();
        return p;
    }

    private static Path buildGamepadBodyPath() {
        Path p = new Path();
        p.moveTo(17.5f, 16.5f);
        p.lineTo(30.5f, 16.5f);
        p.cubicTo(35.0f, 16.5f, 37.8f, 19.0f, 38.6f, 24.0f);
        p.cubicTo(39.4f, 28.8f, 38.0f, 32.8f, 35.0f, 33.2f);
        p.cubicTo(32.8f, 33.5f, 31.0f, 31.2f, 29.4f, 29.0f);
        p.cubicTo(28.4f, 27.6f, 27.2f, 27.2f, 24.0f, 27.2f);
        p.cubicTo(20.8f, 27.2f, 19.6f, 27.6f, 18.6f, 29.0f);
        p.cubicTo(17.0f, 31.2f, 15.2f, 33.5f, 13.0f, 33.2f);
        p.cubicTo(10.0f, 32.8f, 8.6f, 28.8f, 9.4f, 24.0f);
        p.cubicTo(10.2f, 19.0f, 13.0f, 16.5f, 17.5f, 16.5f);
        p.close();
        return p;
    }

    private static Path buildGearPath() {
        Path p = new Path();
        final int teeth = 8;
        final float outerR = 9.2f;
        final float innerR = 6.7f;
        for (int i = 0; i < teeth; i++) {
            double center = i * (2.0 * Math.PI / teeth);
            double step = (2.0 * Math.PI / teeth);
            double a0 = center - step * 0.34;
            double a1 = center - step * 0.16;
            double a2 = center + step * 0.16;
            double a3 = center + step * 0.34;

            float x0 = 12f + (float) (innerR * Math.cos(a0));
            float y0 = 12f + (float) (innerR * Math.sin(a0));
            float x1 = 12f + (float) (outerR * Math.cos(a1));
            float y1 = 12f + (float) (outerR * Math.sin(a1));
            float x2 = 12f + (float) (outerR * Math.cos(a2));
            float y2 = 12f + (float) (outerR * Math.sin(a2));
            float x3 = 12f + (float) (innerR * Math.cos(a3));
            float y3 = 12f + (float) (innerR * Math.sin(a3));

            if (i == 0) {
                p.moveTo(x0, y0);
            } else {
                p.lineTo(x0, y0);
            }
            p.lineTo(x1, y1);
            p.lineTo(x2, y2);
            p.lineTo(x3, y3);
        }
        p.close();
        return p;
    }

    private static Path buildFolderPath() {
        Path p = new Path();
        p.moveTo(3f, 7f);
        p.cubicTo(3f, 5.9f, 3.9f, 5f, 5f, 5f);
        p.lineTo(9.2f, 5f);
        p.lineTo(11.2f, 7f);
        p.lineTo(19f, 7f);
        p.cubicTo(20.1f, 7f, 21f, 7.9f, 21f, 9f);
        p.lineTo(21f, 17f);
        p.cubicTo(21f, 18.1f, 20.1f, 19f, 19f, 19f);
        p.lineTo(5f, 19f);
        p.cubicTo(3.9f, 19f, 3f, 18.1f, 3f, 17f);
        p.close();
        return p;
    }

    private static abstract class VectorDrawable extends Drawable {
        private final int width;
        private final int height;

        VectorDrawable(int width, int height) {
            this.width = width;
            this.height = height;
        }

        @Override
        public int getIntrinsicWidth() {
            return width;
        }

        @Override
        public int getIntrinsicHeight() {
            return height;
        }

        @Override
        public void setAlpha(int alpha) {}

        @Override
        public void setColorFilter(ColorFilter colorFilter) {}

        @Override
        @SuppressWarnings("deprecation")
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
