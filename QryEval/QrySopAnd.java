/**
 *  Copyright (c) 2016, Carnegie Mellon University.  All Rights Reserved.
 */

import java.io.*;

/**
 *  The AND operator for all retrieval models.
 */
public class QrySopAnd extends QrySop {

  /**
   *  Indicates whether the query has all matches.
   *  @param r The retrieval model that determines what is a match
   *  @return True if the query matches, otherwise false.
   */
  public boolean docIteratorHasMatch (RetrievalModel r) {
	  if ( r instanceof RetrievalModelIndri){
			return this.docIteratorHasMatchMin(r);
		}
	  return this.docIteratorHasMatchAll (r);
  }

  /**
   *  Get a score for the document that docIteratorHasMatch matched.
   *  @param r The retrieval model that determines how scores are calculated.
   *  @return The document score.
   *  @throws IOException Error accessing the Lucene index
   */
  public double getScore (RetrievalModel r) throws IOException {

    if (r instanceof RetrievalModelUnrankedBoolean) {
      return this.getScoreUnrankedBoolean (r);
    } else if (r instanceof RetrievalModelRankedBoolean) {
    	return this.getScoreRankedBoolean (r);
    } else if (r instanceof RetrievalModelIndri) {
    	return this.getScoreIndri (r);
    } else {
      throw new IllegalArgumentException
        (r.getClass().getName() + " doesn't support the And operator.");
    }
  }
  /**
   * Calculate score for Indri, if there's no match, use default score.
   * @param r - Indri model.
   * @return calculated score (multiplication of every argument's score).
   */
  private double getScoreIndri(RetrievalModel r) {
	  double score = 1.0;
	  try {
		  for (Qry q_i : this.args){
			  if(q_i.docIteratorHasMatchCache()
					  && q_i.docIteratorGetMatch() == this.docIteratorGetMatch()){
				  score *=  Math.pow(((QrySop) q_i).getScore(r),
						  				1.0 / (double)this.args.size());
			  } else {
				  score *= Math.pow(((QrySop)q_i).getDefaultScore(r, this.docIteratorGetMatch()),
						  				1.0 / (double)this.args.size());
			  }
		  }
	  } catch (IOException e) {
		  e.printStackTrace();
	  }
	  return score;
  }

  /**
   *  getScore for the RankedBoolean retrieval model.
   *  @param r The retrieval model that determines how scores are calculated.
   *  @return The document score.
   *  @throws IOException Error accessing the Lucene index
   */
  private double getScoreRankedBoolean(RetrievalModel r) throws IOException {
	// TODO Auto-generated method stub
    if (! this.docIteratorHasMatchCache()) {
        return 0.0;
      } else {
        return this.calculateScore(r);
      }
    }
  
  /**
   * For RankedBoolean, use minimum score.
   * @param r RetrievalModel - RankedBoolean.
   * @return minimum score of all arguments.
   * @throws IOException
   */
  private double calculateScore(RetrievalModel r) throws IOException {
	  double score = Double.MAX_VALUE;
	  for (Qry q_i : args) {
		  if (q_i.docIteratorHasMatchCache()
				  && q_i.docIteratorGetMatch() == this.docIteratorGetMatch()) {
			  score = Math.min(((QrySop)q_i).getScore(r), score);
		  }
	  }
	  return score;
  }

  /**
   *  getScore for the UnrankedBoolean retrieval model.
   *  @param r The retrieval model that determines how scores are calculated.
   *  @return The document score.
   *  @throws IOException Error accessing the Lucene index
   */
  private double getScoreUnrankedBoolean (RetrievalModel r) throws IOException {
    if (! this.docIteratorHasMatchCache()) {
      return 0.0;
    } else {
      return 1.0;
    }
  }

	@Override
	/**
	 * For indri, if there's no match, use default score.
	 */
	public double getDefaultScore(RetrievalModel r, int docid) {
		double score = 1.0;
		if( r instanceof RetrievalModelIndri){
			for (Qry q_i : args){
				score *= Math.pow(((QrySop)q_i).getDefaultScore(r, docid), 1.0 / this.args.size());
			}
		}
		return score;
	}

}
