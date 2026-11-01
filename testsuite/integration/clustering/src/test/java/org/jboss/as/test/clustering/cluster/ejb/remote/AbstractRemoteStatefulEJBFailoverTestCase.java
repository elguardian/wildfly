/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.as.test.clustering.cluster.ejb.remote;

import static org.junit.jupiter.api.Assertions.*;

import java.util.PropertyPermission;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.as.test.clustering.ClusterTestUtil;
import org.jboss.as.test.clustering.cluster.AbstractClusteringTestCase;
import org.jboss.as.test.clustering.cluster.ejb.remote.bean.Incrementor;
import org.jboss.as.test.clustering.cluster.ejb.remote.bean.IncrementorBean;
import org.jboss.as.test.clustering.cluster.ejb.remote.bean.Result;
import org.jboss.as.test.clustering.cluster.ejb.remote.bean.StatefulIncrementorBean;
import org.jboss.as.test.clustering.ejb.EJBDirectory;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.JavaArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.wildfly.common.function.ExceptionSupplier;

/**
 * Validates failover behavior of a remotely accessed @Stateful EJB.
 * @author Paul Ferraro
 */
@ExtendWith(ArquillianExtension.class)
public abstract class AbstractRemoteStatefulEJBFailoverTestCase extends AbstractClusteringTestCase {
    private static final int COUNT = 20;

    static Archive<?> createDeployment(String moduleName) {
        return ClusterTestUtil.addClientMappingsListener(ShrinkWrap.create(JavaArchive.class, moduleName + ".jar"))
                .addPackage(EJBDirectory.class.getPackage())
                .addClasses(Result.class, Incrementor.class, IncrementorBean.class, StatefulIncrementorBean.class)
                .addAsManifestResource(ClusterTestUtil.clientMappingsListenerPermissions(new PropertyPermission(NODE_NAME_PROPERTY, "read")), "permissions.xml")
                ;
    }

    private final ExceptionSupplier<EJBDirectory, Exception> directoryProvider;

    protected AbstractRemoteStatefulEJBFailoverTestCase(ExceptionSupplier<EJBDirectory, Exception> directoryProvider) {
        this.directoryProvider = directoryProvider;
    }

    @Test
    public void test() throws Exception {
        try (EJBDirectory directory = this.directoryProvider.get()) {
            Incrementor bean = directory.lookupStateful(StatefulIncrementorBean.class, Incrementor.class);

            Result<Integer> result = bean.increment();
            String target = result.getNode();
            int count = 1;

            assertEquals(count++, result.getValue().intValue());

            // Bean should retain weak affinity for this node
            for (int i = 0; i < COUNT; ++i) {
                result = bean.increment();
                assertEquals(count++, result.getValue().intValue());
                assertEquals(target, result.getNode(), String.valueOf(i));
            }

            undeploy(this.findDeployment(target));

            // Wait until the cluster topology pushed to EJB clients no longer contains the undeployed node
            ClusterTestUtil.establishClientMappings(directory, others(target));

            result = bean.increment();
            // Bean should failover to other node
            String failoverTarget = result.getNode();

            assertEquals(count++, result.getValue().intValue());
            assertNotEquals(target, failoverTarget);

            deploy(this.findDeployment(target));

            // Wait until the cluster topology pushed to EJB clients contains both nodes again
            ClusterTestUtil.establishClientMappings(directory, NODE_1_2);

            result = bean.increment();
            String failbackTarget = result.getNode();
            assertEquals(count++, result.getValue().intValue());
            // Bean should retain weak affinity for this node
            assertEquals(failoverTarget, failbackTarget);

            result = bean.increment();
            // Bean may have acquired new weak affinity
            target = result.getNode();
            assertEquals(count++, result.getValue().intValue());

            // Bean should retain weak affinity for this node
            for (int i = 0; i < COUNT; ++i) {
                result = bean.increment();
                assertEquals(count++, result.getValue().intValue());
                assertEquals(target, result.getNode(), String.valueOf(i));
            }

            stop(target);

            result = bean.increment();
            // Bean should failover to other node
            failoverTarget = result.getNode();

            assertEquals(count++, result.getValue().intValue());
            assertNotEquals(target, failoverTarget);

            start(target);

            // Wait until the cluster topology pushed to EJB clients contains both nodes again
            ClusterTestUtil.establishClientMappings(directory, NODE_1_2);

            result = bean.increment();
            failbackTarget = result.getNode();
            assertEquals(count++, result.getValue().intValue());
            // Bean should retain weak affinity for this node
            assertEquals(failoverTarget, failbackTarget);

            result = bean.increment();
            // Bean may have acquired new weak affinity
            target = result.getNode();
            assertEquals(count++, result.getValue().intValue());

            // Bean should retain weak affinity for this node
            for (int i = 0; i < COUNT; ++i) {
                result = bean.increment();
                assertEquals(count++, result.getValue().intValue());
                assertEquals(target, result.getNode(), String.valueOf(i));
            }

            bean.remove();
        }
    }

    private static Set<String> others(String node) {
        return NODE_1_2.stream().filter(Predicate.not(node::equals)).collect(Collectors.toSet());
    }
}
