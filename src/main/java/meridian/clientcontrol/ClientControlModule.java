package meridian.clientcontrol;

import meridian.api.module.ModuleContext;
import meridian.api.module.ProxyModule;
import meridian.api.packet.Direction;
import meridian.api.packet.HandlerPosition;
import meridian.api.session.ProxySession;
import meridian.api.settings.SettingsSpec;
import meridian.protocol.ComponentUpdate;
import meridian.protocol.EntityStatOp;
import meridian.protocol.EntityStatUpdate;
import meridian.protocol.EntityStatsUpdate;
import meridian.protocol.EntityUpdate;
import meridian.protocol.GameMode;
import meridian.protocol.MovementSettings;
import meridian.protocol.packets.entities.EntityUpdates;
import meridian.protocol.packets.interface_.CustomPage;
import meridian.protocol.packets.player.SetGameMode;
import meridian.protocol.packets.player.UpdateMovementSettings;
import org.slf4j.Logger;

/**
 * meridian-client-control — a Layer-1 module that spoofs what the client believes
 * about itself. Everything is on the Default channel and every forge goes
 * <b>to the client</b> (never to the server):
 *
 * <ol>
 *   <li><b>Client game mode</b> — {@code SetGameMode} (id 101) is the only packet
 *       that carries the game mode, and it is server→client. The module reads it
 *       to track the player's current mode; an enable checkbox + a mode dropdown
 *       then forge a {@code SetGameMode} <i>to the client</i> and hold it by
 *       rewriting any later server {@code SetGameMode}. Disabling sends the
 *       server's real mode back.</li>
 *   <li><b>Fly</b> — the fly capability is {@code MovementSettings.canFly}, sent to
 *       the client in {@code UpdateMovementSettings} (id 110). When on, the module
 *       forces {@code canFly = true} (immediately and on every later server update,
 *       which would otherwise undo a one-shot); off restores the server's value.</li>
 *   <li><b>Drop isFlying</b> — clears the {@code flying} flag from every C2S
 *       movement packet ({@code ClientMovement} / {@code MountMovement}) so the
 *       server never sees the player as flying. This is the only C2S leg.</li>
 * </ol>
 */
public class ClientControlModule implements ProxyModule {

    /** Game-mode options for the dropdown; Survival maps to protocol {@code Adventure}. */
    public enum Mode {
        Creative,
        Survival;

        GameMode toProtocol() {
            return this == Creative ? GameMode.Creative : GameMode.Adventure;
        }
    }

    private Logger log;

    // --- live controls (session-only; action state, not tuning) --------------
    private volatile boolean gamemodeEnabled = false;
    private volatile Mode mode = Mode.Creative;
    private volatile boolean flyEnabled = false;
    private volatile boolean stripFlying = false;
    private volatile boolean addSwimming = false;
    private volatile boolean noFall = false;
    private volatile boolean freezeStamina = false;
    private volatile boolean deathIgnore = false;


    /** Mode held on the client, or {@code null} when the override is off. */
    private volatile GameMode spoofGameMode = null;

    // --- captured wire state -------------------------------------------------
    /** Last game mode the server actually sent — used for status + revert. */
    private volatile GameMode serverGameMode;
    /** Last movement settings the server actually sent (true, pre-override). */
    private volatile MovementSettings lastServerSettings;
    /** Stat-type index of "Stamina" + its max, learned from UpdateEntityStatTypes. */
    private volatile int staminaStatIndex = Integer.MIN_VALUE;
    private volatile float staminaMax;
    /** Stat-type index of "Health", learned from UpdateEntityStatTypes. */
    private volatile int healthStatIndex = Integer.MIN_VALUE;
    /** Local player's network id, learned from SetClientId — for self-only edits. */
    private volatile int localEntityId = Integer.MIN_VALUE;
    /** We dropped a RespawnPage (server killed the player) — unmask on toggle off. */
    private volatile boolean wasDead = false;
    /** Last dropped RespawnPage, replayed to the client when death-ignore is off. */
    private volatile CustomPage capturedRespawnPage;
    private volatile ProxySession session;

    /** CustomPage key the server uses for the death/respawn window. */
    static final String RESPAWN_PAGE_KEY =
            "com.hypixel.hytale.server.core.entity.entities.player.pages.RespawnPage";

    @Override
    public void onEnable(ModuleContext ctx) {
        this.log = ctx.getLogger();

        ctx.registerHandler(Direction.S2C, HandlerPosition.NORMAL,
                (direction, session) -> new ClientStateS2CHandler(this));
        ctx.registerHandler(Direction.C2S, HandlerPosition.NORMAL,
                (direction, session) -> new MovementFlagC2SHandler(this));

        ctx.registerSettings(SettingsSpec.builder()
                .bool("gamemode", "Override game mode", false, this::setGamemodeEnabled)
                .enum_("mode", "Mode", Mode.class, Mode.Creative, this::setMode)
                .bool("fly", "Fly (force canFly)", false, this::setFly)
                .bool("stripFlying", "Drop isFlying from C2S movement", false,
                        v -> stripFlying = v)
                .bool("addSwimming", "Add isSwimming to C2S movement", false,
                        v -> addSwimming = v)
                .bool("noFall", "NoFall (no fall damage)", false, v -> noFall = v)
                .bool("freezeStamina", "Freeze stamina (always full)", false,
                        v -> freezeStamina = v)
                .bool("deathIgnore", "Death ignore", false, this::setDeathIgnore)
                .liveText("Status", this::status)
                .build());

        log.info("meridian-client-control enabled — gamemode / fly / isFlying-strip / nofall / stamina");
    }

    // ------------------------------------------------------------------
    // Settings callbacks (Swing EDT) — every forge here targets the CLIENT
    // ------------------------------------------------------------------

    private void setGamemodeEnabled(boolean on) {
        this.gamemodeEnabled = on;
        applyGamemode();
    }

    private void setMode(Mode m) {
        this.mode = m;
        applyGamemode();
    }

    /** Recomputes the held mode and pushes it to the client (or reverts on disable). */
    private void applyGamemode() {
        GameMode target = gamemodeEnabled ? mode.toProtocol() : null;
        this.spoofGameMode = target;

        ProxySession s = session;
        if (s == null) {
            return;
        }
        if (target != null) {
            s.sendToClient(new SetGameMode(target));                 // -> client
            log.info("client-control: forced client game mode -> {}", target);
        } else if (serverGameMode != null) {
            s.sendToClient(new SetGameMode(serverGameMode));         // revert -> client
            log.info("client-control: game-mode override off, restored {}", serverGameMode);
        }
    }

    /**
     * Death ignore. While on, the S2C handler clamps the local player's health to
     * &ge;1 and drops the death animation + RespawnPage window. Turning it off
     * "lets the death through" if the player had actually died: forge health 0
     * and replay the captured RespawnPage so the client resyncs to its real
     * (dead) state.
     */
    private void setDeathIgnore(boolean on) {
        this.deathIgnore = on;
        if (on || !wasDead) {
            return;
        }
        ProxySession s = session;
        if (s != null) {
            if (localEntityId != Integer.MIN_VALUE && healthStatIndex != Integer.MIN_VALUE) {
                EntityStatUpdate hp = new EntityStatUpdate();
                hp.op = EntityStatOp.Set;
                hp.value = 0.0f;
                EntityStatsUpdate stats = new EntityStatsUpdate();
                stats.entityStatUpdates.put(healthStatIndex, new EntityStatUpdate[]{hp});
                EntityUpdate eu = new EntityUpdate(localEntityId, null,
                        new ComponentUpdate[]{stats});
                s.sendToClient(new EntityUpdates(null, new EntityUpdate[]{eu}));
            }
            if (capturedRespawnPage != null) {
                s.sendToClient(capturedRespawnPage);   // re-show the death window
            }
            log.info("client-control: death-ignore off — released death (health 0 + respawn page)");
        }
        wasDead = false;
    }

    private void setFly(boolean on) {
        this.flyEnabled = on;
        ProxySession s = session;
        MovementSettings base = lastServerSettings;
        if (s == null || base == null) {
            return;   // applied on the next server UpdateMovementSettings instead
        }
        MovementSettings copy = new MovementSettings(base);
        copy.canFly = on || base.canFly;   // off = restore the server's real value
        s.sendToClient(new UpdateMovementSettings(copy));   // -> client
        log.info("client-control: fly {} (canFly={})", on ? "ON" : "OFF", copy.canFly);
    }

    // ------------------------------------------------------------------
    // State shared with the handlers (Netty event loop)
    // ------------------------------------------------------------------

    void captureSession(ProxySession s) {
        this.session = s;
    }

    /** The mode to hold on the client, or {@code null} when the override is off. */
    GameMode spoofGameMode() {
        return spoofGameMode;
    }

    void rememberServerGameMode(GameMode mode) {
        this.serverGameMode = mode;
    }

    boolean flyForced() {
        return flyEnabled;
    }

    void rememberServerSettings(MovementSettings settings) {
        this.lastServerSettings = new MovementSettings(settings);
    }

    boolean stripFlying() {
        return stripFlying;
    }

    boolean addSwimming() {
        return addSwimming;
    }

    boolean noFall() {
        return noFall;
    }

    boolean freezeStamina() {
        return freezeStamina;
    }

    /** Stat-type index for "Stamina", or {@code Integer.MIN_VALUE} until learned. */
    int staminaStatIndex() {
        return staminaStatIndex;
    }

    float staminaMax() {
        return staminaMax;
    }

    void rememberStaminaStat(int index, float max) {
        this.staminaStatIndex = index;
        this.staminaMax = max;
    }

    boolean deathIgnore() {
        return deathIgnore;
    }

    int healthStatIndex() {
        return healthStatIndex;
    }

    void rememberHealthStat(int index) {
        this.healthStatIndex = index;
    }

    int localEntityId() {
        return localEntityId;
    }

    void setLocalEntityId(int id) {
        this.localEntityId = id;
    }

    /** Record a dropped RespawnPage: a non-clear page is a death (capture it for
     *  replay); a clear page is a server-side revive (forget the death). */
    void onRespawnPage(CustomPage page) {
        if (page.clear) {
            this.wasDead = false;
        } else {
            this.capturedRespawnPage = page;
            this.wasDead = true;
        }
    }

    private String status() {
        return "Server mode: " + (serverGameMode == null ? "?" : serverGameMode)
                + "  |  Client forced: " + (spoofGameMode == null ? "off" : spoofGameMode)
                + "  |  Fly: " + (flyEnabled ? "ON" : "off")
                + "  |  isFlying strip: " + (stripFlying ? "ON" : "off")
                + "  |  NoFall: " + (noFall ? "ON" : "off")
                + "  |  Stamina freeze: " + (freezeStamina ? "ON" : "off")
                + "  |  Death ignore: " + (deathIgnore ? "ON" : "off")
                + (wasDead ? " (dead, masked)" : "");
    }
}
