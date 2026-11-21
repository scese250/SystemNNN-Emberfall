package com.tyranor.runtime.systemnnn;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Pure Android SDK (API 26+) Emberfall game library launcher and per-game profile modal.
 */
final class LauncherView extends LinearLayout {
    interface Listener {
        void onAddGame();
        void onLaunchGame(GameProfileStore.Profile profile);
        void onPickFontForProfile(String profileId);
        void onExit();
        boolean canExtractIcons();
    }

    private final Activity activity;
    private final Listener listener;
    private final FrameLayout contentArea;
    private final ExecutorService iconExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Emberfall-Icons");
        t.setDaemon(true);
        return t;
    });
    private final Set<String> attemptedIconIds = new HashSet<>();
    private final Map<String, ImageView> cardIconViews = new HashMap<>();

    private List<GameProfileStore.Profile> profiles = new ArrayList<>();
    private int lastColumnCount = -1;
    private boolean destroyed;

    private AlertDialog openProfileDialog;
    private String openProfileId;
    private TextView modalFontValueView;
    private TextView modalResetFontBtn;
    private ImageView modalIconView;

    LauncherView(Activity activity, Listener listener) {
        super(activity);
        this.activity = activity;
        this.listener = listener;
        setOrientation(VERTICAL);
        setBackgroundColor(EmberfallUi.BG_WINDOW);
        setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));

        addView(buildTopBar());

        contentArea = new FrameLayout(activity);
        contentArea.setBackgroundColor(EmberfallUi.BG_WINDOW);
        contentArea.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            int width = r - l;
            if (width > 0 && !profiles.isEmpty()) {
                int cols = computeColumns(width);
                if (cols != lastColumnCount) {
                    renderContent();
                }
            }
        });
        addView(contentArea, new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            0,
            1f
        ));

        refresh();
    }

    private LinearLayout buildTopBar() {
        LinearLayout wrapper = new LinearLayout(activity);
        wrapper.setOrientation(VERTICAL);
        wrapper.setBackgroundColor(EmberfallUi.BG_TOPBAR);

        LinearLayout bar = new LinearLayout(activity);
        bar.setOrientation(HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setMinimumHeight(EmberfallUi.dp(activity, 52));
        bar.setPadding(
            EmberfallUi.dp(activity, 16),
            EmberfallUi.dp(activity, 6),
            EmberfallUi.dp(activity, 12),
            EmberfallUi.dp(activity, 6)
        );

        ImageView logo = new ImageView(activity);
        logo.setImageDrawable(EmberfallUi.emberLogoIcon(activity, 28));
        logo.setContentDescription("Emberfall");
        LayoutParams logoLp = new LayoutParams(EmberfallUi.dp(activity, 28), EmberfallUi.dp(activity, 28));
        logoLp.setMarginEnd(EmberfallUi.dp(activity, 10));
        bar.addView(logo, logoLp);

        TextView title = EmberfallUi.text(activity, "Emberfall", 17f, EmberfallUi.TEXT_PRIMARY, true);
        title.setSingleLine(true);
        bar.addView(title);

        View spacer = new View(activity);
        bar.addView(spacer, new LayoutParams(0, 1, 1f));

        TextView addBtn = EmberfallUi.primaryButton(
            activity,
            "Add Game",
            EmberfallUi.plusIcon(activity, 15, Color.WHITE),
            v -> {
                if (listener != null) {
                    listener.onAddGame();
                }
            }
        );
        addBtn.setMinHeight(EmberfallUi.dp(activity, 36));
        addBtn.setMinimumHeight(EmberfallUi.dp(activity, 36));
        addBtn.setPadding(
            EmberfallUi.dp(activity, 13),
            EmberfallUi.dp(activity, 6),
            EmberfallUi.dp(activity, 14),
            EmberfallUi.dp(activity, 6)
        );
        bar.addView(addBtn);

        ImageButton exitBtn = EmberfallUi.iconButton(
            activity,
            EmberfallUi.closeIcon(activity, 18, EmberfallUi.TEXT_SECONDARY),
            "Exit",
            36,
            v -> {
                if (listener != null) {
                    listener.onExit();
                }
            }
        );
        LayoutParams exitLp = new LayoutParams(EmberfallUi.dp(activity, 36), EmberfallUi.dp(activity, 36));
        exitLp.setMarginStart(EmberfallUi.dp(activity, 8));
        bar.addView(exitBtn, exitLp);

        wrapper.addView(bar, new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        View divider = new View(activity);
        divider.setBackgroundColor(EmberfallUi.BORDER_SUBTLE);
        wrapper.addView(divider, new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            Math.max(1, EmberfallUi.dp(activity, 1))
        ));

        return wrapper;
    }

    void refresh() {
        profiles = new ArrayList<>(GameProfileStore.loadAll(activity));
        lastColumnCount = -1;
        renderContent();
    }

    void refreshIconsAfterPermissionGranted() {
        attemptedIconIds.clear();
        refresh();
    }

    void refreshProfileModalFont(String profileId) {
        if (openProfileDialog == null || !openProfileDialog.isShowing()) {
            return;
        }
        if (profileId == null || !profileId.equals(openProfileId)) {
            return;
        }
        updateProfileModalFontViews(profileId);
    }

    void destroy() {
        destroyed = true;
        if (openProfileDialog != null && openProfileDialog.isShowing()) {
            openProfileDialog.dismiss();
        }
        openProfileDialog = null;
        openProfileId = null;
        modalFontValueView = null;
        modalResetFontBtn = null;
        modalIconView = null;
        cardIconViews.clear();
        iconExecutor.shutdownNow();
    }

    private int computeColumns(int widthPx) {
        float density = activity.getResources().getDisplayMetrics().density;
        float widthDp = widthPx > 0
            ? (widthPx / Math.max(0.5f, density))
            : (activity.getResources().getDisplayMetrics().widthPixels / Math.max(0.5f, density));
        if (widthDp >= 840f) {
            return 4;
        }
        if (widthDp >= 520f) {
            return 3;
        }
        return 2;
    }

    private void renderContent() {
        contentArea.removeAllViews();
        cardIconViews.clear();

        if (profiles.isEmpty()) {
            contentArea.addView(buildEmptyState(), new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            ));
            return;
        }

        int cols = computeColumns(contentArea.getWidth());
        lastColumnCount = cols;

        ScrollView scroll = new ScrollView(activity);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(View.OVER_SCROLL_IF_CONTENT_SCROLLS);
        scroll.setClipToPadding(false);
        int padH = EmberfallUi.dp(activity, 16);
        int padV = EmberfallUi.dp(activity, 14);
        scroll.setPadding(padH, padV, padH, padV);

        GridLayout grid = new GridLayout(activity);
        grid.setColumnCount(cols);
        grid.setAlignmentMode(GridLayout.ALIGN_BOUNDS);
        grid.setUseDefaultMargins(false);

        int gap = EmberfallUi.dp(activity, 10);
        int halfGap = gap / 2;

        for (int i = 0; i < profiles.size(); i++) {
            final GameProfileStore.Profile profile = profiles.get(i);
            View card = buildGameCard(profile);

            int col = i % cols;
            int row = i / cols;
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams(
                GridLayout.spec(row, 1f),
                GridLayout.spec(col, 1f)
            );
            lp.width = 0;
            lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
            lp.setMargins(
                col == 0 ? 0 : halfGap,
                row == 0 ? 0 : gap,
                col == cols - 1 ? 0 : halfGap,
                0
            );
            grid.addView(card, lp);
        }

        scroll.addView(grid, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));
        contentArea.addView(scroll, new FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        ));
    }

    private LinearLayout buildEmptyState() {
        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(VERTICAL);
        box.setGravity(Gravity.CENTER);
        box.setPadding(
            EmberfallUi.dp(activity, 24),
            EmberfallUi.dp(activity, 24),
            EmberfallUi.dp(activity, 24),
            EmberfallUi.dp(activity, 24)
        );

        ImageView icon = new ImageView(activity);
        icon.setImageDrawable(EmberfallUi.gamepadPlaceholderIcon(activity, 64));
        icon.setAlpha(0.88f);
        box.addView(icon, new LayoutParams(EmberfallUi.dp(activity, 64), EmberfallUi.dp(activity, 64)));

        TextView title = EmberfallUi.text(activity, "No Games", 18f, EmberfallUi.TEXT_PRIMARY, true);
        LayoutParams titleLp = new LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        titleLp.topMargin = EmberfallUi.dp(activity, 14);
        box.addView(title, titleLp);

        TextView addBtn = EmberfallUi.primaryButton(
            activity,
            "Add Game",
            EmberfallUi.plusIcon(activity, 15, Color.WHITE),
            v -> {
                if (listener != null) {
                    listener.onAddGame();
                }
            }
        );
        LayoutParams btnLp = new LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        btnLp.topMargin = EmberfallUi.dp(activity, 16);
        box.addView(addBtn, btnLp);

        return box;
    }

    private View buildGameCard(final GameProfileStore.Profile profile) {
        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(VERTICAL);
        card.setBackground(EmberfallUi.cardBg(activity, 12f));
        card.setPadding(
            EmberfallUi.dp(activity, 12),
            EmberfallUi.dp(activity, 12),
            EmberfallUi.dp(activity, 12),
            EmberfallUi.dp(activity, 12)
        );
        card.setClickable(true);
        card.setFocusable(true);
        card.setSoundEffectsEnabled(false);
        card.setOnClickListener(v -> {
            if (listener != null) {
                listener.onLaunchGame(profile);
            }
        });

        FrameLayout topRow = new FrameLayout(activity);

        ImageView iconView = new ImageView(activity);
        iconView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        bindProfileIcon(profile, iconView);
        cardIconViews.put(profile.id, iconView);
        FrameLayout.LayoutParams iconLp = new FrameLayout.LayoutParams(
            EmberfallUi.dp(activity, 52),
            EmberfallUi.dp(activity, 52),
            Gravity.TOP | Gravity.START
        );
        topRow.addView(iconView, iconLp);

        ImageButton gearBtn = EmberfallUi.iconButton(
            activity,
            EmberfallUi.gearIcon(activity, 18, EmberfallUi.TEXT_SECONDARY),
            "Game Profile",
            32,
            v -> showProfileModal(profile)
        );
        FrameLayout.LayoutParams gearLp = new FrameLayout.LayoutParams(
            EmberfallUi.dp(activity, 32),
            EmberfallUi.dp(activity, 32),
            Gravity.TOP | Gravity.END
        );
        topRow.addView(gearBtn, gearLp);

        card.addView(topRow, new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView titleView = EmberfallUi.text(activity, profile.name, 13.5f, EmberfallUi.TEXT_PRIMARY, true);
        titleView.setMaxLines(2);
        titleView.setMinLines(2);
        titleView.setEllipsize(TextUtils.TruncateAt.END);
        titleView.setLineSpacing(0f, 1.18f);
        LayoutParams titleLp = new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        titleLp.topMargin = EmberfallUi.dp(activity, 10);
        card.addView(titleView, titleLp);

        return card;
    }

    private void bindProfileIcon(final GameProfileStore.Profile profile, final ImageView target) {
        Bitmap cached = GameProfileStore.loadCachedIcon(activity, profile.id);
        if (cached != null) {
            target.setImageDrawable(EmberfallUi.roundedIconDrawable(activity, cached, 11f));
            return;
        }

        target.setImageDrawable(EmberfallUi.gamepadPlaceholderIcon(activity, 52));
        if (listener != null && !listener.canExtractIcons()) {
            return;
        }
        if (!attemptedIconIds.add(profile.id)) {
            return;
        }

        final Context appContext = activity.getApplicationContext();
        iconExecutor.execute(() -> {
            if (destroyed) {
                return;
            }
            boolean ok = GameProfileStore.ensureIconExtracted(appContext, profile);
            if (!ok || destroyed) {
                return;
            }
            final Bitmap extracted = GameProfileStore.loadCachedIcon(appContext, profile.id);
            if (extracted == null || destroyed) {
                return;
            }
            post(() -> {
                if (destroyed) {
                    return;
                }
                ImageView current = cardIconViews.get(profile.id);
                if (current != null) {
                    current.setImageDrawable(EmberfallUi.roundedIconDrawable(activity, extracted, 11f));
                }
                if (openProfileDialog != null && openProfileDialog.isShowing()
                        && profile.id.equals(openProfileId) && modalIconView != null) {
                    modalIconView.setImageDrawable(EmberfallUi.roundedIconDrawable(activity, extracted, 11f));
                }
            });
        });
    }

    private void showProfileModal(final GameProfileStore.Profile initialProfile) {
        if (activity.isFinishing() || activity.isDestroyed()) {
            return;
        }
        if (openProfileDialog != null && openProfileDialog.isShowing()) {
            openProfileDialog.dismiss();
        }

        GameProfileStore.Profile latest = GameProfileStore.findById(activity, initialProfile.id);
        final GameProfileStore.Profile profile = latest != null ? latest : initialProfile;
        final boolean[] removed = new boolean[]{false};

        LinearLayout content = new LinearLayout(activity);
        content.setOrientation(VERTICAL);
        content.setLayoutParams(new ViewGroup.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        final AlertDialog[] holder = new AlertDialog[1];
        content.addView(EmberfallUi.buildModalHeader(activity, "Game Profile", () -> {
            if (holder[0] != null) {
                holder[0].dismiss();
            }
        }));

        LinearLayout topRow = new LinearLayout(activity);
        topRow.setOrientation(HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams topRowLp = new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        topRowLp.topMargin = EmberfallUi.dp(activity, 14);

        ImageView preview = new ImageView(activity);
        preview.setScaleType(ImageView.ScaleType.FIT_CENTER);
        Bitmap cached = GameProfileStore.loadCachedIcon(activity, profile.id);
        preview.setImageDrawable(cached != null
            ? EmberfallUi.roundedIconDrawable(activity, cached, 11f)
            : EmberfallUi.gamepadPlaceholderIcon(activity, 52));
        LayoutParams previewLp = new LayoutParams(EmberfallUi.dp(activity, 52), EmberfallUi.dp(activity, 52));
        previewLp.setMarginEnd(EmberfallUi.dp(activity, 12));
        topRow.addView(preview, previewLp);

        LinearLayout nameCol = new LinearLayout(activity);
        nameCol.setOrientation(VERTICAL);

        final EditText nameInput = new EditText(activity);
        nameInput.setText(profile.name);
        nameInput.setSelection(profile.name.length());
        nameInput.setSingleLine(true);
        nameInput.setImeOptions(EditorInfo.IME_ACTION_DONE);
        nameInput.setTextColor(EmberfallUi.TEXT_PRIMARY);
        nameInput.setHintTextColor(EmberfallUi.TEXT_MUTED);
        nameInput.setHint("Game Title");
        nameInput.setTextSize(14.5f);
        nameInput.setBackground(EmberfallUi.roundedRect(
            EmberfallUi.BG_INPUT,
            EmberfallUi.BORDER_CARD,
            EmberfallUi.dp(activity, 1),
            EmberfallUi.dp(activity, 10)
        ));
        nameInput.setPadding(
            EmberfallUi.dp(activity, 12),
            EmberfallUi.dp(activity, 9),
            EmberfallUi.dp(activity, 12),
            EmberfallUi.dp(activity, 9)
        );
        nameCol.addView(nameInput, new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        TextView pathView = EmberfallUi.text(
            activity,
            formatFolderLabel(profile.folderPath),
            11.5f,
            EmberfallUi.TEXT_MUTED,
            false
        );
        pathView.setSingleLine(true);
        pathView.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        LayoutParams pathLp = new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        pathLp.topMargin = EmberfallUi.dp(activity, 5);
        nameCol.addView(pathView, pathLp);

        topRow.addView(nameCol, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        content.addView(topRow, topRowLp);

        LinearLayout fontCard = new LinearLayout(activity);
        fontCard.setOrientation(VERTICAL);
        fontCard.setBackground(EmberfallUi.roundedRect(
            EmberfallUi.BG_CARD,
            EmberfallUi.BORDER_CARD,
            EmberfallUi.dp(activity, 1),
            EmberfallUi.dp(activity, 12)
        ));
        fontCard.setPadding(
            EmberfallUi.dp(activity, 12),
            EmberfallUi.dp(activity, 10),
            EmberfallUi.dp(activity, 12),
            EmberfallUi.dp(activity, 10)
        );
        LayoutParams fontCardLp = new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        fontCardLp.topMargin = EmberfallUi.dp(activity, 14);

        LinearLayout fontInfoRow = new LinearLayout(activity);
        fontInfoRow.setOrientation(HORIZONTAL);
        fontInfoRow.setGravity(Gravity.CENTER_VERTICAL);

        TextView fontLabel = EmberfallUi.text(activity, "Font", 12.5f, EmberfallUi.TEXT_SECONDARY, true);
        fontInfoRow.addView(fontLabel);

        TextView fontValue = EmberfallUi.text(
            activity,
            PlayerFont.currentName(activity, profile.id),
            13f,
            EmberfallUi.TEXT_PRIMARY,
            true
        );
        fontValue.setSingleLine(true);
        fontValue.setEllipsize(TextUtils.TruncateAt.END);
        fontValue.setGravity(Gravity.END);
        LayoutParams fontValLp = new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        fontValLp.setMarginStart(EmberfallUi.dp(activity, 12));
        fontInfoRow.addView(fontValue, fontValLp);

        fontCard.addView(fontInfoRow, new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        ));

        LinearLayout fontActions = new LinearLayout(activity);
        fontActions.setOrientation(HORIZONTAL);
        fontActions.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams fontActLp = new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        fontActLp.topMargin = EmberfallUi.dp(activity, 10);

        TextView chooseFontBtn = EmberfallUi.secondaryButton(
            activity,
            "Choose Font",
            EmberfallUi.fontIcon(activity, 15, EmberfallUi.ACCENT),
            v -> {
                if (listener != null) {
                    listener.onPickFontForProfile(profile.id);
                }
            }
        );
        fontActions.addView(chooseFontBtn, new LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView resetFontBtn = EmberfallUi.dangerButton(activity, "Reset", v -> {
            PlayerFont.reset(activity, profile.id);
            updateProfileModalFontViews(profile.id);
        });
        LayoutParams resetLp = new LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        resetLp.setMarginStart(EmberfallUi.dp(activity, 8));
        fontActions.addView(resetFontBtn, resetLp);

        fontCard.addView(fontActions, fontActLp);
        content.addView(fontCard, fontCardLp);

        LinearLayout bottomBar = new LinearLayout(activity);
        bottomBar.setOrientation(HORIZONTAL);
        bottomBar.setGravity(Gravity.CENTER_VERTICAL);
        LayoutParams bottomLp = new LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        );
        bottomLp.topMargin = EmberfallUi.dp(activity, 16);

        TextView removeBtn = EmberfallUi.dangerButton(activity, "Remove", v -> {
            removed[0] = true;
            GameProfileStore.remove(activity, profile.id);
            if (holder[0] != null) {
                holder[0].dismiss();
            }
            refresh();
        });
        bottomBar.addView(removeBtn);

        View spacer = new View(activity);
        bottomBar.addView(spacer, new LayoutParams(0, 1, 1f));

        TextView playBtn = EmberfallUi.primaryButton(activity, "Play", null, v -> {
            GameProfileStore.Profile current = saveProfileName(profile, nameInput.getText().toString());
            if (holder[0] != null) {
                holder[0].dismiss();
            }
            if (listener != null) {
                listener.onLaunchGame(current);
            }
        });
        bottomBar.addView(playBtn);

        content.addView(bottomBar, bottomLp);

        AlertDialog dialog = EmberfallUi.createModalDialog(activity, content, true, 480);
        holder[0] = dialog;
        openProfileDialog = dialog;
        openProfileId = profile.id;
        modalFontValueView = fontValue;
        modalResetFontBtn = resetFontBtn;
        modalIconView = preview;

        updateProfileModalFontViews(profile.id);

        dialog.setOnDismissListener(d -> {
            if (!removed[0]) {
                String entered = nameInput.getText().toString().trim();
                if (!entered.isEmpty() && !entered.equals(profile.name)) {
                    GameProfileStore.rename(activity, profile.id, entered);
                    refresh();
                }
            }
            if (openProfileDialog == dialog) {
                openProfileDialog = null;
                openProfileId = null;
                modalFontValueView = null;
                modalResetFontBtn = null;
                modalIconView = null;
            }
        });

        dialog.show();
    }

    private GameProfileStore.Profile saveProfileName(GameProfileStore.Profile profile, String rawName) {
        String trimmed = rawName != null ? rawName.trim() : "";
        if (!trimmed.isEmpty() && !trimmed.equals(profile.name)) {
            GameProfileStore.Profile renamed = GameProfileStore.rename(activity, profile.id, trimmed);
            if (renamed != null) {
                return renamed;
            }
        }
        return profile;
    }

    private void updateProfileModalFontViews(String profileId) {
        if (modalFontValueView != null) {
            modalFontValueView.setText(PlayerFont.currentName(activity, profileId));
        }
        if (modalResetFontBtn != null) {
            modalResetFontBtn.setVisibility(
                PlayerFont.hasCustom(activity, profileId) ? View.VISIBLE : View.GONE
            );
        }
    }

    private static String formatFolderLabel(String folderPath) {
        if (folderPath == null || folderPath.isEmpty()) {
            return "";
        }
        File f = new File(folderPath);
        File parent = f.getParentFile();
        if (parent != null && !parent.getName().isEmpty()) {
            return parent.getName() + "/" + f.getName();
        }
        return f.getName().isEmpty() ? folderPath : f.getName();
    }
}
