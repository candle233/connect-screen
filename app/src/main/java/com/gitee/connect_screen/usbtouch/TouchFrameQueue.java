package com.gitee.connect_screen.usbtouch;

import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/** Bounded queue: preserve DOWN/UP and pointer changes, coalesce only subsequent MOVE frames. */
public final class TouchFrameQueue {
    public static final class Point {
        public final float x, y;
        public final boolean down;
        public Point(float x, float y, boolean down) {
            if (!Float.isFinite(x) || !Float.isFinite(y) || x < 0 || y < 0) throw new IllegalArgumentException("Invalid mapped point");
            // GestureDescription rounds sampled path coordinates to pixels. Keep continuation endpoints identical.
            this.x=Math.round(x); this.y=Math.round(y); this.down=down;
        }
    }
    public static final class Frame {
        public final Map<Integer, Point> points;
        public final Set<Integer> active;
        private boolean moveOnly;
        public Frame(Map<Integer, Point> points) {
            this.points = Collections.unmodifiableMap(new TreeMap<>(points));
            Set<Integer> ids = new TreeSet<>();
            for (Map.Entry<Integer, Point> entry : points.entrySet()) {
                Point p = entry.getValue();
                if (!Float.isFinite(p.x) || !Float.isFinite(p.y) || p.x < 0 || p.y < 0)
                    throw new IllegalArgumentException("Invalid mapped point");
                if (p.down) ids.add(entry.getKey());
            }
            active = Collections.unmodifiableSet(ids);
        }
        public boolean sameActivePositions(Map<Integer,Point> previous) {
            if (!active.equals(previous.keySet())) return false;
            for (Integer id:active) {
                Point a=points.get(id), b=previous.get(id);
                if (a.x!=b.x || a.y!=b.y) return false;
            }
            return true;
        }
    }
    private final ArrayDeque<Frame> frames = new ArrayDeque<>();
    private Set<Integer> previous = Collections.emptySet();
    public void offer(Frame frame) {
        frame.moveOnly = previous.equals(frame.active);
        Frame last = frames.peekLast();
        if (last != null && last.moveOnly && frame.moveOnly && last.active.equals(frame.active)) frames.removeLast();
        if (frames.size() >= 64) throw new IllegalStateException("触控输出过慢，已安全停止");
        frames.addLast(frame);
        previous = frame.active;
    }
    public Frame poll() { return frames.pollFirst(); }
    public void clear() { frames.clear(); previous = Collections.emptySet(); }
    public int size() { return frames.size(); }
}
