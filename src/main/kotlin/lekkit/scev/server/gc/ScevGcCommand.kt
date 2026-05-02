/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/.
 */
package lekkit.scev.server.gc

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.context.CommandContext
import com.mojang.logging.LogUtils
import java.util.UUID
import net.minecraft.ChatFormatting
import net.minecraft.commands.CommandSourceStack
import net.minecraft.commands.Commands
import net.minecraft.commands.arguments.UuidArgument
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.Style

/**
 * `/scev gc ...` command tree. All subcommands require OP-level (level 2)
 * permission.
 *
 * ```
 * /scev gc status                 show config + registry stats
 * /scev gc sweep                  dry-run preview of a sweep
 * /scev gc sweep execute          run a sweep now (respects retention)
 * /scev gc purge                  dry-run preview + issue confirm token
 * /scev gc purge confirm <TOKEN>  execute purge (bypasses retention)
 * /scev gc protect <uuid>         pin UUID against all GC paths
 * /scev gc unprotect <uuid>       release pin
 * /scev gc list-protected         list pinned UUIDs
 * ```
 *
 * Purge is the only path that can't be undone — retention and grace are
 * both bypassed, no trash staging. The two-step [PurgeTokenStore] handshake
 * forces the operator to see the preview and type a fresh unpredictable
 * token before anything dies. Tokens are scoped to the
 * [CommandSourceStack.getTextName] that issued them, so a token printed in
 * one operator's chat can't be consumed by another.
 */
object ScevGcCommand {
    private val LOG = LogUtils.getLogger()

    /** Shared across all command invocations for this server. */
    private val PURGE_TOKENS = PurgeTokenStore()

    @JvmStatic fun register(dispatcher: CommandDispatcher<CommandSourceStack>) {
        dispatcher.register(
            Commands.literal("scev").requires { it.hasPermission(2) }
                .then(Commands.literal("gc")
                    .then(Commands.literal("status").executes(::status))
                    .then(Commands.literal("sweep")
                        .executes(::sweepDryRun)
                        .then(Commands.literal("execute").executes(::sweepExecute)))
                    .then(Commands.literal("purge")
                        .executes(::purgeDryRun)
                        .then(Commands.literal("confirm")
                            .then(Commands.argument("token", StringArgumentType.word())
                                .executes(::purgeConfirm))))
                    .then(Commands.literal("protect")
                        .then(Commands.argument("uuid", UuidArgument.uuid()).executes(::protect)))
                    .then(Commands.literal("unprotect")
                        .then(Commands.argument("uuid", UuidArgument.uuid()).executes(::unprotect)))
                    .then(Commands.literal("list-protected").executes(::listProtected)))
        )
    }

    /* ----- Subcommand handlers -------------------------------------------- */

    private fun status(ctx: CommandContext<CommandSourceStack>): Int = withGc(ctx) { gc ->
        val p = gc.policy()
        val reg = gc.registry()
        ctx.tell("scev GC status", ChatFormatting.AQUA)
        ctx.tell("  images on disk:    ${gc.onDiskImageCount()}")
        ctx.tell("  tracked UUIDs:     ${reg.trackedCount()}")
        ctx.tell("  protected UUIDs:   ${reg.protectedCount()}")
        ctx.tell("  creation grace:    ${p.creationGraceMillis / 60_000L} min")
        ctx.tell("  sweep retention:   ${p.sweepRetentionMillis / 86_400_000L} days")
        ctx.tell("  sweep interval:    ${p.sweepIntervalMillis / 3_600_000L} hours")
    }

    private fun sweepDryRun(ctx: CommandContext<CommandSourceStack>): Int = withGc(ctx) { gc ->
        reportResult(ctx, GcRunner.sweep(gc, ctx.source.server, true), "sweep")
    }

    private fun sweepExecute(ctx: CommandContext<CommandSourceStack>): Int = withGc(ctx) { gc ->
        val r = GcRunner.sweep(gc, ctx.source.server, false)
        reportResult(ctx, r, "sweep")
        LOG.info("[scev-gc] /scev gc sweep execute by {}: deleted={} freed={} bytes",
            ctx.source.textName, r.affected(), r.bytesFreed)
    }

    private fun purgeDryRun(ctx: CommandContext<CommandSourceStack>): Int = withGc(ctx) { gc ->
        val preview = GcRunner.purge(gc, ctx.source.server, true)
        ctx.tell("scev GC PURGE (dry-run)", ChatFormatting.YELLOW)
        ctx.tell("  would delete ${preview.wouldDelete.size} image(s), freeing ${preview.bytesFreed} bytes")

        if (preview.wouldDelete.isEmpty()) {
            ctx.tell("  (nothing to purge)", ChatFormatting.GRAY)
            return@withGc
        }

        // Sample of up to 5 UUIDs so the operator can sanity-check.
        preview.wouldDelete.asSequence().take(5).forEach { u ->
            ctx.tell("    - ${u.toString().substring(0, 8)}...", ChatFormatting.GRAY)
        }
        if (preview.wouldDelete.size > 5) ctx.tell("    ...", ChatFormatting.GRAY)

        val issuer = ctx.source.textName
        val token = PURGE_TOKENS.issue(issuer, System.currentTimeMillis())
        val confirmCmd = "/scev gc purge confirm $token"
        val style = Style.EMPTY
            .withColor(ChatFormatting.GREEN)
            .withClickEvent(ClickEvent(ClickEvent.Action.SUGGEST_COMMAND, confirmCmd))
            .withHoverEvent(HoverEvent(HoverEvent.Action.SHOW_TEXT, Component.literal("Click to fill $confirmCmd")))
        ctx.source.sendSuccess(
            { Component.literal("  confirm: ").append(Component.literal(confirmCmd).withStyle(style)) },
            false
        )
        ctx.tell("  token expires in ${PurgeTokenStore.TOKEN_TTL_MILLIS / 1000} s", ChatFormatting.GRAY)
    }

    private fun purgeConfirm(ctx: CommandContext<CommandSourceStack>): Int {
        val gc = ScevGc.active() ?: return fail(ctx, "GC not active — no world loaded?")
        val token = StringArgumentType.getString(ctx, "token")
        val issuer = ctx.source.textName
        if (!PURGE_TOKENS.consume(issuer, token, System.currentTimeMillis())) {
            return fail(ctx, "invalid or expired token — run '/scev gc purge' to get a fresh one")
        }
        val r = GcRunner.purge(gc, ctx.source.server, false)
        ctx.source.sendSuccess(
            { Component.literal("scev GC purge: deleted ${r.deleted.size} image(s), freed ${r.bytesFreed} bytes")
                .withStyle(ChatFormatting.GREEN) },
            true
        )
        LOG.info("[scev-gc] /scev gc purge confirm by {}: deleted={} freed={} bytes",
            issuer, r.deleted.size, r.bytesFreed)
        return 1
    }

    private fun protect(ctx: CommandContext<CommandSourceStack>): Int = withGc(ctx) { gc ->
        val uuid = UuidArgument.getUuid(ctx, "uuid")
        val added = gc.registry().protect(uuid)
        gc.registry().save()
        if (added) ctx.tell("protected $uuid", ChatFormatting.GREEN, broadcast = true)
        else       ctx.tell("already protected: $uuid", ChatFormatting.GRAY)
    }

    private fun unprotect(ctx: CommandContext<CommandSourceStack>): Int = withGc(ctx) { gc ->
        val uuid = UuidArgument.getUuid(ctx, "uuid")
        val removed = gc.registry().unprotect(uuid)
        gc.registry().save()
        if (removed) ctx.tell("unprotected $uuid", ChatFormatting.GREEN, broadcast = true)
        else         ctx.tell("was not protected: $uuid", ChatFormatting.GRAY)
    }

    private fun listProtected(ctx: CommandContext<CommandSourceStack>): Int = withGc(ctx) { gc ->
        val pinned = gc.registry().protectedUuidsCopy()
        if (pinned.isEmpty()) {
            ctx.tell("no protected UUIDs", ChatFormatting.GRAY)
            return@withGc
        }
        ctx.tell("${pinned.size} protected UUID(s):", ChatFormatting.AQUA)
        pinned.forEach { ctx.tell("  $it", ChatFormatting.WHITE) }
    }

    /* ----- Shared helpers -------------------------------------------------- */

    /**
     * Run [action] only if a GC is active; otherwise reply "GC not active"
     * and return 0. Each subcommand returns 1 on a successful (even if
     * no-op) action; 0 means "operationally not applicable."
     */
    private inline fun withGc(
        ctx: CommandContext<CommandSourceStack>,
        action: (DiskImageGc) -> Unit,
    ): Int {
        val gc = ScevGc.active() ?: return fail(ctx, "GC not active — no world loaded?")
        action(gc)
        return 1
    }

    /** Send a literal line. [broadcast] mirrors `sendSuccess`'s `success` flag. */
    private fun CommandContext<CommandSourceStack>.tell(
        text: String,
        style: ChatFormatting? = null,
        broadcast: Boolean = false,
    ) {
        val component = Component.literal(text).also { if (style != null) it.withStyle(style) }
        source.sendSuccess({ component }, broadcast)
    }

    /** Print sweep / sweep-execute result line. Purge has its own richer formatting. */
    private fun reportResult(ctx: CommandContext<CommandSourceStack>, r: GcResult, label: String) {
        val verb = if (r.dryRun) "would delete" else "deleted"
        val color = if (r.dryRun) ChatFormatting.YELLOW else ChatFormatting.GREEN
        ctx.tell("scev GC $label: $verb ${r.affected()} image(s), ${r.bytesFreed} bytes",
            color, broadcast = !r.dryRun)
    }

    /**
     * Report a command failure and return Brigadier's "not successful" code.
     * Doesn't throw `CommandSyntaxException` because these are operational
     * failures ("GC not active"), not malformed-command errors — chat
     * feedback is more useful than Brigadier's default red-bar message.
     */
    private fun fail(ctx: CommandContext<CommandSourceStack>, msg: String): Int {
        ctx.source.sendFailure(Component.literal("scev GC: $msg"))
        return 0
    }
}
