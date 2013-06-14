package org.gridkit.nimble.btrace;

import static org.gridkit.nimble.util.StringOps.F;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import net.java.btrace.client.Client;

import org.gridkit.nimble.btrace.ext.PollSamplesCmdResult;
import org.gridkit.nimble.btrace.ext.RingBuffer;
import org.gridkit.nimble.btrace.ext.model.DurationSample;
import org.gridkit.nimble.btrace.ext.model.PointSample;
import org.gridkit.nimble.btrace.ext.model.RateSample;
import org.gridkit.nimble.btrace.ext.model.SampleStoreContents;
import org.gridkit.nimble.btrace.ext.model.ScalarSample;
import org.gridkit.nimble.btrace.ext.model.SpanSample;
import org.gridkit.nimble.metering.PointSampler;
import org.gridkit.nimble.metering.ScalarSampler;
import org.gridkit.nimble.metering.SpanSampler;
import org.gridkit.nimble.probe.CachingSamplerFactory;
import org.gridkit.nimble.probe.RateSampler;
import org.gridkit.nimble.probe.SamplerFactory;
import org.gridkit.nimble.util.Seconds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BTraceProbe implements Callable<Void> {
    private static final Logger log = LoggerFactory.getLogger(BTraceProbe.class);
    
    private long pid;
    private BTraceScriptSettings settings;
    
    private BTraceClientOps clientOps;
    private BTraceClientSource clientSource;
    
    private BTraceSamplerFactoryProvider factoryProvider;
    
    private Map<String, SampleStoreProcessor> processors = new HashMap<String, SampleStoreProcessor>();
    
    private Client client;

    @Override
    public Void call() throws Exception {
        try {            
            Client client = getClient();
    
            PollSamplesCmdResult result = clientOps.pollSamples(
                client, getScriptClasses(), settings.getTimeoutMs()
            );
            
            for (SampleStoreContents contents : result.getData()) {
                getProcessor(contents.getSampleStore()).process(contents);
            }
            
            return null;
        } catch (Exception e) {
            log.error(F("Error while executing BTrace probe for pid %d with settings %s", pid, settings), e);
            throw e;
        }
    }

    private Client getClient() throws Exception {
        if (client == null) {
            try {
                client = clientSource.getClient(pid);
                                
                // submit is first because it is the only method initializing command channel
                clientOps.submit(client, settings.getScriptClass(), settings.getArgsArray(), settings.getTimeoutMs());
                
                clientOps.clearSamples(client, getScriptClasses(), settings.getTimeoutMs());
            } catch (Exception e) {
                log.error(F("Failed to connect to client with pid %d", pid));
                throw e;
            }
        }
        
        return client;
    }

    private class SampleStoreProcessor {        
        private SamplerFactory userSamplerFactory;
        
        private Map<String, PointSampler> rateSamplers = new HashMap<String, PointSampler>();
        
        private PointSampler receivedSampler;
        private PointSampler missedSampler;
        
        private long lastSeqNum = RingBuffer.START_ID - 1;
        
        public SampleStoreProcessor(String sampleStore) {
            this.userSamplerFactory = new CachingSamplerFactory(
                factoryProvider.getUserSampleFactory(pid, settings.getScriptClass(), sampleStore)
            );
            
            SamplerFactory probeSamplerFactory = factoryProvider.getProbeSamplerFactory(pid, settings.getScriptClass(), sampleStore);

            this.receivedSampler = new RateSampler(probeSamplerFactory.getSpanSampler(BTraceMeasure.SAMPLE_TYPE_RECEIVED));
            this.missedSampler = new RateSampler(probeSamplerFactory.getSpanSampler(BTraceMeasure.SAMPLE_TYPE_MISSED));
        }
        
        public void process(SampleStoreContents contents) {
            for (ScalarSample sample : contents.getSamples()) {
                submit(sample);
            }
            submit(contents);
        }
        
        private void submit(ScalarSample rawSample) {       
            if (rawSample instanceof SpanSample) {
                submitSpan((SpanSample)rawSample);
            } else if (rawSample instanceof DurationSample) {
                submitDuration((DurationSample)rawSample);
            } else if (rawSample instanceof RateSample) {
                submitRate((RateSample)rawSample);
            } else if (rawSample instanceof PointSample) {
                submitPoint((PointSample)rawSample);
            } else {
                submitScalar(rawSample);
            }
        }

        private void submit(SampleStoreContents contents) {
            int missed = calculateMissed(contents);
            
            double timestampS = Seconds.currentTime();
            
            missedSampler.write(missed, timestampS);
            receivedSampler.write(contents.getSamples().size(), timestampS);
        }
        
        private int calculateMissed(SampleStoreContents contents) {
            long newLastSeqNum = lastSeqNum;
            
            for (ScalarSample sample : contents.getSamples()) {
                newLastSeqNum = Math.max(newLastSeqNum, sample.getSeqNumber());
            }
            
            int result = (int)(newLastSeqNum - lastSeqNum - contents.getSamples().size());

            lastSeqNum = newLastSeqNum;

            return result;
        }
        
        public void submitScalar(ScalarSample sample) {            
            ScalarSampler sampler = userSamplerFactory.getScalarSampler(sample.getKey());
            
            sampler.write(sample.getValue().doubleValue());
        }

        public void submitSpan(SpanSample sample) {            
            SpanSampler sampler = userSamplerFactory.getSpanSampler(sample.getKey());
            
            double timestampS = Seconds.fromMillis(sample.getTimestampMs());
            double durationS = Seconds.fromNanos(sample.getDurationNs());
            
            sampler.write(sample.getValue().doubleValue(), timestampS, durationS);
        }
        
        public void submitRate(RateSample sample) {
            PointSampler sampler = getRateSampler(sample.getKey());
            
            double timestampS = Seconds.fromMillis(sample.getTimestampMs());
            
            sampler.write(sample.getValue().doubleValue(), timestampS);
        }
        
        public void submitDuration(DurationSample sample) {
            SpanSampler sampler = userSamplerFactory.getSpanSampler(sample.getKey());
            
            double timestampS = Seconds.fromMillis(sample.getTimestampMs());
            double durationS = Seconds.fromNanos(sample.getValue().doubleValue());
            
            sampler.write(1.0 , timestampS - durationS, durationS);
        }

        public void submitPoint(PointSample sample) {
            PointSampler sampler = userSamplerFactory.getPointSampler(sample.getKey());
            
            double timestampS = Seconds.fromMillis(sample.getTimestampMs());
            
            sampler.write(sample.getValue().doubleValue(), timestampS);
        }
        
        private PointSampler getRateSampler(String samplerKey) {         
            if (!rateSamplers.containsKey(samplerKey)) {
                rateSamplers.put(samplerKey, new RateSampler(userSamplerFactory.getSpanSampler(samplerKey)));
            }
            
            return rateSamplers.get(samplerKey);
        }
    }
    
    private Collection<Class<?>> getScriptClasses() {
        return Collections.<Class<?>>singleton(settings.getScriptClass());
    }
    
    public void setPid(long pid) {
        this.pid = pid;
    }

    public void setSettings(BTraceScriptSettings settings) {
        this.settings = settings;
    }

    public void setClientOps(BTraceClientOps clientOps) {
        this.clientOps = clientOps;
    }

    public void setClientSource(BTraceClientSource clientSource) {
        this.clientSource = clientSource;
    }

    public void setFactoryProvider(BTraceSamplerFactoryProvider factoryProvider) {
        this.factoryProvider = factoryProvider;
    }
        
    private SampleStoreProcessor getProcessor(String sampleStore) {
        if (!processors.containsKey(sampleStore)) {
            processors.put(sampleStore, new SampleStoreProcessor(sampleStore));
        }
        
        return processors.get(sampleStore);
    }
}