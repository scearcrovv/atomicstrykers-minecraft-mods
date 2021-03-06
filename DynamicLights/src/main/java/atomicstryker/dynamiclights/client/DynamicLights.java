package atomicstryker.dynamiclights.client;

import java.lang.reflect.Field;
import java.lang.reflect.ParameterizedType;
import java.util.Iterator;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentSkipListSet;

import org.lwjgl.input.Keyboard;

import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.WorldClient;
import net.minecraft.client.renderer.RenderGlobal;
import net.minecraft.client.settings.KeyBinding;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.text.TextComponentTranslation;
import net.minecraft.world.EnumSkyBlock;
import net.minecraft.world.IBlockAccess;
import net.minecraft.world.World;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.common.config.Configuration;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.client.registry.ClientRegistry;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.common.Mod.EventHandler;
import net.minecraftforge.fml.common.Mod.Instance;
import net.minecraftforge.fml.common.event.FMLInitializationEvent;
import net.minecraftforge.fml.common.event.FMLPreInitializationEvent;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent.Phase;

/**
 * 
 * @author AtomicStryker
 * 
 *         Rewritten and now-awesome Dynamic Lights Mod.
 * 
 *         Instead of the crude base edits and inefficient giant loops of the
 *         original, this Mod uses ASM transforming to hook into Minecraft with
 *         style and has an API that does't suck. It also uses Forge events to
 *         register dropped Items.
 *
 */
@Mod(modid = "dynamiclights", name = "Dynamic Lights", version = "1.4.9", clientSideOnly = true, dependencies="required-after:forge@[14.23.3.2698,)")
public class DynamicLights
{
    private Minecraft mcinstance;

    @Instance("dynamiclights")
    private static DynamicLights instance;

    /*
     * Optimization - instead of repeatedly getting the same List for the same
     * World, just check once for World being equal.
     */
    private IBlockAccess lastWorld;
    private ConcurrentLinkedQueue<DynamicLightSourceContainer> lastList;

    /**
     * This Map contains a List of DynamicLightSourceContainer for each World.
     * Since the client can only be in a single World, the other Lists just
     * float idle when unused.
     */
    private ConcurrentHashMap<World, ConcurrentLinkedQueue<DynamicLightSourceContainer>> worldLightsMap;

    /**
     * Keeps track of the toggle button.
     */
    private boolean globalLightsOff;

    /**
     * The Keybinding instance to monitor
     */
    private KeyBinding toggleButton;
    private long nextKeyTriggerTime;
    private static boolean hackingRenderFailed;

    /**
     * Configuration for "global" dynamic lights settings
     */
    private Configuration config;
    private int[] bannedDimensions;

    @EventHandler
    public void preInit(FMLPreInitializationEvent evt)
    {
        globalLightsOff = false;
        mcinstance = FMLClientHandler.instance().getClient();
        worldLightsMap = new ConcurrentHashMap<>();
        MinecraftForge.EVENT_BUS.register(this);
        nextKeyTriggerTime = System.currentTimeMillis();
        hackingRenderFailed = false;
        config = new Configuration(evt.getSuggestedConfigurationFile());
        config.load();
        bannedDimensions = config.get(Configuration.CATEGORY_CLIENT, "bannedDimensionIDs", new int[] { -1 }).getIntList();
        config.save();
    }

    @EventHandler
    public void load(FMLInitializationEvent evt)
    {
        toggleButton = new KeyBinding("Dynamic Lights toggle", Keyboard.KEY_L, "key.categories.gameplay");
        ClientRegistry.registerKeyBinding(toggleButton);
    }

    @SubscribeEvent
    public void onTick(TickEvent.ClientTickEvent tick)
    {
        if (tick.phase == Phase.END && mcinstance.world != null)
        {
            ConcurrentLinkedQueue<DynamicLightSourceContainer> worldLights = worldLightsMap.get(mcinstance.world);

            if (worldLights != null)
            {
                Iterator<DynamicLightSourceContainer> iter = worldLights.iterator();
                while (iter.hasNext())
                {
                    DynamicLightSourceContainer tickedLightContainer = iter.next();
                    if (tickedLightContainer.onUpdate())
                    {
                        iter.remove();
                        mcinstance.world.checkLightFor(EnumSkyBlock.BLOCK, new BlockPos(tickedLightContainer.getX(), tickedLightContainer.getY(), tickedLightContainer.getZ()));
                        // System.out.println("Dynamic Lights killing off
                        // LightSource on dead Entity
                        // "+tickedLightContainer.getLightSource().getAttachmentEntity());
                    }
                }
            }

            if (mcinstance.currentScreen == null && toggleButton.isPressed() && System.currentTimeMillis() >= nextKeyTriggerTime)
            {
                nextKeyTriggerTime = System.currentTimeMillis() + 1000L;
                globalLightsOff = !globalLightsOff;
                mcinstance.ingameGUI.getChatGUI().printChatMessage(new TextComponentTranslation("Dynamic Lights globally " + (globalLightsOff ? "off" : "on")));

                World world = mcinstance.world;
                if (world != null)
                {
                    if (worldLights != null)
                    {
                        for (DynamicLightSourceContainer c : worldLights)
                        {
                            world.checkLightFor(EnumSkyBlock.BLOCK, new BlockPos(c.getX(), c.getY(), c.getZ()));
                        }
                    }
                }
            }
        }
    }

    /**
     * Used not only to toggle the Lights, but any Ticks in the sub-modules
     * 
     * @return true when all computation and tracking should be suspended, false
     *         otherwise
     */
    public static boolean globalLightsOff()
    {
        return instance.globalLightsOff;
    }

    /**
     * Exposed method which is called by the transformed World.getRawLight
     * method instead of Block.getLightValue. Loops active Dynamic Light Sources
     * and if it finds one for the exact coordinates asked, returns the Light
     * value from that source if higher.
     * 
     * @param block
     *            block queried
     * @param blockState
     *            IBlockState queried
     * @param world
     *            World queried
     * @param pos
     *            BlockPos instance of target coords
     * @return max(Block.getLightValue, Dynamic Light)
     */
    @SuppressWarnings("unused")
    public static int getLightValue(Block block, IBlockState blockState, IBlockAccess world, BlockPos pos)
    {
        int vanillaValue = block.getLightValue(blockState, world, pos);

        if (instance == null || instance.globalLightsOff || !(world instanceof WorldClient))
        {
            return vanillaValue;
        }

        if (!world.equals(instance.lastWorld) || instance.lastList == null)
        {
            instance.lastWorld = world;
            instance.lastList = instance.worldLightsMap.get(world);
            hackRenderGlobalConcurrently();
        }

        int dynamicValue = 0;
        if (instance.lastList != null && !instance.lastList.isEmpty())
        {
            for (DynamicLightSourceContainer light : instance.lastList)
            {
                if (light.getX() == pos.getX())
                {
                    if (light.getY() == pos.getY())
                    {
                        if (light.getZ() == pos.getZ())
                        {
                            dynamicValue = Math.max(dynamicValue, light.getLightSource().getLightLevel());
                        }
                    }
                }
            }
        }
        return Math.max(vanillaValue, dynamicValue);
    }

    @SuppressWarnings("unchecked")
    private static void hackRenderGlobalConcurrently()
    {
        if (hackingRenderFailed || instance.isBannedDimension(Minecraft.getMinecraft().player.dimension))
        {
            return;
        }

        for (Field f : RenderGlobal.class.getDeclaredFields())
        {
            if (Set.class.isAssignableFrom(f.getType()))
            {
                ParameterizedType fieldType = (ParameterizedType) f.getGenericType();
                if (BlockPos.class.equals(fieldType.getActualTypeArguments()[0]))
                {
                    try
                    {
                        f.setAccessible(true);
                        Set<BlockPos> setLightUpdates = (Set<BlockPos>) f.get(instance.mcinstance.renderGlobal);
                        if (setLightUpdates instanceof ConcurrentSkipListSet)
                        {
                            return;
                        }
                        ConcurrentSkipListSet<BlockPos> cs = new ConcurrentSkipListSet<>(setLightUpdates);
                        f.set(instance.mcinstance.renderGlobal, cs);
                        System.out.println("Dynamic Lights successfully hacked Set RenderGlobal.setLightUpdates and replaced it with a ConcurrentSkipListSet!");
                        return;
                    }
                    catch (Exception e)
                    {
                        e.printStackTrace();
                    }
                }
            }
        }
        System.out.println("Dynamic Lights completely failed to hack Set RenderGlobal.setLightUpdates and will not try again!");
        hackingRenderFailed = true;
    }

    /**
     * Exposed method to register active Dynamic Light Sources with. Does all
     * the necessary checks, prints errors if any occur, creates new World
     * entries in the worldLightsMap
     * 
     * @param lightToAdd
     *            IDynamicLightSource to register
     */
    public static void addLightSource(IDynamicLightSource lightToAdd)
    {
        if (lightToAdd.getAttachmentEntity() != null)
        {
            // System.out.printf("Calling addLightSource on entity %s, world %s,
            // dimension %d\n", lightToAdd.getAttachmentEntity(),
            // lightToAdd.getAttachmentEntity().world.getWorldInfo().getWorldName(),
            // lightToAdd.getAttachmentEntity().dimension);
            if (lightToAdd.getAttachmentEntity().isEntityAlive() && !instance.isBannedDimension(lightToAdd.getAttachmentEntity().dimension))
            {
                DynamicLightSourceContainer newLightContainer = new DynamicLightSourceContainer(lightToAdd);
                ConcurrentLinkedQueue<DynamicLightSourceContainer> lightList = instance.worldLightsMap.get(lightToAdd.getAttachmentEntity().world);
                if (lightList != null)
                {
                    if (!lightList.contains(newLightContainer))
                    {
                        // System.out.println("Successfully registered
                        // DynamicLight on Entity:" +
                        // newLightContainer.getLightSource().getAttachmentEntity()
                        // + "in list " + lightList);
                        lightList.add(newLightContainer);
                    }
                    else
                    {
                        System.out.println("Cannot add Dynamic Light: Attachment Entity is already registered!");
                    }
                }
                else
                {
                    lightList = new ConcurrentLinkedQueue<>();
                    lightList.add(newLightContainer);
                    instance.worldLightsMap.put(lightToAdd.getAttachmentEntity().world, lightList);
                }
            }
            else
            {
                System.err.println("Cannot add Dynamic Light: Attachment Entity is dead or in a banned dimension!");
            }
        }
        else
        {
            System.err.println("Cannot add Dynamic Light: Attachment Entity is null!");
        }
    }

    /**
     * Exposed method to remove active Dynamic Light sources with. If it fails
     * for whatever reason, it does so quietly.
     * 
     * @param lightToRemove
     *            IDynamicLightSource you want removed.
     */
    public static void removeLightSource(IDynamicLightSource lightToRemove)
    {
        if (lightToRemove != null && lightToRemove.getAttachmentEntity() != null)
        {
            World world = lightToRemove.getAttachmentEntity().world;
            if (world != null)
            {
                DynamicLightSourceContainer iterContainer = null;
                ConcurrentLinkedQueue<DynamicLightSourceContainer> lightList = instance.worldLightsMap.get(world);
                if (lightList != null)
                {
                    Iterator<DynamicLightSourceContainer> iter = lightList.iterator();
                    while (iter.hasNext())
                    {
                        iterContainer = iter.next();
                        if (iterContainer.getLightSource().equals(lightToRemove))
                        {
                            iter.remove();
                            break;
                        }
                    }

                    if (iterContainer != null)
                    {
                        world.checkLightFor(EnumSkyBlock.BLOCK, new BlockPos(iterContainer.getX(), iterContainer.getY(), iterContainer.getZ()));
                    }
                }
            }
        }
    }

    /**
     * getter for the global configuration, to be used with instance
     */
    public Configuration getConfiguration()
    {
        return config;
    }

    /**
     * is a given dimension id on the banned list and will not receive dynamic
     * lighting
     */
    public boolean isBannedDimension(int dimensionID)
    {
        for (int i : bannedDimensions)
        {
            if (i == dimensionID)
            {
                return true;
            }
        }
        return false;
    }
}
