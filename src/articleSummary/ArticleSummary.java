package articleSummary;

import java.time.Instant;
import java.util.Collections;
import java.util.List;
import java.util.Set;

public class ArticleSummary {
    private final String uuid;
    private final String title;
    private final String author;
    private final String url;
    private final Instant published;
    private final String language;
    private final List<String> categories;
    private final Set<String> processedWords;

    public ArticleSummary(String uuid, String title, String author, String url,
                          Instant published, String language, List<String> categories,
                          Set<String> processedWords) {
        this.uuid = uuid;
        this.title = title;
        this.author = author;
        this.url = url;
        this.published = published;
        this.language = language;
        this.categories = categories;
        this.processedWords = processedWords != null ? processedWords : Collections.emptySet();
    }

    public String getUuid() { return uuid; }
    public String getTitle() { return title; }
    public String getAuthor() { return author; }
    public String getUrl() { return url; }
    public Instant getPublished() { return published; }
    public String getLanguage() { return language; }
    public List<String> getCategories() { return categories; }
    public Set<String> getProcessedWords() { return processedWords; }
}