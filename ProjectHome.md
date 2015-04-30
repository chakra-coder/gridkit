# Project goal #
In memory data grids have become a standard part of java application stack. There are plenty data grid middleware both commercial and open source. This project is home of various supplementary stuff related to data grids. This includes tools, libraries, benchmarks etc.

**Owner's note:** I have to admit that there are a lot of mess at gridkit. I'm trying to sort it out, but stuff like clean project/build setup, proper release procedure and documentation are taking huge amount of time. So if you find anything that you feel may be useful for you, but confused with a lack of documentation or build issues - do not hesitate to contact me (alexey dot ragozin at gmail.com). I will be glad to assist.

# Featured #

---

### [Nanocloud](NanoCloudTutorial.md) ###
A dead simple yet powerful library for [remote execution](http://blog.ragozin.info/2013/01/remote-code-execution-in-java-made.html) of java code.
  * no deployment
  * transparent remotting
  * down scale your topology to run in single JVM or up scale to dozens of servers with single line tweak

### [Grid4Search for Oracle Coherence](GridSearch4Coherence.md) ###
Coherence 3.6 has introduced API for creating custom indexes. `Grid4Search` provide developers with easy to use framework for integration with 3rd party indexing engines. [Lucene](http://lucene.apache.org) full text search is flagship integration of this project.
Now you can run Lucene queries against objects in your Coherence cache!


---

### [Reflection based POF serializer](ReflectionPofSerializer.md) ###
Very simple yet very useful piece of code. Serializes any object to POF using reflection. Key points:
  * no code required,
  * works with private constructors,
  * supports direct extraction of fields from POF [ReflectionPofExtractor](ReflectionPofExtractor.md)

---

# Incubation #

---

### [Coherence toolbox](CoherenceToolBox_overview.md) ###
A set of handy tools:
  * **Consistency guard** - handling data repair in case of partition lost due to disaster
  * **[ViCluster](ViCluster.md)** - running multiple Coherence nodes in single JVM, useful for automatic testing of multinode use cases
  * **ChTest** - comming soon, Coherence test automation library based on [Nanocloud](NanoCloudTutorial.md)
  * **[Distributed resource coordinator](DistributedResourceCoordinator.md)** - provides failover and high availability for application components
  * **[Omni-Stats](ClusterStats.md)** - simple utility for storing/aggregating statistics from multiple JVMs in Coherence. Whole statistics is acceptable from any node and can be exposed via JMX
  * **[TimeSeriesIndex](TimeSeriesIndex.md)** - special purpose index for versioned data in cache

---

### [SpringCacheConfig for Coherence](SpringCacheConfig.md) ###
Configuring caches in Spring contexts instead of cache-config.xml.

---

### tx-lite transaction framework ###
Transaction framework tailored for read mostly / batch updates application. tx-lite is an alternative of Coherence 3.6 transaction, see [data sheet](http://gridkit.googlecode.com/svn-history/r941/wiki/attachments/TxLite.pdf) to feel difference.