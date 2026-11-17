package com.jovoc.facecampresentationrecorder.aiedit.engine.contract;

import org.json.JSONException;
import org.json.JSONObject;

/** One output ratio: size, bitrate and the safe rectangle overlays stay inside (fractions of the output). */
public final class OutputSpec {
    public final String ratio;
    public final int width;
    public final int height;
    public final int bitrate;
    public final double safeX0;
    public final double safeX1;
    public final double safeY0;
    public final double safeY1;

    public OutputSpec(String ratio, int width, int height, int bitrate,
                      double safeX0, double safeX1, double safeY0, double safeY1) {
        this.ratio = ratio;
        this.width = width;
        this.height = height;
        this.bitrate = bitrate;
        this.safeX0 = safeX0;
        this.safeX1 = safeX1;
        this.safeY0 = safeY0;
        this.safeY1 = safeY1;
    }

    static OutputSpec fromJson(String ratio, JSONObject o) throws JSONException {
        JSONObject s = o.getJSONObject("safe");
        return new OutputSpec(ratio, o.getInt("width"), o.getInt("height"), o.getInt("bitrate"),
                s.getDouble("x0"), s.getDouble("x1"), s.getDouble("y0"), s.getDouble("y1"));
    }

    /** The x of a zone centre (a fraction across the safe rectangle) as a fraction of the output width. */
    public double zoneX(double zoneFractionX) {
        return safeX0 + zoneFractionX * (safeX1 - safeX0);
    }

    /** The y of a zone centre (a fraction down the safe rectangle) as a fraction of the output height. */
    public double zoneY(double zoneFractionY) {
        return safeY0 + zoneFractionY * (safeY1 - safeY0);
    }
}
