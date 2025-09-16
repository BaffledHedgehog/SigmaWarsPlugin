// src/main/java/me/luckywars/badapple/DiffBinFrameProvider.java
package me.luckywars.badapple;

import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.nio.MappedByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.BitSet;

/**
 * BAF2 (raw XOR):
 * Header: 'BAF2' + int32 width + int32 height + int32 frames (BE)
 * frame0: stride bytes (bit-packed, LSB-first, row-major Z-rows, X-cols)
 * frames 1..N-1: each exactly 'stride' bytes = XOR delta to previous absolute.
 */
final class DiffBinFrameProvider implements FrameProvider, Closeable {
    private final int width, height, frames, stride;
    private final MappedByteBuffer map;
    private final int baseStart; // 16
    private final int deltasStart; // 16 + stride

    private final byte[] base; // frame0 literal
    private final byte[] prev; // rolling buffer (absolute)
    private final byte[] tmp; // scratch for reading deltas
    private BitSet cached0;
    private int nextIndex; // next frame to decode into 'prev' (>=1)

    DiffBinFrameProvider(File file) throws IOException {
        MappedByteBuffer mb;
        try (FileChannel ch = FileChannel.open(file.toPath(), StandardOpenOption.READ)) {
            mb = ch.map(FileChannel.MapMode.READ_ONLY, 0, ch.size());
        }
        mb.order(ByteOrder.BIG_ENDIAN);
        this.map = mb;

        // --- header ---
        byte[] magic = new byte[4];
        map.get(magic);
        if (!"BAF2".equals(new String(magic, java.nio.charset.StandardCharsets.US_ASCII))) {
            throw new IOException("Invalid magic");
        }
        this.width = map.getInt();
        this.height = map.getInt();
        this.frames = map.getInt();
        if (width <= 0 || height <= 0 || frames <= 0) {
            throw new IOException("Invalid header: " + width + "x" + height + " frames=" + frames);
        }

        this.stride = ((width * height) + 7) >> 3;
        this.baseStart = 16;
        this.deltasStart = baseStart + stride;

        // --- frame0 ---
        map.position(baseStart);
        ensureRemaining(stride);
        this.base = new byte[stride];
        map.get(base);
        this.prev = base.clone();
        this.cached0 = BitSet.valueOf(base);
        this.tmp = new byte[stride];

        resetToStart(); // nextIndex = 1, position = deltasStart
    }

    @Override
    public int width() {
        return width;
    }

    @Override
    public int height() {
        return height;
    }

    @Override
    public int frameCount() {
        return frames;
    }

    @Override
    public BitSet getFrame(int index) {
        if (index < 0 || index >= frames)
            return null;

        if (index == 0) {
            resetToStart();
            return cached0;
        }
        if (index < nextIndex) {
            resetToStart();
        }
        while (nextIndex <= index) {
            if (!decodeNextInPlace())
                return null;
            nextIndex++;
        }
        return BitSet.valueOf(prev);
    }

    private void resetToStart() {
        map.position(deltasStart);
        System.arraycopy(base, 0, prev, 0, stride);
        cached0 = BitSet.valueOf(base);
        nextIndex = 1;
    }

    /** Reads next 'stride' bytes and XORs them into 'prev'. */
    private boolean decodeNextInPlace() {
        if (map.remaining() < stride)
            return false;
        map.get(tmp, 0, stride);
        for (int i = 0; i < stride; i++)
            prev[i] ^= tmp[i];
        return true;
    }

    private void ensureRemaining(int need) {
        if (map.remaining() < need) {
            throw new IllegalStateException("BAF2 truncated: need=" + need +
                    " rem=" + map.remaining() + " pos=" + map.position());
        }
    }

    @Override
    public void close() throws IOException {
        /* no-op */ }
}
