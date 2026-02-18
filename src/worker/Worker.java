package worker;

import aggregator.Aggregator;
import articleSummary.ArticleSummary;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

public class Worker implements Runnable {
    private final int id;
    private final List<String> allFiles;
    private final AtomicInteger globalIndex;
    private final Aggregator globalAggregator;
    private final Aggregator localAggregator;
    private final CyclicBarrier barrier;
    private final Map<String, Path> configFiles;
    private final ConcurrentLinkedQueue<String> writeTasks;

    private static final ObjectMapper mapper = new ObjectMapper();
    private static final JsonFactory jsonFactory = mapper.getFactory();
    private static final Pattern SPACE_PATTERN = Pattern.compile("\\s+");

    public Worker(int id, List<String> allFiles, AtomicInteger globalIndex,
                  Aggregator globalAggregator, CyclicBarrier barrier,
                  Map<String, Path> configFiles, ConcurrentLinkedQueue<String> writeTasks) {
        this.id = id;
        this.allFiles = allFiles;
        this.globalIndex = globalIndex;
        this.globalAggregator = globalAggregator;
        this.localAggregator = new Aggregator();
        this.barrier = barrier;
        this.configFiles = configFiles;
        this.writeTasks = writeTasks;
    }

    @Override
    public void run() {
        try {
            processFiles();
            globalAggregator.combine(localAggregator);
            barrier.await();
            if (id == 0) {
                globalAggregator.prepareWriteTasks(configFiles, writeTasks);
            }
            barrier.await();
            processWriteTasks();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void processFiles() {
        int n = allFiles.size();
        while (true) {
            int i = globalIndex.getAndIncrement();
            if (i >= n) break;
            try {
                Path path = Path.of(allFiles.get(i));
                if (Files.exists(path)) {
                    try (JsonParser jp = jsonFactory.createParser(path.toFile())) {
                        parseJsonStream(jp);
                    }
                }
            } catch (Exception e) {
                // ignore
            }
        }
    }

    private void processWriteTasks() {
        Path outputDir = Path.of(".");
        String task;
        while ((task = writeTasks.poll()) != null) {
            String[] parts = task.split(":", 2);
            String type = parts[0];
            String name = parts.length > 1 ? parts[1] : "";

            try {
                switch (type) {
                    case "ALL":
                        globalAggregator.writeAllArticlesTask(outputDir);
                        break;
                    case "CAT":
                        globalAggregator.writeCategoryTask(name, outputDir);
                        break;
                    case "LANG":
                        globalAggregator.writeLanguageTask(name, outputDir);
                        break;
                    case "KEYWORDS":
                        globalAggregator.writeKeywordsTask(configFiles, outputDir);
                        break;
                    case "REPORTS":
                        globalAggregator.writeReportsTask(configFiles, outputDir);
                        break;
                    default:
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private void parseJsonStream(JsonParser jp) throws IOException {
        JsonToken token = jp.nextToken();
        if (token == null) return;
        if (token == JsonToken.START_ARRAY) {
            while (jp.nextToken() != JsonToken.END_ARRAY) {
                handleNode(mapper.readTree(jp));
            }
        } else if (token == JsonToken.START_OBJECT) {
            while (jp.currentToken() == JsonToken.START_OBJECT) {
                handleNode(mapper.readTree(jp));
                jp.nextToken();
            }
        }
    }

    private void handleNode(JsonNode node) {
        if (node == null || !node.isObject()) return;
        String uuid = getMandatoryText(node, "uuid");
        if (uuid == null) return;
        String title = getMandatoryText(node, "title");
        if (title == null) return;
        String publishedStr = getMandatoryText(node, "published");
        if (publishedStr == null) return;

        Instant published;
        try { published = Instant.parse(publishedStr); } catch (Exception e) { return; }

        String author = getOptionalText(node, "author");
        String url = getOptionalText(node, "url");
        String language = getOptionalText(node, "language");
        List<String> categories = extractCategories(node);

        String rawText = getOptionalText(node, "text");
        Set<String> processedWords = Collections.emptySet();

        if ("english".equalsIgnoreCase(language) && rawText != null && !rawText.isEmpty()) {
            processedWords = processTextCorrectly(rawText);
        }

        ArticleSummary summary = new ArticleSummary(
                uuid, title, author, url, published, language, categories, processedWords
        );
        localAggregator.processArticle(summary);
    }

    private Set<String> processTextCorrectly(String text) {
        Set<String> uniqueWords = new HashSet<>();
        String lower = text.toLowerCase();
        String[] tokens = SPACE_PATTERN.split(lower);
        for (String token : tokens) {
            String cleaned = stripNonLetters(token);
            if (!cleaned.isEmpty()) uniqueWords.add(cleaned);
        }
        return uniqueWords;
    }

    private String stripNonLetters(String token) {
        int len = token.length();
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            char c = token.charAt(i);
            if (c >= 'a' && c <= 'z') sb.append(c);
        }
        return sb.toString();
    }

    private String getMandatoryText(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return (n != null && !n.isNull() && n.isTextual()) ? n.asText() : null;
    }
    private String getOptionalText(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return (n != null && n.isTextual()) ? n.asText() : "";
    }
    private List<String> extractCategories(JsonNode node) {
        JsonNode cats = node.get("categories");
        if (cats == null || !cats.isArray() || cats.isEmpty()) return Collections.emptyList();
        List<String> categories = new ArrayList<>();
        for (JsonNode cn : cats) {
            if (cn != null && cn.isTextual()) {
                String c = cn.asText();
                if (!c.isBlank()) categories.add(c);
            }
        }
        return categories;
    }
}