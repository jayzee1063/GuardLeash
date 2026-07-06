package ru.lunify.guardleash;

import com.destroystokyo.paper.event.player.PlayerJumpEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.LeashHitch;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.entity.Zombie;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityCombustEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityTargetEvent;
import org.bukkit.event.entity.EntityUnleashEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.bukkit.projectiles.ProjectileSource;
import org.bukkit.scheduler.BukkitTask;
import org.bukkit.util.StringUtil;
import org.bukkit.util.Vector;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public final class GuardLeashPlugin extends JavaPlugin implements Listener, TabExecutor {
    private final MiniMessage miniMessage = MiniMessage.miniMessage();
    private final Map<UUID, LeashSession> sessionsByTarget = new HashMap<>();
    private final Map<UUID, Set<UUID>> sessionsByHolder = new HashMap<>();
    private final Map<UUID, UUID> targetByProxy = new HashMap<>();
    private final Map<UUID, Set<UUID>> targetsByKnot = new HashMap<>();
    private final Map<UUID, Integer> pendingLeadReturns = new HashMap<>();
    private final Map<UUID, PendingReconnectLeash> pendingReconnectLeashes = new HashMap<>();
    private final Set<UUID> internalMoves = new HashSet<>();

    private NamespacedKey markerKey;
    private BukkitTask updateTask;
    private BukkitTask pendingReturnTask;
    private Settings settings;

    @Override
    public void onEnable() {
        markerKey = new NamespacedKey(this, "guard_leash_proxy");
        saveDefaultConfig();
        reloadSettings();

        Bukkit.getPluginManager().registerEvents(this, this);
        Objects.requireNonNull(getCommand("guardleash"), "guardleash command").setExecutor(this);
        Objects.requireNonNull(getCommand("guardleash"), "guardleash command").setTabCompleter(this);
    }

    @Override
    public void onDisable() {
        stopTasks();
        for (LeashSession session : new ArrayList<>(sessionsByTarget.values())) {
            release(session, onlinePlayer(session.ownerUuid), ReleaseReason.PLUGIN_DISABLE, settings != null && settings.returnLeadOnAutoRelease, false);
        }
        for (PendingReconnectLeash pending : new ArrayList<>(pendingReconnectLeashes.values())) {
            finishPendingReconnectWithoutRestore(pending, false);
        }
        cleanupMarkedEntities();
    }

    private void startTasks() {
        stopTasks();
        updateTask = Bukkit.getScheduler().runTaskTimer(this, this::tickSessions, 1L, Math.max(1L, settings.updateIntervalTicks));
        pendingReturnTask = Bukkit.getScheduler().runTaskTimer(this, this::tickPendingTasks, 20L, 20L);
    }

    private void stopTasks() {
        if (updateTask != null) {
            updateTask.cancel();
            updateTask = null;
        }
        if (pendingReturnTask != null) {
            pendingReturnTask.cancel();
            pendingReturnTask = null;
        }
    }

    private void reloadSettings() {
        reloadConfig();
        settings = Settings.from(this);
        startTasks();
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPlayerInteractEntity(PlayerInteractEntityEvent event) {
        if (event.getRightClicked() instanceof LeashHitch hitch) {
            handleKnotInteract(event.getPlayer(), hitch, event);
            return;
        }

        if (!(event.getRightClicked() instanceof Player target)) {
            return;
        }

        Player actor = event.getPlayer();
        if (!isValidLeashHand(actor, event.getHand()) || !isLeashItem(itemInHand(actor, event.getHand()))) {
            return;
        }

        LeashSession existing = sessionsByTarget.get(target.getUniqueId());
        if (existing != null) {
            event.setCancelled(true);
            tryReleaseByPlayer(actor, existing);
            return;
        }

        event.setCancelled(true);
        if (!actor.hasPermission(settings.usePermission)) {
            send(actor, "messages.no-permission", actor, target, null);
            return;
        }
        if (target.hasPermission(settings.bypassPermission)) {
            send(actor, "messages.target-bypass", actor, target, null);
            return;
        }

        createHolderLeash(actor, target, event.getHand());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onPlayerInteractAtEntity(PlayerInteractAtEntityEvent event) {
        if (event.getRightClicked() instanceof LeashHitch hitch) {
            handleKnotInteract(event.getPlayer(), hitch, event);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onFenceInteract(PlayerInteractEvent event) {
        if (event.getClickedBlock() == null || event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        if (!settings.fenceEnabled || !isFence(event.getClickedBlock())) {
            return;
        }

        Player actor = event.getPlayer();
        Set<UUID> heldTargets = sessionsByHolder.getOrDefault(actor.getUniqueId(), Collections.emptySet());
        if (heldTargets.isEmpty()) {
            return;
        }
        if (!actor.hasPermission(settings.usePermission)) {
            send(actor, "messages.no-permission", actor, null, event.getClickedBlock().getLocation());
            return;
        }

        event.setCancelled(true);
        int bound = 0;
        for (UUID targetUuid : new ArrayList<>(heldTargets)) {
            LeashSession session = sessionsByTarget.get(targetUuid);
            if (session == null || session.mode != LeashMode.HOLDER) {
                continue;
            }
            bindToFence(actor, session, event.getClickedBlock());
            bound++;
            if (!settings.bindAllHeldTargetsToFence) {
                break;
            }
        }
        if (bound == 0) {
            send(actor, "messages.no-held-players", actor, null, event.getClickedBlock().getLocation());
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onMove(PlayerMoveEvent event) {
        if (!settings.preventMovement) {
            return;
        }
        Player player = event.getPlayer();
        LeashSession session = sessionsByTarget.get(player.getUniqueId());
        if (session == null || internalMoves.contains(player.getUniqueId())) {
            return;
        }
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null) {
            return;
        }
        if (settings.allowLookingAround && samePosition(from, to)) {
            return;
        }
        if (session.mode == LeashMode.HOLDER) {
            Player holder = onlinePlayer(session.holderUuid);
            if (holder == null || !holder.getWorld().equals(player.getWorld())) {
                return;
            }

            double fromDistance = horizontalDistance(from, holder.getLocation());
            double toDistance = horizontalDistance(to, holder.getLocation());
            if (toDistance > fromDistance + settings.movementAwayTolerance) {
                event.setTo(positionWithView(from, to));
            }
            return;
        }

        event.setTo(session.lockLocationWithView(to));
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onJump(PlayerJumpEvent event) {
        if (settings.preventJump && sessionsByTarget.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!settings.preventTeleport || internalMoves.contains(event.getPlayer().getUniqueId())) {
            return;
        }
        LeashSession session = sessionsByTarget.get(event.getPlayer().getUniqueId());
        if (session != null) {
            event.setCancelled(true);
            return;
        }

        Set<UUID> held = sessionsByHolder.get(event.getPlayer().getUniqueId());
        if (held != null && !held.isEmpty()) {
            for (UUID targetUuid : new ArrayList<>(held)) {
                LeashSession heldSession = sessionsByTarget.get(targetUuid);
                if (heldSession != null) {
                    release(heldSession, event.getPlayer(), ReleaseReason.HOLDER_TELEPORT, settings.returnLeadOnAutoRelease, true);
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onDamageByEntity(EntityDamageByEntityEvent event) {
        if (isProxy(event.getEntity())) {
            event.setCancelled(true);
            return;
        }
        if (!settings.preventDamage) {
            return;
        }

        Player damager = playerDamager(event.getDamager());
        if (damager != null && sessionsByTarget.containsKey(damager.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onProxyDamage(EntityDamageEvent event) {
        if (isProxy(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (settings.preventInteract && sessionsByTarget.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!settings.preventInventoryInteract || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (sessionsByTarget.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (!settings.preventInventoryInteract || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (sessionsByTarget.containsKey(player.getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBlockBreak(BlockBreakEvent event) {
        if (settings.preventBlockBreak && sessionsByTarget.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onBlockPlace(BlockPlaceEvent event) {
        if (settings.preventBlockPlace && sessionsByTarget.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onItemDrop(PlayerDropItemEvent event) {
        if (settings.preventItemDrop && sessionsByTarget.containsKey(event.getPlayer().getUniqueId())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onProxyCombust(EntityCombustEvent event) {
        if (isProxy(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onProxyTarget(EntityTargetEvent event) {
        if (isProxy(event.getEntity())) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onProxyUnleash(EntityUnleashEvent event) {
        UUID targetUuid = targetByProxy.get(event.getEntity().getUniqueId());
        if (targetUuid == null) {
            return;
        }
        event.setDropLeash(false);
        if (event.getReason() == EntityUnleashEvent.UnleashReason.DISTANCE && !settings.releaseIfTooFar) {
            event.setCancelled(true);
            LeashSession session = sessionsByTarget.get(targetUuid);
            if (session != null) {
                ensureLeashHolder(session);
            }
            return;
        }

        LeashSession session = sessionsByTarget.get(targetUuid);
        if (session != null) {
            release(session, onlinePlayer(session.ownerUuid), ReleaseReason.INVALID, settings.returnLeadOnAutoRelease, true);
        }
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.HIGHEST)
    public void onHitchBreak(HangingBreakByEntityEvent event) {
        if (!(event.getEntity() instanceof LeashHitch hitch)) {
            return;
        }
        Set<UUID> targets = targetsByKnot.get(hitch.getUniqueId());
        if (targets == null || targets.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        Player remover = event.getRemover() instanceof Player player ? player : null;
        if (remover != null && !remover.hasPermission(settings.usePermission) && !remover.hasPermission("guardleash.admin")) {
            send(remover, "messages.no-permission", remover, null, hitch.getLocation());
            return;
        }
        for (UUID targetUuid : new ArrayList<>(targets)) {
            LeashSession session = sessionsByTarget.get(targetUuid);
            if (session != null) {
                release(session, remover, ReleaseReason.MANUAL, true, true);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        LeashSession targetSession = sessionsByTarget.get(player.getUniqueId());
        if (targetSession != null && settings.releaseWhenTargetQuits) {
            Player owner = onlinePlayer(targetSession.ownerUuid);
            if (canRestoreOnReconnect(targetSession)) {
                boolean returnLeadNow = targetSession.mode == LeashMode.HOLDER && settings.returnLeadOnAutoRelease;
                pendingReconnectLeashes.put(player.getUniqueId(), PendingReconnectLeash.from(
                        targetSession,
                        System.currentTimeMillis(),
                        returnLeadNow && targetSession.consumedLead
                ));
                release(targetSession, owner, ReleaseReason.TARGET_QUIT, returnLeadNow, true);
            } else {
                release(targetSession, owner, ReleaseReason.TARGET_QUIT, settings.returnLeadOnAutoRelease, true);
            }
        }

        Set<UUID> held = sessionsByHolder.get(player.getUniqueId());
        if (held != null && settings.releaseWhenHolderQuits) {
            for (UUID targetUuid : new ArrayList<>(held)) {
                LeashSession session = sessionsByTarget.get(targetUuid);
                if (session != null) {
                    release(session, player, ReleaseReason.HOLDER_QUIT, settings.returnLeadOnAutoRelease, true);
                }
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        if (!settings.restoreTargetLeashOnReconnect || !pendingReconnectLeashes.containsKey(event.getPlayer().getUniqueId())) {
            return;
        }
        UUID targetUuid = event.getPlayer().getUniqueId();
        Bukkit.getScheduler().runTaskLater(this, () -> tryRestoreReconnectLeash(targetUuid), settings.reconnectRestoreDelayTicks);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onDeath(PlayerDeathEvent event) {
        LeashSession session = sessionsByTarget.get(event.getEntity().getUniqueId());
        if (session != null) {
            release(session, onlinePlayer(session.ownerUuid), ReleaseReason.TARGET_DEATH, settings.returnLeadOnAutoRelease, true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onWorldChange(PlayerChangedWorldEvent event) {
        Player player = event.getPlayer();
        LeashSession targetSession = sessionsByTarget.get(player.getUniqueId());
        if (targetSession != null) {
            release(targetSession, onlinePlayer(targetSession.ownerUuid), ReleaseReason.INVALID, settings.returnLeadOnAutoRelease, true);
        }
        Set<UUID> held = sessionsByHolder.get(player.getUniqueId());
        if (held != null) {
            for (UUID targetUuid : new ArrayList<>(held)) {
                LeashSession session = sessionsByTarget.get(targetUuid);
                if (session != null) {
                    release(session, player, ReleaseReason.INVALID, settings.returnLeadOnAutoRelease, true);
                }
            }
        }
    }

    private void createHolderLeash(Player actor, Player target, EquipmentSlot hand) {
        LivingEntity proxy = spawnProxy(target.getLocation());
        if (proxy == null) {
            send(actor, "messages.auto-release.invalid", actor, target, target.getLocation());
            return;
        }

        boolean consumed = consumeLead(actor, hand);
        LeashSession session = new LeashSession(
                target.getUniqueId(),
                actor.getUniqueId(),
                actor.getUniqueId(),
                proxy.getUniqueId(),
                null,
                null,
                LeashMode.HOLDER,
                consumed,
                target.getLocation(),
                target.getWalkSpeed(),
                target.getFlySpeed()
        );
        sessionsByTarget.put(target.getUniqueId(), session);
        sessionsByHolder.computeIfAbsent(actor.getUniqueId(), ignored -> new LinkedHashSet<>()).add(target.getUniqueId());
        targetByProxy.put(proxy.getUniqueId(), target.getUniqueId());
        proxy.setLeashHolder(actor);
        session.lockLocation = target.getLocation();
        applyMovementLock(target);

        send(actor, "messages.leashed.actor", actor, target, target.getLocation());
        send(target, "messages.leashed.target", actor, target, target.getLocation());
        actionbar(actor, "messages.leashed.actionbar-actor", actor, target, target.getLocation());
        actionbar(target, "messages.leashed.actionbar-target", actor, target, target.getLocation());
    }

    private void bindToFence(Player actor, LeashSession session, Block fence) {
        Player target = onlinePlayer(session.targetUuid);
        LivingEntity proxy = proxy(session);
        if (target == null || proxy == null) {
            release(session, actor, ReleaseReason.INVALID, settings.returnLeadOnAutoRelease, true);
            return;
        }

        Entity knot = findOrCreateKnot(fence);
        if (!(knot instanceof LeashHitch)) {
            send(actor, "messages.auto-release.invalid", actor, target, fence.getLocation());
            return;
        }

        sessionsByHolder.computeIfAbsent(session.holderUuid, ignored -> new LinkedHashSet<>()).remove(session.targetUuid);
        if (sessionsByHolder.getOrDefault(session.holderUuid, Collections.emptySet()).isEmpty()) {
            sessionsByHolder.remove(session.holderUuid);
        }

        session.mode = LeashMode.FENCE;
        session.holderUuid = null;
        session.knotUuid = knot.getUniqueId();
        session.anchorLocation = fence.getLocation().add(0.5, 0.5, 0.5);
        session.lockLocation = target.getLocation();
        targetsByKnot.computeIfAbsent(knot.getUniqueId(), ignored -> new LinkedHashSet<>()).add(session.targetUuid);
        proxy.setLeashHolder(knot);

        send(actor, "messages.fence-bound.actor", actor, target, fence.getLocation());
        send(target, "messages.fence-bound.target", actor, target, fence.getLocation());
        actionbar(actor, "messages.fence-bound.actionbar-actor", actor, target, fence.getLocation());
        actionbar(target, "messages.fence-bound.actionbar-target", actor, target, fence.getLocation());
    }

    private void tryReleaseByPlayer(Player actor, LeashSession session) {
        Player target = onlinePlayer(session.targetUuid);
        if (!actor.hasPermission(settings.usePermission) && !actor.hasPermission("guardleash.admin")) {
            send(actor, "messages.no-permission", actor, target, target != null ? target.getLocation() : null);
            return;
        }
        if (settings.onlyOwnerCanRelease && !actor.getUniqueId().equals(session.ownerUuid) && !actor.hasPermission("guardleash.admin")) {
            send(actor, "messages.owner-only-release", actor, target, target != null ? target.getLocation() : null);
            return;
        }
        Player leadRecipient = settings.returnLeadToReleaser ? actor : onlinePlayer(session.ownerUuid);
        release(session, leadRecipient, ReleaseReason.MANUAL, true, true);
        if (target != null) {
            send(actor, "messages.unleashed.actor", actor, target, target.getLocation());
            send(target, "messages.unleashed.target", actor, target, target.getLocation());
            actionbar(actor, "messages.unleashed.actionbar-actor", actor, target, target.getLocation());
            actionbar(target, "messages.unleashed.actionbar-target", actor, target, target.getLocation());
        }
    }

    private void handleKnotInteract(Player actor, LeashHitch hitch, org.bukkit.event.Cancellable event) {
        Set<UUID> targets = targetsByKnot.get(hitch.getUniqueId());
        if (targets == null || targets.isEmpty()) {
            return;
        }
        event.setCancelled(true);
        if (!actor.hasPermission(settings.usePermission) && !actor.hasPermission("guardleash.admin")) {
            send(actor, "messages.no-permission", actor, null, hitch.getLocation());
            return;
        }
        for (UUID targetUuid : new ArrayList<>(targets)) {
            LeashSession session = sessionsByTarget.get(targetUuid);
            if (session != null) {
                release(session, actor, ReleaseReason.MANUAL, true, true);
            }
        }
    }

    private void release(LeashSession session, Player leadRecipient, ReleaseReason reason, boolean returnLead, boolean notifyAuto) {
        sessionsByTarget.remove(session.targetUuid);
        if (session.holderUuid != null) {
            Set<UUID> held = sessionsByHolder.get(session.holderUuid);
            if (held != null) {
                held.remove(session.targetUuid);
                if (held.isEmpty()) {
                    sessionsByHolder.remove(session.holderUuid);
                }
            }
        }
        if (session.knotUuid != null) {
            Set<UUID> targets = targetsByKnot.get(session.knotUuid);
            if (targets != null) {
                targets.remove(session.targetUuid);
                if (targets.isEmpty()) {
                    targetsByKnot.remove(session.knotUuid);
                    Entity knot = Bukkit.getEntity(session.knotUuid);
                    if (knot != null && isMarked(knot)) {
                        knot.remove();
                    }
                }
            }
        }

        LivingEntity proxy = proxy(session);
        if (proxy != null) {
            targetByProxy.remove(proxy.getUniqueId());
            proxy.remove();
        }

        if (returnLead && session.consumedLead && leadRecipient != null && leadRecipient.isOnline()) {
            giveLeadOrQueue(leadRecipient, 1);
        }

        Player target = onlinePlayer(session.targetUuid);
        if (target != null && target.isOnline()) {
            restoreMovement(target, session);
        }
        if (notifyAuto && reason.autoMessagePath != null) {
            Player owner = onlinePlayer(session.ownerUuid);
            if (owner != null && owner.isOnline()) {
                send(owner, reason.autoMessagePath, owner, target, target != null ? target.getLocation() : null);
            }
            if (target != null && target.isOnline() && reason == ReleaseReason.HOLDER_QUIT) {
                send(target, reason.autoMessagePath, owner, target, target.getLocation());
            }
        }
    }

    private boolean canRestoreOnReconnect(LeashSession session) {
        if (!settings.restoreTargetLeashOnReconnect) {
            return false;
        }
        if (session.mode == LeashMode.HOLDER) {
            return session.holderUuid != null;
        }
        return session.mode == LeashMode.FENCE
                && settings.fenceEnabled
                && session.anchorLocation != null
                && session.lockLocation != null;
    }

    private void tryRestoreReconnectLeash(UUID targetUuid) {
        PendingReconnectLeash pending = pendingReconnectLeashes.get(targetUuid);
        if (pending == null) {
            return;
        }
        if (isReconnectExpired(pending)) {
            finishPendingReconnectWithoutRestore(pending, true);
            return;
        }

        Player target = onlinePlayer(pending.targetUuid);
        Player holder = onlinePlayer(pending.holderUuid);
        if (target == null || !target.isOnline()) {
            return;
        }
        if (sessionsByTarget.containsKey(target.getUniqueId())) {
            finishPendingReconnectWithoutRestore(pending, false);
            return;
        }

        if (pending.mode == LeashMode.FENCE) {
            tryRestoreFenceReconnectLeash(pending, target);
            return;
        }

        if (holder == null || !holder.isOnline() || !holder.getWorld().equals(target.getWorld())
                || holder.getLocation().distance(target.getLocation()) > settings.reconnectRestoreRadius) {
            finishPendingReconnectWithoutRestore(pending, true);
            return;
        }

        LivingEntity proxy = spawnProxy(target.getLocation());
        if (proxy == null) {
            finishPendingReconnectWithoutRestore(pending, true);
            return;
        }

        boolean consumedLead = pending.consumedLead;
        if (pending.leadReturnedOnQuit && pending.consumedLead) {
            consumedLead = takeLeadForReconnect(holder);
        }

        LeashSession session = new LeashSession(
                target.getUniqueId(),
                pending.ownerUuid,
                holder.getUniqueId(),
                proxy.getUniqueId(),
                null,
                null,
                LeashMode.HOLDER,
                consumedLead,
                target.getLocation(),
                target.getWalkSpeed(),
                target.getFlySpeed()
        );
        pendingReconnectLeashes.remove(target.getUniqueId());
        sessionsByTarget.put(target.getUniqueId(), session);
        sessionsByHolder.computeIfAbsent(holder.getUniqueId(), ignored -> new LinkedHashSet<>()).add(target.getUniqueId());
        targetByProxy.put(proxy.getUniqueId(), target.getUniqueId());
        proxy.setLeashHolder(holder);
        applyMovementLock(target);

        send(holder, "messages.reconnected.actor", holder, target, target.getLocation());
        send(target, "messages.reconnected.target", holder, target, target.getLocation());
        actionbar(holder, "messages.reconnected.actionbar-actor", holder, target, target.getLocation());
        actionbar(target, "messages.reconnected.actionbar-target", holder, target, target.getLocation());
    }

    private void tryRestoreFenceReconnectLeash(PendingReconnectLeash pending, Player target) {
        if (pending.anchorLocation == null || pending.anchorLocation.getWorld() == null) {
            finishPendingReconnectWithoutRestore(pending, true);
            return;
        }

        Block fence = pending.anchorLocation.getBlock();
        if (!isFence(fence)) {
            finishPendingReconnectWithoutRestore(pending, true);
            return;
        }

        LivingEntity proxy = spawnProxy(target.getLocation());
        if (proxy == null) {
            finishPendingReconnectWithoutRestore(pending, true);
            return;
        }

        Entity knot = findOrCreateKnot(fence);
        if (!(knot instanceof LeashHitch)) {
            proxy.remove();
            finishPendingReconnectWithoutRestore(pending, true);
            return;
        }

        Location lockLocation = pending.lockLocation == null ? target.getLocation() : pending.lockLocation.clone();
        LeashSession session = new LeashSession(
                target.getUniqueId(),
                pending.ownerUuid,
                null,
                proxy.getUniqueId(),
                knot.getUniqueId(),
                pending.anchorLocation.clone(),
                LeashMode.FENCE,
                pending.consumedLead,
                lockLocation,
                target.getWalkSpeed(),
                target.getFlySpeed()
        );

        pendingReconnectLeashes.remove(target.getUniqueId());
        sessionsByTarget.put(target.getUniqueId(), session);
        targetsByKnot.computeIfAbsent(knot.getUniqueId(), ignored -> new LinkedHashSet<>()).add(target.getUniqueId());
        targetByProxy.put(proxy.getUniqueId(), target.getUniqueId());
        proxy.setLeashHolder(knot);
        applyMovementLock(target);
        if (lockLocation.getWorld() != null && lockLocation.getWorld().equals(target.getWorld())) {
            moveTarget(target, session.lockLocationWithView(target.getLocation()));
        }

        Player owner = onlinePlayer(pending.ownerUuid);
        if (owner != null && owner.isOnline()) {
            send(owner, "messages.reconnected-fence.actor", owner, target, fence.getLocation());
            actionbar(owner, "messages.reconnected-fence.actionbar-actor", owner, target, fence.getLocation());
        }
        send(target, "messages.reconnected-fence.target", owner, target, fence.getLocation());
        actionbar(target, "messages.reconnected-fence.actionbar-target", owner, target, fence.getLocation());
    }

    private void finishPendingReconnectWithoutRestore(PendingReconnectLeash pending, boolean notifyOwner) {
        pendingReconnectLeashes.remove(pending.targetUuid);
        if (pending.consumedLead && !pending.leadReturnedOnQuit && settings != null && settings.returnLeadOnAutoRelease) {
            returnLeadToUuid(pending.ownerUuid, 1);
        }
        if (notifyOwner) {
            Player owner = onlinePlayer(pending.ownerUuid);
            Player target = onlinePlayer(pending.targetUuid);
            if (owner != null && owner.isOnline()) {
                send(owner, "messages.reconnect-failed", owner, target, target != null ? target.getLocation() : null);
            }
        }
    }

    private boolean isReconnectExpired(PendingReconnectLeash pending) {
        return settings.reconnectTimeoutMillis > 0L
                && System.currentTimeMillis() - pending.createdAtMillis > settings.reconnectTimeoutMillis;
    }

    private void tickSessions() {
        for (LeashSession session : new ArrayList<>(sessionsByTarget.values())) {
            Player target = onlinePlayer(session.targetUuid);
            LivingEntity proxy = proxy(session);
            if (target == null || !target.isOnline()) {
                release(session, onlinePlayer(session.ownerUuid), ReleaseReason.TARGET_QUIT, settings.returnLeadOnAutoRelease, true);
                continue;
            }
            if (proxy == null || !proxy.isValid()) {
                release(session, onlinePlayer(session.ownerUuid), ReleaseReason.INVALID, settings.returnLeadOnAutoRelease, true);
                continue;
            }

            if (session.mode == LeashMode.HOLDER) {
                tickHolderSession(session, target, proxy);
            } else {
                tickFenceSession(session, target, proxy);
            }
        }
    }

    private void tickHolderSession(LeashSession session, Player target, LivingEntity proxy) {
        Player holder = onlinePlayer(session.holderUuid);
        if (holder == null || !holder.isOnline()) {
            release(session, onlinePlayer(session.ownerUuid), ReleaseReason.HOLDER_QUIT, settings.returnLeadOnAutoRelease, true);
            return;
        }
        if (!holder.getWorld().equals(target.getWorld())) {
            release(session, holder, ReleaseReason.INVALID, settings.returnLeadOnAutoRelease, true);
            return;
        }

        double distance = target.getLocation().distance(holder.getLocation());
        if (settings.releaseIfTooFar && distance > settings.releaseDistance) {
            release(session, holder, ReleaseReason.TOO_FAR, settings.returnLeadOnAutoRelease, true);
            return;
        }

        applyMovementLock(target);
        target.setSprinting(false);

        if (distance > settings.followDistance) {
            pullTargetTowardHolder(target, holder, distance);
        } else if (settings.dampenNearHolder) {
            Vector velocity = target.getVelocity();
            velocity.setX(velocity.getX() * 0.35D);
            velocity.setZ(velocity.getZ() * 0.35D);
            target.setVelocity(velocity);
        }

        if (target.getLocation().getBlock().getType().isSolid()) {
            Vector velocity = target.getVelocity();
            velocity.setY(Math.max(velocity.getY(), settings.stepUpVelocity));
            target.setVelocity(velocity);
        }

        session.lockLocation = target.getLocation();
        syncProxy(proxy, target.getLocation());
        ensureLeashHolder(session);
    }

    private void pullTargetTowardHolder(Player target, Player holder, double distance) {
        Vector direction = holder.getLocation().toVector().subtract(target.getLocation().toVector());
        Vector horizontal = direction.clone().setY(0.0D);
        if (horizontal.lengthSquared() < 0.0001D) {
            return;
        }

        Vector normal = horizontal.normalize();
        double excess = Math.max(0.0D, distance - settings.followDistance);
        double speed = Math.min(settings.maxStepPerUpdate, settings.pullBaseSpeed + excess * settings.pullSpeedPerBlock);

        Vector velocity = target.getVelocity();
        velocity.setX(normal.getX() * speed);
        velocity.setZ(normal.getZ() * speed);

        if (shouldStepUp(target, normal, holder)) {
            velocity.setY(Math.max(velocity.getY(), settings.stepUpVelocity));
            target.setFallDistance(0.0F);
        }

        target.setVelocity(velocity);
    }

    private boolean shouldStepUp(Player target, Vector direction, Player holder) {
        if (!target.isOnGround()) {
            return false;
        }
        if (holder.getLocation().getBlockY() > target.getLocation().getBlockY()) {
            return true;
        }

        Location front = target.getLocation().clone().add(direction.clone().multiply(settings.stepCheckDistance));
        Block feet = front.getBlock();
        Block body = front.clone().add(0.0D, 1.0D, 0.0D).getBlock();
        Block head = front.clone().add(0.0D, 2.0D, 0.0D).getBlock();
        return feet.getType().isSolid() && body.isPassable() && head.isPassable();
    }

    private void applyMovementLock(Player target) {
        if (!settings.disableTargetWalkSpeed) {
            return;
        }
        if (target.getWalkSpeed() != 0.0F) {
            target.setWalkSpeed(0.0F);
        }
        if (target.getFlySpeed() != 0.0F) {
            target.setFlySpeed(0.0F);
        }
    }

    private void restoreMovement(Player target, LeashSession session) {
        if (!settings.disableTargetWalkSpeed) {
            return;
        }
        try {
            target.setWalkSpeed(session.originalWalkSpeed);
            target.setFlySpeed(session.originalFlySpeed);
        } catch (IllegalArgumentException ignored) {
            target.setWalkSpeed(0.2F);
            target.setFlySpeed(0.1F);
        }
    }

    private Location positionWithView(Location positionSource, Location viewSource) {
        Location result = positionSource.clone();
        result.setYaw(viewSource.getYaw());
        result.setPitch(viewSource.getPitch());
        return result;
    }

    private double horizontalDistance(Location first, Location second) {
        if (first.getWorld() != second.getWorld()) {
            return Double.MAX_VALUE;
        }
        double dx = first.getX() - second.getX();
        double dz = first.getZ() - second.getZ();
        return Math.sqrt(dx * dx + dz * dz);
    }

    private void tickFenceSession(LeashSession session, Player target, LivingEntity proxy) {
        if (session.lockLocation == null || session.lockLocation.getWorld() == null || !session.lockLocation.getWorld().equals(target.getWorld())) {
            release(session, onlinePlayer(session.ownerUuid), ReleaseReason.INVALID, settings.returnLeadOnAutoRelease, true);
            return;
        }

        applyMovementLock(target);

        Location locked = session.lockLocationWithView(target.getLocation());
        if (!samePosition(target.getLocation(), locked)) {
            moveTarget(target, locked);
        }

        syncProxy(proxy, target.getLocation());
        ensureLeashHolder(session);
    }

    private void ensureLeashHolder(LeashSession session) {
        LivingEntity proxy = proxy(session);
        if (proxy == null) {
            return;
        }
        Entity holder = null;
        if (session.mode == LeashMode.HOLDER) {
            holder = onlinePlayer(session.holderUuid);
        } else if (session.knotUuid != null) {
            holder = Bukkit.getEntity(session.knotUuid);
        }
        if (holder != null && holder.isValid()) {
            try {
                if (!proxy.isLeashed() || !holder.equals(proxy.getLeashHolder())) {
                    proxy.setLeashHolder(holder);
                }
            } catch (IllegalStateException ignored) {
                proxy.setLeashHolder(holder);
            }
        }
    }

    private void moveTarget(Player target, Location location) {
        internalMoves.add(target.getUniqueId());
        try {
            target.teleport(location, PlayerTeleportEvent.TeleportCause.PLUGIN);
            target.setFallDistance(0.0F);
        } finally {
            Bukkit.getScheduler().runTask(this, () -> internalMoves.remove(target.getUniqueId()));
        }
    }

    private void syncProxy(LivingEntity proxy, Location playerLocation) {
        Location proxyLocation = playerLocation.clone().add(0.0, settings.proxyYOffset, 0.0);
        proxy.teleport(proxyLocation);
        proxy.setFallDistance(0.0F);
    }

    private LivingEntity spawnProxy(Location location) {
        World world = location.getWorld();
        if (world == null) {
            return null;
        }

        EntityType type = settings.proxyEntityType;
        Entity spawned = world.spawnEntity(location.clone().add(0.0, settings.proxyYOffset, 0.0), type);
        if (!(spawned instanceof LivingEntity living)) {
            spawned.remove();
            return null;
        }

        living.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        living.setInvisible(true);
        living.setInvulnerable(true);
        living.setSilent(true);
        living.setCollidable(false);
        living.setGravity(false);
        living.setCanPickupItems(false);
        living.setRemoveWhenFarAway(false);
        living.setPersistent(false);
        living.addPotionEffect(new PotionEffect(PotionEffectType.INVISIBILITY, Integer.MAX_VALUE, 1, false, false, false));

        if (living instanceof Mob mob) {
            mob.setAware(false);
            mob.setAI(false);
            mob.setTarget(null);
        }
        if (living instanceof Zombie zombie) {
            zombie.setBaby(settings.useBabyZombieProxy);
            zombie.setShouldBurnInDay(false);
            zombie.setArmsRaised(false);
        }
        if (living.getEquipment() != null) {
            living.getEquipment().clear();
        }
        return living;
    }

    private Entity findOrCreateKnot(Block fence) {
        Location anchor = fence.getLocation().add(0.5, 0.5, 0.5);
        for (Entity entity : fence.getWorld().getNearbyEntities(anchor, 0.4, 0.4, 0.4)) {
            if (entity instanceof LeashHitch && isMarked(entity)) {
                return entity;
            }
        }
        Entity knot = fence.getWorld().spawnEntity(anchor, EntityType.LEASH_KNOT);
        knot.getPersistentDataContainer().set(markerKey, PersistentDataType.BYTE, (byte) 1);
        return knot;
    }

    private void cleanupMarkedEntities() {
        for (World world : Bukkit.getWorlds()) {
            for (Entity entity : world.getEntities()) {
                if (isMarked(entity)) {
                    entity.remove();
                }
            }
        }
    }

    private boolean consumeLead(Player player, EquipmentSlot hand) {
        if (!settings.consumeLead) {
            return false;
        }
        if (!settings.consumeLeadInCreative && player.getGameMode() == GameMode.CREATIVE) {
            return false;
        }
        ItemStack item = itemInHand(player, hand);
        if (!isLeashItem(item)) {
            return false;
        }
        item.setAmount(item.getAmount() - 1);
        return true;
    }

    private boolean takeLeadForReconnect(Player player) {
        if (!settings.consumeLead) {
            return false;
        }
        if (!settings.consumeLeadInCreative && player.getGameMode() == GameMode.CREATIVE) {
            return false;
        }

        PlayerInventory inventory = player.getInventory();
        ItemStack mainHand = inventory.getItemInMainHand();
        if (isLeashItem(mainHand)) {
            mainHand.setAmount(mainHand.getAmount() - 1);
            return true;
        }
        ItemStack offHand = inventory.getItemInOffHand();
        if (settings.allowOffhand && isLeashItem(offHand)) {
            offHand.setAmount(offHand.getAmount() - 1);
            return true;
        }

        for (ItemStack item : inventory.getStorageContents()) {
            if (!isLeashItem(item)) {
                continue;
            }
            item.setAmount(item.getAmount() - 1);
            return true;
        }
        return false;
    }

    private void giveLeadOrQueue(Player player, int amount) {
        Map<Integer, ItemStack> leftovers = player.getInventory().addItem(new ItemStack(settings.leashMaterial, amount));
        int missing = 0;
        for (ItemStack stack : leftovers.values()) {
            missing += stack.getAmount();
        }
        if (missing <= 0) {
            return;
        }
        if (settings.keepPendingLeadsWhenInventoryFull) {
            pendingLeadReturns.merge(player.getUniqueId(), missing, Integer::sum);
            send(player, "messages.pending-lead", player, null, player.getLocation());
        }
    }

    private void returnLeadToUuid(UUID playerUuid, int amount) {
        if (amount <= 0 || playerUuid == null) {
            return;
        }
        Player player = onlinePlayer(playerUuid);
        if (player != null && player.isOnline()) {
            giveLeadOrQueue(player, amount);
            return;
        }
        if (settings == null || settings.keepPendingLeadsWhenInventoryFull) {
            pendingLeadReturns.merge(playerUuid, amount, Integer::sum);
        }
    }

    private void tickPendingTasks() {
        tryReturnPendingLeads();
        cleanupExpiredReconnectLeashes();
    }

    private void cleanupExpiredReconnectLeashes() {
        for (PendingReconnectLeash pending : new ArrayList<>(pendingReconnectLeashes.values())) {
            if (isReconnectExpired(pending)) {
                finishPendingReconnectWithoutRestore(pending, true);
            }
        }
    }

    private void tryReturnPendingLeads() {
        for (UUID uuid : new ArrayList<>(pendingLeadReturns.keySet())) {
            Player player = onlinePlayer(uuid);
            if (player == null || !player.isOnline()) {
                continue;
            }
            int amount = pendingLeadReturns.getOrDefault(uuid, 0);
            if (amount <= 0) {
                pendingLeadReturns.remove(uuid);
                continue;
            }
            Map<Integer, ItemStack> leftovers = player.getInventory().addItem(new ItemStack(settings.leashMaterial, amount));
            int missing = 0;
            for (ItemStack stack : leftovers.values()) {
                missing += stack.getAmount();
            }
            if (missing != amount) {
                send(player, "messages.pending-lead-returned", player, null, player.getLocation());
            }
            if (missing <= 0) {
                pendingLeadReturns.remove(uuid);
            } else {
                pendingLeadReturns.put(uuid, missing);
            }
        }
    }

    private boolean isValidLeashHand(Player player, EquipmentSlot hand) {
        if (hand == null) {
            return false;
        }
        if (hand == EquipmentSlot.OFF_HAND && !settings.allowOffhand) {
            return false;
        }
        if (hand == EquipmentSlot.OFF_HAND && isLeashItem(player.getInventory().getItemInMainHand())) {
            return false;
        }
        return true;
    }

    private ItemStack itemInHand(Player player, EquipmentSlot hand) {
        PlayerInventory inventory = player.getInventory();
        return hand == EquipmentSlot.OFF_HAND ? inventory.getItemInOffHand() : inventory.getItemInMainHand();
    }

    private boolean isLeashItem(ItemStack item) {
        return item != null && item.getType() == settings.leashMaterial && item.getAmount() > 0;
    }

    private boolean isFence(Block block) {
        if (Tag.FENCES.isTagged(block.getType())) {
            return true;
        }
        return settings.extraFenceMaterials.contains(block.getType());
    }

    private boolean samePosition(Location first, Location second) {
        return first.getWorld() == second.getWorld()
                && Math.abs(first.getX() - second.getX()) < 0.0001
                && Math.abs(first.getY() - second.getY()) < 0.0001
                && Math.abs(first.getZ() - second.getZ()) < 0.0001;
    }

    private boolean isProxy(Entity entity) {
        return entity != null && targetByProxy.containsKey(entity.getUniqueId());
    }

    private boolean isMarked(Entity entity) {
        return entity != null && entity.getPersistentDataContainer().has(markerKey, PersistentDataType.BYTE);
    }

    private LivingEntity proxy(LeashSession session) {
        Entity entity = Bukkit.getEntity(session.proxyUuid);
        return entity instanceof LivingEntity living ? living : null;
    }

    private Player onlinePlayer(UUID uuid) {
        return uuid == null ? null : Bukkit.getPlayer(uuid);
    }

    private Player playerDamager(Entity damager) {
        if (damager instanceof Player player) {
            return player;
        }
        if (damager instanceof Projectile projectile) {
            ProjectileSource source = projectile.getShooter();
            if (source instanceof Player player) {
                return player;
            }
        }
        return null;
    }

    private void send(CommandSender sender, String path, Player actor, Player target, Location location) {
        String raw = getConfig().getString(path, "");
        if (raw == null || raw.isBlank() || sender == null) {
            return;
        }
        sender.sendMessage(format(raw, actor, target, location));
    }

    private void actionbar(Player player, String path, Player actor, Player target, Location location) {
        String raw = getConfig().getString(path, "");
        if (raw == null || raw.isBlank() || player == null) {
            return;
        }
        player.sendActionBar(format(raw, actor, target, location));
    }

    private Component format(String raw, Player actor, Player target, Location location) {
        String prefix = getConfig().getString("messages.prefix", "");
        String anchor = location == null
                ? ""
                : "%s %.1f %.1f %.1f".formatted(location.getWorld() == null ? "world" : location.getWorld().getName(), location.getX(), location.getY(), location.getZ());
        String result = raw
                .replace("<prefix>", prefix == null ? "" : prefix)
                .replace("<actor>", actor == null ? "Система" : actor.getName())
                .replace("<target>", target == null ? "игрок" : target.getName())
                .replace("<anchor>", anchor);
        return miniMessage.deserialize(result);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) {
            sender.sendMessage(format("<prefix><#fe9882>/guardleash reload</#fe9882> <#7f4841>|</#7f4841> <#fe9882>/guardleash release <player></#fe9882>", null, null, null));
            return true;
        }
        if (args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("guardleash.admin")) {
                send(sender, "messages.no-permission", sender instanceof Player player ? player : null, null, null);
                return true;
            }
            reloadSettings();
            send(sender, "messages.config-reloaded", sender instanceof Player player ? player : null, null, null);
            return true;
        }
        if (args[0].equalsIgnoreCase("release") && args.length >= 2) {
            if (!sender.hasPermission("guardleash.admin")) {
                send(sender, "messages.no-permission", sender instanceof Player player ? player : null, null, null);
                return true;
            }
            Player target = Bukkit.getPlayerExact(args[1]);
            if (target == null) {
                sender.sendMessage(format("<prefix><#fe9882>Игрок не найден.</#fe9882>", null, null, null));
                return true;
            }
            LeashSession session = sessionsByTarget.get(target.getUniqueId());
            if (session == null) {
                send(sender, "messages.not-leashed", sender instanceof Player player ? player : null, target, target.getLocation());
                return true;
            }
            Player recipient = sender instanceof Player player ? player : onlinePlayer(session.ownerUuid);
            release(session, recipient, ReleaseReason.MANUAL, true, true);
            send(sender, "messages.force-released", sender instanceof Player player ? player : null, target, target.getLocation());
            return true;
        }
        return false;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>();
            if (sender.hasPermission("guardleash.admin")) {
                options.add("reload");
                options.add("release");
            }
            List<String> result = new ArrayList<>();
            StringUtil.copyPartialMatches(args[0], options, result);
            Collections.sort(result);
            return result;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("release") && sender.hasPermission("guardleash.admin")) {
            List<String> names = sessionsByTarget.keySet().stream()
                    .map(Bukkit::getPlayer)
                    .filter(Objects::nonNull)
                    .map(Player::getName)
                    .toList();
            List<String> result = new ArrayList<>();
            StringUtil.copyPartialMatches(args[1], names, result);
            Collections.sort(result);
            return result;
        }
        return Collections.emptyList();
    }

    private enum LeashMode {
        HOLDER,
        FENCE
    }

    private enum ReleaseReason {
        MANUAL(null),
        TARGET_QUIT("messages.auto-release.target-quit"),
        HOLDER_QUIT("messages.auto-release.holder-quit"),
        HOLDER_TELEPORT("messages.auto-release.holder-teleport"),
        TARGET_DEATH("messages.auto-release.invalid"),
        TOO_FAR("messages.auto-release.too-far"),
        INVALID("messages.auto-release.invalid"),
        PLUGIN_DISABLE(null);

        private final String autoMessagePath;

        ReleaseReason(String autoMessagePath) {
            this.autoMessagePath = autoMessagePath;
        }
    }

    private static final class PendingReconnectLeash {
        private final UUID targetUuid;
        private final UUID ownerUuid;
        private final UUID holderUuid;
        private final LeashMode mode;
        private final Location anchorLocation;
        private final Location lockLocation;
        private final boolean consumedLead;
        private final boolean leadReturnedOnQuit;
        private final long createdAtMillis;

        private PendingReconnectLeash(UUID targetUuid, UUID ownerUuid, UUID holderUuid, LeashMode mode,
                                      Location anchorLocation, Location lockLocation, boolean consumedLead,
                                      boolean leadReturnedOnQuit, long createdAtMillis) {
            this.targetUuid = targetUuid;
            this.ownerUuid = ownerUuid;
            this.holderUuid = holderUuid;
            this.mode = mode;
            this.anchorLocation = anchorLocation == null ? null : anchorLocation.clone();
            this.lockLocation = lockLocation == null ? null : lockLocation.clone();
            this.consumedLead = consumedLead;
            this.leadReturnedOnQuit = leadReturnedOnQuit;
            this.createdAtMillis = createdAtMillis;
        }

        private static PendingReconnectLeash from(LeashSession session, long createdAtMillis, boolean leadReturnedOnQuit) {
            return new PendingReconnectLeash(
                    session.targetUuid,
                    session.ownerUuid,
                    session.holderUuid,
                    session.mode,
                    session.anchorLocation,
                    session.lockLocation,
                    session.consumedLead,
                    leadReturnedOnQuit,
                    createdAtMillis
            );
        }
    }

    private static final class LeashSession {
        private final UUID targetUuid;
        private final UUID ownerUuid;
        private UUID holderUuid;
        private final UUID proxyUuid;
        private UUID knotUuid;
        private Location anchorLocation;
        private LeashMode mode;
        private final boolean consumedLead;
        private Location lockLocation;
        private final float originalWalkSpeed;
        private final float originalFlySpeed;

        private LeashSession(UUID targetUuid, UUID ownerUuid, UUID holderUuid, UUID proxyUuid, UUID knotUuid,
                             Location anchorLocation, LeashMode mode, boolean consumedLead, Location lockLocation,
                             float originalWalkSpeed, float originalFlySpeed) {
            this.targetUuid = targetUuid;
            this.ownerUuid = ownerUuid;
            this.holderUuid = holderUuid;
            this.proxyUuid = proxyUuid;
            this.knotUuid = knotUuid;
            this.anchorLocation = anchorLocation;
            this.mode = mode;
            this.consumedLead = consumedLead;
            this.lockLocation = lockLocation;
            this.originalWalkSpeed = originalWalkSpeed;
            this.originalFlySpeed = originalFlySpeed;
        }

        private Location lockLocationWithView(Location viewSource) {
            Location result = lockLocation.clone();
            result.setYaw(viewSource.getYaw());
            result.setPitch(viewSource.getPitch());
            return result;
        }
    }

    private static final class Settings {
        private final String usePermission;
        private final String bypassPermission;
        private final Material leashMaterial;
        private final boolean consumeLead;
        private final boolean consumeLeadInCreative;
        private final boolean allowOffhand;
        private final boolean onlyOwnerCanRelease;
        private final boolean returnLeadToReleaser;
        private final long updateIntervalTicks;
        private final double proxyYOffset;

        private final boolean fenceEnabled;
        private final boolean bindAllHeldTargetsToFence;
        private final Set<Material> extraFenceMaterials;

        private final double followDistance;
        private final double maxStepPerUpdate;
        private final double pullBaseSpeed;
        private final double pullSpeedPerBlock;
        private final double stepUpVelocity;
        private final double stepCheckDistance;
        private final double movementAwayTolerance;
        private final boolean dampenNearHolder;
        private final boolean releaseIfTooFar;
        private final double releaseDistance;

        private final boolean preventMovement;
        private final boolean allowLookingAround;
        private final boolean disableTargetWalkSpeed;
        private final boolean preventJump;
        private final boolean preventInventoryInteract;
        private final boolean preventDamage;
        private final boolean preventInteract;
        private final boolean preventBlockBreak;
        private final boolean preventBlockPlace;
        private final boolean preventItemDrop;
        private final boolean preventTeleport;

        private final boolean releaseWhenTargetQuits;
        private final boolean releaseWhenHolderQuits;
        private final boolean restoreTargetLeashOnReconnect;
        private final double reconnectRestoreRadius;
        private final long reconnectTimeoutMillis;
        private final long reconnectRestoreDelayTicks;
        private final boolean returnLeadOnAutoRelease;
        private final boolean keepPendingLeadsWhenInventoryFull;
        private final EntityType proxyEntityType;
        private final boolean useBabyZombieProxy;

        private Settings(JavaPlugin plugin) {
            usePermission = plugin.getConfig().getString("settings.use-permission", "guardleash.use");
            bypassPermission = plugin.getConfig().getString("settings.bypass-permission", "guardleash.bypass");
            leashMaterial = material(plugin.getConfig().getString("settings.leash-item", "LEAD"), Material.LEAD);
            consumeLead = plugin.getConfig().getBoolean("settings.consume-lead", true);
            consumeLeadInCreative = plugin.getConfig().getBoolean("settings.consume-lead-in-creative", false);
            allowOffhand = plugin.getConfig().getBoolean("settings.allow-offhand", true);
            onlyOwnerCanRelease = plugin.getConfig().getBoolean("settings.only-owner-can-release", false);
            returnLeadToReleaser = plugin.getConfig().getBoolean("settings.return-lead-to-releaser", true);
            updateIntervalTicks = Math.max(1L, plugin.getConfig().getLong("settings.update-interval-ticks", 2L));
            proxyYOffset = plugin.getConfig().getDouble("settings.proxy-y-offset", -0.35D);

            fenceEnabled = plugin.getConfig().getBoolean("fence-leash.enabled", true);
            bindAllHeldTargetsToFence = plugin.getConfig().getBoolean("fence-leash.bind-all-held-targets", true);
            extraFenceMaterials = new HashSet<>();
            for (String name : plugin.getConfig().getStringList("fence-leash.extra-fence-materials")) {
                Material extra = Material.matchMaterial(name);
                if (extra != null) {
                    extraFenceMaterials.add(extra);
                }
            }

            followDistance = Math.max(0.5D, plugin.getConfig().getDouble("holder-leash.follow-distance", 2.8D));
            maxStepPerUpdate = Math.max(0.05D, plugin.getConfig().getDouble("holder-leash.max-step-per-update", 0.65D));
            pullBaseSpeed = Math.max(0.01D, plugin.getConfig().getDouble("holder-leash.pull-base-speed", 0.22D));
            pullSpeedPerBlock = Math.max(0.0D, plugin.getConfig().getDouble("holder-leash.pull-speed-per-block", 0.09D));
            stepUpVelocity = Math.max(0.0D, plugin.getConfig().getDouble("holder-leash.step-up-velocity", 0.42D));
            stepCheckDistance = Math.max(0.15D, plugin.getConfig().getDouble("holder-leash.step-check-distance", 0.75D));
            movementAwayTolerance = Math.max(0.0D, plugin.getConfig().getDouble("holder-leash.movement-away-tolerance", 0.08D));
            dampenNearHolder = plugin.getConfig().getBoolean("holder-leash.dampen-near-holder", true);
            releaseIfTooFar = plugin.getConfig().getBoolean("holder-leash.release-if-too-far", false);
            releaseDistance = Math.max(followDistance + 1.0D, plugin.getConfig().getDouble("holder-leash.release-distance", 24.0D));

            preventMovement = plugin.getConfig().getBoolean("restrictions.prevent-movement", true);
            allowLookingAround = plugin.getConfig().getBoolean("restrictions.allow-looking-around", true);
            disableTargetWalkSpeed = plugin.getConfig().getBoolean("restrictions.disable-target-walk-speed", true);
            preventJump = plugin.getConfig().getBoolean("restrictions.prevent-jump", true);
            preventInventoryInteract = plugin.getConfig().getBoolean("restrictions.prevent-inventory-interact", true);
            preventDamage = plugin.getConfig().getBoolean("restrictions.prevent-damage", true);
            preventInteract = plugin.getConfig().getBoolean("restrictions.prevent-interact", true);
            preventBlockBreak = plugin.getConfig().getBoolean("restrictions.prevent-block-break", true);
            preventBlockPlace = plugin.getConfig().getBoolean("restrictions.prevent-block-place", true);
            preventItemDrop = plugin.getConfig().getBoolean("restrictions.prevent-item-drop", true);
            preventTeleport = plugin.getConfig().getBoolean("restrictions.prevent-teleport", true);

            releaseWhenTargetQuits = plugin.getConfig().getBoolean("safety.release-when-target-quits", true);
            releaseWhenHolderQuits = plugin.getConfig().getBoolean("safety.release-when-holder-quits", true);
            restoreTargetLeashOnReconnect = plugin.getConfig().getBoolean("safety.restore-target-leash-on-reconnect", true);
            reconnectRestoreRadius = Math.max(0.0D, plugin.getConfig().getDouble("safety.reconnect-restore-radius", 16.0D));
            reconnectTimeoutMillis = Math.max(0L, plugin.getConfig().getLong("safety.reconnect-timeout-seconds", 300L)) * 1000L;
            reconnectRestoreDelayTicks = Math.max(1L, plugin.getConfig().getLong("safety.reconnect-restore-delay-ticks", 10L));
            returnLeadOnAutoRelease = plugin.getConfig().getBoolean("safety.return-lead-on-auto-release", true);
            keepPendingLeadsWhenInventoryFull = plugin.getConfig().getBoolean("safety.keep-pending-leads-when-inventory-full", true);
            proxyEntityType = livingEntityType(plugin.getConfig().getString("proxy-entity.type", "ZOMBIE"));
            useBabyZombieProxy = plugin.getConfig().getBoolean("proxy-entity.use-baby-zombie", true);
        }

        private static Settings from(JavaPlugin plugin) {
            return new Settings(plugin);
        }

        private static Material material(String name, Material fallback) {
            Material matched = Material.matchMaterial(name == null ? "" : name);
            return matched == null ? fallback : matched;
        }

        private static EntityType livingEntityType(String name) {
            if (name != null) {
                try {
                    EntityType type = EntityType.valueOf(name.toUpperCase(Locale.ROOT));
                    Class<? extends Entity> entityClass = type.getEntityClass();
                    if (entityClass != null && LivingEntity.class.isAssignableFrom(entityClass)) {
                        return type;
                    }
                } catch (IllegalArgumentException ignored) {
                }
            }
            return EntityType.ZOMBIE;
        }
    }
}
