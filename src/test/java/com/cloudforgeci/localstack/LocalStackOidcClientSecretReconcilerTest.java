package com.cloudforgeci.localstack;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LocalStackOidcClientSecretReconcilerTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String SECRET_ARN = "arn:aws:secretsmanager:us-east-1:000000000000:secret:cognito-client-AbCdEf";

    /** Parses a JSON literal into a {@code JsonNode} for assertions. */
    private static JsonNode json(String text) throws Exception {
        return MAPPER.readTree(text);
    }

    @Test
    void refToACognitoProvisionedSecretResolvesToItsPhysicalId() throws Exception {
        JsonNode resource = json("{\"Ref\": \"CognitoClientSecret41D1699C\"}");

        assertEquals(SECRET_ARN, LocalStackOidcClientSecretReconciler.secretNameFromResourceArn(
            resource, Map.of("CognitoClientSecret41D1699C", SECRET_ARN)));
    }

    @Test
    void refToAnUnknownLogicalIdResolvesToNothing() throws Exception {
        assertNull(LocalStackOidcClientSecretReconciler.secretNameFromResourceArn(
            json("{\"Ref\": \"Missing\"}"), Map.of()));
    }

    @Test
    void joinedArnStillYieldsTheLiteralSecretNameWithoutTheVersionWildcard() throws Exception {
        JsonNode resource = json("{\"Fn::Join\": [\"\", [\"arn:\", {\"Ref\": \"AWS::Partition\"}, "
            + "\":secretsmanager:us-east-1:000000000000:secret:my/oidc/client-secret-??????\"]]}");

        assertEquals("my/oidc/client-secret", LocalStackOidcClientSecretReconciler.secretNameFromResourceArn(
            resource, Map.of()));
    }

    @Test
    void plainArnStringYieldsTheLiteralSecretName() throws Exception {
        JsonNode resource = json("\"arn:aws:secretsmanager:us-east-1:000000000000:secret:plain-name\"");

        assertEquals("plain-name", LocalStackOidcClientSecretReconciler.secretNameFromResourceArn(
            resource, Map.of()));
    }
}
