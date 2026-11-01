/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */
package org.jboss.as.test.clustering;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeoutException;
import javax.naming.Context;
import javax.naming.InitialContext;
import javax.naming.NamingException;

import jakarta.ejb.Remote;
import jakarta.ejb.Stateless;

import org.infinispan.Cache;
import org.infinispan.factories.GlobalComponentRegistry;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.notifications.Listener;
import org.infinispan.notifications.Listener.Observation;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryCreated;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryModified;
import org.infinispan.notifications.cachelistener.annotation.CacheEntryRemoved;
import org.infinispan.notifications.cachelistener.annotation.TopologyChanged;
import org.infinispan.notifications.cachelistener.event.Event;
import org.infinispan.util.concurrent.BlockingManager;
import org.jboss.logging.Logger;

/**
 * Jakarta Enterprise Beans that waits for the EJB client mappings registry to reach a given state.
 * There is one registry per EJB remoting connector, backed by a replicated cache of the "ejb" container that is named after the connector
 * (created from the "client-mappings" cache configuration). Its values are (node name, client mappings) entries;
 * an Infinispan listener on that cache wakes the waiting thread on every change.
 * These are the same cache events that drive the cluster topology updates sent to remote EJB clients.
 * @author Enrique
 */
@Stateless
@Remote(ClientMappingsListener.class)
public class ClientMappingsListenerBean implements ClientMappingsListener {

    static final String CONTAINER = "ejb";

    private static final Logger logger = Logger.getLogger(ClientMappingsListenerBean.class);

    @Override
    public void establishClientMappings(Set<String> nodes, Duration timeout) throws TimeoutException {
        this.establishClientMappings(DEFAULT_CONNECTOR, nodes, timeout);
    }

    @Override
    public void establishClientMappings(String connector, Set<String> nodes, Duration timeout) throws TimeoutException {
        EmbeddedCacheManager manager = findCacheManager();
        if (manager == null) {
            throw new IllegalStateException(String.format("Cache container %s not found", CONTAINER));
        }
        if (!manager.isRunning(connector)) {
            throw new IllegalStateException(String.format("No client mappings registry cache %s.%s; caches: %s", CONTAINER, connector, manager.getCacheNames()));
        }
        // Obtain the cache with the cache manager's class loader: the registry values are server types,
        // and a deployment class loader would require marshallers for them
        Cache<?, ?> cache;
        Thread thread = Thread.currentThread();
        ClassLoader loader = thread.getContextClassLoader();
        thread.setContextClassLoader(manager.getCacheManagerConfiguration().classLoader());
        try {
            cache = manager.getCache(connector);
        } finally {
            thread.setContextClassLoader(loader);
        }
        Set<String> expected = new TreeSet<>(nodes);
        Object listener = new ClientMappingsChangeListener();
        synchronized (listener) {
            cache.addListener(listener);
            try {
                Set<String> current = getNodes(cache);
                Instant start = Instant.now();
                Instant stop = start.plus(timeout);
                while (!current.equals(expected) && Instant.now().isBefore(stop)) {
                    logger.debugf("%s != %s, waiting for a client mappings change", expected, current);
                    long millis = Duration.between(Instant.now(), stop).toMillis();
                    if (millis > 0) {
                        listener.wait(millis);
                    }
                    current = getNodes(cache);
                }
                if (!current.equals(expected)) {
                    throw new TimeoutException(String.format("Client mappings %s not established within %s. Current client mappings: %s", expected, timeout, current));
                }
                logger.debugf("Client mappings %s established in %s", expected, Duration.between(start, Instant.now()));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                cache.removeListener(listener);
            }
        }
    }

    private static EmbeddedCacheManager findCacheManager() {
        try {
            Context context = new InitialContext();
            try {
                return (EmbeddedCacheManager) context.lookup("java:jboss/infinispan/container/" + CONTAINER);
            } finally {
                context.close();
            }
        } catch (NamingException e) {
            return null;
        }
    }

    /**
     * Returns the names of the nodes that currently publish client mappings.
     * Registry values are entries of node name to client mappings.
     */
    private static Set<String> getNodes(Cache<?, ?> cache) {
        Set<String> nodes = new TreeSet<>();
        for (Object value : cache.values()) {
            if (value instanceof Map.Entry<?, ?> entry) {
                nodes.add(entry.getKey().toString());
            }
        }
        return nodes;
    }

    @Listener(observation = Observation.POST)
    static class ClientMappingsChangeListener implements Runnable {
        @CacheEntryCreated
        @CacheEntryModified
        @CacheEntryRemoved
        @TopologyChanged
        public CompletionStage<Void> changed(Event<?, ?> event) {
            // Do not block the notifying (non-blocking) thread
            BlockingManager blocking = GlobalComponentRegistry.componentOf(event.getCache().getCacheManager(), BlockingManager.class);
            blocking.asExecutor(this.getClass().getName()).execute(this);
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void run() {
            synchronized (this) {
                this.notify();
            }
        }
    }
}
