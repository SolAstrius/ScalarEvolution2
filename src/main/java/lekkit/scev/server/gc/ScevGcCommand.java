/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server.gc;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;
import java.util.Set;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.UuidArgument;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.Style;
import org.slf4j.Logger;

/**
 * {@code /scev gc ...} command tree. All subcommands require OP-level
 * permission (level 2).
 *
 * <h2>Subcommands</h2>
 *
 * <pre>
 * /scev gc status                  show config + registry stats
 * /scev gc sweep                   dry-run preview of a sweep
 * /scev gc sweep execute           run a sweep now (respects retention)
 * /scev gc purge                   dry-run preview + issue confirm token
 * /scev gc purge confirm &lt;TOKEN&gt;   execute purge (bypasses retention)
 * /scev gc protect &lt;uuid&gt;          pin UUID against all GC paths
 * /scev gc unprotect &lt;uuid&gt;        release pin
 * /scev gc list-protected          list pinned UUIDs
 * </pre>
 *
 * <h2>Token gate for purge</h2>
 *
 * <p>Purge is the only path that can't be undone — retention and grace are
 * both bypassed, and there's no trash staging. The two-step
 * {@link PurgeTokenStore} handshake forces the operator to see the preview
 * and type a fresh unpredictable token before anything dies.
 *
 * <h2>Issuer keying</h2>
 *
 * <p>Tokens are scoped to the {@link CommandSourceStack#getTextName()} that
 * issued them. A token printed in one operator's chat can't be consumed by
 * another operator — removes the "I saw your token, let me confirm for
 * you" footgun.
 */
public final class ScevGcCommand {
    private static final Logger LOG = LogUtils.getLogger();

    /** Shared across all command invocations for this server. */
    private static final PurgeTokenStore PURGE_TOKENS = new PurgeTokenStore();

    private ScevGcCommand() {}

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        LiteralArgumentBuilder<CommandSourceStack> root = Commands.literal("scev")
                .requires(src -> src.hasPermission(2))
                .then(Commands.literal("gc")
                        .then(Commands.literal("status").executes(ScevGcCommand::status))
                        .then(Commands.literal("sweep")
                                .executes(ScevGcCommand::sweepDryRun)
                                .then(Commands.literal("execute").executes(ScevGcCommand::sweepExecute)))
                        .then(Commands.literal("purge")
                                .executes(ScevGcCommand::purgeDryRun)
                                .then(Commands.literal("confirm")
                                        .then(Commands.argument("token", StringArgumentType.word())
                                                .executes(ScevGcCommand::purgeConfirm))))
                        .then(Commands.literal("protect")
                                .then(Commands.argument("uuid", UuidArgument.uuid())
                                        .executes(ScevGcCommand::protect)))
                        .then(Commands.literal("unprotect")
                                .then(Commands.argument("uuid", UuidArgument.uuid())
                                        .executes(ScevGcCommand::unprotect)))
                        .then(Commands.literal("list-protected")
                                .executes(ScevGcCommand::listProtected))
                );
        dispatcher.register(root);
    }

    /* -----------------------------------------------------------------
     * Subcommand handlers
     * ----------------------------------------------------------------- */

    private static int status(CommandContext<CommandSourceStack> ctx) {
        DiskImageGc gc = ScevGc.active();
        if (gc == null) return fail(ctx, "GC not active — no world loaded?");

        GcPolicy p = gc.policy();
        DiskImageRegistry reg = gc.registry();

        CommandSourceStack src = ctx.getSource();
        src.sendSuccess(() -> Component.literal("scev GC status").withStyle(ChatFormatting.AQUA), false);
        src.sendSuccess(() -> Component.literal("  images on disk:    " + gc.onDiskImageCount()), false);
        src.sendSuccess(() -> Component.literal("  tracked UUIDs:     " + reg.trackedCount()), false);
        src.sendSuccess(() -> Component.literal("  protected UUIDs:   " + reg.protectedCount()), false);
        src.sendSuccess(() -> Component.literal(String.format(
                "  creation grace:    %d min",
                p.creationGraceMillis() / 60_000L)), false);
        src.sendSuccess(() -> Component.literal(String.format(
                "  sweep retention:   %d days",
                p.sweepRetentionMillis() / 86_400_000L)), false);
        src.sendSuccess(() -> Component.literal(String.format(
                "  sweep interval:    %d hours",
                p.sweepIntervalMillis() / 3_600_000L)), false);
        return 1;
    }

    private static int sweepDryRun(CommandContext<CommandSourceStack> ctx) {
        DiskImageGc gc = ScevGc.active();
        if (gc == null) return fail(ctx, "GC not active");
        GcResult r = GcRunner.sweep(gc, ctx.getSource().getServer(), true);
        reportResult(ctx, r, "sweep");
        return 1;
    }

    private static int sweepExecute(CommandContext<CommandSourceStack> ctx) {
        DiskImageGc gc = ScevGc.active();
        if (gc == null) return fail(ctx, "GC not active");
        GcResult r = GcRunner.sweep(gc, ctx.getSource().getServer(), false);
        reportResult(ctx, r, "sweep");
        LOG.info("[scev-gc] /scev gc sweep execute by {}: deleted={} freed={} bytes",
                ctx.getSource().getTextName(), r.affected(), r.bytesFreed());
        return 1;
    }

    private static int purgeDryRun(CommandContext<CommandSourceStack> ctx) {
        DiskImageGc gc = ScevGc.active();
        if (gc == null) return fail(ctx, "GC not active");

        GcResult preview = GcRunner.purge(gc, ctx.getSource().getServer(), true);
        CommandSourceStack src = ctx.getSource();

        src.sendSuccess(() -> Component.literal("scev GC PURGE (dry-run)")
                .withStyle(ChatFormatting.YELLOW), false);
        src.sendSuccess(() -> Component.literal(String.format(
                "  would delete %d image(s), freeing %d bytes",
                preview.wouldDelete().size(), preview.bytesFreed())), false);

        if (preview.wouldDelete().isEmpty()) {
            src.sendSuccess(() -> Component.literal("  (nothing to purge)")
                    .withStyle(ChatFormatting.GRAY), false);
            return 1;
        }

        // Sample of up to 5 UUIDs so the operator can sanity-check.
        int i = 0;
        for (UUID u : preview.wouldDelete()) {
            if (i++ >= 5) {
                src.sendSuccess(() -> Component.literal("    ...").withStyle(ChatFormatting.GRAY), false);
                break;
            }
            String shortId = u.toString().substring(0, 8);
            src.sendSuccess(() -> Component.literal("    - " + shortId + "...")
                    .withStyle(ChatFormatting.GRAY), false);
        }

        String issuer = src.getTextName();
        String token = PURGE_TOKENS.issue(issuer, System.currentTimeMillis());
        String confirmCmd = "/scev gc purge confirm " + token;

        // Clickable confirm line.
        Style style = Style.EMPTY
                .withColor(ChatFormatting.GREEN)
                .withClickEvent(new ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, confirmCmd))
                .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT,
                        Component.literal("Click to fill " + confirmCmd)));
        src.sendSuccess(() -> Component.literal("  confirm: ").append(
                Component.literal(confirmCmd).withStyle(style)), false);
        src.sendSuccess(() -> Component.literal(String.format(
                "  token expires in %d s", PurgeTokenStore.TOKEN_TTL_MILLIS / 1000))
                .withStyle(ChatFormatting.GRAY), false);
        return 1;
    }

    private static int purgeConfirm(CommandContext<CommandSourceStack> ctx) {
        DiskImageGc gc = ScevGc.active();
        if (gc == null) return fail(ctx, "GC not active");

        String token = StringArgumentType.getString(ctx, "token");
        String issuer = ctx.getSource().getTextName();

        if (!PURGE_TOKENS.consume(issuer, token, System.currentTimeMillis())) {
            return fail(ctx, "invalid or expired token — run '/scev gc purge' to get a fresh one");
        }

        GcResult r = GcRunner.purge(gc, ctx.getSource().getServer(), false);
        CommandSourceStack src = ctx.getSource();
        src.sendSuccess(() -> Component.literal(String.format(
                "scev GC purge: deleted %d image(s), freed %d bytes",
                r.deleted().size(), r.bytesFreed()))
                .withStyle(ChatFormatting.GREEN), true);
        LOG.info("[scev-gc] /scev gc purge confirm by {}: deleted={} freed={} bytes",
                issuer, r.deleted().size(), r.bytesFreed());
        return 1;
    }

    private static int protect(CommandContext<CommandSourceStack> ctx) {
        DiskImageGc gc = ScevGc.active();
        if (gc == null) return fail(ctx, "GC not active");
        UUID uuid = UuidArgument.getUuid(ctx, "uuid");
        boolean added = gc.registry().protect(uuid);
        gc.registry().save();
        if (added) {
            ctx.getSource().sendSuccess(() -> Component.literal("protected " + uuid)
                    .withStyle(ChatFormatting.GREEN), true);
        } else {
            ctx.getSource().sendSuccess(() -> Component.literal("already protected: " + uuid)
                    .withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    private static int unprotect(CommandContext<CommandSourceStack> ctx) {
        DiskImageGc gc = ScevGc.active();
        if (gc == null) return fail(ctx, "GC not active");
        UUID uuid = UuidArgument.getUuid(ctx, "uuid");
        boolean removed = gc.registry().unprotect(uuid);
        gc.registry().save();
        if (removed) {
            ctx.getSource().sendSuccess(() -> Component.literal("unprotected " + uuid)
                    .withStyle(ChatFormatting.GREEN), true);
        } else {
            ctx.getSource().sendSuccess(() -> Component.literal("was not protected: " + uuid)
                    .withStyle(ChatFormatting.GRAY), false);
        }
        return 1;
    }

    private static int listProtected(CommandContext<CommandSourceStack> ctx) {
        DiskImageGc gc = ScevGc.active();
        if (gc == null) return fail(ctx, "GC not active");
        Set<UUID> pinned = gc.registry().protectedUuidsCopy();
        CommandSourceStack src = ctx.getSource();
        if (pinned.isEmpty()) {
            src.sendSuccess(() -> Component.literal("no protected UUIDs").withStyle(ChatFormatting.GRAY), false);
            return 1;
        }
        src.sendSuccess(() -> Component.literal(pinned.size() + " protected UUID(s):")
                .withStyle(ChatFormatting.AQUA), false);
        for (UUID u : pinned) {
            src.sendSuccess(() -> Component.literal("  " + u).withStyle(ChatFormatting.WHITE), false);
        }
        return 1;
    }

    /* -----------------------------------------------------------------
     * Shared helpers
     * ----------------------------------------------------------------- */

    /**
     * Print the deletion result line to the command source. Used by sweep
     * + sweep-execute; purge has its own richer formatting.
     */
    private static void reportResult(CommandContext<CommandSourceStack> ctx, GcResult r, String label) {
        CommandSourceStack src = ctx.getSource();
        int n = r.affected();
        String verb = r.dryRun() ? "would delete" : "deleted";
        ChatFormatting color = r.dryRun() ? ChatFormatting.YELLOW : ChatFormatting.GREEN;
        src.sendSuccess(() -> Component.literal(String.format(
                "scev GC %s: %s %d image(s), %d bytes",
                label, verb, n, r.bytesFreed()))
                .withStyle(color), !r.dryRun());
    }

    /**
     * Report a command failure to the source and return Brigadier's
     * "not successful" code (0). We don't throw {@link CommandSyntaxException}
     * because these are operational failures ("GC not active") rather than
     * malformed-command errors, and chat-feedback is more useful than the
     * default Brigadier red-bar message.
     */
    private static int fail(CommandContext<CommandSourceStack> ctx, String msg) {
        ctx.getSource().sendFailure(Component.literal("scev GC: " + msg));
        return 0;
    }
}
