package com.vimeo.integration_test_android;

import org.junit.Test;

/**
 * Unit tests for {@link UnserializablePlatformType}.
 */
public class UnserializablePlatformTypeTest {

    @Test
    public void verifyTypeAdapterWasGenerated() throws Exception {
        Utils.verifyTypeAdapterGeneration(UnserializablePlatformType.class);
    }
}