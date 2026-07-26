import java.util.Arrays;

public class SimplePuzzleSearcher {

    private static final class LongArrayList {
        private long[] values;
        private int size;

        LongArrayList(int capacity) {
            values = new long[Math.max(1, capacity)];
        }

        void add(long value) {
            if (size == values.length) {
                long[] next = new long[values.length << 1];
                System.arraycopy(values, 0, next, 0, values.length);
                values = next;
            }
            values[size++] = value;
        }

        long get(int index) {
            return values[index];
        }

        int size() {
            return size;
        }

        boolean isEmpty() {
            return size == 0;
        }

        void clear() {
            size = 0;
        }
    }

    private static final class LongHashSet {
        private long[] keys;
        private int[] generations;
        private int generation;
        private int mask;
        private int size;
        private int threshold;

        LongHashSet(int expectedSize) {
            int capacity = 1;
            int minCapacity = Math.max(16, expectedSize << 1);
            while (capacity < minCapacity) capacity <<= 1;
            allocate(capacity);
            generation = 1;
        }

        boolean add(long value) {
            if (size >= threshold) {
                resize();
            }

            int index = mix(value) & mask;
            while (generations[index] == generation) {
                if (keys[index] == value) {
                    return false;
                }
                index = (index + 1) & mask;
            }

            generations[index] = generation;
            keys[index] = value;
            size++;
            return true;
        }

        void clear() {
            size = 0;
            generation++;
            if (generation == 0) {
                Arrays.fill(generations, 0);
                generation = 1;
            }
        }

        private void allocate(int capacity) {
            keys = new long[capacity];
            generations = new int[capacity];
            mask = capacity - 1;
            threshold = capacity >> 1;
            size = 0;
        }

        private void resize() {
            long[] oldKeys = keys;
            int[] oldGenerations = generations;
            int oldGeneration = generation;

            allocate(oldKeys.length << 1);
            generation = 1;

            for (int index = 0; index < oldKeys.length; index++) {
                if (oldGenerations[index] == oldGeneration) {
                    insertRehashed(oldKeys[index]);
                }
            }
        }

        private void insertRehashed(long value) {
            int index = mix(value) & mask;
            while (generations[index] == generation) {
                index = (index + 1) & mask;
            }
            generations[index] = generation;
            keys[index] = value;
            size++;
        }

        private static int mix(long value) {
            value ^= (value >>> 33);
            value *= 0xff51afd7ed558ccdL;
            value ^= (value >>> 33);
            value *= 0xc4ceb9fe1a85ec53L;
            value ^= (value >>> 33);
            return (int) value;
        }
    }

    private final int width;
    private final int height;
    private final int cellCount;
    private final int pieceCount;
    private final long blockedBitboard;
    private final boolean deduplicateIdenticalPiecePermutations;
    private final boolean evaluateSolutionAnalytics;

    private final long[] coordBits;
    private final long[] orthoBitboards;
    private final long[][] pieceMoveBitboards;
    private final long[] pieceValidAnchorMasks;
    private final long[][] pieceNeighborMasks;
    private final int[] pieceTrailingZeroOffsets;
    private final int[][] identicalPieceGroups;

    private final int coordBitWidth;
    private final long coordMask;
    private final int[] pieceStateShifts;
    private final long[] pieceStateClearMasks;
    private final long[][] pieceStateBits;
    private final long initialState;

    private final int[] bestSimplePuzzleLengthsGlobal;
    private final long[] bestSimplePuzzleStartStatesGlobal;
    private final long[] bestSimplePuzzleEndStatesGlobal;
    private final boolean[] bestSimplePuzzleFoundGlobal;

    public SimplePuzzleSearcher(int[][] grid, int pieceCount) {
        this(grid, pieceCount, false, true);
    }

    public SimplePuzzleSearcher(int[][] grid, int pieceCount, boolean deduplicateIdenticalPiecePermutations) {
        this(grid, pieceCount, deduplicateIdenticalPiecePermutations, true);
    }

    public SimplePuzzleSearcher(
        int[][] grid,
        int pieceCount,
        boolean deduplicateIdenticalPiecePermutations,
        boolean evaluateSolutionAnalytics
    ) {
        this.height = grid.length;
        this.width = grid[0].length;
        this.cellCount = width * height;
        this.pieceCount = pieceCount;
        this.deduplicateIdenticalPiecePermutations = deduplicateIdenticalPiecePermutations;
        this.evaluateSolutionAnalytics = evaluateSolutionAnalytics;

        long[] puzzle = new long[pieceCount];
        long[] normalizedPieceShapes = new long[pieceCount];
        int[] minRow = new int[pieceCount];
        int[] minCol = new int[pieceCount];
        int[] maxRow = new int[pieceCount];
        int[] maxCol = new int[pieceCount];
        Arrays.fill(minRow, height);
        Arrays.fill(minCol, width);

        coordBits = new long[cellCount];
        for (int coord = 0; coord < cellCount; coord++) {
            coordBits[coord] = 1L << coord;
        }

        long blocked = 0L;
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                int piece = grid[r][c];
                int coord = r * width + c;
                if (piece == -1) {
                    blocked |= coordBits[coord];
                } else if (piece > 0 && piece <= pieceCount) {
                    int pieceIndex = piece - 1;
                    puzzle[pieceIndex] |= coordBits[coord];
                    if (r < minRow[pieceIndex]) minRow[pieceIndex] = r;
                    if (c < minCol[pieceIndex]) minCol[pieceIndex] = c;
                    if (r > maxRow[pieceIndex]) maxRow[pieceIndex] = r;
                    if (c > maxCol[pieceIndex]) maxCol[pieceIndex] = c;
                }
            }
        }
        blockedBitboard = blocked;

        pieceMoveBitboards = new long[pieceCount][cellCount];
        pieceValidAnchorMasks = new long[pieceCount];
        pieceTrailingZeroOffsets = new int[pieceCount];
        for (int piece = 0; piece < pieceCount; piece++) {
            int currentPieceWidth = maxCol[piece] - minCol[piece] + 1;
            int currentPieceHeight = maxRow[piece] - minRow[piece] + 1;
            normalizedPieceShapes[piece] = puzzle[piece] >> (width * minRow[piece] + minCol[piece]);
            int trailingZeroOffset = Long.numberOfTrailingZeros(puzzle[piece]) - (width * minRow[piece] + minCol[piece]);
            pieceTrailingZeroOffsets[piece] = trailingZeroOffset;

            long validAnchorMask = 0L;
            for (int r = 0; r < height + 1 - currentPieceHeight; r++) {
                for (int c = 0; c < width + 1 - currentPieceWidth; c++) {
                    int coord = r * width + c;
                    int shift = width * (r - minRow[piece]) + (c - minCol[piece]);
                    validAnchorMask |= coordBits[coord];
                    if (shift < 0) {
                        pieceMoveBitboards[piece][coord] = puzzle[piece] >> -shift;
                    } else {
                        pieceMoveBitboards[piece][coord] = puzzle[piece] << shift;
                    }
                }
            }
            pieceValidAnchorMasks[piece] = validAnchorMask;
        }

        coordBitWidth = bitsNeeded(cellCount - 1);
        if ((long) coordBitWidth * pieceCount > 64L) {
            throw new IllegalArgumentException("Packed SPS state requires more than 64 bits for this puzzle");
        }
        coordMask = (1L << coordBitWidth) - 1L;

        pieceStateShifts = new int[pieceCount];
        pieceStateClearMasks = new long[pieceCount];
        pieceStateBits = new long[pieceCount][cellCount];
        long packedInitialState = 0L;
        for (int piece = 0; piece < pieceCount; piece++) {
            int shift = piece * coordBitWidth;
            pieceStateShifts[piece] = shift;
            pieceStateClearMasks[piece] = ~(coordMask << shift);
            for (int coord = 0; coord < cellCount; coord++) {
                pieceStateBits[piece][coord] = ((long) coord) << shift;
            }

            int initialCoord = Long.numberOfTrailingZeros(puzzle[piece]) - pieceTrailingZeroOffsets[piece];
            packedInitialState = rewriteCoord(packedInitialState, piece, initialCoord);
        }
        identicalPieceGroups = buildIdenticalPieceGroups(normalizedPieceShapes);
        initialState = canonicalizeStateIfNeeded(packedInitialState);

        long[] ortho = new long[cellCount];
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                long bitboard = 0L;
                if (r > 0) bitboard |= coordBits[(r - 1) * width + c];
                if (r < height - 1) bitboard |= coordBits[(r + 1) * width + c];
                if (c > 0) bitboard |= coordBits[r * width + (c - 1)];
                if (c < width - 1) bitboard |= coordBits[r * width + (c + 1)];
                ortho[r * width + c] = bitboard;
            }
        }
        orthoBitboards = ortho;

        pieceNeighborMasks = new long[pieceCount][cellCount];
        for (int piece = 0; piece < pieceCount; piece++) {
            long anchors = pieceValidAnchorMasks[piece];
            while (anchors != 0L) {
                int coord = Long.numberOfTrailingZeros(anchors);
                anchors &= anchors - 1;
                pieceNeighborMasks[piece][coord] = orthoBitboards[coord] & pieceValidAnchorMasks[piece];
            }
        }

        bestSimplePuzzleLengthsGlobal = new int[pieceCount];
        bestSimplePuzzleStartStatesGlobal = new long[pieceCount];
        bestSimplePuzzleEndStatesGlobal = new long[pieceCount];
        bestSimplePuzzleFoundGlobal = new boolean[pieceCount];
    }

    public String findBestSimplePuzzles(int threshold, String pieceCodeString) {
        LongArrayList allStatesList = enumerateAllReachableStates();
        int threadCount = Math.min(8, Math.max(1, allStatesList.size()));
        Thread[] threads = new Thread[threadCount];

        Arrays.fill(bestSimplePuzzleLengthsGlobal, 0);
        Arrays.fill(bestSimplePuzzleStartStatesGlobal, 0L);
        Arrays.fill(bestSimplePuzzleEndStatesGlobal, 0L);
        Arrays.fill(bestSimplePuzzleFoundGlobal, false);

        for (int t = 0; t < threadCount; t++) {
            final int threadIndex = t;
            threads[t] = new Thread(() -> processStateSlice(allStatesList, threadCount, threadIndex, threshold));
            threads[t].start();
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

            long startState = bestSimplePuzzleStartStatesGlobal[piece];
            long endState = bestSimplePuzzleEndStatesGlobal[piece];

            sb.append(pieceCodeString).append(",");
            sb.append(piece + 1).append(",");
            sb.append(bestSimplePuzzleLengthsGlobal[piece]).append(",");
            sb.append(stateToStringInline(startState)).append(",");
            sb.append(stateToStringInline(endState)).append(",");

            if (evaluateSolutionAnalytics) {
                int[][] startGrid = packedStateToEvaluatorGrid(startState);
                long winningPieceBitboard = pieceMoveBitboards[piece][getCoord(endState, piece)];
                SolutionEvaluator evaluator = new SolutionEvaluator(startGrid, pieceCount, piece + 1, winningPieceBitboard);
                double[] analytics = evaluator.solve();
                for (int i = 0; i < analytics.length; i++) {
                    sb.append(analytics[i]);
                    if (i < analytics.length - 1) {
                        sb.append(",");
                    }
                }
            } else {
                sb.append(",,,,,");
            }
            sb.append("\n");
        }

        return sb.toString();
    }

    private void processStateSlice(LongArrayList allStatesList, int threadCount, int threadIndex, int threshold) {
        int verticesPerThread = allStatesList.size() / threadCount;
        int startIndex = threadIndex * verticesPerThread;
        int endIndex = (threadIndex == threadCount - 1) ? allStatesList.size() : startIndex + verticesPerThread;
        int totalStates = endIndex - startIndex;
        long threadStartTime = System.nanoTime();

        System.out.println("Thread " + (threadIndex + 1) + " processing states " + startIndex + " to " + (endIndex - 1));

        LongHashSet localVisited = new LongHashSet(Math.max(16, allStatesList.size()));
        LongArrayList currentFrontier = new LongArrayList(1024);
        LongArrayList nextFrontier = new LongArrayList(1024);
        long[] positionsReachedByPiece = new long[pieceCount];

        int[] bestLengthsLocal = new int[pieceCount];
        long[] bestStartsLocal = new long[pieceCount];
        long[] bestEndsLocal = new long[pieceCount];
        boolean[] bestFoundLocal = new boolean[pieceCount];

        for (int index = startIndex; index < endIndex; index++) {
            long startState = allStatesList.get(index);
            findEccentricity(
                startState,
                localVisited,
                currentFrontier,
                nextFrontier,
                positionsReachedByPiece,
                bestLengthsLocal,
                bestStartsLocal,
                bestEndsLocal,
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
                for (int j = 0; j < barLen; j++) {
                    bar.append(j < filled ? ':' : 2 * j < percent ? '.' : ' ');
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
                    bestSimplePuzzleStartStatesGlobal[piece] = bestStartsLocal[piece];
                    bestSimplePuzzleEndStatesGlobal[piece] = bestEndsLocal[piece];
                    bestSimplePuzzleFoundGlobal[piece] = true;

                    if (bestLengthsLocal[piece] >= threshold) {
                        System.out.println("\nNew best simple puzzle found for piece " + (piece + 1) + ": " + bestLengthsLocal[piece] + " moves");
                        System.out.println("    Start: " + stateToStringInline(bestStartsLocal[piece]));
                        System.out.println("    End:   " + stateToStringInline(bestEndsLocal[piece]));
                    }
                }
            }
        }
    }

    private void findEccentricity(
        long startState,
        LongHashSet visitedStates,
        LongArrayList currentFrontier,
        LongArrayList nextFrontier,
        long[] positionsReachedByPiece,
        int[] bestLengthsLocal,
        long[] bestStartsLocal,
        long[] bestEndsLocal,
        boolean[] bestFoundLocal
    ) {
        visitedStates.clear();
        currentFrontier.clear();
        nextFrontier.clear();

        startState = canonicalizeStateIfNeeded(startState);
        visitedStates.add(startState);
        currentFrontier.add(startState);
        for (int piece = 0; piece < pieceCount; piece++) {
            positionsReachedByPiece[piece] = coordBits[getCoord(startState, piece)];
        }

        int depth = 0;
        while (!currentFrontier.isEmpty()) {
            nextFrontier.clear();
            int nextDepth = depth + 1;

            for (int index = 0; index < currentFrontier.size(); index++) {
                expandLocalState(
                    currentFrontier.get(index),
                    startState,
                    nextDepth,
                    visitedStates,
                    nextFrontier,
                    positionsReachedByPiece,
                    bestLengthsLocal,
                    bestStartsLocal,
                    bestEndsLocal,
                    bestFoundLocal
                );
            }

            LongArrayList swap = currentFrontier;
            currentFrontier = nextFrontier;
            nextFrontier = swap;
            depth = nextDepth;
        }
    }

    private LongArrayList enumerateAllReachableStates() {
        LongHashSet allStates = new LongHashSet(1 << 16);
        LongArrayList allStatesList = new LongArrayList(4096);
        LongArrayList currentFrontier = new LongArrayList(1024);
        LongArrayList nextFrontier = new LongArrayList(1024);

        allStates.add(initialState);
        allStatesList.add(initialState);
        currentFrontier.add(initialState);

        while (!currentFrontier.isEmpty()) {
            nextFrontier.clear();

            for (int index = 0; index < currentFrontier.size(); index++) {
                expandGlobalState(currentFrontier.get(index), allStates, allStatesList, nextFrontier);
            }

            LongArrayList swap = currentFrontier;
            currentFrontier = nextFrontier;
            nextFrontier = swap;
        }

        return allStatesList;
    }

    private void expandGlobalState(long packedState, LongHashSet allStates, LongArrayList allStatesList, LongArrayList nextFrontier) {
        long occupied = blockedBitboard;
        for (int piece = 0; piece < pieceCount; piece++) {
            occupied |= pieceMoveBitboards[piece][getCoord(packedState, piece)];
        }

        for (int piece = 0; piece < pieceCount; piece++) {
            int startCoord = getCoord(packedState, piece);
            long currentPieceBitboard = pieceMoveBitboards[piece][startCoord];
            long blockers = occupied ^ currentPieceBitboard;

            long visitedAnchors = coordBits[startCoord];
            long frontier = visitedAnchors;

            while (frontier != 0L) {
                int coord = Long.numberOfTrailingZeros(frontier);
                frontier &= frontier - 1;

                long unexploredNeighbors = pieceNeighborMasks[piece][coord] & ~visitedAnchors;
                visitedAnchors |= unexploredNeighbors;

                while (unexploredNeighbors != 0L) {
                    int nextCoord = Long.numberOfTrailingZeros(unexploredNeighbors);
                    unexploredNeighbors &= unexploredNeighbors - 1;

                    long nextPieceBitboard = pieceMoveBitboards[piece][nextCoord];
                    if ((nextPieceBitboard & blockers) != 0L) {
                        continue;
                    }

                    frontier |= coordBits[nextCoord];

                    long newState = rewriteCoord(packedState, piece, nextCoord);
                    newState = canonicalizeStateIfNeeded(newState);
                    if (allStates.add(newState)) {
                        allStatesList.add(newState);
                        nextFrontier.add(newState);
                    }
                }
            }
        }
    }

    private void expandLocalState(
        long packedState,
        long startState,
        int nextDepth,
        LongHashSet visitedStates,
        LongArrayList nextFrontier,
        long[] positionsReachedByPiece,
        int[] bestLengthsLocal,
        long[] bestStartsLocal,
        long[] bestEndsLocal,
        boolean[] bestFoundLocal
    ) {
        long occupied = blockedBitboard;
        for (int piece = 0; piece < pieceCount; piece++) {
            occupied |= pieceMoveBitboards[piece][getCoord(packedState, piece)];
        }

        for (int piece = 0; piece < pieceCount; piece++) {
            int startCoord = getCoord(packedState, piece);
            long currentPieceBitboard = pieceMoveBitboards[piece][startCoord];
            long blockers = occupied ^ currentPieceBitboard;

            long visitedAnchors = coordBits[startCoord];
            long frontier = visitedAnchors;

            while (frontier != 0L) {
                int coord = Long.numberOfTrailingZeros(frontier);
                frontier &= frontier - 1;

                long unexploredNeighbors = pieceNeighborMasks[piece][coord] & ~visitedAnchors;
                visitedAnchors |= unexploredNeighbors;

                while (unexploredNeighbors != 0L) {
                    int nextCoord = Long.numberOfTrailingZeros(unexploredNeighbors);
                    unexploredNeighbors &= unexploredNeighbors - 1;

                    long nextPieceBitboard = pieceMoveBitboards[piece][nextCoord];
                    if ((nextPieceBitboard & blockers) != 0L) {
                        continue;
                    }

                    frontier |= coordBits[nextCoord];

                    long newState = rewriteCoord(packedState, piece, nextCoord);
                    newState = canonicalizeStateIfNeeded(newState);
                    if (visitedStates.add(newState)) {
                        nextFrontier.add(newState);

                        long coordBit = coordBits[nextCoord];
                        if ((positionsReachedByPiece[piece] & coordBit) == 0L) {
                            positionsReachedByPiece[piece] |= coordBit;
                            if (nextDepth > bestLengthsLocal[piece]) {
                                bestLengthsLocal[piece] = nextDepth;
                                bestStartsLocal[piece] = startState;
                                bestEndsLocal[piece] = newState;
                                bestFoundLocal[piece] = true;
                            }
                        }
                    }
                }
            }
        }
    }

    private int[][] buildIdenticalPieceGroups(long[] normalizedPieceShapes) {
        int[][] groups = new int[pieceCount][];
        boolean[] grouped = new boolean[pieceCount];
        int groupCount = 0;

        for (int piece = 0; piece < pieceCount; piece++) {
            if (grouped[piece]) {
                continue;
            }

            int matches = 1;
            for (int other = piece + 1; other < pieceCount; other++) {
                if (normalizedPieceShapes[piece] == normalizedPieceShapes[other]) {
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
                if (normalizedPieceShapes[piece] == normalizedPieceShapes[other]) {
                    group[index++] = other;
                    grouped[other] = true;
                }
            }
            groups[groupCount++] = group;
        }

        return Arrays.copyOf(groups, groupCount);
    }

    private long canonicalizeStateIfNeeded(long packedState) {
        if (!deduplicateIdenticalPiecePermutations || identicalPieceGroups.length == 0) {
            return packedState;
        }

        long canonicalState = packedState;
        for (int groupIndex = 0; groupIndex < identicalPieceGroups.length; groupIndex++) {
            int[] group = identicalPieceGroups[groupIndex];
            int[] coords = new int[group.length];
            for (int i = 0; i < group.length; i++) {
                coords[i] = getCoord(canonicalState, group[i]);
            }

            for (int i = 1; i < coords.length; i++) {
                int coord = coords[i];
                int j = i - 1;
                while (j >= 0 && coords[j] > coord) {
                    coords[j + 1] = coords[j];
                    j--;
                }
                coords[j + 1] = coord;
            }

            for (int i = 0; i < group.length; i++) {
                canonicalState = rewriteCoord(canonicalState, group[i], coords[i]);
            }
        }
        return canonicalState;
    }

    private int getCoord(long packedState, int piece) {
        return (int) ((packedState >>> pieceStateShifts[piece]) & coordMask);
    }

    private long rewriteCoord(long packedState, int piece, int coord) {
        return (packedState & pieceStateClearMasks[piece]) | pieceStateBits[piece][coord];
    }

    private int[][] packedStateToGridWithBlocks(long packedState) {
        int[][] grid = new int[height][width];
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                int coord = r * width + c;
                if ((blockedBitboard & coordBits[coord]) != 0L) {
                    grid[r][c] = -1;
                }
            }
        }

        for (int piece = 0; piece < pieceCount; piece++) {
            long bits = pieceMoveBitboards[piece][getCoord(packedState, piece)];
            while (bits != 0L) {
                int coord = Long.numberOfTrailingZeros(bits);
                grid[coord / width][coord % width] = piece + 1;
                bits &= bits - 1;
            }
        }
        return grid;
    }

    private int[][] packedStateToEvaluatorGrid(long packedState) {
        int[][] grid = new int[height][width];
        int obstacleValue = pieceCount + 1;
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                int coord = r * width + c;
                if ((blockedBitboard & coordBits[coord]) != 0L) {
                    grid[r][c] = obstacleValue;
                }
            }
        }

        for (int piece = 0; piece < pieceCount; piece++) {
            long bits = pieceMoveBitboards[piece][getCoord(packedState, piece)];
            while (bits != 0L) {
                int coord = Long.numberOfTrailingZeros(bits);
                grid[coord / width][coord % width] = piece + 1;
                bits &= bits - 1;
            }
        }
        return grid;
    }

    private String stateToStringInline(long packedState) {
        int[][] grid = packedStateToGridWithBlocks(packedState);

        StringBuilder sb = new StringBuilder();
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                sb.append(grid[r][c]);
                if (c < width - 1) {
                    sb.append(" ");
                }
            }
            if (r < height - 1) {
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