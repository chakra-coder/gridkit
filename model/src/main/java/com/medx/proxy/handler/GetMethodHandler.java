package com.medx.proxy.handler;

import java.util.Map;

public class GetMethodHandler implements MethodHandler {
	public static String getPrefix() {
		return "get";
	}
	
	private final int attributeId;
	
	public GetMethodHandler(int attributeId) {
		this.attributeId = attributeId;
	}

	@Override
	public Object invoke(AttributeAccessor attributeAccessor, Object[] args) {
		Object attribute = attributeAccessor.getAttributeValue(attributeId);
		
		if (args.length == 0)
			return attribute;
		else if (args.length == 1)
			return ((Map<?, ?>)attribute).get(args[0]);
		else
			throw new RuntimeException("Unexpected arguments count");
	}
}
