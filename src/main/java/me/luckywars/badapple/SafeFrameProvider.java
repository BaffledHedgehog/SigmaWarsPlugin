package me.luckywars.badapple;

import java.util.BitSet;

/** Гарантирует, что null трактуем как "повтор предыдущего кадра". */
public final class SafeFrameProvider implements FrameProvider {
    private final FrameProvider base;
    private BitSet last;

    public SafeFrameProvider(FrameProvider base) {
        this.base = base;
    }

    @Override
    public int width() {
        return base.width();
    }

    @Override
    public int height() {
        return base.height();
    }

    @Override
    public int frameCount() {
        return base.frameCount();
    }

    @Override
    public BitSet getFrame(int index) {
        BitSet f = base.getFrame(index);
        if (f != null) {
            last = (BitSet) f.clone(); // абсолютный кадр
            return (BitSet) last.clone();
        }
        // null => "нет изменений" -> вернём прошлый абсолютный (или null, если ещё не
        // было)
        return (last != null) ? (BitSet) last.clone() : null;
    }
}
