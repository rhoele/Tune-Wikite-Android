package com.auramusic.wiki.classic;

import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * M4A/AAC → 32 bit PCM WAV。
 *
 * <p>等价于 {@code ffmpeg -vn -c:a pcm_s32le -ac 2}：只保留音频流、双声道、
 * 32 bit 小端有符号整数采样。Android 上不引入 ffmpeg 二进制（体积与许可证成本都很高），
 * 改用系统 MediaCodec 解码，再按相同语义写出 WAV 头。
 *
 * <p>说明：源是 AAC 有损，转成 32 bit 只提高容器位深，不会增加信息量；这正是原报告
 * 的结论，因此默认不勾选该选项。
 */
final class PcmWavConverter {

    private static final int TARGET_CHANNELS = 2;

    private PcmWavConverter() {
    }

    interface Progress {
        void onStatus(String text);
    }

    static File toWav32(File source, File target, Progress progress) throws IOException {
        MediaExtractor extractor = new MediaExtractor();
        MediaCodec codec = null;
        RandomAccessFile out = null;
        try {
            extractor.setDataSource(source.getAbsolutePath());
            int trackIndex = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    trackIndex = i;
                    format = f;
                    break;
                }
            }
            if (trackIndex < 0 || format == null) {
                throw new IOException("文件里没有音频流");
            }
            extractor.selectTrack(trackIndex);
            String mime = format.getString(MediaFormat.KEY_MIME);
            int sampleRate = format.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                    ? format.getInteger(MediaFormat.KEY_SAMPLE_RATE) : 44100;

            codec = MediaCodec.createDecoderByType(mime);
            codec.configure(format, null, null, 0);
            codec.start();
            if (progress != null) {
                progress.onStatus("正在解码音频…");
            }

            out = new RandomAccessFile(target, "rw");
            out.setLength(0);
            writeHeader(out, sampleRate, TARGET_CHANNELS, 0);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            boolean inputDone = false;
            boolean outputDone = false;
            long dataBytes = 0;
            int channels = TARGET_CHANNELS;
            int rate = sampleRate;
            boolean headerWritten = false;
            int pcmEncoding = 2; // AudioFormat.ENCODING_PCM_16BIT

            while (!outputDone) {
                if (!inputDone) {
                    int inIndex = codec.dequeueInputBuffer(10000);
                    if (inIndex >= 0) {
                        ByteBuffer inBuf = codec.getInputBuffer(inIndex);
                        int size = inBuf == null ? -1 : extractor.readSampleData(inBuf, 0);
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0,
                                    MediaCodec.BUFFER_FLAG_END_OF_STREAM);
                            inputDone = true;
                        } else {
                            long pts = extractor.getSampleTime();
                            codec.queueInputBuffer(inIndex, 0, size, pts, 0);
                            extractor.advance();
                        }
                    }
                }

                int outIndex = codec.dequeueOutputBuffer(info, 10000);
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat of = codec.getOutputFormat();
                    rate = of.containsKey(MediaFormat.KEY_SAMPLE_RATE)
                            ? of.getInteger(MediaFormat.KEY_SAMPLE_RATE) : rate;
                    channels = of.containsKey(MediaFormat.KEY_CHANNEL_COUNT)
                            ? of.getInteger(MediaFormat.KEY_CHANNEL_COUNT) : channels;
                    if (of.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                        pcmEncoding = of.getInteger(MediaFormat.KEY_PCM_ENCODING);
                    } else {
                        pcmEncoding = 2; // AudioFormat.ENCODING_PCM_16BIT
                    }
                    out.setLength(0);
                    writeHeader(out, rate, TARGET_CHANNELS, 0);
                    headerWritten = true;
                    dataBytes = 0;
                    continue;
                }
                if (outIndex >= 0) {
                    ByteBuffer buf = codec.getOutputBuffer(outIndex);
                    if (buf != null && info.size > 0) {
                        byte[] pcm = new byte[info.size];
                        buf.position(info.offset);
                        buf.limit(info.offset + info.size);
                        buf.get(pcm);
                        byte[] converted = convert(pcm, channels, pcmEncoding);
                        out.write(converted);
                        dataBytes += converted.length;
                    }
                    codec.releaseOutputBuffer(outIndex, false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true;
                    }
                }
            }

            if (!headerWritten && dataBytes == 0) {
                throw new IOException("解码结果为空");
            }
            patchSizes(out, dataBytes);
            return target;
        } finally {
            if (codec != null) {
                try {
                    codec.stop();
                } catch (Exception ignored) {
                    // 解码器可能未成功启动
                }
                try {
                    codec.release();
                } catch (Exception ignored) {
                    // 释放失败无需处理
                }
            }
            if (out != null) {
                try {
                    out.close();
                } catch (Exception ignored) {
                    // 关闭失败无需处理
                }
            }
            extractor.release();
        }
    }

    /** 统一转成双声道 32 bit 小端 PCM。 */
    private static byte[] convert(byte[] pcm, int srcChannels, int encoding) {
        int bytesPerSample = encoding == 4 ? 4 : 2; // 4 = float, 其余按 16 bit
        int frames = pcm.length / (bytesPerSample * Math.max(1, srcChannels));
        ByteBuffer src = ByteBuffer.wrap(pcm).order(ByteOrder.LITTLE_ENDIAN);
        ByteBuffer dst = ByteBuffer.allocate(frames * TARGET_CHANNELS * 4)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int f = 0; f < frames; f++) {
            int left = readSample(src, encoding);
            int right;
            if (srcChannels >= 2) {
                right = readSample(src, encoding);
                for (int c = 2; c < srcChannels; c++) {
                    if (bytesPerSample == 4) {
                        src.getInt();
                    } else {
                        src.getShort();
                    }
                }
            } else {
                right = left;
            }
            dst.putInt(left);
            dst.putInt(right);
        }
        return dst.array();
    }

    private static int readSample(ByteBuffer src, int encoding) {
        if (encoding == 4) {
            // ENCODING_PCM_FLOAT：映射到 32 bit 整数域
            float v = src.getFloat();
            if (v > 1f) {
                v = 1f;
            } else if (v < -1f) {
                v = -1f;
            }
            return (int) (v * Integer.MAX_VALUE);
        }
        short s = src.getShort();
        return ((int) s) << 16;
    }

    private static void writeHeader(RandomAccessFile out, int sampleRate, int channels,
                                    long dataSize) throws IOException {
        out.seek(0);
        int byteRate = sampleRate * channels * 4;
        int blockAlign = channels * 4;
        out.writeBytes("RIFF");
        writeIntLE(out, (int) (36 + dataSize));
        out.writeBytes("WAVE");
        out.writeBytes("fmt ");
        writeIntLE(out, 16);
        writeShortLE(out, 1);              // PCM
        writeShortLE(out, channels);
        writeIntLE(out, sampleRate);
        writeIntLE(out, byteRate);
        writeShortLE(out, blockAlign);
        writeShortLE(out, 32);             // 32 bit
        out.writeBytes("data");
        writeIntLE(out, (int) dataSize);
    }

    private static void patchSizes(RandomAccessFile out, long dataBytes) throws IOException {
        long fileLength = out.length();
        out.seek(4);
        writeIntLE(out, (int) (fileLength - 8));
        out.seek(40);
        writeIntLE(out, (int) dataBytes);
    }

    private static void writeIntLE(RandomAccessFile out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
        out.write((value >>> 16) & 0xFF);
        out.write((value >>> 24) & 0xFF);
    }

    private static void writeShortLE(RandomAccessFile out, int value) throws IOException {
        out.write(value & 0xFF);
        out.write((value >>> 8) & 0xFF);
    }
}
