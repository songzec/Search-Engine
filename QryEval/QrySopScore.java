/**
 *  Copyright (c) 2016, Carnegie Mellon University.  All Rights Reserved.
 */

import java.io.*;
import java.lang.IllegalArgumentException;

/**
 *  The SCORE operator for all retrieval models.
 */
public class QrySopScore extends QrySop {

  /**
   *  Document-independent values that should be determined just once.
   *  Some retrieval models have these, some don't.
   */
  
  /**
   *  Indicates whether the query has a match.
   *  @param r The retrieval model that determines what is a match
   *  @return True if the query matches, otherwise false.
   */
  public boolean docIteratorHasMatch (RetrievalModel r) {
	  return this.docIteratorHasMatchFirst (r);
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
    } else if(r instanceof RetrievalModelBM25){
        return this.getScoreBM25(r);
    } else if(r instanceof RetrievalModelIndri){
        return this.getScoreIndri(r);
    } else {
      throw new IllegalArgumentException
        (r.getClass().getName() + " doesn't support the SCORE operator.");
    }
  }
  	/**
  	 * Score calculation for Indri model.
  	 * @param r Indri model
  	 * @return calculated score for Indri model.
  	 */
	private double getScoreIndri(RetrievalModel r) {
		  double mu = RetrievalModelIndri.mu;
		  double lambda = RetrievalModelIndri.lambda;
		  try {
			  String field = ((QryIop)this.args.get(0)).getField();
			  double tf = ((QryIop)this.args.get(0)).getTf();
			  double lengthC = Idx.getSumOfFieldLengths(field);
			  double lengthD = Idx.getFieldLength(field, this.args.get(0).docIteratorGetMatch());
			  double ctf =  ((QryIop)this.args.get(0)).getCtf();
			  double pMLE = ctf / lengthC;
			  double score = ( (1-lambda) * ( tf + mu*pMLE) / (lengthD + mu) ) + lambda * pMLE;
			  return score;
		  }
		  catch (IOException e){
			  e.printStackTrace();
		  }
		  
		  return 0.0;
	}
	/**
	 * Score calculation for BM25 model.
	 * @param r - BM25
	 * @return calculated score for BM25 model.
	 */
	private double getScoreBM25(RetrievalModel r) {
		if (! this.docIteratorHasMatchCache()) {
	        return 0.0;
	    } else {
	    	double k1 = RetrievalModelBM25.k1;
			double b = RetrievalModelBM25.b;
			double k3 = RetrievalModelBM25.k3;
			try {
				double N = Idx.getNumDocs();
				double docLen = Idx.getFieldLength(((QryIop)this.args.get(0)).getField(),
												this.args.get(0).docIteratorGetMatch() );
				double qtf = 1;
				double tf = ((QryIop)this.args.get(0)).getTf();
				double df = ((QryIop)this.args.get(0)).getDf();
				double avgdocLen = Idx.getSumOfFieldLengths(((QryIop)this.args.get(0)).getField())
								/ (double) Idx.getDocCount(((QryIop)this.args.get(0)).getField());
				double idf = Math.max(0, Math.log( (N-df+0.5)/(df+0.5) ));
				double tfWeight = tf / (tf + k1*((1-b)+b*docLen/avgdocLen));
				double userWeight = (k3+1) * qtf / (k3+qtf);
				double score = idf * tfWeight * userWeight;
				return score;
			} catch (IOException e) {
				e.printStackTrace();
			}
	        return 0.0;
	    }
	}

	private double getScoreRankedBoolean(RetrievalModel r) {
	    if (! this.docIteratorHasMatchCache()) {
	        return 0.0;
	      } else {
	        return ((QryIop)this.args.get(0)).getTf();
	      }
	}

	/**
	 *  getScore for the Unranked retrieval model.
	 *  @param r The retrieval model that determines how scores are calculated.
	 *  @return The document score.
	 *  @throws IOException Error accessing the Lucene index
	 */
	public double getScoreUnrankedBoolean (RetrievalModel r) throws IOException {
		if (!this.docIteratorHasMatchCache()) {
			return 0.0;
		} else {
			return 1.0;
		}
	}

  /**
   *  Initialize the query operator (and its arguments), including any
   *  internal iterators.  If the query operator is of type QryIop, it
   *  is fully evaluated, and the results are stored in an internal
   *  inverted list that may be accessed via the internal iterator.
   *  @param r A retrieval model that guides initialization
   *  @throws IOException Error accessing the Lucene index.
   */
  public void initialize (RetrievalModel r) throws IOException {

    Qry q = this.args.get (0);
    q.initialize (r);
  }

	@Override
	/**
	 *  For indri, if there's no match, use default score.
	 *  The only difference between default score and non-default score
	 *  is term frequency (tf) of default score is zero.
	 */
	public double getDefaultScore(RetrievalModel r, int docid) {
		  double mu = RetrievalModelIndri.mu;
		  double lambda = RetrievalModelIndri.lambda;
		  try {
			  String field = ((QryIop)this.args.get(0)).getField();
			  double lengthC = Idx.getSumOfFieldLengths(field);
			  double lengthD = Idx.getFieldLength(field, docid);
			  double ctf =  ((QryIop)this.args.get(0)).getCtf();
			  double pMLE = ctf / lengthC;
			  double tf = 0;
			  double defaulScore = ( (1-lambda) * ( tf + mu*pMLE) / (lengthD + mu) ) + lambda * pMLE;
			  return defaulScore;
		  }
		  catch (IOException e){
			  e.printStackTrace();
		  }
		  
		  return 0.0;
	}

}
