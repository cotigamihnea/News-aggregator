package aggregator;

import articleSummary.ArticleSummary;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.stream.Collectors;

public class Aggregator {
    private final Map<String, Integer> uuidCounts = new HashMap<>();
    private final Map<String, ArticleSummary> uuidToArticle = new HashMap<>();
    private final Map<String, Set<String>> titleToUuids = new HashMap<>();
    private final Map<String, Integer> authorCounts = new HashMap<>();
    private final Map<String, Integer> languageCounts = new HashMap<>();
    private final Map<String, Set<String>> categoryToUuids = new HashMap<>();

    private List<ArticleSummary> validSortedArticles = null;
    private int duplicatesCount = 0;
    private int totalArticlesSeen = 0;

    public Aggregator() {
        // Constructor
    }

    public void processArticle(ArticleSummary article) {
        totalArticlesSeen++;
        String uuid = article.getUuid();
        String title = article.getTitle();

        uuidCounts.merge(uuid, 1, Integer::sum);
        uuidToArticle.putIfAbsent(uuid, article);
        titleToUuids.computeIfAbsent(title, k -> new HashSet<>()).add(uuid);

        String author = article.getAuthor();
        if (!author.isEmpty()) authorCounts.merge(author, 1, Integer::sum);

        String lang = article.getLanguage();
        if (!lang.isEmpty()) languageCounts.merge(lang, 1, Integer::sum);

        for (String c : article.getCategories()) {
            String normalized = normalizeCategoryName(c);
            categoryToUuids.computeIfAbsent(normalized, k -> new HashSet<>()).add(uuid);
        }
    }

    public synchronized void combine(Aggregator other) {
        this.totalArticlesSeen += other.totalArticlesSeen;

        other.uuidCounts.forEach((k, v) -> this.uuidCounts.merge(k, v, Integer::sum));
        this.uuidToArticle.putAll(other.uuidToArticle);
        other.titleToUuids.forEach((k, v) ->
                this.titleToUuids.merge(k, v, (s1, s2) -> { s1.addAll(s2); return s1; }));
        other.authorCounts.forEach((k, v) -> this.authorCounts.merge(k, v, Integer::sum));
        other.languageCounts.forEach((k, v) -> this.languageCounts.merge(k, v, Integer::sum));
        other.categoryToUuids.forEach((k, v) ->
                this.categoryToUuids.merge(k, v, (s1, s2) -> { s1.addAll(s2); return s1; }));
    }

    public void prepareWriteTasks(Map<String, Path> configFiles, ConcurrentLinkedQueue<String> queue) {
        computeValidArticles();
        queue.add("ALL");
        queue.add("KEYWORDS");
        queue.add("REPORTS");

        List<String> allowedCats = readListFile(configFiles.get("categories.txt"));
        for (String rawCat : allowedCats) {
            queue.add("CAT:" + rawCat);
        }

        List<String> allowedLangs = readListFile(configFiles.get("languages.txt"));
        for (String lang : allowedLangs) {
            queue.add("LANG:" + lang);
        }
    }

    private void computeValidArticles() {
        Set<String> invalidUuids = new HashSet<>();
        uuidCounts.forEach((uuid, count) -> {
            if (count > 1) invalidUuids.add(uuid);
        });
        titleToUuids.forEach((title, uuids) -> {
            if (uuids.size() > 1) invalidUuids.addAll(uuids);
        });

        this.validSortedArticles = uuidToArticle.values().stream()
                .filter(a -> !invalidUuids.contains(a.getUuid()))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(ArticleSummary::getPublished, Comparator.reverseOrder())
                        .thenComparing(ArticleSummary::getUuid))
                .collect(Collectors.toList());

        this.duplicatesCount = totalArticlesSeen - validSortedArticles.size();
    }


    public void writeAllArticlesTask(Path outputDir) throws IOException {
        List<String> lines = validSortedArticles.stream()
                .map(a -> a.getUuid() + " " + DateTimeFormatter.ISO_INSTANT.format(a.getPublished()))
                .collect(Collectors.toList());
        writeLinesToFile(outputDir.resolve("all_articles.txt"), lines);
    }

    public void writeCategoryTask(String rawCat, Path outputDir) throws IOException {
        if (rawCat == null || rawCat.isEmpty()) return;
        String normCat = normalizeCategoryName(rawCat);
        List<String> uuids = validSortedArticles.stream()
                .filter(a -> a.getCategories().stream().anyMatch(c -> normalizeCategoryName(c).equals(normCat)))
                .map(ArticleSummary::getUuid)
                .sorted()
                .collect(Collectors.toList());

        if (!uuids.isEmpty()) {
            String fileName = rawCat.replace(",", "").trim().replaceAll("\\s+", "_") + ".txt";
            writeLinesToFile(outputDir.resolve(fileName), uuids);
        }
    }

    public void writeLanguageTask(String lang, Path outputDir) throws IOException {
        List<String> uuids = validSortedArticles.stream()
                .filter(a -> lang.equalsIgnoreCase(a.getLanguage()))
                .map(ArticleSummary::getUuid)
                .sorted()
                .collect(Collectors.toList());

        if (!uuids.isEmpty()) {
            writeLinesToFile(outputDir.resolve(lang + ".txt"), uuids);
        }
    }

    public void writeKeywordsTask(Map<String, Path> configFiles, Path outputDir) throws IOException {
        Set<String> linkingWords = new HashSet<>(readListFile(configFiles.get("english_linking_words.txt")));
        Map<String, Integer> wordCounts = new HashMap<>();

        for (ArticleSummary a : validSortedArticles) {
            if (!"english".equalsIgnoreCase(a.getLanguage())) continue;
            for (String w : a.getProcessedWords()) {
                if (!linkingWords.contains(w)) {
                    wordCounts.merge(w, 1, Integer::sum);
                }
            }
        }

        List<String> lines = wordCounts.entrySet().stream()
                .sorted((e1, e2) -> {
                    int cmp = Integer.compare(e2.getValue(), e1.getValue());
                    return cmp != 0 ? cmp : e1.getKey().compareTo(e2.getKey());
                })
                .map(e -> e.getKey() + " " + e.getValue())
                .collect(Collectors.toList());

        writeLinesToFile(outputDir.resolve("keywords_count.txt"), lines);
    }

    public void writeReportsTask(Map<String, Path> configFiles, Path outputDir) throws IOException {
        String bestAuthor = getTopStat(ArticleSummary::getAuthor);
        String topLanguage = getTopStat(ArticleSummary::getLanguage);
        String topCategory = getTopCategoryReport(readListFile(configFiles.get("categories.txt")));
        String mostRecent = getMostRecentReport();
        String topKeyword = getTopKeywordEn(configFiles);

        List<String> reportLines = new ArrayList<>();
        reportLines.add("duplicates_found - " + duplicatesCount);
        reportLines.add("unique_articles - " + validSortedArticles.size());
        reportLines.add("best_author - " + bestAuthor);
        reportLines.add("top_language - " + topLanguage);
        reportLines.add("top_category - " + topCategory);
        reportLines.add("most_recent_article - " + mostRecent);
        reportLines.add("top_keyword_en - " + topKeyword);

        writeLinesToFile(outputDir.resolve("reports.txt"), reportLines);
    }

    private String getTopStat(java.util.function.Function<ArticleSummary, String> extractor) {
        return validSortedArticles.stream()
                .map(extractor)
                .filter(s -> s != null && !s.isEmpty())
                .collect(Collectors.groupingBy(s -> s, Collectors.counting()))
                .entrySet().stream()
                .max((e1, e2) -> {
                    int cmp = Long.compare(e1.getValue(), e2.getValue());
                    return cmp != 0 ? cmp : e2.getKey().compareTo(e1.getKey());
                })
                .map(e -> e.getKey() + " " + e.getValue())
                .orElse("");
    }

    private String getTopCategoryReport(List<String> allowedCategories) {
        String bestCat = "";
        long maxCount = -1;

        for (String rawCat : allowedCategories) {
            if (rawCat == null || rawCat.isEmpty()) break;
            String normCat = normalizeCategoryName(rawCat);
            long count = validSortedArticles.stream()
                    .filter(a -> a.getCategories().stream().anyMatch(c -> normalizeCategoryName(c).equals(normCat)))
                    .count();

            String displayCat = rawCat.replace(",", "").trim().replaceAll("\\s+", "_");
            if (count > maxCount) {
                maxCount = count;
                bestCat = displayCat;
            } else if (count == maxCount && (bestCat.equals("") || displayCat.compareTo(bestCat) < 0)) {
                bestCat = displayCat;
            }
        }
        if (maxCount <= 0) return "";
        return bestCat + " " + maxCount;
    }

    private String getMostRecentReport() {
        if (validSortedArticles.isEmpty()) return "";
        ArticleSummary newest = validSortedArticles.get(0);
        return DateTimeFormatter.ISO_INSTANT.format(newest.getPublished()) + " " + (newest.getUrl() == null ? "" : newest.getUrl());
    }

    private String getTopKeywordEn(Map<String, Path> configFiles) {
        Set<String> linkingWords = new HashSet<>(readListFile(configFiles.get("english_linking_words.txt")));
        Map<String, Integer> wordCounts = new HashMap<>();

        for (ArticleSummary a : validSortedArticles) {
            if (!"english".equalsIgnoreCase(a.getLanguage())) continue;
            for (String w : a.getProcessedWords()) {
                if (!linkingWords.contains(w)) {
                    wordCounts.merge(w, 1, Integer::sum);
                }
            }
        }
        return wordCounts.entrySet().stream()
                .max((e1, e2) -> {
                    int cmp = Integer.compare(e1.getValue(), e2.getValue());
                    return cmp != 0 ? cmp : e2.getKey().compareTo(e1.getKey());
                })
                .map(e -> e.getKey() + " " + e.getValue())
                .orElse("");
    }

    private static void writeLinesToFile(Path p, List<String> lines) throws IOException {
        if (p.getParent() != null) Files.createDirectories(p.getParent());
        Files.write(p, lines, StandardCharsets.UTF_8);
    }

    private static List<String> readListFile(Path p) {
        try {
            if (p == null || !Files.exists(p)) return Collections.emptyList();
            List<String> lines = Files.readAllLines(p);
            if (lines.isEmpty()) return Collections.emptyList();
            return lines.stream().skip(1).map(String::trim).filter(s -> !s.isEmpty()).collect(Collectors.toList());
        } catch (IOException e) {
            return Collections.emptyList();
        }
    }

    private static String normalizeCategoryName(String s) {
        if (s == null) return "";
        return s.replace(",", "").trim().replaceAll("\\s+", "_").toLowerCase();
    }
}