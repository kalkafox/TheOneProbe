package mcjty.theoneprobe.rendering;

import mcjty.theoneprobe.TheOneProbe;
import mcjty.theoneprobe.api.*;
import mcjty.theoneprobe.apiimpl.ProbeHitData;
import mcjty.theoneprobe.apiimpl.ProbeHitEntityData;
import mcjty.theoneprobe.apiimpl.ProbeInfo;
import mcjty.theoneprobe.apiimpl.elements.ElementProgress;
import mcjty.theoneprobe.apiimpl.elements.ElementText;
import mcjty.theoneprobe.apiimpl.providers.DefaultProbeInfoEntityProvider;
import mcjty.theoneprobe.apiimpl.providers.DefaultProbeInfoProvider;
import mcjty.theoneprobe.apiimpl.styles.ProgressStyle;
import mcjty.theoneprobe.config.Config;
import mcjty.theoneprobe.network.PacketGetEntityInfo;
import mcjty.theoneprobe.network.PacketGetInfo;
import mcjty.theoneprobe.network.PacketHandler;
import mcjty.theoneprobe.network.ThrowableIdentity;
import net.minecraft.block.Block;
import net.minecraft.block.state.IBlockState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.client.gui.ScaledResolution;
import net.minecraft.client.renderer.GlStateManager;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityList;
import net.minecraft.entity.boss.EntityDragonPart;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.RayTraceResult;
import net.minecraft.util.math.Vec3d;
import net.minecraft.util.text.TextFormatting;
import net.minecraft.world.World;
import org.apache.commons.lang3.tuple.Pair;
import org.lwjgl.opengl.GL11;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class OverlayRenderer {

    private static Map<Pair<Integer,BlockPos>, Pair<Long, ProbeInfo>> cachedInfo = new HashMap<>();
    private static Map<UUID, Pair<Long, ProbeInfo>> cachedEntityInfo = new HashMap<>();
    private static long lastCleanupTime = 0;

    // For a short while we keep displaying the last pair if we have no new information
    // to prevent flickering
    private static Pair<Long, ProbeInfo> lastPair;
    private static long lastPairTime = 0;

    // When the server delays too long we also show some preliminary information already
    private static long lastRenderedTime = -1;

    public static void registerProbeInfo(int dim, BlockPos pos, ProbeInfo probeInfo) {
        if (probeInfo == null) {
            return;
        }
        long time = System.currentTimeMillis();
        cachedInfo.put(Pair.of(dim, pos), Pair.of(time, probeInfo));
    }

    public static void registerProbeInfo(UUID uuid, ProbeInfo probeInfo) {
        if (probeInfo == null) {
            return;
        }
        long time = System.currentTimeMillis();
        cachedEntityInfo.put(uuid, Pair.of(time, probeInfo));
    }

    public static void renderHUD(ProbeMode mode, float partialTicks) {
        float dist = Config.probeDistance;

        RayTraceResult mouseOver = Minecraft.getMinecraft().objectMouseOver;
        if (mouseOver != null) {
            if (mouseOver.typeOfHit == RayTraceResult.Type.ENTITY) {
                GlStateManager.pushMatrix();

                double scale = Config.tooltipScale;

                ScaledResolution scaledresolution = new ScaledResolution(Minecraft.getMinecraft());
                double sw = scaledresolution.getScaledWidth_double();
                double sh = scaledresolution.getScaledHeight_double();

                setupOverlayRendering(sw * scale, sh * scale);
                renderHUDEntity(mode, mouseOver, sw * scale, sh * scale);
                setupOverlayRendering(sw, sh);
                GlStateManager.popMatrix();

                checkCleanup();
                return;
            }
        }

        EntityPlayerSP entity = Minecraft.getMinecraft().thePlayer;
        Vec3d start  = entity.getPositionEyes(partialTicks);
        Vec3d vec31 = entity.getLook(partialTicks);
        Vec3d end = start.addVector(vec31.xCoord * dist, vec31.yCoord * dist, vec31.zCoord * dist);

        mouseOver = entity.worldObj.rayTraceBlocks(start, end, Config.showLiquids);
        if (mouseOver == null) {
            return;
        }

        if (mouseOver.typeOfHit == RayTraceResult.Type.BLOCK) {
            GlStateManager.pushMatrix();

            double scale = Config.tooltipScale;

            ScaledResolution scaledresolution = new ScaledResolution(Minecraft.getMinecraft());
            double sw = scaledresolution.getScaledWidth_double();
            double sh = scaledresolution.getScaledHeight_double();

            setupOverlayRendering(sw * scale, sh * scale);
            renderHUDBlock(mode, mouseOver, sw * scale, sh * scale);
            setupOverlayRendering(sw, sh);

            GlStateManager.popMatrix();
        }

        checkCleanup();
    }

    private static void setupOverlayRendering(double sw, double sh) {
        GlStateManager.clear(256);
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.loadIdentity();
        GlStateManager.ortho(0.0D, sw, sh, 0.0D, 1000.0D, 3000.0D);
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.loadIdentity();
        GlStateManager.translate(0.0F, 0.0F, -2000.0F);
    }

    private static void checkCleanup() {
        long time = System.currentTimeMillis();
        if (time > lastCleanupTime + 5000) {
            cleanupCachedBlocks(time);
            cleanupCachedEntities(time);
            lastCleanupTime = time;
        }
    }

    private static void renderHUDEntity(ProbeMode mode, RayTraceResult mouseOver, double sw, double sh) {
        Entity entity = mouseOver.entityHit;
        if (entity == null) {
            return;
        }
        if (entity instanceof EntityDragonPart) {
            EntityDragonPart part = (EntityDragonPart) entity;
            if (part.entityDragonObj instanceof Entity) {
                entity = (Entity) part.entityDragonObj;
            }
        }

        String entityString = EntityList.getEntityString(entity);
        if (entityString == null && !(entity instanceof EntityPlayer)) {
            // We can't show info for this entity
            return;
        }

        UUID uuid = entity.getPersistentID();

        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        long time = System.currentTimeMillis();

        Pair<Long, ProbeInfo> cacheEntry = cachedEntityInfo.get(uuid);
        if (cacheEntry == null) {
            requestEntityInfo(mode, mouseOver, entity, player);
            if (lastPair != null && time < lastPairTime + Config.timeout) {
                renderElements(lastPair.getRight(), Config.getDefaultOverlayStyle(), sw, sh, null);
                lastRenderedTime = time;
            } else if (Config.waitingForServerTimeout > 0 && lastRenderedTime != -1 && time > lastRenderedTime + Config.waitingForServerTimeout) {
                // It has been a while. Show some info on client that we are
                // waiting for server information
                ProbeInfo info = getWaitingEntityInfo(mode, mouseOver, entity, player);
                registerProbeInfo(uuid, info);
                lastPair = Pair.of(time, info);
                lastPairTime = time;
                renderElements(lastPair.getRight(), Config.getDefaultOverlayStyle(), sw, sh, null);
                lastRenderedTime = time;
            }
        } else {
            if (time > cacheEntry.getLeft() + Config.timeout) {
                // This info is slightly old. Update it
                requestEntityInfo(mode, mouseOver, entity, player);
            }
            renderElements(cacheEntry.getRight(), Config.getDefaultOverlayStyle(), sw, sh, null);
            lastRenderedTime = time;
            lastPair = cacheEntry;
            lastPairTime = time;
        }
    }

    private static void requestEntityInfo(ProbeMode mode, RayTraceResult mouseOver, Entity entity, EntityPlayerSP player) {
        PacketHandler.INSTANCE.sendToServer(new PacketGetEntityInfo(player.worldObj.provider.getDimension(), mode, mouseOver, entity));
    }

    private static void renderHUDBlock(ProbeMode mode, RayTraceResult mouseOver, double sw, double sh) {
        BlockPos blockPos = mouseOver.getBlockPos();
        if (blockPos == null) {
            return;
        }
        EntityPlayerSP player = Minecraft.getMinecraft().thePlayer;
        if (player.worldObj.isAirBlock(blockPos)) {
            return;
        }

        long time = System.currentTimeMillis();

        IElement damageElement = null;
        if (Config.showBreakProgress > 0) {
            float damage = Minecraft.getMinecraft().playerController.curBlockDamageMP;
            if (damage > 0) {
                if (Config.showBreakProgress == 2) {
                    damageElement = new ElementText(TextFormatting.RED + "Progress " + (int) (damage * 100) + "%");
                } else {
                    damageElement = new ElementProgress((long) (damage * 100), 100, new ProgressStyle()
                            .prefix("Progress ")
                            .suffix("%")
                            .borderColor(0)
                            .filledColor(0)
                            .filledColor(0xff990000)
                            .alternateFilledColor(0xff550000));
                }
            }
        }

        int dimension = player.worldObj.provider.getDimension();
        Pair<Long, ProbeInfo> cacheEntry = cachedInfo.get(Pair.of(dimension, blockPos));
        if (cacheEntry == null) {
            requestBlockInfo(mode, mouseOver, blockPos, player);
            if (lastPair != null && time < lastPairTime + Config.timeout) {
                renderElements(lastPair.getRight(), Config.getDefaultOverlayStyle(), sw, sh, damageElement);
                lastRenderedTime = time;
            } else if (Config.waitingForServerTimeout > 0 && lastRenderedTime != -1 && time > lastRenderedTime + Config.waitingForServerTimeout) {
                // It has been a while. Show some info on client that we are
                // waiting for server information
                ProbeInfo info = getWaitingInfo(mode, mouseOver, blockPos, player);
                registerProbeInfo(dimension, blockPos, info);
                lastPair = Pair.of(time, info);
                lastPairTime = time;
                renderElements(lastPair.getRight(), Config.getDefaultOverlayStyle(), sw, sh, damageElement);
                lastRenderedTime = time;
            }
        } else {
            if (time > cacheEntry.getLeft() + Config.timeout) {
                // This info is slightly old. Update it
                requestBlockInfo(mode, mouseOver, blockPos, player);
            }
            renderElements(cacheEntry.getRight(), Config.getDefaultOverlayStyle(), sw, sh, damageElement);
            lastRenderedTime = time;
            lastPair = cacheEntry;
            lastPairTime = time;
        }
    }

    // Information for when the server is laggy
    private static ProbeInfo getWaitingInfo(ProbeMode mode, RayTraceResult mouseOver, BlockPos blockPos, EntityPlayerSP player) {
        ProbeInfo probeInfo = TheOneProbe.theOneProbeImp.create();

        World world = player.worldObj;
        IBlockState blockState = world.getBlockState(blockPos);
        Block block = blockState.getBlock();
        ItemStack pickBlock = block.getPickBlock(blockState, mouseOver, world, blockPos, player);
        IProbeHitData data = new ProbeHitData(blockPos, mouseOver.hitVec, mouseOver.sideHit, pickBlock);

        IProbeConfig probeConfig = TheOneProbe.theOneProbeImp.createProbeConfig();
        try {
            DefaultProbeInfoProvider.showStandardBlockInfo(probeConfig, mode, probeInfo, blockState, block, world, blockPos, player, data);
        } catch (Exception e) {
            ThrowableIdentity.registerThrowable(e);
            probeInfo.text(TextFormatting.RED + "Error (see log for details)!");
        }

        probeInfo.text(TextFormatting.RED + "Waiting for server...");
        return probeInfo;
    }

    private static ProbeInfo getWaitingEntityInfo(ProbeMode mode, RayTraceResult mouseOver, Entity entity, EntityPlayerSP player) {
        ProbeInfo probeInfo = TheOneProbe.theOneProbeImp.create();
        IProbeHitEntityData data = new ProbeHitEntityData(mouseOver.hitVec);

        IProbeConfig probeConfig = TheOneProbe.theOneProbeImp.createProbeConfig();
        try {
            DefaultProbeInfoEntityProvider.showStandardInfo(mode, probeInfo, entity, probeConfig);
        } catch (Exception e) {
            ThrowableIdentity.registerThrowable(e);
            probeInfo.text(TextFormatting.RED + "Error (see log for details)!");
        }

        probeInfo.text(TextFormatting.RED + "Waiting for server...");
        return probeInfo;
    }

    private static void requestBlockInfo(ProbeMode mode, RayTraceResult mouseOver, BlockPos blockPos, EntityPlayerSP player) {
        World world = player.worldObj;
        IBlockState blockState = world.getBlockState(blockPos);
        Block block = blockState.getBlock();
        ItemStack pickBlock = block.getPickBlock(blockState, mouseOver, world, blockPos, player);
        if (pickBlock != null && pickBlock.getItem() == null) {
            // Protection for some invalid items.
            pickBlock = null;
        }
        PacketHandler.INSTANCE.sendToServer(new PacketGetInfo(world.provider.getDimension(), blockPos, mode, mouseOver, pickBlock));
    }

    public static void renderOverlay(IOverlayStyle style, IProbeInfo probeInfo) {
        GlStateManager.pushMatrix();

        double scale = Config.tooltipScale;

        ScaledResolution scaledresolution = new ScaledResolution(Minecraft.getMinecraft());
        double sw = scaledresolution.getScaledWidth_double();
        double sh = scaledresolution.getScaledHeight_double();

        setupOverlayRendering(sw * scale, sh * scale);
        renderElements((ProbeInfo) probeInfo, style, sw * scale, sh * scale, null);
        setupOverlayRendering(sw, sh);
        GlStateManager.popMatrix();
    }

    private static void cleanupCachedBlocks(long time) {
        // It has been a while. Time to clean up unused cached pairs.
        Map<Pair<Integer,BlockPos>, Pair<Long, ProbeInfo>> newCachedInfo = new HashMap<>();
        for (Map.Entry<Pair<Integer, BlockPos>, Pair<Long, ProbeInfo>> entry : cachedInfo.entrySet()) {
            long t = entry.getValue().getLeft();
            if (time < t + Config.timeout + 1000) {
                newCachedInfo.put(entry.getKey(), entry.getValue());
            }
        }
        cachedInfo = newCachedInfo;
    }

    private static void cleanupCachedEntities(long time) {
        // It has been a while. Time to clean up unused cached pairs.
        Map<UUID, Pair<Long, ProbeInfo>> newCachedInfo = new HashMap<>();
        for (Map.Entry<UUID, Pair<Long, ProbeInfo>> entry : cachedEntityInfo.entrySet()) {
            long t = entry.getValue().getLeft();
            if (time < t + Config.timeout + 1000) {
                newCachedInfo.put(entry.getKey(), entry.getValue());
            }
        }
        cachedEntityInfo = newCachedInfo;
    }

    private static void renderElements(ProbeInfo probeInfo, IOverlayStyle style, double sw, double sh, IElement extra) {
        if (extra != null) {
            probeInfo.element(extra);
        }

        GlStateManager.color(1.0F, 1.0F, 1.0F, 1.0F);
        GlStateManager.disableLighting();

//        final ScaledResolution scaledresolution = new ScaledResolution(Minecraft.getMinecraft());
//        final int scaledWidth = scaledresolution.getScaledWidth();
//        final int scaledHeight = scaledresolution.getScaledHeight();
        int scaledWidth = (int) sw;
        int scaledHeight = (int) sh;

        int w = probeInfo.getWidth();
        int h = probeInfo.getHeight();

        int thick = style.getBorderThickness();
        int margin = 0;
        if (thick > 0) {
            w += (thick+3) * 2;
            h += (thick+3) * 2;
            margin = thick + 3;
        }

        int x;
        int y;
        if (style.getLeftX() != -1) {
            x = style.getLeftX();
        } else if (style.getRightX() != -1) {
            x = scaledWidth - w - style.getRightX();
        } else {
            x = (scaledWidth - w) / 2;
        }
        if (style.getTopY() != -1) {
            y = style.getTopY();
        } else if (style.getBottomY() != -1) {
            y = scaledHeight - h - style.getBottomY();
        } else {
            y = (scaledHeight - h) / 2;
        }

        if (thick > 0) {
            RenderHelper.drawThickBeveledBox(x, y, x + w-1, y + h-1, thick, style.getBorderColor(), style.getBorderColor(), style.getBoxColor());
        }

        if (!Minecraft.getMinecraft().isGamePaused()) {
            RenderHelper.rot += .5f;
        }

        probeInfo.render(x + margin, y + margin);
        if (extra != null) {
            probeInfo.removeElement(extra);
        }
    }
}
