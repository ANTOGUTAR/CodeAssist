package com.tyron.builder.internal.service.scopes;

import com.tyron.builder.api.Action;
import com.tyron.builder.api.internal.GradleInternal;
import com.tyron.builder.api.internal.SettingsInternal;
import com.tyron.builder.api.internal.file.FileLookup;
import com.tyron.builder.api.internal.file.FileResolver;
import com.tyron.builder.api.internal.reflect.service.DefaultServiceRegistry;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistration;
import com.tyron.builder.api.internal.reflect.service.ServiceRegistry;

public class SettingsScopeServices extends DefaultServiceRegistry {
    private final SettingsInternal settings;

    public SettingsScopeServices(final ServiceRegistry parent, final SettingsInternal settings) {
        super(parent);
        this.settings = settings;
        register(new Action<ServiceRegistration>() {
            @Override
            public void execute(ServiceRegistration registration) {
                for (PluginServiceRegistry pluginServiceRegistry : parent.getAll(PluginServiceRegistry.class)) {
                    pluginServiceRegistry.registerSettingsServices(registration);
                }
            }
        });
    }

    protected FileResolver createFileResolver(FileLookup fileLookup) {
        return fileLookup.getFileResolver(settings.getSettingsDir());
    }

    protected GradleInternal createGradleInternal() {
        return settings.getGradle();
    }
}
