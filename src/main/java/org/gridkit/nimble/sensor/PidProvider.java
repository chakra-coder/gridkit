package org.gridkit.nimble.sensor;

import static org.gridkit.nimble.util.StringOps.F;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.gridkit.nimble.util.JvmOps;
import org.hyperic.sigar.SigarException;
import org.hyperic.sigar.ptql.ProcessFinder;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;
import com.sun.tools.attach.VirtualMachineDescriptor;

public interface PidProvider {
    Collection<Long> getPids();
    
    @SuppressWarnings("serial")
    public static class CurPidProvider extends SigarHolder implements PidProvider {
        @Override
        public Collection<Long> getPids() {
            return Collections.singleton(getSigar().getPid());
        }
    }

    /**
     * http://support.hyperic.com/display/SIGAR/PTQL
     */
    @SuppressWarnings("serial")
    public static class PtqlPidProvider extends SigarHolder implements PidProvider {
        private static final Logger log = LoggerFactory.getLogger(PtqlPidProvider.class);
        
        private String query;

        public PtqlPidProvider(String query) {
            this.query = query;
        }

        @Override
        public Collection<Long> getPids() {
            List<Long> pids = new ArrayList<Long>();
            
            try {
                for (long pid : ProcessFinder.find(getSigar(), query)) {
                    pids.add(pid);
                }
            } catch (SigarException e) {
                log.error(F("Error while getting processes CPU usage by query '%s'", query), e);
                return null;
            }

            return pids;
        }
    }
    
    @SuppressWarnings("serial")
    public static class JavaPidProvider implements PidProvider, Serializable {        
        private Predicate<String> namePredicate;
        private Predicate<Properties> propsPredicate;

        public JavaPidProvider(Predicate<String> namePredicate, Predicate<Properties> propsPredicate) {
            this.namePredicate = namePredicate;
            this.propsPredicate = propsPredicate;
        }

        public static JavaPidProvider byName(Predicate<String> namePredicate) {
            return new JavaPidProvider(namePredicate, Predicates.<Properties>alwaysTrue());
        }
        
        public static JavaPidProvider byProps(Predicate<Properties> propsPredicate) {
            return new JavaPidProvider(Predicates.<String>alwaysTrue(), propsPredicate);
        }

        @Override
        public Collection<Long> getPids() {
            Collection<Long> pids = new HashSet<Long>();
            
            List<VirtualMachineDescriptor> descs = JvmOps.listVms();
            Collections.shuffle(descs);
                            
            for (VirtualMachineDescriptor desc : descs) {
                if (!namePredicate.apply(desc.displayName())) {
                    continue;
                }
                
                Properties props = JvmOps.getProps(desc);
                
                if (props != null && propsPredicate.apply(props)) {
                    pids.add(Long.valueOf(desc.id()));
                }
            }
                                        
            return pids;
        }
        
        @Override
        public String toString() {
            return F("%s[%s,%s]", JavaPidProvider.class.getSimpleName(), namePredicate, propsPredicate);
        }
    }
    
    @SuppressWarnings("serial")
    public static class CompositePidProvider implements PidProvider, Serializable {
        private Collection<PidProvider> pidProviders;

        public CompositePidProvider(Collection<PidProvider> pidProviders) {
            this.pidProviders = pidProviders;
        }

        public CompositePidProvider(PidProvider... pidProviders) {
            this(Arrays.asList(pidProviders));
        }

        @Override
        public Collection<Long> getPids() {
            Collection<Long> result = new HashSet<Long>();
            
            for (PidProvider pidProvider : pidProviders) {
                result.addAll(pidProvider.getPids());
            }
            
            return result;
        }
    }

	@SuppressWarnings("serial")
	public static class PatternJvmPidProvider implements PidProvider, Serializable {        
	    
	    private final Map<String, Pattern> patterns = new LinkedHashMap<String, Pattern>();
	
	    public PatternJvmPidProvider() {
	    }

	    public void matchVmName(String pattern) {
	    	matchProp(":name", pattern);
	    }
	    
	    public void matchProp(String prop, String pattern) {
	    	Pattern p = Pattern.compile(pattern);
	    	patterns.put(prop, p);
	    }

	    public void matchPropExact(String prop, String pattern) {
	    	matchProp(prop, Pattern.quote(pattern));
	    }
	
	    @Override
	    public Collection<Long> getPids() {
	        Collection<Long> pids = new HashSet<Long>();
	        
	        List<VirtualMachineDescriptor> descs = new ArrayList<VirtualMachineDescriptor>(JvmOps.listVms());
	        Collections.shuffle(descs);
	        
	        nextVm:
	        for (VirtualMachineDescriptor desc : descs) {
	        	if (patterns.containsKey(":name")) {
	        		if (!match(":name", desc.displayName())) {
	        			continue;
	        		}
	        	}
	            
	            Properties props = JvmOps.getProps(desc);
	            
	            for(String prop: patterns.keySet()) {
	            	if (!prop.startsWith(":")) {
	            		if (!match(prop, props.getProperty(prop))) {
	            			continue nextVm;
	            		}
	            	}
	            }
	            
	            pids.add(Long.parseLong(desc.id()));
	        }
	        
//	        System.err.println(this.toString() + " -> " + pids.toString());
	                                    
	        return pids;
	    }
	    
	    private boolean match(String prop, String value) {
			Matcher matcher = patterns.get(prop).matcher(value);
			return matcher.matches();
		}


		@Override
	    public String toString() {
	        return F("%s%s", getClass().getSimpleName(), patterns.toString());
	    }
	}
}
