package com.tyranor.runtime.systemnnn;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

final class GameProfileStore {
    private static final String PROFILES_DIR = "profiles";
    private static final String PROFILES_FILE = "profiles.json";
    private static final String ICONS_DIR = "icons";
    private static final Object LOCK = new Object();

    static final class Profile {
        final String id;
        final String name;
        final String folderPath;
        final String treeUri;
        final long addedAt;
        final long lastPlayedAt;

        Profile(String id, String name, String folderPath, String treeUri, long addedAt, long lastPlayedAt) {
            this.id = id;
            this.name = name;
            this.folderPath = folderPath;
            this.treeUri = treeUri;
            this.addedAt = addedAt;
            this.lastPlayedAt = lastPlayedAt;
        }
    }

    private GameProfileStore() {}

    static String profileIdForPath(String path) {
        String normalized = normalizePath(path);
        return UUID.nameUUIDFromBytes(normalized.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private static String normalizePath(String path) {
        if (path == null) {
            return "";
        }
        String trimmed = path.trim();
        while (trimmed.length() > 1 && (trimmed.endsWith("/") || trimmed.endsWith("\\"))) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private static String canonicalFolderPath(File folder) {
        if (folder == null) {
            return "";
        }
        try {
            return normalizePath(folder.getCanonicalPath());
        } catch (IOException e) {
            return normalizePath(folder.getAbsolutePath());
        }
    }

    private static File profilesDir(Context context) {
        return new File(context.getFilesDir(), PROFILES_DIR);
    }

    private static File profilesJsonFile(Context context) {
        return new File(profilesDir(context), PROFILES_FILE);
    }

    static File iconFile(Context context, String id) {
        if (context == null || id == null || id.isEmpty()) {
            return null;
        }
        return new File(new File(profilesDir(context), ICONS_DIR), id + ".png");
    }

    static List<Profile> loadAll(Context context) {
        if (context == null) {
            return Collections.emptyList();
        }
        synchronized (LOCK) {
            List<Profile> list = readRawList(context);
            sortProfiles(list);
            return list;
        }
    }

    static Profile findById(Context context, String id) {
        if (context == null || id == null || id.isEmpty()) {
            return null;
        }
        synchronized (LOCK) {
            List<Profile> list = readRawList(context);
            for (int i = 0; i < list.size(); i++) {
                Profile p = list.get(i);
                if (id.equals(p.id)) {
                    return p;
                }
            }
            return null;
        }
    }

    static Profile findByPath(Context context, String canonicalPath) {
        if (context == null || canonicalPath == null || canonicalPath.isEmpty()) {
            return null;
        }
        String normalized = normalizePath(canonicalPath);
        String targetId = profileIdForPath(normalized);
        synchronized (LOCK) {
            List<Profile> list = readRawList(context);
            for (int i = 0; i < list.size(); i++) {
                Profile p = list.get(i);
                if (targetId.equals(p.id) || normalized.equals(normalizePath(p.folderPath))) {
                    return p;
                }
            }
            return null;
        }
    }

    static Profile addOrUpdate(Context context, File folder, String treeUri) {
        if (context == null || folder == null) {
            return null;
        }
        String folderPath = canonicalFolderPath(folder);
        if (folderPath.isEmpty()) {
            return null;
        }
        String id = profileIdForPath(folderPath);
        String cleanTreeUri = (treeUri != null && !treeUri.isEmpty()) ? treeUri : null;
        synchronized (LOCK) {
            List<Profile> list = readRawList(context);
            for (int i = 0; i < list.size(); i++) {
                Profile existing = list.get(i);
                if (id.equals(existing.id)) {
                    String nextTree = cleanTreeUri != null ? cleanTreeUri : existing.treeUri;
                    boolean changed = !folderPath.equals(existing.folderPath)
                            || (nextTree == null ? existing.treeUri != null : !nextTree.equals(existing.treeUri));
                    if (!changed) {
                        return existing;
                    }
                    Profile updated = new Profile(
                            existing.id,
                            existing.name,
                            folderPath,
                            nextTree,
                            existing.addedAt,
                            existing.lastPlayedAt);
                    list.set(i, updated);
                    writeRawList(context, list);
                    return updated;
                }
            }

            String defaultName = folder.getName();
            if (defaultName == null || defaultName.trim().isEmpty()) {
                defaultName = "Game";
            } else {
                defaultName = defaultName.trim();
            }
            long now = System.currentTimeMillis();
            Profile created = new Profile(id, defaultName, folderPath, cleanTreeUri, now, 0L);
            list.add(created);
            writeRawList(context, list);
            return created;
        }
    }

    static Profile rename(Context context, String id, String newName) {
        if (context == null || id == null || id.isEmpty()) {
            return null;
        }
        String trimmed = newName != null ? newName.trim() : "";
        synchronized (LOCK) {
            List<Profile> list = readRawList(context);
            for (int i = 0; i < list.size(); i++) {
                Profile existing = list.get(i);
                if (id.equals(existing.id)) {
                    if (trimmed.isEmpty() || trimmed.equals(existing.name)) {
                        return existing;
                    }
                    Profile updated = new Profile(
                            existing.id,
                            trimmed,
                            existing.folderPath,
                            existing.treeUri,
                            existing.addedAt,
                            existing.lastPlayedAt);
                    list.set(i, updated);
                    writeRawList(context, list);
                    return updated;
                }
            }
            return null;
        }
    }

    static void touchLastPlayed(Context context, String id) {
        if (context == null || id == null || id.isEmpty()) {
            return;
        }
        long now = System.currentTimeMillis();
        synchronized (LOCK) {
            List<Profile> list = readRawList(context);
            for (int i = 0; i < list.size(); i++) {
                Profile existing = list.get(i);
                if (id.equals(existing.id)) {
                    Profile updated = new Profile(
                            existing.id,
                            existing.name,
                            existing.folderPath,
                            existing.treeUri,
                            existing.addedAt,
                            now);
                    list.set(i, updated);
                    writeRawList(context, list);
                    return;
                }
            }
        }
    }

    static void remove(Context context, String id) {
        if (context == null || id == null || id.isEmpty()) {
            return;
        }
        synchronized (LOCK) {
            List<Profile> list = readRawList(context);
            boolean removed = false;
            for (int i = list.size() - 1; i >= 0; i--) {
                if (id.equals(list.get(i).id)) {
                    list.remove(i);
                    removed = true;
                }
            }
            if (removed) {
                writeRawList(context, list);
            }
        }
        File icon = iconFile(context, id);
        if (icon != null && icon.exists()) {
            icon.delete();
        }
        PlayerFont.deleteProfileFont(context, id);
    }

    static Bitmap loadCachedIcon(Context context, String id) {
        File file = iconFile(context, id);
        if (file == null || !file.isFile() || file.length() == 0) {
            return null;
        }
        try {
            return BitmapFactory.decodeFile(file.getAbsolutePath());
        } catch (RuntimeException e) {
            return null;
        }
    }

    static boolean ensureIconExtracted(Context context, Profile profile) {
        if (context == null || profile == null || profile.folderPath == null || profile.folderPath.isEmpty()) {
            return false;
        }
        File out = iconFile(context, profile.id);
        if (out == null) {
            return false;
        }
        if (out.isFile() && out.length() > 0) {
            return true;
        }
        return ExeIconExtractor.extractToPng(new File(profile.folderPath), out);
    }

    private static void sortProfiles(List<Profile> list) {
        Collections.sort(list, new Comparator<Profile>() {
            @Override
            public int compare(Profile a, Profile b) {
                long ta = a.lastPlayedAt > 0L ? a.lastPlayedAt : a.addedAt;
                long tb = b.lastPlayedAt > 0L ? b.lastPlayedAt : b.addedAt;
                int cmp = Long.compare(tb, ta);
                if (cmp != 0) {
                    return cmp;
                }
                cmp = Long.compare(b.lastPlayedAt, a.lastPlayedAt);
                if (cmp != 0) {
                    return cmp;
                }
                cmp = Long.compare(b.addedAt, a.addedAt);
                if (cmp != 0) {
                    return cmp;
                }
                return a.name.compareToIgnoreCase(b.name);
            }
        });
    }

    private static List<Profile> readRawList(Context context) {
        List<Profile> result = new ArrayList<>();
        File file = profilesJsonFile(context);
        if (!file.isFile() || file.length() == 0) {
            return result;
        }
        try {
            String text = readUtf8(file);
            if (text.trim().isEmpty()) {
                return result;
            }
            JSONArray array = new JSONArray(text);
            for (int i = 0; i < array.length(); i++) {
                JSONObject obj = array.optJSONObject(i);
                if (obj == null) {
                    continue;
                }
                String folderPath = obj.optString("folderPath", "");
                if (folderPath.isEmpty()) {
                    continue;
                }
                String id = obj.optString("id", "");
                if (id.isEmpty()) {
                    id = profileIdForPath(folderPath);
                }
                String name = obj.optString("name", "");
                if (name.isEmpty()) {
                    String fallback = new File(folderPath).getName();
                    name = fallback.isEmpty() ? "Game" : fallback;
                }
                String treeUri = obj.isNull("treeUri") ? null : obj.optString("treeUri", null);
                if (treeUri != null && treeUri.isEmpty()) {
                    treeUri = null;
                }
                long addedAt = obj.optLong("addedAt", 0L);
                long lastPlayedAt = obj.optLong("lastPlayedAt", 0L);
                result.add(new Profile(id, name, folderPath, treeUri, addedAt, lastPlayedAt));
            }
        } catch (IOException | JSONException | RuntimeException ignored) {
            return new ArrayList<>();
        }
        return result;
    }

    private static void writeRawList(Context context, List<Profile> list) {
        File dir = profilesDir(context);
        if (!dir.exists() && !dir.mkdirs()) {
            return;
        }
        JSONArray array = new JSONArray();
        for (int i = 0; i < list.size(); i++) {
            Profile p = list.get(i);
            try {
                JSONObject obj = new JSONObject();
                obj.put("id", p.id);
                obj.put("name", p.name);
                obj.put("folderPath", p.folderPath);
                if (p.treeUri != null) {
                    obj.put("treeUri", p.treeUri);
                } else {
                    obj.put("treeUri", JSONObject.NULL);
                }
                obj.put("addedAt", p.addedAt);
                obj.put("lastPlayedAt", p.lastPlayedAt);
                array.put(obj);
            } catch (JSONException ignored) {
            }
        }
        byte[] bytes = array.toString().getBytes(StandardCharsets.UTF_8);
        File target = profilesJsonFile(context);
        File temp = new File(dir, PROFILES_FILE + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temp)) {
            out.write(bytes);
            out.flush();
            try {
                out.getFD().sync();
            } catch (IOException ignored) {
            }
        } catch (IOException e) {
            temp.delete();
            return;
        }
        try {
            try {
                Files.move(temp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            temp.delete();
        }
    }

    private static String readUtf8(File file) throws IOException {
        if (file.length() > 16L * 1024L * 1024L) {
            throw new IOException("profiles.json exceeds size limit");
        }
        byte[] buf = new byte[8192];
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (FileInputStream in = new FileInputStream(file)) {
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
        }
        return new String(out.toByteArray(), StandardCharsets.UTF_8);
    }
}
