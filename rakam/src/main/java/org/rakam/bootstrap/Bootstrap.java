package org.rakam.bootstrap;

import com.google.common.annotations.Beta;
import com.google.common.base.Preconditions;
import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSortedMap;
import com.google.common.collect.Maps;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.Stage;
import com.google.inject.spi.Message;
import io.airlift.bootstrap.LifeCycleManager;
import io.airlift.bootstrap.LifeCycleModule;
import io.airlift.configuration.*;
import io.airlift.log.Logger;
import io.airlift.log.Logging;
import io.airlift.log.LoggingConfiguration;
import org.rakam.SystemRegistry;
import org.rakam.plugin.ConditionalModule;
import org.rakam.plugin.RakamModule;

import java.io.PrintWriter;
import java.util.*;
import java.util.stream.Collectors;

import static com.google.common.collect.Maps.fromProperties;

public class Bootstrap
{
    private final Logger log = Logger.get(Bootstrap.class);
    private final List<Module> modules;

    private Map<String, String> requiredConfigurationProperties;
    private Map<String, String> optionalConfigurationProperties;
    private boolean initializeLogging = true;
    private boolean quiet;
    private boolean strictConfig;
    private boolean requireExplicitBindings = true;

    private boolean initialized;

    public Bootstrap(Module... modules)
    {
        this(ImmutableList.copyOf(modules));
    }

    public Bootstrap(Iterable<? extends Module> modules)
    {
        this.modules = ImmutableList.copyOf(modules);
    }

    @Beta
    public Bootstrap setRequiredConfigurationProperty(String key, String value)
    {
        if (this.requiredConfigurationProperties == null) {
            this.requiredConfigurationProperties = new TreeMap<>();
        }
        this.requiredConfigurationProperties.put(key, value);
        return this;
    }

    @Beta
    public Bootstrap setRequiredConfigurationProperties(Map<String, String> requiredConfigurationProperties)
    {
        if (this.requiredConfigurationProperties == null) {
            this.requiredConfigurationProperties = new TreeMap<>();
        }
        this.requiredConfigurationProperties.putAll(requiredConfigurationProperties);
        return this;
    }

    @Beta
    public Bootstrap setOptionalConfigurationProperty(String key, String value)
    {
        if (this.optionalConfigurationProperties == null) {
            this.optionalConfigurationProperties = new TreeMap<>();
        }
        this.optionalConfigurationProperties.put(key, value);
        return this;
    }

    @Beta
    public Bootstrap setOptionalConfigurationProperties(Map<String, String> optionalConfigurationProperties)
    {
        if (this.optionalConfigurationProperties == null) {
            this.optionalConfigurationProperties = new TreeMap<>();
        }
        this.optionalConfigurationProperties.putAll(optionalConfigurationProperties);
        return this;
    }

    @Beta
    public Bootstrap doNotInitializeLogging()
    {
        this.initializeLogging = false;
        return this;
    }

    public Bootstrap quiet()
    {
        this.quiet = true;
        return this;
    }

    public Bootstrap strictConfig()
    {
        this.strictConfig = true;
        return this;
    }

    public Bootstrap requireExplicitBindings(boolean requireExplicitBindings)
    {
        this.requireExplicitBindings = requireExplicitBindings;
        return this;
    }

    public Injector initialize()
            throws Exception
    {
        Preconditions.checkState(!initialized, "Already initialized");
        initialized = true;
        Logging logging = null;
        if(this.initializeLogging) {
            logging = Logging.initialize();
        }

        Thread.currentThread().setUncaughtExceptionHandler((t, e) ->
                log.error(e, "Uncaught exception in thread %s", t.getName()));

        Map<String, String> requiredProperties;
        ConfigurationFactory configurationFactory;
        if (requiredConfigurationProperties == null) {
            // initialize configuration
            log.info("Loading configuration");
            ConfigurationLoader loader = new ConfigurationLoader();

            requiredProperties = Collections.emptyMap();
            String configFile = System.getProperty("config");
            if (configFile != null) {
                requiredProperties = loader.loadPropertiesFrom(configFile);
            }
        }
        else {
            requiredProperties = requiredConfigurationProperties;
        }
        SortedMap<String, String> properties = Maps.newTreeMap();
        if (optionalConfigurationProperties != null) {
            properties.putAll(optionalConfigurationProperties);
        }
        properties.putAll(requiredProperties);
        properties.putAll(fromProperties(System.getProperties()));
        properties = ImmutableSortedMap.copyOf(properties);

        configurationFactory = new ConfigurationFactory(properties);

        if(logging != null) {
            this.log.info("Initializing logging");
            LoggingConfiguration messages1 = configurationFactory.build(LoggingConfiguration.class);
            logging.configure(messages1);
        }

        // create warning logger now that we have logging initialized
        final WarningsMonitor warningsMonitor = message -> log.warn(message);

        ImmutableList.Builder<Module> installedModulesBuilder = ImmutableList.builder();
        // initialize configuration factory
        for (Module module : modules) {
            ConditionalModule annotation = module.getClass().getAnnotation(ConditionalModule.class);
            if(annotation != null) {
                String value = configurationFactory.getProperties().get(annotation.config());
                String annValue = annotation.value();
                configurationFactory.consumeProperty(annotation.config());
                if(!((annValue.isEmpty() && value != null) || annValue.equals(value))) {
                   continue;
                }
            }
            if (module instanceof ConfigurationAwareModule) {
                ConfigurationAwareModule configurationAwareModule = (ConfigurationAwareModule) module;
                configurationAwareModule.setConfigurationFactory(configurationFactory);
            }
            installedModulesBuilder.add(module);
        }
        List<Module> installedModules = installedModulesBuilder.build();

        // Validate configuration
        ConfigurationValidator configurationValidator = new ConfigurationValidator(configurationFactory, warningsMonitor);
        List<Message> messages;
        try {
            messages = configurationValidator.validate(installedModules);
        } catch (Exception e) {
            throw Throwables.propagate(e);
        }

        // at this point all config file properties should be used
        // so we can calculate the unused properties
        final TreeMap<String, String> unusedProperties = Maps.newTreeMap();
        unusedProperties.putAll(requiredProperties);
        unusedProperties.keySet().removeAll(configurationFactory.getUsedProperties());

        // Log effective configuration
        if (!quiet) {
            logConfiguration(configurationFactory, unusedProperties);
        }

        // system modules
        ImmutableList.Builder<Module> moduleList = ImmutableList.builder();
        moduleList.add(new LifeCycleModule());
        moduleList.add(new ConfigurationModule(configurationFactory));
        if (!messages.isEmpty()) {
            moduleList.add(new ValidationErrorModule(messages));
        }
        moduleList.add(binder -> {
            binder.bind(WarningsMonitor.class).toInstance(warningsMonitor);
        });

        moduleList.add(binder -> {
            binder.disableCircularProxies();
            if(requireExplicitBindings) {
                binder.requireExplicitBindings();
            }
        });

        // todo this should be part of the ValidationErrorModule
        if (strictConfig) {
            moduleList.add(binder -> {
                for (Map.Entry<String, String> unusedProperty : unusedProperties.entrySet()) {
                    binder.addError("Configuration property '%s=%s' was not used", unusedProperty.getKey(), unusedProperty.getValue());
                }
            });
        }
        moduleList.add(binder -> {
            SystemRegistry systemRegistry = new SystemRegistry(installedModules.stream()
                    .filter(module -> module instanceof RakamModule)
                    .map(module -> {
                        RakamModule rakamModule = (RakamModule) module;
                        return new SystemRegistry.Module(rakamModule.name(), rakamModule.description(), rakamModule.getClass().getName());
                    }).collect(Collectors.toList()));
            binder.bind(SystemRegistry.class).toInstance(systemRegistry);
        });

        moduleList.addAll(installedModules);

        // create the injector
        Injector injector;
        try {
            injector = Guice.createInjector(Stage.PRODUCTION, moduleList.build());
        } catch (Exception e) {
            throw Throwables.propagate(e);
        }

        // Create the life-cycle manager
        LifeCycleManager lifeCycleManager = injector.getInstance(LifeCycleManager.class);

        // Start services
        if (lifeCycleManager.size() > 0) {
            lifeCycleManager.start();
        }

        return injector;
    }

    private static final String PROPERTY_NAME_COLUMN = "PROPERTY";
    private static final String DEFAULT_VALUE_COLUMN = "DEFAULT";
    private static final String CURRENT_VALUE_COLUMN = "RUNTIME";
    private static final String DESCRIPTION_COLUMN = "DESCRIPTION";

    private void logConfiguration(ConfigurationFactory configurationFactory, Map<String, String> unusedProperties)
    {
        ColumnPrinter columnPrinter = makePrinterForConfiguration(configurationFactory);

        try (PrintWriter out = new PrintWriter(new LoggingWriter(log, LoggingWriter.Type.INFO))) {
            columnPrinter.print(out);
        }

        // Warn about unused properties
        if (!unusedProperties.isEmpty()) {
            log.warn("UNUSED PROPERTIES");
            for (Map.Entry<String, String> unusedProperty : unusedProperties.entrySet()) {
                log.warn("%s=%s", unusedProperty.getKey(), unusedProperty.getValue());
            }
            log.warn("");
        }
    }

    private ColumnPrinter makePrinterForConfiguration(ConfigurationFactory configurationFactory)
    {
        ConfigurationInspector configurationInspector = new ConfigurationInspector();

        ColumnPrinter columnPrinter = new ColumnPrinter();

        columnPrinter.addColumn(PROPERTY_NAME_COLUMN);
        columnPrinter.addColumn(DEFAULT_VALUE_COLUMN);
        columnPrinter.addColumn(CURRENT_VALUE_COLUMN);
        columnPrinter.addColumn(DESCRIPTION_COLUMN);

        for (ConfigurationInspector.ConfigRecord<?> record : configurationInspector.inspect(configurationFactory)) {
            for (ConfigurationInspector.ConfigAttribute attribute : record.getAttributes()) {
                columnPrinter.addValue(PROPERTY_NAME_COLUMN, attribute.getPropertyName());
                columnPrinter.addValue(DEFAULT_VALUE_COLUMN, attribute.getDefaultValue());
                columnPrinter.addValue(CURRENT_VALUE_COLUMN, attribute.getCurrentValue());
                columnPrinter.addValue(DESCRIPTION_COLUMN, attribute.getDescription());
            }
        }
        return columnPrinter;
    }
}
