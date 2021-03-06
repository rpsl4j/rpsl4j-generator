/*
 * Copyright (c) 2015 Benjamin Roberts, Nathan Kelly, Andrew Maxwell
 * All rights reserved.
 */

package org.rpsl4j.emitters;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import org.rpsl4j.OutputEmitterProvider;

import net.ripe.db.whois.common.rpsl.RpslObject;

/**
 * Empty implementation of {@link OutputEmitter}. Ignores all input and returns the empty string.
 * Used as the fall back emitter in {@link OutputEmitterProvider}.
 * @author Benjamin George Roberts
 */
public class NullEmitter implements OutputEmitter {

	@Override
	public String emit(Set<RpslObject> objects) {
		return "";
	}

	@Override
	public void setArguments(Map<String, String> arguments) {		
	}
	
	@Override
	public Map<String, String> validArguments(){
		return new HashMap<String, String>();
	}

}
