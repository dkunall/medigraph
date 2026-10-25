package com.medigraph.testsupport;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.ServerSocket;

public final class FreePorts {

    private FreePorts() {
    }

    public static int next() {
        try (ServerSocket s = new ServerSocket(0)) {
            s.setReuseAddress(true);
            return s.getLocalPort();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
