// src/main/java/me/luckywars/badapple/FrameProvider.java
package me.luckywars.badapple;

import java.util.BitSet;

/**
 * Supplies monochrome frames as BitSets of size WIDTH*HEIGHT; bit=true is
 * WHITE.
 */
public interface FrameProvider {
    int width();

    int height();

    int frameCount();

    /** Returns immutable BitSet view for the frame index (0-based). */
    BitSet getFrame(int index);
}
