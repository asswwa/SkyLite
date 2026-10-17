package dev.asswa.skylite;

import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/**
 * Optional Mod Menu hook: shows the "Config" button on SkyLite's row in the mod list.
 * Only ever loaded when Mod Menu itself is installed; the screen behind the button is
 * SkyLite's own, so Cloth Config is not needed.
 */
public final class ModMenuIntegration implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return SkyLiteConfigScreen::create;
    }
}
