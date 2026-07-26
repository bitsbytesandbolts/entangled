import java.util.HashSet;

public class PiecePacker {

    private int width, height;
    
    private int pieceCount;
    private int[] pieceWidth, pieceHeight;
    private long[][] pieceMoveBitboards;

    private HashSet<int[][]> packings;

    public PiecePacker(long[] pieces, int width, int height) {

        this.width = width;
        this.height = height;
        this.packings = new HashSet<>();
        
        // Calculate metrics for each piece
        this.pieceCount = pieces.length;
        this.pieceWidth = new int[pieceCount];
        this.pieceHeight = new int[pieceCount];
        this.pieceMoveBitboards = new long[pieceCount][width * height];

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
                    long p = pieces[i];
                    while(p != 0L){
                        int nextCoord = Long.numberOfTrailingZeros(p);  // Get the position of the next 1
                        int r = nextCoord / width;                       // Calculate row
                        int c = nextCoord % width;                       // Calculate column
                        if(r < minRow[i]) minRow[i] = r;
                        if(c < minCol[i]) minCol[i] = c;
                        if(r > maxRow[i]) maxRow[i] = r;
                        if(c > maxCol[i]) maxCol[i] = c;
                        p &= (p - 1);                                   // Remove from piece
                    }
                    pieceWidth[i] = maxCol[i] - minCol[i] + 1;
                    pieceHeight[i] = maxRow[i] - minRow[i] + 1;
                    // If not at top left, shift
                    pieces[i] = pieces[i] >> (width * minRow[i] + minCol[i]);
                    print(pieces[i]);
                }
            // #endregion INIT BBOXES

            // #region PRECOMP MOVES
                for(int i = 0; i < pieceCount; i++){
                    for(int r = 0; r < height + 1 - pieceHeight[i]; r++){
                        for(int c = 0; c < width + 1 - pieceWidth[i]; c++){
                            int shift = r*width + c; // Note we already shifted the pieces to the top left!
                            pieceMoveBitboards[i][r*width + c] = pieces[i] << shift;
                            print(pieceMoveBitboards[i][r*width + c]);
                        }
                    }
                }
            // #endregion PRECOMP MOVES

        // #endregion INIT PIECES
    }
    
    public HashSet<int[][]> packPieces(){
        packings.clear();
        long[] packing = new long[pieceCount];
        packPiece(packing, 0L, pieceCount - 1);
        // for(int[][] p : packings){
        //     for(int r = 0; r < height; r++){
        //         for(int c = 0; c < width; c++){
        //             System.out.print(p[r][c] + " ");
        //         }
        //         System.out.println();
        //     }
        //     System.out.println();
        // }
        System.out.println("Found " + packings.size() + " packings.");
        return packings;
    }

    public boolean packPiece(long[] packing, long bitboard, int id) {
        
        // If all pieces placed, we found a packing
        if(id == -1){
            packings.add(toGrid(packing));
        }else{
            // Try placing the current piece in every possible position
            for(int r = 0; r < height + 1 - pieceHeight[id]; r++){
                for(int c = 0; c < width + 1 - pieceWidth[id]; c++){
                    long testBitboard = pieceMoveBitboards[id][r*width + c];
                    if((bitboard & testBitboard) == 0){
                        long[] testPacking = test(packing, testBitboard, id);
                        if(packPiece(testPacking, bitboard | testBitboard, id - 1)){
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    private long[] test(long[] packing, long bitboard, int id){
        long[] test = packing.clone();
        test[id] = bitboard;
        return test;
    }

    private int[][] toGrid(long[] packing){
        int[][] grid = new int[height][width];
        for(int i = 0; i < pieceCount; i++){
            long p = packing[i];
            while(p != 0L){
                int nextCoord = Long.numberOfTrailingZeros(p);  // Get the position of the next 1
                int r = nextCoord / width;                       // Calculate row
                int c = nextCoord % width;                       // Calculate column
                grid[r][c] = i + 1;
                p &= (p - 1);                                   // Remove from piece
            }
        }
        return grid;
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

    public static long gridToBitboard(int[][] grid){
        int height = grid.length;
        int width = grid[0].length;
        long bitboard = 0L;
        for(int r = 0; r < height; r++){
            for(int c = 0; c < width; c++){
                if(grid[r][c] != 0){
                    bitboard |= (1L << (r*width + c));
                }
            }
        }
        return bitboard;
    }

    public static long[] gridToLongGroup(int[][] grid){
        int height = grid.length;
        int width = grid[0].length;
        int pieceCount = 0;
        for(int r = 0; r < height; r++){
            for(int c = 0; c < width; c++){
                if(grid[r][c] > pieceCount){
                    pieceCount = grid[r][c];
                }
            }
        }
        long[] pieces = new long[pieceCount];
        for(int r = 0; r < height; r++){
            for(int c = 0; c < width; c++){
                if(grid[r][c] != 0){
                    pieces[grid[r][c] - 1] |= (1L << (r*width + c));
                }
            }
        }
        for(int i = 0; i < pieceCount; i++){
            System.out.println("Piece " + (i + 1) + ":");
            long bitboard = pieces[i];
            for(int rr = 0; rr < height; rr++){
                for(int cc = 0; cc < width; cc++){
                    long bit = 1L << (rr*width + cc);
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
        return pieces;
    }

}