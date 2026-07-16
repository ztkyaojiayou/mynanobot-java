package com.nanobot.command.impl;

import com.nanobot.command.Command;
import com.nanobot.command.CommandContext;
import com.nanobot.command.CommandRegistry;

/** /help — 列出所有命令。 */
public class HelpCommand implements Command {
    private final CommandRegistry registry;
    public HelpCommand(CommandRegistry registry) { this.registry = registry; }

    @Override public String name() { return "help"; }
    @Override public String description() { return "显示帮助"; }

    @Override
    public boolean execute(CommandContext ctx, String input) {
        System.out.print(registry.helpText());
        return false;
    }
}
