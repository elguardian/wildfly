/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.as.test.clustering;

import static org.jboss.as.controller.descriptions.ModelDescriptionConstants.*;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.function.IntPredicate;

import org.jboss.as.controller.PathAddress;
import org.jboss.as.controller.client.ModelControllerClient;
import org.jboss.as.controller.operations.common.Util;
import org.jboss.as.test.clustering.cluster.AbstractClusteringTestCase;
import org.jboss.as.test.shared.TimeoutUtil;
import org.jboss.dmr.ModelNode;
import org.jboss.logging.Logger;

/**
 * Waits until servers have no requests in progress, as counted by the request-controller subsystem.
 * <p>
 * WildFly signals request completion to the request controller only after {@code Session.requestDone(...)}, i.e. after a distributable
 * session has been written to its cache (see {@code ControlPointDeploymentInfoConfigurator}). The HTTP response however is sent before that,
 * so a client can issue its next request (e.g. to another node) while a non-transactional session write is still replicating.
 * Waiting for {@code active-requests == 0} on the node(s) that served the previous requests closes that window,
 * replacing fixed "grace time to replicate" sleeps.
 * @author Enrique
 */
public final class ActiveRequestsUtil {

    public static final Duration DEFAULT_TIMEOUT = TimeoutUtil.adjust(Duration.ofSeconds(15));
    private static final Duration POLL_INTERVAL = Duration.ofMillis(5);
    private static final ModelNode READ_ACTIVE_REQUESTS = Util.getReadAttributeOperation(PathAddress.pathAddress(SUBSYSTEM, "request-controller"), "active-requests");
    private static final Logger log = Logger.getLogger(ActiveRequestsUtil.class);

    /**
     * Waits until the specified nodes have no active requests.
     * @param nodes node names, e.g. {@code node-1}
     */
    public static void awaitNoActiveRequests(Collection<String> nodes) {
        awaitActiveRequests(nodes, active -> active == 0, "no active requests");
    }

    /**
     * Waits until a request is in progress on the specified node, e.g. a long running request sent asynchronously.
     * @param node a node name, e.g. {@code node-1}
     */
    public static void awaitActiveRequest(String node) {
        awaitActiveRequests(List.of(node), active -> active > 0, "an active request");
    }

    private static void awaitActiveRequests(Collection<String> nodes, IntPredicate condition, String description) {
        Instant stop = Instant.now().plus(DEFAULT_TIMEOUT);
        for (String node : nodes) {
            try (ModelControllerClient client = ModelControllerClient.Factory.create(host(node), managementPort(node))) {
                awaitActiveRequests(client, node, condition, description, stop);
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            } catch (TimeoutException e) {
                throw new IllegalStateException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static void awaitActiveRequests(ModelControllerClient client, String node, IntPredicate condition, String description, Instant stop) throws IOException, TimeoutException, InterruptedException {
        Instant start = Instant.now();
        int polls = 0;
        while (true) {
            ModelNode result = client.execute(READ_ACTIVE_REQUESTS);
            if (!SUCCESS.equals(result.get(OUTCOME).asString())) {
                throw new IllegalStateException(String.format("Cannot read active requests of %s: %s", node, result));
            }
            int active = result.get(RESULT).asInt();
            if (condition.test(active)) {
                if (polls > 0) {
                    log.debugf("%s: %s after %s", node, description, Duration.between(start, Instant.now()));
                }
                return;
            }
            if (Instant.now().isAfter(stop)) {
                throw new TimeoutException(String.format("%s did not reach %s, active requests: %d", node, description, active));
            }
            polls++;
            Thread.sleep(POLL_INTERVAL.toMillis());
        }
    }

    private static InetAddress host(String node) throws UnknownHostException {
        String host = switch (node) {
            case AbstractClusteringTestCase.NODE_1 -> AbstractClusteringTestCase.TESTSUITE_NODE0;
            case AbstractClusteringTestCase.NODE_2 -> AbstractClusteringTestCase.TESTSUITE_NODE1;
            case AbstractClusteringTestCase.NODE_3 -> AbstractClusteringTestCase.TESTSUITE_NODE2;
            case AbstractClusteringTestCase.NODE_4 -> AbstractClusteringTestCase.TESTSUITE_NODE3;
            default -> throw new IllegalArgumentException(node);
        };
        return InetAddress.getByName(host);
    }

    private static int managementPort(String node) {
        return 9990 + AbstractClusteringTestCase.getPortOffsetForNode(node);
    }

    private ActiveRequestsUtil() {
    }
}
