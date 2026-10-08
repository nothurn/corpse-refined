package com.playercorpse.client;

import com.mojang.blaze3d.platform.InputConstants.Type;
import net.minecraft.client.KeyMapping;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;

public final class PlayerCorpseKeyBindings {
   public static final KeyMapping OPEN_DEATH_HISTORY = new KeyMapping("key.playercorpse.open_death_history", Type.KEYSYM, 67, "key.categories.playercorpse");

   private PlayerCorpseKeyBindings() {
   }

   public static void onRegisterKeyMappings(RegisterKeyMappingsEvent event) {
      event.register(OPEN_DEATH_HISTORY);
   }
}
