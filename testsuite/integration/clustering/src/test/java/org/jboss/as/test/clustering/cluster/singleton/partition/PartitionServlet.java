/*
 * Copyright The WildFly Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package org.jboss.as.test.clustering.cluster.singleton.partition;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

import jakarta.annotation.Resource;
import jakarta.servlet.ServletException;
import jakarta.servlet.annotation.WebServlet;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jgroups.Address;
import org.jgroups.Event;
import org.jgroups.JChannel;
import org.jgroups.View;
import org.jgroups.protocols.BasicTCP;
import org.jgroups.protocols.DISCARD;
import org.jgroups.protocols.FD_SOCK2;
import org.jgroups.protocols.TP;
import org.jgroups.protocols.pbcast.GMS;
import org.jgroups.stack.ProtocolStack;

/**
 * Servlet used to simulate network partitions. Partitioning and merging each take two steps, each of which must be requested
 * on all nodes before the next step is requested on any node:
 * <ol>
 * <li>{@code /partition?action=prepare} stores the current (full) view for the future merge event,</li>
 * <li>{@code /partition?action=partition} inserts DISCARD protocol to the stack and installs a new view directly on the {@link GMS},</li>
 * <li>{@code /partition?action=heal} removes the previously inserted {@link DISCARD} protocol,</li>
 * <li>{@code /partition?action=merge} passes a MERGE event up the stack, which other nodes no longer discard.</li>
 * </ol>
 * <p>
 * Note that while it would be desirable for the tests to leave splitting and merging to the servers themselves, this is not practical in a
 * test suite. While FD/VERIFY_SUSPECT/GMS can be be configured to detect partitions quickly the MERGE3 handles merges within randomized
 * intervals and uses unreliable channel which can easily take several minutes for merge to actually happen. Also, speeds up the test
 * significantly.
 *
 * @author Radoslav Husar
 */
@WebServlet(urlPatterns = {PartitionServlet.PARTITION})
public class PartitionServlet extends HttpServlet {
    private static final long serialVersionUID = 3034138469210308974L;

    public static final String PARTITION = "partition";
    private static final String ACTION = "action";

    public enum Action {
        PREPARE, PARTITION, HEAL, MERGE;
    }

    private static Map<Address, View> mergeViews;

    public static URI createURI(URL baseURL, Action action) throws URISyntaxException {
        return baseURL.toURI().resolve(PARTITION + '?' + ACTION + '=' + action.name().toLowerCase(Locale.ROOT));
    }

    @Resource(lookup = "java:jboss/jgroups/channel/default")
    private JChannel channel;

    @Override
    public void init() throws ServletException {
        ProtocolStack stack = this.channel.getProtocolStack();
        TP transport = stack.getTransport();
        if (transport instanceof BasicTCP) {
            BasicTCP tcp = (BasicTCP) transport;
            if (!(tcp.enableSuspectEvents() ^ (stack.findProtocol(FD_SOCK2.class) != null))) {
                // Verify that the stack uses transport-based failure detection or contains a socket-based failure detection protocol, but not both.
                throw new ServletException(String.format("Protocol stack for %s should contain exactly one socket-based failure detection mechanism", this.channel.getClusterName()));
            }
        }
    }

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException {
        Action action = Action.valueOf(request.getParameter(ACTION).toUpperCase(Locale.ROOT));

        this.log("Simulating network partitions: " + action);

        GMS gms = this.channel.getProtocolStack().findProtocol(GMS.class);
        try {
            switch (action) {
                case PREPARE -> {
                    // Store views for future merge event
                    mergeViews = new HashMap<>();
                    this.channel.getView().getMembers().forEach(
                            address -> mergeViews.put(address, View.create(address, gms.getViewId().getId() + 1, address))
                    );
                }
                case PARTITION -> {
                    // Simulate partitions by injecting DISCARD protocol
                    DISCARD discard = new DISCARD();
                    discard.discardAll(true);
                    this.channel.getProtocolStack().insertProtocol(discard, ProtocolStack.Position.ABOVE, TP.class);

                    // Speed up partitioning
                    View view = View.create(this.channel.getAddress(), gms.getViewId().getId() + 1, this.channel.getAddress());
                    gms.installView(view);
                }
                case HEAL -> this.channel.getProtocolStack().removeProtocol(DISCARD.class);
                case MERGE -> {
                    // Since the coordinator is determined by ordering the address in org.jgroups.protocols.pbcast.Merger#determineMergeLeader
                    // let just all nodes send the merge..
                    this.log("Passing event up the stack: " + new Event(Event.MERGE, mergeViews));

                    gms.up(new Event(Event.MERGE, mergeViews));
                    mergeViews = null;
                }
            }
        } catch (Exception e) {
            throw new ServletException(e);
        }

        response.getWriter().write("Success");
    }
}
