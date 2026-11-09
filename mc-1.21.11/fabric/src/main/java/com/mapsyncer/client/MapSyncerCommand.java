package com.mapsyncer.client;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.commands.arguments.DimensionArgument;
import net.minecraft.resources.Identifier;

public class MapSyncerCommand {

    public static void registerClientCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        dispatcher.register(
                ClientCommandManager.literal("mapsyncer")
                        .executes(ctx -> { MapSyncerCommandLogic.showHelp(false); return Command.SINGLE_SUCCESS; })
                        .then(ClientCommandManager.literal("help")
                                .executes(ctx -> { MapSyncerCommandLogic.showHelp(false); return Command.SINGLE_SUCCESS; }))
                        .then(ClientCommandManager.literal("sync")
                                .executes(ctx -> MapSyncerCommandLogic.executeSyncCurrentDim())
                                .then(ClientCommandManager.literal("all")
                                        .executes(ctx -> MapSyncerCommandLogic.executeSyncAll(false)))
                                .then(ClientCommandManager.argument("dimension", DimensionArgument.dimension())
                                        .suggests((ctx, builder) -> { MapSyncerCommandLogic.suggestDimensions(builder); return builder.buildFuture(); })
                                        .executes(ctx -> {
                                            Identifier loc = ctx.getArgument("dimension", Identifier.class);
                                            return MapSyncerCommandLogic.executeSyncDimension(loc.toString());
                                        })))

                        .then(ClientCommandManager.literal("autosync")
                                .executes(ctx -> MapSyncerCommandLogic.executeAutoSyncStatus())
                                .then(ClientCommandManager.literal("on")
                                        .executes(ctx -> MapSyncerCommandLogic.setClientAutoSync(true)))
                                .then(ClientCommandManager.literal("off")
                                        .executes(ctx -> MapSyncerCommandLogic.setClientAutoSync(false))))
        );
    }
}
