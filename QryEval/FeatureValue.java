import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Scanner;

import org.apache.lucene.index.Term;

/**
 * CMU 11-642 Search Engine HW5, SVM method.
 * @author Songze Chen
 *
 */
public class FeatureValue {
	public static final int numOfFeatureVectors = 18;
	public static ArrayList<Integer> featureDisable;
	public int relValue;
	public String qid;
	public static HashMap<Integer, Double> pageRank;
	public double[] featureVector;
	public String externalDocid;
	public FeatureValue(int relValue, String qid, double[] featureVector, String externalDocid) {
		this.relValue = relValue;
		this.qid = qid;
		this.featureVector = featureVector;
		this.externalDocid = externalDocid;
	}
	
	/**
	 * Create non-normalized feature value for one query - document pair.
	 * @param docid
	 * @param stemQuery
	 * @return
	 * @throws NumberFormatException
	 * @throws IOException
	 */
	public static double[] creatNewFeatureValue(int docid, String[] stemQuery) throws IOException {

		double[] featureVector = new double[numOfFeatureVectors];
		String rawUrl = Idx.getAttribute ("rawUrl", docid);
		//System.out.println(rawUrl);
		int fi = 1; // Spam score for document
		if (!featureDisable.contains(fi)) {
			featureVector[0] = Integer.parseInt (Idx.getAttribute("score", docid));
		}
		
		fi = 2; // count Url depth for d(number of '/' in the rawUrl field). 
		if (!featureDisable.contains(fi)) {
			for (int i = 0; i < rawUrl.length(); i++) {
				if (rawUrl.charAt(i) == '/') {
					featureVector[1]++;
				}
			}
		}
		
		fi = 3; // FromWikipedia score
		if (!featureDisable.contains(fi)) {
			featureVector[2] = rawUrl.contains("wikipedia.org") ? 1 : 0;
		}
		
		fi = 4; // PageRank score
		if (!featureDisable.contains(fi)) {
			if (pageRank.containsKey(docid)) {
				featureVector[3] = pageRank.get(docid);
			}
		}
		
		fi = 5;
		if (!featureDisable.contains(fi)) {
			featureVector[4] = BM25Score(docid, stemQuery, "body");
		}
		
		fi = 6;
		if (!featureDisable.contains(fi)) {
			featureVector[5] = IndriScore(docid, stemQuery, "body");
		}
		
		fi = 7;
		if (!featureDisable.contains(fi)) {
			featureVector[6] = termOverlapScore(docid, stemQuery, "body");
		}
		
		fi = 8;
		if (!featureDisable.contains(fi)) {
			featureVector[7] = BM25Score(docid, stemQuery, "title");
		}
		
		fi = 9;
		if (!featureDisable.contains(fi)) {
			featureVector[8] = IndriScore(docid, stemQuery, "title");
		}
		
		fi = 10;
		if (!featureDisable.contains(fi)) {
			featureVector[9] = termOverlapScore(docid, stemQuery, "title");
		}
		
		fi = 11;
		if (!featureDisable.contains(fi)) {
			featureVector[10] = BM25Score(docid, stemQuery, "url");
		}
		
		fi = 12;
		if (!featureDisable.contains(fi)) {
			featureVector[11] = IndriScore(docid, stemQuery, "url");
		}
		
		fi = 13;
		if (!featureDisable.contains(fi)) {
			featureVector[12] = termOverlapScore(docid, stemQuery, "url");
		}
		
		fi = 14;
		if (!featureDisable.contains(fi)) {
			featureVector[13] = BM25Score(docid, stemQuery, "inlink");
		}
		
		fi = 15;
		if (!featureDisable.contains(fi)) {
			featureVector[14] = IndriScore(docid, stemQuery, "inlink");
		}
		
		fi = 16;
		if (!featureDisable.contains(fi)) {
			featureVector[15] = termOverlapScore(docid, stemQuery, "inlink");
		}
		
		fi = 17;
		if (!featureDisable.contains(fi)) {
		}
		
		fi = 18;
		if (!featureDisable.contains(fi)) {
		}
		
		return featureVector;
	}
	
	/**
	 * Term overlap is defined as the percentage of query terms that match the document field.
	 * @param docid
	 * @param stemQuery
	 * @param field
	 * @return Term overlap
	 * @throws IOException
	 */
	private static double termOverlapScore(int docid, String[] stemQuery, String field) throws IOException {

		int numOfExistQuery = 0;
		TermVector termVector = new TermVector(docid, field);
		if (termVector.stemsLength() == 0) {
			return Double.NaN;
		}
		for (int i = 0; i < stemQuery.length; i++) {
			if (termVector.indexOfStem(stemQuery[i]) != -1) {
				numOfExistQuery++;
			}
		}
		return (double)numOfExistQuery / (double)stemQuery.length;
	}
	
	/**
	 * Calculate Indri score use the same formula as in QrySopScore.
	 * @param docid
	 * @param stemQuery
	 * @param field
	 * @return Indri score
	 * @throws IOException
	 */
	private static double IndriScore(int docid, String[] stemQuery, String field) throws IOException {
		
		double mu = RetrievalModelIndri.mu;
		double lambda = RetrievalModelIndri.lambda;
		TermVector termVector = new TermVector(docid, field);
		if (termVector.stemsLength() == 0) {
			return Double.NaN;
		}
		boolean hasMatch = false;
		double score = 1;
		for (int i = 0; i < stemQuery.length; i++) {
			int stemIndex = termVector.indexOfStem(stemQuery[i]);
			int tf;
			if (stemIndex == -1) {
				tf = 0;
			} else {
				hasMatch = true;
				tf = termVector.stemFreq(stemIndex);
			}
			double lengthC = Idx.INDEXREADER.getSumTotalTermFreq(field);
			double lengthD = Idx.getFieldLength(field, docid);
			long ctf = Idx.INDEXREADER.totalTermFreq(new Term(field, stemQuery[i]));
			double pMLE = ctf / lengthC;
			double docScore = ( (1 - lambda) * ( tf + mu * pMLE) / (lengthD + mu) ) + lambda * pMLE;
			score *= Math.pow(docScore, 1.0 / (double)stemQuery.length);
		}
		if (hasMatch) {
			return score;
		}
		return 0;
	}
	
	/**
	 * Calculate BM25 score use the same formula as in QrySopScore.
	 * @param docid
	 * @param stemQuery
	 * @param field
	 * @return BM25 score
	 * @throws IOException
	 */
	private static double BM25Score(int docid, String[] stemQuery, String field) throws IOException {

		double k1 = RetrievalModelBM25.k1;
		double b = RetrievalModelBM25.b;
		double k3 = RetrievalModelBM25.k3;
		TermVector termVector = new TermVector(docid, field);
		if (termVector.stemsLength() == 0) {
			return Double.NaN;
		}
		double N = Idx.getNumDocs();
		int docLen = Idx.getFieldLength(field, docid);
		double avgdocLen = Idx.getSumOfFieldLengths(field) / Idx.getDocCount(field);
		double score = 0;
		for (int i = 0; i < stemQuery.length; i++) {
			int stemIndex = termVector.indexOfStem(stemQuery[i]);
			if (stemIndex == -1) {
				continue;
			}
			int tf = termVector.stemFreq(stemIndex);
			int df = termVector.stemDf(stemIndex);
			int qtf = 1;
			double idf = Math.max(0, Math.log((N - df + 0.5) / (df + 0.5)));
			double tfWeight = tf / (tf + k1 * ((1 - b) + b * docLen / avgdocLen));
			double userWeight = (k3 + 1) * qtf / (k3 + qtf);
			score += idf * tfWeight * userWeight;
		}
		return score;
	}
	
	/**
	 * Normalize every FeatureValue's featureVector according to maxVector and minVector
	 * @param fvList
	 * @param maxVector stores the maximum values of fvList
	 * @param minVector stores the minimum values of fvList
	 */
	public static void normalizeFeatureValues(List<FeatureValue> fvList, double[] maxVector, double[] minVector) {
		for (int i = 0; i < fvList.size(); i++) {
			FeatureValue featureValue= fvList.get(i);
			for (int j = 0; j < numOfFeatureVectors; j++) {
				if (featureDisable.contains(j + 1)) {
					continue;
				}
				if ( maxVector[j] == minVector[j] || Double.isNaN(featureValue.featureVector[j])) {
					featureValue.featureVector[j] = 0;
				} else if (maxVector[j] > minVector[j]) {
					featureValue.featureVector[j] = (featureValue.featureVector[j] - minVector[j]) / (maxVector[j] - minVector[j]);
				}
			}
		}
	}
	
	/**
	 * Set minVector and maxVector so that they update the max and min value of featureVector
	 * @param minVector
	 * @param maxVector
	 * @param featureVector
	 */
	public static void updateMinAndMax(double[] minVector, double[] maxVector, double[] featureVector) {
		for (int i = 0; i < featureVector.length; i++) {
			if (featureVector[i] == Double.NaN) {
				continue;
			}
			if (maxVector[i] < featureVector[i]) {
				maxVector[i] = featureVector[i];
			}
			if (minVector[i] > featureVector[i]) {
				minVector[i] = featureVector[i];
			}
		}
	}
	
	/**
	 * This part handles feature disabled
	 * @param numoffeaturevectors
	 * @return array of double contains Double.MAX_VALUE
	 */
	public static double[] maxDouble() {
		double[] max = new double[numOfFeatureVectors];
		for (int i = 0; i < numOfFeatureVectors; i++) {
			if (!featureDisable.contains(i+1)) {
				max[i] = -Double.MAX_VALUE;
			}
		}
		return max;
	}
	/**
	 * This part handles feature disabled
	 * @param numoffeaturevectors
	 * @return array of double contains Double.MIN_VALUE
	 */
	public static double[] minDouble() {
		double[] min = new double[numOfFeatureVectors];
		for (int i = 0; i < numOfFeatureVectors; i++) {
			if (!featureDisable.contains(i+1)) {
				min[i] = Double.MAX_VALUE;
			}
		}
		return min;
	}
	/**
	 * Read pageRankFile and store it to pageRank HashMap
	 * @param pageRankFileName
	 * @throws FileNotFoundException 
	 */
	public static void initializePageRank(String pageRankFileName) throws FileNotFoundException {
		System.out.println("initial begin");
		pageRank = new HashMap<Integer, Double>();
		File file = new File(pageRankFileName);
		Scanner scanner = new Scanner(file);
		while (scanner.hasNextLine()) {
			String line = scanner.nextLine();
			String externalDocid = line.split("\t")[0];
			int docid;
			try {
				docid = Idx.getInternalDocid(externalDocid);
				double score = Double.parseDouble(line.split("\t")[1]);
				pageRank.put(docid, score);
			} catch (Exception e) {
				// simply ignore it when externalDocid is not in the collection
			}
			
		}
		scanner.close();
		System.out.println("initial end");
	}
}
