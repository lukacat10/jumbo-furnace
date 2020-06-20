package com.github.commoble.jumbofurnace.jumbo_furnace;

import java.util.ArrayList;
import java.util.List;

import com.github.commoble.jumbofurnace.JumboFurnace;
import com.github.commoble.jumbofurnace.JumboFurnaceObjects;
import com.github.commoble.jumbofurnace.recipes.ClaimableRecipeWrapper;
import com.github.commoble.jumbofurnace.recipes.JumboFurnaceRecipe;
import com.github.commoble.jumbofurnace.recipes.RecipeSorter;

import net.minecraft.block.BlockState;
import net.minecraft.inventory.InventoryHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.item.crafting.IRecipe;
import net.minecraft.nbt.CompoundNBT;
import net.minecraft.tileentity.ITickableTileEntity;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Direction;
import net.minecraft.util.math.BlockPos;
import net.minecraftforge.common.ForgeHooks;
import net.minecraftforge.common.util.LazyOptional;
import net.minecraftforge.items.CapabilityItemHandler;
import net.minecraftforge.items.IItemHandler;
import net.minecraftforge.items.ItemStackHandler;

public class JumboFurnaceCoreTileEntity extends TileEntity implements ITickableTileEntity
{
	public static final String INPUT = "input";
	public static final String FUEL = "fuel";
	public static final String OUTPUT = "output";
	public static final String COOK_PROGRESS = "cook_progress";
	public static final String BURN_TIME = "burn_time";
	public static final String BURN_VALUE = "burn_value";
	
	public final InputItemHandler input = new InputItemHandler(this);
	public final ItemStackHandler fuel = new FuelItemHandler(this);
	public final OutputItemHandler output = new OutputItemHandler(this);
	
	public final LazyOptional<IItemHandler> inputOptional = LazyOptional.of(() -> this.input);
	public final LazyOptional<IItemHandler> fuelOptional = LazyOptional.of(() -> this.fuel);
	public final LazyOptional<IItemHandler> outputOptional = LazyOptional.of(() -> this.output);
	
	public int burnTimeRemaining = 0;
	public int lastItemBurnedValue = 200;
	public int cookProgress = 0;
	public boolean isRoomToCook = true;
	public boolean canConsumeFuel = false;
	public ClaimableRecipeWrapper cachedRecipes = this.input.getFreshRecipeInput();
	public boolean needsRecipeUpdate = false;
	public boolean needsOutputUpdate = false;
	public boolean needsFuelUpdate = false;
	
	public JumboFurnaceCoreTileEntity()
	{
		super(JumboFurnaceObjects.CORE_TE_TYPE);
	}

	@Override
	public void remove()
	{
		this.inputOptional.invalidate();
		this.fuelOptional.invalidate();
		this.outputOptional.invalidate();
		super.remove();
	}

	@Override
	public void read(CompoundNBT compound)
	{
		super.read(compound);
		this.input.deserializeNBT(compound.getCompound(INPUT));
		this.fuel.deserializeNBT(compound.getCompound(FUEL));
		this.output.deserializeNBT(compound.getCompound(OUTPUT));
		this.cookProgress = compound.getInt(COOK_PROGRESS);
		this.burnTimeRemaining = compound.getInt(BURN_TIME);
		this.lastItemBurnedValue = compound.getInt(BURN_VALUE);
		this.onInputInventoryChanged();
		this.onOutputInventoryChanged();
		this.onFuelInventoryChanged();
	}

	@Override
	public CompoundNBT write(CompoundNBT compound)
	{
		super.write(compound);
		compound.put(INPUT, this.input.serializeNBT());
		compound.put(FUEL, this.fuel.serializeNBT());
		compound.put(OUTPUT, this.output.serializeNBT());
		compound.putInt(COOK_PROGRESS, this.cookProgress);
		compound.putInt(BURN_TIME, this.burnTimeRemaining);
		compound.putInt(BURN_VALUE, this.lastItemBurnedValue);
		return compound;
	}
	
	public int getBurnConsumption()
	{
		return Math.max(1, this.cachedRecipes.getRecipeCount());
	}
	
	public boolean isBurning()
	{
		return this.burnTimeRemaining > 0;
	}
	
	public void updateBurningBlockstates(boolean burning)
	{
		for (Direction direction : Direction.Plane.HORIZONTAL)
		{
			BlockPos adjacentPos = this.pos.offset(direction);
			BlockState state = this.world.getBlockState(adjacentPos);
			if (state.getBlock() instanceof JumboFurnaceBlock)
			{
				this.world.setBlockState(adjacentPos, state.with(JumboFurnaceBlock.LIT, burning));
			}
		}
	}
	
	public void onFuelInventoryChanged()
	{
		this.needsFuelUpdate = true;
	}
	
	public void onInputInventoryChanged()
	{
		this.needsRecipeUpdate = true;
	}
	
	public void onOutputInventoryChanged()
	{
		this.needsOutputUpdate = true;
	}
	
	/** Called at the start of a tick when the input inventory has changed **/
	public void updateRecipes()
	{
		ClaimableRecipeWrapper wrapper = this.input.getFreshRecipeInput();
		// get all recipes allowed by furnace or jumbo furnace
		// sort them by specificity (can we do this on recipe reload?)
		// recipes requiring multiple ingredients = most important, ingredients with more matching items (tags) = less important
		List<JumboFurnaceRecipe> recipes = RecipeSorter.INSTANCE.getSortedFurnaceRecipes(this.world.getRecipeManager());
		// start assigning input slots to usable recipes as they are found
		for (JumboFurnaceRecipe recipe : recipes)
		{
			// loop recipe over inputs until it can't match or we have no unused inputs left
			while (wrapper.getRecipeCount() < JumboFurnace.SERVER_CONFIG.maxSimultaneousRecipes.get() && wrapper.matchAndClaimInputs(recipe, this.world) && wrapper.hasUnusedInputsLeft());
		}
		// when all input slots are claimed or the recipe list is exhausted, set the new recipe cache
		this.cachedRecipes = wrapper;
		this.needsRecipeUpdate = false;
		this.needsOutputUpdate = true;
	}
	
	/** Called at the start of a tick when the output inventory has changed, or if the recipe cache has updated**/
	public void updateOutput()
	{
		this.isRoomToCook = this.checkIfRoomToCook();
		this.needsOutputUpdate = false;
	}
	
	public boolean checkIfRoomToCook()
	{
		// make copy of output slots
		int slots = this.output.getSlots();
		ItemStackHandler outputSimulator = new ItemStackHandler(slots);
		for (int slot=0; slot<slots; slot++)
		{
			outputSimulator.setStackInSlot(slot, this.output.getStackInSlot(slot).copy());
		}
		
		// see if we can fill every output slot
		// items with container items almost always have a stack size of 1
		// we can generally assume that they can be placed back into the input slots
		// so we only need to be concerned with the canonical output
		for (IRecipe<ClaimableRecipeWrapper> recipe : this.cachedRecipes.getRecipes())
		{
			ItemStack result = recipe.getCraftingResult(this.cachedRecipes).copy();
			for (int slot=0; slot < slots && !result.isEmpty(); slot++)
			{
				result = outputSimulator.insertItem(slot, result, false);
			}
			// if we couldn't fit the result in the output, no room to cook
			if (!result.isEmpty())
			{
				return false;
			}
		}
		
		// if we made it this far, all recipe results have room
		return true;
	}
	
	public void updateFuel()
	{
		this.canConsumeFuel = this.checkIfCanConsumeFuel();
		this.needsFuelUpdate = false;
	}
	
	public boolean checkIfCanConsumeFuel()
	{
		int slots = this.fuel.getSlots();
		for (int slot=0; slot<slots; slot++)
		{
			if (ForgeHooks.getBurnTime(this.fuel.getStackInSlot(slot)) > 0)
			{
				return true;
			}
		}
		
		return false;
	}

	@Override
	public void tick()
	{
		// if burning, decrement burn time
		boolean wasBurningBeforeTick = this.isBurning();
		if (wasBurningBeforeTick)
		{
			this.burnTimeRemaining -= this.getBurnConsumption();
			this.markDirty();
		}
		
		if (!this.world.isRemote)
		{
			// reinform self of own state if inventories have changed
			if (this.needsRecipeUpdate)
			{
				this.updateRecipes();
			}
			if (this.needsOutputUpdate)
			{
				this.updateOutput();
			}
			if (this.needsFuelUpdate)
			{
				this.updateFuel();
			}
			
			boolean hasSmeltableInputs = this.cachedRecipes.getRecipeCount() > 0;
			
			// if burning, or if it can consume fuel and has a smeltable input
			if (this.isBurning() || (this.canConsumeFuel && hasSmeltableInputs))
			{
				// if not burning but can start cooking
				// this also implies that we can consume fuel
				if (!this.isBurning() && hasSmeltableInputs)
				{
					// consume fuel and start burning
					this.consumeFuel();
				}
				
				// if burning and has smeltable inputs
				if (this.isBurning() && hasSmeltableInputs)
				{
					// increase cook progress
					this.cookProgress++;
					
					// if cook progress is complete, reset cook progress and do crafting
					if (this.cookProgress >= JumboFurnace.SERVER_CONFIG.jumboFurnaceCookTime.get())
					{
						this.cookProgress = 0;
						this.craft();
					}
					this.markDirty();
				}
				else // otherwise, reset cook progress
				{
					this.cookProgress = 0;
					this.markDirty();
				}
			}
			// otherwise, if not burning but has cookprogress, reduce cook progress
			else if (!this.isBurning() && this.cookProgress > 0)
			{
				if (hasSmeltableInputs)
				{
					this.cookProgress = Math.max(0, this.cookProgress - 2);
				}
				else
				{
					this.cookProgress = 0;
				}
				this.markDirty();
			}
			
			boolean isBurningAfterTick = this.isBurning();
			
			// if burning state changed since tick started, update furnace blockstates
			if (isBurningAfterTick != wasBurningBeforeTick)
			{
				this.updateBurningBlockstates(isBurningAfterTick);
			}
			
			
		}
	}
	
	public void consumeFuel()
	{
		int slots = this.fuel.getSlots();
		for (int slot=0; slot<slots; slot++)
		{
			ItemStack stackInSlot = this.fuel.extractItem(slot, 1, true);
			int burnTime = ForgeHooks.getBurnTime(stackInSlot);
			if (burnTime > 0)
			{
				this.lastItemBurnedValue = burnTime;
				this.fuel.extractItem(slot, 1, false);
				this.burnTimeRemaining += burnTime;
				// setStackInSlot ignores isItemValidForSlot, so we won't lose the container item here
				// TODO we'd lose a stack if someone makes a stackable item with a container item, though
				if (stackInSlot.hasContainerItem())
				{
					this.fuel.setStackInSlot(slot, stackInSlot.getContainerItem());
				}
				break;
			}
		}
	}
	
	public void craft()
	{
		// we have safeguards to make sure there's room for everything
		// but in case we're wrong, make a failsafe for items we can't put somewhere else
		// and eject them instead of just deleting them
		List<ItemStack> extraItems = new ArrayList<>();
		// put results in output
		List<IRecipe<ClaimableRecipeWrapper>> recipes = this.cachedRecipes.getRecipes();
		IItemHandler unusedInputs = this.cachedRecipes.getUnusedInputs();
		int unusedInputSlots = unusedInputs.getSlots();
		for (IRecipe<ClaimableRecipeWrapper> recipe : recipes)
		{
			ItemStack result = recipe.getCraftingResult(this.cachedRecipes);
			int outputSlots = this.output.getSlots();
			int resultCount = result.getCount();
			for (int slot=0; slot<outputSlots && !result.isEmpty(); slot++)
			{
				int oldCount = result.getCount();
				result = this.output.insertCraftResult(slot, result, false);
				int newCount = result.getCount();
				int itemsInserted = oldCount - newCount;
				float experience = ((float)itemsInserted / (float)resultCount) * (recipe instanceof JumboFurnaceRecipe ? ((JumboFurnaceRecipe)recipe).experience : 0F);
				this.output.addExperience(slot, experience);
			}
			if (!result.isEmpty())
			{
				extraItems.add(result);
			}
			for (ItemStack stack : recipe.getRemainingItems(this.cachedRecipes))
			{
				ItemStack containerItem = stack.copy();
				for (int slot=0; slot<unusedInputSlots && !containerItem.isEmpty(); slot++)
				{
					containerItem = unusedInputs.insertItem(slot, containerItem, false);
				}
				if (!containerItem.isEmpty())
				{
					extraItems.add(stack.copy());
				}
			}
			
		}
		
		// replace inputs with unused inputs + container items
		for (int slot=0; slot<unusedInputSlots; slot++)
		{
			this.input.setStackInSlot(slot, unusedInputs.getStackInSlot(slot));
		}
		
		// if we didn't have room for items, just yeet them
		// check for possible hoppers, etc first though
		TileEntity te = this.world.getTileEntity(this.pos.down(2));
		
		for (ItemStack stack : extraItems)
		{
			ItemStack stackCopy = stack.copy();
			if (te != null)
			{
				stackCopy = te.getCapability(CapabilityItemHandler.ITEM_HANDLER_CAPABILITY, Direction.UP).map(handler ->
				{
					ItemStack innerStackCopy = stack.copy();
					int slots = handler.getSlots();
					for (int slot=0; slot<slots; slot++)
					{
						innerStackCopy = handler.insertItem(slot, innerStackCopy, false);
					}
					return innerStackCopy;
				})
				.orElse(stackCopy);
			}
			if (!stackCopy.isEmpty())
			{
				InventoryHelper.spawnItemStack(this.world, this.pos.getX()+0.5, this.pos.getY() - 1, this.pos.getZ() + 0.5, stack);
			}
		}
	}
	

}