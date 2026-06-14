package meridian.clientcontrol;

import io.netty.channel.ChannelHandlerContext;
import java.util.Map;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.ComponentUpdate;
import meridian.protocol.EntityStatType;
import meridian.protocol.EntityStatUpdate;
import meridian.protocol.EntityStatsUpdate;
import meridian.protocol.GameMode;
import meridian.protocol.MovementSettings;
import meridian.protocol.EntityUpdate;
import meridian.protocol.packets.assets.UpdateEntityStatTypes;
import meridian.protocol.packets.entities.EntityUpdates;
import meridian.protocol.packets.entities.PlayAnimation;
import meridian.protocol.packets.interface_.CustomPage;
import meridian.protocol.packets.player.SetClientId;
import meridian.protocol.packets.player.SetGameMode;
import meridian.protocol.packets.player.UpdateMovementSettings;

/**
 * S2C leg (server → client). Reads the player's current state off the wire and
 * enforces the client-side overrides:
 *
 * <ul>
 *   <li>{@code SetGameMode} — records the server's current mode, then, while the
 *       override is engaged, rewrites it to the forced mode so the server can't
 *       revert the spoof.</li>
 *   <li>{@code UpdateMovementSettings} — snapshots the server's real settings, then
 *       forces {@code canFly = true} while Fly is on.</li>
 * </ul>
 *
 * <p>Both packets live on the <b>Default</b> channel, so the {@link ProxySession}
 * is only captured when one of them is seen — that pins the module's forges to the
 * Default stream. A handler instance also runs on the chunk / voice / world-map
 * streams; capturing their session would send {@code SetGameMode} down the wrong
 * channel (the client rejects it as "not valid on channel WorldMap").
 */
final class ClientStateS2CHandler implements PacketHandler {

    private final ClientControlModule module;

    ClientStateS2CHandler(ClientControlModule module) {
        this.module = module;
    }

    @Override
    public Action handleS2C(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof SetGameMode sg) {
            module.captureSession(session);               // Default stream
            module.rememberServerGameMode(sg.gameMode);   // catch current mode
            GameMode forced = module.spoofGameMode();
            if (forced != null && sg.gameMode != forced) {
                sg.gameMode = forced;                     // hold the spoof, to client
                return Action.MODIFIED;
            }
            return Action.FORWARD;
        }

        if (packet instanceof UpdateMovementSettings ums && ums.movementSettings != null) {
            module.captureSession(session);               // Default stream
            MovementSettings settings = ums.movementSettings;
            module.rememberServerSettings(settings);
            if (module.flyForced() && !settings.canFly) {
                settings.canFly = true;
                return Action.MODIFIED;
            }
            return Action.FORWARD;
        }

        // Learn the "Stamina" and "Health" stat-type indices from the registry.
        if (packet instanceof UpdateEntityStatTypes statTypes && statTypes.types != null) {
            for (Map.Entry<Integer, EntityStatType> e : statTypes.types.entrySet()) {
                EntityStatType t = e.getValue();
                if (t == null) {
                    continue;
                }
                if ("Stamina".equals(t.id)) {
                    module.rememberStaminaStat(e.getKey(), t.max);
                } else if ("Health".equals(t.id)) {
                    module.rememberHealthStat(e.getKey());
                }
            }
            return Action.FORWARD;
        }

        if (packet instanceof SetClientId sci) {
            module.captureSession(session);
            module.setLocalEntityId(sci.clientId);
            return Action.FORWARD;
        }

        // Death ignore: swallow the death window and the death/laydown animation.
        if (module.deathIgnore()) {
            if (packet instanceof CustomPage page
                    && ClientControlModule.RESPAWN_PAGE_KEY.equals(page.key)) {
                module.captureSession(session);
                module.onRespawnPage(page);
                return Action.DROP;
            }
            if (packet instanceof PlayAnimation anim
                    && anim.entityId == module.localEntityId()
                    && isDeathAnimation(anim.animationId)) {
                return Action.DROP;
            }
        }

        // Stat rewrites: stamina freeze (all entities) + death-ignore health
        // clamp (local player only). Both live in EntityStatsUpdate components.
        if (packet instanceof EntityUpdates updates) {
            return rewriteStats(updates);
        }

        return Action.FORWARD;
    }

    private static boolean isDeathAnimation(String id) {
        return "Death".equals(id) || "Laydown".equals(id) || "Sleep".equals(id);
    }

    /**
     * Pins stamina to its max (every entity) and, under death-ignore, clamps the
     * local player's health to &ge;1 so the bar never empties.
     */
    private Action rewriteStats(EntityUpdates updates) {
        boolean freezeStam = module.freezeStamina();
        int stamIdx = module.staminaStatIndex();
        boolean clampHp = module.deathIgnore();
        int healthIdx = module.healthStatIndex();
        int localId = module.localEntityId();
        if (updates.updates == null
                || (!freezeStam || stamIdx == Integer.MIN_VALUE)
                && (!clampHp || healthIdx == Integer.MIN_VALUE)) {
            return Action.FORWARD;
        }
        float stamMax = module.staminaMax();
        boolean changed = false;
        for (EntityUpdate update : updates.updates) {
            if (update == null || update.updates == null) {
                continue;
            }
            boolean isLocal = update.networkId == localId;
            for (ComponentUpdate component : update.updates) {
                if (!(component instanceof EntityStatsUpdate stats)
                        || stats.entityStatUpdates == null) {
                    continue;
                }
                if (freezeStam && stamIdx != Integer.MIN_VALUE) {
                    changed |= clampUp(stats.entityStatUpdates.get(stamIdx), stamMax);
                }
                if (clampHp && isLocal && healthIdx != Integer.MIN_VALUE) {
                    changed |= clampUp(stats.entityStatUpdates.get(healthIdx), 1.0f);
                }
            }
        }
        return changed ? Action.MODIFIED : Action.FORWARD;
    }

    /** Raises any update value below {@code floor} up to it; returns whether changed. */
    private static boolean clampUp(EntityStatUpdate[] arr, float floor) {
        if (arr == null) {
            return false;
        }
        boolean changed = false;
        for (EntityStatUpdate su : arr) {
            if (su != null && su.value < floor) {
                su.value = floor;
                changed = true;
            }
        }
        return changed;
    }
}
