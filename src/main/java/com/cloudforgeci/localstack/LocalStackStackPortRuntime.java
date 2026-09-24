package com.cloudforgeci.localstack;

import com.cloudforge.core.local.AbstractStackPortRuntime;
import com.cloudforge.core.local.StackPortSpec;

/**
 * StackPort resource browser wired to the LocalStack gateway on {@code cfc-network}.
 */
public final class LocalStackStackPortRuntime extends AbstractStackPortRuntime {

    public static final LocalStackStackPortRuntime INSTANCE =
        new LocalStackStackPortRuntime();

    private LocalStackStackPortRuntime() {
        super(StackPortSpec.localstack());
    }
}
