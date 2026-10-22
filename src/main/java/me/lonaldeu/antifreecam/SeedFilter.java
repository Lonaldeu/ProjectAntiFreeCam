package me.lonaldeu.antifreecam;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelOutboundHandlerAdapter;
import io.netty.channel.ChannelPromise;

/**
 * Sends a connection a made-up hashed world seed in its login and respawn packets. Seed-cracking mods check their
 * guesses against the real one; the client only uses it to blend biome colours at their edges.
 */
final class SeedFilter extends ChannelOutboundHandlerAdapter {

    static final String NAME = "antifreecam_seed";

    private final AntiFreeCam plugin;
    private boolean warned;

    private SeedFilter(AntiFreeCam plugin) {
        this.plugin = plugin;
    }

    /** Adds one to a connection, ahead of the server's packet handler, unless it has one. Any thread. */
    static void install(AntiFreeCam plugin, Channel channel) {
        channel.eventLoop().execute(() -> {
            var pipeline = channel.pipeline();
            if (pipeline.get(NAME) != null) return;
            if (pipeline.get("packet_handler") != null) pipeline.addBefore("packet_handler", NAME, new SeedFilter(plugin));
            else pipeline.addLast(NAME, new SeedFilter(plugin));
        });
    }

    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        Object out = msg;
        if (plugin.hideHashedSeed) {
            try {
                out = plugin.nms.withHashedSeed(msg, plugin.hashedSeed);
            } catch (RuntimeException e) { // the packet goes out as it is
                if (!warned) plugin.getLogger().warning("Could not replace the hashed seed in " + msg.getClass().getSimpleName() + ": " + e);
                warned = true;
            }
        }
        ctx.write(out, promise);
    }
}
