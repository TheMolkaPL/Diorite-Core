package org.diorite.impl.command.defaults;

import java.util.Arrays;
import java.util.regex.Pattern;

import org.diorite.impl.command.SystemCommandImpl;
import org.diorite.command.CommandPriority;

public class TpsCmd extends SystemCommandImpl
{
    public TpsCmd()
    {
        super("tps", (Pattern) null, CommandPriority.LOW);
        this.setCommandExecutor((sender, command, label, matchedPattern, args) -> sender.sendMessage("§aAverage tps (1,5,15 min): §9" + Arrays.toString(sender.getServer().getRecentTps()) + ", TPS limit: " + sender.getServer().getTps() + ", Server speed multi: " + sender.getServer().getMutli()));
    }
}
