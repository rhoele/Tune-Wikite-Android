package com.auramusic.wiki.classic;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 下载完成后的元数据写入器。
 *
 * <p>三条音源链路（lucida / 声云直连 / 上传者原文件）产出的容器不同，
 * 这里按扩展名分别写标签：MP3（ID3v2.3）、M4A/MP4（udta/meta/ilst）、
 * FLAC（Vorbis Comment + PICTURE）、WAV（LIST INFO + id3 块）。
 * OGG/Opus 与 TS 分片不做处理（重分页成本高，写坏风险大）。
 *
 * <p>标签写入是「尽力而为」：任何异常都只影响元数据，不影响已下载的音频。
 */
final class AudioTagger {

    static final class Tags {
        String title = "";
        String artist = "";
        String album = "";
        String albumArtist = "";
        String genre = "";
        String year = "";
        int trackNo;
        String coverUrl = "";

        boolean empty() {
            return title.isEmpty() && artist.isEmpty() && album.isEmpty() && coverUrl.isEmpty();
        }
    }

    private static final int MAX_COVER_BYTES = 4 * 1024 * 1024;
    /** 同一张专辑的封面只下载一次 */
    private static final Map<String, byte[]> COVER_CACHE = new LinkedHashMap<String, byte[]>(2, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            return size() > 2;
        }
    };

    private AudioTagger() {
    }

    static void write(File file, Tags tags) {
        if (file == null || tags == null || tags.empty() || !file.isFile()) {
            return;
        }
        String name = file.getName().toLowerCase(Locale.ROOT);
        try {
            byte[] cover = tags.coverUrl.isEmpty() ? null : cover(tags.coverUrl);
            if (name.endsWith(".mp3")) {
                writeMp3(file, tags, cover);
            } else if (name.endsWith(".m4a") || name.endsWith(".mp4")) {
                writeMp4(file, tags, cover);
            } else if (name.endsWith(".flac")) {
                writeFlac(file, tags, cover);
            } else if (name.endsWith(".wav")) {
                writeWav(file, tags, cover);
            }
        } catch (Exception ignored) {
            // 标签失败不影响音频本身
        }
    }

    // ------------------------------------------------------------------
    // 封面
    // ------------------------------------------------------------------

    private static byte[] cover(String url) {
        synchronized (COVER_CACHE) {
            byte[] cached = COVER_CACHE.get(url);
            if (cached != null) {
                return cached;
            }
        }
        byte[] bytes = null;
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setConnectTimeout(10000);
            conn.setReadTimeout(15000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64)");
            if (conn.getResponseCode() >= 200 && conn.getResponseCode() < 300) {
                try (InputStream in = conn.getInputStream()) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[16384];
                    int len;
                    int total = 0;
                    while ((len = in.read(buf)) != -1) {
                        total += len;
                        if (total > MAX_COVER_BYTES) {
                            break;
                        }
                        out.write(buf, 0, len);
                    }
                    bytes = out.toByteArray();
                }
            }
            conn.disconnect();
        } catch (Exception ignored) {
            bytes = null;
        }
        if (bytes != null && bytes.length > 0) {
            synchronized (COVER_CACHE) {
                COVER_CACHE.put(url, bytes);
            }
        }
        return bytes;
    }

    private static boolean isPng(byte[] data) {
        return data != null && data.length > 8
                && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G';
    }

    // ------------------------------------------------------------------
    // MP3：ID3v2.3
    // ------------------------------------------------------------------

    private static void writeMp3(File file, Tags tags, byte[] cover) throws IOException {
        long audioStart = id3Length(file);
        byte[] tag = buildId3(tags, cover);
        File tmp = new File(file.getParentFile(), file.getName() + ".tagtmp");
        try (RandomAccessFile in = new RandomAccessFile(file, "r");
             OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp))) {
            out.write(tag);
            in.seek(audioStart);
            byte[] buf = new byte[65536];
            int len;
            while ((len = in.read(buf)) != -1) {
                out.write(buf, 0, len);
            }
        }
        replace(tmp, file);
    }

    private static long id3Length(File file) throws IOException {
        try (InputStream in = new java.io.FileInputStream(file)) {
            byte[] head = new byte[10];
            if (in.read(head) != 10 || head[0] != 'I' || head[1] != 'D' || head[2] != '3') {
                return 0;
            }
            int size = ((head[6] & 0x7F) << 21) | ((head[7] & 0x7F) << 14)
                    | ((head[8] & 0x7F) << 7) | (head[9] & 0x7F);
            return 10L + size + ((head[5] & 0x10) != 0 ? 10L : 0L);
        }
    }

    private static byte[] buildId3(Tags tags, byte[] cover) throws IOException {
        ByteArrayOutputStream frames = new ByteArrayOutputStream();
        id3Text(frames, "TIT2", tags.title);
        id3Text(frames, "TPE1", tags.artist);
        id3Text(frames, "TALB", tags.album);
        id3Text(frames, "TPE2", tags.albumArtist);
        id3Text(frames, "TCON", tags.genre);
        id3Text(frames, "TYER", tags.year);
        if (tags.trackNo > 0) {
            id3Text(frames, "TRCK", String.valueOf(tags.trackNo));
        }
        if (cover != null && cover.length > 0) {
            ByteArrayOutputStream body = new ByteArrayOutputStream();
            body.write(0x00);
            body.write((isPng(cover) ? "image/png" : "image/jpeg").getBytes(StandardCharsets.ISO_8859_1));
            body.write(0x00);
            body.write(0x03);
            body.write(0x00);
            body.write(cover);
            frame(frames, "APIC", body.toByteArray());
        }
        byte[] body = frames.toByteArray();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(new byte[]{'I', 'D', '3', 0x03, 0x00, 0x00});
        int size = body.length;
        out.write((size >> 21) & 0x7F);
        out.write((size >> 14) & 0x7F);
        out.write((size >> 7) & 0x7F);
        out.write(size & 0x7F);
        out.write(body);
        return out.toByteArray();
    }

    private static void id3Text(ByteArrayOutputStream out, String id, String value) throws IOException {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(0x01);
        body.write(0xFF);
        body.write(0xFE);
        body.write(value.getBytes(StandardCharsets.UTF_16LE));
        frame(out, id, body.toByteArray());
    }

    private static void frame(ByteArrayOutputStream out, String id, byte[] body) throws IOException {
        byte[] name = id.getBytes(StandardCharsets.ISO_8859_1);
        out.write(name, 0, 4);
        int size = body.length;
        out.write((size >> 24) & 0xFF);
        out.write((size >> 16) & 0xFF);
        out.write((size >> 8) & 0xFF);
        out.write(size & 0xFF);
        out.write(0x00);
        out.write(0x00);
        out.write(body);
    }

    // ------------------------------------------------------------------
    // M4A / MP4：udta/meta/ilst
    // ------------------------------------------------------------------

    private static void writeMp4(File file, Tags tags, byte[] cover) throws IOException {
        long[] moov = findTopBox(file, "moov");
        if (moov == null || moov[1] > Integer.MAX_VALUE) {
            return;
        }
        byte[] moovData = readRange(file, moov[0], (int) moov[1]);
        if (indexOf(moovData, "ilst") >= 0) {
            return;
        }
        byte[] udta = buildUdta(tags, cover);
        boolean moovLast = moov[0] + moov[1] == file.length();
        if (!moovLast) {
            adjustChunkOffsets(moovData, 0, moovData.length, udta.length);
        }
        writeU32(moovData, 0, (int) moov[1] + udta.length);
        File tmp = new File(file.getParentFile(), file.getName() + ".tagtmp");
        try (RandomAccessFile in = new RandomAccessFile(file, "r");
             OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp))) {
            copyRange(in, out, 0, moov[0]);
            out.write(moovData);
            out.write(udta);
            copyRange(in, out, moov[0] + moov[1], file.length());
        }
        replace(tmp, file);
    }

    private static byte[] buildUdta(Tags tags, byte[] cover) throws IOException {
        ByteArrayOutputStream ilst = new ByteArrayOutputStream();
        mp4Text(ilst, "©nam", tags.title);
        mp4Text(ilst, "©ART", tags.artist);
        mp4Text(ilst, "aART", tags.albumArtist);
        mp4Text(ilst, "©alb", tags.album);
        mp4Text(ilst, "©gen", tags.genre);
        mp4Text(ilst, "©day", tags.year);
        if (tags.trackNo > 0) {
            ByteArrayOutputStream trkn = new ByteArrayOutputStream();
            trkn.write(0);
            trkn.write(0);
            trkn.write((tags.trackNo >> 8) & 0xFF);
            trkn.write(tags.trackNo & 0xFF);
            trkn.write(0);
            trkn.write(0);
            trkn.write(0);
            trkn.write(0);
            ilst.write(box("trkn", dataBox(0, trkn.toByteArray())));
        }
        if (cover != null && cover.length > 0) {
            ilst.write(box("covr", dataBox(isPng(cover) ? 14 : 13, cover)));
        }
        byte[] hdlr = box("hdlr", new byte[]{
                0, 0, 0, 0,
                0, 0, 0, 0,
                'm', 'd', 'i', 'r',
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0});
        ByteArrayOutputStream metaBody = new ByteArrayOutputStream();
        metaBody.write(new byte[4]);
        metaBody.write(hdlr);
        metaBody.write(box("ilst", ilst.toByteArray()));
        return box("udta", box("meta", metaBody.toByteArray()));
    }

    private static void mp4Text(ByteArrayOutputStream ilst, String key, String value) throws IOException {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        ilst.write(box(key, dataBox(1, value.getBytes(StandardCharsets.UTF_8))));
    }

    private static byte[] dataBox(int type, byte[] payload) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write((type >> 24) & 0xFF);
        body.write((type >> 16) & 0xFF);
        body.write((type >> 8) & 0xFF);
        body.write(type & 0xFF);
        body.write(new byte[4]);
        body.write(payload);
        return box("data", body.toByteArray());
    }

    private static byte[] box(String type, byte[] body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int size = body.length + 8;
        out.write((size >> 24) & 0xFF);
        out.write((size >> 16) & 0xFF);
        out.write((size >> 8) & 0xFF);
        out.write(size & 0xFF);
        out.write(type.getBytes(StandardCharsets.ISO_8859_1), 0, 4);
        out.write(body);
        return out.toByteArray();
    }

    private static final String[] MP4_CONTAINERS = {
            "moov", "trak", "mdia", "minf", "stbl", "edts", "dinf", "mvex", "udta"
    };

    /** moov 不在文件末尾时，插入 udta 会让后续数据整体后移，需要修正 stco/co64。 */
    private static void adjustChunkOffsets(byte[] data, int start, int end, int delta) {
        int p = start;
        while (p + 8 <= end) {
            int size = (int) readU32(data, p);
            String type = new String(data, p + 4, 4, StandardCharsets.ISO_8859_1);
            int header = 8;
            if (size == 1) {
                if (p + 16 > end) {
                    return;
                }
                size = (int) readU64(data, p + 8);
                header = 16;
            }
            if (size < header || p + size > end) {
                return;
            }
            int body = p + header;
            int bodyEnd = p + size;
            if ("stco".equals(type)) {
                int count = (int) readU32(data, body + 4);
                for (int i = 0; i < count && body + 8 + i * 4 + 4 <= bodyEnd; i++) {
                    long value = readU32(data, body + 8 + i * 4);
                    writeU32(data, body + 8 + i * 4, (int) (value + delta));
                }
            } else if ("co64".equals(type)) {
                int count = (int) readU32(data, body + 4);
                for (int i = 0; i < count && body + 8 + i * 8 + 8 <= bodyEnd; i++) {
                    long value = readU64(data, body + 8 + i * 8);
                    writeU64(data, body + 8 + i * 8, value + delta);
                }
            } else {
                for (String container : MP4_CONTAINERS) {
                    if (container.equals(type)) {
                        adjustChunkOffsets(data, body, bodyEnd, delta);
                        break;
                    }
                }
            }
            p = bodyEnd;
        }
    }

    private static long[] findTopBox(File file, String wanted) throws IOException {
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            long p = 0;
            long length = in.length();
            byte[] head = new byte[16];
            while (p + 8 <= length) {
                in.seek(p);
                in.readFully(head, 0, 8);
                int size = (int) readU32(head, 0);
                String type = new String(head, 4, 4, StandardCharsets.ISO_8859_1);
                int header = 8;
                if (size == 1) {
                    in.readFully(head, 8, 8);
                    size = (int) readU64(head, 8);
                    header = 16;
                } else if (size == 0) {
                    size = (int) (length - p);
                }
                if (size < header) {
                    return null;
                }
                if (wanted.equals(type)) {
                    return new long[]{p, size};
                }
                p += size;
            }
        }
        return null;
    }

    private static byte[] readRange(File file, long offset, int length) throws IOException {
        byte[] data = new byte[length];
        try (RandomAccessFile in = new RandomAccessFile(file, "r")) {
            in.seek(offset);
            in.readFully(data);
        }
        return data;
    }

    private static void copyRange(RandomAccessFile in, OutputStream out, long from, long to)
            throws IOException {
        in.seek(from);
        byte[] buf = new byte[65536];
        long left = to - from;
        while (left > 0) {
            int read = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (read < 0) {
                break;
            }
            out.write(buf, 0, read);
            left -= read;
        }
    }

    // ------------------------------------------------------------------
    // FLAC：Vorbis Comment + PICTURE
    // ------------------------------------------------------------------

    private static void writeFlac(File file, Tags tags, byte[] cover) throws IOException {
        byte[] data = readRange(file, 0, (int) file.length());
        if (data.length < 8 || data[0] != 'f' || data[1] != 'L' || data[2] != 'a' || data[3] != 'C') {
            return;
        }
        List<byte[]> blocks = new ArrayList<>();
        int p = 4;
        int audioStart = data.length;
        while (p + 4 <= data.length) {
            int type = data[p] & 0x7F;
            int length = ((data[p + 1] & 0xFF) << 16) | ((data[p + 2] & 0xFF) << 8)
                    | (data[p + 3] & 0xFF);
            int body = p + 4;
            if (body + length > data.length) {
                return;
            }
            if (type != 4 && type != 6) {
                byte[] block = new byte[length + 4];
                System.arraycopy(data, p, block, 0, length + 4);
                blocks.add(block);
            }
            p = body + length;
            if ((data[body - 4] & 0x80) != 0) {
                audioStart = p;
                break;
            }
        }
        blocks.add(flacBlock(4, vorbisComment(tags)));
        if (cover != null && cover.length > 0) {
            blocks.add(flacBlock(6, flacPicture(cover)));
        }
        File tmp = new File(file.getParentFile(), file.getName() + ".tagtmp");
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(tmp))) {
            out.write(new byte[]{'f', 'L', 'a', 'C'});
            for (int i = 0; i < blocks.size(); i++) {
                byte[] block = blocks.get(i);
                block[0] = (byte) ((i == blocks.size() - 1) ? (block[0] | 0x80) : (block[0] & 0x7F));
                out.write(block);
            }
            out.write(data, audioStart, data.length - audioStart);
        }
        replace(tmp, file);
    }

    private static byte[] flacBlock(int type, byte[] body) {
        byte[] block = new byte[body.length + 4];
        block[0] = (byte) type;
        block[1] = (byte) ((body.length >> 16) & 0xFF);
        block[2] = (byte) ((body.length >> 8) & 0xFF);
        block[3] = (byte) (body.length & 0xFF);
        System.arraycopy(body, 0, block, 4, body.length);
        return block;
    }

    private static byte[] vorbisComment(Tags tags) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] vendor = "SoundCloud".getBytes(StandardCharsets.UTF_8);
        le32(out, vendor.length);
        out.write(vendor);
        List<String> fields = new ArrayList<>();
        addVorbis(fields, "TITLE", tags.title);
        addVorbis(fields, "ARTIST", tags.artist);
        addVorbis(fields, "ALBUM", tags.album);
        addVorbis(fields, "ALBUMARTIST", tags.albumArtist);
        addVorbis(fields, "GENRE", tags.genre);
        addVorbis(fields, "DATE", tags.year);
        if (tags.trackNo > 0) {
            addVorbis(fields, "TRACKNUMBER", String.valueOf(tags.trackNo));
        }
        le32(out, fields.size());
        for (String field : fields) {
            byte[] bytes = field.getBytes(StandardCharsets.UTF_8);
            le32(out, bytes.length);
            out.write(bytes);
        }
        return out.toByteArray();
    }

    private static void addVorbis(List<String> fields, String key, String value) {
        if (value != null && !value.trim().isEmpty()) {
            fields.add(key + "=" + value.trim());
        }
    }

    private static byte[] flacPicture(byte[] cover) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        be32(out, 3);
        String mime = isPng(cover) ? "image/png" : "image/jpeg";
        be32(out, mime.length());
        out.write(mime.getBytes(StandardCharsets.US_ASCII));
        be32(out, 0);
        be32(out, 0);
        be32(out, 0);
        be32(out, 0);
        be32(out, 0);
        be32(out, cover.length);
        out.write(cover);
        return out.toByteArray();
    }

    // ------------------------------------------------------------------
    // WAV：LIST INFO + id3 块
    // ------------------------------------------------------------------

    private static void writeWav(File file, Tags tags, byte[] cover) throws IOException {
        byte[] id3 = buildId3(tags, cover);
        ByteArrayOutputStream info = new ByteArrayOutputStream();
        wavInfo(info, "INAM", tags.title);
        wavInfo(info, "IART", tags.artist);
        wavInfo(info, "IPRD", tags.album);
        wavInfo(info, "ICRD", tags.year);
        wavInfo(info, "IGNR", tags.genre);
        if (tags.trackNo > 0) {
            wavInfo(info, "ITRK", String.valueOf(tags.trackNo));
        }
        byte[] list = info.size() == 0 ? new byte[0] : chunk("LIST", concat("INFO".getBytes(StandardCharsets.US_ASCII), info.toByteArray()));
        byte[] id3Chunk = chunk("id3 ", id3);
        long added = list.length + id3Chunk.length;
        try (RandomAccessFile raf = new RandomAccessFile(file, "rw")) {
            raf.seek(0);
            byte[] head = new byte[12];
            raf.readFully(head);
            if (head[0] != 'R' || head[1] != 'I' || head[2] != 'F' || head[3] != 'F') {
                return;
            }
            long size = readLe32(head, 4) + added;
            raf.seek(4);
            raf.write(new byte[]{(byte) (size & 0xFF), (byte) ((size >> 8) & 0xFF),
                    (byte) ((size >> 16) & 0xFF), (byte) ((size >> 24) & 0xFF)});
            raf.seek(file.length());
            raf.write(list);
            raf.write(id3Chunk);
        }
    }

    private static void wavInfo(ByteArrayOutputStream out, String id, String value) throws IOException {
        if (value == null || value.trim().isEmpty()) {
            return;
        }
        byte[] bytes = (value.trim() + "\u0000").getBytes(StandardCharsets.UTF_8);
        out.write(id.getBytes(StandardCharsets.ISO_8859_1), 0, 4);
        le32(out, bytes.length);
        out.write(bytes);
        if ((bytes.length & 1) == 1) {
            out.write(0);
        }
    }

    private static byte[] chunk(String id, byte[] body) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(id.getBytes(StandardCharsets.ISO_8859_1), 0, 4);
        le32(out, body.length);
        out.write(body);
        if ((body.length & 1) == 1) {
            out.write(0);
        }
        return out.toByteArray();
    }

    // ------------------------------------------------------------------
    // 通用工具
    // ------------------------------------------------------------------

    private static byte[] concat(byte[] a, byte[] b) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(a);
        out.write(b);
        return out.toByteArray();
    }

    private static void replace(File tmp, File target) throws IOException {
        if (!tmp.renameTo(target)) {
            try (InputStream in = new java.io.FileInputStream(tmp);
                 OutputStream out = new BufferedOutputStream(new FileOutputStream(target))) {
                byte[] buf = new byte[65536];
                int len;
                while ((len = in.read(buf)) != -1) {
                    out.write(buf, 0, len);
                }
            }
            if (!tmp.delete()) {
                tmp.deleteOnExit();
            }
        }
    }

    private static int indexOf(byte[] data, String ascii) {
        byte[] needle = ascii.getBytes(StandardCharsets.ISO_8859_1);
        outer:
        for (int i = 0; i + needle.length <= data.length; i++) {
            for (int j = 0; j < needle.length; j++) {
                if (data[i + j] != needle[j]) {
                    continue outer;
                }
            }
            return i;
        }
        return -1;
    }

    private static long readU32(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF) << 24) | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8) | (data[offset + 3] & 0xFF);
    }

    private static long readU64(byte[] data, int offset) {
        return (readU32(data, offset) << 32) | readU32(data, offset + 4);
    }

    private static void writeU32(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >> 24) & 0xFF);
        data[offset + 1] = (byte) ((value >> 16) & 0xFF);
        data[offset + 2] = (byte) ((value >> 8) & 0xFF);
        data[offset + 3] = (byte) (value & 0xFF);
    }

    private static void writeU64(byte[] data, int offset, long value) {
        for (int i = 0; i < 8; i++) {
            data[offset + i] = (byte) ((value >> (56 - i * 8)) & 0xFF);
        }
    }

    private static long readLe32(byte[] data, int offset) {
        return ((long) (data[offset] & 0xFF)) | ((data[offset + 1] & 0xFF) << 8)
                | ((data[offset + 2] & 0xFF) << 16) | ((data[offset + 3] & 0xFF) << 24);
    }

    private static void le32(ByteArrayOutputStream out, int value) {
        out.write(value & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 24) & 0xFF);
    }

    private static void be32(ByteArrayOutputStream out, int value) {
        out.write((value >> 24) & 0xFF);
        out.write((value >> 16) & 0xFF);
        out.write((value >> 8) & 0xFF);
        out.write(value & 0xFF);
    }
}
