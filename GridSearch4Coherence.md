# Introduction #
Oracle Coherence has a very powerful ability to create secondary indexes for any attribute of cached objects. While being quite powerful by themselves, standard Coherence indexes have their limitations. The two most important of them are:
  * memory consumption of build in indexes cannot be controlled (and they are [heap hungry](http://blog.griddynamics.com/2009/10/coherence-memory-usage-indexes.html) indeed);
  * only hash table or binary tree implementations are available.
Coherence 3.6 has introduced a new API which allows developers to overcome these limitations by plugging in custom implementations for secondary indexes into Coherence caches. Index API opens a way to integrate full-text and other specialized indexes for your distributed caches.

# Project Goals #
Coherence custom index API is quite complicated. The purpose of this project is to develop generic layer to simplify integration of custom indexes and to adapt widely used search libraries (like [Apache Lucene](http://lucene.apache.org)) to be used with Coherence.

## Advantages of Lucene index over built in Coherence index ##
  * Full text search with various query types
  * Better handling of high cardinality indexes ([read more](LuceneForHighCardinalityIndexes.md))
  * Compact index size ([read mode](LuceneVsCoherenceMemoryUsage.md))

## Advantages of integration search using coherence ##
  * Coherence will keep your index in sych with your data
  * In case of distributed cache, custom cache also gets distributed and will benefit from Coherence advanced parallel queries
  * Combine built in Coherence filters with custom ones to make Coherence filter API even more powerful

## Limitations of Coherence query API ##
  * Coherence Filter API is very basic, so advanced features of search libraries like scoring cannot be exposed. If you need scoring, faceted search, etc .. then Grid4Search would not be your choice.

# Documentation #
  * [JavaDoc GridSearch / Coherence common](http://gridkit.googlecode.com/svn/jdocs/grid-search/coherence-common/latest/index.html)
  * [JavaDoc GridSearch / Lucene search for Coherence](http://gridkit.googlecode.com/svn/jdocs/grid-search/coherence-lucene/latest/index.html)
  * [Getting started, searching data grid](HowToSearchDataGrid.md)
  * [Getting started, using Lucene in grid](HowToUsingLuceneInGrid.md)
  * [Implementing custom index for Coherence](HowToCustomSearchIndex.md)
  * [Coherence custom index API usage](CoherenceCustomIndexOverview.md)
  * [Coherence search core API usage](CoherenceSearchCoreAPI.md)

# Project structure #
There are two modules in project right now.

**[Grid4Search](#.md) / Coherence common** is a layer of abstraction on top of Oracle Coherence index API. It implements generic service provider interface (SPI) to develop custom index implementations.

**[Grid4Search](#.md) / Lucene integration** is implementation of SPI provided by module above using Apache Lucene as search implementation.

# Source #
## [Grid4Search](#.md) / Coherence common ##
| Trunk | **SVN:** https://gridkit.googlecode.com/svn/grid-search/trunk/coherence-search-common |
|:------|:--------------------------------------------------------------------------------------|
| 0.9| **SVN:** https://gridkit.googlecode.com/svn/releases/coherence-search-common-0.9 |

## [Grid4Search](#.md) / Lucene integration ##
| Trunk | **SVN:** https://gridkit.googlecode.com/svn/grid-search/trunk/coherence-search-lucene |
|:------|:--------------------------------------------------------------------------------------|
| 0.9 | **SVN:** https://gridkit.googlecode.com/svn/releases/coherence-search-lucene-0.9 |

# Maven #
See MavenRepo for details of project Maven repository
## [Grid4Search](#.md) / Coherence common ##
#### 0.9 version ####
```
<dependency>
    <groupId>org.gridkit.search</groupId>
    <artifactId>coherence-search-common</artifactId>
    <version>0.9</version>
</dependency>
```
## [Grid4Search](#.md) / Lucene integration ##
#### 0.9 version ####
```
<dependency>
    <groupId>org.gridkit.search</groupId>
    <artifactId>coherence-search-lucene</artifactId>
    <version>0.9</version>
</dependency>
```


# Binaries #
  * [coherence-search-common-0.9.jar](https://gridkit.googlecode.com/svn/repo/org/gridkit/search/coherence-search-common/0.9/coherence-search-common-0.9.jar)
  * [coherence-search-common-0.9-javadoc.jar](https://gridkit.googlecode.com/svn/repo/org/gridkit/search/coherence-search-common/0.9/coherence-search-common-0.9-javadoc.jar)
  * [coherence-search-lucene-0.9.jar](https://gridkit.googlecode.com/svn/repo/org/gridkit/search/coherence-search-lucene/0.9/coherence-search-lucene-0.9.jar)
  * [coherence-search-lucene-0.9-javadoc.jar](https://gridkit.googlecode.com/svn/repo/org/gridkit/search/coherence-search-lucene/0.9/coherence-search-lucene-0.9-javadoc.jar)