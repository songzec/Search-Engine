import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Vector;

public class QryIopWindow extends QryIop {
	public int distance;
	public QryIopWindow(int distance) {
		this.distance = distance;
	}

	@Override
	protected void evaluate() throws IOException {
		//  Create an empty inverted list.  If there are no query arguments,
		// that's the final result.
		
		this.invertedList = new InvList (this.getField());
		
		if (args.size() == 0) {
			return;
		}
		
		// Each pass of the loop adds 1 document to result inverted list
	    //  until all of the argument inverted lists are depleted.

		while (true) {
			
			// Find the minimum next document id.  If there is none, we're done.
			
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
			
			if (minDocid == Qry.INVALID_DOCID) {
				break;               // All docids have been processed.  Done.
			}
			
			ArrayList<Vector<Integer>> locations = 
					new ArrayList<Vector<Integer>>();
			
			for (int i = 0; i < this.args.size(); i++) {
		    	Qry q_i = this.args.get(i);
				if (q_i.docIteratorHasMatch (null)
						&& (q_i.docIteratorGetMatch () == minDocid)) {
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

	private List<Integer> retrieveNearPositions(ArrayList<Vector<Integer>> locations, int distance) {
		
		int numOfTerms = locations.size();
		int[] currentIndices = new int[numOfTerms];
		List<Integer> positions = new ArrayList<Integer>();
		
		boolean outOfBound = false;
		while (!outOfBound) {
			int[] currentPos = new int[numOfTerms];
			int minPosTermIndex = -1;
			int minPos = Integer.MAX_VALUE;
			for (int i = 0; i < numOfTerms; i++) {
				currentPos[i] = locations.get(i).get(currentIndices[i]);
				if (currentPos[i] < minPos) {
					minPos = currentPos[i];
					minPosTermIndex = i;
				}
			}
			int maxPos = Integer.MIN_VALUE;
			minPos = Integer.MAX_VALUE;
			for (int position : currentPos) {
				if (position < minPos) {
					minPos = position;
				}
				if (position > maxPos) {
					maxPos = position;
				}
			}
			if (maxPos - minPos < distance) { // match the window operator
				positions.add(maxPos);
				// index moving forward
				for (int i = 0; i < numOfTerms; i++) {
					currentIndices[i]++;
					if (currentIndices[i] == locations.get(i).size()) {
						outOfBound = true;
						break;
					}
				}
			} else {
				currentIndices[minPosTermIndex]++;
				if (currentIndices[minPosTermIndex] == locations.get(minPosTermIndex).size()) {
					outOfBound = true;
				}
			}
		}
		return positions;
	}
}
