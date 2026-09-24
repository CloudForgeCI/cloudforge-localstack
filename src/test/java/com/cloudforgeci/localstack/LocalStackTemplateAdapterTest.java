package com.cloudforgeci.localstack;

import com.cloudforge.core.local.LocalStackCapabilitySnapshot;
import com.cloudforge.core.local.LocalStackServiceCapability;
import com.cloudforge.core.local.LocalStackTierProfile;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalStackTemplateAdapterTest {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Base-tier snapshot so unit tests do not depend on a running LocalStack gateway. */
    private static LocalStackCapabilitySnapshot baseSnapshot() {
        return new LocalStackCapabilitySnapshot(
            true,
            java.net.URI.create("http://localhost:4566"),
            LocalStackTierProfile.BASE,
            "base",
            "test",
            EnumSet.of(
                LocalStackServiceCapability.ECS,
                LocalStackServiceCapability.ELBV2,
                LocalStackServiceCapability.EC2,
                LocalStackServiceCapability.AUTOSCALING,
                LocalStackServiceCapability.RDS,
                LocalStackServiceCapability.COGNITO),
            Map.of());
    }

    private static com.cloudforge.core.local.TemplateAdaptationResult adaptBase(
            ObjectNode canonical,
            String stackName) {
        return LocalStackTemplateAdapter.INSTANCE.adapt(canonical, stackName, baseSnapshot());
    }

    @Test
    void stripsAlbAuthKeepsForwardAndRemovesEfsAndBackup() {
        ObjectNode canonical = MAPPER.createObjectNode();
        ObjectNode resources = canonical.putObject("Resources");

        ObjectNode efs = resources.putObject("Fs");
        efs.put("Type", "AWS::EFS::FileSystem");

        ObjectNode backup = resources.putObject("Vault");
        backup.put("Type", "AWS::Backup::BackupVault");

        ObjectNode scaling = resources.putObject("Scale");
        scaling.put("Type", "AWS::ApplicationAutoScaling::ScalableTarget");

        ObjectNode alb = resources.putObject("Alb");
        alb.put("Type", "AWS::ElasticLoadBalancingV2::LoadBalancer");
        alb.putObject("Properties").put("Name", "cfc-app");

        ObjectNode listener = resources.putObject("Http");
        listener.put("Type", "AWS::ElasticLoadBalancingV2::Listener");
        ObjectNode props = listener.putObject("Properties");
        ArrayNode actions = props.putArray("DefaultActions");
        ObjectNode auth = actions.addObject();
        auth.put("Type", "authenticate-oidc");
        ObjectNode forward = actions.addObject();
        forward.put("Type", "forward");
        forward.putObject("ForwardConfig");

        ObjectNode task = resources.putObject("Task");
        task.put("Type", "AWS::ECS::TaskDefinition");
        ObjectNode taskProps = task.putObject("Properties");
        ArrayNode containers = taskProps.putArray("ContainerDefinitions");
        ObjectNode container = containers.addObject();
        ArrayNode portMappings = container.putArray("PortMappings");
        portMappings.addObject().put("ContainerPort", 8080);
        ArrayNode volumes = taskProps.putArray("Volumes");
        ObjectNode volume = volumes.addObject();
        volume.put("Name", "data");
        volume.putObject("EFSVolumeConfiguration").put("FileSystemId", "fs-1");

        var result = adaptBase(canonical, "Demo");
        ObjectNode adapted = result.template();
        ObjectNode adaptedResources = (ObjectNode) adapted.get("Resources");

        assertFalse(adaptedResources.has("Fs"));
        assertFalse(adaptedResources.has("Vault"));
        assertTrue(adaptedResources.has("Scale"), "Application Auto Scaling should remain");
        ArrayNode adaptedActions = (ArrayNode) adaptedResources
            .path("Http").path("Properties").path("DefaultActions");
        assertEquals(1, adaptedActions.size());
        assertEquals("redirect", adaptedActions.get(0).path("Type").asText());
        assertEquals("localhost", adaptedActions.get(0).path("RedirectConfig").path("Host").asText());
        assertEquals("8080", adaptedActions.get(0).path("RedirectConfig").path("Port").asText());
        assertTrue(adapted.path("Outputs").has(LocalStackTemplateAdapter.OUTPUT_LOCAL_URL));
        assertTrue(adapted.path("Outputs").has(LocalStackTemplateAdapter.OUTPUT_APPLICATION_URL));
        assertFalse(result.adaptations().isEmpty());
    }

    @Test
    void ultimateSnapshotKeepsEfsAndBackupResources() {
        ObjectNode canonical = MAPPER.createObjectNode();
        ObjectNode resources = canonical.putObject("Resources");
        resources.putObject("Fs").put("Type", "AWS::EFS::FileSystem");
        resources.putObject("Vault").put("Type", "AWS::Backup::BackupVault");
        ObjectNode alb = resources.putObject("Alb");
        alb.put("Type", "AWS::ElasticLoadBalancingV2::LoadBalancer");
        alb.putObject("Properties").put("Name", "cfc-app");
        ObjectNode listener = resources.putObject("Http");
        listener.put("Type", "AWS::ElasticLoadBalancingV2::Listener");
        ArrayNode actions = listener.putObject("Properties").putArray("DefaultActions");
        actions.addObject().put("Type", "forward").putObject("ForwardConfig");
        ObjectNode task = resources.putObject("Task");
        task.put("Type", "AWS::ECS::TaskDefinition");
        ObjectNode taskProps = task.putObject("Properties");
        taskProps.putArray("ContainerDefinitions").addObject()
            .putArray("PortMappings").addObject().put("ContainerPort", 8080);
        taskProps.putArray("Volumes").addObject()
            .put("Name", "data")
            .putObject("EFSVolumeConfiguration").put("FileSystemId", "fs-1");

        var snapshot = new LocalStackCapabilitySnapshot(
            true,
            java.net.URI.create("http://localhost:4566"),
            LocalStackTierProfile.ULTIMATE,
            "ultimate",
            "4.0",
            java.util.EnumSet.of(
                LocalStackServiceCapability.EFS,
                LocalStackServiceCapability.BACKUP,
                LocalStackServiceCapability.ECS,
                LocalStackServiceCapability.ELBV2),
            java.util.Map.of());

        var result = LocalStackTemplateAdapter.INSTANCE.adapt(canonical, "Demo", snapshot);
        ObjectNode adaptedResources = (ObjectNode) result.template().get("Resources");
        assertTrue(adaptedResources.has("Fs"));
        assertTrue(adaptedResources.has("Vault"));
        JsonNode volume = adaptedResources.path("Task").path("Properties").path("Volumes").path(0);
        assertTrue(volume.has("Host"), "ECS tasks still need bind mounts when EFS CFN is kept");
        assertFalse(volume.has("EFSVolumeConfiguration"));
    }

    @Test
    void rewritesCdkBootstrapSsmParameterToStringDefault() {
        ObjectNode canonical = MAPPER.createObjectNode();
        ObjectNode parameters = canonical.putObject("Parameters");
        ObjectNode bootstrap = parameters.putObject("BootstrapVersion");
        bootstrap.put("Type", "AWS::SSM::Parameter::Value<String>");
        bootstrap.put("Default", "/cdk-bootstrap/hnb659fds/version");
        canonical.putObject("Resources");

        var result = adaptBase(canonical, "Demo");
        ObjectNode bootstrapAdapted = (ObjectNode) result.template()
            .path("Parameters").path("BootstrapVersion");
        assertEquals("String", bootstrapAdapted.path("Type").asText());
        assertEquals("21", bootstrapAdapted.path("Default").asText());
    }

    @Test
    void rewritesDummyAvailabilityZonesToRegionAzs() {
        ObjectNode canonical = MAPPER.createObjectNode();
        ObjectNode resources = canonical.putObject("Resources");
        ObjectNode subnet = resources.putObject("Subnet");
        subnet.put("Type", "AWS::EC2::Subnet");
        subnet.putObject("Properties").put("AvailabilityZone", "dummy1b");

        ObjectNode adapted = adaptBase(canonical, "Demo").template();
        // Uses AWS_DEFAULT_REGION from env or us-east-1
        String az = adapted.path("Resources").path("Subnet")
            .path("Properties").path("AvailabilityZone").asText();
        assertTrue(az.endsWith("b"), az);
        assertFalse(az.startsWith("dummy"), az);
    }

    @Test
    void injectsJenkinsPrefixForPathStyleElb() {
        ObjectNode canonical = MAPPER.createObjectNode();
        ObjectNode resources = canonical.putObject("Resources");
        ObjectNode alb = resources.putObject("Alb");
        alb.put("Type", "AWS::ElasticLoadBalancingV2::LoadBalancer");
        alb.putObject("Properties").put("Name", "cfc-testalb");
        ObjectNode task = resources.putObject("Task");
        task.put("Type", "AWS::ECS::TaskDefinition");
        ArrayNode containers = task.putObject("Properties").putArray("ContainerDefinitions");
        ObjectNode container = containers.addObject();
        container.put("Image", "jenkins/jenkins:lts");
        container.putArray("Environment").addObject()
            .put("Name", "JENKINS_OPTS")
            .put("Value", "--httpListenAddress=0.0.0.0");

        var result = adaptBase(canonical, "Demo");
        ArrayNode env = (ArrayNode) result.template().path("Resources").path("Task")
            .path("Properties").path("ContainerDefinitions").get(0).path("Environment");
        String jenkinsOpts = null;
        String jenkinsUrl = null;
        for (int i = 0; i < env.size(); i++) {
            if ("JENKINS_OPTS".equals(env.get(i).path("Name").asText())) {
                jenkinsOpts = env.get(i).path("Value").asText();
            }
            if ("JENKINS_URL".equals(env.get(i).path("Name").asText())) {
                jenkinsUrl = env.get(i).path("Value").asText();
            }
        }
        assertTrue(jenkinsOpts != null && jenkinsOpts.contains("--prefix=/_aws/elb/cfc-testalb"), jenkinsOpts);
        assertEquals("http://localhost:4566/_aws/elb/cfc-testalb/", jenkinsUrl);
    }

    /** Guards against a missing WP_HOME/WP_SITEURL: a LocalStack deploy typically has no {@code
     *  fqdn} configured (authMode none), so without this, {@code WORDPRESS_CONFIG_EXTRA} would
     *  leave WordPress with no way to know it's reached through a {@code /_aws/elb/<name>} path
     *  prefix, and its own first-run "not installed" redirect would come back rooted at {@code /}
     *  and 404. */
    @Test
    void injectsWordPressSiteUrlForPathStyleElb() {
        ObjectNode canonical = MAPPER.createObjectNode();
        ObjectNode resources = canonical.putObject("Resources");
        ObjectNode alb = resources.putObject("Alb");
        alb.put("Type", "AWS::ElasticLoadBalancingV2::LoadBalancer");
        alb.putObject("Properties").put("Name", "cfc-wptest");
        ObjectNode task = resources.putObject("Task");
        task.put("Type", "AWS::ECS::TaskDefinition");
        ArrayNode containers = task.putObject("Properties").putArray("ContainerDefinitions");
        ObjectNode container = containers.addObject();
        container.put("Image", "wordpress:php8.2-apache");
        container.putArray("Environment").addObject()
            .put("Name", "WORDPRESS_CONFIG_EXTRA")
            .put("Value", "define('DISABLE_WP_CRON', true); define('DISALLOW_FILE_EDIT', true);");

        var result = adaptBase(canonical, "Demo");
        ArrayNode env = (ArrayNode) result.template().path("Resources").path("Task")
            .path("Properties").path("ContainerDefinitions").get(0).path("Environment");
        String configExtra = null;
        for (int i = 0; i < env.size(); i++) {
            if ("WORDPRESS_CONFIG_EXTRA".equals(env.get(i).path("Name").asText())) {
                configExtra = env.get(i).path("Value").asText();
            }
        }
        assertTrue(configExtra != null
                && configExtra.contains("define('WP_HOME', 'http://localhost:4566/_aws/elb/cfc-wptest')")
                && configExtra.contains("define('WP_SITEURL', 'http://localhost:4566/_aws/elb/cfc-wptest')"),
            configExtra);
        // The REQUEST_URI fix — without it, wp-login.php's post-login redirect_to (built from the
        // raw, prefix-stripped REQUEST_URI, not from WP_HOME/WP_SITEURL) sends the browser to a
        // bare path LocalStack's edge misreads as an S3 bucket/key and 400s with NoSuchBucket.
        assertTrue(configExtra.contains(
            "strpos($_SERVER['REQUEST_URI'], '/_aws/elb/cfc-wptest') !== 0"), configExtra);
        assertTrue(configExtra.contains(
            "$_SERVER['REQUEST_URI'] = '/_aws/elb/cfc-wptest' . $_SERVER['REQUEST_URI'];"), configExtra);
        // The pre-existing directives (never touched, never dropped) survive alongside the new ones.
        assertTrue(configExtra.contains("define('DISABLE_WP_CRON', true)"), configExtra);
        assertTrue(configExtra.contains("define('DISALLOW_FILE_EDIT', true)"), configExtra);
    }

    @Test
    void rewritesAlbHttpsRedirectToLocalStackGatewayPort() {
        ObjectNode canonical = MAPPER.createObjectNode();
        ObjectNode resources = canonical.putObject("Resources");
        ObjectNode alb = resources.putObject("Alb");
        alb.put("Type", "AWS::ElasticLoadBalancingV2::LoadBalancer");
        alb.putObject("Properties").put("Name", "cfc-test");
        ObjectNode listener = resources.putObject("Http");
        listener.put("Type", "AWS::ElasticLoadBalancingV2::Listener");
        listener.putObject("Properties").putArray("DefaultActions").addObject()
            .put("Type", "redirect")
            .putObject("RedirectConfig")
            .put("Protocol", "HTTPS")
            .put("Port", "443")
            .put("StatusCode", "HTTP_301");
        ObjectNode task = resources.putObject("Task");
        task.put("Type", "AWS::ECS::TaskDefinition");
        task.putObject("Properties").putArray("ContainerDefinitions").addObject()
            .putArray("PortMappings").addObject().put("ContainerPort", 8080);

        var result = adaptBase(canonical, "Demo");
        var config = result.template().path("Resources").path("Http")
            .path("Properties").path("DefaultActions").get(0).path("RedirectConfig");
        assertEquals("localhost", config.path("Host").asText());
        assertEquals("8080", config.path("Port").asText());
        assertEquals(
            "http://localhost:8080/",
            result.template().path("Outputs").path("LocalStackApplicationUrl").path("Value").asText());
        assertEquals(
            "https://cfc-test.elb.localhost.localstack.cloud:4566/",
            result.template().path("Outputs").path("LocalStackElbHostnameUrl").path("Value").asText());
    }

    @Test
    void coercesManagerAuthModeFromAlbOidcToNone() {
        ObjectNode canonical = MAPPER.createObjectNode();
        ObjectNode resources = canonical.putObject("Resources");
        ObjectNode task = resources.putObject("Task");
        task.put("Type", "AWS::ECS::TaskDefinition");
        ArrayNode containers = task.putObject("Properties").putArray("ContainerDefinitions");
        containers.addObject()
            .putArray("Environment").addObject()
            .put("Name", "CFC_MANAGER_AUTH_MODE")
            .put("Value", "alb-oidc");

        var result = adaptBase(canonical, "CloudForgeManager-Dev");
        String mode = result.template().path("Resources").path("Task")
            .path("Properties").path("ContainerDefinitions").get(0)
            .path("Environment").get(0).path("Value").asText();
        assertEquals("none", mode);
    }

    @Test
    void wiresManagerLocalStackConnectionEnv() {
        ObjectNode canonical = MAPPER.createObjectNode();
        ObjectNode resources = canonical.putObject("Resources");
        ObjectNode task = resources.putObject("Task");
        task.put("Type", "AWS::ECS::TaskDefinition");
        ArrayNode containers = task.putObject("Properties").putArray("ContainerDefinitions");
        containers.addObject()
            .put("Image", "cloudforgeci/cloudforge-manager:latest")
            .putArray("Environment").addObject()
            .put("Name", "CFC_MANAGER_PORT")
            .put("Value", "1958");

        var result = adaptBase(canonical, "CloudForgeManager-Dev");
        ArrayNode env = (ArrayNode) result.template().path("Resources").path("Task")
            .path("Properties").path("ContainerDefinitions").get(0)
            .path("Environment");
        Map<String, String> vars = new java.util.LinkedHashMap<>();
        for (int i = 0; i < env.size(); i++) {
            vars.put(env.get(i).path("Name").asText(), env.get(i).path("Value").asText());
        }
        String expectedEndpoint = LocalStackTemplateAdapter.resolveManagerLocalStackEndpoint();
        assertEquals("localstack", vars.get("CFC_MANAGER_TARGET"));
        assertEquals("embedded-h2", vars.get("CFC_MANAGER_DB_MODE"));
        assertEquals(expectedEndpoint, vars.get("LOCALSTACK_ENDPOINT"));
        assertEquals(expectedEndpoint, vars.get("AWS_ENDPOINT_URL"));
    }

    @Test
    void grantsManagerContainerDockerSocketAccessButNotOtherContainers() {
        ObjectNode canonical = MAPPER.createObjectNode();
        ObjectNode resources = canonical.putObject("Resources");
        ObjectNode task = resources.putObject("Task");
        task.put("Type", "AWS::ECS::TaskDefinition");
        ArrayNode containers = task.putObject("Properties").putArray("ContainerDefinitions");
        containers.addObject()
            .put("Image", "cloudforgeci/cloudforge-manager:latest")
            .putArray("Environment").addObject()
            .put("Name", "CFC_MANAGER_PORT")
            .put("Value", "1958");
        containers.addObject().put("Image", "wordpress:latest");

        var result = adaptBase(canonical, "CloudForgeManager-Dev");
        JsonNode taskProperties = result.template().path("Resources").path("Task").path("Properties");

        ArrayNode volumes = (ArrayNode) taskProperties.path("Volumes");
        JsonNode socketVolume = null;
        for (JsonNode volume : volumes) {
            if ("cfc-docker-socket".equals(volume.path("Name").asText())) {
                socketVolume = volume;
            }
        }
        assertTrue(socketVolume != null, "expected a cfc-docker-socket task volume");
        assertTrue(socketVolume.path("Host").path("SourcePath").asText().length() > 0);

        JsonNode managerContainer = taskProperties.path("ContainerDefinitions").get(0);
        ArrayNode managerMountPoints = (ArrayNode) managerContainer.path("MountPoints");
        boolean managerHasSocketMount = false;
        for (JsonNode mountPoint : managerMountPoints) {
            if ("cfc-docker-socket".equals(mountPoint.path("SourceVolume").asText())) {
                assertEquals("/var/run/docker.sock", mountPoint.path("ContainerPath").asText());
                managerHasSocketMount = true;
            }
        }
        assertTrue(managerHasSocketMount, "expected Manager's container to mount the Docker socket");

        JsonNode otherContainer = taskProperties.path("ContainerDefinitions").get(1);
        assertFalse(otherContainer.has("MountPoints"),
            "non-Manager containers must not get Docker socket access");
    }

    /**
     * Guards against a bogus volume-root path: without LOCALSTACK_VOLUME_ROOT baked into
     * Manager's own container, {@code defaultVolumeRoot()} falls back to {@code user.dir} —
     * meaningful only on a host-run process, not inside Manager's own already-containerized JVM
     * (where deploy:create/deploy:catalog run, for every app deployed through Manager's
     * UI, not just Manager itself). Without this, every EFS-backed volume for every such app
     * would bind-mount to a bogus path inside that container's own WORKDIR, reset on every
     * redeploy. Baking the resolved value
     * into Manager's container breaks the cycle: whatever resolves the value now (this test's own
     * process, standing in for the very first host-run deploy) becomes what every later
     * self-referential adapt() call reads back out of its own environment instead of re-deriving.
     */
    @Test
    void injectsVolumeRootIntoManagersOwnContainerToBreakTheSelfDeploySeenFromInsideItsOwnContainerBug() {
        ObjectNode canonical = MAPPER.createObjectNode();
        ObjectNode resources = canonical.putObject("Resources");
        ObjectNode task = resources.putObject("Task");
        task.put("Type", "AWS::ECS::TaskDefinition");
        task.putObject("Properties").putArray("ContainerDefinitions").addObject()
            .put("Image", "cloudforgeci/cloudforge-manager:latest")
            .putArray("Environment").addObject()
            .put("Name", "CFC_MANAGER_PORT")
            .put("Value", "1958");

        var result = adaptBase(canonical, "CloudForgeManager-Dev");
        ArrayNode environment = (ArrayNode) result.template().path("Resources").path("Task")
            .path("Properties").path("ContainerDefinitions").get(0).path("Environment");
        String volumeRoot = null;
        for (JsonNode env : environment) {
            if ("LOCALSTACK_VOLUME_ROOT".equals(env.path("Name").asText())) {
                volumeRoot = env.path("Value").asText();
            }
        }
        assertTrue(volumeRoot != null && !volumeRoot.isBlank(),
            "expected LOCALSTACK_VOLUME_ROOT injected into Manager's own container");
        assertTrue(java.nio.file.Path.of(volumeRoot).isAbsolute(), volumeRoot);
        assertEquals(LocalStackTemplateAdapter.defaultVolumeRoot().toString(), volumeRoot);
    }

    @Test
    void managerUsesSingleTaskReplacementForLocalStackH2() {
        ObjectNode canonical = MAPPER.createObjectNode();
        ObjectNode resources = canonical.putObject("Resources");
        ObjectNode task = resources.putObject("ManagerTask");
        task.put("Type", "AWS::ECS::TaskDefinition");
        task.putObject("Properties").putArray("ContainerDefinitions").addObject()
            .putArray("Environment").addObject()
            .put("Name", "CFC_MANAGER_PORT")
            .put("Value", "1958");
        ObjectNode service = resources.putObject("ManagerService");
        service.put("Type", "AWS::ECS::Service");
        service.putObject("Properties").putObject("TaskDefinition").put("Ref", "ManagerTask");

        var result = adaptBase(canonical, "CloudForgeManager-Dev");
        JsonNode deployment = result.template().path("Resources").path("ManagerService")
            .path("Properties").path("DeploymentConfiguration");
        assertEquals(100, deployment.path("MaximumPercent").asInt());
        assertEquals(0, deployment.path("MinimumHealthyPercent").asInt());
    }

    @Test
    void managerLocalStackEndpointIgnoresDeployShellAwsEndpoint() {
        // Adapt-time endpoint must not inherit AWS_ENDPOINT_URL from the deploy machine.
        assertEquals(
            "http://host.docker.internal:4566",
            LocalStackTemplateAdapter.resolveManagerLocalStackEndpoint());
    }

    @Test
    void stripsCustomLogRetentionAndCleansDependsOn() {
        ObjectNode canonical = MAPPER.createObjectNode();
        ObjectNode resources = canonical.putObject("Resources");

        ObjectNode logGroup = resources.putObject("AppLogGroup");
        logGroup.put("Type", "AWS::Logs::LogGroup");
        logGroup.putObject("Properties").put("LogGroupName", "/cfc/app");
        logGroup.put("DependsOn", "AppLogRetention");

        ObjectNode retention = resources.putObject("AppLogRetention");
        retention.put("Type", "Custom::LogRetention");
        retention.putObject("Properties").put("RetentionInDays", 7);

        ObjectNode keep = resources.putObject("KeepMe");
        keep.put("Type", "AWS::SNS::Topic");
        keep.putObject("Properties").put("TopicName", "keep");

        var result = adaptBase(canonical, "Rds-PhaseB");
        ObjectNode adapted = (ObjectNode) result.template().path("Resources");
        assertFalse(adapted.has("AppLogRetention"));
        assertTrue(adapted.has("AppLogGroup"));
        assertFalse(adapted.path("AppLogGroup").has("DependsOn"));
        assertTrue(adapted.has("KeepMe"));
        assertTrue(result.adaptations().stream()
            .anyMatch(a -> a.path().contains("AppLogRetention")
                && a.reason().contains("Custom::LogRetention")));
    }

    @Test
    void stripsS3AutoDeleteObjectsAndCleansDependsOn() {
        ObjectNode canonical = MAPPER.createObjectNode();
        ObjectNode resources = canonical.putObject("Resources");

        ObjectNode bucket = resources.putObject("CloudTrailBucket");
        bucket.put("Type", "AWS::S3::Bucket");
        bucket.putObject("Properties");
        bucket.put("DependsOn", "CloudTrailBucketAutoDeleteObjects");

        ObjectNode autoDelete = resources.putObject("CloudTrailBucketAutoDeleteObjects");
        autoDelete.put("Type", "Custom::S3AutoDeleteObjects");
        autoDelete.putObject("Properties").put("BucketName", "cloudtrail-bucket");

        ObjectNode keep = resources.putObject("KeepMe");
        keep.put("Type", "AWS::SNS::Topic");
        keep.putObject("Properties").put("TopicName", "keep");

        var result = adaptBase(canonical, "Compliance-Staging");
        ObjectNode adapted = (ObjectNode) result.template().path("Resources");
        assertFalse(adapted.has("CloudTrailBucketAutoDeleteObjects"));
        assertTrue(adapted.has("CloudTrailBucket"));
        assertFalse(adapted.path("CloudTrailBucket").has("DependsOn"));
        assertTrue(adapted.has("KeepMe"));
        assertTrue(result.adaptations().stream()
            .anyMatch(a -> a.path().contains("CloudTrailBucketAutoDeleteObjects")
                && a.reason().contains("Custom::S3AutoDeleteObjects")));
    }

    @Test
    void stripsCustomAwsResourcesAndCleansDependsOn() {
        ObjectNode canonical = MAPPER.createObjectNode();
        ObjectNode resources = canonical.putObject("Resources");

        ObjectNode dependent = resources.putObject("ComplianceBucket");
        dependent.put("Type", "AWS::S3::Bucket");
        dependent.put("DependsOn", "ComplianceBucketSSMWriter");

        ObjectNode writer = resources.putObject("ComplianceBucketSSMWriter");
        writer.put("Type", "Custom::AWS");
        writer.putObject("Properties").put("Create", "putParameter");

        ObjectNode keep = resources.putObject("KeepCustomResource");
        keep.put("Type", "Custom::AWS");
        keep.putObject("Properties").put("Create", "otherApiCall");

        var result = adaptBase(canonical, "GitLab");
        ObjectNode adapted = (ObjectNode) result.template().path("Resources");
        assertFalse(adapted.has("ComplianceBucketSSMWriter"));
        assertFalse(adapted.has("KeepCustomResource"));
        assertFalse(adapted.path("ComplianceBucket").has("DependsOn"));
        assertTrue(result.adaptations().stream()
            .anyMatch(a -> a.path().contains("ComplianceBucketSSMWriter")
                && a.reason().contains("Custom::AWS side effects")));
    }

    @Test
    void removesRoute53QueryLoggingSidecarForRepeatableLocalDeploys() {
        ObjectNode canonical = MAPPER.createObjectNode();
        ObjectNode resources = canonical.putObject("Resources");
        ObjectNode zone = resources.putObject("Zone");
        zone.put("Type", "AWS::Route53::HostedZone");
        zone.putObject("Properties").putObject("QueryLoggingConfig")
            .put("CloudWatchLogsLogGroupArn", "arn:aws:logs:local");
        resources.putObject("Route53QueryLogs").put("Type", "AWS::Logs::LogGroup");
        resources.putObject("Route53QueryLogsPolicy").put("Type", "AWS::Logs::ResourcePolicy");
        resources.putObject("Route53QueryLogsKmsKey").put("Type", "AWS::KMS::Key");

        ObjectNode adapted = (ObjectNode) adaptBase(canonical, "Jtest").template().path("Resources");
        assertFalse(adapted.path("Zone").path("Properties").has("QueryLoggingConfig"));
        assertFalse(adapted.has("Route53QueryLogs"));
        assertFalse(adapted.has("Route53QueryLogsPolicy"));
        assertFalse(adapted.has("Route53QueryLogsKmsKey"));
    }

    @Test
    void dropsAsgTargetGroupAttachmentsBecauseTheEmulatorCannotResolveThem() {
        ObjectNode canonical = MAPPER.createObjectNode();
        ObjectNode resources = canonical.putObject("Resources");
        ObjectNode asg = resources.putObject("Asg");
        asg.put("Type", "AWS::AutoScaling::AutoScalingGroup");
        ObjectNode properties = asg.putObject("Properties");
        properties.put("HealthCheckType", "ELB");
        properties.putArray("TargetGroupARNs").addObject().put("Ref", "Tg");
        resources.putObject("Tg").put("Type", "AWS::ElasticLoadBalancingV2::TargetGroup");

        var result = adaptBase(canonical, "Jtest");
        ObjectNode adaptedAsg = (ObjectNode) result.template().path("Resources").path("Asg").path("Properties");

        assertFalse(adaptedAsg.has("TargetGroupARNs"));
        assertTrue(adaptedAsg.path("HealthCheckType").asText().equals("EC2"));
        assertTrue(result.adaptations().stream()
            .anyMatch(a -> a.path().contains("Asg.Properties.TargetGroupARNs")));
    }

    @Test
    void leavesAsgsWithoutTargetGroupsUntouched() {
        ObjectNode canonical = MAPPER.createObjectNode();
        ObjectNode asg = canonical.putObject("Resources").putObject("Asg");
        asg.put("Type", "AWS::AutoScaling::AutoScalingGroup");
        asg.putObject("Properties").put("HealthCheckType", "ELB");

        var result = adaptBase(canonical, "Jtest");

        assertTrue(result.template().path("Resources").path("Asg").path("Properties")
            .path("HealthCheckType").asText().equals("ELB"));
    }

    @Test
    void removesRoute53AliasesBecauseTheEmulatorEdgeOwnsLocalHostRouting() {
        ObjectNode canonical = MAPPER.createObjectNode();
        ObjectNode resources = canonical.putObject("Resources");
        resources.putObject("ApplicationAlias")
            .put("Type", "AWS::Route53::RecordSet")
            .putObject("Properties")
            .put("Name", "jenkins.cloudforge.localhost.");
        resources.putObject("DependentResource")
            .put("Type", "AWS::S3::Bucket")
            .put("DependsOn", "ApplicationAlias");

        var result = adaptBase(canonical, "Jtest");
        ObjectNode adapted = (ObjectNode) result.template().path("Resources");
        assertFalse(adapted.has("ApplicationAlias"));
        assertFalse(adapted.path("DependentResource").has("DependsOn"));
        assertTrue(result.adaptations().stream()
            .anyMatch(a -> a.path().contains("ApplicationAlias")
                && a.reason().contains("emulator edge owns")));
    }

    @Test
    void injectsEdgeHostnameFromRoute53RecordSetSubdomainForJenkins() {
        ObjectNode canonical = MAPPER.createObjectNode();
        ObjectNode resources = canonical.putObject("Resources");
        ObjectNode alb = resources.putObject("Alb");
        alb.put("Type", "AWS::ElasticLoadBalancingV2::LoadBalancer");
        alb.putObject("Properties").put("Name", "cfc-jtest");
        ObjectNode task = resources.putObject("Task");
        task.put("Type", "AWS::ECS::TaskDefinition");
        task.putObject("Properties").putArray("ContainerDefinitions").addObject()
            .put("Image", "jenkins/jenkins:lts");
        // What ApplicationServiceTopologyConfiguration's ARecord/AaaaRecord construct produces
        // for deploymentContext.subdomain=jenkins1 against a hosted zone — the same resource
        // removeRoute53RecordSets strips for LocalStack, now captured first instead of discarded.
        resources.putObject("ServiceAlbAliasA")
            .put("Type", "AWS::Route53::RecordSet")
            .putObject("Properties")
            .put("Name", "jenkins1.example.com.");
        resources.putObject("ServiceAlbAliasAAAA")
            .put("Type", "AWS::Route53::RecordSet")
            .putObject("Properties")
            .put("Name", "jenkins1.example.com.");

        var result = adaptBase(canonical, "Jtest");
        ArrayNode env = (ArrayNode) result.template().path("Resources").path("Task")
            .path("Properties").path("ContainerDefinitions").get(0).path("Environment");
        String edgeHostname = null;
        for (int i = 0; i < env.size(); i++) {
            if ("CFC_LOCALSTACK_EDGE_HOSTNAME".equals(env.get(i).path("Name").asText())) {
                edgeHostname = env.get(i).path("Value").asText();
            }
        }
        assertEquals("jenkins1.cloudforge.localhost", edgeHostname);
        // Both RecordSets are still gone — capturing the name doesn't change the strip behavior.
        assertFalse(result.template().path("Resources").has("ServiceAlbAliasA"));
        assertFalse(result.template().path("Resources").has("ServiceAlbAliasAAAA"));
    }

    @Test
    void omitsEdgeHostnameWhenNoRoute53RecordSetPresent() {
        ObjectNode canonical = MAPPER.createObjectNode();
        ObjectNode resources = canonical.putObject("Resources");
        ObjectNode alb = resources.putObject("Alb");
        alb.put("Type", "AWS::ElasticLoadBalancingV2::LoadBalancer");
        alb.putObject("Properties").put("Name", "cfc-jtest2");
        ObjectNode task = resources.putObject("Task");
        task.put("Type", "AWS::ECS::TaskDefinition");
        task.putObject("Properties").putArray("ContainerDefinitions").addObject()
            .put("Image", "jenkins/jenkins:lts");

        var result = adaptBase(canonical, "Jtest2");
        ArrayNode env = (ArrayNode) result.template().path("Resources").path("Task")
            .path("Properties").path("ContainerDefinitions").get(0).path("Environment");
        for (int i = 0; i < env.size(); i++) {
            assertFalse("CFC_LOCALSTACK_EDGE_HOSTNAME".equals(env.get(i).path("Name").asText()),
                "no subdomain configured — edge falls back to the static jenkins.cloudforge.localhost host");
        }
    }

    @Test
    void removesUnsupportedAwsCustomResourceWritersWithTheirProvider() {
        ObjectNode canonical = MAPPER.createObjectNode();
        ObjectNode resources = canonical.putObject("Resources");
        resources.putObject("OidcClient")
            .put("Type", "AWS::Cognito::UserPoolClient")
            .putObject("Properties");
        ObjectNode task = resources.putObject("Task");
        task.put("Type", "AWS::ECS::TaskDefinition");
        task.putObject("Properties").putArray("ContainerDefinitions").addObject()
            .putArray("Environment").addObject()
            .put("Name", "CASC_JENKINS_CONFIG")
            .put("Value", "/var/jenkins_home/casc_configs");

        ObjectNode provider = resources.putObject("AWS679f53fac002430cb0da5b7982bd22872D164C4C");
        provider.put("Type", "AWS::Lambda::Function");
        provider.putObject("Properties");

        ObjectNode cognitoSync = resources.putObject("CognitoClientSecretFetcher");
        cognitoSync.put("Type", "Custom::AWS");
        cognitoSync.putObject("Properties").putObject("ServiceToken")
            .put("Fn::GetAtt", "AWS679f53fac002430cb0da5b7982bd22872D164C4C");

        ObjectNode retainedWriter = resources.putObject("CloudTrailBucketSSMWriter");
        retainedWriter.put("Type", "Custom::AWS");
        retainedWriter.putObject("Properties").putObject("ServiceToken")
            .put("Fn::GetAtt", "AWS679f53fac002430cb0da5b7982bd22872D164C4C");

        ObjectNode adapted = (ObjectNode) adaptBase(canonical, "Jtest").template().path("Resources");
        assertFalse(adapted.has("CognitoClientSecretFetcher"));
        assertFalse(adapted.has("CloudTrailBucketSSMWriter"));
        assertFalse(adapted.has("AWS679f53fac002430cb0da5b7982bd22872D164C4C"));
    }

    @Test
    void rewritesApplicationOidcJenkinsForBrowserAndContainerGateways() {
        ObjectNode canonical = MAPPER.createObjectNode();
        ObjectNode resources = canonical.putObject("Resources");

        ObjectNode client = resources.putObject("OidcClient");
        client.put("Type", "AWS::Cognito::UserPoolClient");
        client.putObject("Properties").putArray("CallbackURLs")
            .add("https://jenkins.cloudforge.localhost/securityRealm/finishLogin");

        ObjectNode task = resources.putObject("Task");
        task.put("Type", "AWS::ECS::TaskDefinition");
        ArrayNode containers = task.putObject("Properties").putArray("ContainerDefinitions");
        ObjectNode container = containers.addObject();
        ArrayNode portMappings = container.putArray("PortMappings");
        portMappings.addObject().put("ContainerPort", 8080);
        ArrayNode environment = container.putArray("Environment");
        environment.addObject().put("Name", "CASC_JENKINS_CONFIG").put("Value", "/var/jenkins_home/casc_configs");
        container.putArray("Secrets").addObject()
            .put("Name", "JENKINS_OIDC_CLIENT_SECRET")
            .put("ValueFrom", "arn:aws:secretsmanager:us-east-1:000000000000:secret:jenkins");
        ArrayNode command = container.putArray("Command");
        command.add("bash");
        command.add("-c");
        ObjectNode join = MAPPER.createObjectNode();
        ArrayNode joinParts = join.putArray("Fn::Join").add("").addArray();
        joinParts.add("authorizationServerUrl: \"https://pool.auth.us-east-1.amazoncognito.com/oauth2/authorize\"\n");
        joinParts.add("tokenServerUrl: \"https://pool.auth.us-east-1.amazoncognito.com/oauth2/token\"\n");
        joinParts.add("issuer: \"https://cognito-idp.us-east-1.amazonaws.com/us-east-1_ABC\"\n");
        joinParts.add("jenkinsUrl: \"https://jenkins.cloudforge.localhost\"\n");
        joinParts.add("postLogoutRedirectUrl: \"https://cfc-test.elb.localhost.localstack.cloud\"\n");
        command.add(join);
        command.add("authorizationServerUrl: \"https://pool.auth.us-east-1.amazoncognito.com/oauth2/authorize\"");

        var result = adaptBase(canonical, "Jenkins-Stack");
        String containerGateway = LocalStackTemplateAdapter.resolveContainerLocalStackEndpoint();
        ArrayNode parts = (ArrayNode) result.template().path("Resources").path("Task")
            .path("Properties").path("ContainerDefinitions").get(0)
            .path("Command").get(2).path("Fn::Join").get(1);
        StringBuilder joinedBuilder = new StringBuilder();
        for (JsonNode part : parts) {
            joinedBuilder.append(part.asText());
        }
        String joined = joinedBuilder.toString();

        assertTrue(joined.contains(containerGateway + "/_aws/cognito-idp/oauth2/token"), () -> joined);
        assertTrue(joined.contains("/_aws/cognito-idp/oauth2/authorize"), () -> joined);
        assertTrue(joined.contains("http://localhost.localstack.cloud:4566/us-east-1_ABC"), () -> joined);
        assertTrue(joined.contains("jenkinsUrl: \"http://jenkins.cloudforge.localhost\""), () -> joined);
        assertTrue(joined.contains("postLogoutRedirectUrl: \"http://jenkins.cloudforge.localhost\""), () -> joined);
        assertEquals(
            "authorizationServerUrl: \"http://localstack.cloudforge.localhost/_aws/cognito-idp/oauth2/authorize\"",
            result.template().path("Resources").path("Task").path("Properties")
                .path("ContainerDefinitions").get(0).path("Command").get(3).asText());

        ArrayNode callbacks = (ArrayNode) result.template().path("Resources").path("OidcClient")
            .path("Properties").path("CallbackURLs");
        assertEquals("http://jenkins.cloudforge.localhost/securityRealm/finishLogin", callbacks.get(0).asText());

        ArrayNode adaptedEnv = (ArrayNode) result.template().path("Resources").path("Task")
            .path("Properties").path("ContainerDefinitions").get(0).path("Environment");
        Map<String, String> vars = new java.util.LinkedHashMap<>();
        for (int i = 0; i < adaptedEnv.size(); i++) {
            vars.put(adaptedEnv.get(i).path("Name").asText(), adaptedEnv.get(i).path("Value").asText());
        }
        assertEquals(containerGateway, vars.get("AWS_ENDPOINT_URL"));
        assertEquals("pending-localstack-sync", vars.get("JENKINS_OIDC_CLIENT_SECRET"));
        assertFalse(result.template().path("Resources").path("Task")
            .path("Properties").path("ContainerDefinitions").get(0).has("Secrets"));
    }

    /** Guards against falling back to the container's own internal port: Manager's own Cognito
     *  CallbackURLs are built from the ALB's real DNS name (an Fn::Join token, not a literal
     *  placeholder host like Jenkins/GitLab's "jenkins.local.test"), so {@code
     *  findNamedLocalCallbackHost} never matches it, and without this the base URL would fall
     *  back to {@code http://localhost:<containerPort>} — unreachable from the browser and not
     *  stable across a LocalStack container respawn either. Manager's redirect URI/CallbackURLs
     *  should use the emulator edge's stable vhost instead, same as every other application-oidc
     *  app already gets via its named placeholder. */
    @Test
    void rewritesApplicationOidcManagerCallbacksToTheEmulatorEdgeVhost() {
        ObjectNode canonical = MAPPER.createObjectNode();
        ObjectNode resources = canonical.putObject("Resources");

        ObjectNode client = resources.putObject("OidcClient");
        client.put("Type", "AWS::Cognito::UserPoolClient");
        ObjectNode join = MAPPER.createObjectNode();
        join.putArray("Fn::Join").add("").addArray()
            .add("https://").add(MAPPER.createObjectNode().put("Fn::GetAtt", "Alb.DNSName"))
            .add("/api/v1/auth/oidc/callback");
        client.putObject("Properties").putArray("CallbackURLs").add(join);

        ObjectNode task = resources.putObject("Task");
        task.put("Type", "AWS::ECS::TaskDefinition");
        ArrayNode containers = task.putObject("Properties").putArray("ContainerDefinitions");
        ObjectNode container = containers.addObject();
        container.putArray("PortMappings").addObject().put("ContainerPort", 1958);
        container.putArray("Environment").addObject()
            .put("Name", com.cloudforge.core.manager.ManagerEnvKeys.OIDC_REDIRECT_URL)
            .put("Value", "https://cfc-test.elb.localhost.localstack.cloud/api/v1/auth/oidc/callback");
        container.putArray("Secrets").addObject()
            .put("Name", com.cloudforge.core.manager.ManagerEnvKeys.OIDC_CLIENT_SECRET)
            .put("ValueFrom", "arn:aws:secretsmanager:us-east-1:000000000000:secret:manager-oidc");

        var result = adaptBase(canonical, "CloudForgeManager-Dev");

        ArrayNode adaptedEnv = (ArrayNode) result.template().path("Resources").path("Task")
            .path("Properties").path("ContainerDefinitions").get(0).path("Environment");
        String redirectUrl = null;
        for (JsonNode env : adaptedEnv) {
            if (com.cloudforge.core.manager.ManagerEnvKeys.OIDC_REDIRECT_URL.equals(env.path("Name").asText())) {
                redirectUrl = env.path("Value").asText();
            }
        }
        assertEquals("http://manager.cloudforge.localhost/api/v1/auth/oidc/callback", redirectUrl);

        ArrayNode callbacks = (ArrayNode) result.template().path("Resources").path("OidcClient")
            .path("Properties").path("CallbackURLs");
        assertEquals("http://manager.cloudforge.localhost/api/v1/auth/oidc/callback", callbacks.get(0).asText());
    }

    /** Guards against WORDPRESS_DB_HOST staying unrewritten: WordPressApplicationSpec
     *  .databaseEnvVars builds it as {@code host + ":" + port} — CDK renders that Java string
     *  concatenation on tokens as an Fn::Join wrapping a nested Fn::GetAtt, not a bare Fn::GetAtt
     *  like the plain DB_HOST env var right next to it. Without handling the Fn::Join case,
     *  WORDPRESS_DB_HOST would still point at LocalStack's RDS-emulation hostname (unreachable
     *  from inside the ECS task's own Docker network) even with DB_HOST correctly rewritten —
     *  WordPress would run but report "Database Error". */
    @Test
    void rewritesGetAttEmbeddedInAJoinedHostPortEnvVarForLocalStackMysql() {
        ObjectNode canonical = MAPPER.createObjectNode();
        ObjectNode resources = canonical.putObject("Resources");

        ObjectNode db = resources.putObject("Db");
        db.put("Type", "AWS::RDS::DBInstance");
        db.putObject("Properties").put("Engine", "mysql");

        ObjectNode task = resources.putObject("Task");
        task.put("Type", "AWS::ECS::TaskDefinition");
        ArrayNode environment = task.putObject("Properties").putArray("ContainerDefinitions").addObject()
            .putArray("Environment");

        environment.addObject().put("Name", "DB_HOST")
            .putObject("Value").putArray("Fn::GetAtt").add("Db").add("Endpoint.Address");

        ObjectNode joinedValue = environment.addObject().put("Name", "WORDPRESS_DB_HOST").putObject("Value");
        ArrayNode joinParts = joinedValue.putArray("Fn::Join").add("").addArray();
        joinParts.addObject().putArray("Fn::GetAtt").add("Db").add("Endpoint.Address");
        joinParts.add(":3306");

        ArrayNode adaptedEnv = (ArrayNode) adaptBase(canonical, "WpTest").template().path("Resources")
            .path("Task").path("Properties").path("ContainerDefinitions").get(0).path("Environment");

        assertEquals("cfc-localstack", adaptedEnv.get(0).path("Value").asText());
        JsonNode rewrittenJoinParts = adaptedEnv.get(1).path("Value").path("Fn::Join").get(1);
        assertEquals("cfc-localstack", rewrittenJoinParts.get(0).asText());
        // Not ":3306" — the literal port suffix baked in alongside the host (never a token, so
        // the GetAtt-rewrite alone never touches it) also needs correcting to the emulator's
        // actual MySQL listener port, or the host fix alone still connects to the wrong port.
        assertEquals(":4510", rewrittenJoinParts.get(1).asText());
    }

    /** Guards against a bare literal port staying unrewritten: {@code
     *  DatabaseSpec.DatabaseConnection.port()} is a plain {@code int}, never a CDK token, so
     *  {@code PhpBBApplicationSpec}'s standalone {@code PHPBB_DB_PORT} env var (unlike a combined
     *  "host:port" string) is a bare literal ("3306") from the moment the template is
     *  synthesized, with no Fn::GetAtt shape for the adapter to key off at all. Left unrewritten,
     *  phpBB's installer autofill (and any other direct use of the env var) would point at the
     *  right host but the wrong port. */
    @Test
    void rewritesBareLiteralStandardPortEnvVarForLocalStackMysql() {
        ObjectNode canonical = MAPPER.createObjectNode();
        ObjectNode resources = canonical.putObject("Resources");

        ObjectNode db = resources.putObject("Db");
        db.put("Type", "AWS::RDS::DBInstance");
        db.putObject("Properties").put("Engine", "mysql");

        ObjectNode task = resources.putObject("Task");
        task.put("Type", "AWS::ECS::TaskDefinition");
        ArrayNode environment = task.putObject("Properties").putArray("ContainerDefinitions").addObject()
            .putArray("Environment");

        environment.addObject().put("Name", "PHPBB_DB_HOST")
            .putObject("Value").putArray("Fn::GetAtt").add("Db").add("Endpoint.Address");
        environment.addObject().put("Name", "PHPBB_DB_PORT").put("Value", "3306");
        // A same-shaped literal on an unrelated var name must NOT be touched — the rewrite is
        // scoped to *_DB_PORT/DB_PORT names specifically, not any "3306" string in the template.
        environment.addObject().put("Name", "SOME_OTHER_SETTING").put("Value", "3306");

        ArrayNode adaptedEnv = (ArrayNode) adaptBase(canonical, "PhpBBTest").template().path("Resources")
            .path("Task").path("Properties").path("ContainerDefinitions").get(0).path("Environment");

        assertEquals("cfc-localstack", adaptedEnv.get(0).path("Value").asText());
        assertEquals("4510", adaptedEnv.get(1).path("Value").asText());
        assertEquals("3306", adaptedEnv.get(2).path("Value").asText());
    }

    @Test
    void deriveEdgeHostnameKeepsOnlyTheFirstLabel() {
        assertEquals("jenkins1.cloudforge.localhost",
            LocalStackTemplateAdapter.deriveEdgeHostname("jenkins1.example.com."));
        // No trailing dot — still handled the same way.
        assertEquals("jenkins2.cloudforge.localhost",
            LocalStackTemplateAdapter.deriveEdgeHostname("jenkins2.example.com"));
        // Already under cloudforge.localhost (e.g. local dev used that as the configured domain
        // directly) — first-label extraction is idempotent, not double-suffixed.
        assertEquals("jenkins.cloudforge.localhost",
            LocalStackTemplateAdapter.deriveEdgeHostname("jenkins.cloudforge.localhost."));
    }

    @Test
    void deriveEdgeHostnameIsNullWhenNothingToDerive() {
        assertEquals(null, LocalStackTemplateAdapter.deriveEdgeHostname(null));
        assertEquals(null, LocalStackTemplateAdapter.deriveEdgeHostname(""));
        assertEquals(null, LocalStackTemplateAdapter.deriveEdgeHostname("."));
    }
}
