import java.io.IOException;

public class QrySopWand extends QryWSop {
	@Override
	/**
	 * @return weighted sum of every matched argument.
	 */
	public double getScore(RetrievalModel r) throws IOException {
		if (r instanceof RetrievalModelIndri) {
			double score = 1.0;
			try {
				int i = 0;
				for (Qry q_i : this.args){
					if(q_i.docIteratorHasMatchCache()
							&& q_i.docIteratorGetMatch() == this.docIteratorGetMatch()){
						score *= Math.pow(((QrySop) q_i).getScore(r),
								weights.get(i) / weightSum);
					} else {
						score *= Math.pow(((QrySop)q_i).getDefaultScore(r, this.docIteratorGetMatch()),
								weights.get(i) / weightSum);
					}
					i++;
				}
			} catch (IOException e) {
				e.printStackTrace();
				
			}
			return score;
		}
		throw new IllegalArgumentException
			(r.getClass().getName() + " doesn't support the WAND operator.");
	}

	@Override
	public boolean docIteratorHasMatch(RetrievalModel r) {
		if(r instanceof RetrievalModelIndri) {
			return this.docIteratorHasMatchMin (r);
		} else {
			throw new IllegalArgumentException
	        	(r.getClass().getName() + " doesn't support the WAND operator.");
		}
	}

	@Override
	/**
	 * For indri, if there's no match, use default score.
	 */
	public double getDefaultScore(RetrievalModel r, int docid) {
		double score = 1.0;
		if( r instanceof RetrievalModelIndri){
			int i = 0;
			for (Qry q_i : args){
				score *= Math.pow(((QrySop)q_i).getDefaultScore(r, docid), weights.get(i) / weightSum);
				i++;
			}
		}
		return score;
	}


}
