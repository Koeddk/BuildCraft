package buildcraft.factory.tile;

import java.io.IOException;
import java.util.List;

import net.minecraft.block.state.IBlockState;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumFacing;
import net.minecraft.util.EnumHand;
import net.minecraft.util.ITickable;

import net.minecraftforge.fluids.Fluid;
import net.minecraftforge.fluids.FluidStack;
import net.minecraftforge.fml.common.network.simpleimpl.MessageContext;
import net.minecraftforge.fml.relauncher.Side;
import net.minecraftforge.fml.relauncher.SideOnly;

import buildcraft.api.core.EnumPipePart;
import buildcraft.api.core.SafeTimeTracker;
import buildcraft.api.mj.MjAPI;
import buildcraft.api.mj.MjBattery;
import buildcraft.api.mj.MjCapabilityHelper;
import buildcraft.api.recipes.BuildcraftRecipeRegistry;
import buildcraft.api.recipes.IRefineryRecipeManager.IDistillationRecipe;
import buildcraft.api.tiles.IDebuggable;

import buildcraft.core.BCCoreConfig;
import buildcraft.factory.BCFactoryBlocks;
import buildcraft.lib.block.BlockBCBase_Neptune;
import buildcraft.lib.expression.DefaultContexts;
import buildcraft.lib.expression.FunctionContext;
import buildcraft.lib.expression.node.value.NodeVariableBoolean;
import buildcraft.lib.expression.node.value.NodeVariableLong;
import buildcraft.lib.expression.node.value.NodeVariableString;
import buildcraft.lib.fluid.Tank;
import buildcraft.lib.fluid.TankManager;
import buildcraft.lib.misc.CapUtil;
import buildcraft.lib.misc.LocaleUtil;
import buildcraft.lib.misc.data.AverageLong;
import buildcraft.lib.misc.data.ModelVariableData;
import buildcraft.lib.mj.MjBatteryReciver;
import buildcraft.lib.net.PacketBufferBC;
import buildcraft.lib.tile.TileBC_Neptune;

public class TileDistiller_BC8 extends TileBC_Neptune implements ITickable, IDebuggable {
    public static final FunctionContext MODEL_FUNC_CTX;
    private static final NodeVariableString MODEL_FACING;
    private static final NodeVariableBoolean MODEL_ACTIVE;
    private static final NodeVariableLong MODEL_POWER_AVG;
    private static final NodeVariableLong MODEL_POWER_MAX;

    static {
        MODEL_FUNC_CTX = DefaultContexts.createWithAll();
        MODEL_FACING = MODEL_FUNC_CTX.putVariableString("facing");
        MODEL_POWER_AVG = MODEL_FUNC_CTX.putVariableLong("power_average");
        MODEL_POWER_MAX = MODEL_FUNC_CTX.putVariableLong("power_max");
        MODEL_ACTIVE = MODEL_FUNC_CTX.putVariableBoolean("active");
    }

    public static final long MAX_MJ_PER_TICK = 6 * MjAPI.MJ;

    public final Tank tankIn = new Tank("in", 4 * Fluid.BUCKET_VOLUME, this);
    public final Tank tankOutGas = new Tank("out_gas", 4 * Fluid.BUCKET_VOLUME, this);
    public final Tank tankOutLiquid = new Tank("out_liquid", 4 * Fluid.BUCKET_VOLUME, this);
    public final TankManager<Tank> tankManager = new TankManager<>(tankIn, tankOutGas, tankOutLiquid);

    private final MjBattery mjBattery = new MjBattery(1024 * MjAPI.MJ);
    private final MjBatteryReciver mjBatteryReceiver = new MjBatteryReciver(mjBattery);
    private final MjCapabilityHelper mjCapHelper = new MjCapabilityHelper(mjBatteryReceiver);

    /** The model variables, used to keep track of the various state-based variables. */
    public final ModelVariableData clientModelData = new ModelVariableData();

    private IDistillationRecipe currentRecipe;
    private long distillPower = 0;
    private boolean isActive = false;
    private final AverageLong powerAvg = new AverageLong(100);
    private final SafeTimeTracker updateTracker = new SafeTimeTracker(BCCoreConfig.networkUpdateRate, 2);
    private boolean changedSinceNetUpdate = true;

    private long powerAvgClient;

    public TileDistiller_BC8() {
        tankIn.setCanDrain(false);
        tankOutGas.setCanFill(false);
        tankOutLiquid.setCanFill(false);

        caps.addCapabilityInstance(CapUtil.CAP_FLUIDS, tankIn, EnumPipePart.HORIZONTALS);
        caps.addCapabilityInstance(CapUtil.CAP_FLUIDS, tankOutGas, EnumPipePart.UP);
        caps.addCapabilityInstance(CapUtil.CAP_FLUIDS, tankOutLiquid, EnumPipePart.DOWN);
        caps.addProvider(mjCapHelper);
    }

    @Override
    public NBTTagCompound writeToNBT(NBTTagCompound nbt) {
        super.writeToNBT(nbt);
        nbt.setTag("tanks", tankManager.serializeNBT());
        nbt.setTag("mjBattery", mjBattery.serializeNBT());
        nbt.setLong("distillPower", distillPower);
        powerAvg.writeToNbt(nbt, "powerAvg");
        return nbt;
    }

    @Override
    public void readFromNBT(NBTTagCompound nbt) {
        super.readFromNBT(nbt);
        tankManager.deserializeNBT(nbt.getCompoundTag("tanks"));
        mjBattery.deserializeNBT(nbt.getCompoundTag("mjBattery"));
        distillPower = nbt.getLong("distillPower");
        powerAvg.readFromNbt(nbt, "powerAvg");
    }

    @Override
    public void writePayload(int id, PacketBufferBC buffer, Side side) {
        super.writePayload(id, buffer, side);
        if (side == Side.SERVER) {
            if (id == NET_RENDER_DATA) {
                tankManager.writeData(buffer);
                buffer.writeBoolean(isActive);
                powerAvgClient = powerAvg.getAverageLong();
                final long div = MjAPI.MJ / 2;
                powerAvgClient = Math.round(powerAvgClient / (double) div) * div;
                buffer.writeLong(powerAvgClient);
            }
        }
    }

    @Override
    public void readPayload(int id, PacketBufferBC buffer, Side side, MessageContext ctx) throws IOException {
        super.readPayload(id, buffer, side, ctx);
        if (side == Side.CLIENT) {
            if (id == NET_RENDER_DATA) {
                tankManager.readData(buffer);
                isActive = buffer.readBoolean();
                powerAvgClient = buffer.readLong();
            }
        }
    }

    public static void setClientModelVariablesForItem() {
        DefaultContexts.RENDER_PARTIAL_TICKS.value = 1;
        MODEL_ACTIVE.value = false;
        MODEL_POWER_AVG.value = 0;
        MODEL_POWER_MAX.value = 6;
        MODEL_FACING.value = "west";
    }

    public void setClientModelVariables(float partialTicks) {
        DefaultContexts.RENDER_PARTIAL_TICKS.value = partialTicks;

        MODEL_ACTIVE.value = isActive;
        MODEL_POWER_AVG.value = powerAvgClient / MjAPI.MJ;
        MODEL_POWER_MAX.value = MAX_MJ_PER_TICK / MjAPI.MJ;
        MODEL_FACING.value = "west";

        IBlockState state = world.getBlockState(getPos());
        if (state.getBlock() == BCFactoryBlocks.distiller) {
            MODEL_FACING.value = state.getValue(BlockBCBase_Neptune.PROP_FACING).getName();
        }
    }

    public boolean onActivated(EntityPlayer player, EnumHand hand, EnumFacing facing, float hitX, float hitY, float hitZ) {
        return false;
    }

    @Override
    public void update() {
        if (world.isRemote) {
            setClientModelVariables(1);
            clientModelData.tick();
            return;
        }
        long avgNow = powerAvg.getAverageLong();
        powerAvg.tick();
        changedSinceNetUpdate |= avgNow / MjAPI.MJ != powerAvg.getAverageLong() / MjAPI.MJ;

        currentRecipe = BuildcraftRecipeRegistry.refineryRecipes.getDistilationRegistry().getRecipeForInput(tankIn.getFluid());
        if (currentRecipe == null) {
            mjBattery.addPowerChecking(distillPower, false);
            distillPower = 0;
            isActive = false;
        } else {
            FluidStack reqIn = currentRecipe.in();
            FluidStack outLiquid = currentRecipe.outLiquid();
            FluidStack outGas = currentRecipe.outGas();

            FluidStack potentialIn = tankIn.drainInternal(reqIn, false);
            boolean canExtract = reqIn.isFluidStackIdentical(potentialIn);

            boolean canFillLiquid = tankOutLiquid.fillInternal(outLiquid, false) == outLiquid.amount;
            boolean canFillGas = tankOutGas.fillInternal(outGas, false) == outGas.amount;

            if (canExtract && canFillLiquid && canFillGas) {
                long max = MAX_MJ_PER_TICK;
                max *= mjBattery.getStored() + max;
                max /= mjBattery.getCapacity() / 2;
                max = Math.min(max, MAX_MJ_PER_TICK);
                long powerReq = currentRecipe.powerRequired();
                long power = mjBattery.extractPower(0, max);
                powerAvg.push(max);
                distillPower += power;
                isActive = power > 0;
                if (distillPower >= powerReq) {
                    isActive = true;
                    distillPower -= powerReq;
                    tankIn.drainInternal(reqIn, true);
                    tankOutGas.fillInternal(outGas, true);
                    tankOutLiquid.fillInternal(outLiquid, true);
                    changedSinceNetUpdate = true;
                }
            } else {
                mjBattery.addPowerChecking(distillPower, false);
                distillPower = 0;
                isActive = false;
            }
        }

        if (updateTracker.markTimeIfDelay(getWorld())) {
            sendNetworkUpdate(NET_RENDER_DATA);
        }
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void getDebugInfo(List<String> left, List<String> right, EnumFacing side) {
        left.add("");
        left.add("In = " + tankIn.getDebugString());
        left.add("OutGas = " + tankOutGas.getDebugString());
        left.add("OutLiquid = " + tankOutLiquid.getDebugString());
        left.add("Battery = " + mjBattery.getDebugString());
        left.add("Progress = " + MjAPI.formatMj(distillPower));
        left.add("Rate = " + LocaleUtil.localizeMjFlow(powerAvgClient));
        left.add("CurrRecipe = " + currentRecipe);
        if (world.isRemote) {
            setClientModelVariables(1);
            left.add("Model Variables:");
            left.add("  facing = " + MODEL_FACING.value);
            left.add("  active = " + MODEL_ACTIVE.value);
            left.add("  power_average = " + MODEL_POWER_AVG.value);
            left.add("  power_max = " + MODEL_POWER_MAX.value);
            left.add("Current Model Variables:");
            clientModelData.refresh();
            clientModelData.addDebugInfo(left);
        }
    }
}
