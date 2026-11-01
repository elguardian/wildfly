/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.as.test.clustering;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeoutException;

/**
 * Remote listener for the EJB client mappings registry, i.e. the cluster topology that a server pushes to its remote EJB clients.
 * A server publishes its client mappings while it has EJB deployments, so the registry changes on node start/stop and on deploy/undeploy.
 * Waiting for the registry to reach a given state replaces fixed sleeps that allow clients to "receive the new topology".
 * @author Enrique
 */
public interface ClientMappingsListener {
    Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);
    /** The EJB remoting connector of the default server configuration. */
    String DEFAULT_CONNECTOR = "http-remoting-connector";

    /**
     * Waits until the client mappings registry contains entries for exactly the specified nodes.
     * @param nodes the names of the nodes expected to publish client mappings
     * @param timeout a timeout for which to wait for the expected registry entries
     * @throws TimeoutException if the registry did not reach the expected state within the timeout
     */
    void establishClientMappings(Set<String> nodes, Duration timeout) throws TimeoutException;

    /**
     * Waits until the client mappings registry of the specified EJB remoting connector contains entries for exactly the specified nodes.
     * @param connector the name of the EJB remoting connector, e.g. {@value #DEFAULT_CONNECTOR}
     * @param nodes the names of the nodes expected to publish client mappings
     * @param timeout a timeout for which to wait for the expected registry entries
     * @throws TimeoutException if the registry did not reach the expected state within the timeout
     */
    void establishClientMappings(String connector, Set<String> nodes, Duration timeout) throws TimeoutException;
}
