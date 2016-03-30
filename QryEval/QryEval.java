/*
 *  Copyright (c) 2016, Carnegie Mellon University.  All Rights Reserved.
 *  Version 3.1.1.
 */

import java.io.*;
import java.util.*;
import java.util.Map.Entry;

import org.apache.lucene.analysis.Analyzer.TokenStreamComponents;
import org.apache.lucene.analysis.TokenStream;
import org.apache.lucene.analysis.tokenattributes.CharTermAttribute;
import org.apache.lucene.document.Document;
import org.apache.lucene.index.*;
import org.apache.lucene.search.*;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.Version;

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
	private static boolean fb = false;
	private static String fbInitialRankingFile;
	private static String fbExpansionQueryFile;
	private static int fbDocs;
	private static int fbTerms;
	private static double fbMu = 0;
	private static double fbOrigWeight;
	private static Map<String, ScoreList> initialRankingList;

	private static String expandedQuery;

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

		//  Perform experiments.
		if (fb && fbInitialRankingFile != null) {
			readInitialRankingFile(fbInitialRankingFile);
		}
		processQueryFile(parameters, model);

		//  Clean up.
    
		timer.stop ();
		System.out.println ("Time:  " + timer);
	}

	/**
	 * Allocate the retrieval model and initialize it using parameters
	 * from the parameter file.
	 * @return The initialized retrieval model
	 * @throws IOException Error accessing the Lucene index.
	 */
	private static RetrievalModel initializeRetrievalModel (Map<String, String> parameters) throws IOException {

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
	    } else {
	      throw new IllegalArgumentException
	        ("Unknown retrieval model " + parameters.get("retrievalAlgorithm"));
	    }
	
	    return model;
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
                    printExpandedQuery(qid);
                }
  				
  				if (r != null) {
  					printResults(qid, r, output);
  					System.out.println();
  				}
  			}
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
  	 * @param queryName
  	 *          Original query.
  	 * @param result
  	 *          A list of document ids and scores
  	 * @param output 
  	 * @throws IOException Error accessing the Lucene index.
  	 */
  	static void printResults(String queryName, ScoreList result, BufferedWriter output) throws IOException {
  		result.sort();
  		if (result.size() < 1) {
  			output.write(queryName + "\tQ0\tdummy\t1\t0\trun-1\n");
  		} else {
  			int numOfDoc = Math.min(result.size(), 100);
  			for (int i = 0; i < numOfDoc; i++) {
  				output.write(queryName + "\tQ0\t" + Idx.getExternalDocid(result.getDocid(i))
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
	
	
  	private static void printExpandedQuery(String qid) throws IOException {
  		BufferedWriter writer = new BufferedWriter(new FileWriter(new File(fbExpansionQueryFile)));
  		writer.write(qid + ": " + expandedQuery + "\n");
  		writer.close();
	}
}