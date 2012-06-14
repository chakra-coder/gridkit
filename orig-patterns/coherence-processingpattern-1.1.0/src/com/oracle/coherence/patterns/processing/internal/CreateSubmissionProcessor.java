/*
 * File: CreateSubmissionProcessor.java
 * 
 * Copyright (c) 2009. All Rights Reserved. Oracle Corporation.
 * 
 * Oracle is a registered trademark of Oracle Corporation and/or its
 * affiliates.
 * 
 * This software is the confidential and proprietary information of Oracle
 * Corporation. You shall not disclose such confidential and proprietary
 * information and shall use it only in accordance with the terms of the
 * license agreement you entered into with Oracle Corporation.
 * 
 * Oracle Corporation makes no representations or warranties about 
 * the suitability of the software, either express or implied, 
 * including but not limited to the implied warranties of 
 * merchantability, fitness for a particular purpose, or 
 * non-infringement.  Oracle Corporation shall not be liable for 
 * any damages suffered by licensee as a result of using, modifying 
 * or distributing this software or its derivatives.
 * 
 * This notice may not be removed or altered.
 */
package com.oracle.coherence.patterns.processing.internal;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;

import com.tangosol.io.ExternalizableLite;
import com.tangosol.io.pof.PofReader;
import com.tangosol.io.pof.PofWriter;
import com.tangosol.io.pof.PortableObject;
import com.tangosol.util.ExternalizableHelper;
import com.tangosol.util.InvocableMap.Entry;
import com.tangosol.util.processor.AbstractProcessor;

/**
 * <p>The {@link CreateSubmissionProcessor} is responsible for submitting
 * {@link Submission}s for processing.</p>
 * 
 * @author Noah Arliss
 * @author Brian Oliver
 */
@SuppressWarnings("serial")
public class CreateSubmissionProcessor extends AbstractProcessor 
								       implements PortableObject, ExternalizableLite {
	
	/**
	 * <p>The {@link Submission} to submit and have processed.</p>
	 */
	private Submission submission;
	
	
	/**
	 * <p>Required for {@link ExternalizableLite} and {@link PortableObject}.</p>
	 */
	public CreateSubmissionProcessor() {
		
	}
	
	
	/**
	 * <p>Standard Constructor</p>
	 * 
	 * @param submission The {@link Submission} to process
	 */
	public CreateSubmissionProcessor(Submission submission) {
		this.submission = submission;
	}
	

	/**
	 * <p>Returns the {@link Submission} to be processed.</p>
	 * 
	 * @return the {@link Submission} to be processed.
	 */
	public Submission getSubmission() {
		return submission;
	}

	
	/**
	 * {@inheritDoc}
	 */
	public void readExternal(PofReader reader) throws IOException {
		this.submission = (Submission)reader.readObject(0);
	}

	
	/**
	 * {@inheritDoc}
	 */
	public void writeExternal(PofWriter writer) throws IOException {
		writer.writeObject(0, this.submission);
	}

	
	/**
	 * {@inheritDoc}
	 */
	public void readExternal(DataInput in) throws IOException {
		this.submission = (Submission)ExternalizableHelper.readObject(in);		
	}

	
	/**
	 * {@inheritDoc}
	 */
	public void writeExternal(DataOutput out) throws IOException {
		ExternalizableHelper.writeObject(out, this.submission);
	}

	
	/**
	 * {@inheritDoc}
	 */
	public Object process(Entry entry) {
		if (entry.isPresent()) {
			//TODO: when the entry is already present, we better do something.
			//perhaps we should log and have the client reattempt?
			//(it's extremely unlikely for this to every occur, but we should probably do something)
			return null;
		} else {
			entry.setValue(submission);
			return entry.getKey();
		}		
	}
}