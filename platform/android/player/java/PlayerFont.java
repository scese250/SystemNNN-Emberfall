package com.tyranor.runtime.systemnnn;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.provider.OpenableColumns;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Objects;

final class PlayerFont {
    private static final String FONT_FILE_NAME = "custom_font.ttf";
    private static final String PROFILE_FONT_DIR = "profiles/fonts";
    private static final String PROFILE_FONT_NAME_PREFIX = "profileFontName.";
    private static final String DEFAULT_FONT_DISPLAY_NAME = "Game Default";

    private static volatile String activeProfileId = null;
    private static volatile Typeface cachedTypeface = null;
    private static volatile String cachedProfileId = null;
    private static volatile String cachedPath = null;
    private static volatile long cachedMtime = -1;

    private PlayerFont() {}

    static void setActiveProfile(String profileId) {
        activeProfileId = normalizeProfileId(profileId);
    }

    static String activeProfileId() {
        return activeProfileId;
    }

    private static String normalizeProfileId(String profileId) {
        return (profileId != null && !profileId.isEmpty()) ? profileId : null;
    }

    private static File fontFile(Context context, String profileId) {
        String id = normalizeProfileId(profileId);
        if (id != null) {
            return new File(new File(context.getFilesDir(), PROFILE_FONT_DIR), id + ".ttf");
        }
        return new File(context.getFilesDir(), FONT_FILE_NAME);
    }

    private static String fontNameKey(String profileId) {
        String id = normalizeProfileId(profileId);
        return id != null ? (PROFILE_FONT_NAME_PREFIX + id) : PlayerSettings.CUSTOM_FONT_NAME;
    }

    static boolean hasCustom(Context context) {
        return hasCustom(context, activeProfileId);
    }

    static boolean hasCustom(Context context, String profileId) {
        if (context == null) return false;
        File file = fontFile(context, profileId);
        return file.exists() && file.length() > 0;
    }

    static String currentName(Context context) {
        return currentName(context, activeProfileId);
    }

    static String currentName(Context context, String profileId) {
        if (context == null || !hasCustom(context, profileId)) {
            return DEFAULT_FONT_DISPLAY_NAME;
        }
        String name = PlayerSettings.preferences(context).getString(fontNameKey(profileId), null);
        return (name != null && !name.trim().isEmpty()) ? name : FONT_FILE_NAME;
    }

    static Typeface base(Context context) {
        if (context == null) return Typeface.DEFAULT;
        String profileId = activeProfileId;
        synchronized (PlayerFont.class) {
            File file = fontFile(context, profileId);
            String path = file.getAbsolutePath();
            if (!file.exists() || file.length() == 0) {
                if (Objects.equals(cachedProfileId, profileId) && Objects.equals(cachedPath, path)) {
                    cachedTypeface = null;
                    cachedProfileId = null;
                    cachedPath = null;
                    cachedMtime = -1;
                }
                return Typeface.DEFAULT;
            }
            long mtime = file.lastModified();
            if (cachedTypeface != null
                    && Objects.equals(cachedProfileId, profileId)
                    && Objects.equals(cachedPath, path)
                    && cachedMtime == mtime) {
                return cachedTypeface;
            }
            try {
                Typeface loaded = Typeface.createFromFile(file);
                if (loaded != null) {
                    cachedTypeface = loaded;
                    cachedProfileId = profileId;
                    cachedPath = path;
                    cachedMtime = mtime;
                    return loaded;
                }
            } catch (RuntimeException ignored) {}
            cachedTypeface = null;
            cachedProfileId = null;
            cachedPath = null;
            cachedMtime = -1;
            return Typeface.DEFAULT;
        }
    }

    static Typeface dialogue(Context context, int weight) {
        Typeface custom = base(context);
        if (custom != Typeface.DEFAULT) {
            return custom;
        }
        if (weight > 0) {
            if (Build.VERSION.SDK_INT >= 28) {
                return Typeface.create(Typeface.DEFAULT, weight, false);
            } else if (weight >= 600) {
                return Typeface.DEFAULT_BOLD;
            }
        }
        return Typeface.DEFAULT;
    }

    static Paint apply(Paint paint, Context context) {
        if (paint != null && context != null) {
            paint.setTypeface(base(context));
        }
        return paint;
    }

    static <T extends TextView> T apply(T view, Context context) {
        if (view != null && context != null) {
            view.setTypeface(base(context));
        }
        return view;
    }

    static String importFont(Context context, Uri uri) throws IOException {
        return importFont(context, activeProfileId, uri);
    }

    static String importFont(Context context, String profileId, Uri uri) throws IOException {
        if (context == null || uri == null) throw new IOException("Context or Uri is null");
        String normalizedId = normalizeProfileId(profileId);
        String displayName = FONT_FILE_NAME;
        try (Cursor cursor = context.getContentResolver().query(uri, new String[]{OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = cursor.getString(idx);
                    if (name != null && !name.trim().isEmpty()) {
                        displayName = name.trim();
                    }
                }
            }
        } catch (Exception ignored) {}

        File tempFile = File.createTempFile("font_import_", ".tmp", context.getCacheDir());
        long maxBytes = 50L * 1024 * 1024;
        long totalBytes = 0;
        byte[] buffer = new byte[8192];
        try (InputStream in = context.getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(tempFile)) {
            if (in == null) throw new IOException("Unable to open font stream");
            int read;
            while ((read = in.read(buffer)) != -1) {
                totalBytes += read;
                if (totalBytes > maxBytes) {
                    throw new IOException("Font file exceeds maximum size of 50 MiB");
                }
                out.write(buffer, 0, read);
            }
            out.flush();
        } catch (Exception e) {
            tempFile.delete();
            throw (e instanceof IOException) ? (IOException) e : new IOException("Failed to read font file", e);
        }

        boolean validMagic = false;
        try (RandomAccessFile raf = new RandomAccessFile(tempFile, "r")) {
            if (raf.length() >= 4) {
                int magic = raf.readInt();
                if (magic == 0x00010000 || // TrueType
                    magic == 0x74727565 || // 'true'
                    magic == 0x74797031 || // 'typ1'
                    magic == 0x4F54544F || // 'OTTO'
                    magic == 0x74746366) { // 'ttcf'
                    validMagic = true;
                }
            }
        } catch (Exception ignored) {}

        if (!validMagic) {
            tempFile.delete();
            throw new IOException("Invalid font file. Please select a valid .ttf or .otf font.");
        }

        Typeface testTypeface;
        try {
            testTypeface = Typeface.createFromFile(tempFile);
            if (testTypeface == null) {
                throw new RuntimeException("Typeface.createFromFile returned null");
            }
        } catch (RuntimeException e) {
            tempFile.delete();
            throw new IOException("Invalid font file. Please select a valid .ttf or .otf font.", e);
        }

        File targetFile = fontFile(context, normalizedId);
        File parentDir = targetFile.getParentFile();
        if (parentDir != null && !parentDir.exists() && !parentDir.mkdirs()) {
            tempFile.delete();
            throw new IOException("Failed to create font directory");
        }
        try {
            try {
                Files.move(tempFile.toPath(), targetFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile.toPath(), targetFile.toPath(),
                    StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            tempFile.delete();
            throw new IOException("Failed to save custom font: " + e.getMessage(), e);
        }

        synchronized (PlayerFont.class) {
            cachedTypeface = testTypeface;
            cachedProfileId = normalizedId;
            cachedPath = targetFile.getAbsolutePath();
            cachedMtime = targetFile.lastModified();
        }

        SharedPreferences prefs = PlayerSettings.preferences(context);
        int ver = prefs.getInt(PlayerSettings.CUSTOM_FONT_VERSION, 0);
        prefs.edit()
            .putString(fontNameKey(normalizedId), displayName)
            .putInt(PlayerSettings.CUSTOM_FONT_VERSION, ver + 1)
            .apply();

        return displayName;
    }

    static void reset(Context context) {
        reset(context, activeProfileId);
    }

    static void reset(Context context, String profileId) {
        if (context == null) return;
        String normalizedId = normalizeProfileId(profileId);
        File targetFile = fontFile(context, normalizedId);
        String targetPath = targetFile.getAbsolutePath();
        if (targetFile.exists()) {
            targetFile.delete();
        }
        synchronized (PlayerFont.class) {
            if (Objects.equals(cachedProfileId, normalizedId) || Objects.equals(cachedPath, targetPath)) {
                cachedTypeface = null;
                cachedProfileId = null;
                cachedPath = null;
                cachedMtime = -1;
            }
        }
        SharedPreferences prefs = PlayerSettings.preferences(context);
        int ver = prefs.getInt(PlayerSettings.CUSTOM_FONT_VERSION, 0);
        prefs.edit()
            .remove(fontNameKey(normalizedId))
            .putInt(PlayerSettings.CUSTOM_FONT_VERSION, ver + 1)
            .apply();
    }

    static void deleteProfileFont(Context context, String profileId) {
        if (context == null) return;
        String normalizedId = normalizeProfileId(profileId);
        if (normalizedId == null) return;
        File targetFile = fontFile(context, normalizedId);
        String targetPath = targetFile.getAbsolutePath();
        if (targetFile.exists()) {
            targetFile.delete();
        }
        synchronized (PlayerFont.class) {
            if (Objects.equals(cachedProfileId, normalizedId) || Objects.equals(cachedPath, targetPath)) {
                cachedTypeface = null;
                cachedProfileId = null;
                cachedPath = null;
                cachedMtime = -1;
            }
        }
        SharedPreferences prefs = PlayerSettings.preferences(context);
        SharedPreferences.Editor editor = prefs.edit().remove(fontNameKey(normalizedId));
        if (Objects.equals(activeProfileId, normalizedId)) {
            int ver = prefs.getInt(PlayerSettings.CUSTOM_FONT_VERSION, 0);
            editor.putInt(PlayerSettings.CUSTOM_FONT_VERSION, ver + 1);
        }
        editor.apply();
    }
}
