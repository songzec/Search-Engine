import java.io.IOException;
/**
 * default operator for BM25.
 * @author Songze Chen
 */
public class QrySopSum extends QrySop {

	@Override
	/**
	 * @return sum of every matched argument.
	 */
	public double getScore(RetrievalModel r) throws IOException {
		if (r instanceof RetrievalModelBM25) {
			double sum = 0;
			for(Qry q_i : args){
				if (q_i.docIteratorHasMatchCache()
						&& q_i.docIteratorGetMatch() == this.docIteratorGetMatch()){
					sum += ((QrySop)q_i).getScore(r);
				}
			}
			return sum;
		}
		return 0;
	}

	@Override
	public boolean docIteratorHasMatch(RetrievalModel r) {
		return this.docIteratorHasMatchMin (r);
	}

	@Override
	public double getDefaultScore(RetrievalModel r, int docid) {
		// TODO Auto-generated method stub
		return 0;
	}


}
