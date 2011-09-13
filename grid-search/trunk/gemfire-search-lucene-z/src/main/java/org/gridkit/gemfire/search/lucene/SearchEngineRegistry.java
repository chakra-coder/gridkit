package org.gridkit.gemfire.search.lucene;

import org.gridkit.search.lucene.SearchEngine;

public interface SearchEngineRegistry {
    void registerSearchEngine(String searchEngineName, SearchEngine searchEngine);
    void unregisterSearchEngine(String searchEngineName);

    SearchEngine getSearchEngine(String searchEngineName);
    boolean hasSearchEngine(String searchEngineName);

    void close();
}
