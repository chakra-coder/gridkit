package org.apache.lucene.store.instantiated;

/**
 * Copyright 2006 The Apache Software Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.Closeable;
import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.Fieldable;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermEnum;
import org.apache.lucene.index.TermPositionVector;
import org.apache.lucene.index.TermPositions;
import org.apache.lucene.util.BitVector;

/**
 * Represented as a coupled graph of class instances, this all-in-memory index
 * store implementation delivers search results up to a 100 times faster than
 * the file-centric RAMDirectory at the cost of greater RAM consumption.
 * <p>
 * 
 * @lucene.experimental <p>
 *                      There are no read and write locks in this store.
 *                      {@link InstantiatedIndexReader}
 *                      {@link InstantiatedIndexReader#isCurrent()} all the time
 *                      and
 *                      {@link org.apache.lucene.store.instantiated.InstantiatedIndexWriter}
 *                      will attempt to update instances of the object graph in
 *                      memory at the same time as a searcher is reading from
 *                      it.
 * 
 *                      Consider using InstantiatedIndex as if it was immutable.
 */
public class InstantiatedIndex implements Serializable, Closeable {

	private static final long serialVersionUID = 1l;

	private long version = System.currentTimeMillis();

	private InstantiatedDocument[] documentsByNumber;

	private BitVector deletedDocuments;

	private Map<String, Map<String, InstantiatedTerm>> termsByFieldAndText;
	private InstantiatedTerm[] orderedTerms;

	private Map<String, byte[]> normsByFieldNameAndDocumentNumber;

	private FieldSettings fieldSettings;

	/**
	 * Creates an empty instantiated index for you to fill with data using an
	 * {@link org.apache.lucene.store.instantiated.InstantiatedIndexWriter}.
	 */
	public InstantiatedIndex() {
		initialize();
	}

	void initialize() {
		// todo: clear index without loosing memory (uncouple stuff)
		termsByFieldAndText = new HashMap<String, Map<String, InstantiatedTerm>>();
		fieldSettings = new FieldSettings();
		orderedTerms = new InstantiatedTerm[0];
		documentsByNumber = new InstantiatedDocument[0];
		normsByFieldNameAndDocumentNumber = new HashMap<String, byte[]>();
	}

	/**
	 * Creates a new instantiated index that looks just like the index in a
	 * specific state as represented by a reader.
	 * 
	 * @param sourceIndexReader
	 *            the source index this new instantiated index will be copied
	 *            from.
	 * @throws IOException
	 *             if the source index is not optimized, or when accessing the
	 *             source.
	 */
	public InstantiatedIndex(IndexReader sourceIndexReader) throws IOException {
		this(sourceIndexReader, null);
	}

	/**
	 * Creates a new instantiated index that looks just like the index in a
	 * specific state as represented by a reader.
	 * 
	 * @param sourceIndexReader
	 *            the source index this new instantiated index will be copied
	 *            from.
	 * @param fields
	 *            fields to be added, or null for all
	 * @throws IOException
	 *             if the source index is not optimized, or when accessing the
	 *             source.
	 */
	public InstantiatedIndex(IndexReader sourceIndexReader, Set<String> fields)
			throws IOException {

		initialize();

		Collection<String> allFieldNames = sourceIndexReader
				.getFieldNames(IndexReader.FieldOption.ALL);

		// load field options

		Collection<String> indexedNames = sourceIndexReader
				.getFieldNames(IndexReader.FieldOption.INDEXED);
		for (String name : indexedNames) {
			FieldSetting setting = fieldSettings.get(name, true);
			setting.indexed = true;
		}
		Collection<String> indexedNoVecNames = sourceIndexReader
				.getFieldNames(IndexReader.FieldOption.INDEXED_NO_TERMVECTOR);
		for (String name : indexedNoVecNames) {
			FieldSetting setting = fieldSettings.get(name, true);
			setting.storeTermVector = false;
			setting.indexed = true;
		}
		Collection<String> indexedVecNames = sourceIndexReader
				.getFieldNames(IndexReader.FieldOption.INDEXED_WITH_TERMVECTOR);
		for (String name : indexedVecNames) {
			FieldSetting setting = fieldSettings.get(name, true);
			setting.storeTermVector = true;
			setting.indexed = true;
		}
		Collection<String> payloadNames = sourceIndexReader
				.getFieldNames(IndexReader.FieldOption.STORES_PAYLOADS);
		for (String name : payloadNames) {
			FieldSetting setting = fieldSettings.get(name, true);
			setting.storePayloads = true;
		}
		Collection<String> termVecNames = sourceIndexReader
				.getFieldNames(IndexReader.FieldOption.TERMVECTOR);
		for (String name : termVecNames) {
			FieldSetting setting = fieldSettings.get(name, true);
			setting.storeTermVector = true;
		}
		Collection<String> termVecOffsetNames = sourceIndexReader
				.getFieldNames(IndexReader.FieldOption.TERMVECTOR_WITH_OFFSET);
		for (String name : termVecOffsetNames) {
			FieldSetting setting = fieldSettings.get(name, true);
			setting.storeOffsetWithTermVector = true;
		}
		Collection<String> termVecPosNames = sourceIndexReader
				.getFieldNames(IndexReader.FieldOption.TERMVECTOR_WITH_POSITION);
		for (String name : termVecPosNames) {
			FieldSetting setting = fieldSettings.get(name, true);
			setting.storePositionWithTermVector = true;
		}
		Collection<String> termVecPosOffNames = sourceIndexReader
				.getFieldNames(IndexReader.FieldOption.TERMVECTOR_WITH_POSITION_OFFSET);
		for (String name : termVecPosOffNames) {
			FieldSetting setting = fieldSettings.get(name, true);
			setting.storeOffsetWithTermVector = true;
			setting.storePositionWithTermVector = true;
		}
		Collection<String> unindexedNames = sourceIndexReader
				.getFieldNames(IndexReader.FieldOption.UNINDEXED);
		for (String name : unindexedNames) {
			FieldSetting setting = fieldSettings.get(name, true);
			setting.indexed = false;
		}

		documentsByNumber = new InstantiatedDocument[sourceIndexReader.maxDoc()];

		int[] documentRemaping = new int[sourceIndexReader.maxDoc()];

		// if (sourceIndexReader.hasDeletions()) {
		// deletedDocuments = new BitVector(sourceIndexReader.maxDoc());
		// }

		// new doc counter 
		int n = 0;
		// create documents
		for (int i = 0; i < sourceIndexReader.maxDoc(); i++) {
			if (/*sourceIndexReader.hasDeletions()
					&&*/ sourceIndexReader.isDeleted(i)) {
				documentRemaping[i] = -1;
			} else {
				int docNo = n;
				documentRemaping[i] = n++;
		
				InstantiatedDocument document = new InstantiatedDocument();
				// copy stored fields from source reader
				Document sourceDocument = sourceIndexReader.document(i);
				for (Fieldable field : sourceDocument.getFields()) {
					if (fields == null || fields.contains(field.name())) {
						document.getDocument().add(field);
					}
				}
				document.setDocumentNumber(docNo);
				documentsByNumber[docNo] = document;
				for (Fieldable field : document.getDocument().getFields()) {
					if (fields == null || fields.contains(field.name())) {
						if (field.isTermVectorStored()) {
							if (document.getVectorSpace() == null) {
								document.setVectorSpace(new HashMap<String, List<InstantiatedTermDocumentInformation>>());
							}
							document.getVectorSpace()
									.put(field.name(),
											new ArrayList<InstantiatedTermDocumentInformation>());
						}
					}
				}
			}
		}
		
		if (n != documentsByNumber.length) {
			documentsByNumber = Arrays.copyOf(documentsByNumber, n);
		}
		int[] reverseDocumentRemaping = new int[n];

		for(int i = 0; i != documentRemaping.length; ++i) {
			if (documentRemaping[i] != -1) {
				reverseDocumentRemaping[documentRemaping[i]] = i;
			}
		}
		
		// create norms
		for (String fieldName : allFieldNames) {
			if (fields == null || fields.contains(fieldName)) {
				getNormsByFieldNameAndDocumentNumber().put(fieldName,
						remap(reverseDocumentRemaping.length, documentRemaping, sourceIndexReader.norms(fieldName)));
			}
		}

		// create terms
		for (String fieldName : allFieldNames) {
			if (fields == null || fields.contains(fieldName)) {
				getTermsByFieldAndText().put(fieldName,
						new HashMap<String, InstantiatedTerm>(5000));
			}
		}
		List<InstantiatedTerm> terms = new ArrayList<InstantiatedTerm>(
				5000 * getTermsByFieldAndText().size());

		TermEnum termEnum = sourceIndexReader.terms();
		while (termEnum.next()) {
			if (fields == null || fields.contains(termEnum.term().field())) { // todo
																				// skipto
																				// if
																				// not
																				// using
																				// field
				InstantiatedTerm instantiatedTerm = new InstantiatedTerm(
						termEnum.term().field(), termEnum.term().text());
	
// will be set later
//				instantiatedTerm.setTermIndex(terms.size());
				instantiatedTerm
						.setAssociatedDocuments(new InstantiatedTermDocumentInformation[termEnum
								.docFreq()]);

				// adding term to data structures;
				terms.add(instantiatedTerm);
				getTermsByFieldAndText().get(termEnum.term().field()).put(
						termEnum.term().text(), instantiatedTerm);
			}
		}
		termEnum.close();

		// create term-document informations
		for (InstantiatedTerm term : terms) {
			TermPositions termPositions = sourceIndexReader.termPositions(term.getTerm());
			int position = 0;
			while (termPositions.next()) {
				int docNo = documentRemaping[termPositions.doc()];
				if (docNo == -1) {
					// document is removed
					continue;
				}
				else {
				
					InstantiatedDocument document = documentsByNumber[docNo];
	
					byte[][] payloads = new byte[termPositions.freq()][];
					int[] positions = new int[termPositions.freq()];
					for (int i = 0; i < termPositions.freq(); i++) {
						positions[i] = termPositions.nextPosition();
	
						if (termPositions.isPayloadAvailable()) {
							payloads[i] = new byte[termPositions.getPayloadLength()];
							termPositions.getPayload(payloads[i], 0);
						}
					}
	
					InstantiatedTermDocumentInformation termDocumentInformation = new InstantiatedTermDocumentInformation(
							term, document, positions, payloads);
					term.getAssociatedDocuments()[position++] = termDocumentInformation;
	
					if (document.getVectorSpace() != null
							&& document.getVectorSpace().containsKey(term.field())) {
						document.getVectorSpace().get(term.field())
								.add(termDocumentInformation);
					}
				}
			}
		}
		
		// stripping down unused terms
		int termUsed = 0;
		for(InstantiatedTerm term: terms) {
			InstantiatedTermDocumentInformation[] docs = term.getAssociatedDocuments();
			docs = compact(docs);
			term.setAssociatedDocuments(docs);
			if (docs != null) {
				++termUsed;
			}
		}

		orderedTerms = new InstantiatedTerm[termUsed];
		int m = 0;
		for(InstantiatedTerm term: terms) {
			InstantiatedTermDocumentInformation[] docs = term.getAssociatedDocuments();
			if (docs != null) {
				term.setTermIndex(m);
				orderedTerms[m++] = term;
			}
			else {
				InstantiatedTerm rt = termsByFieldAndText.get(term.getTerm().field()).remove(term.getTerm().text());
				if (term != rt) {
					new String();
				}
			}
		}
		terms.clear(); 
		
		// load offsets to term-document informations
		for (InstantiatedDocument document : getDocumentsByNumber()) {
			if (document == null) {
				continue; // deleted
			}
			for (Fieldable field : document.getDocument().getFields()) {
				if (field.isTermVectorStored()
						&& field.isStoreOffsetWithTermVector()) {
					TermPositionVector termPositionVector = (TermPositionVector) sourceIndexReader
							.getTermFreqVector(reverseDocumentRemaping[document.getDocumentNumber()],
									field.name());
					if (termPositionVector != null) {
						for (int i = 0; i < termPositionVector.getTerms().length; i++) {
							String token = termPositionVector.getTerms()[i];
							InstantiatedTerm term = findTerm(field.name(),
									token);
							InstantiatedTermDocumentInformation termDocumentInformation = term
									.getAssociatedDocument(document
											.getDocumentNumber());
							termDocumentInformation
									.setTermOffsets(termPositionVector
											.getOffsets(i));
						}
					}
				}
			}
		}
	}

	private InstantiatedTermDocumentInformation[] compact(InstantiatedTermDocumentInformation[] docs) {
		int count = 0;
		for(InstantiatedTermDocumentInformation refDoc: docs) {
			if (refDoc != null) {
				++count;
			}
		}
		if (count == 0) {
			return null;
		}
		InstantiatedTermDocumentInformation[] newDocs = new InstantiatedTermDocumentInformation[count];
		int n = 0;
		for(InstantiatedTermDocumentInformation refDoc: docs) {
			if (refDoc != null) {
				newDocs[n++] = refDoc;
			}
		}
		return newDocs;
	}

	private byte[] remap(int length, int[] documentRemaping, byte[] norms) {
		byte[] result = new byte[length];
		for(int i = 0; i != norms.length; ++i) {
			if (documentRemaping[i] != -1) {
				result[documentRemaping[i]] = norms[i];
			}
		}
		return result;
	}

	public InstantiatedIndexWriter indexWriterFactory(Analyzer analyzer,
			boolean create) {
		return new InstantiatedIndexWriter(this, analyzer, create);
	}

	public InstantiatedIndexReader indexReaderFactory() {
		return new InstantiatedIndexReader(this);
	}

	public void close() throws IOException {
		// todo: decouple everything
	}

	InstantiatedTerm findTerm(Term term) {
		return findTerm(term.field(), term.text());
	}

	InstantiatedTerm findTerm(String field, String text) {
		Map<String, InstantiatedTerm> termsByField = termsByFieldAndText
				.get(field);
		if (termsByField == null) {
			return null;
		} else {
			return termsByField.get(text);
		}
	}

	public Map<String, Map<String, InstantiatedTerm>> getTermsByFieldAndText() {
		return termsByFieldAndText;
	}

	public InstantiatedTerm[] getOrderedTerms() {
		return orderedTerms;
	}

	public InstantiatedDocument[] getDocumentsByNumber() {
		return documentsByNumber;
	}

	public Map<String, byte[]> getNormsByFieldNameAndDocumentNumber() {
		return normsByFieldNameAndDocumentNumber;
	}

	void setNormsByFieldNameAndDocumentNumber(
			Map<String, byte[]> normsByFieldNameAndDocumentNumber) {
		this.normsByFieldNameAndDocumentNumber = normsByFieldNameAndDocumentNumber;
	}

	public BitVector getDeletedDocuments() {
		return deletedDocuments;
	}

	void setDeletedDocuments(BitVector deletedDocuments) {
		this.deletedDocuments = deletedDocuments;
	}

	void setOrderedTerms(InstantiatedTerm[] orderedTerms) {
		this.orderedTerms = orderedTerms;
	}

	void setDocumentsByNumber(InstantiatedDocument[] documentsByNumber) {
		this.documentsByNumber = documentsByNumber;
	}

	public long getVersion() {
		return version;
	}

	void setVersion(long version) {
		this.version = version;
	}

	FieldSettings getFieldSettings() {
		return fieldSettings;
	}
}