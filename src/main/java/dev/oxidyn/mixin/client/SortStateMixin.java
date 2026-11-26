package dev.oxidyn.mixin.client;

import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexSorting;
import dev.oxidyn.Features;
import dev.oxidyn.client.CameraDistanceSorting;
import dev.oxidyn.client.CentroidCache;
import dev.oxidyn.ffi.NativeBackend;
import dev.oxidyn.ffi.Natives;
import org.joml.Vector3f;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * The actual resort. Vanilla: sort() -> int[] -> six memPut calls per quad
 * through an IntConsumer. Us: one downcall that sorts and writes the index
 * buffer straight into the memory ByteBufferBuilder just reserved.
 */
@Mixin(MeshData.SortState.class)
public abstract class SortStateMixin {
    @Shadow @Final private Vector3f[] centroids;
    @Shadow @Final private VertexFormat.IndexType indexType;

    // the centroids never change for a given SortState, but it gets resorted
    // every time the camera moves, so flatten them once
    @Unique
    private float[] oxidyn$flat;

    @Inject(method = "buildSortedIndexBuffer", at = @At("HEAD"), cancellable = true)
    private void oxidyn$nativeSort(ByteBufferBuilder builder, VertexSorting sorting,
                                   CallbackInfoReturnable<ByteBufferBuilder.Result> cir) {
        if (!(sorting instanceof CameraDistanceSorting camera) || !Features.translucentSort) return;
        int quads = centroids.length;
        if (quads == 0) return;
        NativeBackend backend = Natives.get();
        if (backend == null) return;

        float[] flat = oxidyn$flat;
        if (flat == null) {
            oxidyn$flat = flat = CentroidCache.flatten(centroids);
        }

        long dst = builder.reserve(quads * 6 * indexType.bytes);
        backend.sortQuads(flat, quads, camera.x, camera.y, camera.z, dst, indexType.bytes);
        cir.setReturnValue(builder.build());
    }
}
