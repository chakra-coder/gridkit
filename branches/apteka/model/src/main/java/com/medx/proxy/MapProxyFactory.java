package com.medx.proxy;

import java.util.Map;

public interface MapProxyFactory {
	public static final int CLASSES_KEY = Integer.MIN_VALUE;
	
	boolean isProxiable(Object object);
	
	<T> T createMapProxy(Map<Integer, Object> backendMap);
}
