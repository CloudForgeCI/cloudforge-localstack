package com.cloudforgeci.localstack;

import com.cloudforge.core.local.LocalEmulatorRuntime;
import com.cloudforge.core.local.LocalEmulatorRuntimeProvider;

public final class LocalStackEmulatorRuntimeProvider implements LocalEmulatorRuntimeProvider {

    @Override
    public LocalEmulatorRuntime runtime() {
        return LocalStackEmulatorRuntime.INSTANCE;
    }
}
