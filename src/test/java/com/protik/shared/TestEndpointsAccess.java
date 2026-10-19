package com.protik.shared;

/** Lets tests in other packages read the package-private counter of {@link TestEndpoints}. */
public final class TestEndpointsAccess {

    private TestEndpointsAccess() {
    }

    public static int created() {
        return TestEndpoints.CREATED.get();
    }
}
