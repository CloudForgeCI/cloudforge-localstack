package com.cloudforgeci.localstack;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalStackRdsSupportTest {

    @Test
    void normalizeEngineMapsPostgresqlAlias() {
        assertEquals("postgres", LocalStackRdsSupport.normalizeEngine("PostgreSQL"));
        assertEquals("mysql", LocalStackRdsSupport.normalizeEngine("MySQL"));
        assertEquals("mariadb", LocalStackRdsSupport.normalizeEngine("mariadb"));
        assertEquals("", LocalStackRdsSupport.normalizeEngine("  "));
    }

    @Test
    void cloudForgeEnginesMatchRdsFactory() {
        assertTrue(LocalStackRdsSupport.isCloudForgeEngine("postgres"));
        assertTrue(LocalStackRdsSupport.isCloudForgeEngine("postgresql"));
        assertTrue(LocalStackRdsSupport.isCloudForgeEngine("mysql"));
        assertTrue(LocalStackRdsSupport.isCloudForgeEngine("mariadb"));
        assertFalse(LocalStackRdsSupport.isCloudForgeEngine("sqlserver"));
        assertFalse(LocalStackRdsSupport.isCloudForgeEngine("oracle"));
    }

    @Test
    void nativeSnapshotsOnlyForPostgresFamily() {
        assertTrue(LocalStackRdsSupport.supportsNativeSnapshots("postgres"));
        assertTrue(LocalStackRdsSupport.supportsNativeSnapshots("postgresql"));
        assertTrue(LocalStackRdsSupport.supportsNativeSnapshots("aurora-postgresql"));
        assertFalse(LocalStackRdsSupport.supportsNativeSnapshots("mysql"));
        assertFalse(LocalStackRdsSupport.supportsNativeSnapshots("mariadb"));
        assertFalse(LocalStackRdsSupport.supportsNativeSnapshots("aurora-mysql"));
    }

    @Test
    void engineFilterMatchesFamilies() {
        Set<String> postgres = LocalStackRdsSupport.normalizeEngineFilter("postgres");
        assertTrue(LocalStackRdsSupport.matchesEngineFilter("postgres", postgres));
        assertTrue(LocalStackRdsSupport.matchesEngineFilter("aurora-postgresql", postgres));
        assertFalse(LocalStackRdsSupport.matchesEngineFilter("mysql", postgres));

        Set<String> mysql = LocalStackRdsSupport.normalizeEngineFilter("mysql");
        assertTrue(LocalStackRdsSupport.matchesEngineFilter("mysql", mysql));
        assertTrue(LocalStackRdsSupport.matchesEngineFilter("aurora-mysql", mysql));
        assertFalse(LocalStackRdsSupport.matchesEngineFilter("mariadb", mysql));

        Set<String> all = LocalStackRdsSupport.normalizeEngineFilter();
        assertTrue(LocalStackRdsSupport.matchesEngineFilter("mariadb", all));
        assertTrue(LocalStackRdsSupport.matchesEngineFilter("mysql", all));
    }
}
