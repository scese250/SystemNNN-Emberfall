package com.tyranor.runtime.systemnnn;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public final class ExeIconExtractorTest {
    private static int checks;

    private static void check(boolean condition, String detail) {
        if (!condition) {
            throw new AssertionError(detail);
        }
        checks++;
    }

    private static final class IconResourceSpec {
        final int id;
        final int width;
        final int height;
        final int bitCount;
        final byte[] payload;

        IconResourceSpec(int id, int width, int height, int bitCount, byte[] payload) {
            this.id = id;
            this.width = width;
            this.height = height;
            this.bitCount = bitCount;
            this.payload = payload;
        }
    }

    /**
     * Builds a synthetic PE32 or PE32+ executable with a .rsrc section at RVA 0x2000,
     * containing RT_ICON (type 3) and RT_GROUP_ICON (type 14).
     */
    private static byte[] buildSyntheticPe(boolean pe32Plus, int groupId, List<IconResourceSpec> icons) {
        final int dosHeaderSize = 0x80;
        final int peSigAndCoffSize = 24;
        final int optHeaderSize = pe32Plus ? 240 : 224;
        final int sectionTableSize = 40;
        final int headersRawSize = alignUp(dosHeaderSize + peSigAndCoffSize + optHeaderSize + sectionTableSize, 0x200);
        final int rsrcRva = 0x2000;
        final int rsrcRawOffset = headersRawSize;

        // Build .rsrc bytes relative to rsrcRva
        byte[] rsrc = buildRsrcSection(rsrcRva, groupId, icons);
        int rsrcRawSize = alignUp(rsrc.length, 0x200);

        ByteBuffer buf = ByteBuffer.allocate(rsrcRawOffset + rsrcRawSize).order(ByteOrder.LITTLE_ENDIAN);

        // DOS header
        buf.putShort(0, (short) 0x5A4D); // MZ
        buf.putInt(0x3C, dosHeaderSize); // e_lfanew

        // PE Signature + COFF header
        int peOffset = dosHeaderSize;
        buf.putInt(peOffset, 0x00004550); // PE\0\0
        buf.putShort(peOffset + 4, (short) (pe32Plus ? 0x8664 : 0x014C)); // Machine
        buf.putShort(peOffset + 6, (short) 1); // NumberOfSections
        buf.putShort(peOffset + 20, (short) optHeaderSize); // SizeOfOptionalHeader

        // Optional header
        int optOffset = peOffset + 24;
        buf.putShort(optOffset, (short) (pe32Plus ? 0x20B : 0x10B));
        int numRvaOffset = pe32Plus ? (optOffset + 108) : (optOffset + 92);
        int dataDirOffset = pe32Plus ? (optOffset + 112) : (optOffset + 96);
        buf.putInt(numRvaOffset, 16);
        // Resource DataDirectory (index 2)
        buf.putInt(dataDirOffset + 2 * 8, rsrcRva);
        buf.putInt(dataDirOffset + 2 * 8 + 4, rsrc.length);

        // Section header: .rsrc
        int secOffset = optOffset + optHeaderSize;
        byte[] secName = {'.', 'r', 's', 'r', 'c', 0, 0, 0};
        for (int i = 0; i < 8; i++) {
            buf.put(secOffset + i, secName[i]);
        }
        buf.putInt(secOffset + 8, rsrc.length); // VirtualSize
        buf.putInt(secOffset + 12, rsrcRva); // VirtualAddress
        buf.putInt(secOffset + 16, rsrcRawSize); // SizeOfRawData
        buf.putInt(secOffset + 20, rsrcRawOffset); // PointerToRawData

        // Copy .rsrc payload
        buf.position(rsrcRawOffset);
        buf.put(rsrc);

        return buf.array();
    }

    private static byte[] buildRsrcSection(int rsrcRva, int groupId, List<IconResourceSpec> icons) {
        if (icons.isEmpty()) {
            return new byte[16];
        }
        // Build GRPICONDIR
        ByteBuffer grp = ByteBuffer.allocate(6 + icons.size() * 14).order(ByteOrder.LITTLE_ENDIAN);
        grp.putShort((short) 0);
        grp.putShort((short) 1);
        grp.putShort((short) icons.size());
        for (IconResourceSpec spec : icons) {
            grp.put((byte) (spec.width >= 256 ? 0 : spec.width));
            grp.put((byte) (spec.height >= 256 ? 0 : spec.height));
            grp.put((byte) 0);
            grp.put((byte) 0);
            grp.putShort((short) 1);
            grp.putShort((short) spec.bitCount);
            grp.putInt(spec.payload.length);
            grp.putShort((short) spec.id);
        }
        byte[] grpBytes = grp.array();

        // Directory layout:
        // Root dir: 16 + 2 * 8 = 32 bytes (Type 3 and Type 14)
        // Type 3 dir: 16 + icons.size() * 8
        // Type 14 dir: 16 + 1 * 8 = 24
        // Lang dirs for Type 3: icons.size() * (16 + 8) = icons.size() * 24
        // Lang dir for Type 14: 16 + 8 = 24
        // Data entries: (icons.size() + 1) * 16
        int rootDirOff = 0;
        int rootDirSize = 16 + 2 * 8;
        int type3DirOff = rootDirOff + rootDirSize;
        int type3DirSize = 16 + icons.size() * 8;
        int type14DirOff = type3DirOff + type3DirSize;
        int type14DirSize = 16 + 8;

        int iconLangDirsOff = type14DirOff + type14DirSize;
        int iconLangDirsSize = icons.size() * 24;
        int groupLangDirOff = iconLangDirsOff + iconLangDirsSize;
        int groupLangDirSize = 24;

        int dataEntriesOff = groupLangDirOff + groupLangDirSize;
        int dataEntriesSize = (icons.size() + 1) * 16;

        int payloadsOff = alignUp(dataEntriesOff + dataEntriesSize, 4);
        int totalPayloadSize = alignUp(grpBytes.length, 4);
        for (IconResourceSpec spec : icons) {
            totalPayloadSize += alignUp(spec.payload.length, 4);
        }

        ByteBuffer out = ByteBuffer.allocate(payloadsOff + totalPayloadSize).order(ByteOrder.LITTLE_ENDIAN);

        // Root directory: 2 ID entries (3, 14)
        writeDirHeader(out, rootDirOff, 0, 2);
        writeDirEntry(out, rootDirOff + 16, 3, true, type3DirOff);
        writeDirEntry(out, rootDirOff + 24, 14, true, type14DirOff);

        // Type 3 directory
        writeDirHeader(out, type3DirOff, 0, icons.size());
        for (int i = 0; i < icons.size(); i++) {
            int langDirOff = iconLangDirsOff + i * 24;
            writeDirEntry(out, type3DirOff + 16 + i * 8, icons.get(i).id, true, langDirOff);
            writeDirHeader(out, langDirOff, 0, 1);
            int dataEntryOff = dataEntriesOff + i * 16;
            writeDirEntry(out, langDirOff + 16, 1033, false, dataEntryOff);
        }

        // Type 14 directory
        writeDirHeader(out, type14DirOff, 0, 1);
        writeDirEntry(out, type14DirOff + 16, groupId, true, groupLangDirOff);
        writeDirHeader(out, groupLangDirOff, 0, 1);
        int grpDataEntryOff = dataEntriesOff + icons.size() * 16;
        writeDirEntry(out, groupLangDirOff + 16, 1033, false, grpDataEntryOff);

        // Write payloads & data entries
        int cursor = payloadsOff;
        for (int i = 0; i < icons.size(); i++) {
            IconResourceSpec spec = icons.get(i);
            int dataEntryOff = dataEntriesOff + i * 16;
            out.putInt(dataEntryOff, rsrcRva + cursor);
            out.putInt(dataEntryOff + 4, spec.payload.length);
            out.position(cursor);
            out.put(spec.payload);
            cursor += alignUp(spec.payload.length, 4);
        }

        out.putInt(grpDataEntryOff, rsrcRva + cursor);
        out.putInt(grpDataEntryOff + 4, grpBytes.length);
        out.position(cursor);
        out.put(grpBytes);

        return out.array();
    }

    private static void writeDirHeader(ByteBuffer buf, int offset, int namedCount, int idCount) {
        buf.putShort(offset + 12, (short) namedCount);
        buf.putShort(offset + 14, (short) idCount);
    }

    private static void writeDirEntry(ByteBuffer buf, int offset, int id, boolean isSubdir, int targetOffset) {
        buf.putInt(offset, id);
        buf.putInt(offset + 4, (isSubdir ? 0x80000000 : 0) | targetOffset);
    }

    private static int alignUp(int value, int align) {
        return (value + align - 1) & ~(align - 1);
    }

    private static byte[] buildMinimalPngStub(int width, int height) {
        // Valid PNG signature + IHDR chunk (enough for pure-Java PNG header verification)
        ByteBuffer buf = ByteBuffer.allocate(33).order(ByteOrder.BIG_ENDIAN);
        buf.put(new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
        buf.putInt(13); // IHDR length
        buf.put(new byte[]{'I', 'H', 'D', 'R'});
        buf.putInt(width);
        buf.putInt(height);
        buf.put((byte) 8); // bit depth
        buf.put((byte) 6); // RGBA
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.put((byte) 0);
        buf.putInt(0); // CRC placeholder
        return buf.array();
    }

    private static byte[] buildDib32(int w, int h, int[] topDownArgb, boolean zeroAlphaWithAndMask, boolean[] transparentMask) {
        int xorRowBytes = w * 4;
        int andRowBytes = ((w + 31) / 32) * 4;
        ByteBuffer buf = ByteBuffer.allocate(40 + xorRowBytes * h + andRowBytes * h).order(ByteOrder.LITTLE_ENDIAN);
        writeDibHeader(buf, w, h, 32, 0);
        // Bottom-up XOR pixels
        for (int y = 0; y < h; y++) {
            int srcRow = h - 1 - y;
            for (int x = 0; x < w; x++) {
                int argb = topDownArgb[srcRow * w + x];
                int p = 40 + y * xorRowBytes + x * 4;
                buf.put(p, (byte) (argb & 0xFF));
                buf.put(p + 1, (byte) ((argb >> 8) & 0xFF));
                buf.put(p + 2, (byte) ((argb >> 16) & 0xFF));
                buf.put(p + 3, (byte) (zeroAlphaWithAndMask ? 0 : ((argb >> 24) & 0xFF)));
            }
        }
        if (transparentMask != null) {
            writeAndMask(buf, 40 + xorRowBytes * h, w, h, andRowBytes, transparentMask);
        }
        return buf.array();
    }

    private static byte[] buildDib24(int w, int h, int[] topDownRgb, boolean[] transparentMask) {
        int xorRowBytes = ((w * 24 + 31) / 32) * 4;
        int andRowBytes = ((w + 31) / 32) * 4;
        ByteBuffer buf = ByteBuffer.allocate(40 + xorRowBytes * h + andRowBytes * h).order(ByteOrder.LITTLE_ENDIAN);
        writeDibHeader(buf, w, h, 24, 0);
        for (int y = 0; y < h; y++) {
            int srcRow = h - 1 - y;
            for (int x = 0; x < w; x++) {
                int rgb = topDownRgb[srcRow * w + x];
                int p = 40 + y * xorRowBytes + x * 3;
                buf.put(p, (byte) (rgb & 0xFF));
                buf.put(p + 1, (byte) ((rgb >> 8) & 0xFF));
                buf.put(p + 2, (byte) ((rgb >> 16) & 0xFF));
            }
        }
        writeAndMask(buf, 40 + xorRowBytes * h, w, h, andRowBytes, transparentMask);
        return buf.array();
    }

    private static byte[] buildDib8(int w, int h, int[] paletteRgb, byte[] topDownIndices, boolean[] transparentMask) {
        int paletteColors = 256;
        int xorRowBytes = ((w * 8 + 31) / 32) * 4;
        int andRowBytes = ((w + 31) / 32) * 4;
        int pixelOffset = 40 + paletteColors * 4;
        ByteBuffer buf = ByteBuffer.allocate(pixelOffset + xorRowBytes * h + andRowBytes * h).order(ByteOrder.LITTLE_ENDIAN);
        writeDibHeader(buf, w, h, 8, paletteColors);
        for (int i = 0; i < paletteRgb.length && i < paletteColors; i++) {
            int rgb = paletteRgb[i];
            int p = 40 + i * 4;
            buf.put(p, (byte) (rgb & 0xFF));
            buf.put(p + 1, (byte) ((rgb >> 8) & 0xFF));
            buf.put(p + 2, (byte) ((rgb >> 16) & 0xFF));
            buf.put(p + 3, (byte) 0);
        }
        for (int y = 0; y < h; y++) {
            int srcRow = h - 1 - y;
            for (int x = 0; x < w; x++) {
                buf.put(pixelOffset + y * xorRowBytes + x, topDownIndices[srcRow * w + x]);
            }
        }
        writeAndMask(buf, pixelOffset + xorRowBytes * h, w, h, andRowBytes, transparentMask);
        return buf.array();
    }

    private static void writeDibHeader(ByteBuffer buf, int w, int h, int bitCount, int clrUsed) {
        buf.putInt(0, 40);
        buf.putInt(4, w);
        buf.putInt(8, h * 2);
        buf.putShort(12, (short) 1);
        buf.putShort(14, (short) bitCount);
        buf.putInt(16, 0); // BI_RGB
        buf.putInt(32, clrUsed);
    }

    private static void writeAndMask(ByteBuffer buf, int maskOffset, int w, int h, int andRowBytes, boolean[] topDownTransparent) {
        for (int y = 0; y < h; y++) {
            int srcRow = h - 1 - y;
            for (int x = 0; x < w; x++) {
                if (topDownTransparent[srcRow * w + x]) {
                    int bytePos = maskOffset + y * andRowBytes + (x >> 3);
                    byte cur = buf.get(bytePos);
                    buf.put(bytePos, (byte) (cur | (1 << (7 - (x & 7)))));
                }
            }
        }
    }

    private static void removeTree(File file) {
        if (file == null || !file.exists()) {
            return;
        }
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    removeTree(child);
                }
            }
        }
        file.delete();
    }

    public static void main(String[] args) throws Exception {
        File tmpRoot = Files.createTempDirectory("exe-icon-test-").toFile();
        try {
            // Case 1: PNG-encoded 256x256 icon (PE32+)
            byte[] png256 = buildMinimalPngStub(256, 256);
            File pePng = new File(tmpRoot, "case1_png.exe");
            Files.write(pePng.toPath(), buildSyntheticPe(true, 1, Arrays.asList(
                    new IconResourceSpec(1, 256, 256, 32, png256)
            )));
            ExeIconExtractor.DecodedIcon dec1 = ExeIconExtractor.decodeIconFromPe(pePng);
            check(dec1 != null && dec1.width == 256 && dec1.height == 256, "Case 1 PNG 256x256 dimensions");
            check(dec1.pngBytes != null && Arrays.equals(dec1.pngBytes, png256), "Case 1 PNG bytes preserved");

            // Case 2: 32bpp DIB icon with real alpha channel (PE32)
            int[] argb32 = {
                0x80FF0000, 0xFF00FF00,
                0x400000FF, 0x00FFFFFF
            };
            File pe32Alpha = new File(tmpRoot, "case2_32bpp_alpha.exe");
            Files.write(pe32Alpha.toPath(), buildSyntheticPe(false, 1, Arrays.asList(
                    new IconResourceSpec(1, 2, 2, 32, buildDib32(2, 2, argb32, false, null))
            )));
            ExeIconExtractor.DecodedIcon dec2 = ExeIconExtractor.decodeIconFromPe(pe32Alpha);
            check(dec2 != null && dec2.width == 2 && dec2.height == 2, "Case 2 32bpp alpha dimensions");
            check(Arrays.equals(dec2.argbPixels, argb32), "Case 2 32bpp alpha pixels decoded bottom-up");

            // Case 3: 32bpp DIB icon with all-zero alpha -> 1bpp AND-mask fallback
            int[] rgbForZeroAlpha = {
                0x00112233, 0x00445566,
                0x00778899, 0x00AABBCC
            };
            boolean[] mask3 = {
                false, true,
                true, false
            };
            File pe32ZeroAlpha = new File(tmpRoot, "case3_32bpp_zero_alpha.exe");
            Files.write(pe32ZeroAlpha.toPath(), buildSyntheticPe(false, 1, Arrays.asList(
                    new IconResourceSpec(1, 2, 2, 32, buildDib32(2, 2, rgbForZeroAlpha, true, mask3))
            )));
            ExeIconExtractor.DecodedIcon dec3 = ExeIconExtractor.decodeIconFromPe(pe32ZeroAlpha);
            check(dec3 != null && dec3.argbPixels != null, "Case 3 32bpp zero-alpha decoded");
            check(dec3.argbPixels[0] == 0xFF112233 && dec3.argbPixels[1] == 0
                    && dec3.argbPixels[2] == 0 && dec3.argbPixels[3] == 0xFFAABBCC,
                    "Case 3 32bpp zero-alpha applies 1bpp AND mask");

            // Case 4: 24bpp DIB icon + 1bpp AND mask (odd width 3x2 to test 4-byte row stride padding)
            int[] rgb24 = {
                0xFF0000, 0x00FF00, 0x0000FF,
                0x123456, 0xABCDEF, 0x654321
            };
            boolean[] mask24 = {
                false, false, true,
                true, false, false
            };
            File pe24 = new File(tmpRoot, "case4_24bpp.exe");
            Files.write(pe24.toPath(), buildSyntheticPe(true, 1, Arrays.asList(
                    new IconResourceSpec(1, 3, 2, 24, buildDib24(3, 2, rgb24, mask24))
            )));
            ExeIconExtractor.DecodedIcon dec4 = ExeIconExtractor.decodeIconFromPe(pe24);
            check(dec4 != null && dec4.width == 3 && dec4.height == 2, "Case 4 24bpp 3x2 dimensions");
            check(dec4.argbPixels[0] == 0xFFFF0000 && dec4.argbPixels[1] == 0xFF00FF00 && dec4.argbPixels[2] == 0
                    && dec4.argbPixels[3] == 0 && dec4.argbPixels[4] == 0xFFABCDEF && dec4.argbPixels[5] == 0xFF654321,
                    "Case 4 24bpp pixels + stride + AND mask");

            // Case 5: 8bpp paletted DIB icon + 1bpp AND mask
            int[] palette8 = {0x000000, 0xFF0000, 0x00FF00, 0x0000FF};
            byte[] indices8 = {
                1, 2,
                3, 0
            };
            boolean[] mask8 = {
                false, false,
                false, true
            };
            File pe8 = new File(tmpRoot, "case5_8bpp.exe");
            Files.write(pe8.toPath(), buildSyntheticPe(false, 1, Arrays.asList(
                    new IconResourceSpec(1, 2, 2, 8, buildDib8(2, 2, palette8, indices8, mask8))
            )));
            ExeIconExtractor.DecodedIcon dec5 = ExeIconExtractor.decodeIconFromPe(pe8);
            check(dec5 != null && dec5.width == 2 && dec5.height == 2, "Case 5 8bpp dimensions");
            check(dec5.argbPixels[0] == 0xFFFF0000 && dec5.argbPixels[1] == 0xFF00FF00
                    && dec5.argbPixels[2] == 0xFF0000FF && dec5.argbPixels[3] == 0,
                    "Case 5 8bpp palette + AND mask");

            // Case 5b: 4bpp paletted DIB icon + 1bpp AND mask
            int[] palette4 = new int[16];
            palette4[1] = 0x112233;
            palette4[15] = 0xAABBCC;
            byte[] dib4 = new byte[40 + 16 * 4 + 4 * 2 + 4 * 2];
            ByteBuffer bb4 = ByteBuffer.wrap(dib4).order(ByteOrder.LITTLE_ENDIAN);
            writeDibHeader(bb4, 2, 2, 4, 16);
            bb4.putInt(40 + 1 * 4, 0x00112233);
            bb4.putInt(40 + 15 * 4, 0x00AABBCC);
            int pix4 = 40 + 16 * 4;
            // bottom row (y=1): indices (15, 1) -> 0xF1; top row (y=0): indices (1, 15) -> 0x1F
            dib4[pix4] = (byte) 0xF1;
            dib4[pix4 + 4] = (byte) 0x1F;
            // AND mask: bottom row pixel (1,1) transparent (bit 6 = 0x40), top row opaque
            dib4[pix4 + 8] = (byte) 0x40;
            File pe4 = new File(tmpRoot, "case5b_4bpp.exe");
            Files.write(pe4.toPath(), buildSyntheticPe(false, 1, Arrays.asList(
                    new IconResourceSpec(1, 2, 2, 4, dib4)
            )));
            ExeIconExtractor.DecodedIcon dec4bpp = ExeIconExtractor.decodeIconFromPe(pe4);
            check(dec4bpp != null && dec4bpp.argbPixels[0] == 0xFF112233 && dec4bpp.argbPixels[1] == 0xFFAABBCC
                    && dec4bpp.argbPixels[2] == 0xFFAABBCC && dec4bpp.argbPixels[3] == 0,
                    "Case 5b 4bpp palette + AND mask");

            // Case 5c: 1bpp paletted DIB icon + top-down negative biHeight
            byte[] dib1 = new byte[40 + 2 * 4 + 4 * 2 + 4 * 2];
            ByteBuffer bb1 = ByteBuffer.wrap(dib1).order(ByteOrder.LITTLE_ENDIAN);
            bb1.putInt(0, 40);
            bb1.putInt(4, 2);
            bb1.putInt(8, -4); // top-down, actual height = 2
            bb1.putShort(12, (short) 1);
            bb1.putShort(14, (short) 1);
            bb1.putInt(32, 2);
            bb1.putInt(40, 0x00000000);
            bb1.putInt(44, 0x00FFFFFF);
            int pix1 = 40 + 8;
            // Top-down row 0: (1, 0) -> 0x80; row 1: (0, 1) -> 0x40
            dib1[pix1] = (byte) 0x80;
            dib1[pix1 + 4] = (byte) 0x40;
            // AND mask row 1 pixel 0 transparent (0x80)
            dib1[pix1 + 8 + 4] = (byte) 0x80;
            File pe1 = new File(tmpRoot, "case5c_1bpp_topdown.exe");
            Files.write(pe1.toPath(), buildSyntheticPe(false, 1, Arrays.asList(
                    new IconResourceSpec(1, 2, 2, 1, dib1)
            )));
            ExeIconExtractor.DecodedIcon dec1bpp = ExeIconExtractor.decodeIconFromPe(pe1);
            check(dec1bpp != null && dec1bpp.argbPixels[0] == 0xFFFFFFFF && dec1bpp.argbPixels[1] == 0xFF000000
                    && dec1bpp.argbPixels[2] == 0 && dec1bpp.argbPixels[3] == 0xFFFFFFFF,
                    "Case 5c 1bpp top-down + AND mask");

            // Case 6: Truncated / invalid / no-icon .exe -> graceful null
            File truncated = new File(tmpRoot, "truncated.exe");
            Files.write(truncated.toPath(), new byte[]{'M', 'Z', 0, 1, 2, 3, 4, 5});
            check(ExeIconExtractor.decodeIconFromPe(truncated) == null, "Case 6 truncated EXE returns null");

            File randomBytes = new File(tmpRoot, "invalid.exe");
            byte[] junk = new byte[512];
            Arrays.fill(junk, (byte) 0xAA);
            Files.write(randomBytes.toPath(), junk);
            check(ExeIconExtractor.decodeIconFromPe(randomBytes) == null, "Case 6 invalid EXE returns null");

            File noIcon = new File(tmpRoot, "noicon.exe");
            Files.write(noIcon.toPath(), buildSyntheticPe(false, 1, new ArrayList<IconResourceSpec>()));
            check(ExeIconExtractor.decodeIconFromPe(noIcon) == null, "Case 6 no-icon EXE returns null");

            // Case 7: findPrimaryExe ranking
            File gameDir = new File(tmpRoot, "gameDir");
            gameDir.mkdirs();
            byte[] validPe = buildSyntheticPe(false, 1, Arrays.asList(
                    new IconResourceSpec(1, 2, 2, 32, buildDib32(2, 2, argb32, false, null))
            ));
            // Large utility EXEs should lose to main game EXE
            byte[] largeUnins = new byte[validPe.length + 10000];
            System.arraycopy(validPe, 0, largeUnins, 0, validPe.length);
            Files.write(new File(gameDir, "unins000.exe").toPath(), largeUnins);
            Files.write(new File(gameDir, "config.exe").toPath(), largeUnins);
            // Helper EXE without icon
            byte[] noIconLarge = new byte[validPe.length + 5000];
            Files.write(new File(gameDir, "helper.exe").toPath(), noIconLarge);
            // Main game EXE with icon
            Files.write(new File(gameDir, "EmberfallGame.exe").toPath(), validPe);

            File primary = ExeIconExtractor.findPrimaryExe(gameDir);
            check(primary != null && "EmberfallGame.exe".equals(primary.getName()),
                    "Case 7 findPrimaryExe prefers main game .exe with icon over utility/no-icon EXEs, got: "
                    + (primary == null ? "null" : primary.getName()));

            // Also verify preference for higher resolution / 32bpp in GRPICONDIR and fallback
            File multiIconExe = new File(tmpRoot, "multi.exe");
            byte[] png64 = buildMinimalPngStub(64, 64);
            Files.write(multiIconExe.toPath(), buildSyntheticPe(false, 1, Arrays.asList(
                    new IconResourceSpec(10, 16, 16, 8, buildDib8(16, 16, palette8, new byte[256], new boolean[256])),
                    new IconResourceSpec(20, 64, 64, 32, png64)
            )));
            ExeIconExtractor.DecodedIcon decMulti = ExeIconExtractor.decodeIconFromPe(multiIconExe);
            check(decMulti != null && decMulti.width == 64 && decMulti.height == 64,
                    "Multi-entry GRPICONDIR prefers 64x64 32bpp over 16x16 8bpp");

            // Fallback when highest-scored entry fails to decode
            File fallbackExe = new File(tmpRoot, "fallback.exe");
            Files.write(fallbackExe.toPath(), buildSyntheticPe(false, 1, Arrays.asList(
                    new IconResourceSpec(10, 128, 128, 32, new byte[]{1, 2, 3, 4}), // corrupt payload
                    new IconResourceSpec(20, 2, 2, 32, buildDib32(2, 2, argb32, false, null))
            )));
            ExeIconExtractor.DecodedIcon decFallback = ExeIconExtractor.decodeIconFromPe(fallbackExe);
            check(decFallback != null && decFallback.width == 2 && decFallback.height == 2,
                    "GRPICONDIR falls back to second entry if top entry fails to decode");

            System.out.println("ExeIconExtractorTest passed (" + checks + " checks)");
        } finally {
            removeTree(tmpRoot);
        }
    }
}
