package com.gitee.connect_screen.usbtouch;

import java.util.Map;
import java.util.TreeMap;
import org.junit.Test;
import static org.junit.Assert.*;
import com.gitee.connect_screen.usbtouch.TouchFrameQueue.*;

public class TouchFrameQueueTest {
    private Frame frame(int id,float x,boolean down) {
        Map<Integer,Point> p=new TreeMap<>(); p.put(id,new Point(x,20,down)); return new Frame(p);
    }
    @Test public void burstRetainsDownMoveUpAndNextTap() {
        TouchFrameQueue q=new TouchFrameQueue();
        q.offer(frame(0,10,true)); q.offer(frame(0,11,true)); q.offer(frame(0,12,true));
        q.offer(frame(0,13,false)); q.offer(frame(0,14,true)); q.offer(frame(0,15,false));
        assertEquals(5,q.size());
        assertEquals(10,q.poll().points.get(0).x,0); assertEquals(12,q.poll().points.get(0).x,0);
        assertTrue(q.poll().active.isEmpty()); assertFalse(q.poll().active.isEmpty()); assertTrue(q.poll().active.isEmpty());
    }
    @Test public void changedPointerIdentityIsNeverCoalesced() {
        TouchFrameQueue q=new TouchFrameQueue(); q.offer(frame(0,1,true)); q.offer(frame(1,2,true));
        q.offer(frame(1,3,true)); q.offer(frame(1,4,true));
        assertEquals(3,q.size()); assertTrue(q.poll().active.contains(0));
        assertEquals(2,q.poll().points.get(1).x,0); assertEquals(4,q.poll().points.get(1).x,0);
    }
    @Test(expected=IllegalStateException.class) public void rejectsUnboundedTapBacklog() {
        TouchFrameQueue q=new TouchFrameQueue(); for (int i=0;i<65;i++) q.offer(frame(0,1,i%2==0));
    }
    @Test public void stationaryContinuationIsSkippedButReleaseIsNot() {
        Frame down=frame(0,10.2f,true);
        assertTrue(frame(0,10.4f,true).sameActivePositions(down.points));
        assertFalse(frame(0,10.6f,true).sameActivePositions(down.points));
        assertFalse(frame(0,10.2f,false).sameActivePositions(down.points));
    }
}
