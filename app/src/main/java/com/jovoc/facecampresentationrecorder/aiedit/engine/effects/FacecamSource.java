package com.jovoc.facecampresentationrecorder.aiedit.engine.effects;

/** Where the facecam circle is in one output (its crop and size applied), by output time. Pure maths. */
public interface FacecamSource {

    /** Room kept at the edge before the circle counts as outside the output. */
    double EDGE_SLACK = 0.002;

    /**
     * Fills {@code out} with the circle at {@code outputMs}: centre x and y as fractions of the output width and
     * height, then the radius as a fraction of the width and of the height.
     *
     * @return false when the plan has no facecam track
     */
    boolean circleAt(long outputMs, double[] out);

    /** Whether the whole circle is inside the output at {@code outputMs} (a crop may cut it off). */
    default boolean visibleAt(long outputMs) {
        double[] c = new double[4];
        if (!circleAt(outputMs, c)) return false;
        return c[0] - c[2] >= -EDGE_SLACK && c[0] + c[2] <= 1 + EDGE_SLACK
                && c[1] - c[3] >= -EDGE_SLACK && c[1] + c[3] <= 1 + EDGE_SLACK;
    }
}
