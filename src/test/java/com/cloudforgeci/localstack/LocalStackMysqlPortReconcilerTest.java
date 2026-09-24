package com.cloudforgeci.localstack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LocalStackMysqlPortReconcilerTest {

    @Test
    void correctsACombinedHostPortValue() {
        assertEquals("cfc-localstack:4513",
            LocalStackMysqlPortReconciler.correctedValue(
                "WORDPRESS_DB_HOST", "cfc-localstack:4510", 4513));
    }

    @Test
    void correctsABarePortLiteralOnlyForPortNamedVariables() {
        assertEquals("4513",
            LocalStackMysqlPortReconciler.correctedValue("DRUPAL_DB_PORT", "4510", 4513));
        assertEquals("4513",
            LocalStackMysqlPortReconciler.correctedValue("DB_PORT", "4510", 4513));
    }

    @Test
    void leavesUnrelatedVariablesAlone() {
        assertNull(LocalStackMysqlPortReconciler.correctedValue("DB_NAME", "wordpress", 4513));
        assertNull(LocalStackMysqlPortReconciler.correctedValue(
            "WORDPRESS_DB_HOST", "cfc-localstack:4513", 4513));
        // Not a *_DB_PORT-named variable — a bare "4510" here could just be coincidence, not the
        // assumed RDS port, so it's left alone rather than guessed at.
        assertNull(LocalStackMysqlPortReconciler.correctedValue("SOME_OTHER_VALUE", "4510", 4513));
    }

    @Test
    void nullValueIsNeverCorrected() {
        assertNull(LocalStackMysqlPortReconciler.correctedValue("DB_PORT", null, 4513));
    }
}
