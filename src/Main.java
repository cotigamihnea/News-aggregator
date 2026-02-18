import aggregator.Aggregator;
import worker.Worker;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;

public class Main {
    public static void main(String[] args) {
        try {
            if (args.length < 3) {
                System.exit(1);
            }

            int numThreads = Integer.parseInt(args[0]);
            Path articlesFile = Paths.get(args[1]);
            Path inputsFile = Paths.get(args[2]);

            Map<String, Path> configFiles = readInputsFile(inputsFile);
            if (!Files.exists(articlesFile)) System.exit(1);

            List<String> allFiles = readAllFilePaths(articlesFile, articlesFile.getParent());

            // Resurse partajate
            AtomicInteger globalIndex = new AtomicInteger(0);
            Aggregator globalAggregator = new Aggregator();

            ConcurrentLinkedQueue<String> writeTasks = new ConcurrentLinkedQueue<>();

            CyclicBarrier barrier = new CyclicBarrier(numThreads);

            Thread[] threads = new Thread[numThreads];

            // Creare thread-uri si pornire (o singura data!!)
            for (int i = 0; i < numThreads; i++) {
                Worker w = new Worker(i, allFiles, globalIndex, globalAggregator,
                        barrier, configFiles, writeTasks);
                threads[i] = new Thread(w, "worker-" + i);
                threads[i].start();
            }

            // Join thread-uri (o singura data!!)
            for (int i = 0; i < numThreads; i++) {
                threads[i].join();
            }

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private static List<String> readAllFilePaths(Path articlesFile, Path baseDir) throws IOException {
        if (baseDir == null) baseDir = Paths.get(".");
        List<String> paths = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(articlesFile)) {
            String line = br.readLine(); // skip count
            if (line == null) return paths;

            while ((line = br.readLine()) != null) {
                String trimmed = line.trim();
                if (!trimmed.isEmpty()) {
                    paths.add(baseDir.resolve(trimmed).normalize().toString());
                }
            }
        }
        return paths;
    }

    private static Map<String, Path> readInputsFile(Path inputsFile) throws IOException {
        if (!Files.exists(inputsFile)) System.exit(1);
        Path inputsDir = inputsFile.getParent();
        if (inputsDir == null) inputsDir = Paths.get(".");

        List<String> lines = Files.readAllLines(inputsFile);
        if (lines.isEmpty()) System.exit(1);

        int count = Integer.parseInt(lines.get(0).trim());
        Map<String, Path> configFiles = new HashMap<>();

        for (int i = 1; i <= count && i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (trimmed.isEmpty()) continue;
            Path resolvedPath = inputsDir.resolve(trimmed).normalize();
            if (Files.exists(resolvedPath)) {
                configFiles.put(resolvedPath.getFileName().toString(), resolvedPath);
            }
        }
        return configFiles;
    }
}