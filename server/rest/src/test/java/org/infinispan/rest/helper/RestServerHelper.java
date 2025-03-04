package org.infinispan.rest.helper;

import java.net.URISyntaxException;
import java.nio.file.Paths;

import org.infinispan.client.rest.RestClient;
import org.infinispan.client.rest.configuration.RestClientConfigurationBuilder;
import org.infinispan.configuration.cache.ConfigurationBuilder;
import org.infinispan.configuration.global.GlobalConfigurationBuilder;
import org.infinispan.manager.EmbeddedCacheManager;
import org.infinispan.registry.InternalCacheRegistry;
import org.infinispan.rest.CertificateTest;
import org.infinispan.rest.RestServer;
import org.infinispan.rest.TestClass;
import org.infinispan.rest.authentication.Authenticator;
import org.infinispan.rest.configuration.RestServerConfiguration;
import org.infinispan.rest.configuration.RestServerConfigurationBuilder;
import org.infinispan.server.core.DummServerManagement;
import org.infinispan.test.fwk.TestCacheManagerFactory;

/**
 * A small utility class which helps managing REST server.
 *
 * @author Sebastian Łaskawiec
 */
public class RestServerHelper {

   public static final String CLIENT_KEY_STORE = CertificateTest.class.getClassLoader().getResource("./client.p12").getPath();
   public static final String SERVER_KEY_STORE = CertificateTest.class.getClassLoader().getResource("./server.p12").getPath();
   public static final char[] STORE_PASSWORD = "secret".toCharArray();
   public static final String STORE_TYPE = "pkcs12";

   private final EmbeddedCacheManager cacheManager;
   private final RestServer restServer = new RestServer();
   private final RestServerConfigurationBuilder restServerConfigurationBuilder = new RestServerConfigurationBuilder();

   public RestServerHelper(EmbeddedCacheManager cacheManager) {
      this.cacheManager = cacheManager;
      try {
         restServerConfigurationBuilder.host("localhost").port(0).maxContentLength(1_000_000)
               .staticResources(Paths.get(this.getClass().getResource("/static-test").toURI()));
      } catch (URISyntaxException ignored) {
      }
   }

   public static RestServerHelper defaultRestServer(String... cachesDefined) {
      return defaultRestServer(new ConfigurationBuilder(), cachesDefined);
   }

   public RestServerHelper withConfiguration(RestServerConfiguration configuration) {
      restServerConfigurationBuilder.read(configuration);
      return this;
   }

   public static RestServerHelper defaultRestServer(ConfigurationBuilder configuration, String... cachesDefined) {
      GlobalConfigurationBuilder globalBuilder = new GlobalConfigurationBuilder().cacheManagerName("default");
      EmbeddedCacheManager cacheManager = TestCacheManagerFactory.createCacheManager(globalBuilder, configuration);
      cacheManager.getClassAllowList().addClasses(TestClass.class);
      for (String cacheConfiguration : cachesDefined) {
         cacheManager.defineConfiguration(cacheConfiguration, configuration.build());
      }

      return new RestServerHelper(cacheManager);
   }

   public RestServerHelper withAuthenticator(Authenticator authenticator) {
      restServerConfigurationBuilder.authentication().authenticator(authenticator);
      return this;
   }

   public RestServerHelper start(String name) {
      restServerConfigurationBuilder.name(name);
      restServer.setServerManagement(new DummServerManagement());
      restServer.start(restServerConfigurationBuilder.build(), cacheManager);
      return this;
   }

   public void clear() {
      InternalCacheRegistry registry = cacheManager.getGlobalComponentRegistry()
            .getComponent(InternalCacheRegistry.class);
      cacheManager.getCacheNames().stream()
            .filter(cacheName -> !registry.isInternalCache(cacheName))
            .filter(cacheManager::isRunning)
            .forEach(cacheName -> cacheManager.getCache(cacheName).getAdvancedCache().getDataContainer().clear());
   }

   public void stop() {
      restServer.stop();
      cacheManager.stop();
   }

   public int getPort() {
      return restServer.getPort();
   }

   public RestServerConfiguration getConfiguration() {
      return restServer.getConfiguration();
   }

   public EmbeddedCacheManager getCacheManager() {
      return cacheManager;
   }

   public String getBasePath() {
      return String.format("/%s/v2/caches/%s", restServer.getConfiguration().contextPath(), cacheManager.getCacheManagerConfiguration().defaultCacheName().get());
   }

   public RestServerHelper withKeyStore(String keyStorePath, char[] secret, String type) {
      restServerConfigurationBuilder.ssl().enable();
      restServerConfigurationBuilder.ssl()
            .keyStoreFileName(keyStorePath)
            .keyStorePassword(secret)
            .keyStoreType(type);
      return this;
   }

   public RestServerHelper withTrustStore(String trustStorePath, char[] secret, String type) {
      restServerConfigurationBuilder.ssl().enable();
      restServerConfigurationBuilder.ssl()
            .trustStoreFileName(trustStorePath)
            .trustStorePassword(secret)
            .trustStoreType(type);
      return this;
   }

   public RestServerHelper withClientAuth() {
      restServerConfigurationBuilder.ssl().enable();
      restServerConfigurationBuilder.ssl().requireClientAuth(true);
      return this;
   }

   public String getHost() {
      return restServer.getHost();
   }

   public void ignoreCache(String cacheName) {
      restServer.getServerStateManager().ignoreCache(cacheName).join();
   }

   public void unignoreCache(String cacheName) {
      restServer.getServerStateManager().unignoreCache(cacheName).join();
   }

   public RestClient createClient() {
      RestClientConfigurationBuilder builder = new RestClientConfigurationBuilder();
      builder.addServer().host(restServer.getHost()).port(restServer.getPort());
      return RestClient.forConfiguration(builder.build());
   }

}
