package net.commoble.jumbofurnace.recipes;

import java.util.List;
import java.util.function.Function;

import com.mojang.datafixers.util.Either;
import com.mojang.serialization.Codec;
import com.mojang.serialization.DataResult;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.commoble.jumbofurnace.JumboFurnace;
import net.commoble.jumbofurnace.jumbo_furnace.JumboFurnaceMenu;
import net.minecraft.core.NonNullList;
import net.minecraft.core.RegistryAccess;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.util.ExtraCodecs;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeSerializer;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.item.crafting.SmeltingRecipe;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.common.crafting.CraftingHelper;
import net.neoforged.neoforge.items.IItemHandler;

public record JumboFurnaceRecipe(String group, NonNullList<Ingredient> ingredients, List<ItemStack> results, float experience) implements Recipe<ClaimableRecipeWrapper>
{
	public static final Codec<JumboFurnaceRecipe> CODEC = RecordCodecBuilder.create(builder -> builder.group(
			ExtraCodecs.strictOptionalField(Codec.STRING, "group", "").forGetter(JumboFurnaceRecipe::group),
			NonNullList.codecOf(Ingredient.CODEC_NONEMPTY)
				.comapFlatMap(JumboFurnaceRecipe::validateIngredients, Function.identity()).fieldOf("ingredients").forGetter(JumboFurnaceRecipe::ingredients),
			// we accept either a "results" results list or a "result" single result
			Codec.mapEither(
					CraftingHelper.smeltingResultCodec().listOf().fieldOf("results"),
					CraftingHelper.smeltingResultCodec().fieldOf("result"))
				.flatXmap(JumboFurnaceRecipe::readResults, JumboFurnaceRecipe::writeResults)
				.forGetter(JumboFurnaceRecipe::results),
			Codec.FLOAT.fieldOf("experience").orElse(0.0F).forGetter(JumboFurnaceRecipe::experience)
		).apply(builder, JumboFurnaceRecipe::new));
	
	public static DataResult<NonNullList<Ingredient>> validateIngredients(NonNullList<Ingredient> ingredients)
	{
		int size = ingredients.size();
		if (size < 1)
		{
			return DataResult.error(() -> "No ingredients for jumbo smelting recipe");
		}
		if (size > JumboFurnaceMenu.INPUT_SLOTS)
		{
			return DataResult.error(() -> "Too many ingredients for jumbo smelting recipe! the max is " + (JumboFurnaceMenu.INPUT_SLOTS));
		}
		return DataResult.success(ingredients);
	}
	
	public static DataResult<List<ItemStack>> readResults(Either<List<ItemStack>,ItemStack> either)
	{
		return either.map(
			list -> list.isEmpty() ? DataResult.error(() -> "Empty result list for jumbo smelting recipe") : DataResult.success(list),
			itemStack -> DataResult.success(List.of(itemStack)));
	}
	
	public static DataResult<Either<List<ItemStack>,ItemStack>> writeResults(List<ItemStack> results)
	{
		return results.isEmpty() ? DataResult.error(() -> "Empty result list for jumbo smelting recipe")
			: results.size() == 1 ? DataResult.success(Either.right(results.get(0)))
			: DataResult.success(Either.left(results));
			
	}
	
	/** Wrapper around regular furnace recipes to make single-input jumbo furnace recipes **/
	public JumboFurnaceRecipe(SmeltingRecipe baseRecipe)
	{
		this(baseRecipe.getGroup(), baseRecipe.getIngredients(), List.of(baseRecipe.result.copy()), baseRecipe.getExperience());
	}
	
	@Override
	public NonNullList<Ingredient> getIngredients()
	{
		return this.ingredients;
	}

	@Override
	public boolean matches(ClaimableRecipeWrapper inv, Level worldIn)
	{
		IItemHandler unusedInputs = inv.getUnusedInputs();
		for (Ingredient ingredient : this.getIngredients())
		{
			int amountOfIngredient = ingredient.getItems()[0].getCount();
			int slots = unusedInputs.getSlots();
			for (int slot=0; slot < slots && amountOfIngredient > 0; slot++)
			{
				ItemStack stackInSlot = unusedInputs.getStackInSlot(slot);
				if (ingredient.test(stackInSlot) && stackInSlot.getCount() >= amountOfIngredient)
				{
					ItemStack usedStack = unusedInputs.extractItem(slot, amountOfIngredient, false);
					amountOfIngredient -= usedStack.getCount();
				}
			}
			
			// if we didn't fully match the ingredient, return false
			if (amountOfIngredient > 0)
			{
				return false;
			}
		}
		
		// if we made it this far, all ingredients matched, so return true
		return true;
	}

	@Override
	public ItemStack assemble(ClaimableRecipeWrapper inv, RegistryAccess registries)
	{
		return this.results.get(0).copy();
	}

	@Override
	public boolean canCraftInDimensions(int width, int height)
	{
		return true;
	}

	@Override
	public ItemStack getResultItem(RegistryAccess registries)
	{
		return this.results.get(0);
	}

	@Override
	public RecipeSerializer<?> getSerializer()
	{
		return JumboFurnace.get().jumboSmeltingRecipeSerializer.get();
	}

	@Override
	public RecipeType<?> getType()
	{
		return JumboFurnace.get().jumboSmeltingRecipeType.get();
	}
	
	@Override
	public boolean isSpecial()
	{
		return true;
	}
	
	public int getSpecificity()
	{
		int specificity = (int)(this.experience*10);
		int totalItems = BuiltInRegistries.ITEM.size();
		for (Ingredient ingredient : this.ingredients)
		{
			ItemStack[] matchingStacks = ingredient.getItems();
			if(matchingStacks.length < 1)
			{
				continue;
			}
			// safe to assume that ingredient has at least one matching stack, and at least two items are registered to forge
			int matchingItems = Math.min(totalItems, matchingStacks.length);
			
			// this equation gives a value of 1D when matchingitems = 1, and 0D when matchingItems = totalItems
			double matchFactor = (double)(totalItems - matchingItems) / (double)(totalItems - 1);
			
			int ingredientWeight = (int)(100D * matchingStacks[0].getCount() * matchFactor);
			specificity += ingredientWeight;
		}
		return specificity;
	}

	@Override
	public NonNullList<ItemStack> getRemainingItems(ClaimableRecipeWrapper inv)
	{
		IItemHandler items = inv.getItemsBeingSmelted();
		int slots = items.getSlots();
		NonNullList<ItemStack> containerItems = NonNullList.withSize(items.getSlots(), ItemStack.EMPTY);
		for (int slot=0; slot<slots; slot++)
		{
			ItemStack stackInSlot = items.getStackInSlot(slot);
			if (stackInSlot.hasCraftingRemainingItem())
			{
				containerItems.set(slot, stackInSlot.getCraftingRemainingItem());
			}
		}
		
		return containerItems;
	}

}
