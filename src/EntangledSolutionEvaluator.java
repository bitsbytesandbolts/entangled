import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;

public class EntangledSolutionEvaluator {

    private static final class StatePair {
        final long stateA;
        final long stateB;
        private final int hash;

        StatePair(long stateA, long stateB) {
            this.stateA = stateA;
            this.stateB = stateB;

            long mixed = stateA * 0x9e3779b97f4a7c15L ^ Long.rotateLeft(stateB, 23);
            mixed ^= (mixed >>> 33);
            mixed *= 0xff51afd7ed558ccdL;
            mixed ^= (mixed >>> 33);
            mixed *= 0xc4ceb9fe1a85ec53L;
            mixed ^= (mixed >>> 33);
            this.hash = (int) mixed;
        }

        @Override
        public boolean equals(Object other) {
            if (!(other instanceof StatePair)) {
                return false;
            }
            StatePair statePair = (StatePair) other;
            return stateA == statePair.stateA && stateB == statePair.stateB;
        }

        @Override
        public int hashCode() {
            return hash;
        }
    }

    private static final class BoardData {
        final long[] normalizedPieceShapes;
        final long[][] pieceMoveBitboards;
        final int[][][] pieceStepNeighbors;
        final long blockedBitboard;
        final long initialState;

        BoardData(
            long[] normalizedPieceShapes,
            long[][] pieceMoveBitboards,
            int[][][] pieceStepNeighbors,
            long blockedBitboard,
            long initialState
        ) {
            this.normalizedPieceShapes = normalizedPieceShapes;
            this.pieceMoveBitboards = pieceMoveBitboards;
            this.pieceStepNeighbors = pieceStepNeighbors;
            this.blockedBitboard = blockedBitboard;
            this.initialState = initialState;
        }
    }

    private final int width;
    private final int height;
    private final int cellCount;
    private final int pairCoordCount;
    private final int pieceCount;
    private final int winningPieceIndex;
    private final long winningBitboardA;
    private final long winningBitboardB;
    private final boolean deduplicateIdenticalPiecePermutations;

    private final long[] coordBits;
    private final int coordBitWidth;
    private final long coordMask;
    private final int[] pieceStateShifts;
    private final long[] pieceStateClearMasks;
    private final long[][] pieceStateBits;

    private final BoardData boardA;
    private final BoardData boardB;
    private final int[][] identicalPiecePairGroups;

    private final HashSet<StatePair> allStates;
    private final ArrayList<HashSet<StatePair>> statesByDepth;
    private final HashMap<StatePair, Integer> stateDegrees;
    private final HashMap<StatePair, Integer> stateIDs;
    private final HashMap<Integer, StatePair> idToState;
    private final HashMap<StatePair, Integer> stateParentIDs;
    private final HashMap<StatePair, Boolean> pathAlreadySolved;
    private final HashSet<StatePair> justSolvedStates;
    private final ArrayList<ArrayList<Integer>> solutionPathsDegrees;
    private final HashSet<StatePair> solutionPathStates;

    private final int[] pairedCoordVisitGenerations;
    private int pairedCoordVisitGeneration;
    private final int[] pairedCoordQueueA;
    private final int[] pairedCoordQueueB;

    private long startTime;
    private long endTime;
    public EntangledSolutionEvaluator(
        int[][] gridA,
        int[][] gridB,
        int pieceCount,
        int winningPiece,
        long winningBitboardA,
        long winningBitboardB
    ) {
        this(gridA, gridB, pieceCount, winningPiece, winningBitboardA, winningBitboardB, false);
    }

    public EntangledSolutionEvaluator(
        int[][] gridA,
        int[][] gridB,
        int pieceCount,
        int winningPiece,
        long winningBitboardA,
        long winningBitboardB,
        boolean deduplicateIdenticalPiecePermutations
    ) {
        this.height = gridA.length;
        this.width = gridA[0].length;
        this.cellCount = width * height;
        this.pairCoordCount = cellCount * cellCount;
        this.pieceCount = pieceCount;
        this.winningPieceIndex = winningPiece - 1;
        this.winningBitboardA = winningBitboardA;
        this.winningBitboardB = winningBitboardB;
        this.deduplicateIdenticalPiecePermutations = deduplicateIdenticalPiecePermutations;

        coordBits = new long[cellCount];
        for (int coord = 0; coord < cellCount; coord++) {
            coordBits[coord] = 1L << coord;
        }

        coordBitWidth = bitsNeeded(cellCount - 1);
        if ((long) coordBitWidth * pieceCount > 64L) {
            throw new IllegalArgumentException("Packed entangled evaluator state requires more than 64 bits per puzzle");
        }
        coordMask = (1L << coordBitWidth) - 1L;

        pieceStateShifts = new int[pieceCount];
        pieceStateClearMasks = new long[pieceCount];
        pieceStateBits = new long[pieceCount][cellCount];
        for (int piece = 0; piece < pieceCount; piece++) {
            int shift = piece * coordBitWidth;
            pieceStateShifts[piece] = shift;
            pieceStateClearMasks[piece] = ~(coordMask << shift);
            for (int coord = 0; coord < cellCount; coord++) {
                pieceStateBits[piece][coord] = ((long) coord) << shift;
            }
        }

        boardA = createBoardData(gridA);
        boardB = createBoardData(gridB);
    identicalPiecePairGroups = buildIdenticalPiecePairGroups();

    StatePair initialState = canonicalizeStateIfNeeded(boardA.initialState, boardB.initialState);
        allStates = new HashSet<>();
        allStates.add(initialState);

        statesByDepth = new ArrayList<>();
        HashSet<StatePair> initialSet = new HashSet<>();
        initialSet.add(initialState);
        statesByDepth.add(initialSet);

        stateDegrees = new HashMap<>();
        stateIDs = new HashMap<>();
        idToState = new HashMap<>();
        stateParentIDs = new HashMap<>();
        pathAlreadySolved = new HashMap<>();
        justSolvedStates = new HashSet<>();
        solutionPathsDegrees = new ArrayList<>();
        solutionPathStates = new HashSet<>();

        stateIDs.put(initialState, 0);
        idToState.put(0, initialState);
        stateParentIDs.put(initialState, -1);
        pathAlreadySolved.put(initialState, false);

        pairedCoordVisitGenerations = new int[pairCoordCount];
        pairedCoordVisitGeneration = 1;
        pairedCoordQueueA = new int[pairCoordCount];
        pairedCoordQueueB = new int[pairCoordCount];
    }

    public double[] solve() {
        startTime = System.currentTimeMillis();
        int depth = 0;
        StatePair initialState = idToState.get(0);

        if (isSolvedState(initialState)) {
            return null;
        }

        while (true) {
            statesByDepth.add(new HashSet<>());
            if (statesByDepth.get(depth).isEmpty()) {
                break;
            }

            for (StatePair state : statesByDepth.get(depth)) {
                findMoves(state, depth, stateIDs.get(state), pathAlreadySolved.get(state));
            }
            depth++;
        }

        double totalTightropeCounts = 0.0;
        double totalAverageDegree = 0.0;
        for (StatePair state : justSolvedStates) {
            double[] result = addSolutionPath(state);
            totalTightropeCounts += result[0];
            totalAverageDegree += result[1];
        }

        double totalSolutions = justSolvedStates.size();
        double solutionStateCount = solutionPathStates.size();
        double totalStateCount = allStates.size();
        double statePercentage = totalStateCount == 0.0 ? 0.0 : 100.0 * solutionStateCount / totalStateCount;
        double averageTightropeCount = totalSolutions == 0.0 ? 0.0 : totalTightropeCounts / totalSolutions;
        double averageDegree = totalSolutions == 0.0 ? 0.0 : totalAverageDegree / totalSolutions;

        double[] analytics = new double[6];
        analytics[0] = totalSolutions;
        analytics[1] = solutionStateCount;
        analytics[2] = totalStateCount;
        analytics[3] = statePercentage;
        analytics[4] = averageTightropeCount;
        analytics[5] = averageDegree;

        System.out.println("Number of entangled solutions found: " + totalSolutions);
        System.out.println("Total entangled states explored: " + totalStateCount);
        System.out.println("Percentage of solution-involved entangled states: " + statePercentage);
        System.out.println("Average tightrope count: " + averageTightropeCount);
        System.out.println("Average degree: " + averageDegree);

        writeSolutionDegreesCsv();
        return analytics;
    }

    private void findMoves(StatePair state, int depth, int id, boolean solvedOnPath) {
        long occupiedA = occupiedBitboard(state.stateA, boardA);
        long occupiedB = occupiedBitboard(state.stateB, boardB);
        int degree = 0;
        boolean pathSolved = solvedOnPath;
        HashSet<StatePair> uniqueNeighbors = deduplicateIdenticalPiecePermutations ? new HashSet<>() : null;

        for (int piece = 0; piece < pieceCount; piece++) {
            int startCoordA = getCoord(state.stateA, piece);
            int startCoordB = getCoord(state.stateB, piece);
            long currentPieceBitboardA = boardA.pieceMoveBitboards[piece][startCoordA];
            long currentPieceBitboardB = boardB.pieceMoveBitboards[piece][startCoordB];
            long blockersA = occupiedA ^ currentPieceBitboardA;
            long blockersB = occupiedB ^ currentPieceBitboardB;

            int visitGeneration = nextPairCoordVisitGeneration();
            int queueHead = 0;
            int queueTail = 1;
            int startPairCoord = startCoordA * cellCount + startCoordB;
            pairedCoordVisitGenerations[startPairCoord] = visitGeneration;
            pairedCoordQueueA[0] = startCoordA;
            pairedCoordQueueB[0] = startCoordB;

            while (queueHead < queueTail) {
                int coordA = pairedCoordQueueA[queueHead];
                int coordB = pairedCoordQueueB[queueHead];
                queueHead++;

                for (int direction = 0; direction < 4; direction++) {
                    int nextCoordA = boardA.pieceStepNeighbors[piece][coordA][direction];
                    int nextCoordB = boardB.pieceStepNeighbors[piece][coordB][direction];
                    if (nextCoordA == -1 || nextCoordB == -1) {
                        continue;
                    }
                    if (!preservesRelativeOffset(coordA, coordB, nextCoordA, nextCoordB)) {
                        continue;
                    }

                    long nextPieceBitboardA = boardA.pieceMoveBitboards[piece][nextCoordA];
                    long nextPieceBitboardB = boardB.pieceMoveBitboards[piece][nextCoordB];
                    if ((nextPieceBitboardA & blockersA) != 0L || (nextPieceBitboardB & blockersB) != 0L) {
                        continue;
                    }

                    int nextPairCoord = nextCoordA * cellCount + nextCoordB;
                    if (pairedCoordVisitGenerations[nextPairCoord] == visitGeneration) {
                        continue;
                    }
                    pairedCoordVisitGenerations[nextPairCoord] = visitGeneration;
                    pairedCoordQueueA[queueTail] = nextCoordA;
                    pairedCoordQueueB[queueTail] = nextCoordB;
                    queueTail++;

                    StatePair newState = canonicalizeStateIfNeeded(
                        rewriteCoord(state.stateA, piece, nextCoordA),
                        rewriteCoord(state.stateB, piece, nextCoordB)
                    );
                    if (uniqueNeighbors == null) {
                        degree++;
                    } else if (uniqueNeighbors.add(newState)) {
                        degree++;
                    }

                    if (allStates.add(newState)) {
                        int newId = allStates.size() - 1;
                        stateIDs.put(newState, newId);
                        idToState.put(newId, newState);
                        stateParentIDs.put(newState, id);

                        boolean childSolved = pathSolved;
                        if (isSolvedState(newState) && !pathSolved) {
                            System.out.println("ENTANGLED SOLVED after depth " + (depth + 1) + "!");
                            endTime = System.currentTimeMillis();
                            System.out.println("Time taken: " + (endTime - startTime) + " ms");
                            pathSolved = true;
                            childSolved = true;
                            justSolvedStates.add(newState);
                        }

                        statesByDepth.get(depth + 1).add(newState);
                        pathAlreadySolved.put(newState, childSolved);
                    }
                }
            }
        }

        stateDegrees.put(state, degree);
    }

    private double[] addSolutionPath(StatePair solvedState) {
        ArrayList<Integer> degrees = new ArrayList<>();
        int currentId = stateIDs.get(solvedState);
        int lastDegree = stateDegrees.getOrDefault(solvedState, 0);
        int tightropeCount = 0;
        int degreeSum = 0;

        while (currentId != -1) {
            StatePair currentState = idToState.get(currentId);
            solutionPathStates.add(currentState);
            int currentDegree = stateDegrees.getOrDefault(currentState, 0);
            if (currentDegree != 2 && lastDegree == 2) {
                tightropeCount++;
            }
            degrees.add(0, currentDegree);
            degreeSum += currentDegree;

            lastDegree = currentDegree;
            currentId = stateParentIDs.get(currentState);
        }

        solutionPathsDegrees.add(degrees);
        return new double[]{tightropeCount, degrees.isEmpty() ? 0.0 : degreeSum / (double) degrees.size()};
    }

    private void writeSolutionDegreesCsv() {
        int maxLength = 0;
        for (ArrayList<Integer> degrees : solutionPathsDegrees) {
            maxLength = Math.max(maxLength, degrees.size());
        }

        StringBuilder csvBuilder = new StringBuilder();
        for (int index = 0; index < maxLength; index++) {
            csvBuilder.append(index);
            for (ArrayList<Integer> degrees : solutionPathsDegrees) {
                csvBuilder.append(",");
                csvBuilder.append(index < degrees.size() ? degrees.get(index) : 0);
            }
            csvBuilder.append("\n");
        }

        try {
            java.nio.file.Files.write(java.nio.file.Paths.get("entangled_solutions_degrees.csv"), csvBuilder.toString().getBytes());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private BoardData createBoardData(int[][] grid) {
        long[] puzzle = new long[pieceCount];
        long[] normalizedPieceShapes = new long[pieceCount];
        int[] minRow = new int[pieceCount];
        int[] minCol = new int[pieceCount];
        int[] maxRow = new int[pieceCount];
        int[] maxCol = new int[pieceCount];
        Arrays.fill(minRow, height);
        Arrays.fill(minCol, width);

        long blockedBitboard = 0L;
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int piece = grid[row][col];
                int coord = row * width + col;
                if (piece == -1 || piece > pieceCount) {
                    blockedBitboard |= coordBits[coord];
                } else if (piece > 0) {
                    int pieceIndex = piece - 1;
                    puzzle[pieceIndex] |= coordBits[coord];
                    if (row < minRow[pieceIndex]) minRow[pieceIndex] = row;
                    if (col < minCol[pieceIndex]) minCol[pieceIndex] = col;
                    if (row > maxRow[pieceIndex]) maxRow[pieceIndex] = row;
                    if (col > maxCol[pieceIndex]) maxCol[pieceIndex] = col;
                }
            }
        }

        long[][] pieceMoveBitboards = new long[pieceCount][cellCount];
        int[][][] pieceStepNeighbors = new int[pieceCount][cellCount][4];
        long initialState = 0L;

        for (int piece = 0; piece < pieceCount; piece++) {
            int currentPieceWidth = maxCol[piece] - minCol[piece] + 1;
            int currentPieceHeight = maxRow[piece] - minRow[piece] + 1;
            normalizedPieceShapes[piece] = puzzle[piece] >> (width * minRow[piece] + minCol[piece]);

            for (int coord = 0; coord < cellCount; coord++) {
                Arrays.fill(pieceStepNeighbors[piece][coord], -1);
            }

            for (int row = 0; row < height + 1 - currentPieceHeight; row++) {
                for (int col = 0; col < width + 1 - currentPieceWidth; col++) {
                    int coord = row * width + col;
                    int shift = width * (row - minRow[piece]) + (col - minCol[piece]);
                    long shiftedBitboard = shift < 0 ? puzzle[piece] >> -shift : puzzle[piece] << shift;
                    if ((shiftedBitboard & blockedBitboard) != 0L) {
                        continue;
                    }

                    pieceMoveBitboards[piece][coord] = shiftedBitboard;
                }
            }

            for (int row = 0; row < height + 1 - currentPieceHeight; row++) {
                for (int col = 0; col < width + 1 - currentPieceWidth; col++) {
                    int coord = row * width + col;
                    if (pieceMoveBitboards[piece][coord] == 0L) {
                        continue;
                    }

                    if (row > 0) {
                        int nextCoord = (row - 1) * width + col;
                        if (pieceMoveBitboards[piece][nextCoord] != 0L) {
                            pieceStepNeighbors[piece][coord][0] = nextCoord;
                        }
                    }
                    if (row < height - currentPieceHeight) {
                        int nextCoord = (row + 1) * width + col;
                        if (pieceMoveBitboards[piece][nextCoord] != 0L) {
                            pieceStepNeighbors[piece][coord][1] = nextCoord;
                        }
                    }
                    if (col > 0) {
                        int nextCoord = row * width + (col - 1);
                        if (pieceMoveBitboards[piece][nextCoord] != 0L) {
                            pieceStepNeighbors[piece][coord][2] = nextCoord;
                        }
                    }
                    if (col < width - currentPieceWidth) {
                        int nextCoord = row * width + (col + 1);
                        if (pieceMoveBitboards[piece][nextCoord] != 0L) {
                            pieceStepNeighbors[piece][coord][3] = nextCoord;
                        }
                    }
                }
            }

            int trailingZeroOffset = Long.numberOfTrailingZeros(puzzle[piece]) - (width * minRow[piece] + minCol[piece]);
            int initialCoord = Long.numberOfTrailingZeros(puzzle[piece]) - trailingZeroOffset;
            initialState = rewriteCoord(initialState, piece, initialCoord);
        }

        return new BoardData(normalizedPieceShapes, pieceMoveBitboards, pieceStepNeighbors, blockedBitboard, initialState);
    }

    private int[][] buildIdenticalPiecePairGroups() {
        int[][] groups = new int[pieceCount][];
        boolean[] grouped = new boolean[pieceCount];
        int groupCount = 0;

        for (int piece = 0; piece < pieceCount; piece++) {
            if (grouped[piece]) {
                continue;
            }

            int matches = 1;
            for (int other = piece + 1; other < pieceCount; other++) {
                if (boardA.normalizedPieceShapes[piece] == boardA.normalizedPieceShapes[other]
                    && boardB.normalizedPieceShapes[piece] == boardB.normalizedPieceShapes[other]) {
                    matches++;
                }
            }
            if (matches == 1) {
                continue;
            }

            int[] group = new int[matches];
            group[0] = piece;
            grouped[piece] = true;
            int index = 1;
            for (int other = piece + 1; other < pieceCount; other++) {
                if (boardA.normalizedPieceShapes[piece] == boardA.normalizedPieceShapes[other]
                    && boardB.normalizedPieceShapes[piece] == boardB.normalizedPieceShapes[other]) {
                    group[index++] = other;
                    grouped[other] = true;
                }
            }
            groups[groupCount++] = group;
        }

        return Arrays.copyOf(groups, groupCount);
    }

    private long occupiedBitboard(long packedState, BoardData board) {
        long occupied = board.blockedBitboard;
        for (int piece = 0; piece < pieceCount; piece++) {
            occupied |= board.pieceMoveBitboards[piece][getCoord(packedState, piece)];
        }
        return occupied;
    }

    private StatePair canonicalizeStateIfNeeded(long stateA, long stateB) {
        if (!deduplicateIdenticalPiecePermutations || identicalPiecePairGroups.length == 0) {
            return new StatePair(stateA, stateB);
        }

        long canonicalStateA = stateA;
        long canonicalStateB = stateB;
        for (int groupIndex = 0; groupIndex < identicalPiecePairGroups.length; groupIndex++) {
            int[] group = identicalPiecePairGroups[groupIndex];
            int[] coordsA = new int[group.length];
            int[] coordsB = new int[group.length];
            for (int index = 0; index < group.length; index++) {
                coordsA[index] = getCoord(canonicalStateA, group[index]);
                coordsB[index] = getCoord(canonicalStateB, group[index]);
            }

            for (int index = 1; index < coordsA.length; index++) {
                int coordA = coordsA[index];
                int coordB = coordsB[index];
                int other = index - 1;
                while (other >= 0 && compareCoordPairs(coordsA[other], coordsB[other], coordA, coordB) > 0) {
                    coordsA[other + 1] = coordsA[other];
                    coordsB[other + 1] = coordsB[other];
                    other--;
                }
                coordsA[other + 1] = coordA;
                coordsB[other + 1] = coordB;
            }

            for (int index = 0; index < group.length; index++) {
                canonicalStateA = rewriteCoord(canonicalStateA, group[index], coordsA[index]);
                canonicalStateB = rewriteCoord(canonicalStateB, group[index], coordsB[index]);
            }
        }

        return new StatePair(canonicalStateA, canonicalStateB);
    }

    private boolean isSolvedState(StatePair state) {
        return boardA.pieceMoveBitboards[winningPieceIndex][getCoord(state.stateA, winningPieceIndex)] == winningBitboardA
            && boardB.pieceMoveBitboards[winningPieceIndex][getCoord(state.stateB, winningPieceIndex)] == winningBitboardB;
    }

    private int compareCoordPairs(int coordA1, int coordB1, int coordA2, int coordB2) {
        if (coordA1 != coordA2) {
            return coordA1 - coordA2;
        }
        return coordB1 - coordB2;
    }

    private int nextPairCoordVisitGeneration() {
        pairedCoordVisitGeneration++;
        if (pairedCoordVisitGeneration == 0) {
            pairedCoordVisitGeneration = 1;
            Arrays.fill(pairedCoordVisitGenerations, 0);
        }
        return pairedCoordVisitGeneration;
    }

    private boolean preservesRelativeOffset(int coordA, int coordB, int nextCoordA, int nextCoordB) {
        int rowOffset = coordB / width - coordA / width;
        int colOffset = coordB % width - coordA % width;
        return rowOffset == nextCoordB / width - nextCoordA / width
            && colOffset == nextCoordB % width - nextCoordA % width;
    }

    private int getCoord(long packedState, int piece) {
        return (int) ((packedState >>> pieceStateShifts[piece]) & coordMask);
    }

    private long rewriteCoord(long packedState, int piece, int coord) {
        return (packedState & pieceStateClearMasks[piece]) | pieceStateBits[piece][coord];
    }

    private static int bitsNeeded(int maxValue) {
        return maxValue <= 0 ? 1 : 32 - Integer.numberOfLeadingZeros(maxValue);
    }
}