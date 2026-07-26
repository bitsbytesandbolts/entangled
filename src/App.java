// #region DESCRIPTION
    // My old Java project for solving these puzzles and generating graphs 
    // is all one gigantic mess. It's time to start fresh, objectively 
    // compare solving methods to find the fastest one, and make everything
    // modular and scalable to support various functionalities.
// #endregion DESCRIPTION

import java.util.ArrayList;

public class App {
    public static void main(String[] args) throws Exception {

        // #region SOLUTION EVALUATOR
        
        // SolutionEvaluator evaluator = new SolutionEvaluator(grid, 5, grid2, 4);
        // evaluator.solve();
        // Thread.sleep(10000);

        // #endregion SOLUTION EVALUATOR


        // #region QUICK SOLVER -- uncomment to solve a particular puzzle as determined by grid and grid2

        // System.out.println("\n Running BitSolverV9...");
        // System.gc();
        // BitSolverV9 solver9 = new BitSolverV9(grid, 10, grid2, 5);
        // solver9.solve(true);

        // Thread.sleep(100000);

        // #endregion QUICK SOLVER

        // #region SINGLE PUZZLE SIMPLE SEARCHER
            // int[][] testGrid = new int[][]{
            //     {3, 1, 0}, {3, 1, 0}, {2, 1, 1}, {2, 4, 0}, {0, 4, 0}
            // };
            // int pieceCount = 4;
            // SimplePuzzleSearcher sps = new SimplePuzzleSearcher(testGrid, pieceCount);
            // System.out.println(sps.findBestSimplePuzzles(0));

            // int[][] testGridA = new int[][]{
            //     {1, 1, 0, 0},
            //     {2, 0, 0, 0},
            //     {2, 3, 3, 0},
            //     {4, 4, 0, 0}
            // };
            // int[][] testGridB = new int[][]{
            //     {0, 1, 1, 0},
            //     {0, 0, 2, 0},
            //     {3, 3, 2, 0},
            //     {0, 4, 4, 0}
            // };
            // EntangledSimplePuzzleSearcher entangledSps = new EntangledSimplePuzzleSearcher(testGridA, testGridB, 4);
            // System.out.println(entangledSps.findBestSimplePuzzles(0, "demoA", "demoB"));

            // Thread.sleep(10000);
        // #endregion SINGLE PUZZLE SIMPLE SEARCHER

        // // #region SIMPLE SEARCHER -- uncomment to find longest simple puzzles in a CSV file

        //     // Load CSV
        //     String filepath = "interesting_groups_found_so_far.csv";
        //     int diameterThreshold = 20;
        //     int simplePuzzleThreshold = 10;
        //     int pieceCount = 3;

        //     ArrayList<int[][]> puzzleGridsA = new ArrayList<>();
        //     ArrayList<int[][]> puzzleGridsB = new ArrayList<>();
        //     ArrayList<String> puzzlePieceCodesA = new ArrayList<>();
        //     ArrayList<String> puzzlePieceCodesB = new ArrayList<>();
        //     // Parse through CSV
        //     // For each row, if entry 5 (0-indexed) is >= diameterThreshold, add entries 6/7 to puzzleGrids

        //     java.nio.file.Files.lines(java.nio.file.Paths.get(filepath)).forEach(line -> {
        //         String[] parts = line.split(",");
        //         if(parts[5].equals("MaxEstimatedDiameter")) return; // Skip header
        //         int diameter = Integer.parseInt(parts[5]);
        //         if(diameter >= diameterThreshold){
        //             int[][] gridA = stringToGrid(parts[6]);
        //             int[][] gridB = stringToGrid(parts[7]);
        //             puzzleGridsA.add(gridA);
        //             puzzleGridsB.add(gridB);

        //             puzzlePieceCodesA.add(parts[1]);
        //             puzzlePieceCodesB.add(parts[2]);
        //         }
        //     });

        //     String csvString = "PieceCodesA,PieceCodesB,Piece,Length,StartStateA,StartStateB,EndStateA,EndStateB,TotalSolutions,SolutionStates,TotalStates,State%,AvgTightropes,AvgDegree\n";
        //     boolean deduplicateIdenticalPiecePermutationsInSps = false; // Only set to true if you want to ignore identical pieces in the simple puzzle searcher, which can speed up searches but may give inaccurate solution counts for groups with identical pieces
        //     boolean evaluateSolutionAnalyticsInSps = true;
        //     for(int i = 0; i < puzzleGridsA.size(); i++){
        //         System.out.print("\033[H");  // Move cursor to top-left
        //         System.out.print("Processing puzzle " + (i+1) + "/" + puzzleGridsA.size());
        //         System.out.flush();
        //         int[][] gridA = puzzleGridsA.get(i);
        //         int[][] gridB = puzzleGridsB.get(i);
        //         EntangledSimplePuzzleSearcher sps = new EntangledSimplePuzzleSearcher(
        //             gridA,
        //             gridB,
        //             pieceCount,
        //             deduplicateIdenticalPiecePermutationsInSps,
        //             evaluateSolutionAnalyticsInSps
        //         );
        //         String s = sps.findBestSimplePuzzles(
        //             simplePuzzleThreshold,
        //             puzzlePieceCodesA.get(i),
        //             puzzlePieceCodesB.get(i)
        //         );
        //         if(!(s.equals("")))
        //             csvString += s;
        //         // Go down 10 lines to print the puzzle
        //         System.out.println("\n\n\n\n\n\n\n\n\n\n");
        //         System.out.println(s);
        //     }

        //     java.nio.file.Files.write(java.nio.file.Paths.get("simple_puzzles.csv"), csvString.getBytes());

            
        // // #endregion SIMPLE SEARCHER

        // #region MAIN SEARCH -- the meat of the potato

            // Create groups of pieces
            int groupSize = 7; // Number of pieces per puzzle
            PieceGrouper grouper = new PieceGrouper();

            int puzzleWidth = 4;
            int puzzleHeight = 4;
            long blockedBitboard = 0;//(1L << 0);// | (1L << 5) | (1L << 6); // Example blocked squares
            boolean removeSymmetries = (puzzleWidth == puzzleHeight) && blockedBitboard == 0; // Only remove symmetries for square puzzles with no blocked squares
            //removeSymmetries = true; // Override to always remove symmetries, comment out to bypass

            // Trying this heuristic to cut down the search space based on good puzzles I've found so far
            int areaMin = 7;
            int areaMax = 10;
            int monominoLimit = 1;
            int mustHavePieceOfSize = 0; // between A and B, at least one piece must be of this size or larger. Set to 0 to ignore.
            ArrayList<PieceGrouper.EntangledGroupPair> allGroups = grouper.generateEntangledPairs(new String[]{
                "0",
                "1", "2I", "2I90", "3I", "3I90", "3L", "3L90", "3L180", "3L270", 
                // "4O", "4I", "4I90", 
                // "4L", "4L90", "4L180", "4L270", 
                // "4J", "4J90", "4J180", "4J270",
                // "4T", "4T90", "4T180", "4T270",
                // "4S", "4S90", "4Z", "4Z90",
                // "5F", "5F90", "5F180", "5F270",
                // "5f", "5f90", "5f180", "5f270",
                // "5I", "5I90",
                // "5L", "5L90", "5L180", "5L270",
                // "5J", "5J90", "5J180", "5J270",
                // "5N", "5N90", "5N180", "5N270",
                // "5n", "5n90", "5n180", "5n270",
                // "5P", "5P90", "5P180", "5P270",
                // "5Q", "5Q90", "5Q180", "5Q270",
                // "5T", "5T90", "5T180", "5T270",
                // "5U", "5U90", "5U180", "5U270",
                // "5V", "5V90", "5V180", "5V270",
                // "5W", "5W90", "5W180", "5W270",
                // "5X", 
                // "5Y", "5Y90", "5Y180", "5Y270",
                // "5y", "5y90", "5y180", "5y270",
                // "5Z", "5Z90", 
                // "5S", "5S90"

            }, groupSize, puzzleWidth, puzzleHeight, areaMin, areaMax, monominoLimit, mustHavePieceOfSize, removeSymmetries);

            System.out.println("Total groups to search: " + allGroups.size());
            Thread.sleep(1000);
            // Create a CSV file to hold search data
            StringBuilder csvBuilderGlobal = new StringBuilder();
            csvBuilderGlobal.append("GroupID,PiecesA,PiecesB,Packings,Islands,MaxEstimatedDiameter,StartStateA,StartStateB,EndStateA,EndStateB,TimeTaken(ms)\n");

            boolean sampleOnly = false;
            int sampleSizePerThread = 5000;
            int csvRowCheckpoint = 1000; // print progress every N rows

            int packingsLowerLimit = 50; // Exclude puzzles with fewer than this many packings
            int packingsUpperLimit = 50000000; // Only search for up to this many packings per group
            int interestingDiameterThreshold = 20;
            boolean connectedAB = false;
            
            // only make true under special circumstances
            // solution numbers may be wrong for groups with identical pieces
            // primarily this is useful for high piece counts and lots of monominos,
            // where the only trustworthy numbers are for unique larger pieces,
            // and the monominos would otherwise blow up the search time
            boolean deduplicateIdenticalPiecePermutations = false;

            int globalStartIndex = 0;
            int globalEndIndex = allGroups.size();

            // truncate allGroups to between globalStartIndex and globalEndIndex
            ArrayList<PieceGrouper.EntangledGroupPair> groupsToSearch = new ArrayList<>(allGroups.subList(globalStartIndex, Math.min(globalEndIndex, allGroups.size())));

            // reverse order of arraylist
            //java.util.Collections.reverse(groupsToSearch);

            // Clear all console lines for progress bars
            System.out.print("\033[2J"); // Clear screen
            System.out.print("\033[H");  // Move cursor to top-left
            System.out.flush();
            // Split allGroups into individual pieces and multithread the searches, then concatenate individual CSVs into one large CSV
            long globalStartTime = System.nanoTime();
            int threadCount = 8;
            Thread[] threads = new Thread[threadCount];
            StringBuilder[] csvBuilders = new StringBuilder[threadCount];
            for(int t = 0; t < threadCount; t++){
                final int threadIndex = t;
                int startIndex = sampleOnly ? t * sampleSizePerThread : threadIndex;
                int endIndex = sampleOnly ? startIndex + sampleSizePerThread : groupsToSearch.size();
                int totalGroups = sampleOnly
                    ? Math.max(0, endIndex - startIndex)
                    : startIndex >= groupsToSearch.size() ? 0 : ((groupsToSearch.size() - 1 - startIndex) / threadCount) + 1;
                if(sampleOnly)
                    System.out.println("Thread " + (threadIndex + 1) + " processing groups " + startIndex + " to " + (endIndex - 1));
                else
                    System.out.println("Thread " + (threadIndex + 1) + " processing every " + threadCount + "th group starting at index " + startIndex);
                csvBuilders[threadIndex] = new StringBuilder();
                threads[threadIndex] = new Thread(() -> {
                    int it = startIndex;
                    for (int groupNum = 1; groupNum <= totalGroups && it < groupsToSearch.size(); groupNum++) {
                        PieceGrouper.EntangledGroupPair testGroup = groupsToSearch.get(it);
                        //csvBuilders[threadIndex].append(groupNum + "/" + totalGroups + ",");
                        EntangledGroupSearcher searcher = new EntangledGroupSearcher(
                            testGroup,
                            puzzleWidth,
                            puzzleHeight,
                            packingsLowerLimit,
                            packingsUpperLimit,
                            interestingDiameterThreshold,
                            connectedAB,
                            blockedBitboard,
                            deduplicateIdenticalPiecePermutations
                        );
                        //System.out.print("\nExploring group " + (groupNum) + "/" + totalGroups + " | ");
                        String s = groupNum + "/" + totalGroups + ",";
                        csvBuilders[threadIndex].append(searcher.explore(s));

                        it += sampleOnly ? -it + (int) (Math.random() * groupsToSearch.size()) : threadCount;
                        // Print progress bar for this thread on its own line
                        if (groupNum % 10 == 0 || groupNum == totalGroups) {
                            int percent = (int) ((groupNum * 100.0) / totalGroups);
                            StringBuilder bar = new StringBuilder();
                            bar.append("\033[").append(threadIndex + 1).append(";0H"); // Move cursor to line
                            bar.append("Thread ").append(threadIndex + 1).append(": [");
                            int barLen = 50;
                            int filled = (int) (barLen * percent / 100.0);
                            for (int j = 0; j < barLen; j++) {
                                bar.append(j < filled ? ':' : 2*j < percent ? '.' : ' '); // assumes barLen = 50
                            }
                            bar.append("] ").append(percent).append("% (").append(groupNum).append("/").append(totalGroups).append(")");
                            System.out.print(bar.toString());
                            System.out.flush();
                        }
                    }
                    // Save single-thread CSV for safety
                    try {
                        java.nio.file.Files.write(java.nio.file.Paths.get("group_search_results_thread" + (threadIndex + 1) + ".csv"), csvBuilders[threadIndex].toString().getBytes());
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                });
                threads[threadIndex].start();
            }

            // Combine all CSV builders into one
            for(int t = 0; t < threadCount; t++){
                threads[t].join();
                csvBuilderGlobal.append(csvBuilders[t].toString());
            }

            // Write to file
            java.nio.file.Files.write(java.nio.file.Paths.get("group_search_results.csv"), csvBuilderGlobal.toString().getBytes());

            // Move cursor below progress bars
            System.out.print("\033[" + (threadCount + 1) + ";0H");
            long globalEndTime = System.nanoTime();
            int seconds = (int) ((globalEndTime - globalStartTime) / 1e9);
            int hours = seconds / 3600;
            int minutes = (seconds % 3600) / 60;
            seconds = seconds % 60;
            System.out.println("Total time for all searches: " + hours + " hours, " + minutes + " minutes, " + seconds + " seconds");
            // filter the CSV to only include lines with diameter >= 100
            CSVFilter.filterByDiameter(100, "group_search_results.csv");
            CSVFilter.filterByDiameter(200, "group_search_results.csv");



            // // Old single threaded code
            // int groupNum = 1;
            // for(long[] testGroup : allGroupsList) {
            //     csvBuilder.append(groupNum + "/" + allGroupsList.size() + ",");
            //     GroupSearcher searcher = new GroupSearcher(testGroup, 5, 5);
            //     System.out.print("\nExploring group " + (groupNum++) + "/" + allGroups.size() + " | ");
            //     csvBuilder.append(searcher.explore());
            //     // Append results of search to CSV
            // }

        // #endregion MAIN SEARCH

    }


    private static int[][] stringToGrid(String state){
        // State is in format: "1 2 3 | 1 2 0 | 1 1 0" etc.
        // Rows are separated by '|', values in rows by spaces
        String[] rows = state.split("\\|");
        int height = rows.length;
        int width = rows[0].trim().split(" ").length;
        int[][] grid = new int[height][width];
        for(int r = 0; r < height; r++){
            String[] vals = rows[r].trim().split(" ");
            for(int c = 0; c < width; c++){
                grid[r][c] = Integer.parseInt(vals[c]);
            }
        }
        return grid;
    }
}
