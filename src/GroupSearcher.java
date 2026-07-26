import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;

public class GroupSearcher {

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

        boolean contains(long value) {
            int index = mix(value) & mask;
            while (generations[index] == generation) {
                if (keys[index] == value) {
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
                for (int i = 0; i < generations.length; i++) {
                    generations[i] = 0;
                }
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

    private static final class BfsResult {
        final int depth;
        final long farthestState;

        BfsResult(int depth, long farthestState) {
            this.depth = depth;
            this.farthestState = farthestState;
        }
    }

    private final int width;
    private final int height;
    private final int cellCount;
    private final int pieceCount;
    private final long blockedBitboard;
    private final long[] pieces;
    private final boolean deduplicateIdenticalPiecePermutations;

    private final int[] pieceWidth;
    private final int[] pieceHeight;
    private final long[][] pieceMoveBitboards;
    private final long[] pieceValidAnchorMasks;
    private final int[][] pieceValidCoords;
    private final long[][] pieceNeighborMasks;
    private final int[][] identicalPieceGroups;

    private final long[] coordBits;
    private final long[] orthoBitboards;

    private final int coordBitWidth;
    private final long coordMask;
    private final int[] pieceStateShifts;
    private final long[] pieceStateClearMasks;
    private final long[][] pieceStateBits;

    private final int packingsLowerLimit;
    private final int packingsUpperLimit;
    private int maxDiameterEstimate;
    private long maxDiameterStartState;
    private long maxDiameterEndState;
    private int islands;
    private long startTime;

    public GroupSearcher(long[] pieces, int width, int height, int packingsLowerLimit, int packingsUpperLimit) {
        this(pieces, width, height, packingsLowerLimit, packingsUpperLimit, 0L, false);
    }

    public GroupSearcher(long[] pieces, int width, int height, int packingsLowerLimit, int packingsUpperLimit, long blockedBitboard) {
        this(pieces, width, height, packingsLowerLimit, packingsUpperLimit, blockedBitboard, false);
    }

    public GroupSearcher(
        long[] pieces,
        int width,
        int height,
        int packingsLowerLimit,
        int packingsUpperLimit,
        long blockedBitboard,
        boolean deduplicateIdenticalPiecePermutations
    ) {
        this.width = width;
        this.height = height;
        this.cellCount = width * height;
        this.pieceCount = pieces.length;
        this.packingsLowerLimit = packingsLowerLimit;
        this.packingsUpperLimit = packingsUpperLimit;
        this.blockedBitboard = blockedBitboard;
        this.deduplicateIdenticalPiecePermutations = deduplicateIdenticalPiecePermutations;

        coordBits = new long[cellCount];
        for (int coord = 0; coord < cellCount; coord++) {
            coordBits[coord] = 1L << coord;
        }

        this.pieces = new long[pieceCount];
        pieceWidth = new int[pieceCount];
        pieceHeight = new int[pieceCount];
        pieceMoveBitboards = new long[pieceCount][cellCount];
        pieceValidAnchorMasks = new long[pieceCount];

        int[] minRow = new int[pieceCount];
        int[] minCol = new int[pieceCount];
        int[] maxRow = new int[pieceCount];
        int[] maxCol = new int[pieceCount];
        for (int piece = 0; piece < pieceCount; piece++) {
            minRow[piece] = height;
            minCol[piece] = width;
            long shape = pieces[piece];
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
            this.pieces[piece] = pieces[piece] >> (width * minRow[piece] + minCol[piece]);

            long validAnchorMask = 0L;
            for (int r = 0; r < height + 1 - pieceHeight[piece]; r++) {
                for (int c = 0; c < width + 1 - pieceWidth[piece]; c++) {
                    int coord = r * width + c;
                    validAnchorMask |= coordBits[coord];
                    pieceMoveBitboards[piece][coord] = this.pieces[piece] << coord;
                }
            }
            pieceValidAnchorMasks[piece] = validAnchorMask;
        }

        orthoBitboards = new long[cellCount];
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                long bitboard = 0L;
                if (r > 0) bitboard |= coordBits[(r - 1) * width + c];
                if (r < height - 1) bitboard |= coordBits[(r + 1) * width + c];
                if (c > 0) bitboard |= coordBits[r * width + (c - 1)];
                if (c < width - 1) bitboard |= coordBits[r * width + (c + 1)];
                orthoBitboards[r * width + c] = bitboard;
            }
        }

        pieceNeighborMasks = new long[pieceCount][cellCount];
        pieceValidCoords = new int[pieceCount][];
        for (int piece = 0; piece < pieceCount; piece++) {
            long anchors = pieceValidAnchorMasks[piece];
            int[] coords = new int[Long.bitCount(anchors)];
            int index = 0;
            while (anchors != 0L) {
                int coord = Long.numberOfTrailingZeros(anchors);
                anchors &= anchors - 1;
                coords[index++] = coord;
                pieceNeighborMasks[piece][coord] = orthoBitboards[coord] & pieceValidAnchorMasks[piece];
            }
            pieceValidCoords[piece] = coords;
        }

        coordBitWidth = bitsNeeded(cellCount - 1);
        if ((long) coordBitWidth * pieceCount > 64L) {
            throw new IllegalArgumentException("Packed group state requires more than 64 bits");
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

        identicalPieceGroups = buildIdenticalPieceGroups();
    }

    public String explore(String s) {
        StringBuilder result = new StringBuilder();
        startTime = System.nanoTime();
        result.append(s);

        PieceGrouper pg = new PieceGrouper();
        result.append(pg.groupToPieceCodes(pieces, width));
        result.append(",");

        SearchSummary summary = searchGroup();
        // print number of packings to temporary csv file called live_packings.csv for debugging
        String outputFileLive = "live_packings.csv";
        try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFileLive, true))) {
            File file = new File(outputFileLive);
            if (file.length() == 0) {
                writer.write("GroupID,PieceCodes,Packings,TimeTaken(ms)\n");
            }
            writer.write(s + "," + pg.groupToPieceCodes(pieces, width) + "," + summary.packings + "," + summary.timeMs + "\n");
        } catch (IOException e) {
            e.printStackTrace();
        }
        
        if (summary.packings < packingsLowerLimit || summary.packings > packingsUpperLimit) {
            result.append(">" + packingsUpperLimit + ",N/A, , , ," + summary.timeMs + "\n");
            return "";
        }
        if (summary.packings == 0) {
            result.append("0,0, , , ," + summary.timeMs + "\n");
            return "";
        }

        result.append(summary.packings).append(",");
        result.append(summary.islands).append(",");
        result.append(summary.maxDiameter).append(",");
        result.append(stateToStringInline(summary.maxDiameterStartState)).append(",");
        result.append(stateToStringInline(summary.maxDiameterEndState)).append(",");
        result.append(summary.timeMs).append("\n");

        if (summary.maxDiameter > 100) {
            String outputFile = "interesting_groups_found_so_far.csv";
            try (BufferedWriter writer = new BufferedWriter(new FileWriter(outputFile, true))) {
                File file = new File(outputFile);
                if (file.length() == 0) {
                    writer.write("GroupID,Pieces,Packings,Islands,MaxEstimatedDiameter,StartState,EndState,TimeTaken(ms)\n");
                }
                writer.write(result.toString());
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        return result.toString();
    }

    public int estimateDiameter() {
        SearchSummary summary = searchGroup();
        if (summary.packings == 0 || summary.packings > packingsUpperLimit) {
            return -1;
        }
        return summary.maxDiameter;
    }

    public int[] diameterAndPackings() {
        SearchSummary summary = searchGroup();
        if (summary.packings == 0 || summary.packings > packingsUpperLimit) {
            return new int[]{-1, -1};
        }
        return new int[]{summary.maxDiameter, summary.packings};
    }

    private SearchSummary searchGroup() {
        LongArrayList packings = new LongArrayList(4096);
        boolean withinLimit = packPieces(packings);
        int packingCount = packings.size();
        double timeMs;

        if (!withinLimit || packingCount > packingsUpperLimit) {
            timeMs = stopTimer();
            return new SearchSummary(packingCount, 0, -1, 0L, 0L, timeMs);
        }
        if (packingCount == 0) {
            timeMs = stopTimer();
            return new SearchSummary(0, 0, -1, 0L, 0L, timeMs);
        }

        LongHashSet assignedStates = new LongHashSet(Math.max(16, packingCount << 1));
        LongHashSet bfsVisited = new LongHashSet(Math.max(16, packingCount << 1));
        LongArrayList bfsVisitedList = new LongArrayList(Math.max(16, packingCount));
        LongArrayList currentFrontier = new LongArrayList(1024);
        LongArrayList nextFrontier = new LongArrayList(1024);

        islands = 0;
        maxDiameterEstimate = 0;
        maxDiameterStartState = 0L;
        maxDiameterEndState = 0L;

        for (int index = 0; index < packings.size(); index++) {
            long startState = packings.get(index);
            if (assignedStates.contains(startState)) {
                continue;
            }

            BfsResult first = doBfs(startState, bfsVisited, bfsVisitedList, currentFrontier, nextFrontier, true);
            for (int visitedIndex = 0; visitedIndex < bfsVisitedList.size(); visitedIndex++) {
                assignedStates.add(bfsVisitedList.get(visitedIndex));
            }
            BfsResult second = doBfs(first.farthestState, bfsVisited, bfsVisitedList, currentFrontier, nextFrontier, false);

            int diameterEstimate = Math.max(first.depth, second.depth);
            if (diameterEstimate > maxDiameterEstimate) {
                maxDiameterEstimate = diameterEstimate;
                maxDiameterStartState = first.farthestState;
                maxDiameterEndState = second.farthestState;
            }
            islands++;
        }

        timeMs = stopTimer();
        return new SearchSummary(packingCount, islands, maxDiameterEstimate, maxDiameterStartState, maxDiameterEndState, timeMs);
    }

    private boolean packPieces(LongArrayList packings) {
        packings.clear();
        LongHashSet canonicalPackings = deduplicateIdenticalPiecePermutations && identicalPieceGroups.length > 0
            ? new LongHashSet(4096)
            : null;
        return packPiece(packings, canonicalPackings, blockedBitboard, pieceCount - 1, 0L);
    }

    private boolean packPiece(LongArrayList packings, LongHashSet canonicalPackings, long occupied, int piece, long packedState) {
        if (piece == -1) {
            if (canonicalPackings == null) {
                packings.add(packedState);
            } else {
                long canonicalState = canonicalizeStateIfNeeded(packedState);
                if (!canonicalPackings.add(canonicalState)) {
                    return true;
                }
                packings.add(canonicalState);
            }
            return packings.size() <= packingsUpperLimit;
        }

        int[] validCoords = pieceValidCoords[piece];
        for (int i = 0; i < validCoords.length; i++) {
            int coord = validCoords[i];
            long pieceBitboard = pieceMoveBitboards[piece][coord];
            if ((occupied & pieceBitboard) == 0L) {
                long nextState = rewriteCoord(packedState, piece, coord);
                if (!packPiece(packings, canonicalPackings, occupied | pieceBitboard, piece - 1, nextState)) {
                    return false;
                }
            }
        }
        return true;
    }

    private int[][] buildIdenticalPieceGroups() {
        int[][] groups = new int[pieceCount][];
        boolean[] grouped = new boolean[pieceCount];
        int groupCount = 0;

        for (int piece = 0; piece < pieceCount; piece++) {
            if (grouped[piece]) {
                continue;
            }

            int matches = 1;
            for (int other = piece + 1; other < pieceCount; other++) {
                if (pieces[piece] == pieces[other]) {
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
                if (pieces[piece] == pieces[other]) {
                    group[index++] = other;
                    grouped[other] = true;
                }
            }
            groups[groupCount++] = group;
        }

        int[][] trimmed = new int[groupCount][];
        for (int i = 0; i < groupCount; i++) {
            trimmed[i] = groups[i];
        }
        return trimmed;
    }

    private long canonicalizeIdenticalPiecePermutations(long packedState) {
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

    private long canonicalizeStateIfNeeded(long packedState) {
        if (!deduplicateIdenticalPiecePermutations || identicalPieceGroups.length == 0) {
            return packedState;
        }
        return canonicalizeIdenticalPiecePermutations(packedState);
    }

    private BfsResult doBfs(
        long startState,
        LongHashSet visitedStates,
        LongArrayList visitedOrder,
        LongArrayList currentFrontier,
        LongArrayList nextFrontier,
        boolean collectVisitedOrder
    ) {
        visitedStates.clear();
        visitedOrder.clear();
        currentFrontier.clear();
        nextFrontier.clear();

        startState = canonicalizeStateIfNeeded(startState);
        visitedStates.add(startState);
        if (collectVisitedOrder) {
            visitedOrder.add(startState);
        }
        currentFrontier.add(startState);

        int depth = 0;
        long farthestState = startState;
        while (!currentFrontier.isEmpty()) {
            nextFrontier.clear();
            int nextDepth = depth + 1;

            for (int index = 0; index < currentFrontier.size(); index++) {
                expandState(currentFrontier.get(index), visitedStates, visitedOrder, nextFrontier, collectVisitedOrder);
            }

            if (nextFrontier.isEmpty()) {
                break;
            }

            farthestState = nextFrontier.get(0);
            LongArrayList swap = currentFrontier;
            currentFrontier = nextFrontier;
            nextFrontier = swap;
            depth = nextDepth;
        }

        return new BfsResult(depth, farthestState);
    }

    private void expandState(
        long packedState,
        LongHashSet visitedStates,
        LongArrayList visitedOrder,
        LongArrayList nextFrontier,
        boolean collectVisitedOrder
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
                        if (collectVisitedOrder) {
                            visitedOrder.add(newState);
                        }
                    }
                }
            }
        }
    }

    private int getCoord(long packedState, int piece) {
        return (int) ((packedState >>> pieceStateShifts[piece]) & coordMask);
    }

    private long rewriteCoord(long packedState, int piece, int coord) {
        return (packedState & pieceStateClearMasks[piece]) | pieceStateBits[piece][coord];
    }

    private String stateToStringInline(long packedState) {
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

    private double stopTimer() {
        return (System.nanoTime() - startTime) / 1_000_000.0;
    }

    private static int bitsNeeded(int maxValue) {
        return maxValue <= 0 ? 1 : 32 - Integer.numberOfLeadingZeros(maxValue);
    }

    private static final class SearchSummary {
        final int packings;
        final int islands;
        final int maxDiameter;
        final long maxDiameterStartState;
        final long maxDiameterEndState;
        final double timeMs;

        SearchSummary(int packings, int islands, int maxDiameter, long maxDiameterStartState, long maxDiameterEndState, double timeMs) {
            this.packings = packings;
            this.islands = islands;
            this.maxDiameter = maxDiameter;
            this.maxDiameterStartState = maxDiameterStartState;
            this.maxDiameterEndState = maxDiameterEndState;
            this.timeMs = timeMs;
        }
    }
}