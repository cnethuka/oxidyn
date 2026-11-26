package dev.oxidyn.mixin.client;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.oxidyn.Features;
import dev.oxidyn.client.CameraDistanceSorting;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/**
 * Swap the sorting used for section compiles and resorts for one we can
 * recognise later. Both the initial compile and ResortTransparencyTask get
 * theirs from createVertexSorting().
 */
@Mixin(targets = "net.minecraft.client.renderer.chunk.SectionRenderDispatcher$RenderSection")
public abstract class RenderSectionMixin {
    @WrapOperation(
            method = "createVertexSorting",
            at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/vertex/VertexSorting;byDistance(FFF)Lcom/mojang/blaze3d/vertex/VertexSorting;"))
    private VertexSorting oxidyn$rememberCamera(float x, float y, float z, Operation<VertexSorting> original) {
        return Features.translucentSort ? new CameraDistanceSorting(x, y, z) : original.call(x, y, z);
    }
}
