package org.infinispan.configuration.serializing;

import static org.infinispan.configuration.parsing.Attribute.BIAS_ACQUISITION;
import static org.infinispan.configuration.parsing.Attribute.BIAS_LIFESPAN;
import static org.infinispan.configuration.parsing.Attribute.INVALIDATION_BATCH_SIZE;
import static org.infinispan.configuration.serializing.SerializeUtils.writeOptional;
import static org.infinispan.configuration.serializing.SerializeUtils.writeTypedProperties;
import static org.infinispan.util.logging.Log.CONFIG;

import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ThreadFactory;

import org.infinispan.commons.configuration.ConfigurationFor;
import org.infinispan.commons.configuration.attributes.AttributeSet;
import org.infinispan.commons.configuration.io.ConfigurationWriter;
import org.infinispan.commons.dataconversion.MediaType;
import org.infinispan.commons.executors.BlockingThreadPoolExecutorFactory;
import org.infinispan.commons.executors.CachedThreadPoolExecutorFactory;
import org.infinispan.commons.executors.ScheduledThreadPoolExecutorFactory;
import org.infinispan.commons.executors.ThreadPoolExecutorFactory;
import org.infinispan.commons.marshall.AdvancedExternalizer;
import org.infinispan.commons.util.TypedProperties;
import org.infinispan.commons.util.Util;
import org.infinispan.configuration.cache.AbstractStoreConfiguration;
import org.infinispan.configuration.cache.AuthorizationConfiguration;
import org.infinispan.configuration.cache.BackupConfiguration;
import org.infinispan.configuration.cache.ClusterLoaderConfiguration;
import org.infinispan.configuration.cache.ClusteringConfiguration;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.cache.CustomInterceptorsConfiguration;
import org.infinispan.configuration.cache.CustomStoreConfiguration;
import org.infinispan.configuration.cache.GroupsConfiguration;
import org.infinispan.configuration.cache.IndexMergeConfiguration;
import org.infinispan.configuration.cache.IndexWriterConfiguration;
import org.infinispan.configuration.cache.IndexingConfiguration;
import org.infinispan.configuration.cache.InterceptorConfiguration;
import org.infinispan.configuration.cache.MemoryConfiguration;
import org.infinispan.configuration.cache.PartitionHandlingConfiguration;
import org.infinispan.configuration.cache.PersistenceConfiguration;
import org.infinispan.configuration.cache.RecoveryConfiguration;
import org.infinispan.configuration.cache.SingleFileStoreConfiguration;
import org.infinispan.configuration.cache.SitesConfiguration;
import org.infinispan.configuration.cache.StatisticsConfiguration;
import org.infinispan.configuration.cache.StoreConfiguration;
import org.infinispan.configuration.cache.TransactionConfiguration;
import org.infinispan.configuration.global.AllowListConfiguration;
import org.infinispan.configuration.global.GlobalAuthorizationConfiguration;
import org.infinispan.configuration.global.GlobalConfiguration;
import org.infinispan.configuration.global.GlobalJmxConfiguration;
import org.infinispan.configuration.global.GlobalMetricsConfiguration;
import org.infinispan.configuration.global.GlobalStateConfiguration;
import org.infinispan.configuration.global.GlobalStatePathConfiguration;
import org.infinispan.configuration.global.SerializationConfiguration;
import org.infinispan.configuration.global.ShutdownHookBehavior;
import org.infinispan.configuration.global.TemporaryGlobalStatePathConfiguration;
import org.infinispan.configuration.global.ThreadPoolConfiguration;
import org.infinispan.configuration.global.TransportConfiguration;
import org.infinispan.configuration.parsing.Attribute;
import org.infinispan.configuration.parsing.CacheParser;
import org.infinispan.configuration.parsing.Element;
import org.infinispan.conflict.EntryMergePolicy;
import org.infinispan.conflict.MergePolicy;
import org.infinispan.distribution.group.Grouper;
import org.infinispan.factories.threads.DefaultThreadFactory;
import org.infinispan.factories.threads.EnhancedQueueExecutorFactory;
import org.infinispan.factories.threads.NonBlockingThreadPoolExecutorFactory;
import org.infinispan.persistence.sifs.configuration.DataConfiguration;
import org.infinispan.persistence.sifs.configuration.IndexConfiguration;
import org.infinispan.persistence.sifs.configuration.SoftIndexFileStoreConfiguration;
import org.infinispan.protostream.SerializationContextInitializer;
import org.infinispan.remoting.transport.jgroups.EmbeddedJGroupsChannelConfigurator;
import org.infinispan.remoting.transport.jgroups.FileJGroupsChannelConfigurator;
import org.infinispan.remoting.transport.jgroups.JGroupsTransport;
import org.infinispan.security.PrincipalRoleMapper;
import org.infinispan.security.Role;
import org.infinispan.security.mappers.ClusterRoleMapper;
import org.infinispan.security.mappers.CommonNameRoleMapper;
import org.infinispan.security.mappers.IdentityRoleMapper;
import org.jgroups.conf.ProtocolConfiguration;
import org.jgroups.conf.ProtocolStackConfigurator;

/**
 * Serializes an Infinispan configuration to an {@link ConfigurationWriter}
 *
 * @author Tristan Tarrant
 * @since 9.0
 */
public class Serializer extends AbstractStoreSerializer implements ConfigurationSerializer<ConfigurationHolder> {
   private static final Map<String, Element> THREAD_POOL_FACTORIES;

   static {
      THREAD_POOL_FACTORIES = new ConcurrentHashMap<>();
      THREAD_POOL_FACTORIES.put(CachedThreadPoolExecutorFactory.class.getName(), Element.CACHED_THREAD_POOL);
      THREAD_POOL_FACTORIES.put(NonBlockingThreadPoolExecutorFactory.class.getName(), Element.BLOCKING_BOUNDED_QUEUE_THREAD_POOL);
      THREAD_POOL_FACTORIES.put(EnhancedQueueExecutorFactory.class.getName(), Element.BLOCKING_BOUNDED_QUEUE_THREAD_POOL);
      THREAD_POOL_FACTORIES.put(ScheduledThreadPoolExecutorFactory.class.getName(), Element.SCHEDULED_THREAD_POOL);
   }

   @Override
   public void serialize(ConfigurationWriter writer, ConfigurationHolder holder) {
      GlobalConfiguration globalConfiguration = holder.getGlobalConfiguration();
      if (globalConfiguration != null) {
         writeJGroups(writer, globalConfiguration);
         writeThreads(writer, globalConfiguration);
      }
      writeCacheContainer(writer, holder);
   }

   private void writeJGroups(ConfigurationWriter writer, GlobalConfiguration globalConfiguration) {
      if (globalConfiguration.isClustered()) {
         writer.writeStartElement(Element.JGROUPS);
         writer.writeAttribute(Attribute.TRANSPORT, globalConfiguration.transport().transport().getClass().getName());
         TypedProperties properties = globalConfiguration.transport().properties();
         for (Object oProperty : properties.keySet()) {
            String property = oProperty.toString();
            if (JGroupsTransport.CHANNEL_CONFIGURATOR.equals(property)) {
               ProtocolStackConfigurator configurator = (ProtocolStackConfigurator) properties.get(property);

               if (configurator.getClass() == FileJGroupsChannelConfigurator.class) {
                  FileJGroupsChannelConfigurator fileConfigurator = (FileJGroupsChannelConfigurator) configurator;
                  writer.writeStartElement(Element.STACK_FILE);
                  writer.writeAttribute(Attribute.NAME, fileConfigurator.getName());
                  writer.writeAttribute(Attribute.PATH, fileConfigurator.getPath());
                  writer.writeEndElement();
               } else if (configurator instanceof EmbeddedJGroupsChannelConfigurator) {
                  EmbeddedJGroupsChannelConfigurator embeddedConfigurator = (EmbeddedJGroupsChannelConfigurator) configurator;
                  writer.writeStartElement(Element.STACK);
                  writer.writeAttribute(Attribute.NAME, embeddedConfigurator.getName());
                  for (ProtocolConfiguration protocol : embeddedConfigurator.getProtocolStack()) {
                     writer.writeStartElement(protocol.getProtocolName());
                     for (Entry<String, String> attr : protocol.getProperties().entrySet()) {
                        writer.writeAttribute(attr.getKey(), attr.getValue());
                     }
                     writer.writeEndElement();
                  }
                  writer.writeEndElement();
               }
            }
         }
         writer.writeEndElement();
      }
   }

   private void writeThreads(ConfigurationWriter writer, GlobalConfiguration globalConfiguration) {
      ConcurrentMap<String, DefaultThreadFactory> threadFactories = new ConcurrentHashMap<>();
      for (ThreadPoolConfiguration threadPoolConfiguration : Arrays.asList(globalConfiguration.expirationThreadPool(), globalConfiguration.listenerThreadPool(),
            globalConfiguration.nonBlockingThreadPool(), globalConfiguration.blockingThreadPool(),
            globalConfiguration.transport().remoteCommandThreadPool(), globalConfiguration.transport().transportThreadPool())) {
         ThreadFactory threadFactory = threadPoolConfiguration.threadFactory();
         if (threadFactory instanceof DefaultThreadFactory) {
            DefaultThreadFactory tf = (DefaultThreadFactory) threadFactory;
            threadFactories.putIfAbsent(tf.getName(), tf);
         }
      }
      if (threadFactories.size() > 0) {
         writer.writeStartElement(Element.THREADS);

         writer.writeStartListElement(Element.THREAD_FACTORIES, false);
         for (DefaultThreadFactory threadFactory : threadFactories.values()) {
            writeThreadFactory(writer, threadFactory);
         }
         writer.writeEndListElement();
         writer.writeStartListElement(Element.THREAD_POOLS, false);
         writeThreadPool(writer, globalConfiguration.nonBlockingThreadPoolName(), globalConfiguration.nonBlockingThreadPool());
         writeThreadPool(writer, globalConfiguration.expirationThreadPoolName(), globalConfiguration.expirationThreadPool());
         writeThreadPool(writer, globalConfiguration.listenerThreadPoolName(), globalConfiguration.listenerThreadPool());
         writeThreadPool(writer, globalConfiguration.blockingThreadPoolName(), globalConfiguration.blockingThreadPool());
         writeThreadPool(writer, globalConfiguration.transport().remoteThreadPoolName(), globalConfiguration.transport().remoteCommandThreadPool());
         writer.writeEndListElement();
         writer.writeEndElement();
      }
   }

   private void writeThreadFactory(ConfigurationWriter writer, DefaultThreadFactory threadFactory) {
      writer.writeStartElement(Element.THREAD_FACTORY);
      writeOptional(writer, Attribute.NAME, threadFactory.getName());
      writeOptional(writer, Attribute.GROUP_NAME, threadFactory.threadGroup().getName());
      writeOptional(writer, Attribute.THREAD_NAME_PATTERN, threadFactory.threadNamePattern());
      writer.writeAttribute(Attribute.PRIORITY, Integer.toString(threadFactory.initialPriority()));
      writer.writeEndElement();
   }

   private void writeThreadPool(ConfigurationWriter writer, String name, ThreadPoolConfiguration threadPoolConfiguration) {
      ThreadPoolExecutorFactory<?> threadPoolFactory = threadPoolConfiguration.threadPoolFactory();
      if (threadPoolFactory != null) {
         Element element = THREAD_POOL_FACTORIES.get(threadPoolFactory.getClass().getName());
         if (element == Element.BLOCKING_BOUNDED_QUEUE_THREAD_POOL && threadPoolFactory.createsNonBlockingThreads()) {
            element = Element.NON_BLOCKING_BOUNDED_QUEUE_THREAD_POOL;
         }
         writer.writeStartElement(element);
         writer.writeAttribute(Attribute.NAME, name);
         ThreadFactory threadFactory = threadPoolConfiguration.threadFactory();
         if (threadFactory instanceof DefaultThreadFactory) {
            DefaultThreadFactory tf = (DefaultThreadFactory) threadFactory;
            writer.writeAttribute(Attribute.THREAD_FACTORY, tf.getName());
         }
         if (threadPoolFactory instanceof BlockingThreadPoolExecutorFactory) {
            BlockingThreadPoolExecutorFactory pool = (BlockingThreadPoolExecutorFactory) threadPoolFactory;
            writer.writeAttribute(Attribute.MAX_THREADS, Integer.toString(pool.maxThreads()));
            writer.writeAttribute(Attribute.CORE_THREADS, Integer.toString(pool.coreThreads()));
            writer.writeAttribute(Attribute.QUEUE_LENGTH, Integer.toString(pool.queueLength()));
            writer.writeAttribute(Attribute.KEEP_ALIVE_TIME, Long.toString(pool.keepAlive()));
         }
         writer.writeEndElement();
      }
   }

   private void writeCacheContainer(ConfigurationWriter writer, ConfigurationHolder holder) {
      writer.writeStartElement(Element.CACHE_CONTAINER);
      GlobalConfiguration globalConfiguration = holder.getGlobalConfiguration();
      if (globalConfiguration != null) {
         writer.writeAttribute(Attribute.NAME, globalConfiguration.cacheManagerName());
         if (globalConfiguration.shutdown().hookBehavior() != ShutdownHookBehavior.DEFAULT) {
            writer.writeAttribute(Attribute.SHUTDOWN_HOOK, globalConfiguration.shutdown().hookBehavior().name());
         }
         writer.writeAttribute(Attribute.STATISTICS, String.valueOf(globalConfiguration.statistics()));

         if (globalConfiguration.nonBlockingThreadPool().threadFactory() != null) {
            writer.writeAttribute(Attribute.NON_BLOCKING_EXECUTOR, globalConfiguration.nonBlockingThreadPoolName());
         }
         if (globalConfiguration.expirationThreadPool().threadFactory() != null) {
            writer.writeAttribute(Attribute.EXPIRATION_EXECUTOR, globalConfiguration.expirationThreadPoolName());
         }
         if (globalConfiguration.listenerThreadPool().threadFactory() != null) {
            writer.writeAttribute(Attribute.LISTENER_EXECUTOR, globalConfiguration.listenerThreadPoolName());
         }
         if (globalConfiguration.blockingThreadPool().threadFactory() != null) {
            writer.writeAttribute(Attribute.BLOCKING_EXECUTOR, globalConfiguration.blockingThreadPoolName());
         }
         writeTransport(writer, globalConfiguration);
         writeSecurity(writer, globalConfiguration);
         writeSerialization(writer, globalConfiguration);
         writeMetrics(writer, globalConfiguration);
         writeJMX(writer, globalConfiguration);
         writeGlobalState(writer, globalConfiguration);
         writeExtraConfiguration(writer, globalConfiguration.modules());
      }
      writer.writeStartListElement(Element.CACHES, false);
      for (Entry<String, Configuration> configuration : holder.getConfigurations().entrySet()) {
         Configuration config = configuration.getValue();
         switch (config.clustering().cacheMode()) {
            case LOCAL:
               writeLocalCache(writer, configuration.getKey(), config);
               break;
            case DIST_ASYNC:
            case DIST_SYNC:
               writeDistributedCache(writer, configuration.getKey(), config);
               break;
            case INVALIDATION_ASYNC:
            case INVALIDATION_SYNC:
               writeInvalidationCache(writer, configuration.getKey(), config);
               break;
            case REPL_ASYNC:
            case REPL_SYNC:
               writeReplicatedCache(writer, configuration.getKey(), config);
               break;
            case SCATTERED_SYNC:
               writeScatteredCache(writer, configuration.getKey(), config);
               break;
            default:
               break;
         }
      }
      writer.writeEndListElement(); // CACHES
      writer.writeEndElement(); // CACHE-CONTAINER
   }

   private void writeExtraConfiguration(ConfigurationWriter writer, Map<Class<?>, ?> modules) {
      for (Entry<Class<?>, ?> entry : modules.entrySet()) {
         SerializedWith serializedWith = entry.getKey().getAnnotation(SerializedWith.class);
         if (serializedWith == null) {
            continue;
         }
         try {
            ConfigurationSerializer<Object> serializer = Util.getInstanceStrict(serializedWith.value());
            serializer.serialize(writer, entry.getValue());
         } catch (InstantiationException | IllegalAccessException | NoSuchMethodException | InvocationTargetException e) {
            throw CONFIG.unableToInstantiateSerializer(serializedWith.value());
         }
      }
   }

   private void writeGlobalState(ConfigurationWriter writer, GlobalConfiguration globalConfiguration) {
      GlobalStateConfiguration configuration = globalConfiguration.globalState();
      if (configuration.enabled()) {
         writer.writeStartElement(Element.GLOBAL_STATE);

         if (configuration.persistenceConfiguration().attributes().attribute(GlobalStatePathConfiguration.PATH).isModified()) {
            writer.writeStartElement(Element.PERSISTENT_LOCATION);
            writer.writeAttribute(Attribute.PATH, configuration.persistentLocation());
            writer.writeEndElement();
         }
         if (configuration.sharedPersistenceConfiguration().attributes().attribute(GlobalStatePathConfiguration.PATH).isModified()) {
            writer.writeStartElement(Element.SHARED_PERSISTENT_LOCATION);
            writer.writeAttribute(Attribute.PATH, configuration.sharedPersistentLocation());
            writer.writeEndElement();
         }
         if (configuration.temporaryLocationConfiguration().attributes().attribute(TemporaryGlobalStatePathConfiguration.PATH).isModified()) {
            writer.writeStartElement(Element.TEMPORARY_LOCATION);
            writer.writeAttribute(Attribute.PATH, configuration.temporaryLocation());
            writer.writeEndElement();
         }
         switch (configuration.configurationStorage()) {
            case IMMUTABLE:
               writer.writeEmptyElement(Element.IMMUTABLE_CONFIGURATION_STORAGE);
               break;
            case VOLATILE:
               writer.writeEmptyElement(Element.VOLATILE_CONFIGURATION_STORAGE);
               break;
            case OVERLAY:
               writer.writeEmptyElement(Element.OVERLAY_CONFIGURATION_STORAGE);
               break;
            case MANAGED:
               writer.writeEmptyElement(Element.MANAGED_CONFIGURATION_STORAGE);
               break;
            case CUSTOM:
               writer.writeStartElement(Element.CUSTOM_CONFIGURATION_STORAGE);
               writer.writeAttribute(Attribute.CLASS, configuration.configurationStorageClass().get().getClass().getName());
               writer.writeEndElement();
               break;
         }

         writer.writeEndElement();
      }
   }

   private void writeSecurity(ConfigurationWriter writer, GlobalConfiguration configuration) {
      GlobalAuthorizationConfiguration authorization = configuration.security().authorization();
      AttributeSet attributes = authorization.attributes();
      if (attributes.isModified() && authorization.enabled()) {
         writer.writeStartElement(Element.SECURITY);
         writer.writeStartElement(Element.AUTHORIZATION);
         attributes.write(writer, GlobalAuthorizationConfiguration.AUDIT_LOGGER, Attribute.AUDIT_LOGGER);
         PrincipalRoleMapper mapper = authorization.principalRoleMapper();
         if (mapper != null) {
            if (mapper instanceof IdentityRoleMapper) {
               writer.writeEmptyElement(Element.IDENTITY_ROLE_MAPPER);
            } else if (mapper instanceof CommonNameRoleMapper) {
               writer.writeEmptyElement(Element.COMMON_NAME_ROLE_MAPPER);
            } else if (mapper instanceof ClusterRoleMapper) {
               writer.writeEmptyElement(Element.CLUSTER_ROLE_MAPPER);
            } else {
               writer.writeStartElement(Element.CUSTOM_ROLE_MAPPER);
               writer.writeAttribute(Attribute.CLASS, mapper.getClass().getName());
               writer.writeEndElement();
            }
         }
         writer.writeStartListElement(Element.ROLES, false);
         for (Role role : authorization.roles().values()) {
            writer.writeStartElement(Element.ROLE);
            writer.writeAttribute(Attribute.NAME, role.getName());
            writeCollectionAsAttribute(writer, Attribute.PERMISSIONS, role.getPermissions());
            writer.writeEndElement();
         }
         writer.writeEndListElement();
         writer.writeEndElement();
         writer.writeEndElement();
      }
   }

   private void writeReplicatedCache(ConfigurationWriter writer, String name, Configuration configuration) {
      writer.writeStartElement(Element.REPLICATED_CACHE);
      writeCommonClusteredCacheAttributes(writer, configuration);
      writeCommonCacheAttributesElements(writer, name, configuration);
      writeExtraConfiguration(writer, configuration.modules());
      writer.writeEndElement();
   }

   private void writeDistributedCache(ConfigurationWriter writer, String name, Configuration configuration) {
      writer.writeStartElement(Element.DISTRIBUTED_CACHE);
      configuration.clustering().hash().attributes().write(writer);
      configuration.clustering().l1().attributes().write(writer);
      writeCommonClusteredCacheAttributes(writer, configuration);
      writeCommonCacheAttributesElements(writer, name, configuration);
      GroupsConfiguration groups = configuration.clustering().hash().groups();
      if (groups.attributes().isModified()) {
         writer.writeStartElement(Element.GROUPS);
         groups.attributes().write(writer, GroupsConfiguration.ENABLED);
         for (Grouper<?> grouper : groups.groupers()) {
            writer.writeStartElement(Element.GROUPER);
            writer.writeAttribute(Attribute.CLASS, grouper.getClass().getName());
            writer.writeEndElement();
         }
         writer.writeEndElement();
      }
      writeExtraConfiguration(writer, configuration.modules());
      writer.writeEndElement();
   }

   private void writeInvalidationCache(ConfigurationWriter writer, String name, Configuration configuration) {
      writer.writeStartElement(Element.INVALIDATION_CACHE);
      writeCommonClusteredCacheAttributes(writer, configuration);
      writeCommonCacheAttributesElements(writer, name, configuration);
      writeExtraConfiguration(writer, configuration.modules());
      writer.writeEndElement();
   }

   private void writeScatteredCache(ConfigurationWriter writer, String name, Configuration configuration) {
      writer.writeStartElement(Element.SCATTERED_CACHE);
      writer.writeAttribute(INVALIDATION_BATCH_SIZE, Integer.toString(configuration.clustering().invalidationBatchSize()));
      writer.writeAttribute(BIAS_ACQUISITION, configuration.clustering().biasAcquisition().toString());
      writer.writeAttribute(BIAS_LIFESPAN, Long.toString(configuration.clustering().biasLifespan()));
      writeCommonClusteredCacheAttributes(writer, configuration);
      writeCommonCacheAttributesElements(writer, name, configuration);
      writeExtraConfiguration(writer, configuration.modules());
      writer.writeEndElement();
   }

   private void writeLocalCache(ConfigurationWriter writer, String name, Configuration configuration) {
      writer.writeStartElement(Element.LOCAL_CACHE);
      if (configuration.simpleCache()) {
         configuration.attributes().write(writer, Configuration.SIMPLE_CACHE, Attribute.SIMPLE_CACHE);
      }
      writeCommonCacheAttributesElements(writer, name, configuration);
      writeExtraConfiguration(writer, configuration.modules());
      writer.writeEndElement();
   }

   private void writeTransport(ConfigurationWriter writer, GlobalConfiguration globalConfiguration) {
      TransportConfiguration transport = globalConfiguration.transport();
      AttributeSet attributes = transport.attributes();
      if (attributes.isModified()) {
         writer.writeStartElement(Element.TRANSPORT);
         attributes.write(writer, TransportConfiguration.CLUSTER_NAME, Attribute.CLUSTER);
         attributes.write(writer, TransportConfiguration.MACHINE_ID, Attribute.MACHINE_ID);
         attributes.write(writer, TransportConfiguration.RACK_ID, Attribute.RACK_ID);
         if (transport.siteId() != null) {
            attributes.write(writer, TransportConfiguration.SITE_ID, Attribute.SITE);
         }
         attributes.write(writer, TransportConfiguration.NODE_NAME, Attribute.NODE_NAME);
         TypedProperties properties = globalConfiguration.transport().properties();
         if (properties.containsKey("stack")) {
            writer.writeAttribute(Attribute.STACK, properties.getProperty("stack"));
         }
         if (transport.remoteCommandThreadPool().threadPoolFactory() != null) {
            writer.writeAttribute(Attribute.REMOTE_COMMAND_EXECUTOR, transport.remoteThreadPoolName());
         }
         attributes.write(writer, TransportConfiguration.DISTRIBUTED_SYNC_TIMEOUT, Attribute.LOCK_TIMEOUT);
         attributes.write(writer, TransportConfiguration.INITIAL_CLUSTER_SIZE, Attribute.INITIAL_CLUSTER_SIZE);
         attributes.write(writer, TransportConfiguration.INITIAL_CLUSTER_TIMEOUT, Attribute.INITIAL_CLUSTER_TIMEOUT);
         writer.writeEndElement();
      }
   }

   private void writeSerialization(ConfigurationWriter writer, GlobalConfiguration globalConfiguration) {
      SerializationConfiguration serialization = globalConfiguration.serialization();
      AttributeSet attributes = serialization.attributes();
      if (attributes.isModified()) {
         writer.writeStartElement(Element.SERIALIZATION);
         attributes.write(writer, SerializationConfiguration.MARSHALLER, Attribute.MARSHALLER_CLASS);
         SerializationConfiguration config = globalConfiguration.serialization();
         writeAdvancedSerializers(writer, config);
         writeSerializationContextInitializers(writer, config);
         writeClassAllowList(writer, config.allowList());
         writer.writeEndElement();
      }
   }

   private void writeAdvancedSerializers(ConfigurationWriter writer, SerializationConfiguration config) {
      Map<Integer, AdvancedExternalizer<?>> externalizers = config.advancedExternalizers();
      boolean userExternalizerExists = externalizers.entrySet().stream().anyMatch(entry -> entry.getKey() >= AdvancedExternalizer.USER_EXT_ID_MIN);
      if (userExternalizerExists) {
         writer.writeStartMapElement(Element.ADVANCED_EXTERNALIZERS);
         for (Entry<Integer, AdvancedExternalizer<?>> externalizer : externalizers.entrySet()) {
            writer.writeStartElement(Element.ADVANCED_EXTERNALIZER);
            writer.writeAttribute(Attribute.ID, Integer.toString(externalizer.getKey()));
            writer.writeAttribute(Attribute.CLASS, externalizer.getValue().getClass().getName());
            writer.writeEndElement();
         }
         writer.writeEndMapElement();
      }
   }

   private void writeSerializationContextInitializers(ConfigurationWriter writer, SerializationConfiguration config) {
      List<SerializationContextInitializer> scis = config.contextInitializers();
      if (scis != null) {
         writer.writeStartListElement(Element.SERIALIZATION_CONTEXT_INITIALIZERS, false);
         for (SerializationContextInitializer sci : config.contextInitializers()) {
            writer.writeStartElement(Element.SERIALIZATION_CONTEXT_INITIALIZER);
            writer.writeAttribute(Attribute.CLASS, sci.getClass().getName());
            writer.writeEndElement();
         }
         writer.writeEndListElement();
      }
   }

   private void writeClassAllowList(ConfigurationWriter writer, AllowListConfiguration config) {
      if (!config.getClasses().isEmpty() || !config.getRegexps().isEmpty()) {
         writer.writeStartElement(Element.ALLOW_LIST);
         writeClassAllowListElements(writer, Element.CLASS, config.getClasses());
         writeClassAllowListElements(writer, Element.REGEX, config.getRegexps());
         writer.writeEndElement();
      }
   }

   private void writeClassAllowListElements(ConfigurationWriter writer, Element element, Collection<String> values) {
      if (values.size() > 0) {
         writer.writeStartListElement(element, false);
         for (String value : values) {
            writer.writeStartElement(element);
            writer.writeCharacters(value);
            writer.writeEndElement();
         }
         writer.writeEndListElement();
      }
   }

   private void writeMetrics(ConfigurationWriter writer, GlobalConfiguration globalConfiguration) {
      GlobalMetricsConfiguration metrics = globalConfiguration.metrics();
      AttributeSet attributes = metrics.attributes();
      if (attributes.isModified()) {
         writer.writeStartElement(Element.METRICS);
         attributes.write(writer, GlobalMetricsConfiguration.GAUGES, Attribute.GAUGES);
         attributes.write(writer, GlobalMetricsConfiguration.HISTOGRAMS, Attribute.HISTOGRAMS);
         attributes.write(writer, GlobalMetricsConfiguration.PREFIX, Attribute.PREFIX);
         attributes.write(writer, GlobalMetricsConfiguration.NAMES_AS_TAGS, Attribute.NAMES_AS_TAGS);
         writer.writeEndElement();
      }
   }

   private void writeJMX(ConfigurationWriter writer, GlobalConfiguration globalConfiguration) {
      GlobalJmxConfiguration jmx = globalConfiguration.jmx();
      AttributeSet attributes = jmx.attributes();
      if (attributes.isModified()) {
         writer.writeStartElement(Element.JMX);
         attributes.write(writer, GlobalJmxConfiguration.ENABLED, Attribute.ENABLED);
         attributes.write(writer, GlobalJmxConfiguration.DOMAIN, Attribute.DOMAIN);
         attributes.write(writer, GlobalJmxConfiguration.MBEAN_SERVER_LOOKUP, Attribute.MBEAN_SERVER_LOOKUP);
         writeTypedProperties(writer, attributes.attribute(GlobalJmxConfiguration.PROPERTIES).get());
         writer.writeEndElement();
      }
   }

   private void writeTransaction(ConfigurationWriter writer, Configuration configuration) {
      TransactionConfiguration transaction = configuration.transaction();
      AttributeSet attributes = transaction.attributes();
      if (attributes.isModified()) {
         writer.writeStartElement(Element.TRANSACTION);
         CacheParser.TransactionMode mode = CacheParser.TransactionMode.fromConfiguration(transaction, configuration.invocationBatching().enabled());
         writer.writeAttribute(Attribute.MODE, mode.toString());
         attributes.write(writer);
         if (mode != CacheParser.TransactionMode.NONE) {
            attributes.write(writer, TransactionConfiguration.TRANSACTION_MANAGER_LOOKUP);
         }
         if (transaction.recovery().enabled())
            transaction.recovery().attributes().write(writer, RecoveryConfiguration.RECOVERY_INFO_CACHE_NAME, Attribute.RECOVERY_INFO_CACHE_NAME);
         writer.writeEndElement();
      }
   }

   private void writeSecurity(ConfigurationWriter writer, Configuration configuration) {
      AuthorizationConfiguration authorization = configuration.security().authorization();
      AttributeSet attributes = authorization.attributes();
      if (attributes.isModified()) {
         writer.writeStartElement(Element.SECURITY);
         writer.writeStartElement(Element.AUTHORIZATION);
         attributes.write(writer, AuthorizationConfiguration.ENABLED, Attribute.ENABLED);
         writeCollectionAsAttribute(writer, Attribute.ROLES, authorization.roles());
         writer.writeEndElement();
         writer.writeEndElement();
      }
   }

   private void writeCommonClusteredCacheAttributes(ConfigurationWriter writer, Configuration configuration) {
      ClusteringConfiguration clustering = configuration.clustering();
      writer.writeAttribute(Attribute.MODE, clustering.cacheMode().isSynchronous() ? "SYNC" : "ASYNC");
      clustering.attributes().write(writer, ClusteringConfiguration.REMOTE_TIMEOUT, Attribute.REMOTE_TIMEOUT);
   }

   private void writeCommonCacheAttributesElements(ConfigurationWriter writer, String name, Configuration configuration) {
      writer.writeAttribute(Attribute.NAME, name);
      configuration.statistics().attributes().write(writer, StatisticsConfiguration.ENABLED, Attribute.STATISTICS);
      configuration.unsafe().attributes().write(writer);
      writeBackup(writer, configuration);
      writeEncoding(writer, configuration);
      configuration.sites().backupFor().attributes().write(writer, Element.BACKUP_FOR.getLocalName());
      configuration.locking().attributes().write(writer, Element.LOCKING.getLocalName());
      writeTransaction(writer, configuration);
      configuration.expiration().attributes().write(writer, Element.EXPIRATION.getLocalName());
      writeMemory(writer, configuration);
      writePersistence(writer, configuration);
      writeIndexing(writer, configuration);
      writeCustomInterceptors(writer, configuration);
      writeSecurity(writer, configuration);
      if (configuration.clustering().cacheMode().needsStateTransfer()) {
         configuration.clustering().stateTransfer().attributes().write(writer, Element.STATE_TRANSFER.getLocalName());
      }
      writePartitionHandling(writer, configuration);
   }

   private void writeEncoding(ConfigurationWriter writer, Configuration configuration) {
      MediaType keyDataType = configuration.encoding().keyDataType().mediaType();
      MediaType valueDataType = configuration.encoding().valueDataType().mediaType();
      if (keyDataType != null || valueDataType != null) {
         writer.writeStartElement(Element.ENCODING);
         if (keyDataType != null) {
            writer.writeStartElement(Element.KEY_DATA_TYPE);
            writer.writeAttribute(Attribute.MEDIA_TYPE, keyDataType.toString());
            writer.writeEndElement();
         }
         if (valueDataType != null) {
            writer.writeStartElement(Element.VALUE_DATA_TYPE);
            writer.writeAttribute(Attribute.MEDIA_TYPE, valueDataType.toString());
            writer.writeEndElement();
         }
         writer.writeEndElement();
      }
   }

   private void writePartitionHandling(ConfigurationWriter writer, Configuration configuration) {
      PartitionHandlingConfiguration partitionHandling = configuration.clustering().partitionHandling();
      AttributeSet attributes = partitionHandling.attributes();
      if (attributes.isModified()) {
         writer.writeStartElement(Element.PARTITION_HANDLING);
         attributes.write(writer, PartitionHandlingConfiguration.WHEN_SPLIT, Attribute.WHEN_SPLIT);
         EntryMergePolicy policyImpl = partitionHandling.mergePolicy();
         MergePolicy policy = MergePolicy.fromConfiguration(policyImpl);
         String output = policy == MergePolicy.CUSTOM ? policyImpl.getClass().getName() : policy.toString();
         writer.writeAttribute(Attribute.MERGE_POLICY, output);
         writer.writeEndElement();
      }
   }

   private void writeCustomInterceptors(ConfigurationWriter writer, Configuration configuration) {
      CustomInterceptorsConfiguration customInterceptors = configuration.customInterceptors();
      if (customInterceptors.interceptors().size() > 0) {
         writer.writeStartListElement(Element.CUSTOM_INTERCEPTORS, true);
         for (InterceptorConfiguration interceptor : customInterceptors.interceptors()) {
            AttributeSet attributes = interceptor.attributes();
            if (!attributes.attribute(InterceptorConfiguration.INTERCEPTOR_CLASS).isNull()) {
               writer.writeStartElement(Element.INTERCEPTOR);
               attributes.write(writer, InterceptorConfiguration.INTERCEPTOR_CLASS, Attribute.CLASS);
               attributes.write(writer, InterceptorConfiguration.AFTER, Attribute.AFTER);
               attributes.write(writer, InterceptorConfiguration.BEFORE, Attribute.BEFORE);
               attributes.write(writer, InterceptorConfiguration.INDEX, Attribute.INDEX);
               attributes.write(writer, InterceptorConfiguration.POSITION, Attribute.POSITION);
               writeTypedProperties(writer, interceptor.properties());
               writer.writeEndElement();
            }
         }
         writer.writeEndListElement();
      }
   }

   private void writeMemory(ConfigurationWriter writer, Configuration configuration) {
      MemoryConfiguration memory = configuration.memory();
      AttributeSet attributes = memory.attributes();
      if (attributes.isModified()) {
         writer.writeStartElement(Element.MEMORY);
         attributes.write(writer, MemoryConfiguration.STORAGE, Attribute.STORAGE);
         if (attributes.attribute(MemoryConfiguration.MAX_COUNT).get() > 0) {
            attributes.write(writer, MemoryConfiguration.MAX_COUNT, Attribute.MAX_COUNT);
         } else if (attributes.attribute(MemoryConfiguration.MAX_SIZE).get() != null) {
            attributes.write(writer, MemoryConfiguration.MAX_SIZE, Attribute.MAX_SIZE);
         }
         attributes.write(writer, MemoryConfiguration.WHEN_FULL, Attribute.WHEN_FULL);
         writer.writeEndElement();
      }
   }

   private void writeIndexing(ConfigurationWriter writer, Configuration configuration) {
      IndexingConfiguration indexing = configuration.indexing();
      AttributeSet attributes = indexing.attributes();
      if (attributes.isModified()) {
         writer.writeStartElement(Element.INDEXING);
         attributes.write(writer, IndexingConfiguration.AUTO_CONFIG, Attribute.AUTO_CONFIG);
         attributes.write(writer, IndexingConfiguration.ENABLED, Attribute.ENABLED);
         attributes.write(writer, IndexingConfiguration.STORAGE, Attribute.STORAGE);
         attributes.write(writer, IndexingConfiguration.PATH, Attribute.PATH);
         long refreshInterval = indexing.reader().getRefreshInterval();
         if (refreshInterval != 0) {
            writer.writeStartElement(Element.INDEX_READER);
            writer.writeAttribute(Attribute.REFRESH_INTERVAL, Long.toString(refreshInterval));
            writer.writeEndElement();
         }
         IndexWriterConfiguration indexWriter = indexing.writer();
         IndexMergeConfiguration indexMerge = indexWriter.merge();
         AttributeSet writerAttributes = indexWriter.attributes();
         AttributeSet mergeAttributes = indexMerge.attributes();
         boolean indexWriterModified = writerAttributes.isModified();
         boolean indexMergeModified = mergeAttributes.isModified();
         if (indexWriterModified || indexMergeModified) {
            writer.writeStartElement(Element.INDEX_WRITER);
            writerAttributes.write(writer, IndexWriterConfiguration.INDEX_COMMIT_INTERVAL, Attribute.COMMIT_INTERVAL);
            writerAttributes.write(writer, IndexWriterConfiguration.INDEX_LOW_LEVEL_TRACE, Attribute.LOW_LEVEL_TRACE);
            writerAttributes.write(writer, IndexWriterConfiguration.INDEX_MAX_BUFFERED_ENTRIES, Attribute.MAX_BUFFERED_ENTRIES);
            writerAttributes.write(writer, IndexWriterConfiguration.INDEX_QUEUE_COUNT, Attribute.QUEUE_COUNT);
            writerAttributes.write(writer, IndexWriterConfiguration.INDEX_QUEUE_SIZE, Attribute.QUEUE_SIZE);
            writerAttributes.write(writer, IndexWriterConfiguration.INDEX_THREAD_POOL_SIZE, Attribute.THREAD_POOL_SIZE);
            writerAttributes.write(writer, IndexWriterConfiguration.INDEX_RAM_BUFFER_SIZE, Attribute.RAM_BUFFER_SIZE);
            if (indexMergeModified) {
               writer.writeStartElement(Element.INDEX_MERGE);
               mergeAttributes.write(writer, IndexMergeConfiguration.CALIBRATE_BY_DELETES, Attribute.CALIBRATE_BY_DELETES);
               mergeAttributes.write(writer, IndexMergeConfiguration.FACTOR, Attribute.FACTOR);
               mergeAttributes.write(writer, IndexMergeConfiguration.MAX_ENTRIES, Attribute.MAX_ENTRIES);
               mergeAttributes.write(writer, IndexMergeConfiguration.MIN_SIZE, Attribute.MIN_SIZE);
               mergeAttributes.write(writer, IndexMergeConfiguration.MAX_SIZE, Attribute.MAX_SIZE);
               mergeAttributes.write(writer, IndexMergeConfiguration.MAX_FORCED_SIZE, Attribute.MAX_FORCED_SIZE);
               writer.writeEndElement();
            }
            writer.writeEndElement();
         }
         if (!indexing.indexedEntityTypes().isEmpty()) {
            writer.writeStartListElement(Element.INDEXED_ENTITIES, true);
            for (String indexedEntity : indexing.indexedEntityTypes()) {
               writer.writeStartElement(Element.INDEXED_ENTITY);
               writer.writeCharacters(indexedEntity);
               writer.writeEndElement();
            }
            writer.writeEndListElement();
         }
         if (!indexing.keyTransformers().isEmpty()) {
            writer.writeStartListElement(Element.KEY_TRANSFORMERS, true);
            for (Map.Entry<Class<?>, Class<?>> e : indexing.keyTransformers().entrySet()) {
               writer.writeStartElement(Element.KEY_TRANSFORMER);
               writer.writeAttribute(Attribute.KEY, e.getKey().getName());
               writer.writeAttribute(Attribute.TRANSFORMER, e.getValue().getName());
               writer.writeEndElement();
            }
            writer.writeEndListElement();
         }
         writeTypedProperties(writer, indexing.properties());
         writer.writeEndElement();
      }
   }

   private void writePersistence(ConfigurationWriter writer, Configuration configuration) {
      PersistenceConfiguration persistence = configuration.persistence();
      AttributeSet attributes = persistence.attributes();
      if (attributes.isModified() || persistence.stores().size() > 0) {
         writer.writeStartElement(Element.PERSISTENCE);
         attributes.write(writer, PersistenceConfiguration.PASSIVATION, Attribute.PASSIVATION);
         attributes.write(writer, PersistenceConfiguration.AVAILABILITY_INTERVAL, Attribute.AVAILABILITY_INTERVAL);
         attributes.write(writer, PersistenceConfiguration.CONNECTION_ATTEMPTS, Attribute.CONNECTION_ATTEMPTS);
         attributes.write(writer, PersistenceConfiguration.CONNECTION_INTERVAL, Attribute.CONNECTION_INTERVAL);
         for (StoreConfiguration store : persistence.stores()) {
            writeStore(writer, store);
         }
         writer.writeEndElement();
      }
   }

   private void writeStore(ConfigurationWriter writer, StoreConfiguration configuration) {
      if (configuration instanceof SoftIndexFileStoreConfiguration) {
         writeFileStore(writer, (SoftIndexFileStoreConfiguration) configuration);
      } else if (configuration instanceof SingleFileStoreConfiguration) {
         writeSingleFileStore(writer, (SingleFileStoreConfiguration) configuration);
      } else if (configuration instanceof ClusterLoaderConfiguration) {
         writeClusterLoader(writer, (ClusterLoaderConfiguration) configuration);
      } else if (configuration instanceof CustomStoreConfiguration) {
         writeCustomStore(writer, (CustomStoreConfiguration) configuration);
      } else {
         SerializedWith serializedWith = configuration.getClass().getAnnotation(SerializedWith.class);
         if (serializedWith == null) {
            ConfigurationFor configurationFor = configuration.getClass().getAnnotation(ConfigurationFor.class);
            if (configuration instanceof AbstractStoreConfiguration && configurationFor != null) {
               writer.writeComment("A serializer for the store configuration class " + configuration.getClass().getName() + " was not found. Using custom store mode");
               AbstractStoreConfiguration asc = (AbstractStoreConfiguration) configuration;
               writeGenericStore(writer, configurationFor.value().getName(), asc);
            } else
               throw new UnsupportedOperationException("Cannot serialize store configuration " + configuration.getClass().getName());
         } else {
            ConfigurationSerializer<StoreConfiguration> serializer;
            try {
               serializer = Util.getInstanceStrict(serializedWith.value());
               serializer.serialize(writer, configuration);
            } catch (InstantiationException | NoSuchMethodException | InvocationTargetException | IllegalAccessException e) {
               throw CONFIG.unableToInstantiateSerializer(serializedWith.value());
            }
         }
      }
   }

   private void writeBackup(ConfigurationWriter writer, Configuration configuration) {
      SitesConfiguration sites = configuration.sites();
      if (sites.allBackups().size() > 0) {
         writer.writeStartListElement(Element.BACKUPS, true);
         for (BackupConfiguration backup : sites.allBackups()) {
            writer.writeStartElement(Element.BACKUP);
            backup.attributes().write(writer);
            AttributeSet stateTransfer = backup.stateTransfer().attributes();
            if (stateTransfer.isModified()) {
               writer.writeStartElement(Element.STATE_TRANSFER);
               stateTransfer.write(writer);
               writer.writeEndElement();
            }
            AttributeSet takeOffline = backup.takeOffline().attributes();
            if (takeOffline.isModified()) {
               writer.writeStartElement(Element.TAKE_OFFLINE);
               takeOffline.write(writer);
               writer.writeEndElement();
            }
            writer.writeEndElement();
         }
         writer.writeEndListElement();
      }
   }

   private void writeCollectionAsAttribute(ConfigurationWriter writer, Attribute attribute, Collection<?> collection) {
      if (!collection.isEmpty()) {
         StringBuilder result = new StringBuilder();
         boolean separator = false;
         for (Object item : collection) {
            if (separator)
               result.append(" ");
            result.append(item);
            separator = true;
         }
         writer.writeAttribute(attribute, result.toString());
      }
   }


   private void writeFileStore(ConfigurationWriter writer, SoftIndexFileStoreConfiguration configuration) {
      writer.writeStartElement(Element.FILE_STORE);
      configuration.attributes().write(writer);
      writeCommonStoreSubAttributes(writer, configuration);
      writeDataElement(writer, configuration);
      writeIndexElement(writer, configuration);
      writeCommonStoreElements(writer, configuration);
      writer.writeEndElement();
   }

   private void writeDataElement(ConfigurationWriter writer, SoftIndexFileStoreConfiguration configuration) {
      configuration.data().attributes().write(writer, Element.DATA.getLocalName(),
            DataConfiguration.DATA_LOCATION,
            DataConfiguration.MAX_FILE_SIZE,
            DataConfiguration.SYNC_WRITES);
   }

   private void writeIndexElement(ConfigurationWriter writer, SoftIndexFileStoreConfiguration configuration) {
      configuration.index().attributes().write(writer, Element.INDEX.getLocalName(),
            IndexConfiguration.INDEX_LOCATION,
            IndexConfiguration.INDEX_QUEUE_LENGTH,
            IndexConfiguration.INDEX_SEGMENTS,
            IndexConfiguration.MIN_NODE_SIZE,
            IndexConfiguration.MAX_NODE_SIZE);
   }

   private void writeSingleFileStore(ConfigurationWriter writer, SingleFileStoreConfiguration configuration) {
      writer.writeStartElement(Element.SINGLE_FILE_STORE);
      configuration.attributes().write(writer);
      writeCommonStoreSubAttributes(writer, configuration);
      writeCommonStoreElements(writer, configuration);
      writer.writeEndElement();
   }

   private void writeClusterLoader(ConfigurationWriter writer, ClusterLoaderConfiguration configuration) {
      writer.writeStartElement(Element.CLUSTER_LOADER);
      configuration.attributes().write(writer);
      writeCommonStoreSubAttributes(writer, configuration);
      writeCommonStoreElements(writer, configuration);
      writer.writeEndElement();
   }

   private void writeCustomStore(ConfigurationWriter writer, CustomStoreConfiguration configuration) {
      writer.writeStartElement(Element.STORE);
      configuration.attributes().write(writer);
      writeCommonStoreSubAttributes(writer, configuration);
      writeCommonStoreElements(writer, configuration);
      writer.writeEndElement();
   }

   private void writeGenericStore(ConfigurationWriter writer, String storeClassName, AbstractStoreConfiguration configuration) {
      writer.writeStartElement(Element.STORE);
      writer.writeAttribute(Attribute.CLASS.getLocalName(), storeClassName);
      configuration.attributes().write(writer);
      writeCommonStoreSubAttributes(writer, configuration);
      writeCommonStoreElements(writer, configuration);
      writer.writeEndElement();
   }
}
