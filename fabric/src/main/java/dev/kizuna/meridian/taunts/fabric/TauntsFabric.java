package dev.kizuna.meridian.taunts.fabric;

import dev.kizuna.meridian.taunts.server.TauntCatalog;
import dev.kizuna.meridian.taunts.server.TauntSettings;
import dev.kizuna.meridian.vanilla.fabric.VanillaDamageFabric;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/** 垃圾话入口：读配置与对应语言的台词表，订阅 Damage 提交后的伤害事实，注册调试命令。 */
public final class TauntsFabric implements ModInitializer {
    public static final String MOD_ID = "meridian_humanoid_taunts";
    static final String SETTINGS_FILE = "meridian-humanoid-taunts.toml";
    static final String CATALOG_RESOURCE = "/data/" + MOD_ID + "/taunts/%s.json";

    @Override
    public void onInitialize() {
        // 台词表在任何世界加载前校验完毕；缺文件或格式错误直接阻止启动。
        TauntSettings settings = TauntSettings.load(FabricLoader.getInstance().getConfigDir().resolve(SETTINGS_FILE));
        TauntCatalog catalog = TauntCatalog.parse(catalogText(settings.language()));
        TauntSpeaker speaker = new TauntSpeaker(settings, catalog);
        VanillaDamageFabric.registerDamageObserver(speaker::onDamage);
        CommandRegistrationCallback.EVENT.register(
                (dispatcher, registryAccess, environment) -> dispatcher.register(TauntCommands.command(speaker)));
    }

    static String catalogText(String language) {
        // 台词表是 JAR 内固定资源，按语言代码选文件；缺失或不可读都是打包或配置错误。
        String resource = CATALOG_RESOURCE.formatted(language);
        try (InputStream input = TauntsFabric.class.getResourceAsStream(resource)) {
            if (input == null) {
                throw new IllegalStateException("缺少台词文件 " + resource);
            }
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) {
            throw new IllegalStateException("无法读取台词文件 " + resource, failure);
        }
    }
}
