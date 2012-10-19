package org.gridkit.nimble.probe;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Random;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.gridkit.nimble.driver.MeteringDriver;
import org.gridkit.nimble.metering.SampleSchema;

public class ProbeOps {
    private static final Random rnd = new Random();
    
    public interface SingleProbeFactory<P> {
        Runnable newProbe(P param, SampleSchema schema);
    }

    public static <P> List<Runnable> instantiate(Collection<P> params, SingleProbeFactory<P> factory, MeteringDriver metering) {
        List<Runnable> probes = new ArrayList<Runnable>();
        
        for (P param : params) {
            probes.add(factory.newProbe(param, metering.getSchema()));
        }

        return probes;
    }

    public static ProbeHandle schedule(Collection<Runnable> probes, ScheduledExecutorService executor, long pollDelayMs) {
        List<Future<?>> futures = new ArrayList<Future<?>>();
        
        for (Runnable probe : probes) {
            Future<?> future = executor.scheduleWithFixedDelay(probe, getInitialDelay(pollDelayMs), pollDelayMs, TimeUnit.MILLISECONDS);
            futures.add(future);
        }
        
        return new FutureProbeHandle(futures);
    }
    
    private static long getInitialDelay(long pollDelayMs) {
        return Math.abs(rnd.nextLong()) % pollDelayMs;
    }
}
