package org.waste.of.time.mixin;

import net.minecraft.client.render.Frustum;
import net.minecraft.client.render.VertexConsumerProvider;
import net.minecraft.client.render.debug.DebugRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.waste.of.time.Events;

@Mixin(DebugRenderer.class)
public class DebugRendererMixin {
    @Inject(method = "render", at = @At("HEAD"))
    public void renderInject(
            net.minecraft.client.util.math.MatrixStack matrices,
            Frustum frustum,
            VertexConsumerProvider.Immediate vertexConsumers,
            double cameraX,
            double cameraY,
            double cameraZ,
            // The boolean argument required by the signature (likely a tick or render flag)
            // The error expected: (..., D, D, D, Z, CallbackInfo)
            boolean tick,
            CallbackInfo ci) {
        Events.INSTANCE.onDebugRenderStart(cameraX, cameraY, cameraZ);
    }
}
