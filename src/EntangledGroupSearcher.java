import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.BitSet;

public class EntangledGroupSearcher {

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

        void clear() {
            size = 0;
        }
    }

    private static final class PackingData {
        final LongArrayList packingsA;
        final LongArrayList packingsB;
        final long packingCount;

        PackingData(LongArrayList packingsA, LongArrayList packingsB, long packingCount) {
            this.packingsA = packingsA;
            this.packingsB = packingsB;
            this.packingCount = packingCount;
        }
    }

    private static final class StatePairArrayList {
        private long[] statesA;
        private long[] statesB;
        private int size;

        StatePairArrayList(int capacity) {
            int initialCapacity = Math.max(1, capacity);
            statesA = new long[initialCapacity];
            statesB = new long[initialCapacity];
        }

        void add(long stateA, long stateB) {
            if (size == statesA.length) {
                int nextCapacity = statesA.length << 1;
                long[] nextA = new long[nextCapacity];
                long[] nextB = new long[nextCapacity];
                System.arraycopy(statesA, 0, nextA, 0, statesA.length);
                System.arraycopy(statesB, 0, nextB, 0, statesB.length);
                statesA = nextA;
                statesB = nextB;
            }
            statesA[size] = stateA;
            statesB[size] = stateB;
            size++;
        }

        long getStateA(int index) {
            return statesA[index];
        }

        long getStateB(int index) {
            return statesB[index];
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

    private static final class LongIndexLookup {
        private final int[] slots;
        private final int mask;
        private final LongArrayList states;

        LongIndexLookup(LongArrayList states) {
            this.states = states;

            int capacity = 1;
            int minCapacity = Math.max(16, states.size() << 1);
            while (capacity < minCapacity) capacity <<= 1;

            slots = new int[capacity];
            mask = capacity - 1;

            for (int stateIndex = 0; stateIndex < states.size(); stateIndex++) {
                insert(stateIndex);
            }
        }

        int findIndex(long state) {
            int index = mix(state) & mask;
            while (slots[index] != 0) {
                int stateIndex = slots[index] - 1;
                if (states.get(stateIndex) == state) {
                    return stateIndex;
                }
                index = (index + 1) & mask;
            }
            return -1;
        }

        private void insert(int stateIndex) {
            long state = states.get(stateIndex);

            int index = mix(state) & mask;
            while (slots[index] != 0) {
                index = (index + 1) & mask;
            }
            slots[index] = stateIndex + 1;
        }

        private static int mix(long state) {
            long value = state;
            value ^= (value >>> 33);
            value *= 0xff51afd7ed558ccdL;
            value ^= (value >>> 33);
            value *= 0xc4ceb9fe1a85ec53L;
            value ^= (value >>> 33);
            return (int) value;
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
                generation = 1;
                for (int index = 0; index < generations.length; index++) {
                    generations[index] = 0;
                }
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

    private static final class StatePairHashSet {
        private long[] keysA;
        private long[] keysB;
        private int[] generations;
        private int generation;
        private int mask;
        private int size;
        private int threshold;

        StatePairHashSet(int expectedSize) {
            int capacity = 1;
            int minCapacity = Math.max(16, expectedSize << 1);
            while (capacity < minCapacity) capacity <<= 1;
            allocate(capacity);
            generation = 1;
        }

        boolean add(long stateA, long stateB) {
            if (size >= threshold) {
                resize();
            }

            int index = mix(stateA, stateB) & mask;
            while (generations[index] == generation) {
                if (keysA[index] == stateA && keysB[index] == stateB) {
                    return false;
                }
                index = (index + 1) & mask;
            }

            generations[index] = generation;
            keysA[index] = stateA;
            keysB[index] = stateB;
            size++;
            return true;
        }

        boolean contains(long stateA, long stateB) {
            int index = mix(stateA, stateB) & mask;
            while (generations[index] == generation) {
                if (keysA[index] == stateA && keysB[index] == stateB) {
                    return true;
                }
                index = (index + 1) & mask;
            }
            return false;
        }

        void clear() {
            size = 0;
            generation++;
            if (generation == 0) {
                generation = 1;
                for (int index = 0; index < generations.length; index++) {
                    generations[index] = 0;
                }
            }
        }

        private void allocate(int capacity) {
            keysA = new long[capacity];
            keysB = new long[capacity];
            generations = new int[capacity];
            mask = capacity - 1;
            threshold = capacity >> 1;
            size = 0;
        }

        private void resize() {
            long[] oldKeysA = keysA;
            long[] oldKeysB = keysB;
            int[] oldGenerations = generations;
            int oldGeneration = generation;

            allocate(oldKeysA.length << 1);
            generation = 1;

            for (int index = 0; index < oldKeysA.length; index++) {
                if (oldGenerations[index] == oldGeneration) {
                    insertRehashed(oldKeysA[index], oldKeysB[index]);
                }
            }
        }

        private void insertRehashed(long stateA, long stateB) {
            int index = mix(stateA, stateB) & mask;
            while (generations[index] == generation) {
                index = (index + 1) & mask;
            }
            generations[index] = generation;
            keysA[index] = stateA;
            keysB[index] = stateB;
            size++;
        }

        private static int mix(long stateA, long stateB) {
            long value = stateA * 0x9e3779b97f4a7c15L ^ Long.rotateLeft(stateB, 23);
            value ^= (value >>> 33);
            value *= 0xff51afd7ed558ccdL;
            value ^= (value >>> 33);
            value *= 0xc4ceb9fe1a85ec53L;
            value ^= (value >>> 33);
            return (int) value;
        }
    }

    private static final class BoardData {
        final long[] pieces;
        final long[][] pieceMoveBitboards;
        final int[][] pieceValidCoords;
        final int[][][] pieceStepNeighbors;

        BoardData(long[] pieces, long[][] pieceMoveBitboards, int[][] pieceValidCoords, int[][][] pieceStepNeighbors) {
            this.pieces = pieces;
            this.pieceMoveBitboards = pieceMoveBitboards;
            this.pieceValidCoords = pieceValidCoords;
            this.pieceStepNeighbors = pieceStepNeighbors;
        }
    }

    private static final class BfsResult {
        final int depth;
        final long farthestStateA;
        final long farthestStateB;

        BfsResult(int depth, long farthestStateA, long farthestStateB) {
            this.depth = depth;
            this.farthestStateA = farthestStateA;
            this.farthestStateB = farthestStateB;
        }
    }

    private static final class SearchSummary {
        final long packings;
        final int islands;
        final int maxDiameter;
        final long maxDiameterStartStateA;
        final long maxDiameterStartStateB;
        final long maxDiameterEndStateA;
        final long maxDiameterEndStateB;
        final double timeMs;

        SearchSummary(
            long packings,
            int islands,
            int maxDiameter,
            long maxDiameterStartStateA,
            long maxDiameterStartStateB,
            long maxDiameterEndStateA,
            long maxDiameterEndStateB,
            double timeMs
        ) {
            this.packings = packings;
            this.islands = islands;
            this.maxDiameter = maxDiameter;
            this.maxDiameterStartStateA = maxDiameterStartStateA;
            this.maxDiameterStartStateB = maxDiameterStartStateB;
            this.maxDiameterEndStateA = maxDiameterEndStateA;
            this.maxDiameterEndStateB = maxDiameterEndStateB;
            this.timeMs = timeMs;
        }
    }

    private final long[] coordBits;
    private final int width;
    private final int height;
    private final int cellCount;
    private final int pieceCount;
    private final int pairCoordCount;
    private final long blockedBitboard;
    private final int packingsLowerLimit;
    private final int packingsUpperLimit;
    private final int interestingDiameterThreshold;
    private final boolean connectedAB;
    private final boolean deduplicateIdenticalPiecePermutations;
    private final BoardData boardA;
    private final BoardData boardB;
    private final int coordBitWidth;
    private final long coordMask;
    private final int[] pieceStateShifts;
    private final long[] pieceStateClearMasks;
    private final long[][] pieceStateBits;
    private final int[][] identicalPiecePairGroups;
    private final long[] canonicalStateScratch;
    private final int[] pairedCoordVisitGenerations;
    private int pairedCoordVisitGeneration;
    private final int[] pairedCoordQueueA;
    private final int[] pairedCoordQueueB;
    private int maxDiameterEstimate;
    private long maxDiameterStartStateA;
    private long maxDiameterStartStateB;
    private long maxDiameterEndStateA;
    private long maxDiameterEndStateB;
    private int islands;
    private long startTime;

    public EntangledGroupSearcher(
        PieceGrouper.EntangledGroupPair groupPair,
        int width,
        int height,
        int packingsLowerLimit,
        int packingsUpperLimit,
        int interestingDiameterThreshold,
        boolean connectedAB,
        long blockedBitboard,
        boolean deduplicateIdenticalPiecePermutations
    ) {
        this.width = width;
        this.height = height;
        this.cellCount = width * height;
        this.pieceCount = groupPair.groupA.length;
        this.pairCoordCount = cellCount * cellCount;
        this.packingsLowerLimit = packingsLowerLimit;
        this.packingsUpperLimit = packingsUpperLimit;
        this.interestingDiameterThreshold = interestingDiameterThreshold;
        this.connectedAB = connectedAB;
        this.blockedBitboard = blockedBitboard;
        this.deduplicateIdenticalPiecePermutations = deduplicateIdenticalPiecePermutations;

        coordBits = new long[cellCount];
        for (int coord = 0; coord < cellCount; coord++) {
            coordBits[coord] = 1L << coord;
        }

        boardA = createBoardData(groupPair.groupA);
        boardB = createBoardData(groupPair.groupB);

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

        identicalPiecePairGroups = buildIdenticalPiecePairGroups();
        canonicalStateScratch = new long[2];
        pairedCoordVisitGenerations = new int[pairCoordCount];
        pairedCoordVisitGeneration = 1;
        pairedCoordQueueA = new int[pairCoordCount];
        pairedCoordQueueB = new int[pairCoordCount];
    }

    public String explore(String prefix) {
        StringBuilder result = new StringBuilder();
        startTime = System.nanoTime();
        result.append(prefix);

        PieceGrouper grouper = new PieceGrouper();
        result.append(grouper.groupToPieceCodes(boardA.pieces, width)).append(',');
        result.append(grouper.groupToPieceCodes(boardB.pieces, width)).append(',');

        SearchSummary summary = searchGroup();
        // String outputFileLive = "live_packings.csv";
        // try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFileLive, true))) {
        //     File file = new File(outputFileLive);
        //     if (file.length() == 0) {
        //         writer.write("GroupID,PiecesA,PiecesB,Packings,TimeTaken(ms)\n");
        //     }
        //     writer.write(prefix + "," + grouper.groupToPieceCodes(boardA.pieces, width) + "," + grouper.groupToPieceCodes(boardB.pieces, width) + "," + summary.packings + "," + summary.timeMs + "\n");
        // } catch (IOException e) {
        //     e.printStackTrace();
        // }

        if (summary.packings < packingsLowerLimit || summary.packings > packingsUpperLimit) {
            result.append('>').append(packingsUpperLimit).append(",N/A,N/A,,,,," ).append(summary.timeMs).append('\n');
            return "";
        }
        if (summary.packings == 0L) {
            result.append("0,0,0,,,,,").append(summary.timeMs).append('\n');
            return "";
        }

        result.append(summary.packings).append(',');
        result.append(summary.islands).append(',');
        result.append(summary.maxDiameter).append(',');
        result.append(stateToStringInline(summary.maxDiameterStartStateA, boardA)).append(',');
        result.append(stateToStringInline(summary.maxDiameterStartStateB, boardB)).append(',');
        result.append(stateToStringInline(summary.maxDiameterEndStateA, boardA)).append(',');
        result.append(stateToStringInline(summary.maxDiameterEndStateB, boardB)).append(',');
        result.append(summary.timeMs).append('\n');

        if (summary.maxDiameter > interestingDiameterThreshold) {
            String outputFile = "interesting_groups_found_so_far.csv";
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile, true))) {
                File file = new File(outputFile);
                if (file.length() == 0) {
                    writer.write("GroupID,PiecesA,PiecesB,Packings,Islands,MaxEstimatedDiameter,StartStateA,StartStateB,EndStateA,EndStateB,TimeTaken(ms)\n");
                }
                writer.write(result.toString());
            } catch (IOException e) {
                e.printStackTrace();
            }

            // System.out.println(statePairToSideBySideString(summary.maxDiameterStartStateA, summary.maxDiameterStartStateB));
            // System.out.println();
            // System.out.println(statePairToSideBySideString(summary.maxDiameterEndStateA, summary.maxDiameterEndStateB));
        }

        return result.toString();
    }

    public int estimateDiameter() {
        SearchSummary summary = searchGroup();
        if (summary.packings == 0L || summary.packings > packingsUpperLimit) {
            return -1;
        }
        return summary.maxDiameter;
    }

    private SearchSummary searchGroup() {
        PackingData packingData = collectPackings();
        long packingCount = packingData.packingCount;
        double timeMs;

        if (packingCount > packingsUpperLimit) {
            timeMs = stopTimer();
            return new SearchSummary(packingCount, 0, -1, 0L, 0L, 0L, 0L, timeMs);
        }
        if (packingCount == 0L) {
            timeMs = stopTimer();
            return new SearchSummary(0L, 0, -1, 0L, 0L, 0L, 0L, timeMs);
        }

        LongArrayList packingsA = packingData.packingsA;
        LongArrayList packingsB = packingData.packingsB;
        int packingsBCount = packingsB.size();
        int initialTraversalCapacity = Math.max(16, (int) Math.min(packingCount, 4096L));
        LongIndexLookup packingIndexLookupA = new LongIndexLookup(packingsA);
        LongIndexLookup packingIndexLookupB = new LongIndexLookup(packingsB);
        BitSet assignedPackingIndices = new BitSet((int) ((long) packingsA.size() * packingsBCount));
        StatePairHashSet bfsVisited = new StatePairHashSet(initialTraversalCapacity);
        StatePairArrayList currentFrontier = new StatePairArrayList(1024);
        StatePairArrayList nextFrontier = new StatePairArrayList(1024);

        islands = 0;
        maxDiameterEstimate = 0;
        maxDiameterStartStateA = 0L;
        maxDiameterStartStateB = 0L;
        maxDiameterEndStateA = 0L;
        maxDiameterEndStateB = 0L;

        for (int indexA = 0; indexA < packingsA.size(); indexA++) {
            long rawStartStateA = packingsA.get(indexA);
            for (int indexB = 0; indexB < packingsBCount; indexB++) {
                long rawStartStateB = packingsB.get(indexB);
                canonicalizeStateIfNeeded(rawStartStateA, rawStartStateB);
                long startStateA = canonicalStateScratch[0];
                long startStateB = canonicalStateScratch[1];
                if (!satisfiesConnectedAB(startStateA, startStateB)) {
                    continue;
                }

                int startPackingIndex = getPackingIndex(
                    startStateA,
                    startStateB,
                    packingIndexLookupA,
                    packingIndexLookupB,
                    packingsBCount
                );
                if (assignedPackingIndices.get(startPackingIndex)) {
                    continue;
                }

                BfsResult first = doBfs(
                    startStateA,
                    startStateB,
                    bfsVisited,
                    currentFrontier,
                    nextFrontier,
                    assignedPackingIndices,
                    packingIndexLookupA,
                    packingIndexLookupB,
                    packingsBCount,
                    true
                );
                BfsResult second = doBfs(
                    first.farthestStateA,
                    first.farthestStateB,
                    bfsVisited,
                    currentFrontier,
                    nextFrontier,
                    assignedPackingIndices,
                    packingIndexLookupA,
                    packingIndexLookupB,
                    packingsBCount,
                    false
                );

                int diameterEstimate = Math.max(first.depth, second.depth);
                if (diameterEstimate > maxDiameterEstimate) {
                    maxDiameterEstimate = diameterEstimate;
                    maxDiameterStartStateA = first.farthestStateA;
                    maxDiameterStartStateB = first.farthestStateB;
                    maxDiameterEndStateA = second.farthestStateA;
                    maxDiameterEndStateB = second.farthestStateB;
                }
                islands++;
            }
        }

        timeMs = stopTimer();
        return new SearchSummary(
            packingCount,
            islands,
            maxDiameterEstimate,
            maxDiameterStartStateA,
            maxDiameterStartStateB,
            maxDiameterEndStateA,
            maxDiameterEndStateB,
            timeMs
        );
    }

    public String statePairToSideBySideString(long stateA, long stateB) {
        int[][] gridA = packedStateToGrid(stateA, boardA);
        int[][] gridB = packedStateToGrid(stateB, boardB);
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

    private BoardData createBoardData(long[] sourcePieces) {
        long[] normalizedPieces = new long[pieceCount];
        int[] pieceWidth = new int[pieceCount];
        int[] pieceHeight = new int[pieceCount];
        long[][] pieceMoveBitboards = new long[pieceCount][cellCount];
        int[][] pieceValidCoords = new int[pieceCount][];
        int[][][] pieceStepNeighbors = new int[pieceCount][cellCount][4];

        int[] minRow = new int[pieceCount];
        int[] minCol = new int[pieceCount];
        int[] maxRow = new int[pieceCount];
        int[] maxCol = new int[pieceCount];

        for (int piece = 0; piece < pieceCount; piece++) {
            for (int coord = 0; coord < cellCount; coord++) {
                for (int direction = 0; direction < 4; direction++) {
                    pieceStepNeighbors[piece][coord][direction] = -1;
                }
            }

            // A blank is virtual: it occupies no cells and has one canonical coordinate.
            // Its paired real piece moves independently; the blank coordinate never needs
            // to become part of the packed state space.
            if (sourcePieces[piece] == 0L) {
                minRow[piece] = 0;
                minCol[piece] = 0;
                maxRow[piece] = 0;
                maxCol[piece] = 0;
                pieceWidth[piece] = 1;
                pieceHeight[piece] = 1;
                normalizedPieces[piece] = 0L;
                pieceValidCoords[piece] = new int[]{0};
                pieceMoveBitboards[piece][0] = 0L;
                continue;
            }

            minRow[piece] = height;
            minCol[piece] = width;
            long shape = sourcePieces[piece];
            while (shape != 0L) {
                int coord = Long.numberOfTrailingZeros(shape);
                int row = coord / width;
                int col = coord % width;
                if (row < minRow[piece]) minRow[piece] = row;
                if (col < minCol[piece]) minCol[piece] = col;
                if (row > maxRow[piece]) maxRow[piece] = row;
                if (col > maxCol[piece]) maxCol[piece] = col;
                shape &= shape - 1;
            }

            pieceWidth[piece] = maxCol[piece] - minCol[piece] + 1;
            pieceHeight[piece] = maxRow[piece] - minRow[piece] + 1;
            normalizedPieces[piece] = sourcePieces[piece] >> (width * minRow[piece] + minCol[piece]);

            int validCoordCount = (height + 1 - pieceHeight[piece]) * (width + 1 - pieceWidth[piece]);
            int[] validCoords = new int[validCoordCount];
            int coordIndex = 0;
            for (int row = 0; row < height + 1 - pieceHeight[piece]; row++) {
                for (int col = 0; col < width + 1 - pieceWidth[piece]; col++) {
                    int coord = row * width + col;
                    validCoords[coordIndex++] = coord;
                    pieceMoveBitboards[piece][coord] = normalizedPieces[piece] << coord;
                }
            }
            pieceValidCoords[piece] = validCoords;

            for (int index = 0; index < validCoords.length; index++) {
                int coord = validCoords[index];
                int row = coord / width;
                int col = coord % width;

                if (row > 0) {
                    int nextCoord = (row - 1) * width + col;
                    if (row - 1 <= height - pieceHeight[piece]) {
                        pieceStepNeighbors[piece][coord][0] = nextCoord;
                    }
                }
                if (row < height - pieceHeight[piece]) {
                    pieceStepNeighbors[piece][coord][1] = (row + 1) * width + col;
                }
                if (col > 0) {
                    int nextCoord = row * width + (col - 1);
                    if (col - 1 <= width - pieceWidth[piece]) {
                        pieceStepNeighbors[piece][coord][2] = nextCoord;
                    }
                }
                if (col < width - pieceWidth[piece]) {
                    pieceStepNeighbors[piece][coord][3] = row * width + (col + 1);
                }
            }
        }

        return new BoardData(normalizedPieces, pieceMoveBitboards, pieceValidCoords, pieceStepNeighbors);
    }

    private PackingData collectPackings() {
        LongArrayList packingsA = new LongArrayList(4096);
        if (!packBoard(boardA, packingsA, blockedBitboard, pieceCount - 1, 0L)) {
            return new PackingData(packingsA, new LongArrayList(1), packingsUpperLimit + 1L);
        }

        LongArrayList packingsB = new LongArrayList(4096);
        if (!packBoard(boardB, packingsB, blockedBitboard, pieceCount - 1, 0L)) {
            return new PackingData(packingsA, packingsB, packingsUpperLimit + 1L);
        }

        long cartesianProduct = (long) packingsA.size() * (long) packingsB.size();
        if (cartesianProduct > packingsUpperLimit) {
            return new PackingData(packingsA, packingsB, cartesianProduct);
        }

        if (!deduplicateIdenticalPiecePermutations && !connectedAB) {
            return new PackingData(packingsA, packingsB, cartesianProduct);
        }

        LongIndexLookup packingIndexLookupA = deduplicateIdenticalPiecePermutations
            ? new LongIndexLookup(packingsA)
            : null;
        LongIndexLookup packingIndexLookupB = deduplicateIdenticalPiecePermutations
            ? new LongIndexLookup(packingsB)
            : null;
        BitSet uniquePackingIndices = deduplicateIdenticalPiecePermutations
            ? new BitSet((int) cartesianProduct)
            : null;
        long filteredPackingCount = 0L;

        for (int indexA = 0; indexA < packingsA.size(); indexA++) {
            long stateA = packingsA.get(indexA);
            for (int indexB = 0; indexB < packingsB.size(); indexB++) {
                long stateB = packingsB.get(indexB);
                canonicalizeStateIfNeeded(stateA, stateB);
                long canonicalStateA = canonicalStateScratch[0];
                long canonicalStateB = canonicalStateScratch[1];

                if (!satisfiesConnectedAB(canonicalStateA, canonicalStateB)) {
                    continue;
                }

                if (uniquePackingIndices == null) {
                    filteredPackingCount++;
                    continue;
                }

                int packingIndex = getPackingIndex(
                    canonicalStateA,
                    canonicalStateB,
                    packingIndexLookupA,
                    packingIndexLookupB,
                    packingsB.size()
                );
                if (!uniquePackingIndices.get(packingIndex)) {
                    uniquePackingIndices.set(packingIndex);
                    filteredPackingCount++;
                }
            }
        }

        return new PackingData(packingsA, packingsB, filteredPackingCount);
    }

    private boolean packBoard(BoardData board, LongArrayList packings, long occupied, int piece, long packedState) {
        if (piece == -1) {
            packings.add(packedState);
            return packings.size() <= packingsUpperLimit;
        }

        int[] validCoords = board.pieceValidCoords[piece];
        for (int index = 0; index < validCoords.length; index++) {
            int coord = validCoords[index];
            long pieceBitboard = board.pieceMoveBitboards[piece][coord];
            if ((occupied & pieceBitboard) == 0L) {
                long nextState = rewriteCoord(packedState, piece, coord);
                if (!packBoard(board, packings, occupied | pieceBitboard, piece - 1, nextState)) {
                    return false;
                }
            }
        }
        return true;
    }

    private BfsResult doBfs(
        long startStateA,
        long startStateB,
        StatePairHashSet visitedStates,
        StatePairArrayList currentFrontier,
        StatePairArrayList nextFrontier,
        BitSet assignedPackingIndices,
        LongIndexLookup packingIndexLookupA,
        LongIndexLookup packingIndexLookupB,
        int packingsBCount,
        boolean markAssignedPackingIndices
    ) {
        visitedStates.clear();
        currentFrontier.clear();
        nextFrontier.clear();

        canonicalizeStateIfNeeded(startStateA, startStateB);
        startStateA = canonicalStateScratch[0];
        startStateB = canonicalStateScratch[1];

        visitedStates.add(startStateA, startStateB);
        if (markAssignedPackingIndices) {
            markAssignedPackingIndex(
                startStateA,
                startStateB,
                assignedPackingIndices,
                packingIndexLookupA,
                packingIndexLookupB,
                packingsBCount
            );
        }
        currentFrontier.add(startStateA, startStateB);

        int depth = 0;
        long farthestStateA = startStateA;
        long farthestStateB = startStateB;
        while (!currentFrontier.isEmpty()) {
            nextFrontier.clear();
            int nextDepth = depth + 1;

            for (int index = 0; index < currentFrontier.size(); index++) {
                expandState(
                    currentFrontier.getStateA(index),
                    currentFrontier.getStateB(index),
                    visitedStates,
                    nextFrontier,
                    assignedPackingIndices,
                    packingIndexLookupA,
                    packingIndexLookupB,
                    packingsBCount,
                    markAssignedPackingIndices
                );
            }

            if (nextFrontier.isEmpty()) {
                break;
            }

            farthestStateA = nextFrontier.getStateA(0);
            farthestStateB = nextFrontier.getStateB(0);
            StatePairArrayList swap = currentFrontier;
            currentFrontier = nextFrontier;
            nextFrontier = swap;
            depth = nextDepth;
        }

        return new BfsResult(depth, farthestStateA, farthestStateB);
    }

    private void expandState(
        long packedStateA,
        long packedStateB,
        StatePairHashSet visitedStates,
        StatePairArrayList nextFrontier,
        BitSet assignedPackingIndices,
        LongIndexLookup packingIndexLookupA,
        LongIndexLookup packingIndexLookupB,
        int packingsBCount,
        boolean markAssignedPackingIndices
    ) {
        long occupiedA = blockedBitboard;
        long occupiedB = blockedBitboard;
        for (int piece = 0; piece < pieceCount; piece++) {
            occupiedA |= boardA.pieceMoveBitboards[piece][getCoord(packedStateA, piece)];
            occupiedB |= boardB.pieceMoveBitboards[piece][getCoord(packedStateB, piece)];
        }

        for (int piece = 0; piece < pieceCount; piece++) {
            int startCoordA = getCoord(packedStateA, piece);
            int startCoordB = getCoord(packedStateB, piece);
            long currentPieceBitboardA = boardA.pieceMoveBitboards[piece][startCoordA];
            long currentPieceBitboardB = boardB.pieceMoveBitboards[piece][startCoordB];
            boolean blankA = boardA.pieces[piece] == 0L;
            boolean blankB = boardB.pieces[piece] == 0L;
            if (blankA && blankB) {
                continue;
            }
            long blockersA = occupiedA ^ currentPieceBitboardA;
            long blockersB = occupiedB ^ currentPieceBitboardB;

            int queueHead = 0;
            int queueTail = 1;
            int startPairCoord = startCoordA * cellCount + startCoordB;
            int visitGeneration = nextPairCoordVisitGeneration();
            pairedCoordVisitGenerations[startPairCoord] = visitGeneration;
            pairedCoordQueueA[0] = startCoordA;
            pairedCoordQueueB[0] = startCoordB;

            while (queueHead < queueTail) {
                int coordA = pairedCoordQueueA[queueHead];
                int coordB = pairedCoordQueueB[queueHead];
                queueHead++;

                for (int direction = 0; direction < 4; direction++) {
                    int nextCoordA = blankA ? coordA : boardA.pieceStepNeighbors[piece][coordA][direction];
                    int nextCoordB = blankB ? coordB : boardB.pieceStepNeighbors[piece][coordB][direction];
                    if ((!blankA && nextCoordA == -1) || (!blankB && nextCoordB == -1)) {
                        continue;
                    }
                    if (!blankA && !blankB
                        && !preservesRelativeOffset(coordA, coordB, nextCoordA, nextCoordB)) {
                        continue;
                    }

                    long nextPieceBitboardA = boardA.pieceMoveBitboards[piece][nextCoordA];
                    if ((nextPieceBitboardA & blockersA) != 0L) {
                        continue;
                    }

                    long nextPieceBitboardB = boardB.pieceMoveBitboards[piece][nextCoordB];
                    if ((nextPieceBitboardB & blockersB) != 0L) {
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

                    long newStateA = rewriteCoord(packedStateA, piece, nextCoordA);
                    long newStateB = rewriteCoord(packedStateB, piece, nextCoordB);
                    canonicalizeStateIfNeeded(newStateA, newStateB);
                    newStateA = canonicalStateScratch[0];
                    newStateB = canonicalStateScratch[1];
                    if (!satisfiesConnectedAB(newStateA, newStateB)) {
                        continue;
                    }
                    if (visitedStates.add(newStateA, newStateB)) {
                        nextFrontier.add(newStateA, newStateB);
                        if (markAssignedPackingIndices) {
                            markAssignedPackingIndex(
                                newStateA,
                                newStateB,
                                assignedPackingIndices,
                                packingIndexLookupA,
                                packingIndexLookupB,
                                packingsBCount
                            );
                        }
                    }
                }
            }
        }
    }

    private void markAssignedPackingIndex(
        long stateA,
        long stateB,
        BitSet assignedPackingIndices,
        LongIndexLookup packingIndexLookupA,
        LongIndexLookup packingIndexLookupB,
        int packingsBCount
    ) {
        assignedPackingIndices.set(getPackingIndex(stateA, stateB, packingIndexLookupA, packingIndexLookupB, packingsBCount));
    }

    private int getPackingIndex(
        long stateA,
        long stateB,
        LongIndexLookup packingIndexLookupA,
        LongIndexLookup packingIndexLookupB,
        int packingsBCount
    ) {
        int packingIndexA = packingIndexLookupA.findIndex(stateA);
        int packingIndexB = packingIndexLookupB.findIndex(stateB);
        if (packingIndexA < 0 || packingIndexB < 0) {
            throw new IllegalStateException("State not found in board packing lookup");
        }
        return packingIndexA * packingsBCount + packingIndexB;
    }

    private boolean satisfiesConnectedAB(long packedStateA, long packedStateB) {
        if (!connectedAB) {
            return true;
        }

        for (int piece = 0; piece < pieceCount; piece++) {
            long pieceBitboardA = boardA.pieceMoveBitboards[piece][getCoord(packedStateA, piece)];
            long pieceBitboardB = boardB.pieceMoveBitboards[piece][getCoord(packedStateB, piece)];
            if (pieceBitboardA == 0L || pieceBitboardB == 0L) {
                continue;
            }
            if ((pieceBitboardA & pieceBitboardB) == 0L) {
                return false;
            }
        }

        return true;
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
                if (boardA.pieces[piece] == boardA.pieces[other] && boardB.pieces[piece] == boardB.pieces[other]) {
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
                if (boardA.pieces[piece] == boardA.pieces[other] && boardB.pieces[piece] == boardB.pieces[other]) {
                    group[index++] = other;
                    grouped[other] = true;
                }
            }
            groups[groupCount++] = group;
        }

        int[][] trimmed = new int[groupCount][];
        for (int index = 0; index < groupCount; index++) {
            trimmed[index] = groups[index];
        }
        return trimmed;
    }

    private void canonicalizeStateIfNeeded(long stateA, long stateB) {
        canonicalStateScratch[0] = stateA;
        canonicalStateScratch[1] = stateB;
        if (!deduplicateIdenticalPiecePermutations || identicalPiecePairGroups.length == 0) {
            return;
        }

        for (int groupIndex = 0; groupIndex < identicalPiecePairGroups.length; groupIndex++) {
            int[] group = identicalPiecePairGroups[groupIndex];
            int[] coordsA = new int[group.length];
            int[] coordsB = new int[group.length];
            for (int index = 0; index < group.length; index++) {
                coordsA[index] = getCoord(canonicalStateScratch[0], group[index]);
                coordsB[index] = getCoord(canonicalStateScratch[1], group[index]);
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
                canonicalStateScratch[0] = rewriteCoord(canonicalStateScratch[0], group[index], coordsA[index]);
                canonicalStateScratch[1] = rewriteCoord(canonicalStateScratch[1], group[index], coordsB[index]);
            }
        }
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
            for (int index = 0; index < pairedCoordVisitGenerations.length; index++) {
                pairedCoordVisitGenerations[index] = 0;
            }
        }
        return pairedCoordVisitGeneration;
    }

    private int getCoord(long packedState, int piece) {
        return (int) ((packedState >>> pieceStateShifts[piece]) & coordMask);
    }

    private boolean preservesRelativeOffset(int coordA, int coordB, int nextCoordA, int nextCoordB) {
        int rowOffset = coordB / width - coordA / width;
        int colOffset = coordB % width - coordA % width;
        return rowOffset == nextCoordB / width - nextCoordA / width
            && colOffset == nextCoordB % width - nextCoordA % width;
    }

    private long rewriteCoord(long packedState, int piece, int coord) {
        return (packedState & pieceStateClearMasks[piece]) | pieceStateBits[piece][coord];
    }

    private int[][] packedStateToGrid(long packedState, BoardData board) {
        int[][] grid = new int[height][width];
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int coord = row * width + col;
                if ((blockedBitboard & coordBits[coord]) != 0L) {
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
        int[][] grid = packedStateToGrid(packedState, board);
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

    private double stopTimer() {
        return (System.nanoTime() - startTime) / 1_000_000.0;
    }

    private static int bitsNeeded(int maxValue) {
        return maxValue <= 0 ? 1 : 32 - Integer.numberOfLeadingZeros(maxValue);
    }
}