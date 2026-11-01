/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.as.test.clustering.cluster.group;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import org.jboss.arquillian.container.test.api.Deployment;
import org.jboss.arquillian.container.test.api.TargetsContainer;
import org.jboss.arquillian.junit5.ArquillianExtension;
import org.jboss.as.test.clustering.cluster.AbstractClusteringTestCase;
import org.jboss.as.test.clustering.cluster.group.bean.ClusterTopology;
import org.jboss.as.test.clustering.cluster.group.bean.ClusterTopologyRetriever;
import org.jboss.as.test.clustering.cluster.group.bean.ClusterTopologyRetrieverBean;
import org.jboss.as.test.clustering.cluster.group.bean.legacy.LegacyClusterTopologyRetrieverBean;
import org.jboss.as.test.clustering.ejb.EJBDirectory;
import org.jboss.as.test.clustering.ejb.RemoteEJBDirectory;
import org.jboss.as.test.shared.PermissionUtils;
import org.jboss.as.test.shared.TimeoutUtil;
import org.jboss.shrinkwrap.api.Archive;
import org.jboss.shrinkwrap.api.ShrinkWrap;
import org.jboss.shrinkwrap.api.spec.WebArchive;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

/**
 * Integration test for the listener facility of a {@link LegacyGroup}.
 * @author Paul Ferraro
 */
@ExtendWith(ArquillianExtension.class)
public class GroupListenerTestCase extends AbstractClusteringTestCase {
    private static final String MODULE_NAME = GroupListenerTestCase.class.getSimpleName();
    private static final Duration VIEW_CHANGE_TIMEOUT = Duration.ofMillis(TimeoutUtil.adjust(15000));
    private static final Duration POLL_INTERVAL = Duration.ofMillis(50);

    @Deployment(name = DEPLOYMENT_1, managed = false, testable = false)
    @TargetsContainer(NODE_1)
    public static Archive<?> deployment1() {
        return createDeployment();
    }

    @Deployment(name = DEPLOYMENT_2, managed = false, testable = false)
    @TargetsContainer(NODE_2)
    public static Archive<?> deployment2() {
        return createDeployment();
    }

    private static Archive<?> createDeployment() {
        WebArchive war = ShrinkWrap.create(WebArchive.class, MODULE_NAME + ".war");
        war.addPackage(ClusterTopologyRetrieverBean.class.getPackage());
        war.addPackage(LegacyClusterTopologyRetrieverBean.class.getPackage());
        war.setWebXML(GroupListenerTestCase.class.getPackage(), "web.xml");
        war.addAsManifestResource(PermissionUtils.createPermissionsXmlAsset(new RuntimePermission("getClassLoader")), "permissions.xml");
        return war;
    }

    @Test
    void test() throws Exception {
        this.test(ClusterTopologyRetrieverBean.class);
    }

    @Test
    void legacy() throws Exception {
        this.test(LegacyClusterTopologyRetrieverBean.class);
    }

    /**
     * Polls the group listener until it reports the expected current membership, and returns that topology.
     */
    private static ClusterTopology awaitCurrentMembers(ClusterTopologyRetriever bean, Set<String> members) throws InterruptedException {
        Instant stop = Instant.now().plus(VIEW_CHANGE_TIMEOUT);
        ClusterTopology topology = bean.getClusterTopology();
        while (!Set.copyOf(topology.getCurrentMembers()).equals(members) && Instant.now().isBefore(stop)) {
            Thread.sleep(POLL_INTERVAL.toMillis());
            topology = bean.getClusterTopology();
        }
        return topology;
    }

    public void test(Class<? extends ClusterTopologyRetriever> beanClass) throws Exception {
        try (EJBDirectory directory = new RemoteEJBDirectory(MODULE_NAME)) {
            ClusterTopologyRetriever bean = directory.lookupStateless(LegacyClusterTopologyRetrieverBean.class, ClusterTopologyRetriever.class);

            ClusterTopology topology = bean.getClusterTopology();
            assertEquals(2, topology.getCurrentMembers().size(), topology.getCurrentMembers().toString());
            assertTrue(topology.getCurrentMembers().contains(NODE_1), topology.getCurrentMembers().toString());
            assertTrue(topology.getCurrentMembers().contains(NODE_2), topology.getCurrentMembers().toString());

            stop(NODE_2);

            topology = awaitCurrentMembers(bean, Set.of(NODE_1));
            assertEquals(1, topology.getCurrentMembers().size(), topology.getCurrentMembers().toString());
            assertTrue(topology.getCurrentMembers().contains(NODE_1), topology.getCurrentMembers().toString());
            assertEquals(2, topology.getPreviousMembers().size(), topology.getPreviousMembers().toString());
            assertTrue(topology.getPreviousMembers().contains(NODE_1), topology.getPreviousMembers().toString());
            assertTrue(topology.getPreviousMembers().contains(NODE_2), topology.getPreviousMembers().toString());

            start(NODE_2);

            topology = awaitCurrentMembers(bean, NODE_1_2);
            assertEquals(2, topology.getCurrentMembers().size(), topology.getCurrentMembers().toString());
            assertTrue(topology.getCurrentMembers().contains(NODE_1), topology.getCurrentMembers().toString());
            assertTrue(topology.getCurrentMembers().contains(NODE_2), topology.getCurrentMembers().toString());
            if (topology.getTargetMember().equals(NODE_1)) {
                assertEquals(1, topology.getPreviousMembers().size(), topology.getPreviousMembers().toString());
                assertTrue(topology.getPreviousMembers().contains(NODE_1), topology.getPreviousMembers().toString());
            } else {
                // Since node 2 was just started, its previous membership will be empty
                assertEquals(0, topology.getPreviousMembers().size(), topology.getPreviousMembers().toString());
            }

            stop(NODE_1);

            topology = awaitCurrentMembers(bean, Set.of(NODE_2));
            assertEquals(1, topology.getCurrentMembers().size(), topology.getCurrentMembers().toString());
            assertTrue(topology.getCurrentMembers().contains(NODE_2), topology.getCurrentMembers().toString());
            assertEquals(2, topology.getPreviousMembers().size(), topology.getPreviousMembers().toString());
            assertTrue(topology.getPreviousMembers().contains(NODE_1), topology.getPreviousMembers().toString());
            assertTrue(topology.getPreviousMembers().contains(NODE_2), topology.getPreviousMembers().toString());

            start(NODE_1);

            topology = awaitCurrentMembers(bean, NODE_1_2);
            assertEquals(2, topology.getCurrentMembers().size(), topology.getCurrentMembers().toString());
            assertTrue(topology.getCurrentMembers().contains(NODE_1), topology.getCurrentMembers().toString());
            assertTrue(topology.getCurrentMembers().contains(NODE_2), topology.getCurrentMembers().toString());
            if (topology.getTargetMember().equals(NODE_2)) {
                assertEquals(1, topology.getPreviousMembers().size(), topology.getPreviousMembers().toString());
                assertTrue(topology.getPreviousMembers().contains(NODE_2), topology.getPreviousMembers().toString());
            } else {
                // Since node 1 was just started, its previous membership will be empty
                assertEquals(0, topology.getPreviousMembers().size(), topology.getPreviousMembers().toString());
            }
        }
    }
}
