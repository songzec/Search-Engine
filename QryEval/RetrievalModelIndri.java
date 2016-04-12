
public class RetrievalModelIndri extends RetrievalModel {
	public static double mu = 2500;
	public static double lambda = 0.4;
	
	public RetrievalModelIndri(String mu, String lambda) {
		RetrievalModelIndri.mu = Double.parseDouble(mu);
		RetrievalModelIndri.lambda = Double.parseDouble(lambda);
	}
	public RetrievalModelIndri() {
		RetrievalModelIndri.mu = 2500;
		RetrievalModelIndri.lambda = 0.4;
	}
	@Override
	public String defaultQrySopName() {
		return new String ("#and");
	}

}
