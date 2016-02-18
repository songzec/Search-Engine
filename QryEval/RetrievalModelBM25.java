
public class RetrievalModelBM25 extends RetrievalModel {

	public static double k1;
	public static double b;
	public static double k3;
	
	public RetrievalModelBM25(String k1, String b, String k3) {
		RetrievalModelBM25.k1 = Double.parseDouble(k1);
		RetrievalModelBM25.b = Double.parseDouble(b);
		RetrievalModelBM25.k3 = Double.parseDouble(k3);
	}
	public RetrievalModelBM25() {
		RetrievalModelBM25.k1 = 1.2;
		RetrievalModelBM25.b = 0.75;
		RetrievalModelBM25.k3 = 0;
	}
	@Override
	public String defaultQrySopName() {
		return new String ("#sum");
	}

}
