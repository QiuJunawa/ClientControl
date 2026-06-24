package com.qiujunawa.clientcontrol.mixin;

import com.qiujunawa.clientcontrol.client.ClientControlClient;
import net.minecraft.client.network.ClientPlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.callback.CallbackInfo;

@Mixin(ClientPlayerEntity.class)
public abstract class ClientPlayerEntityMixin {

	@Shadow
	public abstract void updateVelocity();

	@Inject(method = "updateVelocity", at = @At("HEAD"), cancellable = true)
	private void onUpdateVelocity(CallbackInfo ci) {
		float multiplier = ClientControlClient.getSpeedMultiplier();
		if (multiplier < 1.0f && multiplier > 0.0f) {
			ClientPlayerEntity player = (ClientPlayerEntity) (Object) this;
			player.setVelocity(player.getVelocity().multiply(multiplier));
			System.out.println("[Mixin] 速度已修改，倍率: " + multiplier);
		}
	}
}
