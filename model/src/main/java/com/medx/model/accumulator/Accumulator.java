package com.medx.model.accumulator;

import com.medx.metamodel.Facade;
import com.medx.type.annotation.DictType;

@Facade
@DictType(packageCutPrefix = "com.medx.model", javaAddPrefix = "com.medx.dictionary")
public interface Accumulator {

	public AccumKey getKey();
	
	public LookBackPeriod getLookBackPeriod();
	
}
