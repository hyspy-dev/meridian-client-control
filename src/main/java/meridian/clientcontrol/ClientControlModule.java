package meridian.clientcontrol;

import meridian.api.module.ModuleContext;
import meridian.api.module.ProxyModule;
import meridian.api.settings.SettingsSpec;
import meridian.core.api.ClientSpoof;
import meridian.core.api.NoClip;
import org.slf4j.Logger;

/**
 * meridian-client-control — switches for what the client believes about itself, and for what the
 * server is told of the client's movement. A Layer-2 module: every switch is a setting of core's
 * {@link ClientSpoof}, which applies it inside core's own rewriters, after core has read the truth.
 *
 * <ol>
 *   <li><b>Client game mode</b> — an enable checkbox and a mode dropdown hold the client in a mode,
 *       against every later change the server sends; disabling gives it the server's own back.</li>
 *   <li><b>Fly</b> — lets the client fly where the server has flight disabled; off restores the
 *       server's movement settings.</li>
 *   <li><b>Drop isFlying / add isSwimming / NoFall</b> — the flags and the fall speed in the
 *       movement the client sends the server.</li>
 *   <li><b>Freeze stamina / death ignore</b> — the bars and the death window the client is shown.
 *       Core's own player state keeps the server's values.</li>
 *   <li><b>No-clip</b> — a second toggle for core's own client-side no-clip.</li>
 * </ol>
 */
public class ClientControlModule implements ProxyModule {

    /** Game-mode options for the dropdown; Survival is the game's {@code Adventure}. */
    public enum Mode {
        Creative,
        Survival;

        ClientSpoof.GameMode toSpoof() {
            return this == Creative ? ClientSpoof.GameMode.CREATIVE : ClientSpoof.GameMode.ADVENTURE;
        }
    }

    private Logger log;
    private ClientSpoof spoof;

    // The game-mode override is two settings (on, which mode); the held mode follows both.
    private volatile boolean gamemodeEnabled = false;
    private volatile Mode mode = Mode.Creative;

    @Override
    public void onEnable(ModuleContext ctx) {
        this.log = ctx.getLogger();
        this.spoof = ctx.services().require(ClientSpoof.class);

        // No-clip lives in core (it forges the client's own no-clip switch); this is a second
        // toggle for the same thing, here beside the other client cheats. Optional: on a line
        // whose core does not carry the switch it is simply not provided, and the toggle says so.
        NoClip noClip = ctx.services().get(NoClip.class).orElse(null);

        ctx.registerSettings(SettingsSpec.builder()
                .bool("gamemode", "Override game mode", false, this::setGamemodeEnabled)
                .enum_("mode", "Mode", Mode.class, Mode.Creative, this::setMode)
                .bool("fly", "Fly (allow flight)", false, spoof::setFlight)
                .bool("stripFlying", "Drop isFlying from C2S movement", false, spoof::setDropFlying)
                .bool("addSwimming", "Add isSwimming to C2S movement", false, spoof::setAddSwimming)
                .bool("noFall", "NoFall (no fall damage)", false, spoof::setNoFall)
                .bool("freezeStamina", "Freeze stamina (always full)", false, spoof::setFullStamina)
                .bool("deathIgnore", "Death ignore", false, spoof::setIgnoreDeath)
                .bool("noclip", noClip != null
                                ? "No-clip (client-side; server still collides)"
                                : "No-clip (needs a 0.6+ server)",
                        false, on -> {
                            if (noClip != null) {
                                noClip.setEnabled(on);
                            } else {
                                log.warn("no-clip is not available on this server line");
                            }
                        })
                .liveText("Status", this::status)
                .persistent("fly", "stripFlying", "addSwimming", "noFall", "freezeStamina", "deathIgnore", "noclip")
                .build());

        log.info("meridian-client-control enabled — gamemode / fly / isFlying-strip / nofall / stamina");
    }

    private void setGamemodeEnabled(boolean on) {
        this.gamemodeEnabled = on;
        applyGamemode();
    }

    private void setMode(Mode m) {
        this.mode = m;
        applyGamemode();
    }

    /** Holds the chosen mode on the client, or lets go so it shows the server's. */
    private void applyGamemode() {
        spoof.holdGameMode(gamemodeEnabled ? mode.toSpoof() : null);
    }

    private String status() {
        return "Server mode: " + spoof.serverGameMode().map(ClientControlModule::label).orElse("?")
                + "  |  Client forced: " + spoof.heldGameMode().map(ClientControlModule::label).orElse("off")
                + "  |  Fly: " + (spoof.flight() ? "ON" : "off")
                + "  |  isFlying strip: " + (spoof.dropFlying() ? "ON" : "off")
                + "  |  NoFall: " + (spoof.noFall() ? "ON" : "off")
                + "  |  Stamina freeze: " + (spoof.fullStamina() ? "ON" : "off")
                + "  |  Death ignore: " + (spoof.ignoreDeath() ? "ON" : "off")
                + (spoof.deathHidden() ? " (dead, masked)" : "");
    }

    /** The game's own name for a mode, as the status line always showed it. */
    private static String label(ClientSpoof.GameMode m) {
        return m == ClientSpoof.GameMode.CREATIVE ? "Creative" : "Adventure";
    }
}
