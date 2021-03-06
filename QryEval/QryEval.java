/*
 *  Copyright (c) 2017, Carnegie Mellon University.  All Rights Reserved.
 *  Version 3.1.2.
 */

import java.io.*;
import java.util.*;

public class QryEval {

    //  --------------- Constants and variables ---------------------

    private static final String USAGE =
            "Usage:  java QryEval paramFile\n\n";

    private static final String[] TEXT_FIELDS =
            {"body", "title", "url", "inlink"};

    private static String trecEvalOutputPath;
    private static int trecEvalOutputLength = 100;

    //  --------------- Methods ---------------------------------------

    /**
     * @param args The only argument is the parameter file name.
     * @throws Exception Error accessing the Lucene index.
     */
    public static void main(String[] args) throws Exception {

        Timer timer = new Timer();
        timer.start();

        //  Check that a parameter file is included
        if (args.length < 1) {
            throw new IllegalArgumentException(USAGE);
        }

        Map<String, String> parameters = readParameterFile(args[0].trim());

        // output length
        if (parameters.containsKey("trecEvalOutputLength")) {
            trecEvalOutputLength = Integer.parseInt(parameters.get("trecEvalOutputLength"));
        }

        // output path
        trecEvalOutputPath = parameters.get("trecEvalOutputPath");
        clearOutputPath(trecEvalOutputPath);

        //  Open the index and initialize the retrieval model.
        Idx.open(parameters.get("indexPath"));

        //  Perform experiments.
        if (parameters.containsKey("retrievalAlgorithm") && parameters.get("retrievalAlgorithm").toLowerCase().equals("letor")) {
            QryEvalLeToR leToR = new QryEvalLeToR(parameters);
            leToR.processQueryFile();
        } else if (parameters.containsKey("diversity") && parameters.get("diversity").toLowerCase().equals("true")) {
            QryEvalDiversity qryEvalDiversity = new QryEvalDiversity(parameters);
            qryEvalDiversity.go();
        } else {
            RetrievalModel model = initializeRetrievalModel(parameters);
            processQueryFile(parameters.get("queryFilePath"), model, parameters);
        }


        //  Clean up.
        timer.stop();
        System.out.println("Time:  " + timer);
    }

    public static void clearOutputPath(String trecEvalOutputPath) throws FileNotFoundException {
        File f = new File(trecEvalOutputPath);
        if (f.exists() && !f.isDirectory()) {
            PrintWriter writer = new PrintWriter(f);
            writer.print("");
            writer.close();
        }
    }

    /**
     * Process the each query in queryFile line by line
     * Write the retrieval result to output file and console
     * @param queryFilePath
     * @param model
     * @throws IOException Error accessing the Lucene index.
     */
    static void processQueryFile(String queryFilePath, RetrievalModel model, Map<String, String> parameters) throws Exception {
        BufferedReader input = null;
        try {
            //  Each pass of the loop processes one query.
            String qLine = null;
            input = new BufferedReader(new FileReader(queryFilePath));

            while ((qLine = input.readLine()) != null) {
                // Validat that qid is in the query
                int d = qLine.indexOf(':');
                if (d < 0)  throw new IllegalArgumentException("Syntax error:  Missing ':' in query line.");

                printMemoryUsage(false);

                // Extract query id and query body
                String qid = qLine.substring(0, d);
                String query = qLine.substring(d + 1);
                System.out.println("Query " + qLine);

//                r = QryEval.processQuery(query, model);
                ScoreList r = QryEvalQueryExpension.processQueryWithQueryExpansion(qid, query, model, parameters);

                if (r != null) {
//                    printResults(qid, r);
                    r.sort();
                    r.truncate(trecEvalOutputLength);
                    writeResultToFile(trecEvalOutputPath, qid, r);
                    System.out.println();
                }

            }
        } catch (IOException ex) {
            ex.printStackTrace();
        } finally {
            input.close();
        }
    }


    /**
     * Process one query.
     *
     * @param qString A string that contains a query.
     * @param model   The retrieval model determines how matching and scoring is done.
     * @return Search results
     * @throws IOException Error accessing the index
     */
    static ScoreList processQuery(String qString, RetrievalModel model)
            throws IOException {

        if (needDefaultOperator(qString)) {
            // Add "#or" for default parameter, qString: "forearm pain"
            String defaultOp = model.defaultQrySopName();
            qString = defaultOp + "(" + qString + ")";
        }
        // automaticlly add
        Qry q = QryParser.getQuery(qString);

        // Show the query that is evaluated
        System.out.println("    --> " + q);
        if (q != null) {
            ScoreList r = new ScoreList();
            if (q.args.size() > 0) {        // Ignore empty queries
                q.initialize(model); //creat all inverted list

                while (q.docIteratorHasMatch(model)) {
                    int docid = q.docIteratorGetMatch();
                    double score;
                    if (q instanceof QryIop) {
                        if (model instanceof RetrievalModelUnrankedBoolean) {
                            score = ((QryIop) q).getCurrentTf() > 0 ? 1 : 0;
                        } else {
                            score = ((QryIop) q).getCurrentTf();
                        }
                    } else {
                        score = ((QrySop) q).getScore(model);
                    }
                    r.add(docid, score);
                    q.docIteratorAdvancePast(docid);
                }
            }

            return r;
        } else
            return null;
    }

    /**
     * Allocate the retrieval model and initialize it using parameters
     * from the parameter file.
     *
     * @return The initialized retrieval model
     * @throws IOException Error accessing the Lucene index.
     */
     static RetrievalModel initializeRetrievalModel(Map<String, String> parameters)
            throws IOException {

        RetrievalModel model = null;
        String modelString = parameters.get("retrievalAlgorithm").toLowerCase();


        if (modelString.equals("unrankedboolean")) {
            model = new RetrievalModelUnrankedBoolean();

        } else if (modelString.equals("rankedboolean")) {
            model = new RetrievalModelRankedBoolean();

        } else if (modelString.equals("bm25")) {
            if (!(parameters.containsKey("BM25:k_1") &&
                    parameters.containsKey("BM25:b") &&
                    parameters.containsKey("BM25:k_3"))) {
                throw new IllegalArgumentException
                        ("Required parameters were missing for BM25 model.");
            }
            double k_1 = Double.parseDouble(parameters.get("BM25:k_1"));
            double b = Double.parseDouble(parameters.get("BM25:b"));
            double k_3 = Double.parseDouble(parameters.get("BM25:k_3"));
            model = new RetrievalModelBM25(k_1, b, k_3);

        } else if (modelString.equals("indri")) {
            if (!(parameters.containsKey("Indri:mu") &&
                    parameters.containsKey("Indri:lambda") )) {
                throw new IllegalArgumentException
                        ("Required parameters were missing for Indri model.");
            }
            double mu = Double.parseDouble(parameters.get("Indri:mu"));
            double lambda = Double.parseDouble(parameters.get("Indri:lambda"));

            model = new RetrievalModelIndri(mu, lambda);
        } else {
            throw new IllegalArgumentException
                    ("Unknown retrieval model " + parameters.get("retrievalAlgorithm"));
        }

        return model;
    }

    /**
     * Print a message indicating the amount of memory used. The caller can
     * indicate whether garbage collection should be performed, which slows the
     * program but reduces memory usage.
     *
     * @param gc If true, run the garbage collector before reporting.
     */
    public static void printMemoryUsage(boolean gc) {

        Runtime runtime = Runtime.getRuntime();

        if (gc)
            runtime.gc();

        System.out.println("Memory used:  "
                + ((runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L)) + " MB");
    }



    //handle in Query Optimize
    public static Boolean needDefaultOperator(String qString) throws IllegalArgumentException {
        qString = qString.toLowerCase().trim();
        // check for case "a b c" , "#xx(a b) c"
        if (!qString.startsWith("#") || !qString.trim().endsWith(")")) {
            return true;
        } else if(qString.startsWith("#near") || qString.startsWith("#window") || qString.startsWith("#syn")) {
            // after last check, qString must start with # and ends with )
            // check for case that qStrin starts with a IopTerm #NEAR(a, b) #Window(a, b)
            return true;
        } else {
            // check for case #AND(brooks brothers) #AND(like india)
            int firstLeftIdx = qString.indexOf("(");
            int pairRightIdx = -1;
            int count = 1;
            for (int i = firstLeftIdx + 1; i < qString.length(); i++) {
                if (qString.charAt(i) == '(') count++;
                if (qString.charAt(i) == ')') count--;
                if (count == 0) {
                    pairRightIdx = i;
                    break;
                }
            }
            if (pairRightIdx == -1) throw new IllegalArgumentException("Syntax Error: Missing closing paranthesis");
            if (pairRightIdx != qString.length() - 1) return true;
        }
        return false;
    }





    /**
     * Print the query results.
     * <p>
     * THIS IS NOT THE CORRECT OUTPUT FORMAT. YOU MUST CHANGE THIS METHOD SO
     * THAT IT OUTPUTS IN THE FORMAT SPECIFIED IN THE HOMEWORK PAGE, WHICH IS:
     * <p>
     * QueryID Q0 DocID Rank Score RunID
     *
     * @param qid    Original query.
     * @param result A list of document ids and list
     * @throws IOException Error accessing the Lucene index.
     */
    static void printResults(String qid, ScoreList result) throws IOException {

        System.out.println(qid + ":  ");
        if (result.size() < 1) {
            System.out.println("\tNo results.");
        } else {
            if (result.size() <= 20) {
                for (int i = 0; i < result.size(); i++) {
                    System.out.println("\t" + (i + 1) + ":  " + Idx.getExternalDocid(result.getDocid(i)) + ", "
                            + result.getDocidScore(i));
                }
            } else {
                for (int i = 0; i < 5; i++) {
                    System.out.println("\t" + (i + 1) + ":  " + Idx.getExternalDocid(result.getDocid(i)) + ", " + result.getDocidScore(i));
                }
            }

        }
    }

    static void writeResultToFile(String outputPath, String qid, ScoreList result) throws IOException {
        String runId = "run-1";

        // Sort the ScoreList
        result.sort();
        System.out.println("write" + qid + " to file");

        try (BufferedWriter bw = new BufferedWriter(new FileWriter(outputPath, true));) {
            if (result.size() < 1) {
                String writeLine = String.format("%d\t%s\t%s\t%d\t%d\t%s", 10, "Q0", "dummy", 1, 0, runId);
                bw.write(writeLine);
            } else {
                for (int i = 0; i < result.size(); i++) {
                    String formattedLine = String.format("%s\t%s\t%s\t%d\t%s\t%s\n", qid, "Q0", Idx.getExternalDocid(result.getDocid(i)), i + 1, result.getDocidScore(i), runId);
                    bw.write(formattedLine);
                    if (i < 5) {
                        System.out.print(formattedLine);
                    }
                }
            }
        }
    }

    private static int min(int a, int b) {
        return a <= b ? a : b;
    }

    /**
     * Read the specified parameter file, and confirm that the required
     * parameters are present.  The parameters are returned in a
     * HashMap.  The caller (or its minions) are responsible for processing
     * them.
     *
     * @return The parameters, in <key, value> format.
     */
    private static Map<String, String> readParameterFile(String parameterFileName)
            throws IOException {

        Map<String, String> parameters = new HashMap<String, String>();

        File parameterFile = new File(parameterFileName);

        if (!parameterFile.canRead()) {
            throw new IllegalArgumentException
                    ("Can't read " + parameterFileName);
        }

        System.out.println("parameterFile: " + parameterFile);
        String line = null;
        try (BufferedReader br = new BufferedReader(new FileReader(parameterFile))) {
            while ((line = br.readLine()) != null) {
                String[] pair = line.split("=");
                if (pair.length > 1 && pair[1].trim().length() > 0) {
                    parameters.put(pair[0].trim(), pair[1].trim());
                }
            }
        }

        if (!(parameters.containsKey("indexPath") &&
                parameters.containsKey("queryFilePath") &&
                parameters.containsKey("trecEvalOutputPath"))) {
            throw new IllegalArgumentException
                    ("Required parameters were missing from the parameter file.");
        }

        // For HW5 diversity task, allow retrievalAlgorithm to be empty
        if (!parameters.containsKey("retrievalAlgorithm") && !parameters.containsKey("diversity:initialRankingFile")) {
            throw new IllegalArgumentException
                    ("Required parameters were missing from the parameter file.\n" +
                            "Should at least have one of 'retrievalAlgorithm' and 'diversity:initialRankingFile'");
        }

        return parameters;
    }

}
