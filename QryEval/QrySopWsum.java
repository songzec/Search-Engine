import java.io.IOException;
import java.util.ArrayList;
/**
 * default operator for BM25.
 * @author Songze Chen
 */
public class QrySopWsum extends QrySop {
	public boolean weightExpected = true;
	ArrayList<Double> weights = new ArrayList<Double>();
	public double weightSum = 0;
	@Override
	/**
	 * @return sum of every matched argument.
	 */
	public double getScore(RetrievalModel r) throws IOException {
		if (r instanceof RetrievalModelIndri) {
			double score = 0;
			try {
				int i = 0;
				for (Qry q_i : this.args){
					if(q_i.docIteratorHasMatchCache()
							&& q_i.docIteratorGetMatch() == this.docIteratorGetMatch()){
						score += weights.get(i) / weightSum * ((QrySop) q_i).getScore(r);
					} else {
						score += weights.get(i) / weightSum * ((QrySop)q_i).getDefaultScore(r, this.docIteratorGetMatch());
					}
					i++;
				}
			} catch (IOException e) {
				e.printStackTrace();
			}
			return score;
		}
		throw new IllegalArgumentException
    		(r.getClass().getName() + " doesn't support the WSUM operator.");
	}

	@Override
	public boolean docIteratorHasMatch(RetrievalModel r) {
		if(r instanceof RetrievalModelIndri) {
			return this.docIteratorHasMatchMin (r);
		} else {
			throw new IllegalArgumentException
	        	(r.getClass().getName() + " doesn't support the WSUM operator.");
		}
	}

	@Override
	public double getDefaultScore(RetrievalModel r, int docid) {
		double score = 0;
		if( r instanceof RetrievalModelIndri){
			int i = 0;
			for (Qry q_i : args){
				score += (this.weights.get(i) / weightSum) * ((QrySop)q_i).getDefaultScore(r, docid);
				i++;
			}
		}
		return score;
	}


}
