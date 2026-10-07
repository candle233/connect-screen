package com.gitee.connect_screen.usbtouch;

import org.junit.Test;
import java.util.List;
import static org.junit.Assert.*;

public class IlitekReportTest {
    private byte[] report(int count) { byte[] r=new byte[64]; r[0]=4; r[55]=(byte)count; return r; }
    private void contact(byte[] r,int slot,int id,boolean down,int x,int y) {
        int i=1+5*slot; r[i]=(byte)(id|(down?64:0));
        r[i+1]=(byte)x; r[i+2]=(byte)(x>>8); r[i+3]=(byte)y; r[i+4]=(byte)(y>>8);
    }
    @Test public void releaseRecordStillCountsAsContact() {
        byte[] r=report(1); contact(r,0,0,false,11422,6397);
        List<IlitekReport.Contact> c=IlitekReport.decode(r,64);
        assertEquals(1,c.size()); assertFalse(c.get(0).down); assertEquals(11422,c.get(0).x);
    }
    @Test public void twoFingersCanReleaseIndependently() {
        byte[] r=report(2); contact(r,0,0,true,0,16384); contact(r,1,1,false,16384,0);
        List<IlitekReport.Contact> c=IlitekReport.decode(r,64);
        assertTrue(c.get(0).down); assertFalse(c.get(1).down); assertEquals(1,c.get(1).id);
    }
    @Test public void ignoresOtherDeclaredReportTypes() { assertNull(IlitekReport.decode(new byte[]{5,0,0,0,0,0},6)); }
    @Test(expected=IllegalArgumentException.class) public void rejectsTruncatedTouchReport() { IlitekReport.decode(report(1),63); }
    @Test(expected=IllegalArgumentException.class) public void rejectsUndeclaredTouch() {
        byte[] r=report(1); contact(r,1,1,true,42,43); IlitekReport.decode(r,64);
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsDuplicateIds() {
        byte[] r=report(2); contact(r,0,3,true,42,43); contact(r,1,3,true,42,43); IlitekReport.decode(r,64);
    }
    @Test(expected=IllegalArgumentException.class) public void rejectsInvalidCoordinates() {
        byte[] r=report(1); contact(r,0,0,true,20000,1); IlitekReport.decode(r,64);
    }
}
