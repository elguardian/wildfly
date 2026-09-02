/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.wildfly.test.integration.metrics;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.ADD;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.FAILURE_DESCRIPTION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.FILE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.FILE_HANDLER;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.LOGGER;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.NAME;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.OP_ADDR;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.READ_RESOURCE_OPERATION;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.REMOVE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.SUBSYSTEM;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.VALUE;
import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.WRITE_ATTRIBUTE_OPERATION;
import static org.jboss.as.test.shared.ServerReload.executeReloadAndWaitForCompletion;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.wildfly.test.integration.metrics.MetricsHelper.getPrometheusMetrics;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import org.jboss.arquillian.container.test.api.Deployer;
import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.RunAsClient;
import org.jboss.arquillian.junit.Arquillian;
import org.jboss.arquillian.junit.InSequence;
import org.jboss.arquillian.test.api.ArquillianResource;
import org.jboss.as.arquillian.api.ContainerResource;
import org.jboss.as.arquillian.api.ServerSetup;
import org.jboss.as.arquillian.api.ServerSetupTask;
import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.PathElement;
import org.jboss.as.test.integration.management.ManagementOperations;
import org.jboss.as.test.shared.util.AssumeTestGroupUtil;
import org.jboss.dmr.ModelNode;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Reproducer for JBEAP-32303: ghost Infinispan cache metric entries remain in WildFlyMetricRegistry
 * after a distributable WAR is undeployed. Subsequent calls to /metrics produce WFLYCTL0216 log
 * messages because WildFlyMetric tries to read attributes from the removed management resource.
 *
 * <p>How the ghost entries appear: the metrics subsystem's boot-time global scan
 * (MetricsSubsystemAdd VERIFY step) traverses the live global management model with
 * {@code Function.identity()} as the address resolver. When a distributable WAR is present at
 * server startup, its Infinispan cache service starts concurrently with the VERIFY stage and adds
 * the cache to the global model under
 * {@code /subsystem=infinispan/cache-container=web/cache=<war-name>}. The global scan registers
 * {@code WildFlyMetric} entries at those addresses. When the WAR is later undeployed the Infinispan
 * cache is removed from the management model, but the {@code WildFlyMetric} ghost entries remain
 * in the registry.</p>
 *
 * <p>The test triggers the condition by deploying the WAR, reloading the server (so the WAR
 * participates in the boot scan), and then undeploying it at runtime.</p>
 */
@RunWith(Arquillian.class)
@RunAsClient
@ServerSetup(InfinispanMetricsCleanupAfterUndeployTestCase.SetupTask.class)
public class InfinispanMetricsCleanupAfterUndeployTestCase {

    static final String DEPLOYMENT_NAME = "InfinispanMetricsCleanup";
    static final String WAR_NAME = DEPLOYMENT_NAME + ".war";
    static final String LOG_FILE_NAME = "infinispan-metrics-debug.log";
    static final String METRICS_LOGGER_HANDLER = "infinispan-metrics-test-handler";
    static final String METRICS_LOGGER_CATEGORY = "org.wildfly.extension.metrics";

    static final ModelNode LOGGING_SUBSYSTEM_ADDR = PathAddress.pathAddress(SUBSYSTEM, "logging").toModelNode();
    static final ModelNode FILE_HANDLER_ADDR = PathAddress.pathAddress(SUBSYSTEM, "logging")
            .append(FILE_HANDLER, METRICS_LOGGER_HANDLER).toModelNode();
    static final ModelNode LOGGER_ADDR = PathAddress.pathAddress(SUBSYSTEM, "logging")
            .append(LOGGER, METRICS_LOGGER_CATEGORY).toModelNode();
    static final ModelNode INFINISPAN_WEB_ADDR = PathAddress.pathAddress(SUBSYSTEM, "infinispan")
            .append("cache-container", "web").toModelNode();

    static String serverLogDir;

    static class SetupTask implements ServerSetupTask {

        @Override
        public void setup(ManagementClient managementClient, String containerId) throws Exception {
            // Enable statistics on the infinispan web cache-container so metrics are collected
            ModelNode enableStats = new ModelNode();
            enableStats.get(OP).set(WRITE_ATTRIBUTE_OPERATION);
            enableStats.get(OP_ADDR).set(INFINISPAN_WEB_ADDR);
            enableStats.get(NAME).set("statistics-enabled");
            enableStats.get(VALUE).set(true);
            ManagementOperations.executeOperation(managementClient.getControllerClient(), enableStats);

            // Add a file handler capturing DEBUG messages for the metrics logger category
            ModelNode fileHandler = new ModelNode();
            fileHandler.get(OP).set(ADD);
            fileHandler.get(OP_ADDR).set(FILE_HANDLER_ADDR);
            fileHandler.get("level").set("DEBUG");
            fileHandler.get("append").set(true);
            ModelNode file = new ModelNode();
            file.get("relative-to").set("jboss.server.log.dir");
            file.get("path").set(LOG_FILE_NAME);
            fileHandler.get(FILE).set(file);
            ManagementOperations.executeOperation(managementClient.getControllerClient(), fileHandler);

            // Add a logger for the metrics category at DEBUG level
            ModelNode logger = new ModelNode();
            logger.get(OP).set(ADD);
            logger.get(OP_ADDR).set(LOGGER_ADDR);
            logger.get("level").set("DEBUG");
            logger.get("use-parent-handlers").set(false);
            ModelNode handlers = new ModelNode();
            handlers.add(METRICS_LOGGER_HANDLER);
            logger.get("handlers").set(handlers);
            ManagementOperations.executeOperation(managementClient.getControllerClient(), logger);

            // Resolve the server log directory path so test methods can read the log file
            ModelNode resolveExpr = new ModelNode();
            resolveExpr.get(OP).set("resolve-expression");
            resolveExpr.get("expression").set("${jboss.server.log.dir}");
            serverLogDir = ManagementOperations
                    .executeOperation(managementClient.getControllerClient(), resolveExpr).asString();
        }

        @Override
        public void tearDown(ManagementClient managementClient, String containerId) throws Exception {
            // Remove logger
            ModelNode removeLogger = new ModelNode();
            removeLogger.get(OP).set(REMOVE);
            removeLogger.get(OP_ADDR).set(LOGGER_ADDR);
            ManagementOperations.executeOperation(managementClient.getControllerClient(), removeLogger);

            // Remove file handler
            ModelNode removeHandler = new ModelNode();
            removeHandler.get(OP).set(REMOVE);
            removeHandler.get(OP_ADDR).set(FILE_HANDLER_ADDR);
            ManagementOperations.executeOperation(managementClient.getControllerClient(), removeHandler);

            // Undefine statistics-enabled on the web cache-container
            ModelNode undefineStats = new ModelNode();
            undefineStats.get(OP).set("undefine-attribute");
            undefineStats.get(OP_ADDR).set(INFINISPAN_WEB_ADDR);
            undefineStats.get(NAME).set("statistics-enabled");
            ManagementOperations.executeOperation(managementClient.getControllerClient(), undefineStats);

            // Remove the deployment if it still exists (e.g. test 2 did not run)
            ModelNode checkDeployment = new ModelNode();
            checkDeployment.get(OP).set(READ_RESOURCE_OPERATION);
            checkDeployment.get(OP_ADDR).set(PathAddress.pathAddress("deployment", WAR_NAME).toModelNode());
            ModelNode checkResult = managementClient.getControllerClient().execute(checkDeployment);
            if (!checkResult.hasDefined(FAILURE_DESCRIPTION)) {
                ModelNode removeDeployment = new ModelNode();
                removeDeployment.get(OP).set(REMOVE);
                removeDeployment.get(OP_ADDR).set(PathAddress.pathAddress("deployment", WAR_NAME).toModelNode());
                managementClient.getControllerClient().execute(removeDeployment);
            }

            // Delete the log file
            File logFile = new File(serverLogDir, LOG_FILE_NAME);
            logFile.delete();
        }
    }

    @Deployment(name = DEPLOYMENT_NAME, managed = false, testable = false)
    public static WebArchive deploy() {
        String webXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<web-app xmlns=\"https://jakarta.ee/xml/ns/jakartaee\"\n"
                + "         xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\"\n"
                + "         xsi:schemaLocation=\"https://jakarta.ee/xml/ns/jakartaee https://jakarta.ee/xml/ns/jakartaee/web-app_6_0.xsd\"\n"
                + "         version=\"6.0\">\n"
                + "    <distributable/>\n"
                + "</web-app>\n";
        return ShrinkWrap.create(WebArchive.class, WAR_NAME)
                .addAsWebInfResource(new StringAsset(webXml), "web.xml");
    }

    @BeforeClass
    public static void skipSecurityManager() {
        AssumeTestGroupUtil.assumeSecurityManagerDisabled();
    }

    @BeforeClass
    public static void skipPreview() {
        AssumeTestGroupUtil.assumeNotWildFlyPreview();
    }

    @ContainerResource
    ManagementClient managementClient;

    @ArquillianResource
    Deployer deployer;

    @Test
    @InSequence(1)
    public void testDeployReloadAndVerifyCacheRegistered() throws Exception {
        deployer.deploy(DEPLOYMENT_NAME);

        // Verify the Infinispan deployment cache resource was created in the global model
        ModelNode readResource = new ModelNode();
        readResource.get(OP).set(READ_RESOURCE_OPERATION);
        readResource.get(OP_ADDR).set(infinispanDeploymentCacheAddress());
        ModelNode result = managementClient.getControllerClient().execute(readResource);
        assertFalse("Infinispan cache resource for deployment should exist after deploy: " + result,
                result.hasDefined(FAILURE_DESCRIPTION));

        // Reload so the WAR participates in the next server boot. The metrics subsystem's global
        // scan (MetricsSubsystemAdd VERIFY step) runs during boot and traverses the live global
        // model. The Infinispan deployment cache service starts concurrently, so after reload the
        // scan picks up /subsystem=infinispan/cache-container=web/cache=<war-name> and registers
        // WildFlyMetric ghost candidates at those addresses.
        executeReloadAndWaitForCompletion(managementClient);

        // After reload the cache should still exist (WAR is a persistent deployment)
        result = managementClient.getControllerClient().execute(readResource);
        assertFalse("Infinispan cache resource for deployment should exist after reload: " + result,
                result.hasDefined(FAILURE_DESCRIPTION));

        // /metrics should work cleanly while the deployment is running
        getPrometheusMetrics(managementClient, true);
    }

    @Test
    @InSequence(2)
    public void testUndeployAndVerifyNoGhostMetricReads() throws Exception {
        // Undeploy via management operations. After reload, Arquillian's deployer lost track of
        // the deployment (the server re-deployed it automatically), so use the management API.
        ModelNode remove = new ModelNode();
        remove.get(OP).set(REMOVE);
        remove.get(OP_ADDR).set(PathAddress.pathAddress("deployment", WAR_NAME).toModelNode());
        managementClient.getControllerClient().execute(remove);

        // Verify the Infinispan deployment cache resource is gone
        ModelNode readResource = new ModelNode();
        readResource.get(OP).set(READ_RESOURCE_OPERATION);
        readResource.get(OP_ADDR).set(infinispanDeploymentCacheAddress());
        ModelNode result = managementClient.getControllerClient().execute(readResource);
        assertTrue("Infinispan cache resource for deployment should be gone after undeploy",
                result.hasDefined(FAILURE_DESCRIPTION));

        // Trigger any ghost metric reads — WildFlyMetric entries for the removed infinispan cache
        // will attempt read-attribute on the non-existing resource, triggering WFLYCTL0216 which
        // is logged at DEBUG via MetricsLogger (org.wildfly.extension.metrics category).
        getPrometheusMetrics(managementClient, true);
        getPrometheusMetrics(managementClient, true);

        // Read the debug log captured during this test run and check for ghost reads.
        // If the file doesn't exist, no debug messages were written — no ghost reads occurred.
        Path logPath = Paths.get(serverLogDir, LOG_FILE_NAME);
        List<String> logLines = Files.exists(logPath) ? Files.readAllLines(logPath) : List.of();

        for (String line : logLines) {
            if (line.contains("Unable to read attribute") && line.contains(WAR_NAME)) {
                // Ghost Infinispan metric read detected. This assertion fails until JBEAP-32303
                // is fixed: the global boot-time WildFlyMetric registrations for deployment caches
                // must be cleaned up when the deployment is removed at runtime.
                assertFalse("Found ghost Infinispan cache metric read after undeploy (JBEAP-32303): " + line, true);
            }
        }
    }

    private ModelNode infinispanDeploymentCacheAddress() {
        return PathAddress.pathAddress(
                PathElement.pathElement(SUBSYSTEM, "infinispan"),
                PathElement.pathElement("cache-container", "web"),
                PathElement.pathElement("cache", WAR_NAME)
        ).toModelNode();
    }
}
