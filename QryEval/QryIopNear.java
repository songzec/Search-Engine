/**
 *  Copyright (c) 2016, Carnegie Mellon University.  All Rights Reserved.
 */
import java.io.*;
import java.util.*;

/**
 *  The NEAR operator for all retrieval models.
 */
public class QryIopNear extends QryIop {
	private int distance;
	QryIopNear(int distance) {
		this.distance = distance;
	}
  /**
   *  Evaluate the query operator; the result is an internal inverted
   *  list that may be accessed via the internal iterators.
   *  @throws IOException Error accessing the Lucene index.
   */
  protected void evaluate () throws IOException {

    //  Create an empty inverted list.  If there are no query arguments,
    //  that's the final result.
    
    this.invertedList = new InvList (this.getField());

    if (args.size () == 0) {
      return;
    }

    //  Each pass of the loop adds 1 document to result inverted list
    //  until all of the argument inverted lists are depleted.

    while (true) {
    	
      //  Find the minimum next document id.  If there is none, we're done.

      int minDocid = Qry.INVALID_DOCID;
      for (Qry q_i: this.args) {
          if (q_i.docIteratorHasMatch (null)) {
            int q_iDocid = q_i.docIteratorGetMatch ();
            
            if ((minDocid > q_iDocid) ||
                (minDocid == Qry.INVALID_DOCID)) {
              minDocid = q_iDocid;
            }
          }
        }

      if (minDocid == Qry.INVALID_DOCID)
    	  break;				// All docids have been processed.  Done.
      

      ArrayList<Vector<Integer>> locations = 
    		  					new ArrayList<Vector<Integer>>();
      
      for (int i = 0; i < this.args.size(); i++) {
    	Qry q_i = this.args.get(i);
		if (q_i.docIteratorHasMatch (null) &&
		    (q_i.docIteratorGetMatch () == minDocid)) {
			Vector<Integer> locations_i =
				((QryIop) q_i).docIteratorGetMatchPosting().positions;
			locations.add (locations_i);
		  	q_i.docIteratorAdvancePast (minDocid);
		}
      }
      // if every argument has the same docID,
      // then call retrieveNearPositions()
      if (this.args.size() == locations.size()) {
    	  List<Integer> positions = new ArrayList<Integer>();
    	  positions = retrieveNearPositions(locations, distance);
    	  if (!positions.isEmpty()) {
    		  this.invertedList.appendPosting (minDocid, positions);
    	  }
      }
    }
  }
  	// retrieve positions of the first word by calling retrieveNearPositions()
	private List<Integer> retrieveNearPositions
								(ArrayList<Vector<Integer>> locations, int distance) {

		int[] currentIndices = new int[locations.size()];
		List<Integer> positions = new ArrayList<Integer>();

		for (int i = 0; i < locations.get(0).size(); i++) {
			int temp = retrieveNearPositions(locations, currentIndices, 1, distance);
			if (temp == -1) {
				for (int j = 0; j < currentIndices.length; j++) {
					currentIndices[j]++;
				}
				positions.add(locations.get(0).get(i));
			} else if (temp == -2) {
				break;
			} else {
				currentIndices[temp]++;
			}

		}

		return positions;
	}
	
	
	// recursion version:
	// "currentIndices" stores currentIndices of positions of every term.
	// termNum stores the current term we are looking at.
	// return values:
	// -1 :success
	// -2: end of search
	// others: index++
	private int retrieveNearPositions
				(ArrayList<Vector<Integer>> locations, int[] currentIndices, int termNum, int distance) {
		// out of bound
		if (termNum == locations.size()) {
			return -1;
		} else if (currentIndices[termNum] >= locations.get(termNum).size()) {
			return -2;
		}
		// if the current term's position is less than the previous one
		while (currentIndices[termNum] < locations.get(termNum).size()
				&& locations.get(termNum).get(currentIndices[termNum]) <
				locations.get(termNum-1).get(currentIndices[termNum-1])) {
				currentIndices[termNum]++;
		}
		if (currentIndices[termNum] == locations.get(termNum).size()) {
			return -2;
		}
		// too far
		if (locations.get(termNum).get(currentIndices[termNum]) - 
				locations.get(termNum-1).get(currentIndices[termNum-1]) > distance) {
			return termNum - 1;
		}
		// if close enough
		if(locations.get(termNum).get(currentIndices[termNum]) - 
				locations.get(termNum-1).get(currentIndices[termNum-1]) > 0) {
			if (termNum == locations.size() - 1) {
				return -1;
			}
			return retrieveNearPositions(locations, currentIndices, termNum+1, distance);
		}
		return retrieveNearPositions(locations, currentIndices, termNum+1, distance);

	}

}