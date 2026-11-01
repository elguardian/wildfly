/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.as.test.clustering;

import static org.jboss.as.test.shared.PermissionUtils.createPermissionsXmlAsset;
import static org.junit.jupiter.api.Assertions.*;

import java.security.Permission;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.PropertyPermission;
import java.util.Set;
import java.util.function.Predicate;
import javax.naming.Context;

import org.jboss.as.arquillian.container.ManagementClient;
import org.jboss.as.controller.descriptions.ModelDescriptionConstants;
import org.jboss.as.test.clustering.ejb.EJBDirectory;
import org.jboss.as.test.integration.management.util.CLITestUtil;
import org.jboss.as.test.shared.TimeoutUtil;
import org.jboss.dmr.ModelNode;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.asset.Asset;
import org.jboss.shrinkwrap.api.asset.StringAsset;
import org.jboss.shrinkwrap.api.container.ClassContainer;
import org.jboss.shrinkwrap.api.container.ManifestContainer;

/**
 * Utility class for clustering tests.
 *
 * @author Radoslav Husar
 */
public class ClusterTestUtil {

    public static <A extends Archive<A> & ClassContainer<A> & ManifestContainer<A>> A addTopologyListenerDependencies(A archive) {
        archive.addClasses(TopologyChangeListener.class, TopologyChangeListenerBean.class, TopologyChangeListenerServlet.class);
        archive.setManifest(new StringAsset("Manifest-Version: 1.0\nDependencies: org.infinispan.core\n"));
        archive.addAsManifestResource(createPermissionsXmlAsset(new RuntimePermission("getClassLoader"), new PropertyPermission("jboss.node.name", "read")), "permissions.xml");
        return archive;
    }

    /**
     * Adds the {@link ClientMappingsListener} to an EJB test deployment.
     * Its permissions.xml must include the permissions of {@link #clientMappingsListenerPermissions(Permission...)}.
     */
    public static <A extends Archive<A> & ClassContainer<A> & ManifestContainer<A>> A addClientMappingsListener(A archive) {
        archive.addClasses(ClientMappingsListener.class, ClientMappingsListenerBean.class);
        archive.setManifest(new StringAsset("Manifest-Version: 1.0\nDependencies: org.infinispan.core\n"));
        return archive;
    }

    /**
     * Creates a permissions.xml asset with the given permissions plus those required by {@link ClientMappingsListenerBean}.
     */
    public static Asset clientMappingsListenerPermissions(Permission... permissions) {
        List<Permission> all = new ArrayList<>(List.of(permissions));
        all.add(new RuntimePermission("getClassLoader"));
        all.add(new RuntimePermission("setContextClassLoader"));
        return createPermissionsXmlAsset(all.toArray(Permission[]::new));
    }

    /**
     * Waits until the EJB client mappings registry, i.e. the cluster topology pushed to remote EJB clients, contains exactly the given nodes.
     * Use instead of sleeping after starting/stopping nodes or deploying/undeploying EJB deployments.
     */
    public static void establishClientMappings(EJBDirectory directory, Set<String> nodes) throws Exception {
        ClientMappingsListener listener = directory.lookupStateless(ClientMappingsListenerBean.class, ClientMappingsListener.class);
        listener.establishClientMappings(nodes, TimeoutUtil.adjust(ClientMappingsListener.DEFAULT_TIMEOUT));
    }

    /**
     * Like {@link #establishClientMappings(EJBDirectory, Set)}, for tests that look up beans through a JNDI context.
     * @param moduleName the module of a deployment that contains {@link ClientMappingsListenerBean}
     */
    public static void establishClientMappings(Context context, String moduleName, Set<String> nodes) throws Exception {
        ClientMappingsListener listener = (ClientMappingsListener) context.lookup("ejb:/" + moduleName + "/" + ClientMappingsListenerBean.class.getSimpleName() + "!" + ClientMappingsListener.class.getName());
        listener.establishClientMappings(nodes, TimeoutUtil.adjust(ClientMappingsListener.DEFAULT_TIMEOUT));
    }

    public static void establishTopology(EJBDirectory directory, String container, String cache, Set<String> topology) throws Exception {
        TopologyChangeListener listener = directory.lookupStateless(TopologyChangeListenerBean.class, TopologyChangeListener.class);
        listener.establishTopology(container, cache, topology, TopologyChangeListener.DEFAULT_TIMEOUT);
    }

    // Model management convenience methods

    public static ModelNode execute(ManagementClient client, String request) throws Exception {
        ModelNode operation = CLITestUtil.getCommandContext().buildRequest(request);
        ModelNode result = client.getControllerClient().execute(operation);
        assertEquals(ModelDescriptionConstants.SUCCESS, result.get(ModelDescriptionConstants.OUTCOME).asStringOrNull(), result.toString());
        return result.get(ModelDescriptionConstants.RESULT);
    }

    /** Default timeout of {@link #awaitManagementResult(ManagementClient, String, Predicate)}. */
    public static final Duration MANAGEMENT_POLL_TIMEOUT = Duration.ofSeconds(TimeoutUtil.adjust(15));
    private static final Duration MANAGEMENT_POLL_INTERVAL = Duration.ofMillis(20);

    /**
     * Polls a management request until it succeeds and its result satisfies the condition, e.g. until a singleton is elected.
     * A failed outcome, e.g. a runtime resource that does not exist yet, counts as "not yet".
     * @return the satisfying result
     * @throws AssertionError if the condition is not met within {@link #MANAGEMENT_POLL_TIMEOUT}
     */
    public static ModelNode awaitManagementResult(ManagementClient client, String request, Predicate<ModelNode> condition) throws Exception {
        ModelNode operation = CLITestUtil.getCommandContext().buildRequest(request);
        Instant stop = Instant.now().plus(MANAGEMENT_POLL_TIMEOUT);
        ModelNode last;
        do {
            last = client.getControllerClient().execute(operation);
            if (ModelDescriptionConstants.SUCCESS.equals(last.get(ModelDescriptionConstants.OUTCOME).asStringOrNull())
                    && condition.test(last.get(ModelDescriptionConstants.RESULT))) {
                return last.get(ModelDescriptionConstants.RESULT);
            }
            Thread.sleep(MANAGEMENT_POLL_INTERVAL.toMillis());
        } while (Instant.now().isBefore(stop));
        throw new AssertionError(String.format("%s did not reach the expected result within %s; last response: %s", request, MANAGEMENT_POLL_TIMEOUT, last));
    }

    private ClusterTestUtil() {
    }
}
