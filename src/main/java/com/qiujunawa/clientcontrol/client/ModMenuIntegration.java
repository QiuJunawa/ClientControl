package com.qiujunawa.clientcontrol.client;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import com.qiujunawa.clientcontrol.config.ClientControlConfig;

public class ModMenuIntegration implements ModMenuApi {

    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return parent -> ClientControlConfig.createConfigScreen();
    }
}
