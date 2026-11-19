package com.ghostchu.codframe;

import com.alessiodp.lastloginapi.api.LastLogin;
import com.alessiodp.lastloginapi.api.interfaces.LastLoginAPI;
import com.alessiodp.lastloginapi.api.interfaces.LastLoginPlayer;
import net.kyori.adventure.inventory.Book;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemFrame;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByBlockEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.world.EntitiesLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.BookMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.NotNull;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public final class CodFrame extends JavaPlugin implements Listener {
    private final NamespacedKey KEY = new NamespacedKey(this, "owner");
    private final NamespacedKey NAME_KEY = new NamespacedKey(this, "owner_name");
    private final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private final ConcurrentHashMap<UUID, String> nameCache = new ConcurrentHashMap<>();

    /**
     * Legacy HammerCore item-frame-lock storage. HammerCore builds its keys with
     * {@code new NamespacedKey(plugin, key)}, which lowercases the key, so the
     * values on disk are {@code hammercore:owneruuid}, {@code hammercore:ownerusername}
     * and {@code hammercore:header}.
     */
    private final NamespacedKey LEGACY_OWNER_UUID = new NamespacedKey("hammercore", "owneruuid");
    private final NamespacedKey LEGACY_OWNER_NAME = new NamespacedKey("hammercore", "ownerusername");
    private final NamespacedKey LEGACY_HEADER = new NamespacedKey("hammercore", "header");

    private boolean migrateHammerCore;

    LastLoginAPI lastLoginAPI;

    @Override
    public void onEnable() {
        // Plugin startup logic
        saveDefaultConfig();
        migrateHammerCore = getConfig().getBoolean("migration.hammercore", true);

        if (getServer().getPluginManager().getPlugin("LastLoginAPI") != null) {
            if (getServer().getPluginManager().getPlugin("LastLoginAPI").isEnabled()) {
                lastLoginAPI = LastLogin.getApi();
            }
        }

        Bukkit.getPluginManager().registerEvents(this, this);
    }

    @Override
    public void onDisable() {
        // Plugin shutdown logic
    }

    /**
     * Reads the CodFrame owner without touching the frame. Kept side-effect free
     * so other plugins may call it from any thread that already owns the entity.
     */
    public Optional<UUID> queryProtection(ItemFrame frame) {
        String dat = frame.getPersistentDataContainer().get(KEY, PersistentDataType.STRING);
        if (dat == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(UUID.fromString(dat));
        } catch (IllegalArgumentException ignored) {
            // Corrupted CodFrame entry: treat the frame as unprotected.
            return Optional.empty();
        }
    }

    /**
     * Returns the CodFrame owner, importing a legacy HammerCore lock on the way if
     * one exists. Must be called from the thread that owns the frame's region.
     */
    private Optional<UUID> resolveProtection(ItemFrame frame) {
        Optional<UUID> current = queryProtection(frame);
        if (current.isPresent()) {
            return current;
        }
        return migrateLegacyProtection(frame);
    }

    /**
     * Converts a HammerCore item-frame lock into CodFrame's own format.
     *
     * <p>HammerCore stored the owner UUID as a two-element {@code LONG_ARRAY} plus the
     * owner name as a string. CodFrame keeps a single {@code owner} UUID string and an
     * auxiliary {@code owner_name} so the owner can still be displayed after a restart
     * without LastLoginAPI. Legacy keys are removed only once the CodFrame entry has
     * been written, so a failed migration never loses the original data.</p>
     */
    private Optional<UUID> migrateLegacyProtection(ItemFrame frame) {
        if (!migrateHammerCore) {
            return Optional.empty();
        }

        PersistentDataContainer data = frame.getPersistentDataContainer();
        long[] rawUuid = data.get(LEGACY_OWNER_UUID, PersistentDataType.LONG_ARRAY);
        String legacyName = data.get(LEGACY_OWNER_NAME, PersistentDataType.STRING);

        UUID owner;
        if (rawUuid != null) {
            if (rawUuid.length != 2) {
                getLogger().warning("Ignoring malformed HammerCore owner UUID on frame at " + describe(frame));
                return Optional.empty();
            }
            owner = new UUID(rawUuid[0], rawUuid[1]);
        } else if (legacyName != null && !legacyName.isBlank()) {
            // Defensive fallback for a partially written HammerCore record. Resolving a
            // name is only possible when the server already knows that player.
            OfflinePlayer offlineOwner = Bukkit.getOfflinePlayer(legacyName);
            if (!offlineOwner.hasPlayedBefore() && !offlineOwner.isOnline()) {
                getLogger().warning("Cannot migrate HammerCore frame at " + describe(frame)
                        + ": unknown owner '" + legacyName + "'");
                return Optional.empty();
            }
            owner = offlineOwner.getUniqueId();
        } else {
            return Optional.empty();
        }

        data.set(KEY, PersistentDataType.STRING, owner.toString());
        if (legacyName != null && !legacyName.isBlank()) {
            data.set(NAME_KEY, PersistentDataType.STRING, legacyName);
            nameCache.put(owner, legacyName);
        }
        data.remove(LEGACY_OWNER_UUID);
        data.remove(LEGACY_OWNER_NAME);
        data.remove(LEGACY_HEADER);

        // HammerCore protected frames through event cancellation only; CodFrame also
        // freezes and invulnerates them, so apply those flags during the migration.
        frame.setFixed(true);
        frame.setInvulnerable(true);

        getLogger().info("Migrated HammerCore frame protection at " + describe(frame)
                + " to CodFrame (owner " + (legacyName != null ? legacyName : owner) + ")");
        return Optional.of(owner);
    }

    private static String describe(ItemFrame frame) {
        return frame.getWorld().getName() + " " + frame.getLocation().getBlockX()
                + "," + frame.getLocation().getBlockY() + "," + frame.getLocation().getBlockZ();
    }

    public boolean claimProtection(ItemFrame frame, UUID uuid) {
        frame.setFixed(true);
        frame.setInvulnerable(true);
        if (resolveProtection(frame).isPresent()) {
            return false;
        }
        frame.getPersistentDataContainer().set(KEY, PersistentDataType.STRING, uuid.toString());
        return true;
    }

    public boolean removeProtection(ItemFrame frame, UUID uuid) {
        if (resolveProtection(frame).isEmpty()) {
            return false;
        }
        frame.setFixed(false);
        frame.getPersistentDataContainer().remove(KEY);
        frame.getPersistentDataContainer().remove(NAME_KEY);
        frame.setInvulnerable(false);
        return true;
    }

    public void openBook(ItemFrame frame, Player player) {
        if (frame.getItem().getItemMeta() instanceof BookMeta meta) {
            Component author = meta.author();
            Component title = meta.title();
            if (author == null) {
                author = MINI_MESSAGE.deserialize(getConfig().getString("messages.book.anonymous", ""));
            }
            if (title == null) {
                title = MINI_MESSAGE.deserialize(getConfig().getString("messages.book.no-title", ""));
            }
            player.openBook(Book.book(title, author, meta.pages()));
        }
    }

    public void playerDoProtection(Player player, ItemFrame frame) {
        getServer().getRegionScheduler().execute(this, frame.getLocation(), () -> {
            if (claimProtection(frame, player.getUniqueId())) {
                frame.getPersistentDataContainer().set(NAME_KEY, PersistentDataType.STRING, player.getName());
                nameCache.put(player.getUniqueId(), player.getName());
                player.sendMessage(MINI_MESSAGE.deserialize(getConfig().getString("messages.frame.success-set", "")));
            } else {
                Optional<UUID> uuid = resolveProtection(frame);
                if (uuid.get().equals(player.getUniqueId()) || player.hasPermission("codframe.admin")) {
                    removeProtection(frame, player.getUniqueId());
                    player.sendMessage(MINI_MESSAGE.deserialize(getConfig().getString("messages.frame.success-remove", "")));
                } else {
                    String message = getConfig()
                            .getString("messages.frame.failed-other-protected", "")
                            .replace("<0>", getPlayerName(frame, uuid.get()));
                    player.sendMessage(MINI_MESSAGE.deserialize(message));
                }
            }
        });
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (sender instanceof Player player) {
            player.getScheduler().run(this, task -> {
                Entity target = player.getTargetEntity(4);
                if (target instanceof ItemFrame frame) {
                    playerDoProtection(player, frame);
                } else {
                    player.sendMessage(MINI_MESSAGE.deserialize(getConfig().getString("messages.frame.failed-invalid", "")));
                }
            }, null);
            return true;
        }
        return false;
    }

    @EventHandler(ignoreCancelled = true)
    public void onFrameDropping(HangingBreakEvent event) {
        if (event.getEntity() instanceof ItemFrame frame) {
            if (resolveProtection(frame).isPresent()) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFrameDamaging(EntityDamageEvent event) {
        if (event.getEntity() instanceof ItemFrame frame) {
            if (resolveProtection(frame).isPresent()) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFrameDamaging(EntityDamageByBlockEvent event) {
        if (event.getEntity() instanceof ItemFrame frame) {
            if (resolveProtection(frame).isPresent()) {
                event.setCancelled(true);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onFrameDamaging(EntityDamageByEntityEvent event) {
        if (event.getEntity() instanceof ItemFrame frame) {
            if (resolveProtection(frame).isPresent()) {
                event.setCancelled(true);
            }
        }
    }

    /**
     * Fires whenever a chunk's entities are loaded, including chunks that were
     * already resident when the plugin enabled. HammerCore frames are imported here
     * before any player can interact with them, and the event runs on the region
     * thread that owns the entities, so direct entity access is safe on Folia too.
     */
    @EventHandler
    public void onEntitiesLoad(EntitiesLoadEvent event) {
        if (!migrateHammerCore) {
            return;
        }
        for (Entity entity : event.getEntities()) {
            if (entity instanceof ItemFrame frame) {
                resolveProtection(frame);
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void interactFrame(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof ItemFrame frame && event.getHand() == EquipmentSlot.HAND) {
            Optional<UUID> uuid = resolveProtection(frame);
            if (event.getPlayer().isSneaking()) {
                event.setCancelled(true);
                playerDoProtection(event.getPlayer(), frame);
            } else if (uuid.isPresent()) {
                event.setCancelled(true);
                event.getPlayer().sendActionBar(
                        MINI_MESSAGE.deserialize(getConfig()
                                .getString("messages.general.owner-info", "")
                                .replace("<0>", getPlayerName(frame, uuid.get()))
                        )
                );
                if (frame.getItem().hasItemMeta()) {
                    openBook(frame, event.getPlayer());
                    sendChatPreview(frame.getItem(), event.getPlayer());
                }
            }
        }
    }

    private void sendChatPreview(ItemStack item, Player player) {
        player.sendMessage(MINI_MESSAGE.deserialize(getConfig().getString("messages.general.hover-preview", "")).hoverEvent(item.asHoverEvent()));
    }

    private @NotNull String getPlayerName(ItemFrame frame, UUID uuid) {
        String cached = nameCache.get(uuid);
        if (cached != null) {
            return cached;
        }
        String stored = frame.getPersistentDataContainer().get(NAME_KEY, PersistentDataType.STRING);
        if (stored != null && !stored.isBlank()) {
            nameCache.put(uuid, stored);
            return stored;
        }
        if (lastLoginAPI != null) {
            LastLoginPlayer lastLoginPlayer = lastLoginAPI.getPlayer(uuid);
            String name = lastLoginPlayer.getName();
            if (name != null) {
                nameCache.put(uuid, name);
                return name;
            }
        }
        asyncBackfillName(uuid);
        return uuid.toString();
    }

    private void asyncBackfillName(UUID uuid) {
        if (nameCache.containsKey(uuid)) {
            return;
        }
        getServer().getAsyncScheduler().runNow(this, task -> {
            OfflinePlayer offlineOwner = Bukkit.getOfflinePlayer(uuid);
            String name = offlineOwner.getName();
            if (name != null) {
                nameCache.put(uuid, name);
            }
        });
    }
}
