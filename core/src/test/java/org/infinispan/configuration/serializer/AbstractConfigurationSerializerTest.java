package org.infinispan.configuration.serializer;

import static org.testng.AssertJUnit.assertEquals;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.URL;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.infinispan.commons.configuration.JsonReader;
import org.infinispan.commons.configuration.JsonWriter;
import org.infinispan.commons.configuration.attributes.Attribute;
import org.infinispan.commons.configuration.attributes.AttributeSet;
import org.infinispan.commons.configuration.io.ConfigurationReader;
import org.infinispan.commons.configuration.io.ConfigurationWriter;
import org.infinispan.commons.configuration.io.NamingStrategy;
import org.infinispan.commons.dataconversion.MediaType;
import org.infinispan.commons.util.FileLookupFactory;
import org.infinispan.configuration.cache.AbstractStoreConfiguration;
import org.infinispan.configuration.cache.BackupConfiguration;
import org.infinispan.configuration.cache.Configuration;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.cache.IndexingConfiguration;
import org.infinispan.configuration.cache.StoreConfiguration;
import org.infinispan.configuration.global.GlobalConfiguration;
import org.infinispan.configuration.parsing.ConfigurationBuilderHolder;
import org.infinispan.configuration.parsing.ParserRegistry;
import org.infinispan.test.AbstractInfinispanTest;
import org.testng.annotations.Test;

@Test(groups = "functional")
public abstract class AbstractConfigurationSerializerTest extends AbstractInfinispanTest {
   @Test(dataProvider = "configurationFiles")
   public void configurationSerializationTest(Path config) throws Exception {
      Properties properties = new Properties();
      properties.put("jboss.server.temp.dir", System.getProperty("java.io.tmpdir"));
      ParserRegistry registry = new ParserRegistry(Thread.currentThread().getContextClassLoader(), false, properties);
      URL url = FileLookupFactory.newInstance().lookupFileLocation(config.toString(), Thread.currentThread().getContextClassLoader());
      ConfigurationBuilderHolder holderBefore = registry.parse(url);
      Map<String, Configuration> configurations = new HashMap<>();
      for (Map.Entry<String, ConfigurationBuilder> configuration : holderBefore.getNamedConfigurationBuilders().entrySet()) {
         configurations.put(configuration.getKey(), configuration.getValue().build());
      }
      ByteArrayOutputStream baos = new ByteArrayOutputStream();
      registry.serialize(baos, holderBefore.getGlobalConfigurationBuilder().build(), configurations);
      ByteArrayInputStream bais = new ByteArrayInputStream(baos.toByteArray());
      ConfigurationBuilderHolder holderAfter = registry.parse(bais, null, MediaType.fromExtension(config.getFileName().toString()));
      GlobalConfiguration globalConfigurationBefore = holderBefore.getGlobalConfigurationBuilder().build();
      GlobalConfiguration globalConfigurationAfter = holderAfter.getGlobalConfigurationBuilder().build();

      assertEquals(globalConfigurationBefore.security().securityCacheTimeout(), globalConfigurationAfter.security().securityCacheTimeout());
      assertEquals(globalConfigurationBefore.security().securityCacheSize(), globalConfigurationAfter.security().securityCacheSize());
      compareAttributeSets("Global", globalConfigurationBefore.globalState().attributes(), globalConfigurationAfter.globalState().attributes(), "localConfigurationStorage");
      compareAttributeSets("Global", globalConfigurationBefore.jmx().attributes(), globalConfigurationAfter.jmx().attributes(), "mbeanServerLookup");
      compareAttributeSets("Global", globalConfigurationBefore.security().authorization().attributes(), globalConfigurationAfter.security().authorization().attributes());
      compareAttributeSets("Global", globalConfigurationBefore.serialization().attributes(), globalConfigurationAfter.serialization().attributes(), "marshaller", "classResolver", "advancedExternalizer", "contextInitializers");
      compareAttributeSets("Global", globalConfigurationBefore.transport().attributes(), globalConfigurationAfter.transport().attributes(), "transport", "properties");
      compareExtraGlobalConfiguration(globalConfigurationBefore, globalConfigurationAfter);

      for (String name : holderBefore.getNamedConfigurationBuilders().keySet()) {
         Configuration configurationBefore = holderBefore.getNamedConfigurationBuilders().get(name).build();
         Configuration configurationAfter = holderAfter.getNamedConfigurationBuilders().get(name).build();

         compareConfigurations(name, configurationBefore, configurationAfter);
      }

      baos = new ByteArrayOutputStream();
      ConfigurationWriter yamlWriter = ConfigurationWriter.to(baos).withType(MediaType.APPLICATION_YAML).build();
      registry.serialize(yamlWriter, holderBefore.getGlobalConfigurationBuilder().build(), configurations);
      yamlWriter.close();

      bais = new ByteArrayInputStream(baos.toByteArray());
      ConfigurationReader yamlReader = ConfigurationReader.from(bais).withProperties(properties).withType(MediaType.APPLICATION_YAML).withNamingStrategy(NamingStrategy.KEBAB_CASE).build();
      ConfigurationBuilderHolder yamlHolder = new ConfigurationBuilderHolder();
      registry.parse(yamlReader, yamlHolder);
      yamlReader.close();
   }

   private void compareConfigurations(String name, Configuration configurationBefore, Configuration configurationAfter) {
      compareAttributeSets(name, configurationBefore.clustering().attributes(), configurationAfter.clustering().attributes());
      assertEquals(name, configurationBefore.memory(), configurationAfter.memory());
      compareAttributeSets(name, configurationBefore.expiration().attributes(), configurationAfter.expiration().attributes());
      compareIndexing(name, configurationBefore.indexing(), configurationAfter.indexing());
      compareAttributeSets(name, configurationBefore.locking().attributes(), configurationAfter.locking().attributes());
      compareAttributeSets(name, configurationBefore.statistics().attributes(), configurationAfter.statistics().attributes());
      compareAttributeSets(name, configurationBefore.sites().attributes(), configurationAfter.sites().attributes());
      compareSites(name, configurationBefore.sites().allBackups(), configurationAfter.sites().allBackups());
      compareAttributeSets(name, configurationBefore.invocationBatching().attributes(), configurationAfter.invocationBatching().attributes());
      compareAttributeSets(name, configurationBefore.customInterceptors().attributes(), configurationAfter.customInterceptors().attributes());
      compareAttributeSets(name, configurationBefore.unsafe().attributes(), configurationAfter.unsafe().attributes());
      compareAttributeSets(name, configurationBefore.persistence().attributes(), configurationAfter.persistence().attributes());
      compareStores(name, configurationBefore.persistence().stores(), configurationAfter.persistence().stores());
      compareAttributeSets(name, configurationBefore.security().authorization().attributes(), configurationAfter.security().authorization().attributes());
      compareAttributeSets(name, configurationBefore.transaction().attributes(), configurationAfter.transaction().attributes(), "transaction-manager-lookup");

      compareExtraConfiguration(name, configurationBefore, configurationAfter);
   }

   private void compareIndexing(String name, IndexingConfiguration before, IndexingConfiguration after) {
      assertEquals(String.format("Indexing attributes for %s mismatch", name), before.attributes(), after.attributes());
      assertEquals(String.format("Indexing reader for %s mismatch", name), before.reader(), after.reader());
      assertEquals(String.format("Indexing writer for %s mismatch", name), before.writer(), after.writer());

   }

   @Test(dataProvider = "configurationFiles")
   public void jsonSerializationTest(Path config) throws Exception {
      JsonWriter jsonWriter = new JsonWriter();
      Properties properties = new Properties();
      properties.put("jboss.server.temp.dir", System.getProperty("java.io.tmpdir"));
      ParserRegistry registry = new ParserRegistry(Thread.currentThread().getContextClassLoader(), false, properties);
      URL url = FileLookupFactory.newInstance().lookupFileLocation(config.toString(), Thread.currentThread().getContextClassLoader());
      ConfigurationBuilderHolder holderBefore = registry.parse(url);
      JsonReader jsonReader = new JsonReader();
      for (Map.Entry<String, ConfigurationBuilder> configuration : holderBefore.getNamedConfigurationBuilders().entrySet()) {
         Configuration confBefore = configuration.getValue().build();
         String json = jsonWriter.toJSON(confBefore);
         ConfigurationBuilder builder = new ConfigurationBuilder();
         jsonReader.readJson(builder, json);
         Configuration confAfter = builder.build();
         compareConfigurations(configuration.getKey(), confBefore, confAfter);
      }
   }

   protected void compareExtraConfiguration(String name, Configuration configurationBefore, Configuration configurationAfter) {
      // Do nothing. Subclasses can override to implement their own specific comparison
   }

   protected void compareExtraGlobalConfiguration(GlobalConfiguration configurationBefore, GlobalConfiguration configurationAfter) {
      // Do nothing. Subclasses can override to implement their own specific comparison
   }

   private void compareStores(String name, List<StoreConfiguration> beforeStores, List<StoreConfiguration> afterStores) {
      assertEquals("Configuration " + name + " stores count mismatch", beforeStores.size(), afterStores.size());
      for (int i = 0; i < beforeStores.size(); i++) {
         StoreConfiguration beforeStore = beforeStores.get(i);
         StoreConfiguration afterStore = afterStores.get(i);

         compareStoreConfiguration(name, beforeStore, afterStore);
      }
   }

   protected void compareStoreConfiguration(String name, StoreConfiguration beforeStore, StoreConfiguration afterStore) {
      if (beforeStore instanceof AbstractStoreConfiguration) {
         AbstractStoreConfiguration beforeASC = (AbstractStoreConfiguration) beforeStore;
         AbstractStoreConfiguration afterASC = (AbstractStoreConfiguration) afterStore;
         compareAttributeSets(name, beforeASC.attributes(), afterASC.attributes());
         compareAttributeSets(name, beforeASC.async().attributes(), afterASC.async().attributes());
      } else {
         throw new IllegalArgumentException("Cannot compare stores of type: " + beforeStore.getClass().getName());
      }
   }

   protected void compareAttributeSets(String name, AttributeSet before, AttributeSet after, String... exclude) {
      if (before != null && after != null) {
         List<String> exclusions = exclude != null ? Arrays.asList(exclude) : Collections.emptyList();
         for (Attribute<?> attribute : before.attributes()) {
            if (!exclusions.contains(attribute.name())) {
               assertEquals("Configuration " + name, attribute, after.attribute(attribute.name()));
            }
         }
      }
   }

   private void compareSites(String name, List<BackupConfiguration> sitesBefore, List<BackupConfiguration> sitesAfter) {
      assertEquals("Configuration " + name + " sites count mismatch", sitesBefore.size(), sitesAfter.size());
      for (int i = 0; i < sitesBefore.size(); i++) {
         BackupConfiguration before = sitesBefore.get(i);
         BackupConfiguration after = sitesAfter.get(i);
         assertEquals("Configuration " + name + " stores class mismatch", before.getClass(), after.getClass());
         compareAttributeSets(name, before.attributes(), after.attributes());
         compareAttributeSets(name, before.takeOffline().attributes(), after.takeOffline().attributes());
         compareAttributeSets(name, before.stateTransfer().attributes(), after.stateTransfer().attributes());
      }
   }
}
