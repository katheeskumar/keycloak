package org.keycloak.testsuite.arquillian;

import java.io.IOException;
import java.io.NotSerializableException;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.net.MalformedURLException;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.stream.Collectors;
import javax.management.Attribute;
import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.IntrospectionException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanServerConnection;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.remote.JMXServiceURL;
import org.apache.commons.lang3.reflect.FieldUtils;
import org.jboss.arquillian.container.spi.Container;
import org.jboss.arquillian.container.spi.ContainerRegistry;
import org.jboss.arquillian.core.api.Instance;
import org.jboss.arquillian.core.api.annotation.Inject;
import org.jboss.arquillian.core.spi.Validate;
import org.jboss.arquillian.test.spi.TestEnricher;
import org.jboss.logging.Logger;
import org.keycloak.connections.infinispan.InfinispanConnectionProvider;
import org.keycloak.testsuite.Retry;
import org.keycloak.testsuite.arquillian.annotation.JmxInfinispanCacheStatistics;
import org.keycloak.testsuite.arquillian.annotation.JmxInfinispanChannelStatistics;
import org.keycloak.testsuite.arquillian.jmx.JmxConnectorRegistry;
import org.keycloak.testsuite.arquillian.undertow.KeycloakOnUndertow;
import org.keycloak.testsuite.crossdc.DC;

/**
 *
 * @author hmlnarik
 */
public class CacheStatisticsControllerEnricher implements TestEnricher {

    private static final Logger LOG = Logger.getLogger(CacheStatisticsControllerEnricher.class);

    @Inject
    private Instance<ContainerRegistry> registry;

    @Inject
    private Instance<JmxConnectorRegistry> jmxConnectorRegistry;

    @Inject
    private Instance<SuiteContext> suiteContext;

    @Override
    public void enrich(Object testCase) {
        Validate.notNull(registry.get(), "registry should not be null");
        Validate.notNull(jmxConnectorRegistry.get(), "jmxConnectorRegistry should not be null");
        Validate.notNull(suiteContext.get(), "suiteContext should not be null");

        for (Field field : FieldUtils.getAllFields(testCase.getClass())) {
            JmxInfinispanCacheStatistics annotation = field.getAnnotation(JmxInfinispanCacheStatistics.class);

            if (annotation == null) {
                continue;
            }

            try {
                FieldUtils.writeField(field, testCase, getInfinispanCacheStatistics(annotation), true);
            } catch (IOException | IllegalAccessException | MalformedObjectNameException e) {
                throw new RuntimeException("Could not set value on field " + field);
            }
        }
    }

    private InfinispanStatistics getInfinispanCacheStatistics(JmxInfinispanCacheStatistics annotation) throws MalformedObjectNameException, IOException, MalformedURLException {
        ObjectName mbeanName = new ObjectName(String.format(
          "%s:type=%s,name=\"%s(%s)\",manager=\"%s\",component=%s",
          annotation.domain().isEmpty() ? getDefaultDomain(annotation.dc().getDcIndex(), annotation.dcNodeIndex()) : InfinispanConnectionProvider.JMX_DOMAIN,
          annotation.type(),
          annotation.cacheName(),
          annotation.cacheMode(),
          annotation.cacheManagerName(),
          annotation.component()
        ));

        InfinispanStatistics value = new InfinispanCacheStatisticsImpl(getJmxServerConnection(annotation), mbeanName);

        if (annotation.domain().isEmpty()) {
            try {
                Retry.execute(() -> value.reset(), 2, 150);
            } catch (RuntimeException ex) {
                if (annotation.dc() != DC.UNDEFINED && annotation.dcNodeIndex() != -1
                   && suiteContext.get().getAuthServerBackendsInfo(annotation.dc().getDcIndex()).get(annotation.dcNodeIndex()).isStarted()) {
                    LOG.warn("Could not reset statistics for " + mbeanName + ". The reason is: \"" + ex.getMessage() + "\"");
                }
            }
        }

        return value;
    }

    private InfinispanStatistics getJGroupsChannelStatistics(JmxInfinispanChannelStatistics annotation) throws MalformedObjectNameException, IOException, MalformedURLException {
        ObjectName mbeanName = new ObjectName(String.format(
          "%s:type=%s,cluster=\"%s\"",
          annotation.domain().isEmpty() ? getDefaultDomain(annotation.dc().getDcIndex(), annotation.dcNodeIndex()) : InfinispanConnectionProvider.JMX_DOMAIN,
          annotation.type(),
          annotation.cluster()
        ));

        InfinispanStatistics value = new InfinispanChannelStatisticsImpl(getJmxServerConnection(annotation), mbeanName);

        if (annotation.domain().isEmpty()) {
            try {
                Retry.execute(() -> value.reset(), 2, 150);
            } catch (RuntimeException ex) {
                if (annotation.dc() != DC.UNDEFINED && annotation.dcNodeIndex() != -1
                   && suiteContext.get().getAuthServerBackendsInfo(annotation.dc().getDcIndex()).get(annotation.dcNodeIndex()).isStarted()) {
                    LOG.warn("Could not reset statistics for " + mbeanName + ". The reason is: \"" + ex.getMessage() + "\"");
                }
            }
        }

        return value;
    }

    @Override
    public Object[] resolve(Method method) {
        Object[] values = new Object[method.getParameterCount()];

        for (int i = 0; i < method.getParameterCount(); i ++) {
            Parameter param = method.getParameters()[i];

            JmxInfinispanCacheStatistics annotation = param.getAnnotation(JmxInfinispanCacheStatistics.class);
            if (annotation != null) try {
                values[i] = getInfinispanCacheStatistics(annotation);
            } catch (IOException | MalformedObjectNameException e) {
                throw new RuntimeException("Could not set value on field " + param);
            }

            JmxInfinispanChannelStatistics channelAnnotation = param.getAnnotation(JmxInfinispanChannelStatistics.class);
            if (channelAnnotation != null) try {
                values[i] = getJGroupsChannelStatistics(channelAnnotation);
            } catch (IOException | MalformedObjectNameException e) {
                throw new RuntimeException("Could not set value on field " + param);
            }
        }

        return values;
    }

    private String getDefaultDomain(int dcIndex, int dcNodeIndex) {
        if (dcIndex != -1 && dcNodeIndex != -1) {
            if (Boolean.parseBoolean(System.getProperty("auth.server.jboss.crossdc"))) {
                //backend-jboss-server
                return "org.wildfly.clustering.infinispan";
            }
            
            //backend-undertow-server
            return InfinispanConnectionProvider.JMX_DOMAIN + "-" + suiteContext.get().getAuthServerBackendsInfo(dcIndex).get(dcNodeIndex).getQualifier();
        }
        
        //cache-server
        return InfinispanConnectionProvider.JMX_DOMAIN;
    }

    private Supplier<MBeanServerConnection> getJmxServerConnection(JmxInfinispanCacheStatistics annotation) throws MalformedURLException {
        final String host;
        final int port;

        if (annotation.dc() != DC.UNDEFINED && annotation.dcNodeIndex() != -1) {
            ContainerInfo node = suiteContext.get().getAuthServerBackendsInfo(annotation.dc().getDcIndex()).get(annotation.dcNodeIndex());
            Container container = node.getArquillianContainer();
            if (container.getDeployableContainer() instanceof KeycloakOnUndertow) {
                return () -> ManagementFactory.getPlatformMBeanServer();
            }
            host = "localhost";
            port = container.getContainerConfiguration().getContainerProperties().containsKey("managementPort")
              ? Integer.valueOf(container.getContainerConfiguration().getContainerProperties().get("managementPort"))
              : 9990;
        } else {
            host = annotation.host().isEmpty()
              ? System.getProperty((annotation.hostProperty().isEmpty()
                ? "keycloak.connectionsInfinispan.remoteStoreServer"
                : annotation.hostProperty()))
              : annotation.host();

            port = annotation.managementPort() == -1
              ? Integer.valueOf(System.getProperty((annotation.managementPortProperty().isEmpty()
                ? "cache.server.management.port"
                : annotation.managementPortProperty())))
              : annotation.managementPort();
        }


        JMXServiceURL url = new JMXServiceURL("service:jmx:remote+http://" + host + ":" + port);
        return () -> {
            try {
                return jmxConnectorRegistry.get().getConnection(url).getMBeanServerConnection();
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        };
    }

    private Supplier<MBeanServerConnection> getJmxServerConnection(JmxInfinispanChannelStatistics annotation) throws MalformedURLException {
        final String host;
        final int port;

        if (annotation.dc() != DC.UNDEFINED && annotation.dcNodeIndex() != -1) {
            ContainerInfo node = suiteContext.get().getAuthServerBackendsInfo(annotation.dc().getDcIndex()).get(annotation.dcNodeIndex());
            Container container = node.getArquillianContainer();
            if (container.getDeployableContainer() instanceof KeycloakOnUndertow) {
                return () -> ManagementFactory.getPlatformMBeanServer();
            }
            host = "localhost";
            port = container.getContainerConfiguration().getContainerProperties().containsKey("managementPort")
              ? Integer.valueOf(container.getContainerConfiguration().getContainerProperties().get("managementPort"))
              : 9990;
        } else {
            host = annotation.host().isEmpty()
              ? System.getProperty((annotation.hostProperty().isEmpty()
                ? "keycloak.connectionsInfinispan.remoteStoreServer"
                : annotation.hostProperty()))
              : annotation.host();

            port = annotation.managementPort() == -1
              ? Integer.valueOf(System.getProperty((annotation.managementPortProperty().isEmpty()
                ? "cache.server.management.port"
                : annotation.managementPortProperty())))
              : annotation.managementPort();
        }

        JMXServiceURL url = new JMXServiceURL("service:jmx:remote+http://" + host + ":" + port);
        return () -> {
            try {
                return jmxConnectorRegistry.get().getConnection(url).getMBeanServerConnection();
            } catch (IOException ex) {
                throw new RuntimeException(ex);
            }
        };
    }

    private static abstract class CacheStatisticsImpl implements InfinispanStatistics {

        private final Supplier<MBeanServerConnection> mbscCreateor;
        private final ObjectName mbeanNameTemplate;
        private ObjectName mbeanName;

        public CacheStatisticsImpl(Supplier<MBeanServerConnection> mbscCreateor, ObjectName mbeanNameTemplate) {
            this.mbscCreateor = mbscCreateor;
            this.mbeanNameTemplate = mbeanNameTemplate;
        }

        protected MBeanServerConnection getConnection() {
            return mbscCreateor.get();
        }
        
        @Override
        public boolean exists() {
            try {
                getMbeanName();
                return true;
            } catch (IOException | RuntimeException ex) {
                return false;
            }
        }
        
        @Override
        public Map<String, Object> getStatistics() {
            try {
                MBeanInfo mBeanInfo = getConnection().getMBeanInfo(getMbeanName());
                String[] statAttrs = Arrays.asList(mBeanInfo.getAttributes()).stream()
                  .filter(MBeanAttributeInfo::isReadable)
                  .map(MBeanAttributeInfo::getName)
                  .collect(Collectors.toList())
                  .toArray(new String[] {});
                return getConnection().getAttributes(getMbeanName(), statAttrs)
                  .asList()
                  .stream()
                  .collect(Collectors.toMap(Attribute::getName, Attribute::getValue));
            } catch (IOException | InstanceNotFoundException | ReflectionException | IntrospectionException ex) {
                throw new RuntimeException(ex);
            }
        }

        protected ObjectName getMbeanName() throws IOException, RuntimeException {
            if (this.mbeanName == null) {
                Set<ObjectName> queryNames = getConnection().queryNames(mbeanNameTemplate, null);
                if (queryNames.isEmpty()) {
                    throw new RuntimeException("No MBean of template " + mbeanNameTemplate + " found at JMX server");
                }
                this.mbeanName = queryNames.iterator().next();
            }

            return this.mbeanName;
        }

        @Override
        public Comparable getSingleStatistics(String statisticsName) {
            try {
                return (Comparable) getConnection().getAttribute(getMbeanName(), statisticsName);
            } catch (IOException | InstanceNotFoundException | MBeanException | ReflectionException | AttributeNotFoundException ex) {
                throw new RuntimeException(ex);
            }
        }

        @Override
        public void waitToBecomeAvailable(int time, TimeUnit unit) {
            long timeInMillis = TimeUnit.MILLISECONDS.convert(time, unit);
            Retry.execute(() -> {
                try {
                    getMbeanName();
                    if (! isAvailable()) throw new RuntimeException("Not available");
                } catch (IOException | RuntimeException ex) {
                    throw new RuntimeException("Timed out while waiting for " + mbeanNameTemplate + " to become available", ex);
                }
            }, 1 + (int) timeInMillis / 100, 100);
        }

        protected abstract boolean isAvailable();
    }

    private static class InfinispanCacheStatisticsImpl extends CacheStatisticsImpl {

        public InfinispanCacheStatisticsImpl(Supplier<MBeanServerConnection> mbscCreator, ObjectName mbeanName) {
            super(mbscCreator, mbeanName);
        }

        @Override
        public void reset() {
            try {
                getConnection().invoke(getMbeanName(), "resetStatistics", new Object[] {}, new String[] {});
            } catch (IOException | InstanceNotFoundException | MBeanException | ReflectionException ex) {
                throw new RuntimeException(ex);
            }
        }

        @Override
        protected boolean isAvailable() {
            return getSingleStatistics(Constants.STAT_CACHE_ELAPSED_TIME) != null;
        }
    }

    private static class InfinispanChannelStatisticsImpl extends CacheStatisticsImpl {

        public InfinispanChannelStatisticsImpl(Supplier<MBeanServerConnection> mbscCreator, ObjectName mbeanName) {
            super(mbscCreator, mbeanName);
        }

        @Override
        public void reset() {
            try {
                getConnection().invoke(getMbeanName(), "resetStats", new Object[] {}, new String[] {});
            } catch (NotSerializableException ex) {
                // Ignore return value not serializable, the invocation has already done its job
            } catch (IOException | InstanceNotFoundException | MBeanException | ReflectionException ex) {
                throw new RuntimeException(ex);
            }
        }

        @Override
        protected boolean isAvailable() {
            return Objects.equals(getSingleStatistics(Constants.STAT_CHANNEL_CONNECTED), Boolean.TRUE);
       }
    }
}
