import java.util.ArrayList;

/**
 * Weighted QrySop
 * @author Songze Chen
 *
 */
public abstract class QryWSop extends QrySop {
	public boolean weightExpected = true;
	ArrayList<Double> weights = new ArrayList<Double>();
	public double weightSum = 0;
}
