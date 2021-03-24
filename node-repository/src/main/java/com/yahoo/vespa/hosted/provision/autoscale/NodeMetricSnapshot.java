// Copyright Verizon Media. Licensed under the terms of the Apache 2.0 license. See LICENSE in the project root.
package com.yahoo.vespa.hosted.provision.autoscale;

import java.time.Instant;

/**
 * A single measurement of all values we measure for one node.
 *
 * @author bratseth
 */
public class NodeMetricSnapshot implements Comparable<NodeMetricSnapshot> {

    private final Instant at;

    private final Load load;
    private final long generation;
    private final boolean inService;
    private final boolean stable;
    private final double queryRate;

    public NodeMetricSnapshot(Instant at, double cpu, double memory, double disk,
                              long generation, boolean inService, boolean stable,
                              double queryRate) {
        this.at = at;
        this.load = new Load(cpu, memory, disk);
        this.generation = generation;
        this.inService = inService;
        this.stable = stable;
        this.queryRate = queryRate;
    }

    public Instant at() { return at; }
    public double cpu() { return load.cpu(); } // TODO: Remove
    public double memory() { return load.memory(); } // TODO: Remove
    public double disk() { return load.disk(); } // TODO: Remove
    public Load load() { return load; }

    /** Queries per second */
    public double queryRate() { return queryRate; }

    /** The configuration generation at the time of this measurement, or -1 if not known */
    public long generation() { return generation; }

    public boolean inService() { return inService; }
    public boolean stable() { return stable; }

    @Override
    public int compareTo(NodeMetricSnapshot other) {
        return at.compareTo(other.at);
    }

    @Override
    public String toString() { return "metrics at " + at + ": " +
                                      load +
                                      " generation: " + generation +
                                      " inService: " + inService +
                                      " stable: " + stable +
                                      " queryRate: " + queryRate;
    }

}
