package com.cloudforgeci.localstack;

import com.cloudforge.core.local.DeploymentTarget;
import com.cloudforge.core.local.EmulatorEdgeLifecycle;
import com.cloudforge.core.local.EmulatorEdgeLifecycleAction;
import com.cloudforge.core.local.EmulatorLifecycle;
import com.cloudforge.core.local.EmulatorLifecycleAction;
import com.cloudforge.core.local.PlatformRuntimeAction;
import com.cloudforge.core.local.PlatformRuntimeProvider;

import java.io.IOException;

/** LocalStack-owned implementation of generic platform lifecycle capabilities. */
public final class LocalStackPlatformRuntimeProvider implements PlatformRuntimeProvider {

    @Override public DeploymentTarget target() { return DeploymentTarget.LOCALSTACK; }

    @Override
    public void execute(PlatformRuntimeAction action) throws IOException {
        switch (action) {
            case START -> EmulatorLifecycle.execute(target(), EmulatorLifecycleAction.START);
            case STOP -> EmulatorLifecycle.execute(target(), EmulatorLifecycleAction.STOP);
            case RESTART -> EmulatorLifecycle.execute(target(), EmulatorLifecycleAction.RESTART);
            case STATUS -> EmulatorLifecycle.execute(target(), EmulatorLifecycleAction.STATUS);
            case RECONCILE_EDGE -> EmulatorEdgeLifecycle.execute(EmulatorEdgeLifecycleAction.RECONCILE);
        }
    }
}
