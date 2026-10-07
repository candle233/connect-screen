package com.gitee.connect_screen.usbtouch;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import com.gitee.connect_screen.usbtouch.TouchFrameQueue.Frame;
import com.gitee.connect_screen.usbtouch.TouchFrameQueue.Point;

/** Main-thread streaming bridge. Continuations retain pointer identity across short segments. */
final class GestureStream {
    interface Listener { void fault(String reason); void cancelled(); }
    private final AccessibilityService service;
    private final Listener listener;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final TouchFrameQueue queue=new TouchFrameQueue();
    private final Map<Integer,GestureDescription.StrokeDescription> strokes=new TreeMap<>();
    private final Map<Integer,Point> positions=new TreeMap<>();
    private boolean inFlight, stopped, suppressed;
    private int generation;
    long completed, cancelled;
    private final Runnable pumpTask=this::pump;
    private final Runnable timeout=() -> fatal("系统未响应触控输出，请重新开启无障碍服务");
    GestureStream(AccessibilityService service, Listener listener) { this.service=service; this.listener=listener; }
    void offer(Frame frame) {
        if (stopped) return;
        if (suppressed) { if (frame.active.isEmpty()) suppressed=false; return; }
        try { queue.offer(frame); pump(); }
        catch (RuntimeException e) { fatal(e.getMessage()); }
    }
    void endAndSuppress() {
        suppressed=true;
        queue.clear();
        queue.offer(new Frame(Collections.emptyMap()));
        pump();
    }
    void stop() { stopped=true; endAndSuppress(); }
    private void fatal(String reason) {
        generation++; main.removeCallbacks(pumpTask); main.removeCallbacks(timeout);
        queue.clear(); strokes.clear(); positions.clear(); inFlight=false; stopped=true;
        listener.fault(reason);
    }
    private void pump() {
        if (inFlight) return;
        main.removeCallbacks(pumpTask);
        Frame next=queue.poll();
        // A continued stroke already holds its pointer down. A no-op continuation yields no MotionEvents
        // on Android 12 and is rejected; wait for actual movement or a pointer transition instead.
        while (next!=null && next.sameActivePositions(positions)) next=queue.poll();
        if (next==null) return;
        if (next.active.isEmpty() && strokes.isEmpty()) return;
        GestureDescription.Builder builder=new GestureDescription.Builder().setDisplayId(0);
        Map<Integer,GestureDescription.StrokeDescription> continuing=new TreeMap<>();
        Map<Integer,Point> nextPositions=new TreeMap<>();
        try {
            for (Map.Entry<Integer,GestureDescription.StrokeDescription> entry:strokes.entrySet()) {
                int id=entry.getKey(); Point before=positions.get(id), after=next.points.get(id);
                boolean down=after!=null && after.down;
                Path path=new Path(); path.moveTo(before.x,before.y);
                if (after!=null && (before.x!=after.x || before.y!=after.y)) path.lineTo(after.x,after.y);
                GestureDescription.StrokeDescription stroke=entry.getValue().continueStroke(path,0,16,down);
                builder.addStroke(stroke);
                if (down) { continuing.put(id,stroke); nextPositions.put(id,after); }
            }
            for (Integer id:next.active) {
                if (strokes.containsKey(id)) continue;
                Point point=next.points.get(id); Path path=new Path(); path.moveTo(point.x,point.y);
                // The first step of a continuation must contain precisely the OLD pointers. Add a
                // newly pressed finger just after that step, or Android rejects the whole gesture.
                int start=strokes.isEmpty()?0:1;
                GestureDescription.StrokeDescription stroke=new GestureDescription.StrokeDescription(path,start,16-start,true);
                builder.addStroke(stroke); continuing.put(id,stroke); nextPositions.put(id,point);
            }
            GestureDescription gesture=builder.build();
            final int token=++generation;
            inFlight=true;
            strokes.clear(); strokes.putAll(continuing);
            positions.clear(); positions.putAll(nextPositions);
            main.postDelayed(timeout,1500);
            boolean accepted=service.dispatchGesture(gesture,new AccessibilityService.GestureResultCallback() {
                @Override public void onCompleted(GestureDescription g) {
                    if (generation!=token) return;
                    main.removeCallbacks(timeout); inFlight=false; completed++;
                    // Post instead of recursing, to let physical input and the stop switch run first.
                    main.post(pumpTask);
                }
                @Override public void onCancelled(GestureDescription g) {
                    if (generation!=token) return;
                    main.removeCallbacks(timeout); inFlight=false; cancelled++;
                    strokes.clear(); positions.clear(); queue.clear(); suppressed=true;
                    listener.cancelled(); // A touch on the phone can intentionally interrupt the external gesture.
                }
            },main);
            if (!accepted) fatal("系统拒绝触控输出，请重新开启无障碍服务");
        } catch (RuntimeException e) { fatal("触控输出失败："+e.getMessage()); }
    }
}
