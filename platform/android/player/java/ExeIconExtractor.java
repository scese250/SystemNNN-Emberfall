package com.tyranor.runtime.systemnnn;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class ExeIconExtractor {
    private static final long MIN_EXE_SIZE = 64L;
    private static final long MAX_EXE_SIZE = 64L * 1024L * 1024L;
    private static final int MAX_ICON_SIZE = 192;
    private static final int MAX_ICON_DIM = 512;
    private static final int MAX_RESOURCE_PAYLOAD = 8 * 1024 * 1024;
    private static final int MAX_SECTIONS = 256;
    private static final int MAX_DIR_ENTRIES = 4096;
    private static final int RT_ICON = 3;
    private static final int RT_GROUP_ICON = 14;
    private static final byte[] PNG_SIGNATURE = {
        (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A
    };
    private static final String[] UTILITY_PREFIXES = {
        "unins", "uninst", "setup", "config", "exesetup",
        "dx", "vcredist", "directx", "crash", "update", "register", "manual"
    };

    static final class DecodedIcon {
        final int width;
        final int height;
        final byte[] pngBytes;
        final int[] argbPixels;

        DecodedIcon(int width, int height, byte[] pngBytes, int[] argbPixels) {
            this.width = width;
            this.height = height;
            this.pngBytes = pngBytes;
            this.argbPixels = argbPixels;
        }
    }

    private static final class ExeCandidate {
        final File file;
        final boolean utility;
        final boolean hasGroupIcon;
        final long size;

        ExeCandidate(File file, boolean utility, boolean hasGroupIcon, long size) {
            this.file = file;
            this.utility = utility;
            this.hasGroupIcon = hasGroupIcon;
            this.size = size;
        }
    }

    private static final class Section {
        final long virtualSize;
        final long virtualAddress;
        final long sizeOfRawData;
        final long pointerToRawData;

        Section(long virtualSize, long virtualAddress, long sizeOfRawData, long pointerToRawData) {
            this.virtualSize = virtualSize;
            this.virtualAddress = virtualAddress;
            this.sizeOfRawData = sizeOfRawData;
            this.pointerToRawData = pointerToRawData;
        }
    }

    private static final class DirEntry {
        final int id;
        final boolean isNamed;
        final boolean isSubdir;
        final long offset;

        DirEntry(int id, boolean isNamed, boolean isSubdir, long offset) {
            this.id = id;
            this.isNamed = isNamed;
            this.isSubdir = isSubdir;
            this.offset = offset;
        }
    }

    private static final class GroupEntry {
        final int width;
        final int height;
        final int bitCount;
        final long bytesInRes;
        final int resourceId;
        final int score;

        GroupEntry(int width, int height, int bitCount, long bytesInRes, int resourceId, int score) {
            this.width = width;
            this.height = height;
            this.bitCount = bitCount;
            this.bytesInRes = bytesInRes;
            this.resourceId = resourceId;
            this.score = score;
        }
    }

    private static final class PeContext {
        final RandomAccessFile raf;
        final long fileLength;
        final long resourceRva;
        final long resourceSize;
        final List<Section> sections;

        PeContext(RandomAccessFile raf, long fileLength, long resourceRva, long resourceSize, List<Section> sections) {
            this.raf = raf;
            this.fileLength = fileLength;
            this.resourceRva = resourceRva;
            this.resourceSize = resourceSize;
            this.sections = sections;
        }

        long rvaToFileOffset(long rva) {
            if (rva < 0) {
                return -1L;
            }
            for (int i = 0; i < sections.size(); i++) {
                Section sec = sections.get(i);
                long span = Math.max(sec.virtualSize, sec.sizeOfRawData);
                if (span <= 0) {
                    continue;
                }
                if (rva >= sec.virtualAddress && rva < sec.virtualAddress + span) {
                    long delta = rva - sec.virtualAddress;
                    if (delta < sec.sizeOfRawData && sec.pointerToRawData > 0
                            && sec.pointerToRawData + delta < fileLength) {
                        return sec.pointerToRawData + delta;
                    }
                }
            }
            return -1L;
        }

        long maxBytesAtRva(long rva) {
            if (rva < 0) {
                return 0L;
            }
            for (int i = 0; i < sections.size(); i++) {
                Section sec = sections.get(i);
                long span = Math.max(sec.virtualSize, sec.sizeOfRawData);
                if (span <= 0) {
                    continue;
                }
                if (rva >= sec.virtualAddress && rva < sec.virtualAddress + span) {
                    long delta = rva - sec.virtualAddress;
                    if (delta < sec.sizeOfRawData && sec.pointerToRawData > 0
                            && sec.pointerToRawData + delta < fileLength) {
                        long secAvail = sec.sizeOfRawData - delta;
                        long fileAvail = fileLength - (sec.pointerToRawData + delta);
                        return Math.min(secAvail, fileAvail);
                    }
                }
            }
            return 0L;
        }
    }

    private ExeIconExtractor() {}

    static File findPrimaryExe(File gameDir) {
        List<ExeCandidate> ranked = rankCandidates(gameDir);
        return ranked.isEmpty() ? null : ranked.get(0).file;
    }

    static Bitmap extractFromDirectory(File gameDir) {
        List<ExeCandidate> ranked = rankCandidates(gameDir);
        for (int i = 0; i < ranked.size(); i++) {
            File candidate = ranked.get(i).file;
            try {
                DecodedIcon icon = decodeIconFromPe(candidate);
                if (icon != null) {
                    Bitmap bitmap = toBitmap(icon);
                    if (bitmap != null) {
                        return bitmap;
                    }
                }
            } catch (IOException | RuntimeException ignored) {
            }
        }
        return null;
    }

    static boolean extractToPng(File gameDir, File outputPng) {
        if (gameDir == null || outputPng == null) {
            return false;
        }
        Bitmap bitmap = extractFromDirectory(gameDir);
        if (bitmap == null) {
            return false;
        }
        Bitmap scaled = null;
        File tempFile = null;
        try {
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            if (width <= 0 || height <= 0) {
                return false;
            }
            Bitmap target = bitmap;
            if (width > MAX_ICON_SIZE || height > MAX_ICON_SIZE) {
                float scale = Math.min((float) MAX_ICON_SIZE / width, (float) MAX_ICON_SIZE / height);
                int targetW = Math.max(1, Math.round(width * scale));
                int targetH = Math.max(1, Math.round(height * scale));
                scaled = Bitmap.createScaledBitmap(bitmap, targetW, targetH, true);
                if (scaled != null) {
                    target = scaled;
                }
            }

            File parent = outputPng.getParentFile();
            if (parent != null && !parent.exists() && !parent.mkdirs()) {
                return false;
            }
            tempFile = File.createTempFile("icon_", ".tmp", parent);
            try (FileOutputStream out = new FileOutputStream(tempFile)) {
                if (!target.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    return false;
                }
                out.flush();
                try {
                    out.getFD().sync();
                } catch (IOException ignored) {
                }
            }
            try {
                Files.move(tempFile.toPath(), outputPng.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tempFile.toPath(), outputPng.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            tempFile = null;
            return true;
        } catch (IOException | RuntimeException e) {
            return false;
        } finally {
            if (tempFile != null && tempFile.exists()) {
                tempFile.delete();
            }
            if (scaled != null && scaled != bitmap) {
                scaled.recycle();
            }
            bitmap.recycle();
        }
    }

    private static Bitmap toBitmap(DecodedIcon icon) {
        if (icon == null) {
            return null;
        }
        if (icon.pngBytes != null) {
            return BitmapFactory.decodeByteArray(icon.pngBytes, 0, icon.pngBytes.length);
        }
        if (icon.argbPixels != null && icon.width > 0 && icon.height > 0
                && icon.argbPixels.length == icon.width * icon.height) {
            return Bitmap.createBitmap(icon.argbPixels, icon.width, icon.height, Bitmap.Config.ARGB_8888);
        }
        return null;
    }

    private static List<ExeCandidate> rankCandidates(File gameDir) {
        if (gameDir == null || !gameDir.isDirectory()) {
            return Collections.emptyList();
        }
        File[] files = gameDir.listFiles();
        if (files == null || files.length == 0) {
            return Collections.emptyList();
        }
        List<ExeCandidate> candidates = new ArrayList<>();
        for (int i = 0; i < files.length; i++) {
            File file = files[i];
            if (!file.isFile()) {
                continue;
            }
            String lowerName = file.getName().toLowerCase(Locale.ROOT);
            if (!lowerName.endsWith(".exe")) {
                continue;
            }
            long size = file.length();
            if (size < MIN_EXE_SIZE || size > MAX_EXE_SIZE) {
                continue;
            }
            boolean utility = isUtilityName(lowerName);
            boolean hasGroup = hasValidGroupIcon(file);
            candidates.add(new ExeCandidate(file, utility, hasGroup, size));
        }
        Collections.sort(candidates, new Comparator<ExeCandidate>() {
            @Override
            public int compare(ExeCandidate a, ExeCandidate b) {
                if (a.utility != b.utility) {
                    return a.utility ? 1 : -1;
                }
                if (a.hasGroupIcon != b.hasGroupIcon) {
                    return a.hasGroupIcon ? -1 : 1;
                }
                int sizeCmp = Long.compare(b.size, a.size);
                if (sizeCmp != 0) {
                    return sizeCmp;
                }
                int ciCmp = a.file.getName().compareToIgnoreCase(b.file.getName());
                if (ciCmp != 0) {
                    return ciCmp;
                }
                return a.file.getName().compareTo(b.file.getName());
            }
        });
        return candidates;
    }

    private static boolean isUtilityName(String lowerName) {
        for (int i = 0; i < UTILITY_PREFIXES.length; i++) {
            if (lowerName.startsWith(UTILITY_PREFIXES[i])) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasValidGroupIcon(File exeFile) {
        try (RandomAccessFile raf = new RandomAccessFile(exeFile, "r")) {
            PeContext ctx = openPe(raf);
            if (ctx == null) {
                return false;
            }
            List<DirEntry> rootEntries = readDirectory(ctx, 0L);
            if (rootEntries == null) {
                return false;
            }
            DirEntry groupType = findById(rootEntries, RT_GROUP_ICON);
            if (groupType == null || !groupType.isSubdir) {
                return false;
            }
            List<DirEntry> groupEntries = readDirectory(ctx, groupType.offset);
            if (groupEntries == null || groupEntries.isEmpty()) {
                return false;
            }
            DirEntry firstGroup = pickFirstGroupEntry(groupEntries);
            if (firstGroup == null || !firstGroup.isSubdir) {
                return false;
            }
            List<DirEntry> langEntries = readDirectory(ctx, firstGroup.offset);
            if (langEntries == null || langEntries.isEmpty()) {
                return false;
            }
            DirEntry langLeaf = langEntries.get(0);
            if (langLeaf.isSubdir) {
                return false;
            }
            byte[] grpBytes = readResourceData(ctx, langLeaf.offset, 65536);
            if (grpBytes == null || grpBytes.length < 6) {
                return false;
            }
            int idCount = readU16(grpBytes, 4);
            return idCount > 0 && grpBytes.length >= 6 + idCount * 14;
        } catch (IOException | RuntimeException e) {
            return false;
        }
    }

    static DecodedIcon decodeIconFromPe(File exeFile) throws IOException {
        if (exeFile == null || !exeFile.isFile()) {
            return null;
        }
        long len = exeFile.length();
        if (len < MIN_EXE_SIZE || len > MAX_EXE_SIZE) {
            return null;
        }
        try (RandomAccessFile raf = new RandomAccessFile(exeFile, "r")) {
            PeContext ctx = openPe(raf);
            if (ctx == null) {
                return null;
            }
            List<DirEntry> rootEntries = readDirectory(ctx, 0L);
            if (rootEntries == null) {
                return null;
            }
            DirEntry groupType = findById(rootEntries, RT_GROUP_ICON);
            DirEntry iconType = findById(rootEntries, RT_ICON);
            if (groupType == null || !groupType.isSubdir || iconType == null || !iconType.isSubdir) {
                return null;
            }

            List<DirEntry> groupEntries = readDirectory(ctx, groupType.offset);
            if (groupEntries == null || groupEntries.isEmpty()) {
                return null;
            }
            DirEntry chosenGroup = pickFirstGroupEntry(groupEntries);
            if (chosenGroup == null || !chosenGroup.isSubdir) {
                return null;
            }

            List<DirEntry> groupLangs = readDirectory(ctx, chosenGroup.offset);
            if (groupLangs == null || groupLangs.isEmpty()) {
                return null;
            }
            DirEntry groupLeaf = groupLangs.get(0);
            if (groupLeaf.isSubdir) {
                return null;
            }
            byte[] grpBytes = readResourceData(ctx, groupLeaf.offset, 65536);
            if (grpBytes == null || grpBytes.length < 6) {
                return null;
            }

            int idCount = readU16(grpBytes, 4);
            if (idCount <= 0 || idCount > MAX_DIR_ENTRIES || grpBytes.length < 6 + idCount * 14) {
                return null;
            }

            List<GroupEntry> rankedEntries = new ArrayList<>(idCount);
            for (int i = 0; i < idCount; i++) {
                int base = 6 + i * 14;
                int w = grpBytes[base] & 0xFF;
                int h = grpBytes[base + 1] & 0xFF;
                if (w == 0) w = 256;
                if (h == 0) h = 256;
                int colorCount = grpBytes[base + 2] & 0xFF;
                int bitCount = readU16(grpBytes, base + 6);
                if (bitCount == 0 && colorCount > 0) {
                    if (colorCount <= 2) bitCount = 1;
                    else if (colorCount <= 16) bitCount = 4;
                    else bitCount = 8;
                }
                long bytesInRes = readU32(grpBytes, base + 8);
                int nId = readU16(grpBytes, base + 12);
                int score = scoreGroupEntry(w, h, bitCount);
                rankedEntries.add(new GroupEntry(w, h, bitCount, bytesInRes, nId, score));
            }
            Collections.sort(rankedEntries, new Comparator<GroupEntry>() {
                @Override
                public int compare(GroupEntry a, GroupEntry b) {
                    int scoreCmp = Integer.compare(b.score, a.score);
                    if (scoreCmp != 0) {
                        return scoreCmp;
                    }
                    int bitCmp = Integer.compare(b.bitCount, a.bitCount);
                    if (bitCmp != 0) {
                        return bitCmp;
                    }
                    return Integer.compare(Math.max(b.width, b.height), Math.max(a.width, a.height));
                }
            });

            List<DirEntry> iconEntries = readDirectory(ctx, iconType.offset);
            if (iconEntries == null || iconEntries.isEmpty()) {
                return null;
            }

            for (int i = 0; i < rankedEntries.size(); i++) {
                GroupEntry ge = rankedEntries.get(i);
                DirEntry iconIdEntry = findById(iconEntries, ge.resourceId);
                if (iconIdEntry == null || !iconIdEntry.isSubdir) {
                    continue;
                }
                List<DirEntry> iconLangs = readDirectory(ctx, iconIdEntry.offset);
                if (iconLangs == null || iconLangs.isEmpty()) {
                    continue;
                }
                for (int j = 0; j < iconLangs.size(); j++) {
                    DirEntry iconLeaf = iconLangs.get(j);
                    if (iconLeaf.isSubdir) {
                        continue;
                    }
                    byte[] rawIcon = readResourceData(ctx, iconLeaf.offset, MAX_RESOURCE_PAYLOAD);
                    if (rawIcon == null || rawIcon.length == 0) {
                        continue;
                    }
                    DecodedIcon decoded = decodeIconPayload(rawIcon);
                    if (decoded != null) {
                        return decoded;
                    }
                }
            }
            return null;
        }
    }

    private static int scoreGroupEntry(int width, int height, int bitCount) {
        int dim = Math.max(width, height);
        int dimScore;
        if (dim >= 64 && dim <= 256) {
            // Prefer 64-256px range, especially 128-256px
            dimScore = 3000 + dim;
        } else if (dim >= 48) {
            dimScore = 2000 + dim;
        } else {
            dimScore = dim;
        }
        int effectiveBits = (bitCount == 0) ? 32 : bitCount;
        int bitScore;
        if (effectiveBits >= 32) {
            bitScore = 500;
        } else if (effectiveBits >= 24) {
            bitScore = 400;
        } else if (effectiveBits >= 8) {
            bitScore = 300;
        } else if (effectiveBits >= 4) {
            bitScore = 200;
        } else {
            bitScore = 100;
        }
        return dimScore + bitScore * 100;
    }

    private static PeContext openPe(RandomAccessFile raf) throws IOException {
        long fileLen = raf.length();
        if (fileLen < 64) {
            return null;
        }
        byte[] dos = new byte[64];
        raf.seek(0L);
        raf.readFully(dos);
        if (readU16(dos, 0) != 0x5A4D) {
            return null;
        }
        long eLfanew = readU32(dos, 0x3C);
        if (eLfanew < 64 || eLfanew > fileLen - 24) {
            return null;
        }

        byte[] peAndCoff = new byte[24];
        raf.seek(eLfanew);
        raf.readFully(peAndCoff);
        if (readU32(peAndCoff, 0) != 0x00004550L) {
            return null;
        }

        int numberOfSections = readU16(peAndCoff, 6);
        int sizeOfOptionalHeader = readU16(peAndCoff, 20);
        if (numberOfSections <= 0 || numberOfSections > MAX_SECTIONS) {
            return null;
        }
        long optOffset = eLfanew + 24L;
        if (sizeOfOptionalHeader < 96 || optOffset + sizeOfOptionalHeader > fileLen) {
            return null;
        }

        byte[] optHeader = new byte[Math.min(sizeOfOptionalHeader, 256)];
        raf.seek(optOffset);
        raf.readFully(optHeader);

        int magic = readU16(optHeader, 0);
        int numRvaOffset;
        int dataDirOffset;
        if (magic == 0x10B) {
            // PE32
            numRvaOffset = 92;
            dataDirOffset = 96;
        } else if (magic == 0x20B) {
            // PE32+
            numRvaOffset = 108;
            dataDirOffset = 112;
        } else {
            return null;
        }

        int resourceEntryOffset = dataDirOffset + 2 * 8;
        if (sizeOfOptionalHeader < resourceEntryOffset + 8 || optHeader.length < resourceEntryOffset + 8) {
            return null;
        }
        long numberOfRvaAndSizes = readU32(optHeader, numRvaOffset);
        if (numberOfRvaAndSizes <= 2) {
            return null;
        }

        long resourceRva = readU32(optHeader, resourceEntryOffset);
        long resourceSize = readU32(optHeader, resourceEntryOffset + 4);
        if (resourceRva == 0L || resourceSize == 0L) {
            return null;
        }

        long secTableOffset = optOffset + sizeOfOptionalHeader;
        long secTableBytes = (long) numberOfSections * 40L;
        if (secTableOffset + secTableBytes > fileLen) {
            return null;
        }

        byte[] secBytes = new byte[(int) secTableBytes];
        raf.seek(secTableOffset);
        raf.readFully(secBytes);

        List<Section> sections = new ArrayList<>(numberOfSections);
        for (int i = 0; i < numberOfSections; i++) {
            int base = i * 40;
            long virtualSize = readU32(secBytes, base + 8);
            long virtualAddress = readU32(secBytes, base + 12);
            long sizeOfRawData = readU32(secBytes, base + 16);
            long pointerToRawData = readU32(secBytes, base + 20);
            sections.add(new Section(virtualSize, virtualAddress, sizeOfRawData, pointerToRawData));
        }

        return new PeContext(raf, fileLen, resourceRva, resourceSize, sections);
    }

    private static List<DirEntry> readDirectory(PeContext ctx, long relOffset) throws IOException {
        if (relOffset < 0 || relOffset >= ctx.resourceSize) {
            return null;
        }
        long rva = ctx.resourceRva + relOffset;
        long fileOffset = ctx.rvaToFileOffset(rva);
        long avail = ctx.maxBytesAtRva(rva);
        if (fileOffset < 0 || avail < 16) {
            return null;
        }
        byte[] header = new byte[16];
        ctx.raf.seek(fileOffset);
        ctx.raf.readFully(header);

        int namedCount = readU16(header, 12);
        int idCount = readU16(header, 14);
        int total = namedCount + idCount;
        if (total < 0 || total > MAX_DIR_ENTRIES) {
            return null;
        }
        long entriesBytes = (long) total * 8L;
        if (16L + entriesBytes > avail) {
            return null;
        }
        if (total == 0) {
            return Collections.emptyList();
        }
        byte[] buf = new byte[(int) entriesBytes];
        ctx.raf.seek(fileOffset + 16L);
        ctx.raf.readFully(buf);

        List<DirEntry> entries = new ArrayList<>(total);
        for (int i = 0; i < total; i++) {
            int base = i * 8;
            long nameField = readU32(buf, base);
            long offsetField = readU32(buf, base + 4);
            boolean isNamed = (nameField & 0x80000000L) != 0;
            int id = isNamed ? -1 : (int) (nameField & 0xFFFFL);
            boolean isSubdir = (offsetField & 0x80000000L) != 0;
            long offset = offsetField & 0x7FFFFFFFL;
            entries.add(new DirEntry(id, isNamed, isSubdir, offset));
        }
        return entries;
    }

    private static DirEntry findById(List<DirEntry> entries, int targetId) {
        for (int i = 0; i < entries.size(); i++) {
            DirEntry entry = entries.get(i);
            if (!entry.isNamed && entry.id == targetId) {
                return entry;
            }
        }
        return null;
    }

    private static DirEntry pickFirstGroupEntry(List<DirEntry> entries) {
        DirEntry lowestIdEntry = null;
        for (int i = 0; i < entries.size(); i++) {
            DirEntry entry = entries.get(i);
            if (!entry.isNamed) {
                if (lowestIdEntry == null || entry.id < lowestIdEntry.id) {
                    lowestIdEntry = entry;
                }
            }
        }
        if (lowestIdEntry != null) {
            return lowestIdEntry;
        }
        return entries.isEmpty() ? null : entries.get(0);
    }

    private static byte[] readResourceData(PeContext ctx, long dataEntryRelOffset, int maxBytes) throws IOException {
        if (dataEntryRelOffset < 0 || dataEntryRelOffset + 16 > ctx.resourceSize) {
            return null;
        }
        long entryRva = ctx.resourceRva + dataEntryRelOffset;
        long entryFileOffset = ctx.rvaToFileOffset(entryRva);
        long entryAvail = ctx.maxBytesAtRva(entryRva);
        if (entryFileOffset < 0 || entryAvail < 16) {
            return null;
        }
        byte[] dataEntry = new byte[16];
        ctx.raf.seek(entryFileOffset);
        ctx.raf.readFully(dataEntry);

        long payloadRva = readU32(dataEntry, 0);
        long payloadSize = readU32(dataEntry, 4);
        if (payloadSize <= 0 || payloadSize > maxBytes) {
            return null;
        }
        long payloadFileOffset = ctx.rvaToFileOffset(payloadRva);
        long payloadAvail = ctx.maxBytesAtRva(payloadRva);
        if (payloadFileOffset < 0 || payloadAvail < payloadSize) {
            return null;
        }
        byte[] payload = new byte[(int) payloadSize];
        ctx.raf.seek(payloadFileOffset);
        ctx.raf.readFully(payload);
        return payload;
    }

    static DecodedIcon decodeIconPayload(byte[] raw) {
        if (raw == null || raw.length < 8) {
            return null;
        }
        if (startsWithPngSignature(raw)) {
            return decodePngMetadata(raw);
        }
        return decodeDib(raw);
    }

    private static boolean startsWithPngSignature(byte[] raw) {
        if (raw.length < PNG_SIGNATURE.length) {
            return false;
        }
        for (int i = 0; i < PNG_SIGNATURE.length; i++) {
            if (raw[i] != PNG_SIGNATURE[i]) {
                return false;
            }
        }
        return true;
    }

    private static DecodedIcon decodePngMetadata(byte[] raw) {
        // IHDR chunk starts at offset 8: 4-byte length (13), 4-byte 'IHDR', 4-byte BE width, 4-byte BE height
        if (raw.length < 24) {
            return null;
        }
        if (raw[12] != 'I' || raw[13] != 'H' || raw[14] != 'D' || raw[15] != 'R') {
            return null;
        }
        int width = readS32BE(raw, 16);
        int height = readS32BE(raw, 20);
        if (width <= 0 || width > MAX_ICON_DIM || height <= 0 || height > MAX_ICON_DIM) {
            return null;
        }
        return new DecodedIcon(width, height, raw, null);
    }

    private static DecodedIcon decodeDib(byte[] raw) {
        if (raw.length < 40) {
            return null;
        }
        long biSize = readU32(raw, 0);
        if (biSize < 40 || biSize > raw.length) {
            return null;
        }
        int biWidth = readS32LE(raw, 4);
        int biHeight = readS32LE(raw, 8);
        int biBitCount = readU16(raw, 14);
        int biCompression = readS32LE(raw, 16);
        long biClrUsed = readU32(raw, 32);

        if (biCompression != 0 && biCompression != 3) {
            return null;
        }
        if (biBitCount != 1 && biBitCount != 4 && biBitCount != 8 && biBitCount != 24 && biBitCount != 32) {
            return null;
        }
        if (biWidth <= 0 || biWidth > MAX_ICON_DIM) {
            return null;
        }
        if (biHeight == Integer.MIN_VALUE) {
            return null;
        }
        boolean topDown = biHeight < 0;
        int absHeight = Math.abs(biHeight);
        int height = absHeight / 2;
        int width = biWidth;
        if (height <= 0 || height > MAX_ICON_DIM) {
            return null;
        }

        int offset = (int) biSize;
        if (biCompression == 3 && biSize == 40) {
            // BI_BITFIELDS appends 3 DWORD color masks after BITMAPINFOHEADER
            if (offset + 12 > raw.length) {
                return null;
            }
            offset += 12;
        }

        int[] palette = null;
        int colorCount = 0;
        if (biBitCount <= 8) {
            int maxColors = 1 << biBitCount;
            if (biClrUsed > maxColors) {
                return null;
            }
            colorCount = (biClrUsed == 0) ? maxColors : (int) biClrUsed;
            long paletteBytes = (long) colorCount * 4L;
            if (offset + paletteBytes > raw.length) {
                return null;
            }
            palette = new int[colorCount];
            for (int i = 0; i < colorCount; i++) {
                int p = offset + i * 4;
                int b = raw[p] & 0xFF;
                int g = raw[p + 1] & 0xFF;
                int r = raw[p + 2] & 0xFF;
                palette[i] = 0xFF000000 | (r << 16) | (g << 8) | b;
            }
            offset += (int) paletteBytes;
        } else if (biClrUsed > 0) {
            if (biClrUsed > 256 || offset + biClrUsed * 4L > raw.length) {
                return null;
            }
            offset += (int) (biClrUsed * 4L);
        }

        long xorRowBits = (long) width * biBitCount;
        int xorRowBytes = (int) (((xorRowBits + 31L) / 32L) * 4L);
        long xorTotalBytes = (long) xorRowBytes * height;
        if (offset + xorTotalBytes > raw.length) {
            return null;
        }

        int andRowBytes = ((width + 31) / 32) * 4;
        long andTotalBytes = (long) andRowBytes * height;
        int andOffset = offset + (int) xorTotalBytes;
        boolean hasAndMask = (andOffset + andTotalBytes <= raw.length);

        int[] argb = new int[width * height];
        if (biBitCount == 32) {
            boolean anyNonZeroAlpha = false;
            for (int y = 0; y < height; y++) {
                int srcRow = topDown ? y : (height - 1 - y);
                int rowStart = offset + srcRow * xorRowBytes;
                int dstStart = y * width;
                for (int x = 0; x < width; x++) {
                    int p = rowStart + x * 4;
                    int b = raw[p] & 0xFF;
                    int g = raw[p + 1] & 0xFF;
                    int r = raw[p + 2] & 0xFF;
                    int a = raw[p + 3] & 0xFF;
                    if (a != 0) {
                        anyNonZeroAlpha = true;
                    }
                    argb[dstStart + x] = (a << 24) | (r << 16) | (g << 8) | b;
                }
            }
            if (!anyNonZeroAlpha) {
                for (int y = 0; y < height; y++) {
                    int srcRow = topDown ? y : (height - 1 - y);
                    int maskRowStart = andOffset + srcRow * andRowBytes;
                    int dstStart = y * width;
                    for (int x = 0; x < width; x++) {
                        boolean transparent = hasAndMask && isMaskBitSet(raw, maskRowStart, x);
                        if (transparent) {
                            argb[dstStart + x] = 0;
                        } else {
                            argb[dstStart + x] = 0xFF000000 | (argb[dstStart + x] & 0x00FFFFFF);
                        }
                    }
                }
            }
        } else if (biBitCount == 24) {
            for (int y = 0; y < height; y++) {
                int srcRow = topDown ? y : (height - 1 - y);
                int rowStart = offset + srcRow * xorRowBytes;
                int maskRowStart = andOffset + srcRow * andRowBytes;
                int dstStart = y * width;
                for (int x = 0; x < width; x++) {
                    boolean transparent = hasAndMask && isMaskBitSet(raw, maskRowStart, x);
                    if (transparent) {
                        argb[dstStart + x] = 0;
                    } else {
                        int p = rowStart + x * 3;
                        int b = raw[p] & 0xFF;
                        int g = raw[p + 1] & 0xFF;
                        int r = raw[p + 2] & 0xFF;
                        argb[dstStart + x] = 0xFF000000 | (r << 16) | (g << 8) | b;
                    }
                }
            }
        } else {
            for (int y = 0; y < height; y++) {
                int srcRow = topDown ? y : (height - 1 - y);
                int rowStart = offset + srcRow * xorRowBytes;
                int maskRowStart = andOffset + srcRow * andRowBytes;
                int dstStart = y * width;
                for (int x = 0; x < width; x++) {
                    boolean transparent = hasAndMask && isMaskBitSet(raw, maskRowStart, x);
                    if (transparent) {
                        argb[dstStart + x] = 0;
                        continue;
                    }
                    int idx;
                    if (biBitCount == 8) {
                        idx = raw[rowStart + x] & 0xFF;
                    } else if (biBitCount == 4) {
                        int bv = raw[rowStart + (x >> 1)] & 0xFF;
                        idx = ((x & 1) == 0) ? (bv >> 4) : (bv & 0x0F);
                    } else {
                        int bv = raw[rowStart + (x >> 3)] & 0xFF;
                        idx = (bv >> (7 - (x & 7))) & 1;
                    }
                    argb[dstStart + x] = (idx < colorCount) ? palette[idx] : 0xFF000000;
                }
            }
        }

        return new DecodedIcon(width, height, null, argb);
    }

    private static boolean isMaskBitSet(byte[] raw, int maskRowStart, int x) {
        int maskByte = raw[maskRowStart + (x >> 3)] & 0xFF;
        return ((maskByte >> (7 - (x & 7))) & 1) != 0;
    }

    private static int readU16(byte[] buf, int offset) {
        return (buf[offset] & 0xFF) | ((buf[offset + 1] & 0xFF) << 8);
    }

    private static long readU32(byte[] buf, int offset) {
        return ((long) (buf[offset] & 0xFF))
                | (((long) (buf[offset + 1] & 0xFF)) << 8)
                | (((long) (buf[offset + 2] & 0xFF)) << 16)
                | (((long) (buf[offset + 3] & 0xFF)) << 24);
    }

    private static int readS32LE(byte[] buf, int offset) {
        return (buf[offset] & 0xFF)
                | ((buf[offset + 1] & 0xFF) << 8)
                | ((buf[offset + 2] & 0xFF) << 16)
                | ((buf[offset + 3] & 0xFF) << 24);
    }

    private static int readS32BE(byte[] buf, int offset) {
        return ((buf[offset] & 0xFF) << 24)
                | ((buf[offset + 1] & 0xFF) << 16)
                | ((buf[offset + 2] & 0xFF) << 8)
                | (buf[offset + 3] & 0xFF);
    }
}
