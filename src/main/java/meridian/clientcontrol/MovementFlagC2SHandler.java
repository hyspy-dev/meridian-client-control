package meridian.clientcontrol;

import io.netty.channel.ChannelHandlerContext;
import meridian.api.packet.Packet;
import meridian.api.packet.PacketHandler;
import meridian.api.session.ProxySession;
import meridian.protocol.MovementStates;
import meridian.protocol.packets.entities.MountMovement;
import meridian.protocol.packets.player.ClientMovement;

/**
 * C2S leg (client → server). Two movement spoofs on the packets the client sends:
 *
 * <ul>
 *   <li><b>Drop isFlying</b> — clears the {@code flying} flag from
 *       {@code ClientMovement} (player + rider states) and {@code MountMovement}
 *       so the server never observes the player as flying.</li>
 *   <li><b>Add isSwimming</b> — sets the {@code swimming} flag on the same states,
 *       so the server always sees the player as swimming.</li>
 *   <li><b>NoFall</b> — zeroes the downward component of {@code ClientMovement.velocity}.
 *       The server's fall-damage system (DamageSystems.FallDamagePlayers) only
 *       hurts when {@code abs(clientVelocity.y) > minFallSpeedToEngageRoll} on the
 *       tick the player lands; reporting {@code velocity.y = 0} keeps that below
 *       threshold, so no fall damage is applied. Upward velocity (jumps) is left
 *       untouched.</li>
 * </ul>
 *
 * <p>Only re-serialises when something actually changed. Both packets are on the
 * <b>Default</b> channel; the {@link ProxySession} is captured here (not on
 * world-map / chunk streams) so the module's forges target the Default stream.
 * {@code ClientMovement} flows constantly, so it is the module's primary,
 * always-up-to-date session source.
 */
final class MovementFlagC2SHandler implements PacketHandler {

    private final ClientControlModule module;

    MovementFlagC2SHandler(ClientControlModule module) {
        this.module = module;
    }

    @Override
    public Action handleC2S(ChannelHandlerContext ctx, Packet packet, ProxySession session) {
        if (packet instanceof ClientMovement cm) {
            module.captureSession(session);   // Default stream
            boolean changed = false;
            if (module.stripFlying()) {
                changed |= clearFlying(cm.movementStates);
                changed |= clearFlying(cm.riderMovementStates);
            }
            if (module.addSwimming()) {
                changed |= setSwimming(cm.movementStates);
                changed |= setSwimming(cm.riderMovementStates);
            }
            if (module.noFall() && cm.velocity != null && cm.velocity.y < 0.0) {
                cm.velocity.y = 0.0;   // hide fall speed → no fall damage
                changed = true;
            }
            return changed ? Action.MODIFIED : Action.FORWARD;
        }

        if (packet instanceof MountMovement mm) {
            module.captureSession(session);   // Default stream
            boolean changed = false;
            if (module.stripFlying()) {
                changed |= clearFlying(mm.movementStates);
            }
            if (module.addSwimming()) {
                changed |= setSwimming(mm.movementStates);
            }
            return changed ? Action.MODIFIED : Action.FORWARD;
        }

        return Action.FORWARD;
    }

    /** Clears {@code flying} on {@code states} if set; returns whether it changed. */
    private static boolean clearFlying(MovementStates states) {
        if (states != null && states.flying) {
            states.flying = false;
            return true;
        }
        return false;
    }

    /** Sets {@code swimming} on {@code states} if unset; returns whether it changed. */
    private static boolean setSwimming(MovementStates states) {
        if (states != null && !states.swimming) {
            states.swimming = true;
            return true;
        }
        return false;
    }
}
