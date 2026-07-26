import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashSet;

public class EntangledSimplePuzzleSearcher {

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

    private static final class TraversalScratch {
        final int[] pairedCoordVisitGenerations;
        int pairedCoordVisitGeneration;
        final int[] pairedCoordQueueA;
        final int[] pairedCoordQueueB;

        TraversalScratch(int pairCoordCount) {
            pairedCoordVisitGenerations = new int[pairCoordCount];
            pairedCoordVisitGeneration = 1;
            pairedCoordQueueA = new int[pairCoordCount];
            pairedCoordQueueB = new int[pairCoordCount];
        }

        int nextPairCoordVisitGeneration() {
            pairedCoordVisitGeneration++;
            if (pairedCoordVisitGeneration == 0) {
                pairedCoordVisitGeneration = 1;
                Arrays.fill(pairedCoordVisitGenerations, 0);
            }
            return pairedCoordVisitGeneration;
        }
    }

    private final int width;
    private final int height;
    private final int cellCount;
    private final int pairCoordCount;
    private final int pieceCount;
    private final boolean deduplicateIdenticalPiecePermutations;
    private final boolean evaluateSolutionAnalytics;
    private final long[] coordBits;
    private final BoardData boardA;
    private final BoardData boardB;
    private final int coordBitWidth;
    private final long coordMask;
    private final int[] pieceStateShifts;
    private final long[] pieceStateClearMasks;
    private final long[][] pieceStateBits;
    private final int[][] identicalPiecePairGroups;
    private final long initialStateA;
    private final long initialStateB;
    private final int[] bestSimplePuzzleLengthsGlobal;
    private final long[] bestSimplePuzzleStartStatesA;
    private final long[] bestSimplePuzzleStartStatesB;
    private final long[] bestSimplePuzzleEndStatesA;
    private final long[] bestSimplePuzzleEndStatesB;
    private final boolean[] bestSimplePuzzleFoundGlobal;

    public EntangledSimplePuzzleSearcher(int[][] gridA, int[][] gridB, int pieceCount) {
        this(gridA, gridB, pieceCount, false, true);
    }

    public EntangledSimplePuzzleSearcher(
        int[][] gridA,
        int[][] gridB,
        int pieceCount,
        boolean deduplicateIdenticalPiecePermutations
    ) {
        this(gridA, gridB, pieceCount, deduplicateIdenticalPiecePermutations, true);
    }

    public EntangledSimplePuzzleSearcher(
        int[][] gridA,
        int[][] gridB,
        int pieceCount,
        boolean deduplicateIdenticalPiecePermutations,
        boolean evaluateSolutionAnalytics
    ) {
        this.height = gridA.length;
        this.width = gridA[0].length;
        this.cellCount = width * height;
        this.pairCoordCount = cellCount * cellCount;
        this.pieceCount = pieceCount;
        this.deduplicateIdenticalPiecePermutations = deduplicateIdenticalPiecePermutations;
        this.evaluateSolutionAnalytics = evaluateSolutionAnalytics;

        coordBits = new long[cellCount];
        for (int coord = 0; coord < cellCount; coord++) {
            coordBits[coord] = 1L << coord;
        }

        coordBitWidth = bitsNeeded(cellCount - 1);
        if ((long) coordBitWidth * pieceCount > 64L) {
            throw new IllegalArgumentException("Packed entangled board state requires more than 64 bits per puzzle");
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
        initialStateA = boardA.initialState;
        initialStateB = boardB.initialState;
        identicalPiecePairGroups = buildIdenticalPiecePairGroups();

        bestSimplePuzzleLengthsGlobal = new int[pieceCount];
        bestSimplePuzzleStartStatesA = new long[pieceCount];
        bestSimplePuzzleStartStatesB = new long[pieceCount];
        bestSimplePuzzleEndStatesA = new long[pieceCount];
        bestSimplePuzzleEndStatesB = new long[pieceCount];
        bestSimplePuzzleFoundGlobal = new boolean[pieceCount];
    }

    public String findBestSimplePuzzles(int threshold, String pieceCodeStringA, String pieceCodeStringB) {
        ArrayList<StatePair> allStatesList = enumerateAllReachableStates();
        int threadCount = Math.min(8, Math.max(1, allStatesList.size()));
        Thread[] threads = new Thread[threadCount];

        Arrays.fill(bestSimplePuzzleLengthsGlobal, 0);
        Arrays.fill(bestSimplePuzzleStartStatesA, 0L);
        Arrays.fill(bestSimplePuzzleStartStatesB, 0L);
        Arrays.fill(bestSimplePuzzleEndStatesA, 0L);
        Arrays.fill(bestSimplePuzzleEndStatesB, 0L);
        Arrays.fill(bestSimplePuzzleFoundGlobal, false);

        for (int thread = 0; thread < threadCount; thread++) {
            final int threadIndex = thread;
            threads[thread] = new Thread(() -> processStateSlice(allStatesList, threadCount, threadIndex, threshold));
            threads[thread].start();
        }

        for (Thread thread : threads) {
            try {
                thread.join();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException(e);
            }
        }

        System.out.print("\033[H\033[2J");

        StringBuilder sb = new StringBuilder();
        for (int piece = 0; piece < pieceCount; piece++) {
            if (!bestSimplePuzzleFoundGlobal[piece] || bestSimplePuzzleLengthsGlobal[piece] < threshold) {
                continue;
            }

            sb.append(pieceCodeStringA).append(',');
            sb.append(pieceCodeStringB).append(',');
            sb.append(piece + 1).append(',');
            sb.append(bestSimplePuzzleLengthsGlobal[piece]).append(',');
            sb.append(stateToStringInline(bestSimplePuzzleStartStatesA[piece], boardA)).append(',');
            sb.append(stateToStringInline(bestSimplePuzzleStartStatesB[piece], boardB)).append(',');
            sb.append(stateToStringInline(bestSimplePuzzleEndStatesA[piece], boardA)).append(',');
            sb.append(stateToStringInline(bestSimplePuzzleEndStatesB[piece], boardB));

            if (evaluateSolutionAnalytics) {
                int[][] startGridA = packedStateToGridWithBlocks(bestSimplePuzzleStartStatesA[piece], boardA);
                int[][] startGridB = packedStateToGridWithBlocks(bestSimplePuzzleStartStatesB[piece], boardB);
                long winningPieceBitboardA = boardA.pieceMoveBitboards[piece][getCoord(bestSimplePuzzleEndStatesA[piece], piece)];
                long winningPieceBitboardB = boardB.pieceMoveBitboards[piece][getCoord(bestSimplePuzzleEndStatesB[piece], piece)];
                EntangledSolutionEvaluator evaluator = new EntangledSolutionEvaluator(
                    startGridA,
                    startGridB,
                    pieceCount,
                    piece + 1,
                    winningPieceBitboardA,
                    winningPieceBitboardB,
                    deduplicateIdenticalPiecePermutations
                );
                double[] analytics = evaluator.solve();
                if (analytics == null) {
                    sb.append(",0,0,1,100,0,0");
                } else {
                    for (int i = 0; i < analytics.length; i++) {
                        sb.append(',').append(analytics[i]);
                    }
                }
            } else {
                sb.append(",,,,,,");
            }
            sb.append('\n');
        }

        return sb.toString();
    }

    public String statePairToSideBySideString(long stateA, long stateB) {
        int[][] gridA = packedStateToGridWithBlocks(stateA, boardA);
        int[][] gridB = packedStateToGridWithBlocks(stateB, boardB);
        StringBuilder sb = new StringBuilder();
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                sb.append(String.format("%2d", gridA[row][col]));
                if (col < width - 1) {
                    sb.append(' ');
                }
            }
            sb.append("    ");
            for (int col = 0; col < width; col++) {
                sb.append(String.format("%2d", gridB[row][col]));
                if (col < width - 1) {
                    sb.append(' ');
                }
            }
            if (row < height - 1) {
                sb.append('\n');
            }
        }
        return sb.toString();
    }

    private void processStateSlice(ArrayList<StatePair> allStatesList, int threadCount, int threadIndex, int threshold) {
        int verticesPerThread = allStatesList.size() / threadCount;
        int startIndex = threadIndex * verticesPerThread;
        int endIndex = (threadIndex == threadCount - 1) ? allStatesList.size() : startIndex + verticesPerThread;
        int totalStates = endIndex - startIndex;
        long threadStartTime = System.nanoTime();

        System.out.println("Thread " + (threadIndex + 1) + " processing states " + startIndex + " to " + (endIndex - 1));

        HashSet<StatePair> localVisited = new HashSet<>();
        ArrayList<StatePair> currentFrontier = new ArrayList<>();
        ArrayList<StatePair> nextFrontier = new ArrayList<>();
        BitSet[] positionsReachedByPiece = new BitSet[pieceCount];
        for (int piece = 0; piece < pieceCount; piece++) {
            positionsReachedByPiece[piece] = new BitSet(pairCoordCount);
        }
        TraversalScratch traversalScratch = new TraversalScratch(pairCoordCount);

        int[] bestLengthsLocal = new int[pieceCount];
        long[] bestStartsLocalA = new long[pieceCount];
        long[] bestStartsLocalB = new long[pieceCount];
        long[] bestEndsLocalA = new long[pieceCount];
        long[] bestEndsLocalB = new long[pieceCount];
        boolean[] bestFoundLocal = new boolean[pieceCount];

        for (int index = startIndex; index < endIndex; index++) {
            StatePair startState = allStatesList.get(index);
            findEccentricity(
                startState,
                localVisited,
                currentFrontier,
                nextFrontier,
                positionsReachedByPiece,
                traversalScratch,
                bestLengthsLocal,
                bestStartsLocalA,
                bestStartsLocalB,
                bestEndsLocalA,
                bestEndsLocalB,
                bestFoundLocal
            );

            int stateNum = index - startIndex + 1;
            if (stateNum % 100 == 0 || stateNum == totalStates) {
                int percent = totalStates == 0 ? 100 : (int) ((stateNum * 100.0) / totalStates);
                StringBuilder bar = new StringBuilder();
                bar.append("\033[").append(threadIndex + 1).append(";0H");
                bar.append("Thread ").append(threadIndex + 1).append(": [");
                int barLen = 50;
                int filled = (int) (barLen * percent / 100.0);
                for (int marker = 0; marker < barLen; marker++) {
                    bar.append(marker < filled ? ':' : 2 * marker < percent ? '.' : ' ');
                }
                bar.append("] ").append(percent).append("% (").append(stateNum).append("/").append(totalStates).append(")");

                long elapsedNanos = System.nanoTime() - threadStartTime;
                double ratioComplete = totalStates == 0 ? 1.0 : stateNum / (double) totalStates;
                long remainingNanos = ratioComplete == 0.0 ? 0L : (long) (elapsedNanos * (1.0 - ratioComplete) / ratioComplete);
                bar.append(" Elapsed: ").append(formatDurationNanos(elapsedNanos));
                bar.append(" Remaining: ").append(formatDurationNanos(remainingNanos));
                System.out.print(bar.toString());
                System.out.flush();
            }
        }

        System.out.println("Thread " + (threadIndex + 1) + " finished.");

        synchronized (this) {
            for (int piece = 0; piece < pieceCount; piece++) {
                if (bestFoundLocal[piece] && bestLengthsLocal[piece] > bestSimplePuzzleLengthsGlobal[piece]) {
                    bestSimplePuzzleLengthsGlobal[piece] = bestLengthsLocal[piece];
                    bestSimplePuzzleStartStatesA[piece] = bestStartsLocalA[piece];
                    bestSimplePuzzleStartStatesB[piece] = bestStartsLocalB[piece];
                    bestSimplePuzzleEndStatesA[piece] = bestEndsLocalA[piece];
                    bestSimplePuzzleEndStatesB[piece] = bestEndsLocalB[piece];
                    bestSimplePuzzleFoundGlobal[piece] = true;

                    if (bestLengthsLocal[piece] >= threshold) {
                        System.out.println("\nNew best entangled simple puzzle found for piece " + (piece + 1) + ": " + bestLengthsLocal[piece] + " moves");
                        System.out.println(statePairToSideBySideString(bestStartsLocalA[piece], bestStartsLocalB[piece]));
                        System.out.println();
                        System.out.println(statePairToSideBySideString(bestEndsLocalA[piece], bestEndsLocalB[piece]));
                    }
                }
            }
        }
    }

    private void findEccentricity(
        StatePair startState,
        HashSet<StatePair> visitedStates,
        ArrayList<StatePair> currentFrontier,
        ArrayList<StatePair> nextFrontier,
        BitSet[] positionsReachedByPiece,
        TraversalScratch traversalScratch,
        int[] bestLengthsLocal,
        long[] bestStartsLocalA,
        long[] bestStartsLocalB,
        long[] bestEndsLocalA,
        long[] bestEndsLocalB,
        boolean[] bestFoundLocal
    ) {
        visitedStates.clear();
        currentFrontier.clear();
        nextFrontier.clear();
        for (int piece = 0; piece < pieceCount; piece++) {
            positionsReachedByPiece[piece].clear();
        }

        StatePair canonicalStart = canonicalizeStateIfNeeded(startState.stateA, startState.stateB);
        visitedStates.add(canonicalStart);
        currentFrontier.add(canonicalStart);
        for (int piece = 0; piece < pieceCount; piece++) {
            int coordPair = getCoordPair(canonicalStart.stateA, canonicalStart.stateB, piece);
            positionsReachedByPiece[piece].set(coordPair);
        }

        int depth = 0;
        while (!currentFrontier.isEmpty()) {
            nextFrontier.clear();
            int nextDepth = depth + 1;

            for (int index = 0; index < currentFrontier.size(); index++) {
                expandLocalState(
                    currentFrontier.get(index),
                    canonicalStart,
                    nextDepth,
                    visitedStates,
                    nextFrontier,
                    positionsReachedByPiece,
                    traversalScratch,
                    bestLengthsLocal,
                    bestStartsLocalA,
                    bestStartsLocalB,
                    bestEndsLocalA,
                    bestEndsLocalB,
                    bestFoundLocal
                );
            }

            ArrayList<StatePair> swap = currentFrontier;
            currentFrontier = nextFrontier;
            nextFrontier = swap;
            depth = nextDepth;
        }
    }

    private ArrayList<StatePair> enumerateAllReachableStates() {
        HashSet<StatePair> allStates = new HashSet<>();
        ArrayList<StatePair> allStatesList = new ArrayList<>();
        ArrayList<StatePair> currentFrontier = new ArrayList<>();
        ArrayList<StatePair> nextFrontier = new ArrayList<>();
        TraversalScratch traversalScratch = new TraversalScratch(pairCoordCount);

        StatePair initialState = canonicalizeStateIfNeeded(initialStateA, initialStateB);
        allStates.add(initialState);
        allStatesList.add(initialState);
        currentFrontier.add(initialState);

        while (!currentFrontier.isEmpty()) {
            nextFrontier.clear();

            for (int index = 0; index < currentFrontier.size(); index++) {
                expandGlobalState(currentFrontier.get(index), allStates, allStatesList, nextFrontier, traversalScratch);
            }

            ArrayList<StatePair> swap = currentFrontier;
            currentFrontier = nextFrontier;
            nextFrontier = swap;
        }

        return allStatesList;
    }

    private void expandGlobalState(
        StatePair packedState,
        HashSet<StatePair> allStates,
        ArrayList<StatePair> allStatesList,
        ArrayList<StatePair> nextFrontier,
        TraversalScratch traversalScratch
    ) {
        long occupiedA = occupiedBitboard(packedState.stateA, boardA);
        long occupiedB = occupiedBitboard(packedState.stateB, boardB);

        for (int piece = 0; piece < pieceCount; piece++) {
            int startCoordA = getCoord(packedState.stateA, piece);
            int startCoordB = getCoord(packedState.stateB, piece);
            long currentPieceBitboardA = boardA.pieceMoveBitboards[piece][startCoordA];
            long currentPieceBitboardB = boardB.pieceMoveBitboards[piece][startCoordB];
            long blockersA = occupiedA ^ currentPieceBitboardA;
            long blockersB = occupiedB ^ currentPieceBitboardB;

            int queueHead = 0;
            int queueTail = 1;
            int visitGeneration = traversalScratch.nextPairCoordVisitGeneration();
            int startPairCoord = startCoordA * cellCount + startCoordB;
            traversalScratch.pairedCoordVisitGenerations[startPairCoord] = visitGeneration;
            traversalScratch.pairedCoordQueueA[0] = startCoordA;
            traversalScratch.pairedCoordQueueB[0] = startCoordB;

            while (queueHead < queueTail) {
                int coordA = traversalScratch.pairedCoordQueueA[queueHead];
                int coordB = traversalScratch.pairedCoordQueueB[queueHead];
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
                    if (traversalScratch.pairedCoordVisitGenerations[nextPairCoord] == visitGeneration) {
                        continue;
                    }
                    traversalScratch.pairedCoordVisitGenerations[nextPairCoord] = visitGeneration;
                    traversalScratch.pairedCoordQueueA[queueTail] = nextCoordA;
                    traversalScratch.pairedCoordQueueB[queueTail] = nextCoordB;
                    queueTail++;

                    StatePair newState = canonicalizeStateIfNeeded(
                        rewriteCoord(packedState.stateA, piece, nextCoordA),
                        rewriteCoord(packedState.stateB, piece, nextCoordB)
                    );
                    if (allStates.add(newState)) {
                        allStatesList.add(newState);
                        nextFrontier.add(newState);
                    }
                }
            }
        }
    }

    private void expandLocalState(
        StatePair packedState,
        StatePair startState,
        int nextDepth,
        HashSet<StatePair> visitedStates,
        ArrayList<StatePair> nextFrontier,
        BitSet[] positionsReachedByPiece,
        TraversalScratch traversalScratch,
        int[] bestLengthsLocal,
        long[] bestStartsLocalA,
        long[] bestStartsLocalB,
        long[] bestEndsLocalA,
        long[] bestEndsLocalB,
        boolean[] bestFoundLocal
    ) {
        long occupiedA = occupiedBitboard(packedState.stateA, boardA);
        long occupiedB = occupiedBitboard(packedState.stateB, boardB);

        for (int piece = 0; piece < pieceCount; piece++) {
            int startCoordA = getCoord(packedState.stateA, piece);
            int startCoordB = getCoord(packedState.stateB, piece);
            long currentPieceBitboardA = boardA.pieceMoveBitboards[piece][startCoordA];
            long currentPieceBitboardB = boardB.pieceMoveBitboards[piece][startCoordB];
            long blockersA = occupiedA ^ currentPieceBitboardA;
            long blockersB = occupiedB ^ currentPieceBitboardB;

            int queueHead = 0;
            int queueTail = 1;
            int visitGeneration = traversalScratch.nextPairCoordVisitGeneration();
            int startPairCoord = startCoordA * cellCount + startCoordB;
            traversalScratch.pairedCoordVisitGenerations[startPairCoord] = visitGeneration;
            traversalScratch.pairedCoordQueueA[0] = startCoordA;
            traversalScratch.pairedCoordQueueB[0] = startCoordB;

            while (queueHead < queueTail) {
                int coordA = traversalScratch.pairedCoordQueueA[queueHead];
                int coordB = traversalScratch.pairedCoordQueueB[queueHead];
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
                    if (traversalScratch.pairedCoordVisitGenerations[nextPairCoord] == visitGeneration) {
                        continue;
                    }
                    traversalScratch.pairedCoordVisitGenerations[nextPairCoord] = visitGeneration;
                    traversalScratch.pairedCoordQueueA[queueTail] = nextCoordA;
                    traversalScratch.pairedCoordQueueB[queueTail] = nextCoordB;
                    queueTail++;

                    StatePair newState = canonicalizeStateIfNeeded(
                        rewriteCoord(packedState.stateA, piece, nextCoordA),
                        rewriteCoord(packedState.stateB, piece, nextCoordB)
                    );
                    if (visitedStates.add(newState)) {
                        nextFrontier.add(newState);

                        int canonicalCoordPair = getCoordPair(newState.stateA, newState.stateB, piece);
                        if (!positionsReachedByPiece[piece].get(canonicalCoordPair)) {
                            positionsReachedByPiece[piece].set(canonicalCoordPair);
                            if (nextDepth > bestLengthsLocal[piece]) {
                                bestLengthsLocal[piece] = nextDepth;
                                bestStartsLocalA[piece] = startState.stateA;
                                bestStartsLocalB[piece] = startState.stateB;
                                bestEndsLocalA[piece] = newState.stateA;
                                bestEndsLocalB[piece] = newState.stateB;
                                bestFoundLocal[piece] = true;
                            }
                        }
                    }
                }
            }
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
                if (piece == -1) {
                    blockedBitboard |= coordBits[coord];
                } else if (piece > 0 && piece <= pieceCount) {
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
            int trailingZeroOffset = Long.numberOfTrailingZeros(puzzle[piece]) - (width * minRow[piece] + minCol[piece]);

            for (int coord = 0; coord < cellCount; coord++) {
                for (int direction = 0; direction < 4; direction++) {
                    pieceStepNeighbors[piece][coord][direction] = -1;
                }
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
                    if (row > 0) {
                        int nextCoord = (row - 1) * width + col;
                        if (pieceMoveBitboards[piece][nextCoord] != 0L) {
                            pieceStepNeighbors[piece][coord][0] = nextCoord;
                        }
                    }
                    if (col > 0) {
                        int nextCoord = row * width + (col - 1);
                        if (pieceMoveBitboards[piece][nextCoord] != 0L) {
                            pieceStepNeighbors[piece][coord][2] = nextCoord;
                        }
                    }
                }
            }

            for (int row = 0; row < height + 1 - currentPieceHeight; row++) {
                for (int col = 0; col < width + 1 - currentPieceWidth; col++) {
                    int coord = row * width + col;
                    if (pieceMoveBitboards[piece][coord] == 0L) {
                        continue;
                    }
                    if (row < height - currentPieceHeight) {
                        int nextCoord = (row + 1) * width + col;
                        if (pieceMoveBitboards[piece][nextCoord] != 0L) {
                            pieceStepNeighbors[piece][coord][1] = nextCoord;
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

    private StatePair canonicalizeStateIfNeeded(long stateA, long stateB) {
        long canonicalStateA = stateA;
        long canonicalStateB = stateB;
        if (!deduplicateIdenticalPiecePermutations || identicalPiecePairGroups.length == 0) {
            return new StatePair(stateA, stateB);
        }

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

    private int compareCoordPairs(int coordA1, int coordB1, int coordA2, int coordB2) {
        if (coordA1 != coordA2) {
            return coordA1 - coordA2;
        }
        return coordB1 - coordB2;
    }

    private long occupiedBitboard(long packedState, BoardData board) {
        long occupied = board.blockedBitboard;
        for (int piece = 0; piece < pieceCount; piece++) {
            occupied |= board.pieceMoveBitboards[piece][getCoord(packedState, piece)];
        }
        return occupied;
    }

    private int getCoordPair(long stateA, long stateB, int piece) {
        return getCoord(stateA, piece) * cellCount + getCoord(stateB, piece);
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

    private int[][] packedStateToGridWithBlocks(long packedState, BoardData board) {
        int[][] grid = new int[height][width];
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int coord = row * width + col;
                if ((board.blockedBitboard & coordBits[coord]) != 0L) {
                    grid[row][col] = -1;
                }
            }
        }

        for (int piece = 0; piece < pieceCount; piece++) {
            long bits = board.pieceMoveBitboards[piece][getCoord(packedState, piece)];
            while (bits != 0L) {
                int coord = Long.numberOfTrailingZeros(bits);
                grid[coord / width][coord % width] = piece + 1;
                bits &= bits - 1;
            }
        }
        return grid;
    }

    private String stateToStringInline(long packedState, BoardData board) {
        int[][] grid = packedStateToGridWithBlocks(packedState, board);
        StringBuilder sb = new StringBuilder();
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                sb.append(grid[row][col]);
                if (col < width - 1) {
                    sb.append(' ');
                }
            }
            if (row < height - 1) {
                sb.append(" | ");
            }
        }
        return sb.toString();
    }

    private static int bitsNeeded(int maxValue) {
        return maxValue <= 0 ? 1 : 32 - Integer.numberOfLeadingZeros(maxValue);
    }

    private static String formatDurationNanos(long nanos) {
        long totalSeconds = nanos / 1_000_000_000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        return hours + "h" + minutes + "m" + seconds + "s";
    }
}