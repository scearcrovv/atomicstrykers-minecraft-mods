package atomicstryker.infernalmobs.client;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import javax.annotation.Nullable;

import org.lwjgl.opengl.GL11;

import com.google.common.base.Predicate;
import com.google.common.base.Predicates;

import atomicstryker.infernalmobs.common.ISidedProxy;
import atomicstryker.infernalmobs.common.InfernalMobsCore;
import atomicstryker.infernalmobs.common.MobModifier;
import atomicstryker.infernalmobs.common.mods.MM_Gravity;
import atomicstryker.infernalmobs.common.network.HealthPacket;
import atomicstryker.infernalmobs.common.network.MobModsPacket;
import net.minecraft.block.material.Material;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.FontRenderer;
import net.minecraft.client.gui.Gui;
import net.minecraft.client.gui.GuiIngame;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.EntityMob;
import net.minecraft.entity.monster.IMob;
import net.minecraft.util.EntitySelectors;
import net.minecraft.util.math.AxisAlignedBB;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.minecraftforge.client.event.RenderGameOverlayEvent;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.entity.EntityJoinWorldEvent;
import net.minecraftforge.fml.client.FMLClientHandler;
import net.minecraftforge.fml.common.eventhandler.SubscribeEvent;
import net.minecraftforge.fml.common.gameevent.TickEvent;

public class InfernalMobsClient implements ISidedProxy
{
    private final double NAME_VISION_DISTANCE = 32D;
    private Minecraft mc;
    private World lastWorld;
    private long nextPacketTime;
    private ConcurrentHashMap<EntityLivingBase, MobModifier> rareMobsClient;
    private int airOverrideValue = -999;
    private long airDisplayTimeout;

    private long healthBarRetainTime;
    private EntityLivingBase retainedTarget;

    @Override
    public void preInit()
    {
        MinecraftForge.EVENT_BUS.register(this);
        mc = FMLClientHandler.instance().getClient();
    }

    @Override
    public void load()
    {
        nextPacketTime = 0;
        rareMobsClient = new ConcurrentHashMap<>();

        MinecraftForge.EVENT_BUS.register(new RendererBossGlow());
        MinecraftForge.EVENT_BUS.register(this);

        healthBarRetainTime = 0;
        retainedTarget = null;
    }

    @SubscribeEvent
    public void onEntityJoinedWorld(EntityJoinWorldEvent event)
    {
        if (event.getWorld().isRemote && mc.player != null && (event.getEntity() instanceof EntityMob || (event.getEntity() instanceof EntityLivingBase && event.getEntity() instanceof IMob)))
        {
            InfernalMobsCore.instance().networkHelper.sendPacketToServer(new MobModsPacket(mc.player.getName(), event.getEntity().getEntityId(), (byte) 0));
        }
    }

    private void askServerHealth(Entity ent)
    {
        if (System.currentTimeMillis() > nextPacketTime)
        {
            InfernalMobsCore.instance().networkHelper.sendPacketToServer(new HealthPacket(mc.player.getName(), ent.getEntityId(), 0f, 0f));
            nextPacketTime = System.currentTimeMillis() + 100L;
        }
    }

    @SubscribeEvent
    public void onPreRenderGameOverlay(RenderGameOverlayEvent.Pre event)
    {
        if (InfernalMobsCore.instance().getIsHealthBarDisabled() || event.getType() != RenderGameOverlayEvent.ElementType.BOSSHEALTH || mc.ingameGUI.getBossOverlay().shouldPlayEndBossMusic())
        {
            return;
        }

        Entity ent = getEntityCrosshairOver(event.getPartialTicks(), mc);
        boolean retained = false;

        if (ent == null && System.currentTimeMillis() < healthBarRetainTime)
        {
            ent = retainedTarget;
            retained = true;
        }

        if (ent != null && ent instanceof EntityLivingBase)
        {
            MobModifier mod = InfernalMobsCore.getMobModifiers((EntityLivingBase) ent);
            if (mod != null)
            {
                askServerHealth(ent);

                GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
                this.mc.getTextureManager().bindTexture(Gui.ICONS);
                GL11.glDisable(GL11.GL_BLEND);

                EntityLivingBase target = (EntityLivingBase) ent;
                String buffer = mod.getEntityDisplayName(target);

                ScaledResolution resolution = new ScaledResolution(mc);
                int screenwidth = resolution.getScaledWidth();
                FontRenderer fontR = mc.fontRenderer;

                GuiIngame gui = mc.ingameGUI;
                short lifeBarLength = 182;
                int x = screenwidth / 2 - lifeBarLength / 2;

                int lifeBarLeft = (int) (mod.getActualHealth(target) / mod.getActualMaxHealth(target) * (float) (lifeBarLength + 1));
                byte y = 12;
                gui.drawTexturedModalRect(x, y, 0, 74, lifeBarLength, 5);
                gui.drawTexturedModalRect(x, y, 0, 74, lifeBarLength, 5);

                if (lifeBarLeft > 0)
                {
                    gui.drawTexturedModalRect(x, y, 0, 79, lifeBarLeft, 5);
                }

                int yCoord = 10;
                fontR.drawStringWithShadow(buffer, screenwidth / 2 - fontR.getStringWidth(buffer) / 2, yCoord, 0x2F96EB);

                String[] display = mod.getDisplayNames();
                int i = 0;
                while (i < display.length && display[i] != null)
                {
                    yCoord += 10;
                    fontR.drawStringWithShadow(display[i], screenwidth / 2 - fontR.getStringWidth(display[i]) / 2, yCoord, 0xffffff);
                    i++;
                }

                GL11.glColor4f(1.0F, 1.0F, 1.0F, 1.0F);
                this.mc.getTextureManager().bindTexture(Gui.ICONS);

                if (!retained)
                {
                    retainedTarget = target;
                    healthBarRetainTime = System.currentTimeMillis() + 3000L;
                }

            }
        }
    }

    private Entity getEntityCrosshairOver(float partialTicks, Minecraft mc)
    {

        Entity returnedEntity = null;
        Entity viewEntity = mc.getRenderViewEntity();

        if (mc.world != null && viewEntity != null)
        {
            double distance = NAME_VISION_DISTANCE;
            RayTraceResult traceResult = viewEntity.rayTrace(distance, partialTicks);
            Vec3d viewEntEyeVec = viewEntity.getPositionEyes(partialTicks);

            if (traceResult != null)
            {
                distance = traceResult.hitVec.distanceTo(viewEntEyeVec);
            }

            Vec3d lookVector = viewEntity.getLook(1.0F);
            Vec3d viewEntEyeRay = viewEntEyeVec.addVector(lookVector.x * distance, lookVector.y * distance, lookVector.z * distance);
            Vec3d intersectVector = null;
            List<Entity> list = this.mc.world.getEntitiesInAABBexcluding(viewEntity,
                    viewEntity.getEntityBoundingBox().expand(lookVector.x * distance, lookVector.y * distance, lookVector.z * distance).grow(1.0D, 1.0D, 1.0D),
                    Predicates.and(EntitySelectors.NOT_SPECTATING, new Predicate<Entity>()
                    {
                        public boolean apply(@Nullable Entity e)
                        {
                            return e != null && e.canBeCollidedWith();
                        }
                    }));

            for (Entity candidateHit : list)
            {
                AxisAlignedBB axisalignedbb = candidateHit.getEntityBoundingBox().grow((double) candidateHit.getCollisionBorderSize());
                RayTraceResult raytraceresult = axisalignedbb.calculateIntercept(viewEntEyeVec, viewEntEyeRay);

                if (axisalignedbb.contains(viewEntEyeVec))
                {
                    if (distance >= 0.0D)
                    {
                        returnedEntity = candidateHit;
                        intersectVector = raytraceresult == null ? viewEntEyeVec : raytraceresult.hitVec;
                        distance = 0.0D;
                    }
                }
                else if (raytraceresult != null)
                {
                    double d3 = viewEntEyeVec.distanceTo(raytraceresult.hitVec);
                    if (d3 < distance || distance == 0.0D)
                    {
                        if (candidateHit.getLowestRidingEntity() == viewEntity.getLowestRidingEntity() && !candidateHit.canRiderInteract())
                        {
                            if (distance == 0.0D)
                            {
                                returnedEntity = candidateHit;
                                intersectVector = raytraceresult.hitVec;
                            }
                        }
                        else
                        {
                            returnedEntity = candidateHit;
                            intersectVector = raytraceresult.hitVec;
                            distance = d3;
                        }
                    }
                }
            }

            if (returnedEntity != null && viewEntEyeVec.distanceTo(intersectVector) > NAME_VISION_DISTANCE)
            {
                returnedEntity = null;
            }
        }
        return returnedEntity;
    }

    @SubscribeEvent
    public void onTick(TickEvent.RenderTickEvent tick)
    {
        if (mc.world == null || (mc.currentScreen != null && mc.currentScreen.doesGuiPauseGame()))
            return;

        /* client reset in case of swapping worlds */
        if (mc.world != lastWorld)
        {
            boolean newGame = lastWorld == null;
            lastWorld = mc.world;

            if (!newGame)
            {
                InfernalMobsCore.proxy.getRareMobs().clear();
            }
        }
    }

    @Override
    public ConcurrentHashMap<EntityLivingBase, MobModifier> getRareMobs()
    {
        return rareMobsClient;
    }

    @Override
    public void onHealthPacketForClient(int entID, float health, float maxhealth)
    {
        Entity ent = FMLClientHandler.instance().getClient().world.getEntityByID(entID);
        if (ent != null && ent instanceof EntityLivingBase)
        {
            MobModifier mod = InfernalMobsCore.getMobModifiers((EntityLivingBase) ent);
            if (mod != null)
            {
                // System.out.printf("health packet [%f of %f] for %s\n",
                // health, maxhealth, ent);
                mod.setActualHealth(health, maxhealth);
            }
        }
    }

    @Override
    public void onKnockBackPacket(float xv, float zv)
    {
        MM_Gravity.knockBack(FMLClientHandler.instance().getClient().player, xv, zv);
    }

    @Override
    public void onMobModsPacketToClient(String stringData, int entID)
    {
        InfernalMobsCore.instance().addRemoteEntityModifiers(FMLClientHandler.instance().getClient().world, entID, stringData);
    }

    @Override
    public void onVelocityPacket(float xv, float yv, float zv)
    {
        FMLClientHandler.instance().getClient().player.addVelocity(xv, yv, zv);
    }

    @Override
    public void onAirPacket(int air)
    {
        airOverrideValue = air;
        airDisplayTimeout = System.currentTimeMillis() + 3000L;
    }

    @SubscribeEvent
    public void onTick(RenderGameOverlayEvent.Pre event)
    {
        if (System.currentTimeMillis() > airDisplayTimeout) {
            airOverrideValue = -999;
        }
        
        if (event.getType() == RenderGameOverlayEvent.ElementType.AIR)
        {
            if (!mc.player.isInsideOfMaterial(Material.WATER) && airOverrideValue != -999)
            {
                final ScaledResolution res = new ScaledResolution(mc);
                GL11.glEnable(GL11.GL_BLEND);

                int right_height = 39;

                final int left = res.getScaledWidth() / 2 + 91;
                final int top = res.getScaledHeight() - right_height;
                final int full = MathHelper.ceil((double) (airOverrideValue - 2) * 10.0D / 300.0D);
                final int partial = MathHelper.ceil((double) airOverrideValue * 10.0D / 300.0D) - full;

                for (int i = 0; i < full + partial; ++i)
                {
                    mc.ingameGUI.drawTexturedModalRect(left - i * 8 - 9, top, (i < full ? 16 : 25), 18, 9, 9);
                }
                GL11.glDisable(GL11.GL_BLEND);
            }
        }
    }
}
