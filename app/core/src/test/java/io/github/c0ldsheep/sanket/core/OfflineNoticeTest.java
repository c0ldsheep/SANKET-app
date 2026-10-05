package io.github.c0ldsheep.sanket.core;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class OfflineNoticeTest {
    @Test
    public void buildsJsonWithEscapesAndNulls() {
        String json = OfflineNotice.json(0L, null, Double.NaN, 73.02972, "405874", 180, "lift \"B\"\nfloor 3");
        assertEquals("{\"type\":\"going_offline\",\"time\":\"1970-01-01T00:00:00Z\",\"zone\":null,\"lat\":null,"
                + "\"lon\":73.0297,\"operator\":\"405874\",\"expected_back_s\":180,"
                + "\"reason\":\"lift \\\"B\\\"\\nfloor 3\"}", json);
    }
}
