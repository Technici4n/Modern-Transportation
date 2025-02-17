/*
 * Modern Dynamics
 * Copyright (C) 2021 shartte & Technici4n
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package dev.technici4n.moderndynamics.pipe;

import com.google.common.base.Preconditions;
import dev.technici4n.moderndynamics.MdBlockEntity;
import dev.technici4n.moderndynamics.attachment.AttachmentItem;
import dev.technici4n.moderndynamics.attachment.attached.AttachedAttachment;
import dev.technici4n.moderndynamics.model.AttachmentModelData;
import dev.technici4n.moderndynamics.model.PipeModelData;
import dev.technici4n.moderndynamics.network.NodeHost;
import dev.technici4n.moderndynamics.network.TickHelper;
import dev.technici4n.moderndynamics.util.DropHelper;
import dev.technici4n.moderndynamics.util.ShapeHelper;
import dev.technici4n.moderndynamics.util.WrenchHelper;
import java.util.Objects;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.ContainerHelper;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.ItemInteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.neoforged.neoforge.capabilities.BlockCapability;
import net.neoforged.neoforge.client.model.data.ModelData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Abstract base BE class for all pipes.
 * Subclasses must have a static list of {@link NodeHost}s that will be used for all the registration and saving logic.
 */
public abstract class PipeBlockEntity extends MdBlockEntity {
    public PipeBlockEntity(BlockEntityType<?> type, BlockPos pos, BlockState state) {
        super(type, pos, state);
    }

    private NodeHost[] hosts;
    private boolean hostsRegistered = false;
    public int connectionBlacklist = 0;
    private VoxelShape cachedShape = PipeBoundingBoxes.CORE_SHAPE;
    /* client side stuff */
    private ModelData clientModelData = ModelData.EMPTY;

    private int clientSideConnections = 0;

    protected abstract NodeHost[] createHosts();

    public final NodeHost[] getHosts() {
        if (hosts == null) {
            hosts = createHosts();
        }
        return hosts;
    }

    @Nullable
    public final <T> T findHost(Class<T> hostClass) {
        for (var host : getHosts()) {
            if (hostClass.isInstance(host)) {
                return hostClass.cast(host);
            }
        }
        return null;
    }

    private boolean hasAttachment(Direction side) {
        if (isClientSide()) {
            var pipeData = getPipeModelData();
            return pipeData != null && pipeData.attachments()[side.get3DDataValue()] != null;
        } else {
            return getAttachment(side) != null;
        }
    }

    @Nullable
    private PipeModelData getPipeModelData() {
        return clientModelData.get(PipeModelData.PIPE_DATA);
    }

    public final AttachedAttachment getAttachment(Direction side) {
        Preconditions.checkState(!isClientSide(), "Attachments don't exist on the client.");

        for (var host : getHosts()) {
            var attachment = host.getAttachment(side);
            if (attachment != null) {
                return attachment;
            }
        }
        return null;
    }

    @Nullable
    public Item getAttachmentItem(Direction side) {
        if (isClientSide()) {
            var pipeModelData = getPipeModelData();
            if (pipeModelData != null) {
                var attachment = pipeModelData.attachments()[side.get3DDataValue()];
                return attachment == null ? null : attachment.item();
            }
            return null;
        } else {
            var attachment = getAttachment(side);
            return attachment == null ? null : attachment.getItem();
        }
    }

    @Override
    public void sync() {
        super.sync();
        updateCachedShape(getPipeConnections(), getInventoryConnections());
    }

    @Override
    public void toClientTag(CompoundTag tag, RegistryAccess registries) {
        tag.putByte("connectionBlacklist", (byte) connectionBlacklist);
        tag.putByte("connections", (byte) getPipeConnections());
        tag.putByte("inventoryConnections", (byte) getInventoryConnections());
        for (var host : getHosts()) {
            host.writeClientNbt(tag, registries);
        }
        var attachments = new ListTag();
        for (var direction : Direction.values()) {
            var attachment = getAttachment(direction);
            if (attachment != null) {
                attachments.add(attachment.getModelData().write(new CompoundTag()));
            } else {
                attachments.add(new CompoundTag());
            }
        }
        tag.put("attachments", attachments);
    }

    @Override
    public void fromClientTag(CompoundTag tag, RegistryAccess registries) {
        connectionBlacklist = tag.getByte("connectionBlacklist");
        byte connections = tag.getByte("connections");
        byte inventoryConnections = tag.getByte("inventoryConnections");
        var attachmentStacks = NonNullList.withSize(6, ItemStack.EMPTY);
        ContainerHelper.loadAllItems(tag, attachmentStacks, registries);

        for (var host : getHosts()) {
            host.readClientNbt(tag, registries);
        }

        // remesh flag, a bit hacky but it should work ;)
        // the second check ensures that the very first packet is processed even though it doesn't have the remesh flag
        if (tag.getBoolean("#c") || clientModelData == ModelData.EMPTY) {
            var attachmentTags = tag.getList("attachments", Tag.TAG_COMPOUND);
            var attachments = new AttachmentModelData[6];
            for (var direction : Direction.values()) {
                var attachmentTag = attachmentTags.getCompound(direction.get3DDataValue());
                attachments[direction.get3DDataValue()] = AttachmentModelData.from(attachmentTag);
            }

            clientModelData = ModelData.builder()
                    .with(PipeModelData.PIPE_DATA, new PipeModelData(connections, inventoryConnections, attachments))
                    .build();
            clientSideConnections = connections | inventoryConnections;
            requestModelDataUpdate();

            updateCachedShape(connections, inventoryConnections);
        }
    }

    @Override
    public @NotNull ModelData getModelData() {
        return clientModelData;
    }

    @Override
    public void toTag(CompoundTag nbt, HolderLookup.Provider registries) {
        nbt.putByte("connectionBlacklist", (byte) connectionBlacklist);

        if (!level.isClientSide()) { // WTHIT calls this on the client side
            for (NodeHost host : getHosts()) {
                if (hostsRegistered) {
                    host.separateNetwork();
                }

                host.writeNbt(nbt, registries);
            }
        }
    }

    @Override
    public void fromTag(CompoundTag nbt, HolderLookup.Provider registries) {
        connectionBlacklist = nbt.getByte("connectionBlacklist");

        for (NodeHost host : getHosts()) {
            if (hostsRegistered) {
                host.separateNetwork();
            }

            host.readNbt(nbt, registries);
        }
    }

    public void scheduleHostUpdates() {
        for (NodeHost host : getHosts()) {
            host.scheduleUpdate();
        }
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();

        if (!level.isClientSide()) {
            if (!hostsRegistered) {
                TickHelper.runLater(() -> {
                    if (!hostsRegistered && !isRemoved()) {
                        hostsRegistered = true;

                        for (NodeHost host : getHosts()) {
                            host.addSelf();
                        }
                    }
                });
            }
        }
    }

    @Override
    public void setRemoved() {
        super.setRemoved();

        if (!level.isClientSide()) {
            if (hostsRegistered) {
                hostsRegistered = false;

                for (NodeHost host : getHosts()) {
                    host.removeSelf();
                }
            }
        }
    }

    public void refreshHosts() {
        if (hostsRegistered) {
            for (NodeHost host : getHosts()) {
                host.refreshSelf();
            }
        }
    }

    @Nullable
    public Object getApiInstance(BlockCapability<?, Direction> capability, @Nullable Direction side) {
        for (var host : getHosts()) {
            var api = host.getApiInstance(capability, side);
            if (api != null) {
                return api;
            }
        }
        return null;
    }

    protected int getPipeConnections() {
        int pipeConnections = 0;

        for (NodeHost host : getHosts()) {
            pipeConnections |= host.pipeConnections;
        }

        return pipeConnections;
    }

    protected int getInventoryConnections() {
        int inventoryConnections = 0;

        for (NodeHost host : getHosts()) {
            inventoryConnections |= host.inventoryConnections;
        }

        return inventoryConnections;
    }

    public VoxelShape getCachedShape() {
        return cachedShape;
    }

    public void updateCachedShape(int pipeConnections, int inventoryConnections) {
        int attachments = 0;

        for (var direction : Direction.values()) {
            if (hasAttachment(direction)) {
                attachments |= 1 << direction.get3DDataValue();
            }
        }

        cachedShape = PipeBoundingBoxes.getPipeShape(pipeConnections, inventoryConnections, attachments);
    }

    private void updateConnectionBlacklist(Direction side, boolean addConnection) {
        if (level.isClientSide()) {
            throw new IllegalStateException("updateConnections() should not be called client-side.");
        }

        // Update mask
        if (addConnection) {
            connectionBlacklist &= ~(1 << side.get3DDataValue());
        } else {
            connectionBlacklist |= 1 << side.get3DDataValue();
        }

        // Update neighbor's mask as well
        BlockEntity be = level.getBlockEntity(worldPosition.relative(side));

        if (be instanceof PipeBlockEntity neighborPipe) {
            if (addConnection) {
                neighborPipe.connectionBlacklist &= ~(1 << side.getOpposite().get3DDataValue());
            } else {
                neighborPipe.connectionBlacklist |= 1 << side.getOpposite().get3DDataValue();
            }
            neighborPipe.setChanged();
        }
    }

    /**
     * Update connection blacklist for a side, and schedule a node update, on the server side.
     */
    protected void updateConnection(Direction side, boolean addConnection) {
        updateConnectionBlacklist(side, addConnection);

        // Schedule inventory and network updates.
        refreshHosts();
        // The call to getNode() causes a network rebuild, but that shouldn't be an issue. (?)
        scheduleHostUpdates();
        // Exposed caps do change
        invalidateCapabilities();

        level.blockUpdated(worldPosition, getBlockState().getBlock());
        setChanged();
        // no need to sync(), that's already handled by the refresh or update if necessary
    }

    public ItemInteractionResult useItemOn(Player player, InteractionHand hand, BlockHitResult hitResult) {
        var stack = player.getItemInHand(hand);
        Vec3 posInBlock = getPosInBlock(hitResult);

        if (WrenchHelper.isWrench(stack)) {
            // If the core was hit, add back the pipe on the target side
            if (ShapeHelper.shapeContains(PipeBoundingBoxes.CORE_SHAPE, posInBlock)) {
                if ((connectionBlacklist & (1 << hitResult.getDirection().get3DDataValue())) > 0) {
                    if (!level.isClientSide()) {
                        updateConnection(hitResult.getDirection(), true);
                    }

                    return ItemInteractionResult.sidedSuccess(level.isClientSide());
                }
            }

            for (int i = 0; i < 6; ++i) {
                if (ShapeHelper.shapeContains(PipeBoundingBoxes.INVENTORY_CONNECTIONS[i], posInBlock)) {
                    var side = Direction.from3DDataValue(i);
                    if (hasAttachment(side)) {
                        // We will either remove the attachment or clear out its stuffed items.
                        // In any case, for the client it's a success.
                        if (isClientSide()) {
                            return ItemInteractionResult.SUCCESS;
                        }

                        for (var host : getHosts()) {
                            var attachment = host.getAttachment(side);
                            if (attachment != null) {
                                // Try to clear contents
                                if (attachment.tryClearContents(this)) {
                                    return ItemInteractionResult.CONSUME;
                                } else {
                                    // Remove attachment
                                    host.removeAttachment(side);
                                    if (!player.isCreative()) {
                                        DropHelper.dropStacks(this, attachment.getDrops());
                                    }
                                    level.blockUpdated(worldPosition, getBlockState().getBlock());
                                    refreshHosts();
                                    scheduleHostUpdates();
                                    setChanged();
                                    sync();
                                    return ItemInteractionResult.CONSUME;
                                }
                            }
                        }
                    } else {
                        // If a pipe or inventory connection was hit, add it to the blacklist
                        // INVENTORY_CONNECTIONS contains both the pipe and the connector, so it will work in both cases
                        if (level.isClientSide()) {
                            if ((clientSideConnections & (1 << i)) > 0) {
                                return ItemInteractionResult.SUCCESS;
                            }
                        } else {
                            if ((getPipeConnections() & (1 << i)) > 0 || (getInventoryConnections() & (1 << i)) > 0) {
                                updateConnection(Direction.from3DDataValue(i), false);
                                return ItemInteractionResult.CONSUME;
                            }
                        }
                    }
                }
            }
        }

        if (stack.getItem() instanceof AttachmentItem attachmentItem) {
            Direction hitSide = null;
            if (ShapeHelper.shapeContains(PipeBoundingBoxes.CORE_SHAPE, posInBlock)) {
                hitSide = hitResult.getDirection();
            }
            for (int i = 0; i < 6; ++i) {
                if (ShapeHelper.shapeContains(PipeBoundingBoxes.INVENTORY_CONNECTIONS[i], posInBlock)) {
                    hitSide = Direction.from3DDataValue(i);
                }
            }

            if (hitSide != null) {
                if (!hasAttachment(hitSide)) {
                    for (var host : getHosts()) {
                        if (host.acceptsAttachment(attachmentItem, stack)) {
                            if (!level.isClientSide) {
                                // Re-enable connection when an attachment is added to it if was previously disabled.
                                // (Attachments on disabled connections don't work as expected,
                                // yet there is no visual indication. So we just disallow that.)
                                updateConnectionBlacklist(hitSide, true);

                                host.setAttachment(hitSide, attachmentItem, new CompoundTag(), level.registryAccess());
                                host.getAttachment(hitSide).onPlaced(player);
                                level.blockUpdated(worldPosition, getBlockState().getBlock());
                                refreshHosts();
                                scheduleHostUpdates();
                                setChanged();
                                sync();
                            }
                            if (!player.isCreative()) {
                                stack.shrink(1);
                            }
                            return ItemInteractionResult.sidedSuccess(level.isClientSide);
                        }
                    }
                }
            }
        }

        return ItemInteractionResult.PASS_TO_DEFAULT_BLOCK_INTERACTION;
    }

    public InteractionResult useWithoutItem(Player player, BlockHitResult hitResult) {
        Vec3 posInBlock = getPosInBlock(hitResult);

        // Handle click on attachment
        var hitSide = hitTestAttachments(posInBlock);
        if (hitSide != null) {
            if (!isClientSide()) {
                var attachment = getAttachment(hitSide);
                if (attachment != null && attachment.hasMenu()) {
                    // Open attachment GUI
                    var menuProvider = attachment.createMenu(this, hitSide);
                    if (menuProvider != null) {
                        player.openMenu(menuProvider, menuProvider::writeScreenOpeningData);
                    }
                }
            }
            return InteractionResult.sidedSuccess(isClientSide());
        }

        return InteractionResult.PASS;
    }

    public Vec3 getPosInBlock(HitResult hitResult) {
        return hitResult.getLocation().subtract(worldPosition.getX(), worldPosition.getY(), worldPosition.getZ());
    }

    @Nullable
    public Direction hitTestAttachments(Vec3 posInBlock) {
        // Handle click on attachment
        for (int i = 0; i < 6; ++i) {
            var direction = Direction.from3DDataValue(i);
            if (hasAttachment(direction)
                    && ShapeHelper.shapeContains(PipeBoundingBoxes.CONNECTOR_SHAPES[i], posInBlock)) {
                return direction;
            }
        }

        return null;
    }

    public ItemStack overridePickBlock(HitResult hitResult) {
        Direction side = hitTestAttachments(getPosInBlock(hitResult));
        if (side != null) {
            return Objects.requireNonNull(getAttachmentItem(side), "Failed to get attachment item").getDefaultInstance();
        }
        return ItemStack.EMPTY;
    }

    public void onRemoved() {
        for (var host : getHosts()) {
            host.onRemoved();
        }
    }

    public int getClientSideConnections() {
        Preconditions.checkState(isClientSide());
        return clientSideConnections;
    }

    public void clientTick() {
        for (var host : getHosts()) {
            host.clientTick();
        }
    }
}
