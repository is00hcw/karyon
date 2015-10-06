package com.netflix.karyon;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.inject.Module;
import com.google.inject.Stage;
import com.netflix.governator.LifecycleInjector;
import com.netflix.karyon.conditional.ConditionalOnProfile;

/**
 * Base entry point for creating a LifecycleInjector with module auto-loading capabilities.  
 * Module auto-loading makes it possible to load bindings that are contextual to the 
 * environment in which the application is running based on things like profiles,
 * properties and existing bindings.  
 * 
 * The LifecycleInjector created here uses a layered approach to construct the Guice Injector
 * so that bindings can be overridden at a high level based on the runtime environment
 * as opposed to sprinkling Modules.overrides and conditionals throughout the modules themselves
 * since using Modules.overrides directly looses the original module's context and can easily result
 * in difficult to debug duplicate binding errors.
 * 
 * This injector is constructed in two phases.  The first bootstrap phase determines which core
 * Guice modules should be installed based on processing of conditional annotations.  The final
 * list of auto discovered modules is appended to the main list of modules and installed on the
 * main injector.  Application level override modules may be applied to this final list from the
 * list of modules returned from {@link KaryonConfiguration.getOverrideModules()}.
 * 
 * <pre>
 * {@code
     Karyon
        .create()
        .addModules(
             new JettyModule(),
             new JerseyServletModule() {
                @Override
                protected void configureServlets() {
                    serve("/*").with(GuiceContainer.class);
                    bind(GuiceContainer.class).asEagerSingleton();
                    
                    bind(HelloWorldApp.class).asEagerSingleton();
                }  
            }
        )
        .start()
        .awaitTermination();
 * }
 * </pre>
 * 
 * @param config
 * @param modules
 * 
 *      +-------------------+
 *      |      Override     |
 *      +-------------------+
 *      |   Auto Override   |
 *      +-------------------+
 *      |    Core + Auto    |
 *      +-------------------+
 *      | Bootstrap Exposed |
 *      +-------------------+
 *      
 * @see ArchaiusKaryon
 * 
 * @author elandau
 *
 * @param <T>
 */
public class Karyon {
    private static final String KARYON_PROFILES = "karyon.profiles";
    
    protected PropertySource              propertySource    = DefaultPropertySource.INSTANCE;
    protected Set<String>                 profiles          = new LinkedHashSet<>();
    protected List<ModuleListProvider>    moduleProviders   = new ArrayList<>();
    protected Map<KaryonFeature, Boolean> features          = new HashMap<>();
    protected Stage                       stage             = Stage.DEVELOPMENT;
    protected List<Module>                modules           = new ArrayList<>();
    protected List<Module>                overrideModules   = new ArrayList<>();

    // This is a hack to make sure that if archaius is used at some point we make use
    // of the bridge so any access to the legacy Archaius1 API is actually backed by 
    // the Archaius2 implementation
    static {
        System.setProperty("archaius.default.configuration.class",      "com.netflix.archaius.bridge.StaticAbstractConfiguration");
        System.setProperty("archaius.default.deploymentContext.class",  "com.netflix.archaius.bridge.StaticDeploymentContext");
    }
    
    /**
     * Add main Guice modules to your application
     * @param modules
     * @return
     */
    public Karyon addModules(Module ... modules) {
        if (modules != null) {
            this.modules.addAll(Arrays.asList(modules));
        }
        return this;
    }
    
    /**
     * Add main Guice modules to your application
     * @param modules
     * @return
     */
    public Karyon addModules(List<Module> modules) {
        if (modules != null) {
            this.modules.addAll(modules);
        }
        return this;
    }
    
    /**
     * Add override modules for any modules add via addModules or that are 
     * conditionally loaded.  This is useful for testing or when an application
     * absolutely needs to override a binding to fix a binding problem in the
     * code modules
     * @param modules
     * @return
     */
    public Karyon addOverrideModules(Module ... modules) {
        if (modules != null) {
            this.overrideModules.addAll(Arrays.asList(modules));
        }
        return this;
    }
    
    /**
     * Add override modules for any modules add via addModules or that are 
     * conditionally loaded.  This is useful for testing or when an application
     * absolutely needs to override a binding to fix a binding problem in the
     * code modules
     * @param modules
     * @return
     */
    public Karyon addOverrideModules(List<Module> modules) {
        if (modules != null) {
            this.overrideModules.addAll(modules);
        }
        return this;
    }

    /**
     * Specify the Guice stage in which the application is running.  By default Karyon
     * runs in Stage.DEVELOPMENT to achieve default lazy singleton behavior. 
     * @param stage
     * @return
     */
    public Karyon inStage(Stage stage) {
        this.stage = stage;
        return this;
    }
    
    /**
     * Add a module finder such as a ServiceLoaderModuleFinder or ClassPathScannerModuleFinder
     * @param provider
     * @return
     */
    public Karyon addAutoModuleListProvider(ModuleListProvider provider) {
        if (provider != null) {
            this.moduleProviders.add(provider);
        }
        return this;
    }
    
    /**
     * Add a runtime profile.  @see {@link ConditionalOnProfile}
     * 
     * @param profile
     */
    public Karyon addProfile(String profile) {
        if (profile != null) {
            this.profiles.add(profile);
        }
        return this;
    }

    /**
     * Add a runtime profiles.  @see {@link ConditionalOnProfile}
     * 
     * @param profile
     */
    public Karyon addProfiles(String... profiles) {
        if (profiles != null) {
            this.profiles.addAll(Arrays.asList(profiles));
        }
        return this;
    }
    
    /**
     * Add a runtime profiles.  @see {@link ConditionalOnProfile}
     * 
     * @param profile
     */
    public Karyon addProfiles(Collection<String> profiles) {
        if (profiles != null) {
            this.profiles.addAll(profiles);
        }
        return this;
    }
    
    /**
     * Enable the specified feature
     * @param feature
     */
    public Karyon enableFeature(KaryonFeature feature) {
        return enableFeature(feature, true);
    }
    
    /**
     * Enable or disable the specified feature
     * @param feature
     */
    public Karyon enableFeature(KaryonFeature feature, boolean enabled) {
        if (feature != null) {
            this.features.put(feature, enabled);
        }
        return this;
    }

    /**
     * Disable the specified feature
     * @param feature
     */
    public Karyon disableFeature(KaryonFeature feature) {
        return enableFeature(feature, false);
    }
    
    public Karyon setPropertySource(PropertySource propertySource) {
        this.propertySource = propertySource;
        return this;
    }

    public PropertySource getPropertySource() {
        return this.propertySource;
    }
    
    /**
     * Call this anywhere in the process of manipulating the builder to apply a reusable
     * sequence of calls to the builder 
     * 
     * @param module
     * @return The builder
     * @throws Exception
     */
    public Karyon apply(KaryonModule module) {
        module.configure(this);
        return this;
    }
    
    /**
     * 
     */
    public LifecycleInjector start() {
        return start(null);
    }
    
    /**
     * Shortcut to creating the injector
     * @return The builder
     * @throws Exception
     */
    public LifecycleInjector start(String[] args) {
        if (this.getPropertySource().equals(DefaultPropertySource.INSTANCE) && isFeatureEnabled(KaryonFeatures.USE_ARCHAIUS)) { 
            try {
                apply((KaryonModule) Class.forName("com.netflix.karyon.archaius.ArchaiusKaryonModule").newInstance());
            }
            catch (ClassNotFoundException e) {
                throw new RuntimeException("Unable to bootstrap using archaius. Either add a dependency on 'com.netflix.karyon:karyon3-archaius2' or disable the feature KaryonFeatures.USE_ARCHAIUS");
            } 
            catch (InstantiationException | IllegalAccessException e) {
                throw new RuntimeException("Unable to bootstrap using archaius");
            }
        }
        
        String karyonProfiles = getPropertySource().get(KARYON_PROFILES);
        if (karyonProfiles != null) {
             addProfiles(karyonProfiles);
        }
        
        if (isFeatureEnabled(KaryonFeatures.USE_DEFAULT_KARYON_MODULE)) 
            apply(new DefaultKaryonModule());
        
        return LifecycleInjectorCreator.createInjector(new KaryonConfiguration() {
            @Override
            public List<Module> getModules() {
                return modules;
            }

            @Override
            public List<Module> getOverrideModules() {
                return overrideModules;
            }

            @Override
            public List<ModuleListProvider> getAutoModuleListProviders() {
                return moduleProviders;
            }

            @Override
            public Set<String> getProfiles() {
                return profiles;
            }

            @Override
            public PropertySource getPropertySource() {
                return propertySource;
            }

            @Override
            public Stage getStage() {
                return stage;
            }

            @Override
            public boolean isFeatureEnabled(KaryonFeature feature) {
                return Karyon.this.isFeatureEnabled(feature);
            }
        });
    }

    private boolean isFeatureEnabled(KaryonFeature feature) {
        if (propertySource != null) {
            Boolean value = propertySource.get(feature.getKey(), Boolean.class);
            if (value != null && value == true) {
                return true;
            }
        }
        Boolean value = features.get(feature);
        return value == null
                ? feature.isEnabledByDefault()
                : value;
    }
    
    public static Karyon create() {
        return new Karyon();
    }
    
    public static Karyon create(Module ... modules) {
        return new Karyon().addModules(modules);
    }
    
    public static Karyon from(KaryonModule ... modules) {
        Karyon karyon = new Karyon();
        if (modules != null) {
            for (KaryonModule module : modules) {
                karyon.apply(module);
            }
        }
        return karyon;
    }
}
