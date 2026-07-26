import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;

public class Solver {

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
        private boolean[] used;
        private int size;
        private int threshold;

        LongHashSet(int expectedSize) {
            int capacity = 1;
            int minCapacity = Math.max(16, expectedSize << 1);
            while (capacity < minCapacity) capacity <<= 1;
            keys = new long[capacity];
            used = new boolean[capacity];
            threshold = capacity >> 1;
        }

        boolean add(long value) {
            if (size >= threshold) {
                resize();
            }

            int mask = keys.length - 1;
            int index = mix(value) & mask;
            while (used[index]) {
                if (keys[index] == value) {
                    return false;
                }
                index = (index + 1) & mask;
            }

            used[index] = true;
            keys[index] = value;
            size++;
            return true;
        }

        private void resize() {
            long[] oldKeys = keys;
            boolean[] oldUsed = used;

            keys = new long[oldKeys.length << 1];
            used = new boolean[oldUsed.length << 1];
            threshold = keys.length >> 1;
            size = 0;

            for (int i = 0; i < oldKeys.length; i++) {
                if (oldUsed[i]) {
                    insertRehashed(oldKeys[i]);
                }
            }
        }

        private void insertRehashed(long value) {
            int mask = keys.length - 1;
            int index = mix(value) & mask;
            while (used[index]) {
                index = (index + 1) & mask;
            }
            used[index] = true;
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

    private long[] puzzle;
    private int width, height, cellCount, pieceCount;
    private int winningPieceIndex;
    private long winningBitboard;
    private int winningCoord;
    private long blockerBitboard;

    private long[] coordBits;
    private long[] orthoBitboards;
    private long[][] pieceMoveBitboards;
    private long[] pieceValidAnchorMasks;
    private long[][] pieceNeighborMasks;
    private int[] pieceTrailingZeroOffsets;

    private int coordBitWidth;
    private long coordMask;
    private int[] pieceStateShifts;
    private long[] pieceStateClearMasks;
    private long[][] pieceStateBits;
    private long initialState;

    private LongHashSet allStates;
    private int solvedDepth;
    private boolean printSolutionPath;
    private HashMap<Long, Long> parentStates;
    private long solvedState;

    public Solver(int[][] grid, int pieceCount, int winningPiece, long winningBitboard) {
        initializeBase(grid, pieceCount, winningPiece);
        setWinningTarget(winningBitboard);
    }

    public Solver(int[][] grid, int pieceCount, int[][] winningGrid, int winningPiece) {
        initializeBase(grid, pieceCount, winningPiece);
        initializeWinningBitboard(winningGrid, winningPiece);
    }

    private void initializeBase(int[][] grid, int pieceCount, int winningPiece) {
        height = grid.length;
        width = grid[0].length;
        cellCount = width * height;
        this.pieceCount = pieceCount;
        this.winningPieceIndex = winningPiece - 1;
        puzzle = new long[pieceCount];

        initializeCoordBits();
        initializePieces(grid);
        initializePackingTables();
        initializeOrthoBitboards();
        initializePieceNeighborMasks();
    }

    private void initializeCoordBits() {
        coordBits = new long[cellCount];
        for (int coord = 0; coord < cellCount; coord++) {
            coordBits[coord] = 1L << coord;
        }
    }

    private void initializePieces(int[][] grid) {
        int[] minRow = new int[pieceCount];
        int[] minCol = new int[pieceCount];
        int[] maxRow = new int[pieceCount];
        int[] maxCol = new int[pieceCount];
        blockerBitboard = 0L;

        for (int piece = 0; piece < pieceCount; piece++) {
            minRow[piece] = height;
            minCol[piece] = width;
        }

        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                int piece = grid[r][c];
                if (piece != 0 && piece <= pieceCount) {
                    int pieceIndex = piece - 1;
                    puzzle[pieceIndex] |= coordBits[r * width + c];
                    if (r < minRow[pieceIndex]) minRow[pieceIndex] = r;
                    if (c < minCol[pieceIndex]) minCol[pieceIndex] = c;
                    if (r > maxRow[pieceIndex]) maxRow[pieceIndex] = r;
                    if (c > maxCol[pieceIndex]) maxCol[pieceIndex] = c;
                } else if (piece == -1 || piece > pieceCount) {
                    blockerBitboard |= coordBits[r * width + c];
                }
            }
        }

        pieceMoveBitboards = new long[pieceCount][cellCount];
        pieceValidAnchorMasks = new long[pieceCount];
        pieceTrailingZeroOffsets = new int[pieceCount];

        for (int piece = 0; piece < pieceCount; piece++) {
            int pieceWidth = maxCol[piece] - minCol[piece] + 1;
            int pieceHeight = maxRow[piece] - minRow[piece] + 1;
            int trailingZeroOffset = Long.numberOfTrailingZeros(puzzle[piece]) - (width * minRow[piece] + minCol[piece]);
            pieceTrailingZeroOffsets[piece] = trailingZeroOffset;

            long validAnchorMask = 0L;
            for (int r = 0; r < height + 1 - pieceHeight; r++) {
                for (int c = 0; c < width + 1 - pieceWidth; c++) {
                    int coord = r * width + c;
                    int shift = width * (r - minRow[piece]) + (c - minCol[piece]);
                    long shiftedBitboard;
                    if (shift < 0) {
                        shiftedBitboard = puzzle[piece] >> -shift;
                    } else {
                        shiftedBitboard = puzzle[piece] << shift;
                    }

                    if ((shiftedBitboard & blockerBitboard) != 0L) {
                        continue;
                    }

                    validAnchorMask |= coordBits[coord];
                    pieceMoveBitboards[piece][coord] = shiftedBitboard;
                }
            }
            pieceValidAnchorMasks[piece] = validAnchorMask;
        }
    }

    private void initializePackingTables() {
        coordBitWidth = bitsNeeded(cellCount - 1);
        if ((long) coordBitWidth * pieceCount > 64L) {
            throw new IllegalArgumentException("Packed V9 state requires more than 64 bits for this puzzle");
        }
        coordMask = (1L << coordBitWidth) - 1L;

        pieceStateShifts = new int[pieceCount];
        pieceStateClearMasks = new long[pieceCount];
        pieceStateBits = new long[pieceCount][cellCount];

        initialState = 0L;
        for (int piece = 0; piece < pieceCount; piece++) {
            int shift = piece * coordBitWidth;
            pieceStateShifts[piece] = shift;
            pieceStateClearMasks[piece] = ~(coordMask << shift);
            for (int coord = 0; coord < cellCount; coord++) {
                pieceStateBits[piece][coord] = ((long) coord) << shift;
            }

            int initialCoord = Long.numberOfTrailingZeros(puzzle[piece]) - pieceTrailingZeroOffsets[piece];
            initialState = rewriteCoord(initialState, piece, initialCoord);
        }
    }

    private void initializeOrthoBitboards() {
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
    }

    private void initializePieceNeighborMasks() {
        pieceNeighborMasks = new long[pieceCount][cellCount];
        for (int piece = 0; piece < pieceCount; piece++) {
            long validAnchorMask = pieceValidAnchorMasks[piece];
            long anchors = validAnchorMask;
            while (anchors != 0L) {
                int coord = Long.numberOfTrailingZeros(anchors);
                anchors &= anchors - 1;
                pieceNeighborMasks[piece][coord] = orthoBitboards[coord] & validAnchorMask;
            }
        }
    }

    private void initializeWinningBitboard(int[][] winningGrid, int winningPiece) {
        long targetBitboard = 0L;
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                if (winningGrid[r][c] == winningPiece) {
                    targetBitboard |= coordBits[r * width + c];
                }
            }
        }
        setWinningTarget(targetBitboard);
    }

    private void setWinningTarget(long targetBitboard) {
        winningBitboard = targetBitboard;
        winningCoord = Long.numberOfTrailingZeros(winningBitboard) - pieceTrailingZeroOffsets[winningPieceIndex];
        if (winningCoord < 0 || winningCoord >= cellCount) {
            throw new IllegalArgumentException("Winning bitboard does not map to a valid anchor coordinate");
        }
        if (pieceMoveBitboards[winningPieceIndex][winningCoord] != winningBitboard) {
            throw new IllegalArgumentException("Winning bitboard is not a valid anchor position for the winning piece");
        }
    }

    private static int bitsNeeded(int maxValue) {
        return maxValue <= 0 ? 1 : 32 - Integer.numberOfLeadingZeros(maxValue);
    }

    private int getCoord(long packedState, int piece) {
        return (int) ((packedState >>> pieceStateShifts[piece]) & coordMask);
    }

    private long rewriteCoord(long packedState, int piece, int coord) {
        return (packedState & pieceStateClearMasks[piece]) | pieceStateBits[piece][coord];
    }

    private void resetSearchStructures() {
        allStates = new LongHashSet(1 << 16);
        allStates.add(initialState);
        solvedDepth = -1;
        solvedState = -1L;
        if (printSolutionPath) {
            parentStates = new HashMap<>();
            parentStates.put(initialState, initialState);
        } else {
            parentStates = null;
        }
    }

    public int solve() {
        return solve(false);
    }

    public int solve(boolean printSolutionPath) {
        this.printSolutionPath = printSolutionPath;
        resetSearchStructures();

        if (getCoord(initialState, winningPieceIndex) == winningCoord) {
            solvedDepth = 0;
            solvedState = initialState;
            if (this.printSolutionPath) {
                printSolutionPath();
            }
            return 0;
        }

        LongArrayList currentFrontier = new LongArrayList(1024);
        LongArrayList nextFrontier = new LongArrayList(1024);
        currentFrontier.add(initialState);

        int depth = 0;
        while (!currentFrontier.isEmpty()) {
            nextFrontier.clear();
            int nextDepth = depth + 1;

            for (int i = 0; i < currentFrontier.size(); i++) {
                if (findMoves(currentFrontier.get(i), nextFrontier, nextDepth)) {
                    if (this.printSolutionPath) {
                        printSolutionPath();
                    }
                    return solvedDepth;
                }
            }

            LongArrayList swap = currentFrontier;
            currentFrontier = nextFrontier;
            nextFrontier = swap;
            depth = nextDepth;
        }

        return -1;
    }

    private boolean findMoves(long packedState, LongArrayList nextFrontier, int nextDepth) {
        long occupied = blockerBitboard;
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

                    if (piece == winningPieceIndex && nextCoord == winningCoord) {
                        System.out.println("Solved at depth " + nextDepth);
                        solvedState = newState;
                        if (printSolutionPath && !parentStates.containsKey(newState)) {
                            parentStates.put(newState, packedState);
                        }
                        solvedDepth = nextDepth;
                        return true;
                    }

                    if (allStates.add(newState)) {
                        if (printSolutionPath) {
                            parentStates.put(newState, packedState);
                        }
                        nextFrontier.add(newState);
                    }
                }
            }
        }

        return false;
    }

    private void printSolutionPath() {
        if (parentStates == null || solvedState == -1L) {
            return;
        }

        ArrayList<Long> states = new ArrayList<>();
        long currentState = solvedState;
        while (true) {
            states.add(currentState);
            long parentState = parentStates.get(currentState);
            if (parentState == currentState) {
                break;
            }
            currentState = parentState;
        }
        Collections.reverse(states);

        System.out.println("Solution path (" + (states.size() - 1) + " moves):");
        System.out.println("Step 0 (initial state):");
        printState(states.get(0));
        for (int step = 1; step < states.size(); step++) {
            System.out.println("Step " + step + ": " + describeMove(states.get(step - 1), states.get(step)));
            printState(states.get(step));
        }
    }

    private String describeMove(long previousState, long nextState) {
        for (int piece = 0; piece < pieceCount; piece++) {
            int fromCoord = getCoord(previousState, piece);
            int toCoord = getCoord(nextState, piece);
            if (fromCoord != toCoord) {
                return "piece " + (piece + 1) + " " + formatCoord(fromCoord) + " -> " + formatCoord(toCoord);
            }
        }
        return "no move detected";
    }

    private String formatCoord(int coord) {
        int row = coord / width;
        int col = coord % width;
        return "(" + row + ", " + col + ")";
    }

    private void printState(long packedState) {
        for (int r = 0; r < height; r++) {
            for (int c = 0; c < width; c++) {
                long bit = coordBits[r * width + c];
                if ((blockerBitboard & bit) != 0L) {
                    System.out.print("-1 ");
                    continue;
                }

                int pieceAtCell = 0;
                for (int piece = 0; piece < pieceCount; piece++) {
                    long pieceBitboard = pieceMoveBitboards[piece][getCoord(packedState, piece)];
                    if ((pieceBitboard & bit) != 0L) {
                        pieceAtCell = piece + 1;
                        break;
                    }
                }
                System.out.print(pieceAtCell + " ");
            }
            System.out.println();
        }
        System.out.println();
    }
}