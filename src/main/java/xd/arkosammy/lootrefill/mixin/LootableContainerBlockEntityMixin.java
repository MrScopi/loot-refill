package xd.arkosammy.lootrefill.mixin;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntityType;
import net.minecraft.block.entity.LockableContainerBlockEntity;
import net.minecraft.block.entity.LootableContainerBlockEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.inventory.LootableInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.util.Identifier;
import net.minecraft.util.collection.DefaultedList;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;
import xd.arkosammy.lootrefill.LootRefill;
import xd.arkosammy.lootrefill.util.ducks.LootableContainerBlockEntityAccessor;
import xd.arkosammy.lootrefill.util.ducks.VieweableContainer;

@Mixin(LootableContainerBlockEntity.class)
public abstract class LootableContainerBlockEntityMixin extends LockableContainerBlockEntity implements LootableInventory, LootableContainerBlockEntityAccessor {

    // getItemStacks
    @Shadow protected abstract DefaultedList<ItemStack> method_11282();

    @Shadow public abstract @Nullable Identifier getLootTableId();

    @Unique
    @Nullable
    private Identifier cachedLootTableId;

    @Unique
    private long refillCount;

    @Unique
    private long maxRefills;

    @Unique
    private long lastSavedTime;

    @Unique
    private boolean looted;

    protected  LootableContainerBlockEntityMixin(BlockEntityType<?> blockEntityType, BlockPos blockPos, BlockState blockState) {
        super(blockEntityType, blockPos, blockState);
    }

    @Override
    public void lootrefill$setCachedLootTableId(Identifier lootTableId) {
        this.cachedLootTableId = lootTableId;
    }

    @Override
    public Identifier lootrefill$getCachedLootTableId() {
        return this.cachedLootTableId;
    }

    @Override
    public void lootrefill$setMaxRefills(long maxRefills) {
        this.maxRefills = maxRefills;
    }

    @Inject(method = "setStack", at = @At("RETURN"))
    private void onStackQuickMoved(int slot, ItemStack stack, CallbackInfo ci) {
        if(stack.isEmpty()) {
            this.onStackRemoved();
        }
    }

    @Inject(method = "removeStack(I)Lnet/minecraft/item/ItemStack;", at = @At(value = "RETURN"))
    private void setLootedOnStackRemoved(int slot, CallbackInfoReturnable<ItemStack> cir) {
        this.onStackRemoved();
    }


    @Inject(method = "removeStack(II)Lnet/minecraft/item/ItemStack;", at = @At(value = "RETURN"))
    private void setLootedOnStackRemovedWithAmount(int slot, int amount, CallbackInfoReturnable<ItemStack> cir) {
        this.onStackRemoved();
    }

    // Consider this container as looted whenever a stack is extracted from it. If we care about container emptiness, then take that into account as well
    @Unique
    private void onStackRemoved() {
         World world = this.getWorld();
        if(world == null) {
            return;
        }
        boolean refillWhenEmpty = world.getGameRules().getBoolean(LootRefill.REFILL_ONLY_WHEN_EMPTY);
        // Update the last-saved time before the looted property is updated, to make sure we update it to the last possible time before the countdown can begin
        this.updateLastSavedTime(world);
        this.looted = !refillWhenEmpty || this.isEmptyNoSideEffects();
    }

    @Override
    public void lootrefill$writeDataToNbt(NbtCompound nbt) {
        nbt.putLong("refillCount", this.refillCount);
        nbt.putLong("maxRefills", this.maxRefills);
        nbt.putLong("lastSavedTime", this.lastSavedTime);
        nbt.putBoolean("looted", this.looted);
        if(this.cachedLootTableId != null) {
            nbt.putString("cachedLootTableId", this.cachedLootTableId.toString());
        }
    }

    @Override
    public void lootrefill$readDataFromNbt(NbtCompound nbt) {
        if(nbt.contains("refillCount")) {
            this.refillCount = nbt.getLong("refillCount");
        }
        if(nbt.contains("maxRefills")) {
            this.maxRefills = nbt.getLong("maxRefills");
        }
        if(nbt.contains("lastSavedTime")) {
            this.lastSavedTime = nbt.getLong("lastSavedTime");
        }
        if(nbt.contains("looted")) {
            this.looted = nbt.getBoolean("looted");
        }
        if(nbt.contains("cachedLootTableId")) {
            this.cachedLootTableId = new Identifier(nbt.getString("cachedLootTableId"));
        }
    }

    @Override
    public boolean lootrefill$shouldRefillLoot(World world, PlayerEntity player) {
        // Refill the container if it is the first time that it is being attempted to be refilled
        if(this.refillCount == 0) {
            return true;
        }
        this.updateLastSavedTime(world);
        boolean isEmpty = this.isEmptyNoSideEffects();
        if(!this.looted) {
            return false;
        }
        if(!isEmpty && world.getGameRules().getBoolean(LootRefill.REFILL_ONLY_WHEN_EMPTY)) {
            return false;
        }
        // A value of -1 for maxRefills means unlimited refills
        if (maxRefills >= 0 && this.refillCount >= this.maxRefills) {
            return false;
        }
        if (this instanceof VieweableContainer vieweableContainer && vieweableContainer.lootrefill$isBeingViewed()) {
            return false;
        }
        return world.getTime() - this.lastSavedTime >= LootRefill.secondsToTicks(world.getGameRules().getInt(LootRefill.SECONDS_UNTIL_REFILL));
    }

    @Override
    public void lootrefill$onLootRefilled(World world) {
        this.lastSavedTime = world.getTime();
        this.refillCount++;
        this.looted = false;
        this.maxRefills = world.getGameRules().getInt(LootRefill.MAX_REFILLS);
    }

    @Override
    public boolean lootrefill$shouldBeProtected(World world) {
        return this.cachedLootTableId != null && this.refillCount < this.maxRefills;
    }

    // Update the last saved time to the current time only if this container has not been looted.
    // This allows us to start the refill countdown only when this container has been looted.
    @Unique
    private void updateLastSavedTime(World world) {
        if(!this.looted) {
            this.lastSavedTime = world.getTime();
        }
    }

    // Check if the container is empty. Do not use the Minecraft provided method since that calls generateLoot(), which we don't want
    @Unique
    private boolean isEmptyNoSideEffects() {
        return this.method_11282().stream().allMatch(ItemStack::isEmpty);
    }

}
