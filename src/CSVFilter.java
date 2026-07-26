import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;

public class CSVFilter {
    // Headers: 
    // GroupID, PiecesA, PiecesB, Packings, Islands, MaxEstimatedDiameter,
    // StartStateA, StartStateB, EndStateA, EndStateB, TimeTaken(ms)

    public static void filterByDiameter(int threshold, String inputFile){
        // Read input CSV and only keep rows with diameter >= threshold
        // using the MaxEstimatedDiameter header column
        // save the result to a new CSV file or print to console

        // Start new CSV with header
        
        StringBuilder csvBuilderGlobal = new StringBuilder();

        try(BufferedReader reader = new BufferedReader(new FileReader(inputFile))){
            String header = reader.readLine();
            if(header == null){
                return;
            }

            csvBuilderGlobal.append(header).append("\n");
            String[] headerParts = header.split(",");
            int diameterIndex = -1;
            for(int i = 0; i < headerParts.length; i++){
                if(headerParts[i].equals("MaxEstimatedDiameter")){
                    diameterIndex = i;
                    break;
                }
            }
            if(diameterIndex == -1){
                throw new IllegalArgumentException("Could not find MaxEstimatedDiameter column in " + inputFile);
            }

            String line;
            while((line = reader.readLine()) != null){
                String[] parts = line.split(",");
                if(parts.length <= diameterIndex) continue; // Skip malformed lines
                try{
                    int diameter = Integer.parseInt(parts[diameterIndex]);
                    if(diameter >= threshold){
                        csvBuilderGlobal.append(line).append("\n");
                        System.out.println(line);
                    }
                }catch(NumberFormatException e){
                    // Skip lines where diameter is not a valid integer (e.g., ">limit")
                }
            }
        }catch(IOException e){
            e.printStackTrace();
        }

        // Save CSV
        String outputFile = "filtered_by_diameter_" + threshold + "_" + inputFile;
        try(java.io.FileWriter writer = new java.io.FileWriter(outputFile)){
            writer.write(csvBuilderGlobal.toString());
            System.out.println("Filtered CSV saved to: " + outputFile);
        }catch(IOException e){
            e.printStackTrace();
        }
    }
}
