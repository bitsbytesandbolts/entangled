// Finds all groups of N pieces from a library
// Embeds them within a bitboard of given dimensions (width x height)

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;

public class PieceGrouper {

    public static final class EntangledGroupPair {
        public final long[] groupA;
        public final long[] groupB;

        public EntangledGroupPair(long[] groupA, long[] groupB) {
            this.groupA = groupA;
            this.groupB = groupB;
        }
    }

    private long[] pieceLibrary;
    private int[] pieceWidth;
    private int[] pieceHeight;
    private String[] pieceName;

    // Precomputed transformations
    private long[] pieceLibrary90;
    private long[] pieceLibrary180;
    private long[] pieceLibrary270;
    private long[] pieceLibraryM;
    private long[] pieceLibraryM90;
    private long[] pieceLibraryM180;
    private long[] pieceLibraryM270;

    int count, countIgnoreSymmetries;
    int globalDiameter;

    public PieceGrouper(){
        buildLibrary();
        //printTest(pieceName, 0);
    }

    public ArrayList<EntangledGroupPair> generateEntangledPairs(
        String[] piecePool,
        int groupSize,
        int width,
        int height,
        int areaMin,
        int areaMax,
        int monominoLimit,
        int mustHavePieceOfSize,
        boolean removeSymmetries
    ) {
        long startTime = System.nanoTime();

        // Pair-level symmetry must be checked after the cartesian product;
        // removing single-board symmetries first would discard valid pairs.
        ArrayList<long[]> baseGroups = generateGroups(
            piecePool,
            groupSize,
            width,
            height,
            areaMin,
            areaMax,
            monominoLimit,
            false
        );

        ArrayList<EntangledGroupPair> allPairs = new ArrayList<>();
        HashSet<String> seenPairs = new HashSet<>();
        int ignoredSymmetries = 0;

        for (int first = 0; first < baseGroups.size(); first++) {
            boolean firstHasRequiredPiece = groupHasPieceOfSize(baseGroups.get(first), mustHavePieceOfSize);
            for (int second = first + 1; second < baseGroups.size(); second++) {
                long[] groupA = baseGroups.get(first);
                long[] groupB = baseGroups.get(second);

                if (mustHavePieceOfSize > 0
                    && !firstHasRequiredPiece
                    && !groupHasPieceOfSize(groupB, mustHavePieceOfSize)) {
                    continue;
                }

                int[] ignoredSymmetriesHolder = new int[]{ignoredSymmetries};
                appendEntangledPairings(groupA, groupB, width, removeSymmetries, seenPairs, allPairs, ignoredSymmetriesHolder);
                ignoredSymmetries = ignoredSymmetriesHolder[0];
            }
        }

        long endTime = System.nanoTime();
        System.out.println("Total entangled pairs: " + allPairs.size());
        if (removeSymmetries) {
            System.out.println("Entangled pair symmetries ignored: " + ignoredSymmetries);
        }
        System.out.println("Entangled pair generation time: " + (endTime - startTime) / 1000000.0 + " ms");
        return allPairs;
    }

    private void appendEntangledPairings(
        long[] groupA,
        long[] groupB,
        int width,
        boolean removeSymmetries,
        HashSet<String> seenPairs,
        ArrayList<EntangledGroupPair> allPairs,
        int[] ignoredSymmetriesHolder
    ) {
        permuteEntangledGroupB(
            groupA,
            groupB,
            new boolean[groupB.length],
            new long[groupB.length],
            0,
            width,
            removeSymmetries,
            seenPairs,
            allPairs,
            ignoredSymmetriesHolder
        );
    }

    private void permuteEntangledGroupB(
        long[] groupA,
        long[] sourceGroupB,
        boolean[] used,
        long[] permutation,
        int depth,
        int width,
        boolean removeSymmetries,
        HashSet<String> seenPairs,
        ArrayList<EntangledGroupPair> allPairs,
        int[] ignoredSymmetriesHolder
    ) {
        if (depth == sourceGroupB.length) {
            long[] candidateGroupB = permutation.clone();
            if (removeSymmetries) {
                String canonicalHash = canonicalEntangledPairHash(groupA, candidateGroupB, width);
                if (!seenPairs.add(canonicalHash)) {
                    ignoredSymmetriesHolder[0]++;
                    return;
                }
            }

            allPairs.add(new EntangledGroupPair(groupA.clone(), candidateGroupB));
            return;
        }

        HashSet<Long> usedValuesAtDepth = new HashSet<>();
        for (int index = 0; index < sourceGroupB.length; index++) {
            if (used[index]) {
                continue;
            }

            long piece = sourceGroupB[index];
            if (!usedValuesAtDepth.add(piece)) {
                continue;
            }

            used[index] = true;
            permutation[depth] = piece;
            permuteEntangledGroupB(
                groupA,
                sourceGroupB,
                used,
                permutation,
                depth + 1,
                width,
                removeSymmetries,
                seenPairs,
                allPairs,
                ignoredSymmetriesHolder
            );
            used[index] = false;
        }
    }

    public ArrayList<EntangledGroupPair> generateEntangledPairs(
        String[] piecePool,
        int groupSize,
        int width,
        int areaMin,
        int areaMax,
        int monominoLimit,
        boolean removeSymmetries
    ) {
        return generateEntangledPairs(piecePool, groupSize, width, width, areaMin, areaMax, monominoLimit, 0, removeSymmetries);
    }

    private boolean groupHasPieceOfSize(long[] group, int mustHavePieceOfSize) {
        if (mustHavePieceOfSize <= 0) {
            return true;
        }

        for (long piece : group) {
            if (Long.bitCount(piece) >= mustHavePieceOfSize) {
                return true;
            }
        }

        return false;
    }


    public ArrayList<long[]> generateGroups(String[] piecePool, int groupSize, int width, int areaMin, int areaMax, int monominoLimit, boolean removeSymmetries){
        return generateGroups(piecePool, groupSize, width, Integer.MAX_VALUE, areaMin, areaMax, monominoLimit, removeSymmetries);
    }

    public ArrayList<long[]> generateGroups(String[] piecePool, int groupSize, int width, int height, int areaMin, int areaMax, int monominoLimit, boolean removeSymmetries){
        // Takes in a String array of piece names/codes
        // ex. {"1", "2I", "2I90", "3I", "3I90", "3L", "3L90", "3L180", "3L270"}
        // and an integer group size
        // and generates all possible groups of that size.
        // It reformats their minimal bounding boxes to fit within a bitboard of given width


        //timer        
        long startTime = System.nanoTime();
        count = 0;
        countIgnoreSymmetries = 0;

        String[] filteredPiecePool = filterPiecePoolByBoardDimensions(piecePool, width, height);

        // Step 1: Create an array of indices corresponding to the pieces in the pool
        // This will let us quickly reference the library in the recursive grouping function
        int numPieces = filteredPiecePool.length;
        int[] id = new int[numPieces];
        for(int i = 0; i < numPieces; i++){
            id[i] = -1;
            for (int j = 0; j < pieceName.length; j++) {
                if (pieceName[j].equals(filteredPiecePool[i])) {
                    id[i] = j;
                    break;
                }
            }
        }

        // Step 2: Precompute reformatted pieces
        long[] pieces = new long[numPieces];
        for(int i = 0; i < numPieces; i++){
            pieces[i] = embed(id[i], width);
            System.out.println(filteredPiecePool[i] + " reformatted to " + Long.toBinaryString(pieces[i]));
        }

        // Step 3: Precompute all transformed versions of each piece
            // 0 : original
            // 1 : rotate 90
            // 2 : rotate 180
            // 3 : rotate 270
            // 4 : flip horizontal
            // 5 : flip horizontal + rotate 90
            // 6 : flip horizontal + rotate 180
            // 7 : flip horizontal + rotate 270
        long[][] transformedPieces = new long[numPieces][8];
        for(int i = 0; i < numPieces; i++){
        
            transformedPieces[i] = embedTransformations(id[i], width);
            // System.out.println("\nPiece " + pieceName[id[i]] + ": ");
            // printAsGrid(pieces[i], width, width);
            // for(int t = 0; t < 8; t++){
            //     System.out.println("\nTransformation " + t + ": ");
            //     printAsGrid(transformedPieces[i][t], width, width);
            // }
        }

        ArrayList<long[]> allGroups = new ArrayList<>();
        HashSet<String> allGroupsSet = new HashSet<>(); // for quick lookup
        recursiveGroup(pieces, transformedPieces, groupSize, 0, new long[groupSize], allGroups, allGroupsSet, areaMin, areaMax, monominoLimit, removeSymmetries);
        long endTime = System.nanoTime();
        System.out.println("Total unique groups: " + count);
        System.out.println("Symmetries ignored: " + (countIgnoreSymmetries - count));
        System.out.println("Ratio : " + ((double)countIgnoreSymmetries / count));
        // time in ms
        System.out.println("Time taken: " + (endTime - startTime) / 1000000.0 + " ms");
        try {
            Thread.sleep(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return allGroups;
    }

    private String[] filterPiecePoolByBoardDimensions(String[] piecePool, int width, int height) {
        ArrayList<String> filtered = new ArrayList<>();
        for (String pieceCode : piecePool) {
            int pieceId = -1;
            for (int index = 0; index < pieceName.length; index++) {
                if (pieceName[index].equals(pieceCode)) {
                    pieceId = index;
                    break;
                }
            }

            if (pieceId == -1) {
                continue;
            }

            boolean tooWide = pieceWidth[pieceId] > width;
            boolean tooTall = height != Integer.MAX_VALUE && pieceHeight[pieceId] > height;
            if (tooWide || tooTall) {
                String boardLabel = height == Integer.MAX_VALUE ? Integer.toString(width) : width + "x" + height;
                System.out.println("Skipping piece " + pieceCode + " because it exceeds board " + boardLabel);
                continue;
            }

            filtered.add(pieceCode);
        }
        return filtered.toArray(new String[0]);
    }

    private void recursiveGroup(long[] pieces, long[][] transformedPieces, int groupSize, int startingOffset, long[] groupInProgress, ArrayList<long[]> allGroups, HashSet<String> allGroupsSet, int areaMin, int areaMax, int monominoLimit, boolean removeSymmetries){
        // Note: Initially I passed in numPieces as well as pieces,
        // believing it would be faster. However, the code is blazingly fast anyway
        // (16-20ms for 24310 groups of 8 from 10 pieces with or without this change)
        // so I'm removing it for simplicity.

        int numPieces = pieces.length;
        if(groupSize == 0){
            boolean shouldAdd = true;

            // Check area constraint
            int biggestPiece = 0;
            int area = 0;
            for(long piece : groupInProgress) {
                area += Long.bitCount(piece);
                biggestPiece = Math.max(biggestPiece, Long.bitCount(piece));
            }
            // 14Sept2025: added the hacky biggestPiece check while I'm doing pentomino searches
            // I don't want to clog up the search with pentomino-free groups
            // At some point I'll make this a user-defined parameter
            if(area < areaMin || area > areaMax)// || biggestPiece < 5)
                shouldAdd = false;
            
            if(shouldAdd){
                countIgnoreSymmetries++;
                // Check for symmetrical groups
                if(removeSymmetries && containsAnySymmetries(pieces, transformedPieces, allGroupsSet, groupInProgress)){
                    shouldAdd = false;
                    //System.out.println("Candidate group " + countIgnoreSymmetries + " rejected due to symmetry.");
                }
            }

            if(shouldAdd){
                count++;
                allGroups.add(groupInProgress);
                allGroupsSet.add(hash(groupInProgress));
                System.out.println(Arrays.toString(groupInProgress));
            }
        }else{
            // #region OPTIMIZATION
                // Idea to speed up search
                // What would probably be smarter is to keep a running tally of area
                // and pass it between recursive calls
                // but I'll try this first since it's less invasive

                // Hey, works great!
                // Benchmarked on 8-piece groups from 1-5ominoes,
                // with areaMin = 20 and areaMax = 21
                // About 12 seconds with optimizations and >25min without (i got bored)
                // Whoa... down to 5.7 seconds just by changing currentArea > areaMax
                // to currentArea + groupSize > areaMax... nice!
                // This is obviously extreme area bounding but of course we want that
                // to heuristically explore large puzzles like 8 piece 5x5s and beyond.
                int currentArea = 0;
                int monominoCount = 0;
                for(long piece : groupInProgress) {
                    currentArea += Long.bitCount(piece);
                    if(Long.bitCount(piece) == 1) monominoCount++;
                }
                if(currentArea + groupSize > areaMax){
                    return; // prune search
                }
                if(currentArea + groupSize * 5 < areaMin){ // Magic number of 5 for now, since I'm only considering 1-5ominoes
                    return; // prune search
                }
                if(monominoCount > monominoLimit){
                    return; // prune search
                }
            // #endregion OPTIMIZATION
            for(int i = startingOffset; i < numPieces; i++){
                long[] groupInProgressCopy = test(groupInProgress, pieces[i], groupSize - 1);
                recursiveGroup(pieces, transformedPieces, groupSize - 1, i, groupInProgressCopy, allGroups, allGroupsSet, areaMin, areaMax, monominoLimit, removeSymmetries);
            }
        }
    }

    private String hash(long[] group){
        // Create a unique string representation of the group for quick lookup
        long[] sorted = group.clone();
        Arrays.sort(sorted);
        StringBuilder sb = new StringBuilder();
        for(long piece : sorted){
            sb.append(piece).append(",");
        }
        return sb.toString();
    }

    // Function to test a new group by adding a piece at a certain position
    private long[] test(long[] group, long piece, int pos){
        long[] test = group.clone();
        test[pos] = piece;
        return test;
    }

    private long[] append(long[] group, long piece){
        long[] appended = Arrays.copyOf(group, group.length + 1);
        appended[group.length] = piece;
        return appended;
    }

    private String canonicalEntangledPairHash(long[] groupA, long[] groupB, int width) {
        String bestHash = null;
        for (int transform = 0; transform < 8; transform++) {
            long[] transformedA = transformGroup(groupA, width, transform);
            long[] transformedB = transformGroup(groupB, width, transform);

            String orderedHash = orderedEntangledPairHash(transformedA, transformedB);
            if (bestHash == null || orderedHash.compareTo(bestHash) < 0) {
                bestHash = orderedHash;
            }

            String swappedHash = orderedEntangledPairHash(transformedB, transformedA);
            if (swappedHash.compareTo(bestHash) < 0) {
                bestHash = swappedHash;
            }
        }
        return bestHash;
    }

    private String orderedEntangledPairHash(long[] groupA, long[] groupB) {
        StringBuilder sb = new StringBuilder();
        for (int index = 0; index < groupA.length; index++) {
            sb.append(groupA[index]).append(':').append(groupB[index]).append(';');
        }
        return sb.toString();
    }

    private long[] transformGroup(long[] group, int width, int transform) {
        long[] transformed = new long[group.length];
        for (int index = 0; index < group.length; index++) {
            transformed[index] = transformEmbeddedPiece(group[index], width, transform);
        }
        return transformed;
    }

    private long transformEmbeddedPiece(long piece, int boardWidth, int transform) {
        if (piece == 0L) {
            return 0L;
        }

        int minRow = Integer.MAX_VALUE;
        int minCol = Integer.MAX_VALUE;
        int maxRow = 0;
        int maxCol = 0;
        long cursor = piece;
        while (cursor != 0L) {
            int coord = Long.numberOfTrailingZeros(cursor);
            int row = coord / boardWidth;
            int col = coord % boardWidth;
            if (row < minRow) minRow = row;
            if (col < minCol) minCol = col;
            if (row > maxRow) maxRow = row;
            if (col > maxCol) maxCol = col;
            cursor &= cursor - 1;
        }

        int height = maxRow - minRow + 1;
        int width = maxCol - minCol + 1;
        boolean swapDimensions = transform == 1 || transform == 3 || transform == 5 || transform == 7;
        int transformedWidth = swapDimensions ? height : width;

        long transformed = 0L;
        cursor = piece;
        while (cursor != 0L) {
            int coord = Long.numberOfTrailingZeros(cursor);
            int localRow = coord / boardWidth - minRow;
            int localCol = coord % boardWidth - minCol;

            int transformedRow;
            int transformedCol;
            switch (transform) {
                case 0:
                    transformedRow = localRow;
                    transformedCol = localCol;
                    break;
                case 1:
                    transformedRow = localCol;
                    transformedCol = height - 1 - localRow;
                    break;
                case 2:
                    transformedRow = height - 1 - localRow;
                    transformedCol = width - 1 - localCol;
                    break;
                case 3:
                    transformedRow = width - 1 - localCol;
                    transformedCol = localRow;
                    break;
                case 4:
                    transformedRow = localRow;
                    transformedCol = width - 1 - localCol;
                    break;
                case 5:
                    transformedRow = width - 1 - localCol;
                    transformedCol = height - 1 - localRow;
                    break;
                case 6:
                    transformedRow = height - 1 - localRow;
                    transformedCol = localCol;
                    break;
                case 7:
                    transformedRow = localCol;
                    transformedCol = localRow;
                    break;
                default:
                    throw new IllegalArgumentException("Unknown transform: " + transform);
            }

            transformed |= 1L << (transformedRow * boardWidth + transformedCol);
            cursor &= cursor - 1;
        }

        // Keep the transformed piece normalized to the top-left corner.
        long normalized = 0L;
        cursor = transformed;
        int normalizedMinRow = Integer.MAX_VALUE;
        int normalizedMinCol = Integer.MAX_VALUE;
        while (cursor != 0L) {
            int coord = Long.numberOfTrailingZeros(cursor);
            int row = coord / boardWidth;
            int col = coord % boardWidth;
            if (row < normalizedMinRow) normalizedMinRow = row;
            if (col < normalizedMinCol) normalizedMinCol = col;
            cursor &= cursor - 1;
        }

        cursor = transformed;
        while (cursor != 0L) {
            int coord = Long.numberOfTrailingZeros(cursor);
            int row = coord / boardWidth - normalizedMinRow;
            int col = coord % boardWidth - normalizedMinCol;
            normalized |= 1L << (row * boardWidth + col);
            cursor &= cursor - 1;
        }

        if (transformedWidth > boardWidth) {
            throw new IllegalArgumentException("Transformed piece exceeds board width");
        }
        return normalized;
    }

    private boolean containsAnySymmetries(long[] pieces, long[][] transformedPieces, HashSet<String> allGroupsSet, long[] group){

        // Get reference indices of pieces in group
        ArrayList<Integer> groupIndices = new ArrayList<>();
        for(long piece : group){
            boolean found = false;
            for(int i = 0; i < pieces.length; i++){
                if(piece == pieces[i]){
                    groupIndices.add(i);
                    found = true;
                    break;
                }
            }
            if(!found){
                System.out.println("Error: Piece not found in library.");
                return false;
            }
        }

        // Check each transformation of the group
        for(int i = 0; i < 8; i++){
            long[] transformedGroup = new long[groupIndices.size()];
            for(int j = 0; j < groupIndices.size(); j++){
                int index = groupIndices.get(j);
                transformedGroup[j] = transformedPieces[index][i];
            }
            if(allGroupsSet.contains(hash(transformedGroup))){
                // System.out.println("\nFound symmetry with transformation " + i);
                // System.out.println("Original group:");
                // for(long p : group){
                //     printAsGrid(p, 5, 5); // magic number just for debugging
                //     System.out.println();
                // }
                // System.out.println("Transformed group:");
                // for(long p : transformedGroup){
                //     printAsGrid(p, 5, 5); // magic number just for debugging
                //     System.out.println();
                // }
                return true;
            }
        }
        // System.out.println("No symmetry found.");
        return false;
    }

    private void buildLibrary(){
        ArrayList<Long> pieceData = new ArrayList<>();
        ArrayList<Integer> pieceW = new ArrayList<>();
        ArrayList<Integer> pieceH = new ArrayList<>();
        ArrayList<String> pieceN = new ArrayList<>();

        // Precomputed transformations
        ArrayList<Long> pieceData90 = new ArrayList<>();
        ArrayList<Long> pieceData180 = new ArrayList<>();
        ArrayList<Long> pieceData270 = new ArrayList<>();
        ArrayList<Long> pieceDataM = new ArrayList<>();
        ArrayList<Long> pieceDataM90 = new ArrayList<>();
        ArrayList<Long> pieceDataM180 = new ArrayList<>();
        ArrayList<Long> pieceDataM270 = new ArrayList<>();

        // #region BLANK PIECE

            pieceN.add("0");
            pieceW.add(0);
            pieceH.add(0);
            pieceData.add(0L);

            // #region TRANSFORMATIONS
                pieceData90.add(0L);
                pieceData180.add(0L);
                pieceData270.add(0L);
                pieceDataM.add(0L);
                pieceDataM90.add(0L);
                pieceDataM180.add(0L);
                pieceDataM270.add(0L);
            // #endregion TRANSFORMATIONS

        // #endregion BLANK PIECE
        // #region MONOMINOES
            pieceN.add("1");
            pieceW.add(1);
            pieceH.add(1);
            pieceData.add(gridToBitboard(new int[][]{
                {1}
            }));

            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1}
                }));
            // #endregion TRANSFORMATIONS

        // #endregion MONOMINOES    

        // #region DOMINOES
            pieceN.add("2I");
            pieceW.add(1);
            pieceH.add(2);
            pieceData.add(gridToBitboard(new int[][]{
                {1},
                {1}
            }));

            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1},
                    {1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1},
                    {1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1},
                    {1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 1}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("2I90");
            pieceW.add(2);
            pieceH.add(1);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 1}
            }));

            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1},
                    {1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1},
                    {1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1},
                    {1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1},
                    {1}
                }));
            // #endregion TRANSFORMATIONS
        
        // #endregion DOMINOES

        // #region TROMINOES

            pieceN.add("3I");
            pieceW.add(1);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {1},
                {1},
                {1}
            }));

            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 1, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1},
                    {1},
                    {1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 1, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1},
                    {1},
                    {1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 1, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1},
                    {1},
                    {1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 1, 1}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("3I90");
            pieceW.add(3);
            pieceH.add(1);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 1, 1}
            }));

            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1},
                    {1},
                    {1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 1, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1},
                    {1},
                    {1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 1, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1},
                    {1},
                    {1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 1, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1},
                    {1},
                    {1}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("3L");
            pieceW.add(2);
            pieceH.add(2);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 0},
                {1, 1}
            }));

            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {0, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 0}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {0, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 0}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("3L90");
            pieceW.add(2);
            pieceH.add(2);
            pieceData.add(gridToBitboard(new int[][]{
                {0, 1},
                {1, 1}
            }));

            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {0, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 0}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {0, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 0}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("3L180");
            pieceW.add(2);
            pieceH.add(2);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 1},
                {0, 1}
            }));

            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 0}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 0}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {0, 1}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("3L270");
            pieceW.add(2);
            pieceH.add(2);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 1},
                {1, 0}
            }));

            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {0, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {0, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 0}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1}
                }));
            // #endregion TRANSFORMATIONS

        // #endregion TROMINOES

        // #region TETROMINOES

            pieceN.add("4I");
            pieceW.add(1);
            pieceH.add(4);
            pieceData.add(gridToBitboard(new int[][]{
                {1},
                {1},
                {1},
                {1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1},
                    {1},
                    {1},
                    {1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1},
                    {1},
                    {1},
                    {1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1},
                    {1},
                    {1},
                    {1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("4I90");
            pieceW.add(4);
            pieceH.add(1);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 1, 1, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1},
                    {1},
                    {1},
                    {1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1},
                    {1},
                    {1},
                    {1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1},
                    {1},
                    {1},
                    {1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1},
                    {1},
                    {1},
                    {1}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("4O");
            pieceW.add(2);
            pieceH.add(2);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 1},
                {1, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 1}
                }));
            // #endregion TRANSFORMATIONS
            
            pieceN.add("4L");
            pieceW.add(2);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 0},
                {1, 0},
                {1, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {1, 1, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {0, 1},
                    {0, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {1, 0, 0}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {0, 1},
                    {1, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {0, 0, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 0},
                    {1, 0}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 1, 1}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("4L90");
            pieceW.add(3);
            pieceH.add(2);
            pieceData.add(gridToBitboard(new int[][]{
                {0, 0, 1},
                {1, 1, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {0, 1},
                    {0, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {1, 0, 0}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 0},
                    {1, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 1, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {0, 1},
                    {1, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {0, 0, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 0},
                    {1, 0}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("4L180");
            pieceW.add(2);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 1},
                {0, 1},
                {0, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {1, 0, 0}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 0},
                    {1, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {1, 1, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 0},
                    {1, 0}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 1, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {0, 1},
                    {1, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {0, 0, 1}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("4L270");
            pieceW.add(3);
            pieceH.add(2);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 1, 1},
                {1, 0, 0}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 0},
                    {1, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {1, 1, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {0, 1},
                    {0, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {0, 0, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 0},
                    {1, 0}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 1, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {0, 1},
                    {1, 1}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("4J");
            pieceW.add(2);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {0, 1},
                {0, 1},
                {1, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {0, 0, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 0},
                    {1, 0}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 1, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 0},
                    {1, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {1, 1, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {0, 1},
                    {0, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {1, 0, 0}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("4J90");
            pieceW.add(3);
            pieceH.add(2);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 1, 1},
                {0, 0, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 0},
                    {1, 0}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 1, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {0, 1},
                    {1, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {1, 0, 0}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 0},
                    {1, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {1, 1, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {0, 1},
                    {0, 1}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("4J180");
            pieceW.add(2);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 1},
                {1, 0},
                {1, 0}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 1, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {0, 1},
                    {1, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {0, 0, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {0, 1},
                    {0, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {1, 0, 0}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 0},
                    {1, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {1, 1, 1}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("4J270");
            pieceW.add(3);
            pieceH.add(2);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 0, 0},
                {1, 1, 1}
            }));    
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {0, 1},
                    {1, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {0, 0, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 0},
                    {1, 0}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {1, 1, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {0, 1},
                    {0, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {1, 0, 0}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 0},
                    {1, 1}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("4T");
            pieceW.add(3);
            pieceH.add(2);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 1, 1},
                {0, 1, 0}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1},
                    {1, 0}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {1, 1, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1},
                    {0, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {0, 1, 0}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1}, 
                    {1, 0}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {1, 1, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1}, 
                    {0, 1}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("4T90");
            pieceW.add(2);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 0},
                {1, 1},
                {1, 0}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {1, 1, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1}, 
                    {0, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {0, 1, 0}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1}, 
                    {0, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {0, 1, 0}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1},
                    {1, 0}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {1, 1, 1}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("4T180");
            pieceW.add(3);
            pieceH.add(2);
            pieceData.add(gridToBitboard(new int[][]{
                {0, 1, 0},
                {1, 1, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1},
                    {0, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {0, 1, 0}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1},
                    {1, 0}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {1, 1, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1},
                    {0, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {0, 1, 0}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1},
                    {1, 0}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("4T270");
            pieceW.add(2);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {0, 1},
                {1, 1},
                {0, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {0, 1, 0}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1},
                    {1, 0}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {1, 1, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1},
                    {1, 0}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {1, 1, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{   
                    {0, 1},
                    {1, 1},
                    {0, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {0, 1, 0}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("4S");
            pieceW.add(3);
            pieceH.add(2);
            pieceData.add(gridToBitboard(new int[][]{
                {0, 1, 1},
                {1, 1, 0}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1},
                    {0, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {0, 1, 1},
                    {1, 1, 0}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1},
                    {0, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 1, 0},
                    {0, 1, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1},
                    {1, 0}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 1, 0},
                    {0, 1, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1},
                    {1, 0}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("4S90");
            pieceW.add(2);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 0},
                {1, 1},
                {0, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {0, 1, 1},
                    {1, 1, 0}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1},
                    {0, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {0, 1, 1},
                    {1, 1, 0}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1},
                    {1, 0}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 1, 0},
                    {0, 1, 1}
                }));        
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1},
                    {1, 0}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 1, 0},
                    {0, 1, 1}
                }));
            // #endregion TRANSFORMATIONS
                
            pieceN.add("4Z");
            pieceW.add(3);
            pieceH.add(2);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 1, 0},
                {0, 1, 1}
            }));    
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1},
                    {1, 0}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 1, 0},
                    {0, 1, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1},
                    {1, 0}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {0, 1, 1},
                    {1, 1, 0}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1},
                    {0, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {0, 1, 1},
                    {1, 1, 0}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1},
                    {0, 1}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("4Z90");
            pieceW.add(2);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {0, 1},
                {1, 1},
                {1, 0}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 1, 0},
                    {0, 1, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1},
                    {1, 0}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 1, 0},
                    {0, 1, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1},
                    {0, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {0, 1, 1},
                    {1, 1, 0}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1},
                    {0, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {0, 1, 1},
                    {1, 1, 0}
                }));
            // #endregion TRANSFORMATIONS

        // #endregion TETROMINOES

        // #region PENTOMINOES

            pieceN.add("5F");
            pieceW.add(3);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {0, 1, 1},
                {1, 1, 0},
                {0, 1, 0}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 1, 1},
                    {0, 1, 0}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {0, 1, 1},
                    {1, 1, 0}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {1, 1, 1},
                    {0, 0, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 1, 0},
                    {0, 1, 1},
                    {0, 1, 0}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {1, 1, 1},
                    {1, 0, 0}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {1, 1, 0},
                    {0, 1, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {1, 1, 1},
                    {0, 1, 0}
                }));            
            // #endregion TRANSFORMATIONS

            pieceN.add("5F90");
            pieceW.add(3);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 0, 0},
                {1, 1, 1},
                {0, 1, 0}
            }));    
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {0, 1, 1},
                    {1, 1, 0}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {1, 1, 1},
                    {0, 0, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {0, 1, 1},
                    {1, 1, 0},
                    {0, 1, 0}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {1, 1, 1},
                    {0, 1, 0}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 1, 0},  
                    {0, 1, 1},
                    {0, 1, 0}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {1, 1, 1},
                    {1, 0, 0}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {1, 1, 0},
                    {0, 1, 1}
                }));
            // #endregion TRANSFORMATIONS
            
            pieceN.add("5F180");
            pieceW.add(3);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {0, 1, 0},
                {0, 1, 1},
                {1, 1, 0}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {1, 1, 1},
                    {0, 0, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {0, 1, 1},
                    {1, 1, 0},
                    {0, 1, 0}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 1, 1},
                    {0, 1, 0}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {1, 1, 0},
                    {0, 1, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {1, 1, 1},
                    {0, 1, 0}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 1, 0},
                    {0, 1, 1},
                    {0, 1, 0}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {1, 1, 1},
                    {1, 0, 0}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("5F270");
            pieceW.add(3);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {0, 1, 0},
                {1, 1, 1},
                {0, 0, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {0, 1, 1},
                    {1, 1, 0},
                    {0, 1, 0}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 1, 1},
                    {0, 1, 0}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {0, 1, 1},
                    {1, 1, 0}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {1, 1, 1},
                    {1, 0, 0}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {1, 1, 0},
                    {0, 1, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {1, 1, 1},
                    {0, 1, 0}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 1, 0},
                    {0, 1, 1},
                    {0, 1, 0}
                }));    

            // #endregion TRANSFORMATIONS

            pieceN.add("5f"); // No standard letter name for mirror of F...
            pieceW.add(3);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 1, 0},
                {0, 1, 1},
                {0, 1, 0}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {1, 1, 1},
                    {1, 0, 0}
                }));    
                pieceData180.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {1, 1, 0},
                    {0, 1, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {1, 1, 1},
                    {0, 1, 0}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {0, 1, 1},
                    {1, 1, 0},
                    {0, 1, 0}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 1, 1},
                    {0, 1, 0}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {0, 1, 1},
                    {1, 1, 0}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {1, 1, 1},
                    {0, 0, 1}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("5f90");
            pieceW.add(3);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {0, 1, 0},
                {1, 1, 1},
                {1, 0, 0}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {1, 1, 0},
                    {0, 1, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {1, 1, 1},
                    {0, 1, 0}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 1, 0},
                    {0, 1, 1},
                    {0, 1, 0}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {1, 1, 1},
                    {0, 0, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {0, 1, 1},
                    {1, 1, 0},
                    {0, 1, 0}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 1, 1},
                    {0, 1, 0}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {0, 1, 1},
                    {1, 1, 0}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("5f180");
            pieceW.add(3);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {0, 1, 0},
                {1, 1, 0},
                {0, 1, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {1, 1, 1},
                    {0, 1, 0}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 1, 0},
                    {0, 1, 1},
                    {0, 1, 0}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {1, 1, 1},
                    {1, 0, 0}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {0, 1, 1},
                    {1, 1, 0}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {1, 1, 1},
                    {0, 0, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {0, 1, 1},
                    {1, 1, 0},
                    {0, 1, 0}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 1, 1},
                    {0, 1, 0}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("5f270");
            pieceW.add(3);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {0, 0, 1},
                {1, 1, 1},
                {0, 1, 0}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 1, 0},
                    {0, 1, 1},
                    {0, 1, 0}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {1, 1, 1},
                    {1, 0, 0}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {1, 1, 0},
                    {0, 1, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 1, 1},
                    {0, 1, 0}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {0, 1, 1},
                    {1, 1, 0}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {1, 1, 1},
                    {0, 0, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {0, 1, 1},
                    {1, 1, 0},
                    {0, 1, 0}
                }));
            // #endregion PENTOMINOES

            pieceN.add("5I");
            pieceW.add(1);
            pieceH.add(5);
            pieceData.add(gridToBitboard(new int[][]{
                {1},
                {1},
                {1},
                {1},    
                {1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1},
                    {1},
                    {1},
                    {1},    
                    {1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1},
                    {1},
                    {1},
                    {1},    
                    {1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1},
                    {1},
                    {1},
                    {1},    
                    {1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1, 1}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("5I90");
            pieceW.add(5);
            pieceH.add(1);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 1, 1, 1, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1},
                    {1},
                    {1},
                    {1},    
                    {1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1},
                    {1},
                    {1},
                    {1},    
                    {1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1},
                    {1},
                    {1},
                    {1},    
                    {1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1},
                    {1},
                    {1},
                    {1},    
                    {1}
                }));
            // #endregion TRANSFORMATIONS


            pieceN.add("5L");
            pieceW.add(2);
            pieceH.add(4);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 0},
                {1, 0},
                {1, 0},
                {1, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {0, 0, 0, 1},
                    {1, 1, 1, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {0, 1},
                    {0, 1},
                    {0, 1}  
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1},
                    {1, 0, 0, 0}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {0, 1},
                    {0, 1},
                    {1, 1}  
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1},
                    {0, 0, 0, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 0},
                    {1, 0},
                    {1, 0}  
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 0, 0, 0},
                    {1, 1, 1, 1}
                }));
            // #endregion TRANSFORMATIONS   

            pieceN.add("5L90");
            pieceW.add(4);
            pieceH.add(2);
            pieceData.add(gridToBitboard(new int[][]{
                {0, 0, 0, 1},
                {1, 1, 1, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {0, 1},
                    {0, 1},
                    {0, 1}  
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1},
                    {1, 0, 0, 0}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 0},
                    {1, 0}, 
                    {1, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 0, 0, 0},
                    {1, 1, 1, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {0, 1},
                    {0, 1},
                    {1, 1}  
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1},
                    {0, 0, 0, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 0},
                    {1, 0},
                    {1, 0}  
                }));
            // #endregion TRANSFORMATIONS
            
            pieceN.add("5L180");
            pieceW.add(2);
            pieceH.add(4);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 1},
                {0, 1},
                {0, 1},
                {0, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1},
                    {1, 0, 0, 0}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 0},
                    {1, 0},
                    {1, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {0, 0, 0, 1},
                    {1, 1, 1, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 0},
                    {1, 0},
                    {1, 0}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 0, 0, 0},
                    {1, 1, 1, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {0, 1},
                    {0, 1},
                    {1, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1},
                    {0, 0, 0, 1}
                }));
            // #endregion TRANSFORMATIONS
            
            pieceN.add("5L270");
            pieceW.add(4);
            pieceH.add(2);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 1, 1, 1},
                {1, 0, 0, 0}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 0},
                    {1, 0}, 
                    {1, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {0, 0, 0, 1},
                    {1, 1, 1, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {0, 1},
                    {0, 1},
                    {0, 1}  
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1},
                    {0, 0, 0, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 0},
                    {1, 0},
                    {1, 0}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 0, 0, 0},
                    {1, 1, 1, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {0, 1},
                    {0, 1},
                    {1, 1}  
                }));
            // #endregion TRANSFORMATIONS
            
            pieceN.add("5J");
            pieceW.add(2);
            pieceH.add(4);
            pieceData.add(gridToBitboard(new int[][]{
                {0, 1},
                {0, 1},
                {0, 1},
                {1, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1},
                    {0, 0, 0, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 0},
                    {1, 0},
                    {1, 0}  
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 0, 0, 0},
                    {1, 1, 1, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 0},
                    {1, 0},
                    {1, 1}  
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {0, 0, 0, 1},
                    {1, 1, 1, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {0, 1},
                    {0, 1},
                    {0, 1}  
                }));    
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1},
                    {1, 0, 0, 0}
                }));
            // #endregion TRANSFORMATIONS
            
            pieceN.add("5J90");
            pieceW.add(4);
            pieceH.add(2);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 1, 1, 1},
                {0, 0, 0, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 0},
                    {1, 0},
                    {1, 0}  
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 0, 0, 0},
                    {1, 1, 1, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {0, 1},
                    {0, 1},
                    {1, 1}  
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1},
                    {1, 0, 0, 0}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 0},
                    {1, 0},
                    {1, 1}  
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {0, 0, 0, 1},
                    {1, 1, 1, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {0, 1},
                    {0, 1},
                    {0, 1}  
                }));
            // #endregion TRANSFORMATIONS
            
            pieceN.add("5J180");
            pieceW.add(2);
            pieceH.add(4);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 1},
                {1, 0},
                {1, 0},
                {1, 0}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 0, 0, 0},
                    {1, 1, 1, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {0, 1},
                    {0, 1},
                    {1, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1},
                    {0, 0, 0, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {0, 1},
                    {0, 1},
                    {0, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1},
                    {1, 0, 0, 0}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 0},
                    {1, 0},
                    {1, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {0, 0, 0, 1},   
                    {1, 1, 1, 1}
                }));
            // #endregion TRANSFORMATIONS
            
            pieceN.add("5J270");
            pieceW.add(4);
            pieceH.add(2);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 0, 0, 0},
                {1, 1, 1, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {0, 1},
                    {0, 1},
                    {1, 1}  
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1},
                    {0, 0, 0, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 0},
                    {1, 0},
                    {1, 0}  
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {0, 0, 0, 1},
                    {1, 1, 1, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {0, 1},
                    {0, 1},
                    {0, 1}  
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1},
                    {1, 0, 0, 0}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 0},
                    {1, 0},
                    {1, 1}  
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("5N");
            pieceW.add(2);
            pieceH.add(4);
            pieceData.add(gridToBitboard(new int[][]{
                {0, 1},
                {1, 1},
                {1, 0},
                {1, 0}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 1, 0, 0},
                    {0, 1, 1, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {0, 1},
                    {1, 1},
                    {1, 0}  
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 0},
                    {0, 0, 1, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1},
                    {0, 1},
                    {0, 1}  
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {0, 1, 1, 1},
                    {1, 1, 0, 0}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 0},
                    {1, 1},
                    {0, 1}  
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {0, 0, 1, 1},
                    {1, 1, 1, 0}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("5N90");
            pieceW.add(4);
            pieceH.add(2);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 1, 0, 0},
                {0, 1, 1, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {0, 1},
                    {1, 1},
                    {1, 0}  
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 0},
                    {0, 0, 1, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1},
                    {1, 0},
                    {1, 0}  
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {0, 0, 1, 1},
                    {1, 1, 1, 0}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1},
                    {0, 1},
                    {0, 1}  
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {0, 1, 1, 1},
                    {1, 1, 0, 0}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 0},
                    {1, 1},
                    {0, 1}  
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("5N180");
            pieceW.add(2);
            pieceH.add(4);
            pieceData.add(gridToBitboard(new int[][]{
                {0, 1},
                {0, 1},
                {1, 1},
                {1, 0}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 0},
                    {0, 0, 1, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1},
                    {1, 0},
                    {1, 0}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 1, 0, 0},
                    {0, 1, 1, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 0},
                    {1, 1},
                    {0, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {0, 0, 1, 1},
                    {1, 1, 1, 0}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1},
                    {0, 1},
                    {0, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {0, 1, 1, 1},
                    {1, 1, 0, 0}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("5N270");
            pieceW.add(4);
            pieceH.add(2);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 1, 1, 0},
                {0, 0, 1, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1},
                    {1, 0},
                    {1, 0}  
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 1, 0, 0},
                    {0, 1, 1, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {0, 1},
                    {1, 1},
                    {1, 0}  
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {0, 1, 1, 1},
                    {1, 1, 0, 0}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 0},
                    {1, 1},
                    {0, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {0, 0, 1, 1},
                    {1, 1, 1, 0}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1},
                    {0, 1},
                    {0, 1}  
                }));    
            // #endregion TRANSFORMATIONS

            pieceN.add("5n"); // No standard letter name for mirrored N
            pieceW.add(2);
            pieceH.add(4);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 0},
                {1, 1},
                {0, 1},
                {0, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {0, 1, 1, 1},
                    {1, 1, 0, 0}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 0},
                    {1, 1},
                    {0, 1}  
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {0, 0, 1, 1},
                    {1, 1, 1, 0}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1},
                    {1, 0},
                    {1, 0}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 1, 0, 0},
                    {0, 1, 1, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {0, 1},
                    {1, 1},
                    {1, 0}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 0},
                    {0, 0, 1, 1}
                }));
            // #endregion TRANSFORMATIONS
        
            pieceN.add("5n90");
            pieceW.add(4);
            pieceH.add(2);
            pieceData.add(gridToBitboard(new int[][]{
                {0, 1, 1, 1},
                {1, 1, 0, 0}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 0},
                    {1, 1},
                    {0, 1}  
                }));        
                pieceData180.add(gridToBitboard(new int[][]{
                    {0, 0, 1, 1},
                    {1, 1, 1, 0}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1},
                    {0, 1},
                    {0, 1}  
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 0},
                    {0, 0, 1, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1},
                    {1, 0},
                    {1, 0}  
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 1, 0, 0},
                    {0, 1, 1, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {0, 1},
                    {1, 1},
                    {1, 0}  
                }));
            // #endregion TRANSFORMATIONS
        
            pieceN.add("5n180");
            pieceW.add(2);
            pieceH.add(4);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 0},
                {1, 0},
                {1, 1},
                {0, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {0, 0, 1, 1},
                    {1, 1, 1, 0}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1},
                    {0, 1},
                    {0, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {0, 1, 1, 1},
                    {1, 1, 0, 0}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {0, 1},
                    {1, 1},
                    {1, 0}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 0},
                    {0, 0, 1, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1},
                    {1, 0},
                    {1, 0}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 1, 0, 0},
                    {0, 1, 1, 1}
                }));
            // #endregion TRANSFORMATIONS
            pieceN.add("5n270");
            pieceW.add(4);
            pieceH.add(2);
            pieceData.add(gridToBitboard(new int[][]{
                {0, 0, 1, 1},
                {1, 1, 1, 0}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1},
                    {0, 1},
                    {0, 1}  
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {0, 1, 1, 1},
                    {1, 1, 0, 0}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 0},
                    {1, 1},
                    {0, 1}  
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 1, 0, 0},
                    {0, 1, 1, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {0, 1},
                    {1, 1},
                    {1, 0}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 0},
                    {0, 0, 1, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1},
                    {1, 0},
                    {1, 0}  
                }));
            // #endregion TRANSFORMATIONS
            
            
            pieceN.add("5P");
            pieceW.add(2);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 1},
                {1, 1},
                {1, 0}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 1, 0},
                    {1, 1, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1},
                    {1, 1}  
                }));    
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {0, 1, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 1},
                    {0, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {1, 1, 0}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1}, 
                    {1, 1}  
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {0, 1, 1},
                    {1, 1, 1}
                }));
            // #endregion TRANSFORMATIONS
        
            pieceN.add("5P90");
            pieceW.add(3);
            pieceH.add(2);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 1, 0},
                {1, 1, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1},
                    {1, 1}  
                }));    
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {0, 1, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 1},
                    {1, 0}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {0, 1, 1},
                    {1, 1, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 1},
                    {0, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {1, 1, 0}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1}, 
                    {1, 1}  
                }));
            // #endregion TRANSFORMATIONS
        
            pieceN.add("5P180");
            pieceW.add(2);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {0, 1},
                {1, 1},
                {1, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {0, 1, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 1},
                    {1, 0}  
                }));    
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 1, 0},
                    {1, 1, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1},
                    {1, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {0, 1, 1},
                    {1, 1, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 1},
                    {0, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {1, 1, 0}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("5P270");
            pieceW.add(3);
            pieceH.add(2);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 1, 1},
                {0, 1, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 1},
                    {1, 0}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 1, 0},
                    {1, 1, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1},
                    {1, 1}  
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {1, 1, 0}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1},
                    {1, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {0, 1, 1},
                    {1, 1, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 1},
                    {0, 1}
                }));
            // #endregion TRANSFORMATIONS
        
            pieceN.add("5Q");
            pieceW.add(2);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 1},
                {1, 1},
                {0, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {1, 1, 0}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1},
                    {1, 1}  
                }));    
                pieceData270.add(gridToBitboard(new int[][]{
                    {0, 1, 1},
                    {1, 1, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 1}, 
                    {1, 0}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 1, 0},
                    {1, 1, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1}, 
                    {1, 1}  
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {0, 1, 1}
                }));
            // #endregion TRANSFORMATIONS
        
            pieceN.add("5Q90");
            pieceW.add(3);
            pieceH.add(2);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 1, 1},
                {1, 1, 0}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1},
                    {1, 1}  
                }));    
                pieceData180.add(gridToBitboard(new int[][]{
                    {0, 1, 1},
                    {1, 1, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 1},
                    {0, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {0, 1, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 1},
                    {1, 0}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 1, 0},
                    {1, 1, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1},
                    {1, 1}  
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("5Q180");
            pieceW.add(2);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 0},
                {1, 1},
                {1, 1}
            }));    
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {0, 1, 1},
                    {1, 1, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 1},
                    {0, 1}  
                }));    
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {1, 1, 0}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1}, 
                    {1, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {0, 1, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 1},
                    {1, 0}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 1, 0},
                    {1, 1, 1}
                }));
            // #endregion PENTOMINOES

            pieceN.add("5Q270");
            pieceW.add(3);
            pieceH.add(2);
            pieceData.add(gridToBitboard(new int[][]{
                {0, 1, 1},
                {1, 1, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 1},
                    {0, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {1, 1, 0}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1},
                    {1, 1}  
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 1, 0},
                    {1, 1, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1},
                    {1, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {0, 1, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 1},
                    {1, 0}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("5T");
            pieceW.add(3);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 1, 1},
                {0, 1, 0},
                {0, 1, 0}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 1, 1},
                    {1, 0, 0}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {0, 1, 0},
                    {1, 1, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {1, 1, 1},
                    {0, 0, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {0, 1, 0},
                    {0, 1, 0}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 1, 1},
                    {1, 0, 0}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {0, 1, 0},
                    {1, 1, 1}   
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {1, 1, 1},
                    {0, 0, 1}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("5T90");
            pieceW.add(3);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 0, 0},
                {1, 1, 1},
                {1, 0, 0}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {0, 1, 0},
                    {1, 1, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {1, 1, 1},
                    {0, 0, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {0, 1, 0},
                    {0, 1, 0}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {1, 1, 1},
                    {0, 0, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {0, 1, 0},
                    {0, 1, 0}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 1, 1},
                    {1, 0, 0}   
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {0, 1, 0},
                    {1, 1, 1}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("5T180");
            pieceW.add(3);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {0, 1, 0},
                {0, 1, 0},
                {1, 1, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{ 
                    {0, 0, 1},
                    {1, 1, 1},
                    {0, 0, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {0, 1, 0},
                    {0, 1, 0}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 1, 1},
                    {1, 0, 0}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {0, 1, 0},
                    {1, 1, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{ 
                    {0, 0, 1},
                    {1, 1, 1},
                    {0, 0, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {0, 1, 0},
                    {0, 1, 0}   
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 1, 1},
                    {1, 0, 0}
                }));
            // #endregion TRANSFORMATIONS
        
            pieceN.add("5T270");
            pieceW.add(3);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {0, 0, 1},
                {1, 1, 1},
                {0, 0, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {0, 1, 0},
                    {0, 1, 0}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 1, 1},
                    {1, 0, 0}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {0, 1, 0},  
                    {1, 1, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 1, 1},
                    {1, 0, 0}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {0, 1, 0},  
                    {1, 1, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {1, 1, 1},
                    {0, 0, 1}   
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {0, 1, 0},
                    {0, 1, 0}
                }));
            // #endregion TRANSFORMATIONS
            
            pieceN.add("5U");
            pieceW.add(3);
            pieceH.add(2);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 0, 1},
                {1, 1, 1}   
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {0, 1},
                    {1, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {1, 0, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 0},
                    {1, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 0, 1},
                    {1, 1, 1}   
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {0, 1},
                    {1, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {1, 0, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 0},
                    {1, 1}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("5U90");
            pieceW.add(2);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 1},
                {0, 1},
                {1, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {1, 0, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 0},
                    {1, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 0, 1},
                    {1, 1, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 0},
                    {1, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 0, 1},
                    {1, 1, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {0, 1},
                    {1, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {1, 0, 1}
                }));
            // #endregion TRANSFORMATIONS
        
            pieceN.add("5U180");
            pieceW.add(3);
            pieceH.add(2);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 1, 1},
                {1, 0, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 0},
                    {1, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 0, 1},
                    {1, 1, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {0, 1},
                    {1, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {1, 0, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 0},
                    {1, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 0, 1},
                    {1, 1, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {0, 1},
                    {1, 1}
                }));
            // #endregion TRANSFORMATIONS
        
            pieceN.add("5U270");
            pieceW.add(2);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 1},
                {1, 0},
                {1, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 0, 1},
                    {1, 1, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {0, 1},
                    {1, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {1, 0, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {0, 1},
                    {1, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {1, 0, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 1},
                    {1, 0},
                    {1, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 0, 1},
                    {1, 1, 1}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("5V");
            pieceW.add(3);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 0, 0},
                {1, 0, 0},
                {1, 1, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {0, 0, 1},
                    {1, 1, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {0, 0, 1},
                    {0, 0, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {1, 0, 0},
                    {1, 0, 0}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {0, 0, 1},
                    {1, 1, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {0, 0, 1},
                    {0, 0, 1}
                }));    
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {1, 0, 0},
                    {1, 0, 0}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 0, 0},
                    {1, 1, 1}
                }));
            // #endregion TRANSFORMATIONS
        
            pieceN.add("5V90");
            pieceW.add(3);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {0, 0, 1},
                {0, 0, 1},
                {1, 1, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {0, 0, 1},
                    {0, 0, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {1, 0, 0},
                    {1, 0, 0}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 0, 0},
                    {1, 1, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 0, 0},
                    {1, 1, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {0, 0, 1},
                    {1, 1, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {0, 0, 1},
                    {0, 0, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {1, 0, 0},
                    {1, 0, 0}
                }));
            // #endregion TRANSFORMATIONS
        
            pieceN.add("5V180");
            pieceW.add(3);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 1, 1},
                {0, 0, 1},
                {0, 0, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {1, 0, 0},
                    {1, 0, 0}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 0, 0},
                    {1, 1, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {0, 0, 1},
                    {1, 1, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {0, 0, 1},
                    {0, 0, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {1, 0, 0},
                    {1, 0, 0}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 0, 0},
                    {1, 1, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {0, 0, 1},
                    {1, 1, 1}
                }));
            // #endregion TRANSFORMATIONS
        
            pieceN.add("5V270");
            pieceW.add(3);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 1, 1},
                {1, 0, 0},
                {1, 0, 0}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 0, 0},
                    {1, 1, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {0, 0, 1},
                    {1, 1, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {0, 0, 1},
                    {0, 0, 1}   
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {0, 0, 1},
                    {0, 0, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 1, 1},
                    {1, 0, 0},
                    {1, 0, 0}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 0, 0},
                    {1, 1, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {0, 0, 1},
                    {1, 1, 1}   
                }));
            // #endregion TRANSFORMATIONS
        
            pieceN.add("5W");
            pieceW.add(3);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 0, 0},
                {1, 1, 0},
                {0, 1, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {0, 1, 1},
                    {1, 1, 0}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 1, 0},
                    {0, 1, 1},
                    {0, 0, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {0, 1, 1},
                    {1, 1, 0},
                    {1, 0, 0}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {0, 1, 1},
                    {1, 1, 0}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 1, 0},
                    {0, 1, 1},
                    {0, 0, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {0, 1, 1},
                    {1, 1, 0},
                    {1, 0, 0}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 1, 0},
                    {0, 1, 1}
                }));
            // #endregion TRANSFORMATIONS
        
            pieceN.add("5W90");
            pieceW.add(3);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {0, 0, 1},
                {0, 1, 1},
                {1, 1, 0}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 1, 0},
                    {0, 1, 1},
                    {0, 0, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {0, 1, 1},
                    {1, 1, 0},
                    {1, 0, 0}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 1, 0},
                    {0, 1, 1}   
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 1, 0},
                    {0, 1, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {0, 1, 1},
                    {1, 1, 0}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 1, 0},
                    {0, 1, 1},
                    {0, 0, 1}       
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {0, 1, 1},
                    {1, 1, 0},
                    {1, 0, 0}   
                }));
            // #endregion TRANSFORMATIONS   
            
            pieceN.add("5W180");
            pieceW.add(3);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 1, 0},
                {0, 1, 1},
                {0, 0, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {0, 1, 1},
                    {1, 1, 0},
                    {1, 0, 0}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 1, 0},
                    {0, 1, 1}   
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {0, 1, 1},
                    {1, 1, 0}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {0, 1, 1},
                    {1, 1, 0},
                    {1, 0, 0}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 1, 0},
                    {0, 1, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {0, 1, 1},
                    {1, 1, 0}       
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 1, 0},
                    {0, 1, 1},  
                    {0, 0, 1}
                }));
            // #endregion TRANSFORMATIONS
        
            pieceN.add("5W270");
            pieceW.add(3);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {0, 1, 1},
                {1, 1, 0},
                {1, 0, 0}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 1, 0},
                    {0, 1, 1}   
                }));
                pieceData180.add(gridToBitboard(new int[][]{    
                    {0, 0, 1},
                    {0, 1, 1},
                    {1, 1, 0}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 1, 0},
                    {0, 1, 1},
                    {0, 0, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 1, 0},
                    {0, 1, 1},
                    {0, 0, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {0, 1, 1},
                    {1, 1, 0},
                    {1, 0, 0}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{    
                    {1, 0, 0},
                    {1, 1, 0},
                    {0, 1, 1}       
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {0, 1, 1},
                    {1, 1, 0}
                }));
            // #endregion TRANSFORMATIONS
        
            pieceN.add("5X");
            pieceW.add(3);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {0, 1, 0},
                {1, 1, 1},
                {0, 1, 0}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {1, 1, 1},
                    {0, 1, 0}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {0, 1, 0},  
                    {1, 1, 1},
                    {0, 1, 0}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {1, 1, 1},
                    {0, 1, 0}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {1, 1, 1},
                    {0, 1, 0}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {1, 1, 1},
                    {0, 1, 0}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {0, 1, 0},  
                    {1, 1, 1},
                    {0, 1, 0}       
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {0, 1, 0},
                    {1, 1, 1},
                    {0, 1, 0}
                }));
            // #endregion TRANSFORMATIONS
        
            pieceN.add("5Y");
            pieceW.add(2);
            pieceH.add(4);
            pieceData.add(gridToBitboard(new int[][]{
                {0, 1},
                {1, 1},
                {0, 1},
                {0, 1}  
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1},
                    {0, 1, 0, 0}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 0},
                    {1, 1},
                    {1, 0}  
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {0, 0, 1, 0},
                    {1, 1, 1, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1},
                    {1, 0},
                    {1, 0}  
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {0, 1, 0, 0},
                    {1, 1, 1, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {0, 1},
                    {1, 1},
                    {0, 1}       
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1},
                    {0, 0, 1, 0}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("5Y90");
            pieceW.add(4);
            pieceH.add(2);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 1, 1, 1},
                {0, 1, 0, 0}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 0},
                    {1, 1},
                    {1, 0}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {0, 0, 1, 0},
                    {1, 1, 1, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1},
                    {0, 1},
                    {0, 1}  
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1},
                    {0, 0, 1, 0}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1},
                    {1, 0},
                    {1, 0}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {0, 1, 0, 0},
                    {1, 1, 1, 1}       
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {0, 1},
                    {1, 1},
                    {0, 1}  
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("5Y180");
            pieceW.add(2);
            pieceH.add(4);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 0},
                {1, 0},
                {1, 1},
                {1, 0}  
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {0, 0, 1, 0},
                    {1, 1, 1, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1},
                    {0, 1},
                    {0, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1},
                    {0, 1, 0, 0}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {0, 1},
                    {1, 1},
                    {0, 1}  
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1},
                    {0, 0, 1, 0}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1},
                    {1, 0},
                    {1, 0}       
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {0, 1, 0, 0},
                    {1, 1, 1, 1}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("5Y270");
            pieceW.add(4);
            pieceH.add(2);
            pieceData.add(gridToBitboard(new int[][]{
                {0, 0, 1, 0},
                {1, 1, 1, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1},
                    {0, 1},
                    {0, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1},
                    {0, 1, 0, 0}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 0},
                    {1, 1},
                    {1, 0}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {0, 1, 0, 0},
                    {1, 1, 1, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {0, 1},
                    {1, 1},
                    {0, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1},
                    {0, 0, 1, 0}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1},
                    {1, 0},
                    {1, 0}       
                }));
            // #endregion TRANSFORMATIONS
        
            pieceN.add("5y"); // No standard letter for mirrored Y
            pieceW.add(2);
            pieceH.add(4);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 0},
                {1, 1},
                {1, 0},
                {1, 0}  
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {0, 1, 0, 0},
                    {1, 1, 1, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {0, 1},
                    {1, 1},
                    {0, 1}       
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1},
                    {0, 0, 1, 0}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1},
                    {0, 1},
                    {0, 1}  
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1},
                    {0, 1, 0, 0}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 0},
                    {1, 1},
                    {1, 0}       
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {0, 0, 1, 0},
                    {1, 1, 1, 1}
                }));
            // #endregion TRANSFORMATIONS
        
            pieceN.add("5y90");
            pieceW.add(4);
            pieceH.add(2);
            pieceData.add(gridToBitboard(new int[][]{
                {0, 1, 0, 0},
                {1, 1, 1, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {0, 1},
                    {1, 1},
                    {0, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1},
                    {0, 0, 1, 0}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1},
                    {1, 0},
                    {1, 0}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {0, 0, 1, 0},
                    {1, 1, 1, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1},
                    {0, 1},
                    {0, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1},
                    {0, 1, 0, 0}       
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 0},
                    {1, 1},
                    {1, 0}
                }));
            // #endregion TRANSFORMATIONS
        
            pieceN.add("5y180");
            pieceW.add(2);
            pieceH.add(4);
            pieceData.add(gridToBitboard(new int[][]{
                {0, 1},
                {0, 1},
                {1, 1},
                {0, 1}       
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1},
                    {0, 0, 1, 0}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1},
                    {1, 0},
                    {1, 0}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {0, 1, 0, 0},
                    {1, 1, 1, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 0},
                    {1, 1},
                    {1, 0}       
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {0, 0, 1, 0},
                    {1, 1, 1, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1},
                    {0, 1},
                    {0, 1}       
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1},
                    {0, 1, 0, 0}
                }));
            // #endregion TRANSFORMATIONS
        
            pieceN.add("5y270");
            pieceW.add(4);
            pieceH.add(2);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 1, 1, 1},
                {0, 0, 1, 0}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 1},
                    {1, 0},
                    {1, 0}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {0, 1, 0, 0},
                    {1, 1, 1, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {0, 1},
                    {1, 1},
                    {0, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 1, 1, 1},
                    {0, 1, 0, 0}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 0},
                    {1, 0},
                    {1, 1},
                    {1, 0}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {0, 0, 1, 0},
                    {1, 1, 1, 1}       
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {0, 1},
                    {1, 1},
                    {0, 1},
                    {0, 1}
                }));
            // #endregion TRANSFORMATIONS
        
            pieceN.add("5Z");
            pieceW.add(3);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 1, 0},
                {0, 1, 0},
                {0, 1, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {1, 1, 1},
                    {1, 0, 0}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 1, 0},
                    {0, 1, 0},
                    {0, 1, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {1, 1, 1},
                    {1, 0, 0}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {0, 1, 1},
                    {0, 1, 0},
                    {1, 1, 0}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 1, 1},
                    {0, 0, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {0, 1, 1},
                    {0, 1, 0},
                    {1, 1, 0}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 1, 1},
                    {0, 0, 1}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("5Z90");
            pieceW.add(3);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {0, 0, 1},
                {1, 1, 1},
                {1, 0, 0}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 1, 0},
                    {0, 1, 0},
                    {0, 1, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {1, 1, 1},
                    {1, 0, 0}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 1, 0},
                    {0, 1, 0},
                    {0, 1, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 1, 1},
                    {0, 0, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {0, 1, 1},
                    {0, 1, 0},
                    {1, 1, 0}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 1, 1},
                    {0, 0, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{   
                    {0, 1, 1},
                    {0, 1, 0},
                    {1, 1, 0}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("5S");
            pieceW.add(3);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {0, 1, 1},
                {0, 1, 0},
                {1, 1, 0}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 1, 1},
                    {0, 0, 1}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {0, 1, 1},
                    {0, 1, 0},
                    {1, 1, 0}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 1, 1},
                    {0, 0, 1}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {1, 1, 0},
                    {0, 1, 0},
                    {0, 1, 1}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {1, 1, 1},
                    {1, 0, 0}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {1, 1, 0},
                    {0, 1, 0},
                    {0, 1, 1}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {1, 1, 1},
                    {1, 0, 0}
                }));
            // #endregion TRANSFORMATIONS

            pieceN.add("5S90");
            pieceW.add(3);
            pieceH.add(3);
            pieceData.add(gridToBitboard(new int[][]{
                {1, 0, 0},
                {1, 1, 1},
                {0, 0, 1}
            }));
            // #region TRANSFORMATIONS
                pieceData90.add(gridToBitboard(new int[][]{
                    {0, 1, 1},
                    {0, 1, 0},
                    {1, 1, 0}
                }));
                pieceData180.add(gridToBitboard(new int[][]{
                    {1, 0, 0},
                    {1, 1, 1},
                    {0, 0, 1}
                }));
                pieceData270.add(gridToBitboard(new int[][]{
                    {0, 1, 1},
                    {0, 1, 0},
                    {1, 1, 0}
                }));
                pieceDataM.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {1, 1, 1},
                    {1, 0, 0}
                }));
                pieceDataM90.add(gridToBitboard(new int[][]{
                    {1, 1, 0},
                    {0, 1, 0},
                    {0, 1, 1}
                }));
                pieceDataM180.add(gridToBitboard(new int[][]{
                    {0, 0, 1},
                    {1, 1, 1},
                    {1, 0, 0}
                }));
                pieceDataM270.add(gridToBitboard(new int[][]{
                    {1, 1, 0},
                    {0, 1, 0},
                    {0, 1, 1}
                }));
            // #endregion TRANSFORMATIONS




        // #endregion PENTOMINOES
        
        // Populate libraries
        pieceLibrary = new long[pieceData.size()];
        pieceWidth = new int[pieceData.size()];
        pieceHeight = new int[pieceData.size()];
        pieceName = new String[pieceData.size()];

        pieceLibrary90 = new long[pieceData.size()];
        pieceLibrary180 = new long[pieceData.size()];
        pieceLibrary270 = new long[pieceData.size()];
        pieceLibraryM = new long[pieceData.size()];
        pieceLibraryM90 = new long[pieceData.size()];
        pieceLibraryM180 = new long[pieceData.size()];
        pieceLibraryM270 = new long[pieceData.size()];

        for(int i = 0; i < pieceData.size(); i++){
            pieceLibrary[i] = pieceData.get(i);
            pieceWidth[i] = pieceW.get(i);
            pieceHeight[i] = pieceH.get(i);
            pieceName[i] = pieceN.get(i);

            pieceLibrary90[i] = pieceData90.get(i);
            pieceLibrary180[i] = pieceData180.get(i);
            pieceLibrary270[i] = pieceData270.get(i);
            pieceLibraryM[i] = pieceDataM.get(i);
            pieceLibraryM90[i] = pieceDataM90.get(i);
            pieceLibraryM180[i] = pieceDataM180.get(i);
            pieceLibraryM270[i] = pieceDataM270.get(i);
        }
    }


    private long gridToBitboard(int[][] grid){
        int height = grid.length;
        int width = grid[0].length;
        long bitboard = 0L;

        for(int r = 0; r < height; r++)
            for(int c = 0; c < width; c++)
                if(grid[r][c] != 0)
                    bitboard |= (1L << r * width + c);

        return bitboard;
    }

    private void printAsGrid(long bitboard, int width, int height){
        for(int r = 0; r < height; r++){
            for(int c = 0; c < width; c++){
                long bit = 1L << (r * width + c);
                if((bitboard & bit) != 0)
                    System.out.print("1 ");
                else
                    System.out.print("0 ");
            }
            System.out.println();
        }
    }

    private long embed(int pieceID, int width){
        // Takes in a piece ID from the library
        // and embeds it in a bitboard of given width
        // at the top-left corner (height is irrelevant)
        long piece = pieceLibrary[pieceID];
        int pw = pieceWidth[pieceID];
        int ph = pieceHeight[pieceID];

        long embedded = 0L;

        for(int r = 0; r < ph; r++)
            for(int c = 0; c < pw; c++){
                long bit = 1L << (r * pw + c);
                if((piece & bit) != 0)
                    embedded |= (1L << (r * width + c));
            }

        return embedded;
    }

    private long[] embedTransformations(int pieceID, int width){
        // Takes in a piece ID from the library
        // and embeds all its transformations 
        // in a bitboard of given width at the 
        // top-left corner (height is irrelevant)
        long piece = pieceLibrary[pieceID];
        int pw = pieceWidth[pieceID];
        int ph = pieceHeight[pieceID];
        int pwRot = ph; // for 90 and 270 degree rotations
        int phRot = pw; // for 90 and 270 degree rotations

        long embedded;
        long[] embeddedArray = new long[8];

        // Original
        embedded = 0L;
        for(int r = 0; r < ph; r++)
            for(int c = 0; c < pw; c++){
                long bit = 1L << (r * pw + c);
                if((piece & bit) != 0)
                    embedded |= (1L << (r * width + c));
            }
        embeddedArray[0] = embedded;

        // 90
        embedded = 0L;
        piece = pieceLibrary90[pieceID];
        for(int r = 0; r < phRot; r++)
            for(int c = 0; c < pwRot; c++){
                long bit = 1L << (r * pwRot + c);
                if((piece & bit) != 0)
                    embedded |= (1L << (r * width + c));
            }
        embeddedArray[1] = embedded;

        // 180
        embedded = 0L;
        piece = pieceLibrary180[pieceID];
        for(int r = 0; r < ph; r++)
            for(int c = 0; c < pw; c++){
                long bit = 1L << (r * pw + c);
                if((piece & bit) != 0)
                    embedded |= (1L << (r * width + c));
            }
        embeddedArray[2] = embedded;

        // 270
        embedded = 0L;
        piece = pieceLibrary270[pieceID];
        for(int r = 0; r < phRot; r++)
            for(int c = 0; c < pwRot; c++){
                long bit = 1L << (r * pwRot + c);
                if((piece & bit) != 0)
                    embedded |= (1L << (r * width + c));
            }
        embeddedArray[3] = embedded;

        // M (mirror horizontally)
        embedded = 0L;
        piece = pieceLibraryM[pieceID];
        for(int r = 0; r < ph; r++)
            for(int c = 0; c < pw; c++){
                long bit = 1L << (r * pw + c);
                if((piece & bit) != 0)
                    embedded |= (1L << (r * width + c));
            }
        embeddedArray[4] = embedded;

        // M90
        embedded = 0L;
        piece = pieceLibraryM90[pieceID];
        for(int r = 0; r < phRot; r++)
            for(int c = 0; c < pwRot; c++){
                long bit = 1L << (r * pwRot + c);
                if((piece & bit) != 0)
                    embedded |= (1L << (r * width + c));
            }
        embeddedArray[5] = embedded;

        // M180
        embedded = 0L;
        piece = pieceLibraryM180[pieceID];
        for(int r = 0; r < ph; r++)
            for(int c = 0; c < pw; c++){
                long bit = 1L << (r * pw + c);
                if((piece & bit) != 0)
                    embedded |= (1L << (r * width + c));
            }
        embeddedArray[6] = embedded;

        // M270
        embedded = 0L;
        piece = pieceLibraryM270[pieceID];
        for(int r = 0; r < phRot; r++)
            for(int c = 0; c < pwRot; c++){
                long bit = 1L << (r * pwRot + c);
                if((piece & bit) != 0)
                    embedded |= (1L << (r * width + c));
            }
        embeddedArray[7] = embedded;

        return embeddedArray;
    }

    public String groupToPieceCodes(long[] group, int width){
        // Takes in a group of already embedded pieces and gets their piece codes
        
        // First put all pieces in library in the same embedded dimensions as inputs
        long[] embedded = new long[pieceLibrary.length];
        for(int i = 0; i < pieceLibrary.length; i++){
            embedded[i] = embed(i, width);
        }

        // Then for each piece in the group, find its index in the embedded library
        StringBuilder sb = new StringBuilder();
        for(long piece : group){
            boolean found = false;
            for(int i = 0; i < embedded.length; i++){
                if(piece == embedded[i]){
                    sb.append(pieceName[i]);
                    sb.append("/");
                    found = true;
                    break;
                }
            }
            if(!found){
                sb.append("?");
                sb.append("/");
            }
        }
        if(sb.length() > 0) sb.setLength(sb.length() - 1); // remove trailing slash
        return sb.toString();
    }


    // #region TESTS

        private void printTest(String[] piecePool, int groupSize){

            // Basic test function to just print all groups
            // Allows duplicates but does not double-count them
            // Thus it's a k-multicombination problem
            count = 0;
            //timer
            long startTime = System.nanoTime();
            recursiveTest(8, 0, 10, ""); // can find (and print!) all 24310 combinations in 86ms!
            long endTime = System.nanoTime();
            System.out.println("Total combinations: " + count);
            // time in ms
            System.out.println("Time taken: " + (endTime - startTime) / 1000000.0 + " ms");

        }

        private void recursiveTest(int depth, int start, int n, String s){
            // Figured this out by looking at the photo here: https://en.wikipedia.org/wiki/Combination#Number_of_combinations_with_repetition 
            // The idea is that for each digit in the previous level,
            // the next level does 12345-2345-345-45-5 (for n=5)
            // To be honest, recursion still feels like sorcery haha
            if(depth == 0){
                count++;
                System.out.println(s);
            }else{
                for(int i = start; i < n; i++){
                    recursiveTest(depth - 1, i, n, s + (i+1));
                }
            }
        }

    // #endregion TESTS


























    // #region DIAMETER ASCENT TESTS

        public void diameterAscent(String[] piecePool, int groupSize, int width, int height, int startingDepth){
            // For now I'm ignoring starting depth and just trying it with one, two, three, etc.

            globalDiameter = -1;
            //timer        
            long startTime = System.nanoTime();
            count = 0;
            // Step 1: Create an array of indices corresponding to the pieces in the pool
            // This will let us quickly reference the library in the recursive grouping function
            int numPieces = piecePool.length;
            int[] id = new int[numPieces];
            for(int i = 0; i < numPieces; i++){
                id[i] = -1;
                for (int j = 0; j < pieceName.length; j++) {
                    if (pieceName[j].equals(piecePool[i])) {
                        id[i] = j;
                        break;
                    }
                }
            }

            // Step 2: Precompute reformatted pieces
            long[] pieces = new long[numPieces];
            for(int i = 0; i < numPieces; i++){
                pieces[i] = embed(id[i], width);
                System.out.println(piecePool[i] + " reformatted to " + Long.toBinaryString(pieces[i]));
            }

            // Step 3: Start with seed of all pairs of pieces
            // for(int i = 0; i < numPieces; i++){
            //     for(int j = i; j < numPieces; j++){
            //         long[] seed = new long[2];
            //         seed[0] = pieces[i];
            //         seed[1] = pieces[j];
            //         int initialDiameter = new GroupSearcher(seed, width, height, 1000000).estimateDiameter();
            //         System.out.println("Starting with seed: " + Arrays.toString(seed) + " diameter " + initialDiameter);
            //         addBestPiece(initialDiameter, pieces, groupSize, seed, width, height);
            //     }
            // }

            for(int i = 0; i < numPieces; i++){
                long[] seed = new long[1];
                seed[0] = pieces[i];
                int initialDiameter = new GroupSearcher(seed, width, height, 0, 1000000).estimateDiameter();
                //System.out.println("Starting with seed: " + Arrays.toString(seed) + " diameter " + initialDiameter);
                addBestPiece2(initialDiameter, pieces, groupSize, seed, width, height, groupSize);
            }

            long endTime = System.nanoTime();
            double timeTaken = (endTime - startTime) / 1000000.0;
            System.out.println("Test finished after " + timeTaken + " ms");
            // 
        }

        // Dud. Couldn't come up with a better function than just diameter
        private boolean addBestPiece3(int myDiameter, long[] pieces, int groupSize, long[] groupInProgress, int width, int height, int tries){
            
            if(groupInProgress.length == groupSize){
                count++;
                System.out.println("Found group with diameter " + myDiameter + ": " + Arrays.toString(groupInProgress));
                return true;
            }

            // First, iterate through all pieces and add their improvements to an array
            // Improvements may be negative! Greedily, we hope that always picking 
            // the best one will lead to a good solution, but this may not always be the case.
            // Thus, we will keep track of all improvements and try them in order,
            // backtracking if we reach a dead end.
            int depth = groupInProgress.length + 1;

            // Create a score based on both diameter estimate and number of packings
            double[] scores = new double[pieces.length];
            int[] diameters = new int[pieces.length];
            int[] packings = new int[pieces.length];
            int maxDiameter = Integer.MIN_VALUE;
            int maxPackings = Integer.MIN_VALUE;
            for(int i=0; i < pieces.length; i++){
                //System.out.println("Testing piece " + i + " added to " + Arrays.toString(groupInProgress));
                System.out.print("."); // progress dot
                long[] testGroup = append(groupInProgress, pieces[i]);
                GroupSearcher gs = new GroupSearcher(testGroup, width, height, 0, 1000000);
                int[] results = gs.diameterAndPackings();
                diameters[i] = results[0];
                packings[i] = results[1];
                maxDiameter = Math.max(maxDiameter, diameters[i]);
                maxPackings = Math.max(maxPackings, packings[i]);
            }

            // Normalize diameters by max diameter
            double[] normDiameters = new double[pieces.length];
            double[] normPackings = new double[pieces.length];
            if(maxDiameter > 0){
                for(int i=0; i < diameters.length; i++){
                    normDiameters[i] = (double)diameters[i] / maxDiameter;
                }
            }

            // Normalize packings by max packings
            if(maxPackings > 0){
                for(int i=0; i < packings.length; i++){
                    normPackings[i] = (double)packings[i] / maxPackings;
                }
            }

            // Create a score based on both diameter estimate and number of packings
            for(int i=0; i < pieces.length; i++){
                double normDepth = (double)depth / groupSize;
                // Give more weight to packings at the start, more weight to diameter at the end
                double weightDiameter = normDepth;
                double weightPackings = 1 - normDepth;
                scores[i] = (normDiameters[i]);//* (1 + normPackings[i]));

                //scores[i] = normDiameters[i] + normPackings[i];
                //scores[i] = normDiameters[i];// * (1 + normPackings[i]);
                //System.out.println("Piece " + i + " diameter " + normDiameters[i] + " packings " + normPackings[i] + " score " + scores[i]);
            }

            // Find the piece with the best score
            int bestPiece = -1;
            double bestScore = Double.MIN_VALUE;
            for(int i=0; i < scores.length; i++){
                if(scores[i] > bestScore){
                    bestScore = scores[i];
                    bestPiece = i;
                }
            }

            //System.out.println("Best piece to add is " + bestPiece + " with score " + bestScore + " diameter " + diameters[bestPiece] + " packings " + packings[bestPiece]);

            // If we found a best piece, we can add it to the group
            if(bestPiece != -1){
                long[] newGroup = append(groupInProgress, pieces[bestPiece]);
                if(addBestPiece3(maxDiameter, pieces, groupSize, newGroup, width, height, tries)){
                    return true;
                }
            }

            return false;
        }

        private boolean addBestPiece2(int myDiameter, long[] pieces, int groupSize, long[] groupInProgress, int width, int height, int tries){
            
            if(groupInProgress.length == groupSize){
                count++;
                if (myDiameter > globalDiameter){
                    globalDiameter = myDiameter;
                    System.out.println("\nNew global best diameter " + globalDiameter + ": " + Arrays.toString(groupInProgress));
                }
                //System.out.println("Found group with diameter " + myDiameter + ": " + Arrays.toString(groupInProgress));
                return true;
            }

            // First, iterate through all pieces and add their improvements to an array
            // Improvements may be negative! Greedily, we hope that always picking 
            // the best one will lead to a good solution, but this may not always be the case.
            // Thus, we will keep track of all improvements and try them in order,
            // backtracking if we reach a dead end.
            int[] improvements = new int[pieces.length];
            int bestImprovement = Integer.MIN_VALUE;
            for(int i=0; i < pieces.length; i++){
                //System.out.println("Testing piece " + i + " added to " + Arrays.toString(groupInProgress));
                //System.out.print("."); // progress dot
                long[] testGroup = append(groupInProgress, pieces[i]);
                GroupSearcher gs = new GroupSearcher(testGroup, width, height, 0, 1000000);
                int diameter = gs.estimateDiameter();
                int improvement = diameter - myDiameter;
                bestImprovement = Math.max(bestImprovement, improvement);
                improvements[i] = improvement;
            }
            if(bestImprovement < 0){
                // No piece improved diameter, so we need to backtrack
                return false;
            }


            // Create array of the indices of the sorted improvements
            // e.x. suppose piece[0] has improvement 3, piece[1] has improvement -1, piece[2] has improvement 0
            // then sortedIndices = [0, 2, 1] so we try piece 0 first, then piece 2, then piece 1
            Integer[] sortedIndices = new Integer[pieces.length];
            for(int i=0; i < pieces.length; i++){
                sortedIndices[i] = i;
            }
            // Clever Copilot haha
            Arrays.sort(sortedIndices, (a, b) -> Integer.compare(improvements[b], improvements[a])); // sort in descending order
            for(int i = 0; i < Math.min(tries, sortedIndices.length); i++){
                int index = sortedIndices[i];
                int improvement = improvements[index];
                long[] testGroup = append(groupInProgress, pieces[index]);
                int newDiameter = myDiameter + improvement;
                addBestPiece2(newDiameter, pieces, groupSize, testGroup, width, height, tries - 1);
                // if(addBestPiece2(newDiameter, pieces, groupSize, testGroup, width, height)){
                //     //found a group
                //     if(testGroup.length == groupSize)
                //     System.out.println("\nFound group with diameter " + newDiameter + ": " + Arrays.toString(testGroup));
                //     return true;
                // }
            }
            return false;
        }

        private boolean addBestPiece(int myDiameter, long[] pieces, int groupSize, long[] groupInProgress, int width, int height){
            if(groupInProgress.length == groupSize){
                count++;
                //System.out.println("Found group with diameter " + myDiameter + ": " + Arrays.toString(groupInProgress));
                return true;
            }
            int maxDiameter = myDiameter;
            int bestIndex = -1;
            for(int i=0; i < pieces.length; i++){
                System.out.println("Testing piece " + i + " added to " + Arrays.toString(groupInProgress));
                long[] testGroup = append(groupInProgress, pieces[i]);
                GroupSearcher gs = new GroupSearcher(testGroup, width, height, 0, 1000000);
                int diameter = gs.estimateDiameter();
                if(diameter > maxDiameter){
                    maxDiameter = diameter;
                    bestIndex = i;
                }
            }
            if(bestIndex == -1){ // No piece improved diameter, so we need to backtrack
                //backtrack
                return false;
            }else{
                if(addBestPiece(maxDiameter, pieces, groupSize, append(groupInProgress, pieces[bestIndex]), width, height)){
                    //found a group
                    System.out.println("Found group with diameter " + maxDiameter + ": " + Arrays.toString(append(groupInProgress, pieces[bestIndex])));
                    return true;
                }
            }
            return false;
        }

    // #endregion DIAMETER ASCENT TESTS

    // #region RANDOM TESTS

        public void randomExploration(String[] piecePool, int groupSize, int width, int height){
            //timer        
            long startTime = System.nanoTime();
            count = 0;
            // Step 1: Create an array of indices corresponding to the pieces in the pool
            // This will let us quickly reference the library in the recursive grouping function
            int numPieces = piecePool.length;
            int[] id = new int[numPieces];
            for(int i = 0; i < numPieces; i++){
                id[i] = -1;
                for (int j = 0; j < pieceName.length; j++) {
                    if (pieceName[j].equals(piecePool[i])) {
                        id[i] = j;
                        break;
                    }
                }
            }

            // Step 2: Precompute reformatted pieces
            long[] pieces = new long[numPieces];
            for(int i = 0; i < numPieces; i++){
                pieces[i] = embed(id[i], width);
                System.out.println(piecePool[i] + " reformatted to " + Long.toBinaryString(pieces[i]));
            }

            // Step 3: Randomly generate groups and evaluate them
            HashSet<String> seenGroups = new HashSet<>();
            // First keep making groups until one has a positive diameter
            int diameter = -1;
            long[] group = null; // Declare group here so it's accessible in the next loop
            while(diameter <= 0){
                group = new long[groupSize];
                StringBuilder sb = new StringBuilder();
                for(int i = 0; i < groupSize; i++){
                    int pieceIndex = (int)(Math.random() * pieces.length);
                    group[i] = pieces[pieceIndex];
                    sb.append(pieceIndex).append(","); // create a string representation of the group
                }
                String groupString = sb.toString();
                if(!seenGroups.contains(groupString)){
                    seenGroups.add(groupString);
                    GroupSearcher gs = new GroupSearcher(group, width, height, 0, 1000000);
                    diameter = gs.estimateDiameter();
                    if(diameter > 0){
                        count++;
                        System.out.println("Found group with diameter " + diameter + ": " + Arrays.toString(group));
                    }
                }
            }

            // Then for N iterations, go through each element and try replacing it with all other random pieces.
            // Keep the change that improves diameter the most, then move to the next element, etc.
            int generations = 1000;
            for(int gen = 0; gen < generations; gen++){
                System.out.println("Generation " + (gen + 1) + " of " + generations + " starting with diameter " + diameter + ": " + Arrays.toString(group));
                boolean improved = false;
                for(int pos = 0; pos < groupSize; pos++){
                    int bestDiameter = diameter;
                    long bestPiece = group[pos];
                    for(int pieceIndex = 0; pieceIndex < pieces.length; pieceIndex++){
                        if(pieces[pieceIndex] != group[pos]){ // only test if different piece
                            long[] testGroup = test(group, pieces[pieceIndex], pos);
                            GroupSearcher gs = new GroupSearcher(testGroup, width, height, 0, 1000000);
                            int testDiameter = gs.estimateDiameter();
                            if(testDiameter > bestDiameter){
                                bestDiameter = testDiameter;
                                bestPiece = pieces[pieceIndex];
                            }
                        }
                    }
                    if(bestDiameter > diameter){
                        // We found an improvement
                        group[pos] = bestPiece;
                        diameter = bestDiameter;
                        improved = true;
                        System.out.println(" Improved to diameter " + diameter + ": " + Arrays.toString(group));
                    }
                }
                if(!improved){
                    // If no improvement, change two random elements to random other pieces
                    // This injects "temperature" into the system to keep it from stagnating
                    for(int n = 0; n < 2; n++){
                        int pos = (int)(Math.random() * groupSize);
                        int pieceIndex = (int)(Math.random() * pieces.length);
                        group[pos] = pieces[pieceIndex];
                    }
                    diameter = new GroupSearcher(group, width, height, 0, 1000000).estimateDiameter();
                    System.out.println(" No improvement, randomizing two pieces: " + Arrays.toString(group));

                }else{
                    count++;
                }
            }

        }



    // #endregion RANDOM TESTS


}
