package com.jovoc.facecampresentationrecorder.aiedit.engine.timeline;

/** A crop of the master in whole pixels (even sizes, so an encoder accepts it). Not an Android Rect. */
public final class CropRect {
    public final int x;
    public final int y;
    public final int w;
    public final int h;

    public CropRect(int x, int y, int w, int h) {
        this.x = x;
        this.y = y;
        this.w = w;
        this.h = h;
    }

    /** The left edge as a fraction of the master width (the request's {@code crop.x}). */
    public double fractionX(int masterW) {
        return (double) x / masterW;
    }

    /** The top edge as a fraction of the master height (the request's {@code crop.y}). */
    public double fractionY(int masterH) {
        return (double) y / masterH;
    }

    /** The width as a fraction of the master width (the request's {@code crop.w}). */
    public double fractionW(int masterW) {
        return (double) w / masterW;
    }

    /** The height as a fraction of the master height (the request's {@code crop.h}). */
    public double fractionH(int masterH) {
        return (double) h / masterH;
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof CropRect)) return false;
        CropRect r = (CropRect) o;
        return x == r.x && y == r.y && w == r.w && h == r.h;
    }

    @Override
    public int hashCode() {
        return ((x * 31 + y) * 31 + w) * 31 + h;
    }

    @Override
    public String toString() {
        return "CropRect(" + x + "," + y + " " + w + "x" + h + ")";
    }
}
