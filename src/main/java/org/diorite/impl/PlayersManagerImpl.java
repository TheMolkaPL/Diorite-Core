package org.diorite.impl;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import org.apache.commons.lang3.builder.ToStringBuilder;
import org.apache.commons.lang3.builder.ToStringStyle;

import org.diorite.impl.auth.GameProfile;
import org.diorite.impl.connection.NetworkManager;
import org.diorite.impl.connection.packets.Packet;
import org.diorite.impl.connection.packets.PacketDataSerializer;
import org.diorite.impl.connection.packets.play.out.PacketPlayOutAbilities;
import org.diorite.impl.connection.packets.play.out.PacketPlayOutCustomPayload;
import org.diorite.impl.connection.packets.play.out.PacketPlayOutHeldItemSlot;
import org.diorite.impl.connection.packets.play.out.PacketPlayOutKeepAlive;
import org.diorite.impl.connection.packets.play.out.PacketPlayOutLogin;
import org.diorite.impl.connection.packets.play.out.PacketPlayOutPlayerInfo;
import org.diorite.impl.connection.packets.play.out.PacketPlayOutPosition;
import org.diorite.impl.connection.packets.play.out.PacketPlayOutServerDifficulty;
import org.diorite.impl.connection.packets.play.out.PacketPlayOutSpawnPosition;
import org.diorite.impl.entity.EntityImpl;
import org.diorite.impl.entity.PlayerImpl;
import org.diorite.impl.inventory.item.ItemMetaImpl;
import org.diorite.BlockLocation;
import org.diorite.Difficulty;
import org.diorite.GameMode;
import org.diorite.ImmutableLocation;
import org.diorite.TeleportData;
import org.diorite.chat.ChatPosition;
import org.diorite.chat.component.TextComponent;
import org.diorite.entity.Player;
import org.diorite.inventory.item.ItemStack;
import org.diorite.material.Material;
import org.diorite.world.Dimension;
import org.diorite.world.WorldType;

import io.netty.buffer.Unpooled;

public class PlayersManagerImpl implements Tickable
{
    private final Map<UUID, PlayerImpl> players = new ConcurrentHashMap<>(100, 0.2f, 8);
    private final ServerImpl server;
    private final int        keepAliveTimer;

    private transient long lastKeepAlive = System.currentTimeMillis();

    public PlayersManagerImpl(final ServerImpl server)
    {
        this.server = server;
        this.keepAliveTimer = (int) TimeUnit.SECONDS.toMillis(this.server.getKeepAliveTimer());
    }

    public PlayerImpl createPlayer(final GameProfile gameProfile, final NetworkManager networkManager)
    {// TODO: loading player
        //noinspection MagicNumber

        final PlayerImpl player = new PlayerImpl(this.server, EntityImpl.ENTITY_ID.getAndIncrement(), gameProfile, networkManager, new ImmutableLocation(4, 255, - 4, 0, 0, this.server.getWorldsManager().getDefaultWorld()));
        this.players.put(gameProfile.getId(), player);
//        player.getWorld().addEntity(player);
        return player;
    }

    @SuppressWarnings("MagicNumber")
    public void playerJoin(final PlayerImpl player)
    {
        // TODO: this is only test code
        player.getNetworkManager().sendPacket(new PacketPlayOutLogin(player.getId(), GameMode.SURVIVAL, false, Dimension.OVERWORLD, Difficulty.PEACEFUL, 20, WorldType.FLAT));
        player.getNetworkManager().sendPacket(new PacketPlayOutCustomPayload("MC|Brand", new PacketDataSerializer(Unpooled.buffer()).writeText(this.server.getServerModName())));
        player.getNetworkManager().sendPacket(new PacketPlayOutServerDifficulty(Difficulty.EASY));
        player.getNetworkManager().sendPacket(new PacketPlayOutSpawnPosition(new BlockLocation(2, 255, - 2)));
        player.getNetworkManager().sendPacket(new PacketPlayOutAbilities(false, false, false, false, Player.WALK_SPEED, Player.FLY_SPEED));
        player.getNetworkManager().sendPacket(new PacketPlayOutHeldItemSlot(3));
        player.getNetworkManager().sendPacket(new PacketPlayOutPosition(new TeleportData(4, 255, - 4)));

        // TODO: changeable message, events, etc..
        this.server.broadcastSimpleColoredMessage(ChatPosition.ACTION, "&3&l" + player.getName() + "&7&l join to the server!");
        this.server.broadcastSimpleColoredMessage(ChatPosition.SYSTEM, "&3" + player.getName() + "&7 join to the server!");
//        this.server.sendConsoleSimpleColoredMessage("&3" + player.getName() + " &7join to the server.");

        this.forEach(new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.PlayerInfoAction.ADD_PLAYER, player.getGameProfile()));

        this.server.updatePlayerListHeaderAndFooter(TextComponent.fromLegacyText("Welcome to Diorite!"), TextComponent.fromLegacyText("http://diorite.org"), player); // TODO Tests, remove it
        player.sendTitle(TextComponent.fromLegacyText("Welcome to Diorite"), TextComponent.fromLegacyText("http://diorite.org"), 20, 100, 20); // TODO Tests, remove it

        player.getInventory().add(new ItemStack(Material.ACACIA_FENCE));
        player.getInventory().update();
        /*EntityMinecartRideable entity = new EntityMinecartRideable(4, 180, -4);

        player.getNetworkManager().sendPacket(new PacketPlayOutSpawnEntity(entity));
        List<EntityMetadataObject> tff = new ArrayList<>();
        tff.add(new EntityMetadataObject(EntityMetadataCodec.DataType.BYTE, 0, 0x00));
        tff.add(new EntityMetadataObject(EntityMetadataCodec.DataType.INT, 17, 2));
        player.getNetworkManager().sendPacket(new PacketPlayOutEntityMetadata(666, tff));*/ // TODO ENTITY TEST
    }

    public List<String> getOnlinePlayersNames()
    {
        return this.players.values().parallelStream().map(PlayerImpl::getName).collect(Collectors.toList());
    }

    public List<String> getOnlinePlayersNames(final String prefix)
    {
        final String lcPrefix = prefix.toLowerCase();
        return this.players.values().parallelStream().map(PlayerImpl::getName).filter(s -> s.toLowerCase().startsWith(lcPrefix)).sorted().collect(Collectors.toList());
    }

    public Map<UUID, PlayerImpl> getRawPlayers()
    {
        return this.players;
    }

    public void playerQuit(final PlayerImpl player)
    {
        this.forEach(new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.PlayerInfoAction.REMOVE_PLAYER, player.getGameProfile()));
        this.players.remove(player.getUniqueID());
    }

    public void playerQuit(final UUID uuid)
    {
        final PlayerImpl player = this.players.remove(uuid);
        if (player != null)
        {
            this.playerQuit(player);
        }
        else
        {
            this.forEach(new PacketPlayOutPlayerInfo(PacketPlayOutPlayerInfo.PlayerInfoAction.REMOVE_PLAYER, new GameProfile(uuid, null))); // name isn't needed when removing player
        }
    }

    @Override
    public void doTick(final int tps)
    {
        final long curr = System.currentTimeMillis();
        this.players.values().parallelStream().forEach(p -> p.getPlayerChunks().doTick(tps));
        if ((curr - this.lastKeepAlive) > this.keepAliveTimer)
        {
            this.players.values().parallelStream().forEach(p -> p.getNetworkManager().sendPacket(new PacketPlayOutKeepAlive(p.getId())));
            this.lastKeepAlive = curr;
        }
    }

    public void forEach(final Packet<?> packet)
    {
        this.forEach(player -> player.getNetworkManager().sendPacket(packet));
    }

    public Collection<PlayerImpl> getOnlinePlayers(final Predicate<PlayerImpl> predicate)
    {
        return this.players.values().stream().filter(predicate).collect(Collectors.toSet());
    }

    public void forEach(final Predicate<PlayerImpl> predicate, final Packet<?> packet)
    {
        this.forEach(predicate, player -> player.getNetworkManager().sendPacket(packet));
    }

    public void forEach(final Consumer<PlayerImpl> consumer)
    {
        this.players.values().parallelStream().forEach(consumer);
    }

    public void forEach(final Predicate<PlayerImpl> predicate, final Consumer<PlayerImpl> consumer)
    {
        this.players.values().parallelStream().filter(predicate).forEach(consumer);
    }

    public ServerImpl getServer()
    {
        return this.server;
    }

    @Override
    public String toString()
    {
        return new ToStringBuilder(this, ToStringStyle.SHORT_PREFIX_STYLE).appendSuper(super.toString()).append("players", this.players).append("server", this.server).toString();
    }
}
