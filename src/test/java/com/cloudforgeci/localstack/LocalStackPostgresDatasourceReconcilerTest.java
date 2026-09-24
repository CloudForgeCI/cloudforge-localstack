package com.cloudforgeci.localstack;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalStackPostgresDatasourceReconcilerTest {

    @Test
    void identifiesDatasourceParametersWithoutMatchingOtherParameters() {
        String template = """
            {"Resources":{"Datasource":{"Type":"AWS::SSM::Parameter","Properties":{
            "Name":"/Mattermost/mattermost/datasource-url"}},"Other":{"Type":"AWS::SSM::Parameter",
            "Properties":{"Name":"/Mattermost/other"}}}}""";

        assertTrue(LocalStackPostgresDatasourceReconciler.requiresDatasourceParameters(template));
    }

    @Test
    void ignoresTemplatesWithoutDatasourceParameter() {
        String template = """
            {"Resources":{"Other":{"Type":"AWS::SSM::Parameter","Properties":{
            "Name":"/Mattermost/other"}}}}""";

        assertFalse(LocalStackPostgresDatasourceReconciler.requiresDatasourceParameters(template));
    }

    @Test
    void rewritesPostgresDatasourceForTheCompanion() {
        var datasource = LocalStackPostgresDatasourceReconciler.Datasource.parse(
            "postgres://mattermostadmin:secret@rds.local:5432/mattermost?sslmode=require");

        assertEquals("mattermostadmin", datasource.username());
        assertEquals("mattermost", datasource.database());
        assertEquals(
            "postgres://mattermostadmin:secret@cfc-localstack-postgres:5432/mattermost"
                + "?sslmode=disable&connect_timeout=10",
            datasource.localValue());
    }

    @Test
    void rejectsNonPostgresOrIncompleteDatasourceUrls() {
        assertNull(LocalStackPostgresDatasourceReconciler.Datasource.parse(
            "mysql://user:secret@db.local:3306/app"));
        assertNull(LocalStackPostgresDatasourceReconciler.Datasource.parse(
            "postgres://db.local:5432/app"));
    }
}
