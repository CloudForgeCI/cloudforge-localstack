package com.cloudforgeci.localstack;

import com.cloudforge.core.local.StackPortRuntime;
import com.cloudforge.core.local.StackPortRuntimeProvider;

public final class LocalStackStackPortRuntimeProvider implements StackPortRuntimeProvider {

    @Override
    public StackPortRuntime runtime() {
        return LocalStackStackPortRuntime.INSTANCE;
    }
}
