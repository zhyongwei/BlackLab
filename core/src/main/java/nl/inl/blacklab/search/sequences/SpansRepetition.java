/*******************************************************************************
 * Copyright (c) 2010, 2012 Institute for Dutch Lexicology
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
 *******************************************************************************/
package nl.inl.blacklab.search.sequences;

import java.io.IOException;

import org.apache.lucene.search.spans.Spans;

import nl.inl.blacklab.search.Span;
import nl.inl.blacklab.search.lucene.BLSpans;
import nl.inl.blacklab.search.lucene.BLSpansWrapper;
import nl.inl.blacklab.search.lucene.HitQueryContext;

/**
 * Finds all sequences of consecutive hits from the source spans of the specified min and max
 * lengths. Used to implement repetition operators.
 *
 * This generates all possible sequences of consecutive hits, so if we search
 * for B+ in the input string ABBBA, we'll get 3 hits of length 1, 2 hits of length 2,
 * and 1 hit of length 3. In the future, this should be made configurable (to specifically
 * support greedy matching, etc.)
 */
class SpansRepetition extends BLSpans {
	private SpansInBuckets source;

	/**
	 * If -1: not started yet. If NO_MORE_DOCS: done. Otherwise: doc id.
	 */
	private int currentDoc = -1;

	boolean moreBuckets = false;

	private int min;

	private int max;

	private int firstToken;

	private int tokenLength;

	private BLSpans spansSource;

	private boolean alreadyAtFirstMatch = false;

	public SpansRepetition(Spans source, int min, int max) {
		// Find all consecutive matches in this Spans
		spansSource = BLSpansWrapper.optWrapSortUniq(source);
		this.source = new SpansInBucketsConsecutive(spansSource);
		this.min = min;
		this.max = max;
		if (max != -1 && min > max)
			throw new IllegalArgumentException("min > max");
		if (min < 1)
			throw new IllegalArgumentException("min < 1");
	}

	@Override
	public int docID() {
		return currentDoc;
	}

	@Override
	public int endPosition() {
		if (alreadyAtFirstMatch)
			return -1; // .nextStartPosition() not called yet
		if (!moreBuckets)
			return NO_MORE_POSITIONS;
		return source.endPosition(firstToken + tokenLength - 1);
	}

	@Override
	public int nextDoc() throws IOException {
		alreadyAtFirstMatch = false;

		if (currentDoc == NO_MORE_DOCS)
			return NO_MORE_DOCS;

		// Go to next doc (we don't know if it has a match)
		currentDoc = source.nextDoc();
		if (currentDoc != NO_MORE_DOCS) {
			// From here, find next match in this or further doc
			currentDoc = findDocWithMatchingBucket();
			if (currentDoc != NO_MORE_DOCS)
				// findDocWithMatchingBucket places us at the first match.
				alreadyAtFirstMatch = true;
		}
		return currentDoc;
	}

	/**
	 * Go to the next matching bucket, not necessarily in the current doc.
	 * @return the doc id, or NO_MORE_DOCS if there's no more buckets.
	 * @throws IOException
	 */
	private int findDocWithMatchingBucket() throws IOException {
		while (currentDoc != NO_MORE_DOCS) {

			// Another bucket in this doc?
			int startPos = nextBucket();
			moreBuckets = startPos != SpansInBuckets.NO_MORE_BUCKETS;
			if (moreBuckets) {
				// Yes, found one.
				break;
			}

			// No more matching buckets; try next doc
			currentDoc = source.nextDoc();
		}
		return currentDoc;
	}

	/**
	 * Go to the next matching bucket in the current doc, if it has any.
	 *
	 * @return the start position of the bucket, or NO_MORE_BUCKETS if there's no more matching buckets
	 * @throws IOException
	 */
	private int nextBucket() throws IOException {
		moreBuckets = source.nextBucket() != SpansInBuckets.NO_MORE_BUCKETS;
		while (moreBuckets) {
			if (source.bucketSize() >= min) {
				// This stretch is large enough to get a repetition hit;
				// Position us at the first hit and remember we're already there.
				firstToken = 0;
				tokenLength = min;
				currentDoc = source.docID();
				return source.startPosition(firstToken);
			}
			// Not large enough; try next bucket
			moreBuckets = source.nextBucket() != SpansInBuckets.NO_MORE_BUCKETS;
		}
		return SpansInBuckets.NO_MORE_BUCKETS;
	}

	/**
	 * Go to the next match.
	 *
	 * @return true if we're on a valid match, false if we're done.
	 * @throws IOException
	 */
	@Override
	public int nextStartPosition() throws IOException {
		if (currentDoc == NO_MORE_DOCS || !moreBuckets)
			return NO_MORE_POSITIONS;

		if (alreadyAtFirstMatch) {
			// We're already at the first match in the document, because
			// we needed to check if there were matches at all. Return it now.
			alreadyAtFirstMatch = false;
			return source.startPosition(firstToken);
		}

		// Go to the next hit length for this start point in the current bucket.
		tokenLength++;

		// Find the first valid hit in the bucket
		if ((max != -1 && tokenLength > max) || firstToken + tokenLength > source.bucketSize()) {
			// On to the next start point.
			firstToken++;
			tokenLength = min;
		}

		if (firstToken + tokenLength <= source.bucketSize()) {
			// Still a valid rep. hit.
			return source.startPosition(firstToken);
		}

		// No valid hits left; on to the next matching bucket
		int startPos = nextBucket();
		moreBuckets = startPos != SpansInBuckets.NO_MORE_BUCKETS;
		if (moreBuckets) {
			return startPos;
		}

		// No more matching buckets.
		return NO_MORE_POSITIONS;
	}

	/**
	 * Go to the specified document, if it has hits. If not, go to the next document containing
	 * hits.
	 *
	 * @param doc
	 *            the document number to skip to / over
	 * @return true if we're at a valid hit, false if not
	 * @throws IOException
	 */
	@Override
	public int advance(int doc) throws IOException {
		alreadyAtFirstMatch = false;

		if (currentDoc != NO_MORE_DOCS) {
			// Go to first doc at or after target that has a match.
			currentDoc = source.advance(doc);
			if (currentDoc != NO_MORE_DOCS) {
				// From here, find next match in this or further doc
				currentDoc = findDocWithMatchingBucket();
				if (currentDoc != NO_MORE_DOCS)
					alreadyAtFirstMatch = true;
			}
		}
		return currentDoc;
	}

	/**
	 * @return start of the current hit
	 */
	@Override
	public int startPosition() {
		if (alreadyAtFirstMatch)
			return -1; // .nextStartPosition() not called yet
		if (!moreBuckets)
			return NO_MORE_POSITIONS;
		return source.startPosition(firstToken);
	}

	@Override
	public String toString() {
		return "SpansRepetition(" + source + ", " + min + ", " + max + ")";
	}

	@Override
	public boolean hitsEndPointSorted() {
		return spansSource.hitsEndPointSorted() && min == max;
	}

	@Override
	public boolean hitsStartPointSorted() {
		return true;
	}

	@Override
	public boolean hitsAllSameLength() {
		return spansSource.hitsAllSameLength() && min == max;
	}

	@Override
	public int hitsLength() {
		return hitsAllSameLength() ? spansSource.hitsLength() * min : -1;
	}

	@Override
	public boolean hitsHaveUniqueStart() {
		return spansSource.hitsHaveUniqueStart() && min == max;
	}

	@Override
	public boolean hitsHaveUniqueEnd() {
		return spansSource.hitsHaveUniqueEnd() && min == max;
	}

	@Override
	public boolean hitsAreUnique() {
		return true;
	}

	@Override
	public void passHitQueryContextToClauses(HitQueryContext context) {
		source.setHitQueryContext(context);
	}

	@Override
	public void getCapturedGroups(Span[] capturedGroups) {
		int index = firstToken + tokenLength - 1; // use the last match for captured groups
		source.getCapturedGroups(index, capturedGroups);
	}


}
