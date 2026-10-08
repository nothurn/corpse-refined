package com.playercorpse;

import com.mojang.logging.LogUtils;
import com.playercorpse.client.PlayerCorpseClientEvents;
import com.playercorpse.client.PlayerCorpseKeyBindings;
import com.playercorpse.registry.PlayerCorpseAttributes;
import com.playercorpse.registry.PlayerCorpseBlockEntities;
import com.playercorpse.registry.PlayerCorpseBlocks;
import com.playercorpse.registry.PlayerCorpseEntities;
import com.playercorpse.registry.PlayerCorpseMenus;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig.Type;
import net.neoforged.fml.loading.FMLEnvironment;
import net.neoforged.neoforge.common.NeoForge;
import org.slf4j.Logger;

@Mod("playercorpse")
public class PlayerCorpseMod {
   public static final String MOD_ID = "playercorpse";
   private static final Logger LOGGER = LogUtils.getLogger();

   public PlayerCorpseMod(IEventBus modEventBus, ModContainer modContainer) {
      PlayerCorpseBlocks.BLOCKS.register(modEventBus);
      PlayerCorpseBlockEntities.BLOCK_ENTITY_TYPES.register(modEventBus);
      PlayerCorpseEntities.ENTITY_TYPES.register(modEventBus);
      PlayerCorpseMenus.MENU_TYPES.register(modEventBus);
      modEventBus.addListener(PlayerCorpseAttributes::onEntityAttributeCreation);
      modEventBus.addListener(PlayerCorpseNetworking::onRegisterPayloadHandlers);
      if (FMLEnvironment.dist == Dist.CLIENT) {
         modEventBus.addListener(PlayerCorpseClientEvents::onRegisterRenderers);
         modEventBus.addListener(PlayerCorpseClientEvents::onRegisterLayerDefinitions);
         modEventBus.addListener(PlayerCorpseClientEvents::onRegisterMenuScreens);
         modEventBus.addListener(PlayerCorpseKeyBindings::onRegisterKeyMappings);
      }
      modContainer.registerConfig(Type.COMMON, PlayerCorpseConfig.SPEC);
      NeoForge.EVENT_BUS.register(new PlayerCorpseEvents());
      LOGGER.info("Corpse: Refined initialized.");
   }
}
