// Duplicate of BitSolverV7 but with a few added tricks
// (see comments around new code) to generate a CSV of
// the degrees of each state for every non-backtracking
// solution. Also generates two numbers to summarize the
// difficulty score: average degree (across all vertices
// of all solutions) and average number of "tightropes" 
// (strings of states w/ degree 2) across all solutions.

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;

public class SolutionEvaluator {

    // Helper class for fast hashing and equality checking of states
    private static final class State {
        final long[] pieces;
        private final int hash;

        State(long[] pieces) {
            this.pieces = pieces;
            this.hash = Arrays.hashCode(pieces);
        }

        @Override
        public boolean equals(Object o) {
            return (o instanceof State) && Arrays.equals(pieces, ((State)o).pieces);
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    // #region DECLARATIONS

        // Array of bitboards for each piece
        // Since it's a long, the maximum puzzle size is 64 cells.
        private long[] puzzle;
        private int width, height, pieceCount, winningPiece;
        private long winningBitboard;

        // Computed bitboards
        private long[][] pieceMoveBitboards;
        private long[] orthoBitboards;

        // Other computed piece data
        private int[] pieceHeight, pieceWidth;
        private int[] pieceTrailingZeroOffset;

        // Data structures for holding states
        private HashSet<State> allStates;
        private ArrayList<HashSet<State>> statesByDepth;
        private ArrayList<State> solutionPath;

        // NEW FOR SOLUTION EVALUATOR
        // quick and dirty, likely inefficient
        // better would be to add info to State class (hold parent State, degree, ID, etc)
        // make it exist, then make it good!
        private HashMap<State, Integer> stateDegrees = new HashMap<>();
        private HashMap<State, Integer> stateIDs = new HashMap<>();
        private HashMap<Integer, State> idToState = new HashMap<>();
        private HashMap<State, Integer> stateParentIDs = new HashMap<>();
        private HashMap<State, Boolean> pathAlreadySolved = new HashMap<>();
        private HashSet<State> justsolvedStates = new HashSet<>();
        private ArrayList<ArrayList<Integer>> solutionPathsDegrees = new ArrayList<>();
        private HashSet<State> solutionPathStates = new HashSet<>();

        // Timer
        private long startTime, endTime;
        private boolean solved = false;

    // #endregion DECLARATIONS

    // Code to make solver from grid and winning bitboard (hybrid approach until I rewrite grid parsing)
    public SolutionEvaluator(int[][] grid, int pieceCount, int winningPiece, long winningBitboard){
        
        // #region BASIC INFO

            height = grid.length;
            width = grid[0].length;

            this.winningBitboard = winningBitboard;
            this.winningPiece = winningPiece;

            // Knowing the piece count in advance
            // allows us to have walls/obstacles 
            // represented by larger numbers
            this.pieceCount = pieceCount;
            puzzle = new long[pieceCount];

        // #endregion BASIC INFO

        // #region INIT PIECES

            // #region INIT BBOXES
                // Arrays to track the bounding box of each piece 
                // to help with precomputing possible moves
                int[] minRow = new int[pieceCount];
                int[] minCol = new int[pieceCount];
                int[] maxRow = new int[pieceCount];
                int[] maxCol = new int[pieceCount];
                for(int i = 0; i < pieceCount; i++){
                    minRow[i] = height;
                    minCol[i] = width;
                    maxRow[i] = 0;
                    maxCol[i] = 0;
                }
            // #endregion INIT BBOXES

            // #region POPULATE PIECES
                // Iterate through the grid and set bits
                // At the same time, find the bounding box of each piece
                for(int r = 0; r < height; r++){
                    for(int c = 0; c < width; c++){
                        int piece = grid[r][c];
                        if(piece != 0 && piece <= pieceCount){
                            puzzle[piece-1] |= (1L << (r*width + c));
                            if(r < minRow[piece-1]) minRow[piece-1] = r;
                            if(c < minCol[piece-1]) minCol[piece-1] = c;
                            if(r > maxRow[piece-1]) maxRow[piece-1] = r;
                            if(c > maxCol[piece-1]) maxCol[piece-1] = c;
                        }
                    }
                }
            // #endregion POPULATE PIECES

            // #region PRECOMP MOVES

                // Initialize arrays to hold piece dimensions and trailing zero offsets
                this.pieceWidth = new int[pieceCount];
                this.pieceHeight = new int[pieceCount];
                this.pieceTrailingZeroOffset = new int[pieceCount];

                // Precompute all possible moves for each piece based on their bounding boxes and the puzzle dimensions
                // This will help speed up move generation later
                // With this implementation, pieceMoveBitboards is rather sparse, 
                // but if I only store valid moves I'll have to backcalculate positions later which is a pain.
                this.pieceMoveBitboards = new long[pieceCount][width * height];
                for(int i = 0; i < pieceCount; i++){
                    int pieceWidth = maxCol[i] - minCol[i] + 1;
                    int pieceHeight = maxRow[i] - minRow[i] + 1;
                    this.pieceWidth[i] = pieceWidth;
                    this.pieceHeight[i] = pieceHeight;

                    // Compute trailing zero offset for each piece
                    // This allows rapid indexing of starting position, correcting for any concave features of the piece
                    // that would mean its leading 1 is not at the top-left of its bounding box.
                    this.pieceTrailingZeroOffset[i] = Long.numberOfTrailingZeros(puzzle[i]) - (width * minRow[i] + minCol[i]);


                    // All positions from piece in top left to bottom right
                    for(int r = 0; r < height + 1 - pieceHeight; r++){
                        for(int c = 0; c < width + 1 - pieceWidth; c++){
                            int shift = width * (r - minRow[i]) + (c - minCol[i]);
                            if(shift < 0) // can't shift by negative amount
                                pieceMoveBitboards[i][r*width + c] = puzzle[i] >> -shift;
                            else
                                pieceMoveBitboards[i][r*width + c] = puzzle[i] << shift;
                        }
                    }
                }

            // #endregion PRECOMP MOVES

        // #endregion INIT PIECES

        // #region INIT DATA STRUCTS

            allStates = new HashSet<State>();
            allStates.add(new State(puzzle));

            statesByDepth = new ArrayList<HashSet<State>>();
            HashSet<State> initialSet = new HashSet<State>();
            initialSet.add(new State(puzzle));
            statesByDepth.add(initialSet);

            solutionPath = new ArrayList<State>();
            solutionPath.add(new State(puzzle));

            // NEW FOR SOLUTION EVALUATOR
            stateIDs.put(new State(puzzle), 0);
            idToState.put(0, new State(puzzle));
            stateParentIDs.put(new State(puzzle), -1);
            pathAlreadySolved.put(new State(puzzle), false);

        // #endregion INIT DATA STRUCTS

        // #region ORTHO BITBOARDS

            // Precompute orthogonal bitboards for each coordinate
            // This will allow fast bitwise operations to check for orthogonal
            // reachability from one position to another
            orthoBitboards = new long[width * height];
            for(int r = 0; r < height; r++){
                for(int c = 0; c < width; c++){
                    long bitboard = 0L;
                    if(r > 0)           bitboard |= (1L << ((r-1)*width + c));
                    if(r < height - 1)  bitboard |= (1L << ((r+1)*width + c));
                    if(c > 0)           bitboard |= (1L << (r*width + (c-1)));
                    if(c < width - 1)   bitboard |= (1L << (r*width + (c+1)));
                    orthoBitboards[r*width + c] = bitboard;
                }
            }

        // #endregion ORTHO BITBOARDS
    
    }

    // Code to make the puzzle from 2D int array
    public SolutionEvaluator(int[][] grid, int pieceCount, int[][] winningGrid, int winningPiece){
        
        // #region BASIC INFO

            height = grid.length;
            width = grid[0].length;

            // Knowing the piece count in advance
            // allows us to have walls/obstacles 
            // represented by larger numbers
            this.pieceCount = pieceCount;
            puzzle = new long[pieceCount];

        // #endregion BASIC INFO

        // #region INIT PIECES

            // #region INIT BBOXES
                // Arrays to track the bounding box of each piece 
                // to help with precomputing possible moves
                int[] minRow = new int[pieceCount];
                int[] minCol = new int[pieceCount];
                int[] maxRow = new int[pieceCount];
                int[] maxCol = new int[pieceCount];
                for(int i = 0; i < pieceCount; i++){
                    minRow[i] = height;
                    minCol[i] = width;
                    maxRow[i] = 0;
                    maxCol[i] = 0;
                }
            // #endregion INIT BBOXES

            // #region POPULATE PIECES
                // Iterate through the grid and set bits
                // At the same time, find the bounding box of each piece
                for(int r = 0; r < height; r++){
                    for(int c = 0; c < width; c++){
                        int piece = grid[r][c];
                        if(piece != 0 && piece <= pieceCount){
                            puzzle[piece-1] |= (1L << (r*width + c));
                            if(r < minRow[piece-1]) minRow[piece-1] = r;
                            if(c < minCol[piece-1]) minCol[piece-1] = c;
                            if(r > maxRow[piece-1]) maxRow[piece-1] = r;
                            if(c > maxCol[piece-1]) maxCol[piece-1] = c;
                        }
                    }
                }
            // #endregion POPULATE PIECES

            // #region PRECOMP MOVES

                // Initialize arrays to hold piece dimensions and trailing zero offsets
                this.pieceWidth = new int[pieceCount];
                this.pieceHeight = new int[pieceCount];
                this.pieceTrailingZeroOffset = new int[pieceCount];

                // Precompute all possible moves for each piece based on their bounding boxes and the puzzle dimensions
                // This will help speed up move generation later
                // With this implementation, pieceMoveBitboards is rather sparse, 
                // but if I only store valid moves I'll have to backcalculate positions later which is a pain.
                this.pieceMoveBitboards = new long[pieceCount][width * height];
                for(int i = 0; i < pieceCount; i++){
                    int pieceWidth = maxCol[i] - minCol[i] + 1;
                    int pieceHeight = maxRow[i] - minRow[i] + 1;
                    this.pieceWidth[i] = pieceWidth;
                    this.pieceHeight[i] = pieceHeight;

                    // Compute trailing zero offset for each piece
                    // This allows rapid indexing of starting position, correcting for any concave features of the piece
                    // that would mean its leading 1 is not at the top-left of its bounding box.
                    this.pieceTrailingZeroOffset[i] = Long.numberOfTrailingZeros(puzzle[i]) - (width * minRow[i] + minCol[i]);


                    // All positions from piece in top left to bottom right
                    for(int r = 0; r < height + 1 - pieceHeight; r++){
                        for(int c = 0; c < width + 1 - pieceWidth; c++){
                            int shift = width * (r - minRow[i]) + (c - minCol[i]);
                            if(shift < 0) // can't shift by negative amount
                                pieceMoveBitboards[i][r*width + c] = puzzle[i] >> -shift;
                            else
                                pieceMoveBitboards[i][r*width + c] = puzzle[i] << shift;
                        }
                    }
                }

            // #endregion PRECOMP MOVES

        // #endregion INIT PIECES

        // #region INIT DATA STRUCTS

            allStates = new HashSet<State>();
            allStates.add(new State(puzzle));

            statesByDepth = new ArrayList<HashSet<State>>();
            HashSet<State> initialSet = new HashSet<State>();
            initialSet.add(new State(puzzle));
            statesByDepth.add(initialSet);

            solutionPath = new ArrayList<State>();
            solutionPath.add(new State(puzzle));

            // NEW FOR SOLUTION EVALUATOR
            stateIDs.put(new State(puzzle), 0);
            idToState.put(0, new State(puzzle));
            stateParentIDs.put(new State(puzzle), -1);
            pathAlreadySolved.put(new State(puzzle), false);

        // #endregion INIT DATA STRUCTS

        // #region ORTHO BITBOARDS

            // Precompute orthogonal bitboards for each coordinate
            // This will allow fast bitwise operations to check for orthogonal
            // reachability from one position to another
            orthoBitboards = new long[width * height];
            for(int r = 0; r < height; r++){
                for(int c = 0; c < width; c++){
                    long bitboard = 0L;
                    if(r > 0)           bitboard |= (1L << ((r-1)*width + c));
                    if(r < height - 1)  bitboard |= (1L << ((r+1)*width + c));
                    if(c > 0)           bitboard |= (1L << (r*width + (c-1)));
                    if(c < width - 1)   bitboard |= (1L << (r*width + (c+1)));
                    orthoBitboards[r*width + c] = bitboard;
                }
            }

        // #endregion ORTHO BITBOARDS

        // #region INIT WIN STATE

            // Make winning bitboard based on grid
            this.winningPiece = winningPiece;
            this.winningBitboard = 0L;
            for(int r = 0; r < height; r++){
                for(int c = 0; c < width; c++){
                    if(winningGrid[r][c] == winningPiece){
                        this.winningBitboard |= (1L << (r*width + c));
                    }
                }
            }

        // #endregion INIT WIN STATE
    
    }

    public double[] solve(){

        int depth = 0;
        solved = false;

        // Check if puzzle is already solved
        if((puzzle[winningPiece - 1] == winningBitboard)){
            return null;
        }

        while(true){
            
            // Add layer for new states found at this depth
            statesByDepth.add(new HashSet<State>());

            // If there are states to check at this depth, check them
            if(statesByDepth.get(depth).isEmpty()){
                break;
            }else{
                for(State state : statesByDepth.get(depth)){
                    findMoves(state, depth, stateIDs.get(state), pathAlreadySolved.get(state));
                    if(solved) break;
                }
            }
            depth++;

        }

        // Create new CSV of solution paths and their degrees



        double totalTightropeCounts = 0;
        double totalAverageDegree = 0.0;
        for(State s : justsolvedStates){

            double[] result = addSolutionPath(s);
            totalTightropeCounts += result[0];
            totalAverageDegree += result[1];
        }

        double totalSolutions = justsolvedStates.size();
        double solutionStateCount = solutionPathStates.size();  
        double totalStateCount = allStates.size();
        double statePercentage = 100 * solutionStateCount / totalStateCount;
        double averageTightropeCount = totalTightropeCounts / totalSolutions;
        double averageDegree = totalAverageDegree / totalSolutions;

        double analytics[] = new double[6];
        analytics[0] = totalSolutions;
        analytics[1] = solutionStateCount;
        analytics[2] = totalStateCount;
        analytics[3] = statePercentage;
        analytics[4] = averageTightropeCount;
        analytics[5] = averageDegree;

        System.out.println("Number of solutions found: " + totalSolutions);
        System.out.println("Total states explored: " + totalStateCount);
        System.out.println("Percentage of solution-involved states out of total states: 100 * " + solutionStateCount + " / " + totalStateCount + " = " + statePercentage);
        System.out.println("Average tightrope count: " + averageTightropeCount);
        System.out.println("Average degree: " + averageDegree);

        // Create new CSV file where the first column is depth and each subsequent column is the degree of each state in the solution path
        String csvString = "";
        // find max length of solution paths
        int maxLength = 0;
        for(ArrayList<Integer> degrees : solutionPathsDegrees){
            maxLength = Math.max(maxLength, degrees.size());
        }

        for(int i = 0; i < maxLength; i++){
            StringBuilder sb = new StringBuilder();
            sb.append(i);
            for(ArrayList<Integer> degrees : solutionPathsDegrees){
                if(i < degrees.size()){
                    sb.append(",").append(degrees.get(i));
                }else{
                    sb.append(",0");
                }
            }
            csvString += sb.toString() + "\n";
        }

        try {
            java.nio.file.Files.write(java.nio.file.Paths.get("solutions_degrees.csv"), csvString.getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }

        return analytics;

    }

    // NEW FOR SOLUTION EVALUATOR:
    // 1. Modify findMoves to count degree of each state + store in map
    // 2. Pass in "solved" boolean to avoid re-counting solutions from a path that has already found one
    // 3. Solving no longer breaks the loop--just adds a solution to the CSV
    public void findMoves(State state, int depth, int id, boolean solved){

        // Bitboard of all other pieces for fast collision checking
        long otherPieces = 0L;
        for(int piece = 0; piece < pieceCount; piece++)
            otherPieces |= state.pieces[piece];

        // NEW FOR SOLUTION EVALUATOR
        int degree = 0;

        // Add new moves for each piece
        for(int piece = 0; piece < pieceCount; piece++){
            
            // #region CANDIDATE MOVES

                // Mask out the current piece from otherPieces
                otherPieces ^= state.pieces[piece];

                // Find starting position of piece i
                int startCoord = Long.numberOfTrailingZeros(state.pieces[piece]) - pieceTrailingZeroOffset[piece];
                
                // Start with original position to ensure it's included in flood fill (will be masked away later)
                long candidateMoves = (1L << startCoord);

                // Grab local piece dimensions
                int pieceHeight = this.pieceHeight[piece];
                int pieceWidth = this.pieceWidth[piece];

                // Iterate through all precomputed moves for this piece
                for(int r = 0; r < height + 1 - pieceHeight; r++){
                    for(int c = 0; c < width + 1 - pieceWidth; c++){

                        int coord = r*width + c;
                        if(coord == startCoord) continue; // Skip original position, already included

                        // If move is not original position, try it out
                        long move = pieceMoveBitboards[piece][coord];

                        // If not a collision, add to candidates
                        if((move & otherPieces) == 0)
                            candidateMoves |= 1L << coord;
                    }
                }

            // #endregion CANDIDATE MOVES

            // #region CONNECTEDNESS
                long connectedRegion = (1L << startCoord);              // This will be our output of moves reachable from start
                long frontier = (orthoBitboards[startCoord] & candidateMoves); // Initialize frontier with valid orthogonal neighbors of the starting position
                while(frontier != 0L){                                  // While there are still positions to explore

                    connectedRegion |= frontier;                        // Add frontier (already legal/filtered!) to connected region
                    long newFrontier = 0L;                              // To hold the next layer of frontier positions
                    long f = frontier;                                  // Copy of frontier to iterate/modify
                    while(f != 0L){
                        int nextCoord = Long.numberOfTrailingZeros(f);  // Get the next position from the frontier
                        newFrontier |= (orthoBitboards[nextCoord]);     // Add orthogonal neighbors to the new frontier
                        f &= (f - 1);                                   // Remove from frontier
                    }

                    newFrontier &= candidateMoves;                      // Keep only positions that are valid candidate moves
                    newFrontier &= ~connectedRegion;                    // Remove positions already in connected region
                    frontier = newFrontier;                             // Update frontier for next iteration

                }
                
                // Mask away original piece position -- we only want new states!
                connectedRegion ^= (1L << startCoord);

            // #endregion CONNECTEDNESS

            // #region ADDING STATES
                // For every 1 bit in connectedRegion, add state if new
                for(int r = 0; r < height + 1 - pieceHeight; r++){
                    for(int c = 0; c < width + 1 - pieceWidth; c++){
                        long bit = 1L << (r*width + c);
                        if((connectedRegion & bit) != 0){
                            long[] newStatePieces = state.pieces.clone();
                            newStatePieces[piece] = pieceMoveBitboards[piece][r*width + c];
                            State newState = new State(newStatePieces);
                            degree++; // NEW FOR SOLUTION EVALUATOR (increment degree regardless of whether state is new)
                            // Add newState to data structures if not already present
                            if (allStates.add(newState)) {
                                // NEW FOR SOLUTION EVALUATOR
                                int newID = allStates.size() - 1;
                                stateIDs.put(newState, newID);
                                idToState.put(newID, newState);
                                stateParentIDs.put(newState, id);

                                // CHECK FOR JUST-SOLVED
                                if((newState.pieces[piece] == winningBitboard) && (piece == winningPiece - 1) && !solved){
                                    System.out.println("SOLVED after depth " + (depth + 1) + "!");
                                    endTime = System.currentTimeMillis();
                                    System.out.println("Time taken: " + (endTime - startTime) + " ms");
                                    solved = true;
                                    justsolvedStates.add(newState);
                                }
                                statesByDepth.get(depth + 1).add(newState);

                                // NEW FOR SOLUTION EVALUATOR
                                pathAlreadySolved.put(newState, solved);
                            }
                        }
                    }
                }
                // Finally, add the piece back to otherPieces
                otherPieces |= state.pieces[piece];

            // #endregion ADDING STATES

            stateDegrees.put(state, degree); // NEW FOR SOLUTION EVALUATOR
        }
    }

    // NEW FOR SOLUTION EVALUATOR
    private double[] addSolutionPath(State solvedState){
        ArrayList<State> path = new ArrayList<>();
        ArrayList<Integer> degrees = new ArrayList<>();
  
        int currentID = stateIDs.get(solvedState);
        int lastDegree = stateDegrees.get(solvedState);
        int tightropeCount = 0;
        int degreeSum = 0;
        while(currentID != -1){
            State currentState = idToState.get(currentID);
            path.add(0, currentState); // add to front of list
            solutionPathStates.add(currentState);
            int currentDegree = stateDegrees.get(currentState);
            if(currentDegree != 2 && lastDegree == 2)
                tightropeCount++;
            degrees.add(0, currentDegree); // add to front of list
            degreeSum += currentDegree;

            // Move to parent
            lastDegree = currentDegree;
            currentID = stateParentIDs.get(currentState);
        }

        solutionPathsDegrees.add(degrees);
        
        return new double[]{tightropeCount, degreeSum / (double) degrees.size()};
    }

    // #region DEBUG/PRINTING
        private void print(long[] state){
            for(int r = 0; r < height; r++){
                for(int c = 0; c < width; c++){
                    int piece = 0;
                    long bit = 1L << (r*width + c);
                    for(int i = 0; i < pieceCount; i++){
                        if((state[i] & bit) != 0){
                            piece = i + 1;
                            break;
                        }
                    }
                    System.out.print(piece + " ");
                }
                System.out.println();
            }
            System.out.println();
        }

        private void print(long bitboard){
            for(int r = 0; r < height; r++){
                for(int c = 0; c < width; c++){
                    long bit = 1L << (r*width + c);
                    if((bitboard & bit) != 0){
                        System.out.print("1 ");
                    }else{
                        System.out.print("0 ");
                    }
                }
                System.out.println();
            }
            System.out.println();
        }
    // #endregion DEBUG/PRINTING

}
