/*
 *  Copyright (c) 2016, Carnegie Mellon University.  All Rights Reserved.
 *  Version 3.1.1.
 */

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.StringReader;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.Scanner;
import java.util.Stack;
import java.util.StringTokenizer;
import java.util.TreeMap;

import org.apache.lucene.analysis.Analyzer.TokenStreamComponents;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.util.Version;

import com.sun.corba.se.spi.orbutil.fsm.Guard.Result;
import com.sun.org.apache.bcel.internal.generic.NEW;

import sun.tools.jar.resources.jar;



/**
 * QryEval is a simple application that reads queries from a file,
 * evaluates them against an index, and writes the results to an
 * output file.  This class contains the main method, a method for
 * reading parameter and query files, initialization methods, a simple
 * query parser, a simple query processor, and methods for reporting
 * results.
 * <p>
 * This software illustrates the architecture for the portion of a
 * search engine that evaluates queries.  It is a guide for class
 * homework assignments, so it emphasizes simplicity over efficiency.
 * Everything could be done more efficiently and elegantly.
 * <p>
 * The {@link Qry} hierarchy implements query evaluation using a
 * 'document at a time' (DaaT) methodology.  Initially it contains an
 * #OR operator for the unranked Boolean retrieval model and a #SYN
 * (synonym) operator for any retrieval model.  It is easily extended
 * to support additional query operators and retrieval models.  See
 * the {@link Qry} class for details.
 * <p>
 * The {@link RetrievalModel} hierarchy stores parameters and
 * information required by different retrieval models.  Retrieval
 * models that need these parameters (e.g., BM25 and Indri) use them
 * very frequently, so the RetrievalModel class emphasizes fast access.
 * <p>
 * The {@link Idx} hierarchy provides access to information in the
 * Lucene index.  It is intended to be simpler than accessing the
 * Lucene index directly.
 * <p>
 * As the search engine becomes more complex, it becomes useful to
 * have a standard approach to representing documents and scores.
 * The {@link ScoreList} class provides this capability.
 */
public class QryEval {

	//  --------------- Constants and variables ---------------------

	private static final String USAGE = "Usage:  java QryEval paramFile\n\n";

	private static final EnglishAnalyzerConfigurable ANALYZER = new EnglishAnalyzerConfigurable(Version.LUCENE_43);
	private static final String[] TEXT_FIELDS = { "body", "title", "url", "inlink", "keywords" };
	
	// fields for query expansion 
	private static boolean fb = false;
	private static String fbInitialRankingFile;
	private static String fbExpansionQueryFile;
	private static int fbDocs;
	private static int fbTerms;
	private static double fbMu = 0;
	private static double fbOrigWeight;
	private static Map<String, ScoreList> initialRankingList;
	private static String expandedQuery;
	
	// fields for LetoR
	private static List<HashMap<String, Double>> features = new ArrayList<HashMap<String, Double>>();
	private static boolean useLeToR = false;
	private static String trainingQueryFileName;
	private static String trainingQrelsFileName;
	private static String trainingFeatureVectorsFileName;
	private static String svmRankLearnPath;
	private static String svmRankClassifyPath;
	private static double svmRankParamC = 0.001;
	private static String svmRankModelFileName;
	private static String testingFeatureVectorsFileName;
	private static String testingDocumentScoresFileName;
	private static String queryFilePath;
	private static String BM25_k1;
	private static String BM25_b;
	private static String BM25_k3;
	private static String Indri_mu;
	private static String Indri_lambda;
	private static ArrayList<ScoreList> testResults = new ArrayList<ScoreList>();
	private static final int numOfFeatureVectors = 18;
	private static String trecEvalOutputPath;
	private static class TestScoreDocPair {
		String qid;
		String externalDocid;
		double score;
		public TestScoreDocPair(String qid, String externalDocid, double score) {
			this.qid = qid;
			this.externalDocid = externalDocid;
			this.score = score;
		}
	}
	//  --------------- Methods ---------------------------------------

	/**
	 * @param args The only argument is the parameter file name.
	 * @throws Exception Error accessing the Lucene index.
	 */
	public static void main(String[] args) throws Exception {
		
		//  This is a timer that you may find useful.  It is used here to
		//  time how long the entire program takes, but you can move it
		//  around to time specific parts of your code.
    
		Timer timer = new Timer();
		timer.start ();

		//  Check that a parameter file is included, and that the required
		//  parameters are present.  Just store the parameters.  They get
		//  processed later during initialization of different system
		//  components.

		if (args.length < 1) {
			throw new IllegalArgumentException (USAGE);
		}

		Map<String, String> parameters = readParameterFile (args[0]);

		//  Configure query lexical processing to match index lexical
		//  processing.  Initialize the index and retrieval model.

		ANALYZER.setLowercase(true);
		ANALYZER.setStopwordRemoval(true);
		ANALYZER.setStemmer(EnglishAnalyzerConfigurable.StemmerType.KSTEM);

		Idx.initialize (parameters.get ("indexPath"));
		
		
		RetrievalModel model = initializeRetrievalModel (parameters);
		if (useLeToR) {
			System.out.println("generating Training Data...");
			generateTrainingData();
			
			System.out.println("Training Data...");
			doTraining();
			
			System.out.println("generating Testing Data...");
			generateTestingData();
			
			System.out.println("Re-ranking Test Data");
			reRankingTestData();
			
		} else {
			//  Perform experiments.
			if (fb && fbInitialRankingFile != null) {
				readInitialRankingFile(fbInitialRankingFile);
			}
			processQueryFile(parameters, model);
		}
		//  Clean up.
    
		timer.stop ();
		System.out.println ("Time:  " + timer);
	}

	private static void reRankingTestData() throws Exception {
		// TODO Auto-generated method stub
		Process process = Runtime.getRuntime().exec(
				new String[] { svmRankClassifyPath, testingFeatureVectorsFileName,
						svmRankModelFileName, testingDocumentScoresFileName });
		process.waitFor();
		
		BufferedReader docScoreReader = new BufferedReader(new FileReader(testingDocumentScoresFileName));
		FileWriter outputWriter = new FileWriter(new File(trecEvalOutputPath));
		for (ScoreList result : testResults) {
			int numOfDoc = Math.min(result.size(), 100);
			ArrayList<TestScoreDocPair> listOfOneQuery = new ArrayList<TestScoreDocPair>();
			String qid = result.qid;
			
			for (int j = 0; j < numOfDoc; j++) {
				String externalDocid = Idx.getExternalDocid(result.getDocid(j));
				double score = Double.parseDouble(docScoreReader.readLine());
				listOfOneQuery.add(new TestScoreDocPair(qid, externalDocid, score));
			}
			
			Collections.sort(listOfOneQuery, new Comparator<TestScoreDocPair>() {
				@Override
				public int compare(TestScoreDocPair arg1, TestScoreDocPair arg2) {
					if (arg1.score - arg2.score < 0) {
						return 1;
					} else if (arg1.score - arg2.score > 0) {
						return -1;
					} else {
						return 0;
					}
				}
			});
			
			for (int j = 0; j < numOfDoc; j++) {
				String externalDocid = listOfOneQuery.get(j).externalDocid;
				
				outputWriter.write(qid + "\tQ0\t" + externalDocid
					+ "\t" + (j+1) + "\t" + listOfOneQuery.get(j).score + "\t" + "songzec" + "\n");
			}
		}
		outputWriter.close();
		docScoreReader.close();
	}

	private static void generateTestingData() throws IOException {
		BufferedReader input = null;
		String qLine = null;
		RetrievalModel model = new RetrievalModelBM25(BM25_k1, BM25_b, BM25_k3);
		FileWriter testingFeatureWriter = new FileWriter(new File(testingFeatureVectorsFileName));
		try {
			input = new BufferedReader(new FileReader(queryFilePath));
			while ((qLine = input.readLine()) != null) {
  				int d = qLine.indexOf(':');

  				if (d < 0) {
  					throw new IllegalArgumentException ("Syntax error:  Missing ':' in query line.");
  				}

  				printMemoryUsage(false);

  				String qid = qLine.substring(0, d);
  				String query = qLine.substring(d + 1);

  				System.out.println("Query " + qLine);

  				ScoreList result = null;

  				result = processQuery(qid, query, model);
  				
  				if (result != null) {
  					result.qid = qid;
  					String[] stemQuery = tokenizeQuery(query);
  					result.sort();
  					testResults.add(result);
  					List<FeatureValue> fvList = new ArrayList<FeatureValue>();
  					double[] maxVector = FeatureValue.maxDouble();
  					double[] minVector = FeatureValue.minDouble();
  					int numOfDoc = Math.min(result.size(), 100);
  					for (int i = 0; i < numOfDoc; i++) {
  						int docid = result.getDocid(i);
  						String externalDocid = Idx.getExternalDocid(docid);
  						double[] featureVector = FeatureValue.creatNewFeatureValue(docid, stemQuery);
  						FeatureValue featureValue = new FeatureValue(0, qid, featureVector, externalDocid);
  						fvList.add(featureValue);
  						FeatureValue.updateMinAndMax(minVector, maxVector, featureVector);
  					}
  					FeatureValue.normalizeFeatureValues(fvList, maxVector, minVector);
  					Collections.sort(fvList, new Comparator<FeatureValue>() {
  						@Override
  						public int compare(FeatureValue fv1, FeatureValue fv2) {
  							return fv1.externalDocid.compareTo(fv2.externalDocid);
  						}
  					});
  					writeToFile(testingFeatureWriter, fvList);
  				}
  			}
			testingFeatureWriter.close();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		} finally {
			input.close();
		}
	}

	private static void doTraining() throws IOException, InterruptedException {
		Process process = Runtime.getRuntime().exec(
				new String[] { svmRankLearnPath, "-c", String.valueOf(svmRankParamC),
									trainingFeatureVectorsFileName, svmRankModelFileName });
		process.waitFor();
	}

	private static void generateTrainingData() throws FileNotFoundException, IOException {
		File trainingQueryFile = new File(trainingQueryFileName);
		File trainingQrelsFile = new File(trainingQrelsFileName);
		File trainingFeatureVectorsFile = new File(trainingFeatureVectorsFileName);
		Scanner trainingQueryScanner = new Scanner(trainingQueryFile);
		Scanner trainingQrelsScanner = new Scanner(trainingQrelsFile);
		FileWriter trainingFeatureWriter = new FileWriter(trainingFeatureVectorsFile);
		String lineOfTrainingQrels = null;
		while (trainingQueryScanner.hasNextLine()) {
			List<FeatureValue> fvList = new ArrayList<FeatureValue>();
			double[] maxVector = FeatureValue.maxDouble();
			double[] minVector = FeatureValue.minDouble();
			String lineOfTrainingQuery = trainingQueryScanner.nextLine();
			String qid = lineOfTrainingQuery.split(":")[0];
			String qryString = lineOfTrainingQuery.split(":")[1];
			
			// check if the current line matches, same as if block of qid matched
			if (lineOfTrainingQrels != null && lineOfTrainingQrels.startsWith(qid + " ")) {
				String externalDocid = lineOfTrainingQrels.split(" ")[2];
				try {
					int docid = Idx.getInternalDocid(externalDocid);
					int relValue = Integer.parseInt(lineOfTrainingQrels.split(" ")[3]);
					String[] stemQuery = tokenizeQuery(qryString);
					double[] featureVector = FeatureValue.creatNewFeatureValue(docid, stemQuery);
					FeatureValue featureValue = new FeatureValue(relValue, qid, featureVector, externalDocid);
					fvList.add(featureValue);
					FeatureValue.updateMinAndMax(minVector, maxVector, featureVector);
				} catch (Exception e) {
					//System.out.println("externalDocid not found: " + externalDocid);
				}
			} else {
				System.out.println(lineOfTrainingQrels);
			}
			
			// check if the next lines match
			while (trainingQrelsScanner.hasNextLine()) {
				
				lineOfTrainingQrels = trainingQrelsScanner.nextLine();

				if (lineOfTrainingQrels.startsWith(qid + " ")) {	// qid matched
					String externalDocid = lineOfTrainingQrels.split(" ")[2];
					try {
						int docid = Idx.getInternalDocid(externalDocid);
						int relValue = Integer.parseInt(lineOfTrainingQrels.split(" ")[3]);
						String[] stemQuery = tokenizeQuery(qryString);
						double[] featureVector = FeatureValue.creatNewFeatureValue(docid, stemQuery);
						FeatureValue featureValue = new FeatureValue(relValue, qid, featureVector, externalDocid);
						fvList.add(featureValue);
						FeatureValue.updateMinAndMax(minVector, maxVector, featureVector);
					} catch (Exception e) {
						//System.out.println("externalDocid not found: " + externalDocid);
					}
				} else { // qid not matched
					break;
				}
			}
			FeatureValue.normalizeFeatureValues(fvList, maxVector, minVector);
			Collections.sort(fvList, new Comparator<FeatureValue>() {
				@Override
				public int compare(FeatureValue fv1, FeatureValue fv2) {
					return fv1.externalDocid.compareTo(fv2.externalDocid);
				}
			});
			writeToFile(trainingFeatureWriter, fvList);
		}
		trainingQueryScanner.close();
		trainingQrelsScanner.close();
		trainingFeatureWriter.close();
	}

	/**
	 * Write FeatureValue of one qid to file by writer
	 * @param writer
	 * @param fvList contains all FeatureValue with the same qid
	 * @throws IOException
	 */
	private static void writeToFile(FileWriter writer, List<FeatureValue> fvList) throws IOException {
		for (FeatureValue fv : fvList) {
			writer.write(fv.relValue + " qid:" + fv.qid + " ");
			for (int i = 1; i <= numOfFeatureVectors; i++) {
				writer.write(i + ":" + fv.featureVector[i - 1] + " ");
			}
			writer.write("# " + fv.externalDocid + "\n");
		}
	}

	/**
	 * Allocate the retrieval model and initialize it using parameters
	 * from the parameter file.
	 * @return The initialized retrieval model
	 * @throws Exception 
	 */
	private static RetrievalModel initializeRetrievalModel (Map<String, String> parameters) throws Exception {

		RetrievalModel model = null;
		String modelString = parameters.get ("retrievalAlgorithm").toLowerCase();

		if (modelString.equals("unrankedboolean")) {
			model = new RetrievalModelUnrankedBoolean();
		} else if (modelString.equals("rankedboolean")) {
			model = new RetrievalModelRankedBoolean();
	    } else if (modelString.equals("bm25")) {
	    	if (parameters.containsKey("BM25:k_1")
	    			&& parameters.containsKey("BM25:b")
	    			&& parameters.containsKey("BM25:k_3")) {
	    		model = new RetrievalModelBM25(parameters.get ("BM25:k_1"),
	    										parameters.get ("BM25:b"),
	    										parameters.get ("BM25:k_3"));
	    	} else {
	    		model = new RetrievalModelBM25();
	    	}
	    } else if (modelString.equals("indri")) {
	    	if (parameters.containsKey("Indri:mu")
	    			&& parameters.containsKey("Indri:lambda")) {
	    		model = new RetrievalModelIndri(parameters.get("Indri:mu"),
	    										parameters.get("Indri:lambda"));
	    	} else {
	    		model = new RetrievalModelIndri();
	    	}
	    } else if (modelString.equals("letor")) {
	    	useLeToR  = true;
	    	trainingQueryFileName = parameters.get("letor:trainingQueryFile");
	    	trainingQrelsFileName = parameters.get("letor:trainingQrelsFile");
	    	trainingFeatureVectorsFileName = parameters.get("letor:trainingFeatureVectorsFile");
	    	FeatureValue.initializePageRank(parameters.get("letor:pageRankFile"));
	    	FeatureValue.featureDisable = parseFeatureDisable(parameters.get("letor:featureDisable"));
	    	svmRankLearnPath = parameters.get("letor:svmRankLearnPath");
	    	svmRankClassifyPath = parameters.get("letor:svmRankClassifyPath");
	    	svmRankParamC = Double.parseDouble(parameters.get("letor:svmRankParamC"));
	    	svmRankModelFileName = parameters.get("letor:svmRankModelFile");
	    	testingFeatureVectorsFileName = parameters.get("letor:testingFeatureVectorsFile");
	    	testingDocumentScoresFileName = parameters.get("letor:testingDocumentScores");
	    	queryFilePath = parameters.get("queryFilePath");
	    	trecEvalOutputPath = parameters.get("trecEvalOutputPath");
	    	BM25_k1 = parameters.get("BM25:k_1");
	    	BM25_b = parameters.get("BM25:b");
	    	BM25_k3 = parameters.get("BM25:k_3");
	    	new RetrievalModelBM25(BM25_k1, BM25_b, BM25_k3);
	    	Indri_mu = parameters.get("Indri:mu");
	    	Indri_lambda = parameters.get("Indri:lambda");
	    	new RetrievalModelIndri(Indri_mu, Indri_lambda);
	    } else {
	      throw new IllegalArgumentException
	        ("Unknown retrieval model " + parameters.get("retrievalAlgorithm"));
	    }
	
	    return model;
	}

	private static ArrayList<Integer> parseFeatureDisable(String string) {
		if (string == null) {
			return new ArrayList<Integer>();
		}
		String[] s = string.split(",");
		ArrayList<Integer> disabledFeatures = new ArrayList<Integer>();
		for (String str : s) {
			disabledFeatures.add(Integer.parseInt(str));
		}
		return disabledFeatures;
	}

	/**
	 * Optimize the query by removing degenerate nodes produced during
	 * query parsing, for example '#NEAR/1 (of the)' which turns into 
	 * '#NEAR/1 ()' after stopwords are removed; and unnecessary nodes
	 * or subtrees, such as #AND (#AND (a)), which can be replaced by 'a'.
	 */
	static Qry optimizeQuery(Qry q) {

		//  Term operators don't benefit from optimization.

		if (q instanceof QryIopTerm) {
			return q;
		}

		//  Optimization is a depth-first task, so recurse on query
		//  arguments.  This is done in reverse to simplify deleting
		//  query arguments that become null.
    
		for (int i = q.args.size() - 1; i >= 0; i--) {

			Qry q_i_before = q.args.get(i);
			Qry q_i_after = optimizeQuery (q_i_before);

			if (q_i_after == null) {
				q.removeArg(i);			// optimization deleted the argument
			} else {
				if (q_i_before != q_i_after) {
					q.args.set (i, q_i_after);	// optimization changed the argument
				}
			}
		}

		//  If the operator now has no arguments, it is deleted.

		if (q.args.size () == 0) {
			return null;
		}

		//  Only SCORE operators can have a single argument.  Other
		//  query operators that have just one argument are deleted.

		if ((q.args.size() == 1) && (!(q instanceof QrySopScore))) {
			q = q.args.get (0);
		}
		return q;
	}

	/**
  	 * Return a query tree that corresponds to the query.
  	 * 
  	 * @param qString
  	 *          A string containing a query.
  	 * @param qTree
  	 *          A query tree
  	 * @throws IOException Error accessing the Lucene index.
  	 */
  	static Qry parseQuery(String qString, RetrievalModel model) throws IOException {

  		//  Add a default query operator to every query. This is a tiny
  		//  bit of inefficiency, but it allows other code to assume
  		//  that the query will return document ids and scores.

  		String defaultOp = model.defaultQrySopName ();
  		qString = defaultOp + "(" + qString + ")";

  		//  Simple query tokenization.  Terms like "near-death" are handled later.

  		StringTokenizer tokens = new StringTokenizer(qString, "\t\n\r ,()", true);
  		String token = null;

  		//  This is a simple, stack-based parser.  These variables record
  		//  the parser's state.
    
  		Qry currentOp = null;
  		
  		Stack<Qry> opStack = new Stack<Qry>();

  		//  Each pass of the loop processes one token. The query operator
  		//  on the top of the opStack is also stored in currentOp to
  		//  make the code more readable.

	    while (tokens.hasMoreTokens()) {
	    	//weightExpected = false;
	    	token = tokens.nextToken();
	
	    	if (token.matches("[ ,(\t\n\r]")) {
	    		continue;
	    	} else if (token.equals(")")) {	// Finish current query op.
	
		        // If the current query operator is not an argument to another
		        // query operator (i.e., the opStack is empty when the current
		        // query operator is removed), we're done (assuming correct
		        // syntax - see below).
		
		        opStack.pop();
		
		        if (opStack.empty())
		          break;
		
		        // Not done yet.  Add the current operator as an argument to
		        // the higher-level operator, and shift processing back to the
		        // higher-level operator.
		
		        Qry arg = currentOp;
		        currentOp = opStack.peek();
		        currentOp.appendArg(arg);
		        
		        if (currentOp instanceof QryWSop ) {
		        	((QryWSop)currentOp).weightExpected = true;
		        }
	        
	    	} else if (token.equalsIgnoreCase("#or")) {
	    		currentOp = new QrySopOr ();
	    		currentOp.setDisplayName (token);
	    		opStack.push(currentOp);
	    	} else if (token.equalsIgnoreCase("#syn")) {
	    		currentOp = new QryIopSyn();
	    		currentOp.setDisplayName (token);
	    		opStack.push(currentOp);
	    	} else if (token.equalsIgnoreCase("#and")) {
	    		currentOp = new QrySopAnd();
	    		currentOp.setDisplayName (token);
	    		opStack.push(currentOp);
	    	} else if (token.equalsIgnoreCase("#sum")) {
	    		currentOp = new QrySopSum();
	    		currentOp.setDisplayName (token);
	    		opStack.push(currentOp);
	    	} else if (token.equalsIgnoreCase("#wsum")) {
	    		currentOp = new QrySopWsum();
	    		currentOp.setDisplayName (token);
	    		opStack.push(currentOp);
	    	} else if (token.equalsIgnoreCase("#wand")) {
	    		currentOp = new QrySopWand();
	    		currentOp.setDisplayName (token);
	    		opStack.push(currentOp);
	    	} else if (token.toLowerCase().startsWith("#near")) {
	    		int distance = 1;
	    		try {
	    			distance = Integer.parseInt(token.trim().split("/")[1]);
	    		} catch (Exception e) {
	    			System.err.println(e.getStackTrace());
	    		}
	    		currentOp = new QryIopNear(distance);
	    		currentOp.setDisplayName (token);
	    		opStack.push(currentOp);
	    	} else if (token.toLowerCase().startsWith("#window")) {
	    		if (model instanceof RetrievalModelBM25
	    				|| model instanceof RetrievalModelIndri) {
		    		int distance = 1;
		    		try {
		    			distance = Integer.parseInt(token.trim().split("/")[1]);
		    		} catch (Exception e) {
		    			System.err.println(e.getStackTrace());
		    		}
		    		currentOp = new QryIopWindow(distance);
		    		currentOp.setDisplayName (token);
		    		opStack.push(currentOp);
	    		} else {
	    			throw new IllegalArgumentException
	    				(model.getClass().getName() + " doesn't support the WINDOW operator.");
	    		}
	    	} else if (currentOp instanceof QryWSop
	    			&& ((QryWSop)currentOp).weightExpected
	    			&& (token.matches("^[0-9]*.[0-9]+") || token.matches("^[0-9]+"))) {
	    		double weight = Double.parseDouble(token);
	    		((QryWSop) currentOp).weights.add(weight);
    			((QryWSop) currentOp).weightSum += weight;
    			((QryWSop)currentOp).weightExpected = false;
	    	} else {
	    		//  Split the token into a term and a field.

	    		int delimiter = token.indexOf('.');
	    		String field = null;
	    		String term = null;

	    		if (delimiter < 0) {
	    			field = "body";
	    			term = token;
	    		} else {
	    			field = token.substring(delimiter + 1).toLowerCase();
	    			term = token.substring(0, delimiter);
	    		}
	    		
	    		boolean fieldIsLegal = false;
	    		for (String validField : TEXT_FIELDS) {
	    			if (field.compareTo(validField) == 0) {
	    				fieldIsLegal = true;
	    				break;
	    			}
	    		}
	    		if (!fieldIsLegal) {
	    			throw new IllegalArgumentException ("Error: Unknown field " + token);
	    		}
	
	    		//  Lexical processing, stopwords, stemming.  A loop is used
	    		//  just in case a term (e.g., "near-death") gets tokenized into
	    		//  multiple terms (e.g., "near" and "death").
	
	    		String t[] = tokenizeQuery(term);
	    		if (t.length == 0) {	// a tricky way to drop the weights of stop words
	    			if (currentOp instanceof QryWSop) {
		    			double weight = ((QryWSop)currentOp).weights.remove(((QryWSop)currentOp).weights.size() - 1);
		    			((QryWSop)currentOp).weightSum -= weight;
		    		}
	    		}
	    		for (int j = 0; j < t.length; j++) {
	    			Qry termOp = new QryIopTerm(t[j], field);
	    			currentOp.appendArg (termOp);
	    		}
	    		
	    		if (currentOp instanceof QryWSop) {
	    			((QryWSop)currentOp).weightExpected = true;
	    		}
	    		
	    	}
	    	
	    }
	
	
	    //  A broken structured query can leave unprocessed tokens on the opStack,
	
	    if (tokens.hasMoreTokens()) {
	      throw new IllegalArgumentException
	        ("Error:  Query syntax is incorrect.  " + qString);
	    }
	
	    return currentOp;
  	}

  	/**
  	 * Print a message indicating the amount of memory used. The caller
  	 * can indicate whether garbage collection should be performed,
  	 * which slows the program but reduces memory usage.
  	 * 
  	 * @param gc
  	 *          If true, run the garbage collector before reporting.
  	 */
  	public static void printMemoryUsage(boolean gc) {

  		Runtime runtime = Runtime.getRuntime();

  		if (gc) {
  			runtime.gc();
  		}

  		System.out.println("Memory used:  "
  				+ ((runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)) + " MB");
  	}

  	/**
  	 * Process one query.
  	 * @param qString A string that contains a query.
  	 * @param model The retrieval model determines how matching and scoring is done.
  	 * @return Search results
  	 * @throws IOException Error accessing the index
  	 */
  	static ScoreList processQuery(String qid, String qString, RetrievalModel model) throws IOException {

  		Qry q = parseQuery(qString, model);
  		q = optimizeQuery (q);

  		// Show the query that is evaluated

  		System.out.println("    --> " + q);
    
  		if (fb) {
  			ScoreList r = new ScoreList();
  			
  			if (initialRankingList != null) {
  				r = initialRankingList.get(qid);
  			} else {
  				r = processInitialQuery(q, model);
  				r.sort();
  			}
  			
  			String defaultOp = model.defaultQrySopName();
  			qString = defaultOp + "(" + qString + ")";
  			String expendedQuery = expendQuery(q, r);
  			String newQueryString = "#wand ( " + String.valueOf(fbOrigWeight) + " " + qString + " "
  											+ String.valueOf(1 - fbOrigWeight) + " " + expendedQuery + " )";
  			
  			Qry newQry = parseQuery(newQueryString, model);
  			r = processInitialQuery(newQry, model);
  			
  			return r;
  		} else {
  			return processInitialQuery(q, model);
  		}  		
  		
  	}

  	/**
  	 * Expend initial query q using Indri model.
  	 * @param q the initial query
  	 * @param r the ScoreList for the initial query q
  	 * @return String of expended query, not including initial query
  	 * @throws IOException 
  	 */
  	private static String expendQuery(Qry q, ScoreList r) throws IOException {
  		Map<Integer,TermVector>termVectorMap = new HashMap<>();
  		Map<String, Double> termScore = new HashMap<String, Double>();
  		Map<String, Double> pMLEs = new HashMap<String, Double>();
  		Map<String, ArrayList<Integer>> termAppearance = new TreeMap<String, ArrayList<Integer>>();
  		for (int i = 0; i < fbDocs && i < r.size(); i++) { // calculate score of term if term is in documents
  			TermVector termVector = new TermVector(r.getDocid(i), "body");
  			termVectorMap.put(r.getDocid(i), termVector);
  			double docScore = r.getDocidScore(i);
  			double docLen = Idx.getFieldLength("body", r.getDocid(i));
  			
  			for (int j = 1; j < termVector.stemsLength(); j++) {
  				String term = termVector.stemString(j);
  				if (term.contains(".")) {
  					continue;
  				}
  				if (termAppearance.containsKey(term)) {
  					ArrayList<Integer> tmp = termAppearance.get(term);
  					tmp.add(r.getDocid(i));
  					termAppearance.put(term, tmp);
  				} else {
  					ArrayList<Integer> newList = new ArrayList<>();
  					newList.add(r.getDocid(i));
  					termAppearance.put(term, newList);
  				}
  				
  				double pMLE;
  				if (pMLEs.containsKey(term)) {
  					pMLE = pMLEs.get(term);
  				} else {
  					pMLE= (double) termVector.totalStemFreq(j) / (double) Idx.getSumOfFieldLengths("body");
  					pMLEs.put(term, pMLE);
  				}
  				
  				double ptd = ((double) termVector.stemFreq(j)  + fbMu * pMLE) / (double)(docLen + fbMu);
  				double score = ptd * docScore * Math.log(1.0 / pMLE);
  				if (termScore.containsKey(term)) {
  					termScore.put(term, termScore.get(term) + score);
  				} else {
  					termScore.put(term, score);
  				}
  			}
  		}
  		
  		for (String term : termAppearance.keySet()) { // consider if a term is not in a document, use a default score
  			ArrayList<Integer> docsContainingThisTerm = termAppearance.get(term);
  			 for (int i = 0; i < fbDocs && i < r.size(); i++) {
  				if (!docsContainingThisTerm.contains(r.getDocid(i))) {
  					double docScore = r.getDocidScore(i);
  					double docLen = Idx.getFieldLength("body", r.getDocid(i));
  					double pMLE = pMLEs.get(term);
  					double ptd = (double)(fbMu * pMLE) / (double)(docLen + fbMu);
  					double score = ptd * docScore * (Math.log(1.0 / pMLE));
  					
  					if (termScore.containsKey(term)) {
  	  					termScore.put(term, termScore.get(term) + score);
  	  				} else {
  	  					termScore.put(term, score);
  	  				}
  				}
  			 }
  		}
  		
  		PriorityQueue<Map.Entry<String, Double>> pq = new PriorityQueue<Map.Entry<String, Double>>(termScore.size(), new Comparator<Map.Entry<String, Double>>() {
			@Override
			public int compare(Entry<String, Double> o1, Entry<String, Double> o2) {
				return o2.getValue().compareTo(o1.getValue());
			}
  			
  		});
  		pq.addAll(termScore.entrySet());
  		
  		expandedQuery = "#wand ( ";
  		for (int i = 0; i < fbTerms; i++) {
  			Entry<String, Double> entry = pq.poll();
  			String term = entry.getKey();
  			String score = String.format("%.9f", entry.getValue());
  			expandedQuery = expandedQuery + " " + score + " " + term;
  		}
  		expandedQuery += " )";
  		System.out.println(expandedQuery);
		return expandedQuery;
	}

  	/**
  	 * process a query and return the corresponding ScoreList
  	 * @param q
  	 * @param model
  	 * @return the corresponding ScoreList
  	 * @throws IOException
  	 */
	private static ScoreList processInitialQuery(Qry q, RetrievalModel model) throws IOException {
  		if (q != null) {
  			ScoreList r = new ScoreList ();
      
  			if (q.args.size () > 0) {		// Ignore empty queries
  				q.initialize (model);
  				while (q.docIteratorHasMatch (model)) {
  					int docid = q.docIteratorGetMatch ();
  					double score = ((QrySop) q).getScore (model);
  					r.add (docid, score);
  					q.docIteratorAdvancePast (docid);
  				}
  			}
  			
  			return r;
  		} else {
  			return null;
  		}
	}

	/**
  	 * Process the query file.
  	 * @param parameters
  	 * @param model
  	 * @throws IOException Error accessing the Lucene index.
  	 */
  	static void processQueryFile(Map<String, String> parameters, RetrievalModel model) throws IOException {

  		BufferedReader input = null;
  		File file = new File(parameters.get("trecEvalOutputPath"));
  		BufferedWriter output = new BufferedWriter(new FileWriter(file));
  		try {
  			String qLine = null;

  			input = new BufferedReader(new FileReader(parameters.get("queryFilePath")));
  			BufferedWriter writer = new BufferedWriter(new FileWriter(new File(fbExpansionQueryFile)));
  			//  Each pass of the loop processes one query.

  			while ((qLine = input.readLine()) != null) {
  				int d = qLine.indexOf(':');

  				if (d < 0) {
  					throw new IllegalArgumentException ("Syntax error:  Missing ':' in query line.");
  				}

  				printMemoryUsage(false);

  				String qid = qLine.substring(0, d);
  				String query = qLine.substring(d + 1);

  				System.out.println("Query " + qLine);

  				ScoreList r = null;

  				r = processQuery(qid, query, model);

  				if (fb) {
              		writer.write(qid + ": " + expandedQuery + "\n");
  				}
  				
  				if (r != null) {
  					printResults(qid, r, output);
  					System.out.println();
  				}
  			}
  			writer.close();
  		} catch (IOException ex) {
  			ex.printStackTrace();
  		} finally {
  			
  			input.close();
  			output.close();
  		}
  	}



	/**
  	 * Print the query results.
  	 * 
  	 * THIS IS THE CORRECT OUTPUT FORMAT:
  	 * 
  	 * QueryID Q0 DocID Rank Score RunID
  	 * 
  	 * @param qid
  	 *          Original query.
  	 * @param result
  	 *          A list of document ids and scores
  	 * @param output 
  	 * @throws IOException Error accessing the Lucene index.
  	 */
  	static void printResults(String qid, ScoreList result, BufferedWriter output) throws IOException {
  		result.sort();
  		if (result.size() < 1) {
  			output.write(qid + "\tQ0\tdummy\t1\t0\trun-1\n");
  		} else {
  			int numOfDoc = Math.min(result.size(), 100);
  			for (int i = 0; i < numOfDoc; i++) {
  				output.write(qid + "\tQ0\t" + Idx.getExternalDocid(result.getDocid(i))
  					+ "\t" + (i+1) + "\t" + result.getDocidScore(i) + "\t" + "songzec" + "\n");
  			}
  		}
  	}

  	/**
  	 * Read the specified parameter file, and confirm that the required
  	 * parameters are present.  The parameters are returned in a
  	 * HashMap.  The caller (or its minions) are responsible for
  	 * processing them.
  	 * @return The parameters, in <key, value> format.
  	 */
  	private static Map<String, String> readParameterFile (String parameterFileName) throws IOException {

  		Map<String, String> parameters = new HashMap<String, String>();

  		File parameterFile = new File (parameterFileName);

  		if (! parameterFile.canRead ()) {
  			throw new IllegalArgumentException ("Can't read " + parameterFileName);
  		}

  		Scanner scan = new Scanner(parameterFile);
  		String line = null;
  		do {
  			line = scan.nextLine();
  			String[] pair = line.split ("=");
  			parameters.put(pair[0].trim(), pair[1].trim());
  		} while (scan.hasNext());

  		scan.close();

  		if (! (parameters.containsKey ("indexPath") &&
  				parameters.containsKey ("queryFilePath") &&
  				parameters.containsKey ("trecEvalOutputPath") &&
  				parameters.containsKey ("retrievalAlgorithm"))) {
  			throw new IllegalArgumentException ("Required parameters were missing from the parameter file.");
  		}

  		if (parameters.containsKey("fb") && parameters.get("fb").toLowerCase().equals("true")) {
  			fb = true;
  			
  			if (parameters.containsKey(("fbExpansionQueryFile"))) {
  				fbExpansionQueryFile = parameters.get("fbExpansionQueryFile");
  			} else {
  				throw new IllegalArgumentException("Expecting parameter: fbExpansionQueryFile.");
  			}
  			
  			if (parameters.containsKey("fbDocs")) {
  				fbDocs = Integer.parseInt(parameters.get("fbDocs"));
  			} else {
  				throw new IllegalArgumentException("Expecting parameter: fbDocs.");
  			}
  			
  			if (parameters.containsKey("fbTerms")) {
  				fbTerms = Integer.parseInt(parameters.get("fbTerms"));
  			} else {
  				throw new IllegalArgumentException("Expecting parameter: fbTerms.");
  			}
  			
  			if (parameters.containsKey("fbOrigWeight")) {
  				fbOrigWeight = Double.parseDouble(parameters.get("fbOrigWeight"));
  			} else {
  				throw new IllegalArgumentException("Expecting parameter: fbOrigWeight.");
  			}
  			
  			if (parameters.containsKey("fbMu")) {
  	            fbMu = Double.parseDouble(parameters.get("fbMu"));
  			}
  			if (parameters.containsKey("fbInitialRankingFile")) {
  				fbInitialRankingFile = parameters.get("fbInitialRankingFile");
  			}
  		}
  		return parameters;
  	}

  	/**
  	 * Given a query string, returns the terms one at a time with stopwords
  	 * removed and the terms stemmed using the Krovetz stemmer.
  	 * 
  	 * Use this method to process raw query terms.
  	 * 
  	 * @param query
  	 *          String containing query
  	 * @return Array of query tokens
  	 * @throws IOException Error accessing the Lucene index.
  	 */
  	static String[] tokenizeQuery(String query) throws IOException {

  		TokenStreamComponents comp =
  				ANALYZER.createComponents("dummy", new StringReader(query));
  		TokenStream tokenStream = comp.getTokenStream();

  		CharTermAttribute charTermAttribute =
  				tokenStream.addAttribute(CharTermAttribute.class);
  		tokenStream.reset();

  		List<String> tokens = new ArrayList<String>();

  		while (tokenStream.incrementToken()) {
  			String term = charTermAttribute.toString();
  			tokens.add(term);
  		}

  		return tokens.toArray (new String[tokens.size()]);
  	}

  	/**
  	 * Read initial ranking file and put information into initialRankingList.
  	 * @param fbInitialRankingFile
  	 * @throws Exception
  	 */
	private static void readInitialRankingFile(String fbInitialRankingFile)  throws Exception {

		File initialRankingFile = new File(fbInitialRankingFile);
		initialRankingList = new HashMap<String, ScoreList>();
		ScoreList r = null;
		Scanner scanner = new Scanner(initialRankingFile);
		String line;
		String qid = "-1";
		String currentQID = "-1";
		while (scanner.hasNextLine()) {
			line = scanner.nextLine();
			String[] args = line.split(" ");
			currentQID = args[0].trim();
			int docid = Idx.getInternalDocid(args[2].trim());
			double score = Double.parseDouble(args[4].trim());
			
			if (!qid.equals(currentQID)) {
				if (r != null) {
					initialRankingList.put(qid, r);
				}
				r = new ScoreList();
				qid = currentQID;
			}
			r.add(docid, score);
		}
		if (qid != "-1") {
			initialRankingList.put(qid, r);
		}
		scanner.close();
	}
	
}